package com.bydwatch.companion.lab;

import com.overdrive.app.byd.cloud.CloudCallTrace;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Bounded, newest-last log of diagnostic cloud calls. */
public final class LabConsole {

    private static final class Entry {
        final String time;
        final CloudCallTrace trace;

        Entry(String time, CloudCallTrace trace) {
            this.time = time;
            this.trace = trace;
        }
    }

    private final int capacity;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public LabConsole(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(CloudCallTrace trace) {
        if (trace == null) return;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        entries.addLast(new Entry(time, trace));
        while (entries.size() > capacity) {
            entries.removeFirst();
        }
    }

    public synchronized String text() {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (out.length() > 0) out.append("\n\n");
            out.append('[').append(entry.time).append("] ").append(entry.trace.consoleText());
        }
        return out.toString();
    }

    public synchronized String shareText() {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (out.length() > 0) out.append("\n\n");
            out.append('[').append(entry.time).append("] ").append(entry.trace.shareText());
        }
        return out.toString();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
