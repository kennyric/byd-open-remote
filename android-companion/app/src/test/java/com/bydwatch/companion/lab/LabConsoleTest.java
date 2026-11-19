package com.bydwatch.companion.lab;

import com.overdrive.app.byd.cloud.CloudCallTrace;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LabConsoleTest {
    @Test
    public void keepsNewestEntriesAndClears() {
        LabConsole console = new LabConsole(2);
        console.add(new CloudCallTrace("/a", new JSONObject(), new JSONObject(),
                new JSONObject(), "0", "", 1L, null));
        console.add(new CloudCallTrace("/b", new JSONObject(), new JSONObject(),
                new JSONObject(), "0", "", 1L, null));
        console.add(new CloudCallTrace("/c", new JSONObject(), new JSONObject(),
                new JSONObject(), "0", "", 1L, null));

        String text = console.text();
        assertFalse(text.contains("/a"));
        assertTrue(text.contains("/b"));
        assertTrue(text.contains("/c"));

        console.clear();
        assertEquals("", console.text());
    }
}
