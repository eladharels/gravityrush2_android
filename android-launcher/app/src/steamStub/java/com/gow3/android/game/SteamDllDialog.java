package com.gow3.android.game;

import android.app.Activity;
import android.widget.Toast;
import java.io.File;
import java.util.function.BiConsumer;

/**
 * Fallback when Steam download is not part of this build (no steam-libs/jars.txt, e.g. a plain
 * Android Studio build before tools/android/inputs/fetch_steam_libs.sh has run). Tells the player
 * to use IMPORT .DLL instead. The real JavaSteam implementation lives in the src/steam/java source
 * set and replaces this class once the Steam libraries are present.
 */
public final class SteamDllDialog {
    public SteamDllDialog(Activity activity, File ignoredTarget) {
        this(activity, ignoredTarget, null);
    }

    public SteamDllDialog(Activity activity, File ignoredTarget, BiConsumer<Boolean, String> onInstalled) {
        this.activity = activity;
    }

    private final Activity activity;

    public void show() {
        Toast.makeText(activity,
            "Steam downloads are not part of this build. Use IMPORT .DLL on the launcher Home page "
                + "to add Lossless.dll yourself.", Toast.LENGTH_LONG).show();
    }
}
