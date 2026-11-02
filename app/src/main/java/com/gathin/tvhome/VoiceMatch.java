package com.gathin.tvhome;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Reads a spoken phrase into a TV command, for what Google Assistant heard (AssistantWatch) or the launcher's own
 * voice prompt (VoiceLaunchActivity). On the Streamer the Assistant hears Czech and Slovak fine but, with the stock
 * launcher disabled and no Slovak support, does not act on "otevři IPTV", "vypni IPTV" or "vypni". The launcher
 * does: open or close an app, standby, Home, Back, mute, volume, pause and play. Anything else is null and stays
 * with the Assistant. Case, accents and punctuation don't matter; app names may be misheard by one letter in five.
 */
final class VoiceMatch {
    private VoiceMatch() {}

    enum Kind { OPEN, CLOSE, CLOSE_FRONT, SLEEP, HOME, BACK, MUTE, UNMUTE, VOLUME_UP, VOLUME_DOWN, VOLUME_SET, PAUSE, PLAY }

    /** A command: what to do, the app for OPEN and CLOSE, the percent for VOLUME_SET. */
    static final class Command {
        final Kind kind;
        final String pkg;
        final int value;

        Command(Kind kind, String pkg, int value) {
            this.kind = kind;
            this.pkg = pkg;
            this.value = value;
        }

        @Override
        public String toString() {
            return kind + (pkg != null ? ":" + pkg : "") + (kind == Kind.VOLUME_SET ? ":" + value : "");
        }
    }

    /** Words that ask to open something; dropped from the start of a phrase. Already without accents. */
    private static final String[] OPEN_WORDS = {
            "otevri", "otevrete", "otevrit", "otvor", "otvorte", "otvorit", "spusti", "spustte", "spust", "spustit",
            "zapni", "zapnite", "zapnete", "zapnout", "pusti", "pustte", "pust", "pustit",
            "open", "launch", "start", "run",
    };
    /** Words that ask to close an app, or with nothing after them (or the TV) to switch off. */
    private static final String[] CLOSE_WORDS = {
            "vypni", "vypnite", "vypnete", "vypnout", "vypnut", "zavri", "zavrete", "zavrit", "zatvor", "zatvorte",
            "zatvorit", "ukonci", "ukoncete", "ukoncit", "close", "quit", "exit", "turn off", "switch off",
    };
    /** Words that may follow the verb: "otevři aplikaci Nuvio". */
    private static final String[] APP_WORDS = {"aplikaci", "aplikacia", "aplikaciu", "appku", "apku", "app", "the"};
    /** "vypni televizor": the set itself, so standby. */
    private static final String[] TV_WORDS = {
            "tv", "televizor", "televizi", "televiziu", "televize", "televizia", "telku", "telka", "prehravac",
            "prijimac", "vse", "vsetko", "vsechno", "obrazovku", "the tv", "television",
    };
    /** "zavri to": the app in front. */
    private static final String[] FRONT_WORDS = {"to", "aplikaci", "aplikaciu", "appku", "apku", "app", "it", "this"};

    /** Lower case, no accents, letters and digits only, single spaces. */
    static String normalize(String text) {
        if (text == null) return "";
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** The command a phrase asks for, or null when it is not one (the Assistant keeps it). */
    static Command parse(String phrase, List<String[]> apps) {
        String s = normalize(phrase);
        if (s.isEmpty()) return null;

        // Sound first: "vypni zvuk" and "zapni zvuk" are not about apps.
        if (oneOf(s, "ztlum", "ztlumit", "stlm", "stlmit", "stlm zvuk", "ztlum zvuk", "mute", "ticho",
                "vypni zvuk", "vypnout zvuk", "bez zvuku")) return new Command(Kind.MUTE, null, 0);
        if (oneOf(s, "zapni zvuk", "zapnout zvuk", "odtlm", "odtlmit", "zrus stlmenie", "unmute"))
            return new Command(Kind.UNMUTE, null, 0);
        Integer level = volumeLevel(s);
        if (level != null) return new Command(Kind.VOLUME_SET, null, level);
        if (oneOf(s, "hlasnejsie", "hlasiteji", "hlasitej", "nahlas", "zesil", "zesilit", "zvys hlasitost",
                "pridaj hlasitost", "pridej hlasitost", "zvysit hlasitost", "louder", "volume up", "turn it up",
                "turn up the volume")) return new Command(Kind.VOLUME_UP, null, 0);
        if (oneOf(s, "tisie", "tiseji", "potichu", "stis", "ztis", "znis hlasitost", "zniz hlasitost",
                "uber hlasitost", "snizit hlasitost", "quieter", "volume down", "turn it down",
                "turn down the volume")) return new Command(Kind.VOLUME_DOWN, null, 0);

        if (oneOf(s, "domov", "domu", "jdi domu", "chod domov", "hlavna obrazovka", "hlavni obrazovka", "plocha",
                "home", "go home", "home screen")) return new Command(Kind.HOME, null, 0);
        if (oneOf(s, "spat", "zpet", "naspat", "vrat sa", "vrat se", "back", "go back"))
            return new Command(Kind.BACK, null, 0);
        if (oneOf(s, "pauza", "pozastav", "pozastavit", "zastav", "zastavit", "stop", "pause"))
            return new Command(Kind.PAUSE, null, 0);
        if (oneOf(s, "pokracuj", "pokracovat", "prehraj", "prehravej", "hraj", "pusti", "pust", "play", "resume",
                "continue")) return new Command(Kind.PLAY, null, 0);
        if (oneOf(s, "uspi", "uspat", "uspi sa", "uspi se", "standby", "go to sleep", "sleep"))
            return new Command(Kind.SLEEP, null, 0);

        String rest = after(s, CLOSE_WORDS);
        if (rest != null) {
            if (rest.isEmpty()) {
                // "vypni" alone switches the TV off; "zavri" alone closes the app in front.
                boolean off = s.startsWith("vypn") || s.startsWith("turn") || s.startsWith("switch");
                return new Command(off ? Kind.SLEEP : Kind.CLOSE_FRONT, null, 0);
            }
            if (oneOf(rest, TV_WORDS)) return new Command(Kind.SLEEP, null, 0);
            if (oneOf(rest, FRONT_WORDS)) return new Command(Kind.CLOSE_FRONT, null, 0);
            String pkg = app(rest, apps);
            return pkg != null ? new Command(Kind.CLOSE, pkg, 0) : null;
        }
        rest = after(s, OPEN_WORDS);
        String pkg = app(rest != null ? rest : s, apps);
        return pkg != null ? new Command(Kind.OPEN, pkg, 0) : null;
    }

    /** The package of the app the phrase asks to open, or null. Kept for the launcher's own prompt. */
    static String match(String phrase, List<String[]> apps) {
        Command c = parse(phrase, apps);
        return c != null && c.kind == Kind.OPEN ? c.pkg : null;
    }

    /** "hlasitost 20", "hlasitost na 20", "volume 20", "hlasitost 20 percent": the percent, or null. */
    static Integer volumeLevel(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(?:nastav |set )?(?:hlasitost|volume|zvuk)(?: na| to)? (\\d{1,3})(?: percent| procent| %)?$")
                .matcher(s);
        if (!m.find()) return null;
        int v = Integer.parseInt(m.group(1));
        return v <= 100 ? v : null;
    }

    private static boolean oneOf(String s, String... options) {
        for (String o : options) if (s.equals(o)) return true;
        return false;
    }

    /** What follows one of the verbs (and an app word after it), or null when the phrase starts with none. */
    private static String after(String s, String[] verbs) {
        String rest = null;
        for (String w : verbs) {
            if (s.equals(w)) return "";
            if (s.startsWith(w + " ")) {
                rest = s.substring(w.length() + 1);
                break;
            }
        }
        if (rest == null) return null;
        for (String w : APP_WORDS) {
            if (rest.startsWith(w + " ")) return rest.substring(w.length() + 1).trim();
        }
        return rest.trim();
    }

    /**
     * The package of the app named by the whole subject, with or without spaces ("you tube"), within one wrong
     * letter in five; the closest name wins. apps holds {label, package}.
     */
    static String app(String subject, List<String[]> apps) {
        if (subject == null || subject.isEmpty()) return null;
        String joined = subject.replace(" ", "");
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String[] app : apps) {
            String label = normalize(app[0]);
            if (label.isEmpty()) continue;
            String labelJoined = label.replace(" ", "");
            int d = Math.min(distance(subject, label), distance(joined, labelJoined));
            int allowed = labelJoined.length() / 5;
            if (d <= allowed && d < bestDistance) {
                best = app[1];
                bestDistance = d;
            }
        }
        return best;
    }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
