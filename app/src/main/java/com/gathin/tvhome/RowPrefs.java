package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The owner's row settings: the order of the channel rows and of the apps row, and what is hidden (rows, app rows, apps).
 *
 * Kept in the launcher's private files (gtv_rows.txt). Saved keys come first in their saved order; items never saved keep
 * their input order after them, and saved keys with no matching item are not used. Sorting never drops an item.
 *
 * Format, UTF-8 text: {@code gtv-rows 1}, then one line per entry: {@code row <key>}, {@code app <pkg>},
 * {@code hide-row <key>\t<label>}, {@code hide-app-rows <pkg>\t<label>} or {@code hide-app <pkg>\t<label>}. Unknown
 * line types are ignored, so an older launcher reads a newer file. A missing or wrong header means empty settings.
 * Tabs and line breaks in keys and labels are saved as spaces.
 *
 * Uses java.nio, not AtomicFile, so the JVM tests can read and write real files.
 */
final class RowPrefs {

    static final String FILE_NAME = "gtv_rows.txt";
    private static final String MAGIC = "gtv-rows 1";
    private static final String ROW = "row ";
    private static final String APP = "app ";
    private static final String HIDE_ROW = "hide-row ";
    private static final String HIDE_APP_ROWS = "hide-app-rows ";
    private static final String HIDE_APP = "hide-app ";

    private final List<String> rowOrder = new ArrayList<>();
    private final List<String> appOrder = new ArrayList<>();
    private final Map<String, String> hiddenRows = new LinkedHashMap<>();
    private final Map<String, String> hiddenAppRows = new LinkedHashMap<>();
    private final Map<String, String> hiddenApps = new LinkedHashMap<>();

    private RowPrefs() {
    }

    // ---- file ------------------------------------------------------------------------------------

    static File file(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    /** The saved settings, or empty ones when there is no file or it is not one. Never throws. */
    static RowPrefs load(File f) {
        RowPrefs p = new RowPrefs();
        if (f == null || !f.isFile()) return p;
        String text;
        try {
            text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return p;
        }
        String[] lines = text.split("\n", -1);
        if (!lines[0].equals(MAGIC)) return p;
        for (int i = 1; i < lines.length; i++) p.readLine(lines[i]);
        return p;
    }

    /** Writes the settings atomically (temp file, then rename). False when the write failed. */
    boolean save(File f) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        for (String k : rowOrder) sb.append(ROW).append(k).append('\n');
        for (String p : appOrder) sb.append(APP).append(p).append('\n');
        appendHidden(sb, HIDE_ROW, hiddenRows);
        appendHidden(sb, HIDE_APP_ROWS, hiddenAppRows);
        appendHidden(sb, HIDE_APP, hiddenApps);
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
    }

    private void readLine(String line) {
        if (line.startsWith(ROW)) addOnce(rowOrder, line.substring(ROW.length()));
        else if (line.startsWith(APP)) addOnce(appOrder, line.substring(APP.length()));
        else if (line.startsWith(HIDE_ROW)) readHidden(hiddenRows, line.substring(HIDE_ROW.length()));
        else if (line.startsWith(HIDE_APP_ROWS)) readHidden(hiddenAppRows, line.substring(HIDE_APP_ROWS.length()));
        else if (line.startsWith(HIDE_APP)) readHidden(hiddenApps, line.substring(HIDE_APP.length()));
    }

    private static void addOnce(List<String> list, String key) {
        if (!key.isEmpty() && !list.contains(key)) list.add(key);
    }

    /** Reads {@code <key>\t<label>}; a line without a tab uses the key as its label. */
    private static void readHidden(Map<String, String> map, String rest) {
        int tab = rest.indexOf('\t');
        String key = tab < 0 ? rest : rest.substring(0, tab);
        if (key.isEmpty()) return;
        map.put(key, tab < 0 ? key : rest.substring(tab + 1));
    }

    private static void appendHidden(StringBuilder sb, String prefix, Map<String, String> map) {
        for (Map.Entry<String, String> e : map.entrySet()) {
            sb.append(prefix).append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
    }

    // ---- order -----------------------------------------------------------------------------------

    /** The rows in the saved order; unsaved rows after them, in input order. Returns a new list. */
    <T> List<T> sortRows(List<T> rows, Function<T, String> key) {
        return sortBy(rows, key, rowOrder);
    }

    void setRowOrder(List<String> keys) {
        rowOrder.clear();
        rowOrder.addAll(cleanKeys(keys));
    }

    /** The apps in the saved order; apps never saved after them, in input order. Returns a new list. */
    <T> List<T> sortApps(List<T> apps, Function<T, String> pkg) {
        return sortBy(apps, pkg, appOrder);
    }

    void setAppOrder(List<String> pkgs) {
        appOrder.clear();
        appOrder.addAll(cleanKeys(pkgs));
    }

    private static <T> List<T> sortBy(List<T> items, Function<T, String> key, List<String> order) {
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) rank.put(order.get(i), i);
        List<T> out = new ArrayList<>(items);
        // List.sort is stable, so items with no saved rank (Integer.MAX_VALUE) keep their input order.
        out.sort(Comparator.comparingInt(t -> rank.getOrDefault(key.apply(t), Integer.MAX_VALUE)));
        return out;
    }

    // ---- hidden ----------------------------------------------------------------------------------

    boolean isRowHidden(String rowKey) {
        return hiddenRows.containsKey(rowKey);
    }

    /** The label is shown in the "Hidden items" list. */
    void setRowHidden(String rowKey, String label, boolean hidden) {
        setHidden(hiddenRows, rowKey, label, hidden);
    }

    boolean areAppRowsHidden(String pkg) {
        return hiddenAppRows.containsKey(pkg);
    }

    void setAppRowsHidden(String pkg, String label, boolean hidden) {
        setHidden(hiddenAppRows, pkg, label, hidden);
    }

    boolean isAppHidden(String pkg) {
        return hiddenApps.containsKey(pkg);
    }

    void setAppHidden(String pkg, String label, boolean hidden) {
        setHidden(hiddenApps, pkg, label, hidden);
    }

    /** key to label, in the order each was hidden. A copy: changing it changes nothing. */
    Map<String, String> hiddenRows() {
        return new LinkedHashMap<>(hiddenRows);
    }

    Map<String, String> hiddenAppRows() {
        return new LinkedHashMap<>(hiddenAppRows);
    }

    Map<String, String> hiddenApps() {
        return new LinkedHashMap<>(hiddenApps);
    }

    private static void setHidden(Map<String, String> map, String key, String label, boolean hidden) {
        String k = sanitize(key);
        if (k.isEmpty()) return;
        if (hidden) map.put(k, sanitize(label));
        else map.remove(k);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Tabs and line breaks would break the line format, so they become spaces. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Sanitised, non-empty, de-duplicated, in the given order. */
    private static List<String> cleanKeys(List<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        for (String k : keys) {
            String c = sanitize(k);
            if (!c.isEmpty()) out.add(c);
        }
        return new ArrayList<>(out);
    }
}
