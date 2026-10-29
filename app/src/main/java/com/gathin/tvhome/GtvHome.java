package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * gtv_home (config.xml): a Google TV style Home under the top bar, in place of the tile grid. One row of round app
 * icons, then each app's Android TV preview channels (ChannelRepo) as rows of programme cards. Apps and rows are
 * moved and hidden from their hold-OK (or Menu key) menu and kept in RowPrefs; Launcher settings > Hidden items
 * brings them back. MainActivity owns it and hands it focus (focusTile), Home presses, resumes and memory trims.
 * The rows load on one background thread on each resume: no timer, no polling.
 */
final class GtvHome {
    private static final int TYPE_APPS = 0, TYPE_ROW = 1;
    private static final int CARD_HEIGHT_DP = 150;
    /** The app icon's diameter in the apps row (item_gtv_app.xml gtvIcon). */
    private static final int APP_ICON_DP = 72;
    private static final float APP_FOCUS_SCALE = 1.1f, CARD_FOCUS_SCALE = 1.08f;
    private static final float MIN_ASPECT = 0.6f, MAX_ASPECT = 2f, DEFAULT_ASPECT = 16f / 9f;
    /** The blue MainActivity's reorder mode uses, here for the title of a row being moved. */
    private static final int MOVING_ACCENT = 0xFF6EC1FF;
    private static final int ROW_TITLE_COLOR = 0xE6FFFFFF;
    private static final String WATCH_NEXT = "watchnext";

    private final MainActivity activity;
    private final RecyclerView home;
    private final LinearLayoutManager homeLayout;
    private final HomeAdapter homeAdapter = new HomeAdapter();
    private final AppsAdapter appsAdapter = new AppsAdapter();
    /** Program cards are one view type for every row: scrolling to a new row reuses the cards the last one let go. */
    private final RecyclerView.RecycledViewPool cardPool = new RecyclerView.RecycledViewPool();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GtvRows");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PackageManager pm;
    private final float density;
    private final int cardHeight;
    /** cfg.animations without reduced_motion: scrolls glide and focus scales animate; otherwise they jump. */
    private final boolean smooth;
    private final boolean animations;
    private final int focusMs;
    private final boolean watchNext;
    private final int programsPerRow;
    /** config.xml hidden_apps (top_bar_apps included): never a round app either. */
    private final Set<String> configHidden;
    private final List<String> dockApps;

    private RowPrefs prefs;
    /** The apps row as shown, in order. */
    private final List<App> apps = new ArrayList<>();
    /** Apps by component and install path, so labels are read once and an updated app (new path) reloads its icon. */
    private final Map<String, App> known = new HashMap<>();
    /** Header icons of rows whose app has no round app (hidden, or no TV entry point). */
    private final Map<String, Drawable> headerIcons = new HashMap<>();
    /** Every row ChannelRepo returned, hidden ones too, so hiding and showing need no reload. */
    private List<ChannelRepo.Row> allRows = Collections.emptyList();
    /** The rows as shown, in order; adapter position = index + 1. */
    private final List<ChannelRepo.Row> rows = new ArrayList<>();
    /** Each row's sideways scroll while its view is recycled, by row key. */
    private final Map<String, Parcelable> rowStates = new HashMap<>();

    // Where focus last was, to go back to it after the lists change.
    private String lastAppPkg;
    private int lastAppIndex;
    /** The focused card's row key, null while focus is in the apps row. */
    private String focusRowKey;
    private int focusRowIndex;
    private long focusProgramId;

    /** The app or the row being moved, or null. */
    private String movingApp, movingRow;
    /** OK (or Back) went down while moving: only its release puts the item down, not the release that chose Move. */
    private boolean moveKeyDown;
    private int appsRowHeight;
    /**
     * The top bar (clock and pills) folds away while focus is in a channel row, like Google TV, and the list takes
     * its place. The header and the list's wrapper only translate (no relayout while moving); the wrapper has a
     * negative bottom margin of the same size, so the list is already tall enough when the header is gone.
     */
    private View header, headerWrap;
    private boolean headerHidden;
    private int headerShift;
    private static final int HEADER_MS = 200;
    private int loadSequence;
    private int focusToken;
    private boolean released;

    /** One round app: a TV entry point, the first one of its package. */
    private static final class App {
        final String pkg;
        final ComponentName component;
        final CharSequence label;
        final String key;
        final ResolveInfo info;
        Drawable icon;
        /** icon drawn edge to edge into a circle (GtvIcons), for the apps row; rendered once. */
        Drawable circleIcon;

        App(ResolveInfo info, CharSequence label, String key) {
            this.info = info;
            this.pkg = info.activityInfo.packageName;
            this.component = new ComponentName(pkg, info.activityInfo.name);
            this.label = label;
            this.key = key;
        }
    }

    GtvHome(MainActivity activity, RecyclerView home, Config cfg) {
        this.activity = activity;
        this.home = home;
        pm = activity.getPackageManager();
        density = activity.getResources().getDisplayMetrics().density;
        cardHeight = Math.round(CARD_HEIGHT_DP * density);
        animations = cfg.animations;
        smooth = cfg.animations && !cfg.reducedMotion;
        focusMs = cfg.reducedMotion ? Motion.REDUCED_MS : 160;
        watchNext = cfg.gtvWatchNext;
        programsPerRow = cfg.gtvProgramsPerRow;
        configHidden = new HashSet<>(cfg.hiddenApps);
        dockApps = cfg.dockApps;
        prefs = RowPrefs.load(RowPrefs.file(activity));
        cardPool.setMaxRecycledViews(0, 24);

        homeLayout = new LinearLayoutManager(activity) {
            /**
             * Every focus move lands here with the row that holds the new focus. The apps row scrolls back to the
             * top; a channel row is brought to the focus line, so the focused row sits in the top third while the
             * rest of the list moves past it. A move inside a row asks again with the row already in place.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                int dy = getPosition(child) == 0 ? child.getTop() - parent.getPaddingTop() : child.getTop() - focusLine();
                if (dy == 0) return false;
                scroll(parent, 0, dy, immediate);
                return true;
            }

            /** A screen more of rows laid out on each side: Up and Down then find the next row already there. */
            @Override
            protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extra) {
                extra[0] = getHeight();
                extra[1] = getHeight();
            }
        };
        home.setLayoutManager(homeLayout);
        home.setItemAnimator(null);
        home.setAdapter(homeAdapter);
        setUpHeader();
    }

    // ---- collapsing top bar ---------------------------------------------------------------------------

    private void setUpHeader() {
        header = activity.findViewById(R.id.topHeader);
        android.view.ViewParent parent = home.getParent();
        if (header == null || !(parent instanceof View)
                || !(((View) parent).getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            header = null;
            return;
        }
        headerWrap = (View) parent;
        header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) main.post(this::syncHeaderSpace);
        });
        activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (released || newF == null) return;
            View item = home.findContainingItemView(newF);
            if (item == null) {
                setHeaderHidden(false);   // top bar, or anything else outside the list
                return;
            }
            int at = home.getChildAdapterPosition(item);
            if (at >= 0) setHeaderHidden(at >= 1);   // the apps row is position 0
        });
    }

    /** Measures the header and gives the list's wrapper a matching negative bottom margin. */
    private void syncHeaderSpace() {
        if (header == null || released) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) headerWrap.getLayoutParams();
        int shift = header.getHeight() + lp.topMargin;
        if (header.getHeight() <= 0) return;
        headerShift = shift;
        if (lp.bottomMargin != -shift) {
            lp.bottomMargin = -shift;
            headerWrap.setLayoutParams(lp);
        }
        if (headerHidden) {
            header.setTranslationY(-shift);
            headerWrap.setTranslationY(-shift);
        }
    }

    private void setHeaderHidden(boolean hide) {
        if (header == null || hide == headerHidden) return;
        headerHidden = hide;
        syncHeaderSpace();
        float shift = hide ? -headerShift : 0f, alpha = hide ? 0f : 1f;
        header.animate().cancel();
        headerWrap.animate().cancel();
        int ms = !animations ? 0 : smooth ? HEADER_MS : focusMs;
        if (ms == 0) {
            header.setTranslationY(shift);
            header.setAlpha(alpha);
            headerWrap.setTranslationY(shift);
            return;
        }
        android.view.animation.Interpolator ease = new android.view.animation.DecelerateInterpolator(1.5f);
        header.animate().translationY(shift).alpha(alpha).setDuration(ms).setInterpolator(ease).start();
        headerWrap.animate().translationY(shift).setDuration(ms).setInterpolator(ease).start();
    }

    View view() {
        return home;
    }

    // ---- MainActivity's calls ---------------------------------------------------------------------

    /** The apps row's last focused app (the first one at the start), with the list back at the top. */
    void focusFirst() {
        if (home.getAdapter() == null) home.setAdapter(homeAdapter);
        int index = indexOfApp(lastAppPkg);
        final int at = index >= 0 ? index : 0;
        if (home.canScrollVertically(-1)) {
            // Focus the app once the list is back at the top, not where its row was before the jump.
            home.stopScroll();
            homeLayout.scrollToPositionWithOffset(0, 0);
            afterLayout(() -> focusApp(at));
            return;
        }
        focusApp(at);
    }

    /** Home pressed: back to the top and the first app, like the stock launcher. */
    void onHome() {
        endMove();
        setHeaderHidden(false);
        lastAppPkg = apps.isEmpty() ? null : apps.get(0).pkg;
        lastAppIndex = 0;
        AppsHolder holder = appsHolder();
        if (holder != null) holder.list.scrollToPosition(0);
        focusFirst();
    }

    /** Re-reads the saved order and hidden items, the apps, and the rows (in the background). */
    void refresh() {
        if (released) return;
        if (home.getAdapter() == null) home.setAdapter(homeAdapter);   // dropped by trim
        prefs = RowPrefs.load(RowPrefs.file(activity));
        keepingFocus(() -> {
            rebuildApps();
            showRows(visibleRows(allRows));
        });
        loadRows();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of the artwork and every row's views.
     * refresh() sets the list up again when Home comes back.
     */
    void trim() {
        ImageLoader.get(activity).trim();
        home.setAdapter(null);   // recycles the rows now, and with them their cards (onViewRecycled)
        home.getRecycledViewPool().clear();
        cardPool.clear();
        for (App app : apps) {
            app.icon = null;
            app.circleIcon = null;
        }
        known.clear();
        headerIcons.clear();
    }

    void release() {
        released = true;
        if (header != null) {
            header.animate().cancel();
            headerWrap.animate().cancel();
        }
        loader.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    /**
     * Up and Down inside the home list by adapter position, never by FocusFinder (which reads layout positions and
     * ignores the translation of the folding header): apps row (0) and channel rows 1..n, one step each. The
     * destination card is the one whose centre is nearest the current focus on screen. Down at the last row stays,
     * Up from the apps row goes to the top bar, Down from the top bar goes to the apps row. True when consumed.
     */
    boolean handleVerticalKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (released || (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN)) return false;
        if (movingApp != null || movingRow != null) return false;
        View focus = activity.getCurrentFocus();
        if (focus == null) return false;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean forward = code == KeyEvent.KEYCODE_DPAD_DOWN;
        View item = home.findContainingItemView(focus);
        if (item == null) {
            // The top bar: Down goes to the apps row (the pill's next-focus id would pick any child of the list).
            if (!forward || header == null || !isInside(focus, header)) return false;
            if (down && event.getRepeatCount() == 0) {
                int app = indexOfApp(lastAppPkg);
                focusApp(app >= 0 ? app : 0);
            }
            return true;
        }
        int at = home.getChildAdapterPosition(item);
        if (at < 0) return false;
        if (!down) return true;
        float x = centerX(focus);
        int to = forward ? at + 1 : at - 1;
        if (to < 0) {
            activity.focusTopBar();
        } else if (to == 0) {
            int app = indexOfApp(lastAppPkg);
            focusApp(app >= 0 ? app : 0);
        } else if (to <= rows.size()) {
            focusRow(to - 1, x);
        }   // else: below the last row, stay
        return true;
    }

    private boolean isInside(View v, View ancestor) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    /**
     * While an app or a row is being moved: Left/Right move the app, Up/Down the row, OK or Back puts it down and
     * saves the order. Every other arrow, OK and Menu is swallowed so nothing opens mid-move; volume and the
     * rest pass. False when nothing is being moved.
     */
    boolean handleMoveKey(KeyEvent event) {
        if (movingApp == null && movingRow == null) return false;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && movingApp != null) moveApp(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && movingRow != null) moveRow(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down) {
                    if (event.getRepeatCount() == 0) moveKeyDown = true;
                } else if (moveKeyDown) {
                    moveKeyDown = false;
                    endMove();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    /** Puts a moved app or row down and saves the order (also when Home is left mid-move). */
    void endMove() {
        moveKeyDown = false;
        if (movingApp != null) {
            String pkg = movingApp;
            movingApp = null;
            List<String> order = new ArrayList<>();
            for (App app : apps) order.add(app.pkg);
            prefs.setAppOrder(order);
            save();
            int at = indexOfApp(pkg);
            if (at >= 0) appsAdapter.notifyItemChanged(at);
        }
        if (movingRow != null) {
            String key = movingRow;
            movingRow = null;
            List<String> order = new ArrayList<>();
            for (ChannelRepo.Row row : rows) order.add(row.key);
            prefs.setRowOrder(order);
            save();
            int at = indexOfRow(key);
            if (at >= 0) homeAdapter.notifyItemChanged(at + 1);
            // Rows that arrived while it was moving.
            keepingFocus(() -> showRows(visibleRows(allRows)));
        }
    }

    // ---- apps ---------------------------------------------------------------------------------------

    /**
     * Every TV entry point once per package, without this launcher, hidden_apps and the apps hidden here. Saved
     * order first; apps never moved follow in dock_apps order, then by name.
     */
    private void rebuildApps() {
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        String own = activity.getPackageName();
        Set<String> seen = new HashSet<>();
        Map<String, App> stillThere = new HashMap<>();
        List<App> next = new ArrayList<>();
        for (ResolveInfo r : found) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(own) || configHidden.contains(pkg) || prefs.isAppHidden(pkg) || !seen.add(pkg)) continue;
            String key = pkg + "/" + r.activityInfo.name + "|" + r.activityInfo.applicationInfo.sourceDir;
            App app = known.get(key);
            if (app == null) app = new App(r, r.loadLabel(pm), key);
            stillThere.put(key, app);
            next.add(app);
        }
        known.clear();
        known.putAll(stillThere);
        Collections.sort(next, (a, b) -> {
            int da = dockRank(a.pkg), db = dockRank(b.pkg);
            if (da != db) return Integer.compare(da, db);
            return a.label.toString().compareToIgnoreCase(b.label.toString());
        });
        next = prefs.sortApps(next, a -> a.pkg);

        final List<App> old = new ArrayList<>(apps);
        final List<App> now = next;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return now.size(); }
            @Override public boolean areItemsTheSame(int o, int n) { return old.get(o).pkg.equals(now.get(n).pkg); }
            @Override public boolean areContentsTheSame(int o, int n) { return old.get(o) == now.get(n); }
        });
        apps.clear();
        apps.addAll(now);
        diff.dispatchUpdatesTo(appsAdapter);
    }

    private int dockRank(String pkg) {
        for (int i = 0; i < dockApps.size(); i++) {
            String entry = dockApps.get(i);
            if (entry.equals(pkg) || entry.startsWith(pkg + "/")) return i;
        }
        return Integer.MAX_VALUE;
    }

    private Drawable iconOf(App app) {
        if (app.icon == null) app.icon = app.info.loadIcon(pm);
        return app.icon;
    }

    private Drawable circleIconOf(App app) {
        if (app.circleIcon == null) {
            app.circleIcon = GtvIcons.circle(activity.getResources(), iconOf(app), Math.round(APP_ICON_DP * density));
        }
        return app.circleIcon;
    }

    private int indexOfApp(String pkg) {
        if (pkg == null) return -1;
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    private String appLabel(String pkg) {
        int at = indexOfApp(pkg);
        if (at >= 0) return apps.get(at).label.toString();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void launchApp(App app) {
        // The same leanback intent the stock launcher sends, so a running app resumes instead of restarting.
        activity.open(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    private void showAppMenu(App app, View anchor) {
        String label = app.label.toString();
        List<MainActivity.MenuEntry> entries = new ArrayList<>();
        if (apps.size() > 1) entries.add(entry(R.string.gtv_move, () -> startAppMove(app.pkg)));
        entries.add(entry(R.string.gtv_hide_app, () -> {
            prefs.setAppHidden(app.pkg, label, true);
            saveAndApply();
        }));
        boolean rowsHidden = prefs.areAppRowsHidden(app.pkg);
        entries.add(entry(rowsHidden ? R.string.gtv_show_app_rows : R.string.gtv_hide_app_rows, () -> {
            prefs.setAppRowsHidden(app.pkg, label, !rowsHidden);
            saveAndApply();
        }));
        // Some firmware has no App info page at all; a row that only toasts "can't open" helps nobody.
        Intent info = SystemSettings.appInfo(activity, app.pkg);
        if (info != null) entries.add(entry(R.string.library_app_info, () -> activity.open(info)));
        activity.showMenu(anchor, entries);
    }

    private void startAppMove(String pkg) {
        int at = indexOfApp(pkg);
        if (at < 0) return;
        movingApp = pkg;
        moveKeyDown = false;
        appsAdapter.notifyItemChanged(at);
        Toast.makeText(activity, R.string.gtv_move_hint_app, Toast.LENGTH_LONG).show();
    }

    private void moveApp(int step) {
        int from = indexOfApp(movingApp), to = from + step;
        if (from < 0 || to < 0 || to >= apps.size()) return;
        Collections.swap(apps, from, to);
        appsAdapter.notifyItemMoved(from, to);
        lastAppIndex = to;
        AppsHolder holder = appsHolder();
        if (holder != null) holder.list.scrollToPosition(to);
    }

    // ---- rows ---------------------------------------------------------------------------------------

    private void loadRows() {
        final int sequence = ++loadSequence;
        if (!ChannelRepo.hasPermission(activity)) {
            // Without READ_TV_LISTINGS there are no rows to read: the apps row alone.
            allRows = Collections.emptyList();
            keepingFocus(() -> showRows(allRows));
            return;
        }
        loader.execute(() -> {
            List<ChannelRepo.Row> loaded = ChannelRepo.load(activity, programsPerRow, watchNext);
            main.post(() -> {
                if (released || sequence != loadSequence) return;
                allRows = loaded;
                if (movingRow != null) return;   // endMove shows them
                keepingFocus(() -> showRows(visibleRows(loaded)));
            });
        });
    }

    /** The rows not hidden here (by row, by app, or with their app hidden), in the saved order. */
    private List<ChannelRepo.Row> visibleRows(List<ChannelRepo.Row> all) {
        List<ChannelRepo.Row> visible = new ArrayList<>();
        for (ChannelRepo.Row row : all) {
            if (prefs.isRowHidden(row.key)) continue;
            if (!row.pkg.isEmpty() && (prefs.areAppRowsHidden(row.pkg) || prefs.isAppHidden(row.pkg))) continue;
            visible.add(row);
        }
        return prefs.sortRows(visible, row -> row.key);
    }

    /** Updates the rows in place (DiffUtil by row key), so rows and cards that stay keep their views and focus. */
    private void showRows(List<ChannelRepo.Row> next) {
        if (sameRows(rows, next)) return;
        final List<ChannelRepo.Row> old = new ArrayList<>(rows);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size() + 1; }
            @Override public int getNewListSize() { return next.size() + 1; }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                if (o == 0 || n == 0) return o == n;   // the apps row
                return old.get(o - 1).key.equals(next.get(n - 1).key);
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                return o == 0 || sameRow(old.get(o - 1), next.get(n - 1));
            }
        });
        rows.clear();
        rows.addAll(next);
        diff.dispatchUpdatesTo(homeAdapter);
    }

    private static boolean sameRows(List<ChannelRepo.Row> a, List<ChannelRepo.Row> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!sameRow(a.get(i), b.get(i))) return false;
        return true;
    }

    private static boolean sameRow(ChannelRepo.Row a, ChannelRepo.Row b) {
        if (!a.key.equals(b.key) || !a.pkg.equals(b.pkg) || !a.title.equals(b.title)
                || a.programs.size() != b.programs.size()) return false;
        for (int i = 0; i < a.programs.size(); i++) if (!sameProgram(a.programs.get(i), b.programs.get(i))) return false;
        return true;
    }

    private static boolean sameProgram(ChannelRepo.Program a, ChannelRepo.Program b) {
        return a.id == b.id && a.aspect == b.aspect && a.pkg.equals(b.pkg) && Objects.equals(a.title, b.title)
                && Objects.equals(a.imageUri, b.imageUri) && Objects.equals(a.intentUri, b.intentUri);
    }

    private int indexOfRow(String key) {
        if (key == null) return -1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).key.equals(key)) return i;
        return -1;
    }

    private Drawable headerIcon(String pkg) {
        if (pkg.isEmpty()) return null;   // Watch Next gathers many apps
        int at = indexOfApp(pkg);
        if (at >= 0) return iconOf(apps.get(at));
        if (headerIcons.containsKey(pkg)) return headerIcons.get(pkg);
        Drawable icon;
        try {
            icon = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            icon = null;
        }
        headerIcons.put(pkg, icon);
        return icon;
    }

    /**
     * The programme's own intent; its app's TV entry point when it has none, it does not parse or nothing opens it.
     * The intent comes from another app's channel, so it is sent without URI grants or a selector and never to this
     * launcher: it can open that app, but borrows none of this launcher's rights.
     */
    private void launchProgram(ChannelRepo.Program program) {
        if (program.intentUri != null) {
            try {
                Intent intent = Intent.parseUri(program.intentUri, Intent.URI_INTENT_SCHEME);
                intent.setSelector(null);
                intent.setClipData(null);
                intent.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                String own = activity.getPackageName();
                boolean ours = own.equals(intent.getPackage())
                        || (intent.getComponent() != null && own.equals(intent.getComponent().getPackageName()));
                if (!ours) {
                    activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
            } catch (Exception e) {
                // Falls back to the app below.
            }
        }
        Intent fallback = program.pkg.isEmpty() ? null : pm.getLeanbackLaunchIntentForPackage(program.pkg);
        if (fallback != null) activity.open(fallback);
        else Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    private void showRowMenu(ChannelRepo.Row row, View anchor) {
        List<MainActivity.MenuEntry> entries = new ArrayList<>();
        if (rows.size() > 1) entries.add(entry(R.string.gtv_move_row, () -> startRowMove(row.key)));
        entries.add(entry(R.string.gtv_hide_row, () -> {
            prefs.setRowHidden(row.key, row.title, true);
            saveAndApply();
        }));
        if (!WATCH_NEXT.equals(row.key) && !row.pkg.isEmpty()) {
            String label = appLabel(row.pkg);
            entries.add(new MainActivity.MenuEntry(activity.getString(R.string.gtv_hide_all_rows, label), () -> {
                prefs.setAppRowsHidden(row.pkg, label, true);
                saveAndApply();
            }));
        }
        activity.showMenu(anchor, entries);
    }

    private void startRowMove(String key) {
        int at = indexOfRow(key);
        if (at < 0) return;
        movingRow = key;
        moveKeyDown = false;
        homeAdapter.notifyItemChanged(at + 1);
        Toast.makeText(activity, R.string.gtv_move_hint_row, Toast.LENGTH_LONG).show();
    }

    private void moveRow(int step) {
        int from = indexOfRow(movingRow), to = from + step;
        if (from < 0 || to < 0 || to >= rows.size()) return;
        Collections.swap(rows, from, to);
        homeAdapter.notifyItemMoved(from + 1, to + 1);
        focusRowIndex = to;
        // The focused card moves with its row; keep the row on the focus line.
        afterLayout(() -> {
            View row = homeLayout.findViewByPosition(to + 1);
            if (row != null) scroll(home, 0, row.getTop() - focusLine(), false);
        });
    }

    // ---- prefs ----------------------------------------------------------------------------------------

    private MainActivity.MenuEntry entry(int title, Runnable action) {
        return new MainActivity.MenuEntry(activity.getText(title), action);
    }

    private void save() {
        if (!prefs.save(RowPrefs.file(activity))) {
            Toast.makeText(activity, R.string.gtv_saved_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** A hide or show from a menu: saved, then the apps and rows filtered again from what is already loaded. */
    private void saveAndApply() {
        save();
        keepingFocus(() -> {
            rebuildApps();
            showRows(visibleRows(allRows));
        });
    }

    // ---- focus ----------------------------------------------------------------------------------------

    /**
     * The line a focused channel row's top is scrolled to. The top bar folds away while a row has focus, so the row
     * goes to the top of the list; without the folding header it stays under the apps row, no lower than a third down.
     */
    private int focusLine() {
        // Folded header: the row's top sits on the list's top edge (the wrapper clips there, so the row above is gone
        // and the focused row's title lands about 40dp from the top of the screen).
        if (header != null) return 0;
        int third = home.getHeight() / 3;
        return appsRowHeight > 0 ? Math.min(third, home.getPaddingTop() + appsRowHeight) : third;
    }

    private void scroll(RecyclerView list, int dx, int dy, boolean immediate) {
        if (immediate || !smooth) list.scrollBy(dx, dy);
        else list.smoothScrollBy(dx, dy);
    }

    /**
     * Runs a list change; if it took focus from a removed app or row (Android then hands focus to the first view
     * of the window, a top-bar pill), focus goes back to the nearest app or card once the list is laid out.
     */
    private void keepingFocus(Runnable change) {
        boolean had = home.hasFocus();
        change.run();
        if (had) afterLayout(() -> {
            if (!home.hasFocus()) refocus();
        });
    }

    private void refocus() {
        if (focusRowKey != null && !rows.isEmpty()) {
            int r = indexOfRow(focusRowKey);
            // The row went: the one that took its place, or the last one.
            focusProgram(r >= 0 ? r : Math.min(focusRowIndex, rows.size() - 1), focusProgramId);
            return;
        }
        int at = indexOfApp(lastAppPkg);
        focusApp(at >= 0 ? at : Math.min(lastAppIndex, apps.size() - 1));
    }

    /** Runs once after the next layout of Home, unless another focus move was asked for in between. */
    private void afterLayout(Runnable action) {
        final int token = ++focusToken;
        home.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                home.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (token == focusToken && !released) action.run();
            }
        });
        home.requestLayout();   // so the next layout is soon, not whenever something else changes
    }

    private AppsHolder appsHolder() {
        RecyclerView.ViewHolder holder = home.findViewHolderForAdapterPosition(0);
        return holder instanceof AppsHolder ? (AppsHolder) holder : null;
    }

    private RowHolder rowHolder(int r) {
        RecyclerView.ViewHolder holder = home.findViewHolderForAdapterPosition(r + 1);
        return holder instanceof RowHolder ? (RowHolder) holder : null;
    }

    private void focusApp(int index) {
        if (apps.isEmpty()) {
            if (!rows.isEmpty()) focusRow(0, 0f);
            else activity.focusTopBar();
            return;
        }
        final int at = Math.max(0, Math.min(index, apps.size() - 1));
        AppsHolder holder = appsHolder();
        if (holder != null && holder.list.getAdapter() != null && focusAt(holder.list, at)) return;
        afterLayout(() -> {
            AppsHolder again = appsHolder();
            if (again != null) focusAt(again.list, at);
        });
    }

    /** Down or Up into row r: the card whose centre is nearest x (screen pixels). */
    private void focusRow(int r, float x) {
        if (r < 0 || r >= rows.size()) return;
        RowHolder holder = rowHolder(r);
        if (holder != null && focusNearest(holder.list, x)) return;
        home.stopScroll();
        homeLayout.scrollToPositionWithOffset(r + 1, focusLine());
        afterLayout(() -> {
            RowHolder again = rowHolder(r);
            if (again != null) focusNearest(again.list, x);
        });
    }

    private void focusProgram(int r, long id) {
        RowHolder holder = rowHolder(r);
        if (holder == null) {
            home.stopScroll();
            homeLayout.scrollToPositionWithOffset(r + 1, focusLine());
            afterLayout(() -> {
                RowHolder again = rowHolder(r);
                if (again != null) focusAt(again.list, again.programs.positionOf(id));
            });
            return;
        }
        focusAt(holder.list, holder.programs.positionOf(id));
    }

    /** Focuses the item at position in a sideways list, scrolling it in first when it is not laid out. */
    private boolean focusAt(RecyclerView list, int position) {
        RecyclerView.LayoutManager layout = list.getLayoutManager();
        if (layout == null) return false;
        View item = layout.findViewByPosition(position);
        if (item != null) return item.requestFocus();
        list.scrollToPosition(position);
        afterLayout(() -> {
            View again = layout.findViewByPosition(position);
            if (again != null) again.requestFocus();
        });
        return true;
    }

    private static boolean focusNearest(RecyclerView list, float x) {
        View best = null;
        float bestDistance = Float.MAX_VALUE;
        int[] at = new int[2];
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            child.getLocationOnScreen(at);
            float distance = Math.abs(at[0] + child.getWidth() / 2f - x);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = child;
            }
        }
        return best != null && best.requestFocus();
    }

    private static float centerX(View v) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return at[0] + v.getWidth() / 2f;
    }

    private void scaleOnFocus(View v, boolean focused, float scale) {
        float to = focused ? scale : 1f;
        v.animate().cancel();
        if (animations) {
            v.animate().scaleX(to).scaleY(to).setDuration(focusMs).start();
        } else {
            v.setScaleX(to);
            v.setScaleY(to);
        }
    }

    /** A sideways list that scrolls just enough to keep the focused item inside its side padding. */
    private LinearLayoutManager sidewaysLayout() {
        LinearLayoutManager layout = new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false) {
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                int start = parent.getPaddingLeft(), end = parent.getWidth() - parent.getPaddingRight();
                int dx = child.getLeft() < start ? child.getLeft() - start
                        : child.getRight() > end ? child.getRight() - end : 0;
                if (dx == 0) return false;
                scroll(parent, dx, 0, immediate);
                return true;
            }
        };
        layout.setInitialPrefetchItemCount(6);
        return layout;
    }

    // ---- adapters -------------------------------------------------------------------------------------

    private final class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_APPS : TYPE_ROW;
        }

        @Override
        public int getItemCount() {
            return rows.size() + 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gtv_row, parent, false);
            return type == TYPE_APPS ? new AppsHolder(v) : new RowHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof RowHolder) ((RowHolder) holder).bind(rows.get(position - 1));
            else ((AppsHolder) holder).bind();
        }

        /** A row leaving the screen hands its cards to the shared pool (and their images to ImageLoader). */
        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof RowHolder) {
                RowHolder row = (RowHolder) holder;
                row.saveState();
                row.list.setAdapter(null);
            } else {
                ((AppsHolder) holder).list.setAdapter(null);
            }
        }
    }

    private final class AppsHolder extends RecyclerView.ViewHolder {
        final RecyclerView list;

        AppsHolder(View v) {
            super(v);
            v.findViewById(R.id.gtvRowHeader).setVisibility(View.GONE);
            list = v.findViewById(R.id.gtvRowList);
            list.setLayoutManager(sidewaysLayout());
            list.setItemAnimator(null);
            v.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> appsRowHeight = b - t);
        }

        void bind() {
            if (list.getAdapter() != null) return;
            list.setAdapter(appsAdapter);
            int at = indexOfApp(lastAppPkg);
            if (at > 0) list.scrollToPosition(at);
        }
    }

    private final class AppsAdapter extends RecyclerView.Adapter<AppHolder> {
        @Override
        public int getItemCount() {
            return apps.size();
        }

        @NonNull
        @Override
        public AppHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new AppHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gtv_app, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull AppHolder holder, int position) {
            App app = apps.get(position);
            holder.label.setText(app.label);
            holder.itemView.setContentDescription(app.label);
            holder.icon.setImageDrawable(circleIconOf(app));
            boolean moving = app.pkg.equals(movingApp);
            holder.itemView.setSelected(moving);
            // The app being moved is lifted with the blue ring.
            holder.circle.setTranslationY(moving ? -8 * density : 0f);
            float scale = holder.itemView.isFocused() ? APP_FOCUS_SCALE : 1f;
            holder.circle.setScaleX(scale);
            holder.circle.setScaleY(scale);
        }
    }

    private final class AppHolder extends RecyclerView.ViewHolder {
        final View circle;
        final ImageView icon;
        final TextView label;

        AppHolder(View v) {
            super(v);
            circle = v.findViewById(R.id.gtvCircle);
            icon = v.findViewById(R.id.gtvIcon);
            label = v.findViewById(R.id.gtvLabel);
            v.setOnClickListener(view -> {
                App app = app();
                if (app != null && movingApp == null) launchApp(app);
            });
            v.setOnLongClickListener(view -> {
                App app = app();
                if (app != null && movingApp == null && movingRow == null) showAppMenu(app, view);
                return true;
            });
            v.setOnFocusChangeListener((view, focused) -> {
                scaleOnFocus(circle, focused, APP_FOCUS_SCALE);
                App app = app();
                if (focused && app != null) {
                    lastAppPkg = app.pkg;
                    lastAppIndex = getBindingAdapterPosition();
                    focusRowKey = null;
                }
            });
            v.setOnKeyListener((view, code, event) -> {
                if (code == KeyEvent.KEYCODE_MENU) {
                    App app = app();
                    if (app != null && RemoteKeys.menuReleased(event.getAction(), event.getFlags())) showAppMenu(app, view);
                    return true;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int at = getBindingAdapterPosition();
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        activity.focusTopBar();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        focusRow(0, centerX(view));
                        return true;   // no rows: stay on the app
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return at == 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return at == apps.size() - 1;
                    default:
                        return false;
                }
            });
        }

        App app() {
            int at = getBindingAdapterPosition();
            return at >= 0 && at < apps.size() ? apps.get(at) : null;
        }
    }

    private final class RowHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final RecyclerView list;
        final LinearLayoutManager layout;
        final ProgramAdapter programs = new ProgramAdapter(this);
        ChannelRepo.Row row;

        RowHolder(View v) {
            super(v);
            icon = v.findViewById(R.id.gtvRowIcon);
            title = v.findViewById(R.id.gtvRowTitle);
            list = v.findViewById(R.id.gtvRowList);
            layout = sidewaysLayout();
            list.setLayoutManager(layout);
            list.setRecycledViewPool(cardPool);
            list.setItemAnimator(null);
        }

        void bind(ChannelRepo.Row next) {
            boolean same = row != null && row.key.equals(next.key) && list.getAdapter() != null;
            if (!same && list.getAdapter() != null) saveState();
            row = next;
            title.setText(next.title);
            boolean moving = next.key.equals(movingRow);
            title.setTextColor(moving ? MOVING_ACCENT : ROW_TITLE_COLOR);
            Drawable header = headerIcon(next.pkg);
            icon.setImageDrawable(header);
            icon.setVisibility(header != null ? View.VISIBLE : View.GONE);
            if (same) {
                programs.update(next.programs);
            } else {
                programs.replace(next.programs);
                if (list.getAdapter() == null) list.setAdapter(programs);
                Parcelable state = rowStates.get(next.key);
                if (state != null) layout.onRestoreInstanceState(state);
                else layout.scrollToPosition(0);
            }
            // A row being moved rings all its cards in blue (gtv_card_ring's activated state).
            itemView.setActivated(moving);
        }

        void saveState() {
            if (row != null) rowStates.put(row.key, layout.onSaveInstanceState());
        }

        int rowIndex() {
            return getBindingAdapterPosition() - 1;
        }
    }

    private final class ProgramAdapter extends RecyclerView.Adapter<CardHolder> {
        final RowHolder owner;
        List<ChannelRepo.Program> programs = Collections.emptyList();

        ProgramAdapter(RowHolder owner) {
            this.owner = owner;
        }

        @SuppressLint("NotifyDataSetChanged")   // a different row: every card changes
        void replace(List<ChannelRepo.Program> next) {
            programs = next;
            notifyDataSetChanged();
        }

        /** The same row with new programmes: cards that stay keep their views, so focus stays on them. */
        void update(List<ChannelRepo.Program> next) {
            final List<ChannelRepo.Program> old = programs;
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return old.size(); }
                @Override public int getNewListSize() { return next.size(); }
                @Override public boolean areItemsTheSame(int o, int n) {
                    return old.get(o).id == next.get(n).id && old.get(o).pkg.equals(next.get(n).pkg);
                }
                @Override public boolean areContentsTheSame(int o, int n) { return sameProgram(old.get(o), next.get(n)); }
            });
            programs = next;
            diff.dispatchUpdatesTo(this);
        }

        int positionOf(long id) {
            for (int i = 0; i < programs.size(); i++) if (programs.get(i).id == id) return i;
            return 0;
        }

        @Override
        public int getItemCount() {
            return programs.size();
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new CardHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gtv_program, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder holder, int position) {
            ChannelRepo.Program program = programs.get(position);
            holder.owner = owner;
            holder.program = program;
            float aspect = program.aspect > 0f ? program.aspect : DEFAULT_ASPECT;
            int width = Math.round(cardHeight * Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, aspect)));
            ViewGroup.LayoutParams lp = holder.card.getLayoutParams();
            if (lp.width != width || lp.height != cardHeight) {
                lp.width = width;
                lp.height = cardHeight;
                holder.card.setLayoutParams(lp);
            }
            boolean art = program.imageUri != null;
            holder.cardTitle.setText(art ? null : program.title);
            holder.cardTitle.setVisibility(art ? View.GONE : View.VISIBLE);
            holder.title.setText(program.title);
            holder.itemView.setContentDescription(program.title);
            ImageLoader.get(activity).load(holder.image, program.imageUri, width, cardHeight);
            boolean focused = holder.itemView.isFocused();
            holder.title.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            float scale = focused ? CARD_FOCUS_SCALE : 1f;
            holder.card.setScaleX(scale);
            holder.card.setScaleY(scale);
            holder.itemView.setActivated(owner.row != null && owner.row.key.equals(movingRow));
        }

        @Override
        public void onViewRecycled(@NonNull CardHolder holder) {
            ImageLoader.get(activity).cancel(holder.image);
            holder.image.setImageDrawable(null);
            holder.card.animate().cancel();
            holder.program = null;
        }
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView image;
        final TextView cardTitle;
        final TextView title;
        RowHolder owner;
        ChannelRepo.Program program;

        CardHolder(View v) {
            super(v);
            card = v.findViewById(R.id.gtvCard);
            image = v.findViewById(R.id.gtvImage);
            cardTitle = v.findViewById(R.id.gtvCardTitle);
            title = v.findViewById(R.id.gtvTitle);
            card.setClipToOutline(true);   // rounds the poster to gtv_card_bg's corners
            v.setOnClickListener(view -> {
                if (program != null && movingApp == null && movingRow == null) launchProgram(program);
            });
            v.setOnLongClickListener(view -> {
                if (owner != null && owner.row != null && movingApp == null && movingRow == null) showRowMenu(owner.row, view);
                return true;
            });
            v.setOnFocusChangeListener((view, focused) -> {
                scaleOnFocus(card, focused, CARD_FOCUS_SCALE);
                title.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
                if (focused && owner != null && owner.row != null && program != null) {
                    focusRowKey = owner.row.key;
                    focusRowIndex = Math.max(0, owner.rowIndex());
                    focusProgramId = program.id;
                }
            });
            v.setOnKeyListener((view, code, event) -> {
                if (code == KeyEvent.KEYCODE_MENU) {
                    if (owner != null && owner.row != null
                            && RemoteKeys.menuReleased(event.getAction(), event.getFlags())) showRowMenu(owner.row, view);
                    return true;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN || owner == null) return false;
                int r = owner.rowIndex();
                int at = getBindingAdapterPosition();
                if (r < 0) return false;
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (r == 0) {
                            int app = indexOfApp(lastAppPkg);
                            focusApp(app >= 0 ? app : 0);
                        } else {
                            focusRow(r - 1, centerX(view));
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        focusRow(r + 1, centerX(view));
                        return true;   // the last row: stay
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return at == 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return at == owner.programs.getItemCount() - 1;
                    default:
                        return false;
                }
            });
        }
    }
}
