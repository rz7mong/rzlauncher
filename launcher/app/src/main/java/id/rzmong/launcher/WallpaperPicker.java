package id.rzmong.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * MOD (OwnTV fork): D-pad wallpaper picker, full-screen overlay.
 * Browses internal storage from /storage/emulated/0: folders first, then
 * JPG/JPEG/PNG/MP4 files in the current folder. OK enters a folder or applies
 * a file, Back goes to the parent folder, Back at the root closes without
 * changing anything. "Chainsaw Man (bawaan)" is always the first row. .mpkg/.pkg
 * Wallpaper Engine files are NOT listed and the picker says so in plain text.
 * If the Wallpaper folder cannot be created, a clear note is shown instead of
 * a silently empty list; other folders stay browsable. The host owns applying
 * the choice and restoring focus when the picker closes.
 *
 * MOD 1.2.3-navfix — MODAL navigation (see MainActivity.dispatchKeyEvent).
 * The pre-1.2.3 picker was NOT modal, and that is how a picker key launched
 * a home app during the 1.2.2 MP4 test: each row only consumed BACK and
 * CENTER on ACTION_DOWN and returned false for everything else, so DPAD
 * keys fell through to Android's window-global focus search (which can
 * leave the picker for a grid tile, a pill or a panel row — LEFT/RIGHT
 * from any picker row have no picker neighbour at all), and a CENTER
 * ACTION_UP was never consumed, so after a CENTER DOWN applied a file
 * and closed the picker, the UP landed on the grid tile that focus had
 * just been restored to and its default click launched that app. The
 * fix: keys are intercepted at the activity level while the picker is
 * visible and handled here by index only — no FocusFinder navigation,
 * no fall-through, no tile ever sees a picker key. This class therefore
 * owns an explicit row list and focused index; rows keep only a pointer
 * click listener, never a key listener.
 */
final class WallpaperPicker extends FrameLayout {

    interface Host {
        void onWallpaperPicked(String choice); // "builtin" or an absolute file path

        void onPickerClosedAtRoot(); // Back at root: no change; host restores focus to Settings > Wallpaper

        void onPickerClosedAfterPick(); // OK on a file: choice applied; host restores focus
    }

    private static final long NAV_REPEAT_MIN_MS = 85; // same cap as the home grid's held-key throttle

    private final Host host;
    private final TextView subView, noteView;
    private final LinearLayout list;
    private final ScrollView scroll;
    private File dir;
    private final File root;
    private String currentChoice;
    private boolean wallpaperDirOk = true;
    // MOD 1.2.3-navfix: explicit row model for index-only navigation. The
    // focused row is always one of these, never whatever FocusFinder found.
    private final List<View> rowViews = new ArrayList<>();
    private final List<Runnable> rowActions = new ArrayList<>();
    private int focusedIndex = 0;
    private long lastNavAt = 0;

    WallpaperPicker(Context c, Host host, String currentChoice) {
        super(c);
        this.host = host;
        this.currentChoice = currentChoice == null ? "builtin" : currentChoice;
        this.root = Environment.getExternalStorageDirectory();
        setBackgroundColor(0xE6000000);
        setFocusable(true);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -1);
        col.setPadding(Ui.px(120), Ui.px(80), Ui.px(120), Ui.px(80));
        addView(col, clp);

        TextView title = new TextView(c);
        title.setText("Pilih Wallpaper");
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.f(56));
        title.setTypeface(Ui.semibold);
        title.setTextColor(Color.WHITE);
        col.addView(title);

        subView = new TextView(c);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.f(24));
        subView.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = Ui.px(16);
        col.addView(subView, slp);

        noteView = new TextView(c);
        noteView.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.f(22));
        noteView.setTextColor(0xFFFFD54F);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = Ui.px(12);
        col.addView(noteView, nlp);

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sclp.topMargin = Ui.px(32);
        col.addView(scroll, sclp);

        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));

        TextView hint = new TextView(c);
        hint.setText("OK = buka folder / terapkan  •  Back = folder atas / tutup");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.f(22));
        hint.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = Ui.px(24);
        col.addView(hint, hlp);

        TextView note2 = new TextView(c);
        note2.setText("Format: JPG, PNG, MP4. File .mpkg/.pkg Wallpaper Engine tidak bisa dipakai di sini.");
        note2.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.f(20));
        note2.setTextColor(0xAAFFFFFF);
        LinearLayout.LayoutParams n2lp = new LinearLayout.LayoutParams(-2, -2);
        n2lp.topMargin = Ui.px(8);
        col.addView(note2, n2lp);

        // Folder creation is attempted once; a failure is reported, not hidden.
        try {
            File wd = new File(root, "Wallpaper");
            wallpaperDirOk = wd.exists() || wd.mkdirs();
        } catch (Exception ignored) {
            wallpaperDirOk = false;
        }
        dir = root;
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        rowViews.clear();
        rowActions.clear();
        focusedIndex = 0;
        subView.setText("Memori internal: " + dir.getAbsolutePath());
        if (!wallpaperDirOk) {
            noteView.setText("Folder Wallpaper tidak bisa dibuat di perangkat ini — tapi folder lain di memori internal tetap bisa dijelajahi di bawah.");
        } else {
            noteView.setText("");
        }
        List<File> folders = new ArrayList<>();
        List<File> files = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory() && !f.getName().startsWith(".")) folders.add(f);
                else if (f.isFile() && (FitBackgroundView.isImageFile(f.getName()) || FitBackgroundView.isVideoFile(f.getName()))) files.add(f);
            }
        }
        Comparator<File> byName = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        Collections.sort(folders, byName);
        Collections.sort(files, byName);
        subView.append("\n" + folders.size() + " folder, " + files.size() + " file wallpaper di folder ini.");

        addRow("Chainsaw Man (bawaan)" + ("builtin".equals(currentChoice) ? "  ✓ Aktif" : ""), () -> {
            host.onWallpaperPicked("builtin");
            closeAfterPick();
        }, true);
        for (File f : folders) {
            final File folder = f;
            addRow("[Folder] " + f.getName(), () -> {
                dir = folder;
                refresh();
            }, false);
        }
        for (File f : files) {
            final File file = f;
            boolean active = file.getAbsolutePath().equals(currentChoice);
            addRow(f.getName() + (active ? "  ✓ Aktif" : ""), () -> {
                host.onWallpaperPicked(file.getAbsolutePath());
                closeAfterPick();
            }, false);
        }
        // MOD 1.2.3-navfix: focus is always the first row after a refresh
        // (folder entry, parent return, fresh open) — never left to a
        // FocusFinder search that could land outside the picker.
        if (!rowViews.isEmpty()) rowViews.get(0).requestFocus();
    }

    private void addRow(String text, Runnable action, boolean first) {
        final TextView row = new TextView(getContext());
        row.setText(text);
        row.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.f(28));
        row.setTypeface(Ui.semibold);
        row.setTextColor(Color.WHITE);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(Ui.px(24), Ui.px(18), Ui.px(24), Ui.px(18));
        row.setOnFocusChangeListener((v, has) -> {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(Ui.f(16));
            d.setColor(has ? 0xFFF0B95A : 0x00000000);
            row.setBackground(d);
            row.setTextColor(has ? Color.BLACK : Color.WHITE);
        });
        // MOD 1.2.3-navfix: pointer clicks only. Keys NEVER reach this
        // listener: MainActivity.dispatchKeyEvent intercepts every key
        // while the picker is visible and routes it to handleKey() below.
        // A row key listener that returned false for DPAD/UP events was
        // one of the leak paths (see class comment).
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.px(6);
        list.addView(row, lp);
        rowViews.add(row);
        rowActions.add(action);
    }

    /**
     * MOD 1.2.3-navfix: the ONLY key path while the picker is visible.
     * Called from MainActivity.dispatchKeyEvent, which returns this
     * method's result (always true) so no key ever reaches a grid tile,
     * pill, panel row or the default focus search. Index-only navigation:
     * UP/DOWN move within this list and clamp at the ends, LEFT/RIGHT are
     * consumed no-ops (no picker neighbour exists in those directions, so
     * letting FocusFinder run would escape the picker), and every
     * ACTION_UP is consumed so a CENTER that closed the picker cannot
     * fall through as a click on whatever focus lands on next.
     */
    boolean handleKey(int code, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true; // consume all key-ups, no action
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                onBackPressed();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!rowActions.isEmpty() && focusedIndex >= 0 && focusedIndex < rowActions.size()) {
                    rowActions.get(focusedIndex).run();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveFocus(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveFocus(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true; // consumed: there is no picker row in these directions
            default:
                return true; // modal: MENU/SEARCH/VOICE must not open anything mid-picker
        }
    }

    private void moveFocus(int delta) {
        if (rowViews.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastNavAt < NAV_REPEAT_MIN_MS) return; // held-key cap, as on the home grid
        lastNavAt = now;
        int next = Math.max(0, Math.min(focusedIndex + delta, rowViews.size() - 1));
        if (next == focusedIndex) return; // clamped at the end: stay, never escape
        focusedIndex = next;
        rowViews.get(focusedIndex).requestFocus();
    }

    /** Defensive re-assert: if focus ever stops being a picker row while
     * the picker is visible, put it back on the current row. Called from
     * the activity's key dispatch before any key is acted on. */
    void reassertFocus() {
        if (rowViews.isEmpty()) return;
        focusedIndex = Math.max(0, Math.min(focusedIndex, rowViews.size() - 1));
        rowViews.get(focusedIndex).requestFocus();
    }

    /** Back inside the picker: parent folder, or close at the root. Returns true if handled. */
    boolean onBackPressed() {
        if (dir != null && !dir.equals(root) && dir.getParentFile() != null) {
            dir = dir.getParentFile();
            refresh();
            return true;
        }
        closeAtRoot();
        return true;
    }

    void close() {
        closeAtRoot();
    }

    /** Back at root: hide without changing the wallpaper; host restores
     * focus to the Settings > Wallpaper row. */
    void closeAtRoot() {
        setVisibility(View.GONE);
        host.onPickerClosedAtRoot();
    }

    /** OK on a file: choice already applied; host restores focus. */
    void closeAfterPick() {
        setVisibility(View.GONE);
        host.onPickerClosedAfterPick();
    }

    void open() {
        open(currentChoice);
    }

    /** MOD 1.2.3-navfix: open with a fresh choice. The old open() reused
     * the choice from construction time, so after any pick the picker
     * kept showing "Bawaan ✓ Aktif" even when another file was active
     * (that stale marker is what the 1.2.2 MP4 test screencaps showed). */
    void open(String choice) {
        currentChoice = choice == null ? "builtin" : choice;
        dir = root;
        refresh();
        setVisibility(View.VISIBLE);
        reassertFocus();
    }
}
