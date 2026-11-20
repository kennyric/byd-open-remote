package com.bydwatch.companion.lab;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OverdrivePagesTest {

    @Test
    public void topFiveAreTheUsefulDefaults() {
        List<OverdrivePages.Page> top = OverdrivePages.topPages();
        assertEquals(5, top.size());
        assertEquals("/vehicle-control.html", top.get(0).path);
        assertEquals("/events.html", top.get(1).path);
        assertEquals("/charging.html", top.get(2).path);
        assertEquals("/trips.html", top.get(3).path);
        assertEquals("/live-view.html", top.get(4).path);
    }

    @Test
    public void morePagesContainDashboardAndSystemPages() {
        assertTrue(OverdrivePages.morePages().stream()
                .anyMatch(p -> "/".equals(p.path)));
        assertTrue(OverdrivePages.morePages().stream()
                .anyMatch(p -> "/byd-cloud.html".equals(p.path)));
    }

    @Test
    public void allLabelsAndPathsAreUnique() {
        Set<String> labels = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (OverdrivePages.Page page : OverdrivePages.allPages()) {
            assertTrue(labels.add(page.label));
            assertTrue(paths.add(page.path));
            assertTrue(page.path.startsWith("/"));
        }
        assertEquals(5 + OverdrivePages.morePages().size(),
                OverdrivePages.allPages().size());
    }
}
