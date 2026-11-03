package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class VoiceAliasesTest {
    @Test
    public void parsesNameAndPackage() {
        List<String[]> a = VoiceAliases.parse(Arrays.asList("telka:com.example.iptvplayer", "filmy:com.nuvio.tv"));
        assertEquals(2, a.size());
        assertEquals("filmy", a.get(0)[0]);
        assertEquals("com.nuvio.tv", a.get(0)[1]);
        assertEquals("telka", a.get(1)[0]);
    }

    @Test
    public void webAliases() {
        List<String[]> a = VoiceAliases.parse(Arrays.asList("claude:https://claude.ai"));
        assertEquals("https://claude.ai", a.get(0)[1]);
        org.junit.Assert.assertTrue(VoiceAliases.isWeb(a.get(0)[1]));
        assertEquals("OPEN:https://claude.ai", VoiceMatch.parse("otvor Claude", a).toString());
    }

    @Test
    public void skipsMalformedEntries() {
        assertEquals(0, VoiceAliases.parse(Arrays.asList("telka", ":com.x.y", "telka:", "telka:nodot", null)).size());
    }

    @Test
    public void anAliasWorksAsAnAppName() {
        List<String[]> apps = Arrays.asList(new String[] {"IPTV", "com.example.iptvplayer"},
                new String[] {"telka", "com.example.iptvplayer"});
        assertEquals("OPEN:com.example.iptvplayer", VoiceMatch.parse("spusti telku", apps).toString());
    }
}
