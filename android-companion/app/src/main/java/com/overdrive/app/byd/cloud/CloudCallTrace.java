package com.overdrive.app.byd.cloud;

import org.json.JSONObject;

/**
 * One diagnostic cloud call: what was sent, what came back, and how long it took.
 *
 * <p>Console text is for the local trial screen and keeps the full VIN.
 * Share text masks VIN values so a copied log can be sent to a maintainer.
 */
public final class CloudCallTrace {

    public final String endpoint;
    public final JSONObject request;
    public final JSONObject outerResponse;
    public final JSONObject decoded;
    public final String code;
    public final String message;
    public final long durationMs;
    public final String error;

    public CloudCallTrace(String endpoint,
                          JSONObject request,
                          JSONObject outerResponse,
                          JSONObject decoded,
                          String code,
                          String message,
                          long durationMs,
                          String error) {
        this.endpoint = endpoint != null ? endpoint : "";
        this.request = request != null ? request : new JSONObject();
        this.outerResponse = outerResponse != null ? outerResponse : new JSONObject();
        this.decoded = decoded;
        this.code = code != null ? code : "";
        this.message = message != null ? message : "";
        this.durationMs = durationMs;
        this.error = error;
    }

    public boolean isSuccess() {
        return error == null && "0".equals(code);
    }

    /** Last four VIN characters, or empty when the request has no VIN. */
    public String maskedVin() {
        String vin = request.optString("vin", "");
        if (vin.isEmpty()) return "";
        return vin.substring(Math.max(0, vin.length() - 4));
    }

    public String consoleText() {
        StringBuilder out = new StringBuilder();
        out.append(endpoint)
                .append("  code=").append(code)
                .append("  ").append(durationMs).append("ms");
        if (!message.isEmpty()) out.append("  message=").append(message);
        out.append('\n').append("request: ").append(request);
        if (outerResponse.length() > 0) {
            out.append('\n').append("outer: ").append(outerResponse);
        }
        if (decoded != null) {
            out.append('\n').append("decoded: ").append(decoded);
        }
        if (error != null) {
            out.append('\n').append("error: ").append(error);
        }
        return out.toString();
    }

    public String shareText() {
        String masked = maskedVin();
        return consoleText().replaceAll(
                "\"vin\"\\s*:\\s*\"[^\"]*\"",
                "\"vin\":\"***" + masked + "\"");
    }
}
