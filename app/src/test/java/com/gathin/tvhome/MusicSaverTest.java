package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;

import org.junit.Test;

public class MusicSaverTest {
    private static final Set<String> MUSIC = Collections.singleton("com.spotify.tv.android");
    private static final String SPOTIFY = "com.spotify.tv.android";

    @Test
    public void startsOverPlayingMusicAfterTheDelay() {
        assertTrue(MusicSaver.due(SPOTIFY, MUSIC, true, true, 120_000, 120_000));
    }

    @Test
    public void waitsForTheDelay() {
        assertFalse(MusicSaver.due(SPOTIFY, MUSIC, true, true, 119_999, 120_000));
    }

    @Test
    public void onlyOverMusicApps() {
        assertFalse(MusicSaver.due("com.nuvio.tv", MUSIC, true, true, 600_000, 120_000));
        assertFalse(MusicSaver.due(null, MUSIC, true, true, 600_000, 120_000));
    }

    @Test
    public void notWhenPausedOrScreenOff() {
        assertFalse(MusicSaver.due(SPOTIFY, MUSIC, false, true, 600_000, 120_000));
        assertFalse(MusicSaver.due(SPOTIFY, MUSIC, true, false, 600_000, 120_000));
    }
}
