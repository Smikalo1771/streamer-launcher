package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class VoiceMatchTest {
    private static final List<String[]> APPS = Arrays.asList(
            new String[] {"IPTV", "com.example.iptvplayer"},
            new String[] {"Nuvio", "com.nuvio.tv"},
            new String[] {"YouTube", "com.google.android.youtube.tv"},
            new String[] {"Spotify", "com.spotify.tv.android"},
            new String[] {"SmartTube", "org.smarttube.stable"},
            new String[] {"TuneIn Radio", "tunein.player"});

    @Test
    public void opensAnAppByNameAfterAnOpenWord() {
        assertEquals("com.example.iptvplayer", VoiceMatch.match("otevři IPTV", APPS));
        assertEquals("com.nuvio.tv", VoiceMatch.match("Otvor Nuvio", APPS));
        assertEquals("com.spotify.tv.android", VoiceMatch.match("spusť aplikaci Spotify", APPS));
        assertEquals("com.google.android.youtube.tv", VoiceMatch.match("open YouTube", APPS));
        assertEquals("com.example.iptvplayer", VoiceMatch.match("Spusti IP TV", APPS));   // as the Assistant heard it
    }

    @Test
    public void opensAnAppByItsNameAlone() {
        assertEquals("com.nuvio.tv", VoiceMatch.match("Nuvio", APPS));
        assertEquals("tunein.player", VoiceMatch.match("tunein radio", APPS));
    }

    @Test
    public void toleratesSpacesAndSmallMishearings() {
        assertEquals("com.google.android.youtube.tv", VoiceMatch.match("otevři you tube", APPS));
        assertEquals("org.smarttube.stable", VoiceMatch.match("otevři smart tube", APPS));
        assertEquals("com.spotify.tv.android", VoiceMatch.match("otevři spotifi", APPS));
    }

    @Test
    public void leavesOtherRequestsToTheAssistant() {
        assertNull(VoiceMatch.match("pusť Simpsonovi na YouTube", APPS));
        assertNull(VoiceMatch.match("jaké je počasí", APPS));
        assertNull(VoiceMatch.match("otevři", APPS));
        assertNull(VoiceMatch.match("", APPS));
        assertNull(VoiceMatch.match("otevři Netflix", APPS));
    }

    @Test
    public void shortNamesNeedToBeExact() {
        assertNull(VoiceMatch.match("otevři IPTB", APPS));
    }

    private static String cmd(String phrase) {
        VoiceMatch.Command c = VoiceMatch.parse(phrase, APPS);
        return c == null ? null : c.toString();
    }

    @Test
    public void closesAnAppOrTheOneInFront() {
        assertEquals("CLOSE:com.example.iptvplayer", cmd("Vypni IPTV"));
        assertEquals("CLOSE:com.nuvio.tv", cmd("zavři Nuvio"));
        assertEquals("CLOSE:com.spotify.tv.android", cmd("zatvor aplikáciu Spotify"));
        assertEquals("CLOSE_FRONT", cmd("zavři"));
        assertEquals("CLOSE_FRONT", cmd("vypni to"));
        assertEquals("CLOSE_FRONT", cmd("zatvor appku"));
        assertNull(cmd("vypni Netflix"));
    }

    @Test
    public void switchesTheTvOff() {
        assertEquals("SLEEP", cmd("Vypni"));
        assertEquals("SLEEP", cmd("vypni televizor"));
        assertEquals("SLEEP", cmd("vypni televizi"));
        assertEquals("SLEEP", cmd("turn off the tv"));
        assertEquals("SLEEP", cmd("uspi"));
    }

    @Test
    public void soundAndVolume() {
        assertEquals("MUTE", cmd("ztlum"));
        assertEquals("MUTE", cmd("vypni zvuk"));
        assertEquals("UNMUTE", cmd("zapni zvuk"));
        assertEquals("VOLUME_SET:20", cmd("hlasitosť 20"));
        assertEquals("VOLUME_SET:35", cmd("hlasitost na 35 %"));
        assertEquals("VOLUME_UP", cmd("hlasnejšie"));
        assertEquals("VOLUME_DOWN", cmd("tišie"));
        assertNull(cmd("hlasitost 300"));
    }

    @Test
    public void navigationAndPlayback() {
        assertEquals("HOME", cmd("domov"));
        assertEquals("BACK", cmd("späť"));
        assertEquals("PAUSE", cmd("pauza"));
        assertEquals("PLAY", cmd("pokračuj"));
        assertEquals("OPEN:com.nuvio.tv", cmd("zapni Nuvio"));
    }
}
