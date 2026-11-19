package com.bydwatch.companion.lab;

/** Visibility gate for the trial Lab. Never enabled in demo builds. */
public final class LabGate {
    private LabGate() {}

    public static boolean isEnabled(boolean debugBuild, boolean demoBuild) {
        return debugBuild && !demoBuild;
    }
}
