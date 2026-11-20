package com.bydwatch.companion.lab;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bydwatch.companion.BuildConfig;
import com.bydwatch.companion.R;

import java.net.Inet4Address;

/**
 * Trial-only WebView that opens the car's Overdrive server on port 8080.
 *
 * <p>If the phone is on the car's hotspot, the default gateway is used as the
 * car IP. On any other network the user can type the car's IP; it is remembered.
 */
public class CarWebActivity extends AppCompatActivity {

    private static final String PREFS = "car_web";
    private static final String PREF_URL = "car_web_url";

    private WebView webView;
    private EditText etUrl;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!LabGate.isEnabled(BuildConfig.DEBUG, BuildConfig.DEMO_BUILD)) {
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_web);

        webView = findViewById(R.id.webCar);
        etUrl = findViewById(R.id.etCarWebUrl);
        progress = findViewById(R.id.pbCarWeb);

        ImageButton back = findViewById(R.id.btnCarWebBack);
        if (back != null) back.setOnClickListener(v -> finish());
        View reload = findViewById(R.id.btnCarWebReload);
        if (reload != null) reload.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });
        View open = findViewById(R.id.btnCarWebLoad);
        if (open != null) open.setOnClickListener(v -> loadFromField());
        if (etUrl != null) {
            etUrl.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    loadFromField();
                    return true;
                }
                return false;
            });
        }

        configureWebView();
        autoLoad();
    }

    private void configureWebView() {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (progress != null) progress.setVisibility(View.VISIBLE);
                if (etUrl != null && url != null) etUrl.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (progress != null) progress.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (progress != null) progress.setVisibility(View.GONE);
                if (request != null && request.isForMainFrame()) {
                    Toast.makeText(CarWebActivity.this,
                            R.string.car_web_error, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl() != null ? request.getUrl().getScheme() : "";
                return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            }
        });
    }

    private void autoLoad() {
        String saved = prefs().getString(PREF_URL, "");
        String url = CarWebUrl.normalize(saved);
        if (url.isEmpty()) {
            url = CarWebUrl.fromGateway(findGateway());
        }
        if (url.isEmpty()) {
            if (etUrl != null) etUrl.requestFocus();
            Toast.makeText(this, R.string.car_web_no_url, Toast.LENGTH_LONG).show();
            return;
        }
        if (etUrl != null) etUrl.setText(url);
        load(url, false);
    }

    private void loadFromField() {
        String raw = etUrl != null ? etUrl.getText().toString() : "";
        load(CarWebUrl.normalize(raw), true);
    }

    private void load(String url, boolean save) {
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.car_web_no_url, Toast.LENGTH_SHORT).show();
            return;
        }
        if (etUrl != null) etUrl.setText(url);
        if (save) prefs().edit().putString(PREF_URL, url).apply();
        if (webView != null) webView.loadUrl(url);
    }

    private String findGateway() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return "";
            Network network = manager.getActiveNetwork();
            if (network == null) return "";
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) return "";
            for (RouteInfo route : properties.getRoutes()) {
                if (route.isDefaultRoute() && route.getGateway() instanceof Inet4Address) {
                    return route.getGateway().getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
