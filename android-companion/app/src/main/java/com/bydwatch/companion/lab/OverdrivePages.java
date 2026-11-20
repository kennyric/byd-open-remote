package com.bydwatch.companion.lab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Curated Overdrive pages exposed by the Advanced default-page picker. */
public final class OverdrivePages {

    public static final class Page {
        public final String label;
        public final String path;

        public Page(String label, String path) {
            this.label = label;
            this.path = path;
        }
    }

    private static final List<Page> TOP = Collections.unmodifiableList(Arrays.asList(
            new Page("Vehicle Control", "/vehicle-control.html"),
            new Page("Events", "/events.html"),
            new Page("Charging", "/charging.html"),
            new Page("Trips", "/trips.html"),
            new Page("Live View", "/live-view.html")));

    private static final List<Page> MORE = Collections.unmodifiableList(Arrays.asList(
            new Page("Dashboard", "/"),
            new Page("Automations", "/automations.html"),
            new Page("Parking", "/parking.html"),
            new Page("Recordings", "/recording.html"),
            new Page("Widgets", "/widgets.html"),
            new Page("Network", "/network.html"),
            new Page("BYD Cloud", "/byd-cloud.html"),
            new Page("Remote Access", "/remote-access.html"),
            new Page("About", "/about.html"),
            new Page("Module Faults", "/module-faults.html"),
            new Page("Seat Positions", "/seat-positions.html"),
            new Page("Sessions", "/sessions.html"),
            new Page("Daemons", "/daemons.html"),
            new Page("Communication", "/communicate.html"),
            new Page("Telegram", "/telegram.html"),
            new Page("MQTT", "/mqtt.html"),
            new Page("WireGuard", "/wireguard.html"),
            new Page("ABRP", "/abrp.html"),
            new Page("Road Sense", "/road-sense.html"),
            new Page("SafeKeep", "/safekeep.html"),
            new Page("Storage", "/storage.html"),
            new Page("Factory DVR", "/factory-dvr.html"),
            new Page("Key Mapping", "/key-mapping.html"),
            new Page("Notifications", "/notifications.html"),
            new Page("Performance", "/performance.html"),
            new Page("Surveillance", "/surveillance.html")));

    private OverdrivePages() {}

    public static List<Page> topPages() {
        return TOP;
    }

    public static List<Page> morePages() {
        return MORE;
    }

    public static List<Page> allPages() {
        List<Page> all = new ArrayList<>(TOP);
        all.addAll(MORE);
        return Collections.unmodifiableList(all);
    }
}
