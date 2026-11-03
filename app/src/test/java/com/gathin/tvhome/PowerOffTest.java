package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class PowerOffTest {
    private static String cmd(String phrase) {
        VoiceMatch.Command c = VoiceMatch.parse(phrase, Collections.emptyList());
        return c == null ? null : c.toString();
    }

    @Test
    public void sleepTimerByVoice() {
        assertEquals("SLEEP_TIMER:30", cmd("vypni o 30 minút"));
        assertEquals("SLEEP_TIMER:60", cmd("Vypni za hodinu"));
        assertEquals("SLEEP_TIMER:90", cmd("vypni televízor o hodinu a pol"));
        assertEquals("SLEEP_TIMER:120", cmd("vypni o 2 hodiny"));
        assertEquals("SLEEP_TIMER:120", cmd("vypni o dve hodiny"));
        assertEquals("SLEEP_TIMER:45", cmd("vypni za 45 minut"));
        assertEquals("SLEEP_TIMER:30", cmd("vypni o pol hodiny"));
        assertEquals("SLEEP_TIMER:150", cmd("vypni o 2 a pol hodiny"));
        assertEquals("SLEEP_CANCEL", cmd("zruš časovač"));
        assertEquals("SLEEP_CANCEL", cmd("nevypínaj"));
        assertEquals("SLEEP", cmd("vypni"));   // right away, as before
        assertNull(VoiceMatch.sleepTimer("vypni o 900 minut"));
    }

    @Test
    public void idleOnlyWithoutPlayingMusic() {
        assertTrue(PowerOff.idleDue(7_200_000, 7_200_000, true, false, false));
        assertTrue(PowerOff.idleDue(7_200_000, 7_200_000, true, false, true));    // a film plays sound too
        assertFalse(PowerOff.idleDue(7_200_000, 7_200_000, true, true, true));    // music app playing
        assertFalse(PowerOff.idleDue(7_199_999, 7_200_000, true, false, false));
        assertFalse(PowerOff.idleDue(9_000_000, 0, true, false, false));          // switched off in config
        assertFalse(PowerOff.idleDue(9_000_000, 7_200_000, false, false, false)); // screen off already
    }

    @Test
    public void countdownText() {
        assertEquals("2:00", PowerOff.clock(120_000));
        assertEquals("1:05", PowerOff.clock(64_100));
        assertEquals("1 h 30 min", PowerOff.duration(90 * 60_000L));
        assertEquals("30 min", PowerOff.duration(30 * 60_000L));
    }
}
