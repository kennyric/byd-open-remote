package com.bydwatch.companion.lab;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CarWebActivityTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        CarWebPrefs.reset(context);
    }

    @Test
    public void hideUrlBarDefaultsToGone() {
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        CarWebPrefs.setDefaultPage(context, "/events.html");
        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class)
                .setup().get();
        assertEquals(View.GONE,
                activity.findViewById(com.bydwatch.companion.R.id.rowCarWebUrl)
                        .getVisibility());
    }

    @Test
    public void blankAddressAndRelativePageShowsEmptyState() {
        CarWebPrefs.setBaseUrl(context, "");
        CarWebPrefs.setDefaultPage(context, "/events.html");

        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class)
                .setup().get();

        assertEquals(View.VISIBLE,
                activity.findViewById(com.bydwatch.companion.R.id.layoutOverdriveEmpty)
                        .getVisibility());
        WebView webView = activity.findViewById(com.bydwatch.companion.R.id.webCar);
        assertNull(Shadows.shadowOf(webView).getLastLoadedUrl());
    }

    @Test
    public void baseAndPageLoadResolvedUrl() {
        CarWebPrefs.setHideUrlBar(context, false);
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        CarWebPrefs.setDefaultPage(context, "/events.html");

        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class)
                .setup().get();
        assertEquals(View.VISIBLE,
                activity.findViewById(com.bydwatch.companion.R.id.rowCarWebUrl)
                        .getVisibility());

        WebView webView = activity.findViewById(com.bydwatch.companion.R.id.webCar);
        String loaded = Shadows.shadowOf(webView).getLastLoadedUrl();
        assertTrue("loaded=" + loaded,
                loaded != null && loaded.contains("192.168.1.50:8080/events.html"));
    }

    @Test
    public void pastedPageUrlOpensExactlyAsEntered() {
        String pasted = "http://100.84.59.94:8080/login.html?redirect=/index.html";
        CarWebPrefs.setBaseUrl(context, pasted);
        CarWebPrefs.setDefaultPage(context, "/vehicle-control.html");

        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class)
                .setup().get();
        WebView webView = activity.findViewById(com.bydwatch.companion.R.id.webCar);

        assertEquals(pasted, Shadows.shadowOf(webView).getLastLoadedUrl());
    }

    @Test
    public void crossOriginPageRevealsTheAddressBar() {
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        CarWebPrefs.setHideUrlBar(context, true);

        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class)
                .setup().get();
        WebView webView = activity.findViewById(com.bydwatch.companion.R.id.webCar);
        View row = activity.findViewById(com.bydwatch.companion.R.id.rowCarWebUrl);

        android.webkit.WebViewClient client = Shadows.shadowOf(webView).getWebViewClient();
        client.onPageStarted(webView, "http://192.168.1.50:8080/vehicle-control.html", null);
        assertEquals(View.GONE, row.getVisibility());

        client.onPageStarted(webView, "http://evil.example/login", null);
        assertEquals(View.VISIBLE, row.getVisibility());
    }

    @Test
    public void sameHostOnAnotherPortOrSchemeRevealsTheAddressBar() {
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        CarWebPrefs.setHideUrlBar(context, true);

        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class)
                .setup().get();
        WebView webView = activity.findViewById(com.bydwatch.companion.R.id.webCar);
        View row = activity.findViewById(com.bydwatch.companion.R.id.rowCarWebUrl);
        android.webkit.WebViewClient client = Shadows.shadowOf(webView).getWebViewClient();

        client.onPageStarted(webView, "http://192.168.1.50:9999/login", null);
        assertEquals("another port is another origin", View.VISIBLE, row.getVisibility());

        client.onPageStarted(webView, "http://192.168.1.50:8080/vehicle-control.html", null);
        assertEquals(View.GONE, row.getVisibility());

        client.onPageStarted(webView, "https://192.168.1.50:8080/vehicle-control.html", null);
        assertEquals("another scheme is another origin", View.VISIBLE, row.getVisibility());
    }

    @Test
    public void defaultPageLoadsVehicleControl() {
        CarWebPrefs.reset(context);
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");

        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class)
                .setup().get();
        WebView webView = activity.findViewById(com.bydwatch.companion.R.id.webCar);
        String loaded = Shadows.shadowOf(webView).getLastLoadedUrl();
        assertTrue("loaded=" + loaded,
                loaded != null && loaded.contains("192.168.1.50:8080/vehicle-control.html"));
    }

    @Test
    public void deepLinkLoadsVehicleControlSection() {
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        android.content.Intent intent = CarWebActivity.intent(
                context, "/vehicle-control.html", "panelCharging");

        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class, intent)
                .setup().get();
        WebView webView = activity.findViewById(com.bydwatch.companion.R.id.webCar);
        String loaded = Shadows.shadowOf(webView).getLastLoadedUrl();
        assertTrue("loaded=" + loaded,
                loaded != null && loaded.contains("/vehicle-control.html#panelCharging"));
    }
}
