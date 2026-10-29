package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the row settings: file format, the sort step, hidden toggles and label sanitising. */
public class RowPrefsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file() {
        return new File(tmp.getRoot(), RowPrefs.FILE_NAME);
    }

    @Test
    public void roundTripsEveryField() throws Exception {
        RowPrefs p = RowPrefs.load(file());
        p.setRowOrder(Arrays.asList("tv.cvte/.Live", "rows:inputs", "net.x/.Main"));
        p.setAppOrder(Arrays.asList("com.plexapp.android", "com.netflix.ninja"));
        p.setRowHidden("rows:inputs", "Inputs row", true);
        p.setRowHidden("tv.cvte/.Live", "Live TV", true);
        p.setAppRowsHidden("com.netflix.ninja", "Netflix row", true);
        p.setAppHidden("com.plexapp.android", "Plex", true);
        assertTrue(p.save(file()));
        assertFalse(new File(tmp.getRoot(), RowPrefs.FILE_NAME + ".tmp").exists());

        String text = new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8);
        assertEquals("gtv-rows 1\n"
                + "row tv.cvte/.Live\nrow rows:inputs\nrow net.x/.Main\n"
                + "app com.plexapp.android\napp com.netflix.ninja\n"
                + "hide-row rows:inputs\tInputs row\nhide-row tv.cvte/.Live\tLive TV\n"
                + "hide-app-rows com.netflix.ninja\tNetflix row\n"
                + "hide-app com.plexapp.android\tPlex\n", text);

        RowPrefs back = RowPrefs.load(file());
        assertEquals(Arrays.asList("tv.cvte/.Live", "rows:inputs", "net.x/.Main"),
                back.sortRows(Arrays.asList("net.x/.Main", "rows:inputs", "tv.cvte/.Live"), s -> s));
        assertEquals(Arrays.asList("com.plexapp.android", "com.netflix.ninja"),
                back.sortApps(Arrays.asList("com.netflix.ninja", "com.plexapp.android"), s -> s));
        assertEquals(Arrays.asList("rows:inputs", "tv.cvte/.Live"), new ArrayList<>(back.hiddenRows().keySet()));
        assertEquals("Inputs row", back.hiddenRows().get("rows:inputs"));
        assertEquals("Live TV", back.hiddenRows().get("tv.cvte/.Live"));
        assertEquals("Netflix row", back.hiddenAppRows().get("com.netflix.ninja"));
        assertEquals("Plex", back.hiddenApps().get("com.plexapp.android"));
        assertTrue(back.isRowHidden("rows:inputs"));
        assertTrue(back.areAppRowsHidden("com.netflix.ninja"));
        assertTrue(back.isAppHidden("com.plexapp.android"));
        assertFalse(back.isAppHidden("com.netflix.ninja"));
    }

    @Test
    public void sortRowsPutsSavedKeysFirstAndKeepsTheRestInInputOrder() {
        RowPrefs p = RowPrefs.load(file());
        p.setRowOrder(Arrays.asList("c", "gone", "a"));
        List<String> rows = Arrays.asList("x", "a", "y", "c", "z");
        assertEquals(Arrays.asList("c", "a", "x", "y", "z"), p.sortRows(rows, s -> s));
        assertEquals(Arrays.asList("x", "a", "y", "c", "z"), rows);   // the input list is not changed
    }

    @Test
    public void sortRowsNeverDropsItems() {
        RowPrefs p = RowPrefs.load(file());
        p.setRowOrder(Arrays.asList("gone1", "gone2"));
        List<String> rows = Arrays.asList("b", "a", "c");
        assertEquals(rows, p.sortRows(rows, s -> s));
    }

    @Test
    public void sortAppsUsesTheSavedAppOrderOnly() {
        RowPrefs p = RowPrefs.load(file());
        p.setAppOrder(Arrays.asList("b.pkg", "a.pkg", "gone.pkg"));
        assertEquals(Arrays.asList("b.pkg", "a.pkg", "d.pkg", "c.pkg"),
                p.sortApps(Arrays.asList("d.pkg", "a.pkg", "c.pkg", "b.pkg"), s -> s));
    }

    @Test
    public void hiddenTogglesOnAndOffAndSurviveASave() {
        RowPrefs p = RowPrefs.load(file());
        assertFalse(p.isRowHidden("k"));
        p.setRowHidden("k", "Label", true);
        p.setAppRowsHidden("pkg", "Row", true);
        p.setAppHidden("pkg", "App", true);
        assertTrue(p.save(file()));

        RowPrefs back = RowPrefs.load(file());
        assertTrue(back.isRowHidden("k"));
        assertTrue(back.areAppRowsHidden("pkg"));
        assertTrue(back.isAppHidden("pkg"));

        back.setRowHidden("k", "Label", false);
        back.setAppRowsHidden("pkg", "Row", false);
        back.setAppHidden("pkg", "App", false);
        assertTrue(back.hiddenRows().isEmpty());
        assertTrue(back.hiddenAppRows().isEmpty());
        assertTrue(back.hiddenApps().isEmpty());
        assertTrue(back.save(file()));

        RowPrefs again = RowPrefs.load(file());
        assertFalse(again.isRowHidden("k"));
        assertFalse(again.areAppRowsHidden("pkg"));
        assertFalse(again.isAppHidden("pkg"));
    }

    @Test
    public void aMissingFileIsEmpty() {
        RowPrefs p = RowPrefs.load(file());
        assertTrue(p.hiddenRows().isEmpty());
        assertEquals(Arrays.asList("b", "a"), p.sortRows(Arrays.asList("b", "a"), s -> s));
    }

    @Test
    public void aCorruptHeaderIsEmpty() throws Exception {
        Files.write(file().toPath(), "tvhome-order 1\nrow a\nhide-row a\tA\n".getBytes(StandardCharsets.UTF_8));
        RowPrefs p = RowPrefs.load(file());
        assertTrue(p.hiddenRows().isEmpty());
        assertEquals(Arrays.asList("b", "a"), p.sortRows(Arrays.asList("b", "a"), s -> s));

        Files.write(file().toPath(), new byte[] {(byte) 0xff, 0, 1, 2});
        assertTrue(RowPrefs.load(file()).hiddenApps().isEmpty());
    }

    @Test
    public void unknownLineTypesAreIgnored() throws Exception {
        Files.write(file().toPath(),
                "gtv-rows 1\nfuture-line x y\n\nrow a\nhide-row\n".getBytes(StandardCharsets.UTF_8));
        RowPrefs p = RowPrefs.load(file());
        assertEquals(Arrays.asList("a", "b"), p.sortRows(Arrays.asList("b", "a"), s -> s));
        assertTrue(p.hiddenRows().isEmpty());
    }

    @Test
    public void labelsAreSanitised() throws Exception {
        RowPrefs p = RowPrefs.load(file());
        p.setRowHidden("rows:inputs", "Inputs\trow\nline\rbreak", true);
        assertTrue(p.save(file()));

        String text = new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("hide-row rows:inputs\tInputs row line break\n"));
        assertEquals("Inputs row line break", RowPrefs.load(file()).hiddenRows().get("rows:inputs"));
    }

    @Test
    public void saveReturnsFalseWhenTheWriteFails() throws Exception {
        File blocker = tmp.newFile("blocker");   // a regular file cannot be a folder
        assertFalse(RowPrefs.load(file()).save(new File(blocker, RowPrefs.FILE_NAME)));
    }
}
