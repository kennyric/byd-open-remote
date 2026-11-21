package com.bydwatch.companion.lab;

import android.content.Context;
import android.content.Intent;
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

/**
 * WebView bridge to the car's Overdrive server.
 *
 * <p>The page comes from Advanced (car address + default page). A blank address
 * leaves the screen closed; the URL bar is hidden by default and appears
 * automatically when a page leaves the configured origin.
 */
public class CarWebActivity extends AppCompatActivity {

    public static final String EXTRA_PAGE = "overdrive_page";
    public static final String EXTRA_SCROLL_ID = "overdrive_scroll_id";

    private WebView webView;
    private EditText etUrl;
    private ProgressBar progress;
    private View rowUrl;
    private View emptyState;
    private String scrollTarget = "";

    public static Intent intent(Context context, String page, String scrollId) {
        Intent intent = new Intent(context, CarWebActivity.class);
        if (page != null) intent.putExtra(EXTRA_PAGE, page);
        if (scrollId != null) intent.putExtra(EXTRA_SCROLL_ID, scrollId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (BuildConfig.DEMO_BUILD) {
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_web);

        webView = findViewById(R.id.webCar);
        etUrl = findViewById(R.id.etCarWebUrl);
        progress = findViewById(R.id.pbCarWeb);
        rowUrl = findViewById(R.id.rowCarWebUrl);
        emptyState = findViewById(R.id.layoutOverdriveEmpty);

        ImageButton back = findViewById(R.id.btnCarWebBack);
        if (back != null) back.setOnClickListener(v -> finish());
        View reload = findViewById(R.id.btnCarWebReload);
        if (reload != null) reload.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });
        View open = findViewById(R.id.btnCarWebLoad);
        if (open != null) open.setOnClickListener(v -> loadFromField());
        View advanced = findViewById(R.id.btnOverdriveAdvanced);
        if (advanced != null) advanced.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, AdvancedActivity.class)));
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
        openDefaultPage();
    }

    private void configureWebView() {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (progress != null) progress.setVisibility(View.VISIBLE);
                if (etUrl != null && url != null) etUrl.setText(url);
                // Navigation is allowed so a changed car address still works, but the origin is
                // never hidden while a page outside the configured scheme/host/port is on screen.
                if (rowUrl != null && url != null) {
                    boolean crossOrigin =
                            !CarWebUrl.sameOrigin(url, CarWebPrefs.baseUrl(CarWebActivity.this));
                    rowUrl.setVisibility(!CarWebPrefs.hideUrlBar(CarWebActivity.this) || crossOrigin
                            ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (progress != null) progress.setVisibility(View.GONE);
                if (!scrollTarget.isEmpty()) {
                    scrollToTarget();
                    if (webView != null) webView.postDelayed(CarWebActivity.this::scrollToTarget, 300);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (progress != null) progress.setVisibility(View.GONE);
                if (request != null && request.isForMainFrame()) {
                    Toast.makeText(CarWebActivity.this,
                            R.string.overdrive_error, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl() != null ? request.getUrl().getScheme() : "";
                return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            }
        });
    }

    private void openDefaultPage() {
        boolean hideBar = CarWebPrefs.hideUrlBar(this);
        if (rowUrl != null) rowUrl.setVisibility(hideBar ? View.GONE : View.VISIBLE);

        String pageExtra = getIntent().getStringExtra(EXTRA_PAGE);
        String rawScroll = getIntent().getStringExtra(EXTRA_SCROLL_ID);
        scrollTarget = rawScroll != null
                ? rawScroll.replaceAll("[^A-Za-z0-9_-]", "") : "";

        String url;
        if (pageExtra != null && !pageExtra.trim().isEmpty()) {
            // Explicit page (for example the login button): resolve it against the server root.
            url = CarWebUrl.resolve(CarWebPrefs.baseUrl(this), pageExtra);
        } else {
            // A pasted page URL opens exactly as entered; a bare address uses the page setting.
            url = CarWebUrl.entry(CarWebPrefs.entryUrl(this), CarWebPrefs.defaultPage(this));
        }
        url = CarWebUrl.withFragment(url, scrollTarget);
        if (url.isEmpty()) {
            if (webView != null) webView.setVisibility(View.GONE);
            if (emptyState != null) emptyState.setVisibility(View.VISIBLE);
            if (etUrl != null && !hideBar) etUrl.setText("");
            return;
        }

        if (emptyState != null) emptyState.setVisibility(View.GONE);
        if (webView != null) webView.setVisibility(View.VISIBLE);
        if (etUrl != null) etUrl.setText(url);
        load(url);
    }

    private void loadFromField() {
        String raw = etUrl != null ? etUrl.getText().toString() : "";
        load(CarWebUrl.normalize(raw));
    }

    private void load(String url) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, R.string.car_web_no_url, Toast.LENGTH_SHORT).show();
            return;
        }
        if (etUrl != null) etUrl.setText(url);
        if (webView != null) webView.loadUrl(url);
    }

    private void scrollToTarget() {
        if (webView == null || scrollTarget.isEmpty()) return;
        String js = "(function(){var e=document.getElementById('" + scrollTarget
                + "');if(e){e.scrollIntoView({block:'start'});}})()";
        webView.evaluateJavascript(js, null);
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
