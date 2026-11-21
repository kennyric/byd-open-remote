package com.bydwatch.companion.lab;

import androidx.annotation.VisibleForTesting;

import com.bydwatch.companion.BuildConfig;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Synchronous calls to the car's Overdrive HTTP API.
 *
 * <p>Callers run these off the main thread. Every result is an
 * {@link Outcome}: either a parsed value, an error message, or an auth failure
 * that means the stored JWT is gone.
 */
public final class OverdriveClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** One HTTP round trip. Swappable in tests. */
    public interface Transport {
        Response execute(String method, String url, String jwt, String body) throws IOException;
    }

    /** Raw HTTP result. */
    public static final class Response {
        public final int code;
        public final String body;

        public Response(int code, String body) {
            this.code = code;
            this.body = body != null ? body : "";
        }
    }

    /** Parsed result of one API call. */
    public static final class Outcome<T> {
        public final T value;
        public final int code;
        public final String error;

        Outcome(T value, int code, String error) {
            this.value = value;
            this.code = code;
            this.error = error;
        }

        public boolean ok() {
            return code >= 200 && code < 300 && error == null;
        }

        public boolean authFailed() {
            return OverdriveApi.isAuthFailure(code);
        }

        public boolean unreachable() {
            return code == 0;
        }
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    /** Upper bound for a car response; the real payloads are a few KB. */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private static final Transport DEFAULT_TRANSPORT = (method, url, jwt, body) -> {
        Request.Builder builder = new Request.Builder().url(url);
        if (jwt != null && !jwt.isEmpty()) {
            builder.header("Authorization", "Bearer " + jwt);
        }
        if ("POST".equals(method)) {
            builder.post(RequestBody.create(body != null ? body : "{}", JSON));
        } else {
            builder.get();
        }
        try (okhttp3.Response response = CLIENT.newCall(builder.build()).execute()) {
            return new Response(response.code(), readBody(response.body()));
        }
    };

    private static String readBody(okhttp3.ResponseBody body) throws java.io.IOException {
        if (body == null) return "";
        long declared = body.contentLength();
        if (declared > MAX_BODY_BYTES) {
            throw new java.io.IOException("Overdrive response too large");
        }
        try (java.io.InputStream in = body.byteStream();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    throw new java.io.IOException("Overdrive response too large");
                }
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    /** Reject absurd nesting before org.json can throw StackOverflowError on it. */
    private static boolean looksDeeplyNested(String body) {
        if (body == null || body.isEmpty()) return false;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{' || ch == '[') {
                if (++depth > 64) return true;
            } else if ((ch == '}' || ch == ']') && depth > 0) {
                depth--;
            }
        }
        return false;
    }

    private static volatile Transport transport;

    private OverdriveClient() {}

    @VisibleForTesting
    public static void setTransportForTesting(Transport value) {
        if (!BuildConfig.DEBUG) return;
        transport = value;
    }

    private static Transport transport() {
        Transport injected = transport;
        return injected != null ? injected : DEFAULT_TRANSPORT;
    }

    private static Outcome<Response> call(String method, String url, String jwt, String body) {
        if (url.isEmpty()) {
            return new Outcome<>(null, 0, "No Overdrive address");
        }
        try {
            Response response = transport().execute(method, url, jwt, body);
            if (looksDeeplyNested(response.body)) {
                return new Outcome<>(null, 0, "Invalid Overdrive response");
            }
            return new Outcome<>(response, response.code, null);
        } catch (Throwable e) {
            String message = e.getMessage();
            return new Outcome<>(null, 0, message != null ? message : "Connection failed");
        }
    }

    private static String jsonError(String body, String fallback) {
        try {
            JSONObject object = new JSONObject(body);
            if (object.optBoolean("success", false)) return null;
            String error = object.optString("error", "");
            if (!error.isEmpty()) return error;
            String message = object.optString("message", "");
            if (!message.isEmpty()) return message;
        } catch (Exception ignored) {
            // Non-JSON body (proxy page, HTML error) — fall through.
        }
        return fallback;
    }

    public static Outcome<String> login(String base, String deviceId, String accessCode) {
        String url = OverdriveApi.tokenUrl(base);
        JSONObject body = new JSONObject();
        try {
            body.put("token", OverdriveApi.deviceToken(deviceId, accessCode));
        } catch (Exception ignored) {
            // String values never fail.
        }
        Outcome<Response> raw = call("POST", url, null, body.toString());
        if (raw.value == null) return new Outcome<>(null, raw.code, raw.error);
        if (raw.code < 200 || raw.code >= 300) {
            return new Outcome<>(null, raw.code, jsonError(raw.value.body, "Login failed"));
        }
        String jwt = OverdriveApi.parseJwt(raw.value.body);
        if (jwt == null) {
            return new Outcome<>(null, raw.code, jsonError(raw.value.body, "Invalid access code"));
        }
        return new Outcome<>(jwt, raw.code, null);
    }

    public static String deviceId(String base) {
        Outcome<Response> raw = call("GET", OverdriveApi.authStatusUrl(base), null, null);
        if (raw.value == null || raw.code < 200 || raw.code >= 300) return null;
        return OverdriveApi.parseDeviceId(raw.value.body);
    }

    public static boolean isReachable(String base) {
        Outcome<Response> raw = call("GET", OverdriveApi.authStatusUrl(base), null, null);
        return raw.value != null && raw.code >= 200 && raw.code < 300;
    }

    public static Outcome<OverdriveApi.CurrentLimit> currentLimit(String base, String jwt) {
        Outcome<Response> raw = call("GET", OverdriveApi.currentLimitUrl(base), jwt, null);
        return interpretCurrentLimit(raw, null);
    }

    public static Outcome<OverdriveApi.CurrentLimit> setCurrentLimit(
            String base, String jwt, int state) {
        JSONObject body = new JSONObject();
        try {
            body.put("state", state);
        } catch (Exception ignored) {
            // Int values never fail.
        }
        Outcome<Response> raw = call("POST", OverdriveApi.currentLimitUrl(base), jwt,
                body.toString());
        return interpretCurrentLimit(raw, "The car did not accept that current limit");
    }

    private static Outcome<OverdriveApi.CurrentLimit> interpretCurrentLimit(
            Outcome<Response> raw, String failureFallback) {
        if (raw.value == null) return new Outcome<>(null, raw.code, raw.error);
        if (raw.code < 200 || raw.code >= 300) {
            return new Outcome<>(null, raw.code, jsonError(raw.value.body, "Request failed"));
        }
        OverdriveApi.CurrentLimit limit = OverdriveApi.parseCurrentLimit(raw.value.body);
        String error = jsonError(raw.value.body, failureFallback);
        if (error != null) return new Outcome<>(limit, raw.code, error);
        return new Outcome<>(limit, raw.code, null);
    }

    public static Outcome<OverdriveApi.Schedule> schedule(String base, String jwt) {
        Outcome<Response> raw = call("GET", OverdriveApi.scheduleUrl(base), jwt, null);
        return interpretSchedule(raw, "Could not read the charging schedule");
    }

    public static Outcome<OverdriveApi.Schedule> setSchedule(
            String base, String jwt, String start, String end,
            OverdriveApi.Repeat repeat, boolean enabled) {
        Outcome<Response> raw = call("POST", OverdriveApi.scheduleUrl(base), jwt,
                OverdriveApi.scheduleBodyJson(start, end, repeat, enabled));
        return interpretSchedule(raw, "The car did not accept that schedule");
    }

    public static Outcome<OverdriveApi.Schedule> setScheduleEnabled(
            String base, String jwt, boolean enabled) {
        JSONObject body = new JSONObject();
        try {
            body.put("enabled", enabled);
        } catch (Exception ignored) {
            // Boolean values never fail.
        }
        Outcome<Response> raw = call("POST", OverdriveApi.scheduleUrl(base), jwt,
                body.toString());
        return interpretSchedule(raw, "The car did not accept that change");
    }

    private static Outcome<OverdriveApi.Schedule> interpretSchedule(
            Outcome<Response> raw, String failureFallback) {
        if (raw.value == null) return new Outcome<>(null, raw.code, raw.error);
        if (raw.code < 200 || raw.code >= 300) {
            return new Outcome<>(null, raw.code, jsonError(raw.value.body, "Request failed"));
        }
        OverdriveApi.Schedule schedule = OverdriveApi.parseSchedule(raw.value.body);
        String error = jsonError(raw.value.body, failureFallback);
        if (error != null) return new Outcome<>(schedule, raw.code, error);
        return new Outcome<>(schedule, raw.code, null);
    }

    public static Outcome<String> startCharging(String base, String jwt) {
        Outcome<Response> raw = call("POST", OverdriveApi.startChargingUrl(base), jwt, "{}");
        if (raw.value == null) return new Outcome<>(null, raw.code, raw.error);
        if (raw.code < 200 || raw.code >= 300) {
            return new Outcome<>(null, raw.code, jsonError(raw.value.body, "Request failed"));
        }
        String error = jsonError(raw.value.body, "Charging did not start");
        return new Outcome<>(error == null ? "ok" : null, raw.code, error);
    }
}
