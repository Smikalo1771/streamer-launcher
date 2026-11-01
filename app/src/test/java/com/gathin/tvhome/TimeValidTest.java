package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TimeValidTest {
    /** Google TV Streamer UTTK.260317.003: ro.build.date.utc 1776199182 (14 Apr 2026 20:39:42 UTC). */
    private static final long BUILD = 1_776_199_182_000L;

    @Test public void epochBootIsInvalid() {
        assertFalse(MainActivity.timeValid(0L, BUILD));
        assertFalse(MainActivity.timeValid(0L, 0L));
    }

    @Test public void buildTimePlaceholderIsInvalid() {
        assertFalse(MainActivity.timeValid(BUILD + 14_000L, BUILD));       // the first seconds after boot
        assertFalse(MainActivity.timeValid(BUILD + 5 * 3_600_000L, BUILD));
    }

    @Test public void networkTimeIsValid() {
        assertTrue(MainActivity.timeValid(1_793_564_174_981L, BUILD));     // 1 Nov 2026, the NTP answer
        assertTrue(MainActivity.timeValid(BUILD + 6 * 3_600_000L, BUILD));
    }

    @Test public void unknownBuildTimeFallsBackToTheYear() {
        assertTrue(MainActivity.timeValid(1_793_564_174_981L, 0L));
        assertFalse(MainActivity.timeValid(1_700_000_000_000L, 0L));       // 2023
    }
}
