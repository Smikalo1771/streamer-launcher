package com.gathin.tvhome;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The remote's voice button ("voice" in app_keys, KEYCODE_ASSIST). Google's speech input (katniss) listens through
 * the remote's microphone and hands back the words; an app's name ("otevři IPTV", "Nuvio") opens that app here,
 * anything else goes on to Google Assistant as a text query. On the Streamer with the stock launcher disabled the
 * Assistant understands "open Nuvio" but fails to open it (provider.OPEN, UNKNOWN_ERROR), hence this detour.
 * Without a speech input the Assistant opens as before.
 */
public final class VoiceLaunchActivity extends Activity {
    private static final int LISTEN = 1;
    static final String ASSISTANT = "com.google.android.katniss";

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return;   // recreated while listening: the result still comes here
        Intent listen = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt));
        // Google's own speech input first: it opens the remote's microphone, and with two recognizers installed an
        // unaddressed request would bring up a chooser every time.
        Intent google = new Intent(listen).setPackage(ASSISTANT);
        if (getPackageManager().resolveActivity(google, 0) != null) listen = google;
        try {
            startActivityForResult(listen, LISTEN);
        } catch (ActivityNotFoundException | SecurityException e) {
            askAssistant(null);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != LISTEN) return;
        ArrayList<String> heard = resultCode == RESULT_OK && data != null
                ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
        if (heard != null && !heard.isEmpty()) {
            List<String[]> apps = apps();
            String pkg = null;
            for (String phrase : heard) {
                pkg = VoiceMatch.match(phrase, apps);
                if (pkg != null) break;
            }
            if (pkg != null) {
                Toast.makeText(this, getString(R.string.voice_opening, label(apps, pkg)), Toast.LENGTH_SHORT).show();
                RemoteKeys.launch(this, pkg);
            } else {
                askAssistant(heard.get(0));
            }
        }
        finish();
    }

    /** Google Assistant with the words as a typed question, or just opened when there are none. */
    private void askAssistant(String query) {
        Intent ask = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH).setPackage(ASSISTANT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (query != null) ask.putExtra(SearchManager.QUERY, query);
        try {
            startActivity(ask);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Installed apps with a launcher entry, TV or phone: {label, package}, without this launcher. */
    private List<String[]> apps() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                boolean seen = false;
                for (String[] a : apps) seen |= a[1].equals(pkg);
                if (!seen) apps.add(new String[] {r.loadLabel(pm).toString(), pkg});
            }
        }
        return apps;
    }

    private static String label(List<String[]> apps, String pkg) {
        for (String[] a : apps) if (a[1].equals(pkg)) return a[0];
        return pkg;
    }
}
