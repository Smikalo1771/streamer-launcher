package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Launcher settings to and from launcher-settings.json, for backup.sh and restore.sh on the PC (the same as Launcher
 * settings > Back up / Restore). Only the adb shell may send it (WRITE_SECURE_SETTINGS):
 * {@code adb shell am broadcast -a com.gathin.tvhome.BACKUP -n <package>/com.gathin.tvhome.SettingsBackupReceiver}
 * and {@code ...RESTORE}. The result data is "ok" or "failed".
 */
public class SettingsBackupReceiver extends BroadcastReceiver {
    static final String BACKUP = "com.gathin.tvhome.BACKUP";
    static final String RESTORE = "com.gathin.tvhome.RESTORE";

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean ok;
        if (BACKUP.equals(intent.getAction())) {
            ok = SettingsBackup.save(context);
        } else if (RESTORE.equals(intent.getAction())) {
            ok = SettingsBackup.restore(context);
            if (ok) HomeKeyService.reloadConfig();
        } else {
            return;
        }
        setResultData(ok ? "ok" : "failed");
    }
}
