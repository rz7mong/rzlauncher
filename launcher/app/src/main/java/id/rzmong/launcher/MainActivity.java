package id.rzmong.launcher;

import android.app.ActivityManager;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.media.MediaPlayer;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import java.io.DataOutputStream;
import java.io.IOException;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen in the approved design: a wallpaper that glides with the focus, app banners in five
 * columns, one frosted capsule top-right (Inputs, network, Settings, clock) and a frosted side panel
 * for Inputs and for the hold-OK menu. Navigation is decided here, by grid index, so focus always moves
 * exactly one tile in the pressed direction and never gets lost.
 */
public class MainActivity extends Activity implements AppAdapter.Host {

    // MOD 1.2.7-tiles-sound: 7 columns (was 5). Horizontal navigation
    // uses menuSpan() (COLS, or 1 for sidebars), so the span follows
    // the menu placement automatically.
    private static final int COLS = 7;
    private static final long REPEAT_MIN_MS = 85;      // held key: at most ~12 moves per second
    private static final Object HINT_TOKEN = new Object();
    private static final PathInterpolator EASE_OUT = new PathInterpolator(0.22f, 0.85f, 0.3f, 1f);
    /** Settles with a small overshoot, like a light spring. */
    private static final PathInterpolator SPRING_OUT = new PathInterpolator(0.3f, 1.32f, 0.5f, 1f);

    // Survive the activity being recreated while an app is in front; reset only when the process dies.
    private static String lastKey;
    private static int lastPos;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private Sounds sounds;

    private FrameLayout root;
    private ImageView wall, wallBlur;
    private RecyclerView grid;
    private GridLayoutManager gridLayout;
    private FrameLayout gridViewport;
    private int modGridSide, modGridCellW, modScreenW, modScreenH;
    // MOD 1.2.12: height of the bottom-anchored Bawah window, as last
    // applied by applyMenuLayout() (126 / 305 / Semua: content height
    // or the widget-to-margin space, whichever is smaller; 560 px
    // provisional only when the widget is not yet measurable). Used by
    // the Bawah scroll path so the focused row is always fully visible.
    private int modBottomWinH;
    private AppAdapter adapter;
    private GlassLayout bar, panel, hint;
    private Zoom zoom;
    private LinearLayout panelRows, panelStatus;
    private TextView panelTitle;
    private final View[] pills = new View[3];
    private final ImageView[] pillIcons = new ImageView[3];
    private final ValueAnimator[] iconTint = new ValueAnimator[3];
    private final boolean[] iconDark = new boolean[3];
    private View platter, scrim, bigClock;
    private TextView caption;
    // ---- MOD (OwnTV fork): Bawaan background, live wallpaper picker, clock+weather ----
    // OwnTV's app listing, grid, focus and navigation code below is untouched; these fields
    // only add a background layer, a left clock/weather block and a picker overlay.
    // MOD 1.2.13-video-texture: video wallpaper renders through a
    // TextureView + an app-owned MediaPlayer instead of a VideoView
    // (SurfaceView). A SurfaceView lives OUTSIDE the view hierarchy,
    // so on this Amlogic firmware the compositor momentarily showed
    // the layer underneath (the bundled Bawaan image) during grid
    // scroll/animation — the scroll-time flicker the user reported.
    // A TextureView is composited INSIDE the hierarchy like any other
    // view, so it cannot punch through or drop independently.
    private TextureView modVideoTexture;
    // The MediaPlayer of the currently playing live wallpaper, owned
    // by this activity (created in startVideoPlayer, released in
    // releaseVideoPlayer), so the "Suara wallpaper" toggle can
    // mute/unmute it live. Null when no video is playing (image
    // wallpaper, error, or before prepare).
    private MediaPlayer modVideoPlayer;
    // URI the texture should play once its surface is available.
    // Null when the current choice is an image or a failed video.
    private android.net.Uri modPendingVideoUri;
    private boolean modVideoSurfaceReady;
    private FitBackgroundView modFitView;
    private WallpaperPicker modPicker;
    private TextView modClockView, modDateView, modWeatherLine;
    private WeatherIconView modWeatherIcon;
    private LinearLayout modClockBlock;
    // MOD 1.2.10-weather-widget: small FULLY TRANSPARENT system widget
    // (CPU + RAM + storage meter bars, no background box at all, only
    // shadowed text + bars), stacked below the clock/weather block and
    // above the menu grid, refreshed every 5 s. No clock and no weather
    // in it — both already sit above it, and duplicating either was the
    // user's complaint about mockup v3/v4. The widget is never focusable
    // and never consumes keys; it is drawn above the wallpaper/grid but
    // below the picker, panel and scrim.
    private LinearLayout modWidget;
    private View modCpuBar, modRamBar, modDiskBar;
    private TextView modCpuPct, modRamPct, modDiskPct;
    private TextView modCpuSub, modRamSub, modDiskSub;
    private long modPrevCpuTotal = -1, modPrevCpuIdle = -1;
    private int modLastCpuPct = -1; // last displayed CPU %, kept across a bad sample
    // MOD 1.2.12: whether the last displayed CPU % came from the
    // /proc/loadavg estimate (true) or from /proc/stat (false) — the
    // sub-line shows "beban" for the estimate, "N core" for the exact
    // reading, so the two are never confused.
    private boolean modLastCpuLoadBased;
    // MOD 1.2.18-cpu-freq-display: live CPU clock display. /proc/stat and
    // /proc/loadavg are both EACCES from this app's context on this
    // firmware (device-proven), so they can never drive the display.
    // scaling_cur_freq IS readable (device-proven, e.g. 2004000 kHz);
    // scaling_max_freq is EACCES, so the bar ratio uses the max step of
    // the readable scaling_available_frequencies table instead.
    private int modLastCpuFreqKhz = -1; // last displayed CPU clock, kept across a bad read
    private int modFreqMaxKhz = -1; // max step of the available-frequencies table, cached once read
    // MOD 1.2.17-widget-anchor-fix: Bawah anchor top margin LAST
    // APPLIED. 1.2.16 froze the anchor from the first stable non-zero
    // clock-block measurement, but that measurement happens BEFORE
    // weather arrives (weather icon still GONE, block ~30 px shorter);
    // once weather loaded and pushed the date line down, the frozen
    // widget stayed too high and its CPU row overlapped the date line
    // permanently (device screenshot), because nothing ever called
    // applyWidgetAnchor() again. 1.2.15 had the opposite flaw
    // (re-anchor on every menu layout -> shifting). Now the anchor
    // TRACKS the block: it is re-applied only when the block's
    // measured height actually differs from the height it was last
    // anchored from, driven by an OnLayoutChangeListener on the block
    // (see buildModClockWeather) plus the explicit placement-change
    // path. Menu layouts never re-anchor. MIN_VALUE = never anchored.
    // Placement changes reset this (see menuPlaceRow) so returning to
    // Bawah applies fresh TOP params even if the margin is unchanged.
    private int modAnchorAppliedTop = Integer.MIN_VALUE;
    // MOD 1.2.16: loop guard for the Semua window re-apply post (see
    // applyMenuLayout): at most one re-apply may be pending at a time,
    // and the posted run never posts again.
    private boolean modSemuaRepostPending;
    private final Runnable modWidgetTick = new Runnable() {
        @Override
        public void run() {
            updateModWidget();
            handler.postDelayed(this, 5000);
        }
    };
    private String modChoice = "builtin";
    private final Runnable modClockTick = new Runnable() {
        @Override
        public void run() {
            if (modClockView != null) {
                modClockView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            }
            if (modDateView != null) {
                modDateView.setText(new SimpleDateFormat("EEEE, d MMMM yyyy", new Locale("in", "ID")).format(new Date()));
            }
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable modWeatherTick = new Runnable() {
        @Override
        public void run() {
            fetchWeather();
            handler.postDelayed(this, 30 * 60 * 1000);
        }
    };
    private boolean platterShown, bigStyle;
    private int captionSeq;
    // MOD 1.2.3-navfix: set when a CENTER DOWN closes the picker (after a
    // pick). The matching ACTION_UP arrives after the picker is gone, so
    // it must be swallowed here or it clicks the newly focused view.
    private boolean suppressCenterUp = false;

    private int focusPos = -1;          // focused tile, or -1 when focus is in the bar or panel
    private int barIndex = 2;           // last pill used, so Up from the grid lands somewhere familiar
    private boolean inBar, deep, moving, panelOpen, dirty = true, firstFocus = true, resumed;
    private List<String> savedOrder;    // order before a move started, for Back to undo
    private int moveStart;
    private View panelOpener;
    private long lastMoveAt;
    private int scrollTarget;
    private int pendingFocus = -1;     // tile to focus as soon as its view is attached

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            dirty = true;
            if (hasWindowFocus()) reload();
        }
    };

    // ---- lifecycle -------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        sounds = new Sounds(this);
        buildViews();
        applyHomeStyle(false);
        setContentView(root);
        // MOD 1.2.29-donate-dialog: tampilkan sekali saat launcher pertama
        // hidup, sampai user mencentang "Jangan tampilkan lagi". Diposting
        // supaya window sudah siap; tidak pernah dipasang otomatis ke STB
        // untuk build ini (perintah user 19:34, "jangan instal ke stb saya").
        handler.post(this::showDonateDialog);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packages, f, 4 /* Context.RECEIVER_NOT_EXPORTED */);
        else registerReceiver(packages, f);
    }

    // MOD 1.2.29-donate-dialog (user 2026-10-27 19:34): dialog donasi
    // Saweria saat launcher pertama hidup. Muncul setiap kali launcher
    // dibuka sampai user mencentang "Jangan tampilkan lagi" — saat itu
    // prefs "donate_dialog_dismissed" disimpan true dan dialog tidak
    // pernah muncul lagi (kecuali data aplikasi dihapus). Tombol Dukung
    // membuka https://saweria.co/rzmong lewat browser TV jika ada;
    // kalau tidak ada browser yang bisa dibuka, tampil toast, bukan crash.
    // Dialog ini tidak mengubah wallpaper, default launcher, atau
    // pengaturan lain. Tombol Tutup tanpa centang = dialog muncul lagi
    // di pembukaan launcher berikutnya, supaya user masih bisa
    // mencentang di lain waktu.
    private void showDonateDialog() {
        if (prefs.getBoolean("donate_dialog_dismissed", false)) return;
        if (isFinishing()) return;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.px(48), Ui.px(32), Ui.px(48), Ui.px(16));
        TextView msg = new TextView(this);
        msg.setText("🎉 RZ Launcher gratis dan dibuat dengan ❤️.\n\nKalau kamu suka, dukung pengembangannya lewat Saweria ☕ — dukungan bersifat sukarela dan tidak memengaruhi fitur apa pun. Terima kasih! 🙏");
        msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.f(28));
        col.addView(msg);
        final CheckBox dontShow = new CheckBox(this);
        dontShow.setText("✅ Jangan tampilkan lagi");
        dontShow.setFocusable(true);
        dontShow.setFocusableInTouchMode(true);
        dontShow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.f(24));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = Ui.px(24);
        col.addView(dontShow, clp);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("☕✨ Support Developer");
        b.setView(col);
        b.setPositiveButton("☕ Support via Saweria", (d, which) -> {
            if (dontShow.isChecked()) prefs.edit().putBoolean("donate_dialog_dismissed", true).apply();
            try {
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("https://saweria.co/rzmong"));
                if (getPackageManager().resolveActivity(view, 0) != null) startActivity(view);
                else Toast.makeText(this, "Tidak ada browser di TV ini", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Tidak bisa membuka Saweria", Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("Tutup", (d, which) -> {
            if (dontShow.isChecked()) prefs.edit().putBoolean("donate_dialog_dismissed", true).apply();
        });
        b.setOnCancelListener(d -> {
            if (dontShow.isChecked()) prefs.edit().putBoolean("donate_dialog_dismissed", true).apply();
        });
        b.show();
    }

    // MOD 1.0-about-dialog: dialog penjelasan About, hanya muncul
    // saat baris About diklik. Teks agak panjang, berisi profil
    // pengembang + penjelasan fork + atribusi OwnTV (MIT).
    private void showAboutDialog() {
        if (isFinishing()) return;
        ScrollView scroll = new ScrollView(this);
        TextView msg = new TextView(this);
        msg.setText("🚀 RZ Launcher 1.0\n\n👤 Developed by RZMONG\n💻 GitHub: github.com/rz7mong\n🌐 Website: www.rzmong.my.id\n\n📱 About this app:\nRZ Launcher is an Android TV launcher with live wallpaper (image and video) 🖼️🎬, clock and weather ⏰🌤️, and system widgets for CPU, RAM and storage 📊\n\n🤝 RZ Launcher is an unofficial fork, based on OwnTV (MIT) by Le Tri Thanh Dat and Gathin. The original MIT license and credits are kept in the GitHub repository.\n\n🆓 RZ Launcher is free. Support is voluntary and does not affect any feature. Support the developer via Saweria: saweria.co/rzmong ☕🙏");
        msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.f(26));
        int pad = Ui.px(48);
        scroll.addView(msg);
        scroll.setPadding(pad, Ui.px(32), pad, Ui.px(16));
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("About RZ Launcher");
        b.setView(scroll);
        b.setPositiveButton("Tutup", null);
        b.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        boolean reloading = dirty;
        if (dirty) reload();
        else restoreFocus();
        // Back from an app: the plate that filled the screen shrinks back into its tile. If the app
        // list is being reloaded (apps changed meanwhile) tiles may move, so it just fades instead.
        if (zoom.isOpen()) handler.postDelayed(() -> zoom.close(reloading ? null : holderFor(zoom.key())), 80);
        // MOD 1.2.10-weather-widget: (re)start the system widget's 5 s refresh.
        applyWidgetVisibility();
        // MOD 1.2.11-menu-cpu-bar: re-apply the menu geometry on every
        // resume, in case any later layout pass reset the row window.
        // applyMenuLayout never touches focus, only geometry.
        applyMenuLayout();
        // MOD 1.2.28-wall-sound-pause: restart the wallpaper video
        // released in onPause(). Needed when the texture surface
        // survived the pause (the usual case when opening an app),
        // because then onSurfaceTextureAvailable does NOT fire again.
        // If the surface was destroyed, that listener restarts it
        // instead; startVideoPlayer() is idempotent via release first.
        if (modPendingVideoUri != null && modVideoPlayer == null
                && modVideoSurfaceReady && modVideoTexture != null
                && modVideoTexture.isAvailable()) {
            startVideoPlayer(modPendingVideoUri);
        }
    }

    /** Home pressed while already home: put away a panel or an unfinished move. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (panelOpen) closePanel();
        if (moving) endMove(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // MOD 1.2.9-widget-layout: stop the widget refresh while paused.
        handler.removeCallbacks(modWidgetTick);
        // MOD 1.2.28-wall-sound-pause (user 2026-10-27 17:03, "masuk ke
        // YouTube / aplikasi lain, suara wallpaper masih muncul"): the
        // wallpaper MediaPlayer was only released in onDestroy(), so
        // opening any app only paused the launcher activity and the
        // looping MP4 audio kept playing over that app. Release the
        // player here, on every pause. modPendingVideoUri is NOT cleared,
        // so onResume() below can restart the same wallpaper.
        releaseVideoPlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // MOD: stop the clock/weather ticks added by this fork.
        handler.removeCallbacks(modClockTick);
        handler.removeCallbacks(modWeatherTick);
        handler.removeCallbacks(modWidgetTick); // MOD 1.2.9-widget-layout
        unregisterReceiver(packages);
        io.shutdownNow();
        sounds.release();
        releaseVideoPlayer(); // MOD 1.2.13-video-texture: owned MediaPlayer
    }

    // ---- views -----------------------------------------------------------------------------------

    private void buildViews() {
        root = new FrameLayout(this);
        root.setClipChildren(false);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;

        // Wallpaper, a little larger than the screen so it can glide with the focus.
        float over = Ui.f(44);
        int ww = Math.round(sw + 2 * over), wh = Math.round(sh + 2 * over * 0.5625f);
        int wl = Math.round(-over), wt = Math.round(-over * 0.5625f);
        Backdrop.place(wl, wt, ww, wh);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wall.setImageBitmap(Backdrop.sharp(this));
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(ww, wh);
        wlp.leftMargin = wl;
        wlp.topMargin = wt;
        root.addView(wall, wlp);

        wallBlur = new ImageView(this);
        wallBlur.setScaleType(ImageView.ScaleType.FIT_XY);
        wallBlur.setImageBitmap(Backdrop.blur(this));
        wallBlur.setAlpha(0f);
        FrameLayout.LayoutParams blp0 = new FrameLayout.LayoutParams(ww, wh);
        blp0.leftMargin = wl;
        blp0.topMargin = wt;
        root.addView(wallBlur, blp0);

        // MOD: background layer for a chosen image or MP4, behind OwnTV's own
        // wallpaper view. For the bundled Bawaan image this layer stays empty
        // and OwnTV's wall (whose bundled wallpaper.jpg is itself the FIT
        // composition) stays visible; for a chosen file the wall is hidden.
        FrameLayout modBgLayer = new FrameLayout(this);
        modVideoTexture = new TextureView(this);
        modVideoTexture.setVisibility(View.GONE);
        modVideoTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                modVideoSurfaceReady = true;
                // MOD 1.2.14-wallbg-cpudiag: diagnostic only.
                Log.w("OwnTV-Wall", "surface available " + w + "x" + h
                        + " pending=" + (modPendingVideoUri != null));
                // Surface (re)created: start the pending video, if any.
                // This is what resumes playback after pause/resume or
                // activity recreation without ever showing black: the
                // static wall stays hidden only while a video plays.
                if (modPendingVideoUri != null) startVideoPlayer(modPendingVideoUri);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                modVideoSurfaceReady = false;
                // MOD 1.2.14-wallbg-cpudiag: diagnostic only.
                Log.w("OwnTV-Wall", "surface destroyed, player released");
                // Release the player BEFORE the texture goes away, so
                // no frame is ever composited onto a dead surface.
                // Returning true lets TextureView destroy the texture;
                // the next onSurfaceTextureAvailable restarts playback.
                releaseVideoPlayer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        modBgLayer.addView(modVideoTexture, new FrameLayout.LayoutParams(-1, -1));
        modFitView = new FitBackgroundView(this);
        modBgLayer.addView(modFitView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(modBgLayer, 0);
        // MOD 1.2.31-clean-traces: nama penyimpanan wallpaper lama
        // "owntv_wallpaper" dipindah ke "rzlauncher_wallpaper". Migrasi
        // satu kali: kalau penyimpanan baru belum punya pilihan, salin
        // pilihan lama (misalnya file wallpaper yang sedang aktif) ke
        // yang baru, lalu hapus yang lama — jadi wallpaper pengguna
        // tidak kembali ke bawaan hanya karena namanya diganti.
        {
            SharedPreferences oldWp = getSharedPreferences("owntv_wallpaper", MODE_PRIVATE);
            SharedPreferences newWp = getSharedPreferences("rzlauncher_wallpaper", MODE_PRIVATE);
            if (oldWp.contains("choice") && !newWp.contains("choice")) {
                newWp.edit().putString("choice", oldWp.getString("choice", "builtin")).apply();
            }
            if (oldWp.contains("choice")) oldWp.edit().clear().apply();
        }
        modChoice = getSharedPreferences("rzlauncher_wallpaper", MODE_PRIVATE).getString("choice", "builtin");
        applyWallpaperChoice(modChoice, false);

        // Gentle top and bottom shading over the wallpaper, so the bar and the tiles always read.
        View shade = new View(this) {
            private final Paint p = new Paint();

            @Override
            protected void onSizeChanged(int w, int h, int ow, int oh) {
                p.setShader(new LinearGradient(0, 0, 0, h,
                        new int[]{0x400B0B0F, 0x000B0B0F, 0x000B0B0F, 0x800B0B0F},
                        new float[]{0f, 0.22f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            }

            @Override
            protected void onDraw(Canvas c) {
                c.drawRect(0, 0, getWidth(), getHeight(), p);
            }
        };
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        // MOD 1.2.5: NO separate petal/fog/sparkle overlay any more. The
        // bundled Bawaan live wallpaper IS an MP4 (res/raw/builtin_live.mp4)
        // with petals, fog, sparkle and bloom baked into its frames, so its
        // animation can never leak onto another wallpaper. Every other
        // wallpaper (image or MP4) is therefore completely clean by
        // construction, with no overlay layer left to hide or show.

        // App grid: seven columns, first row at y = 612, tiles 224 x 126 with 28 between them.
        int side = Ui.px(96 - 21);
        int cellW = (sw - 2 * side) / COLS;
        modScreenW = sw;
        modScreenH = sh;
        modGridSide = side;
        modGridCellW = cellW;
        grid = new RecyclerView(this);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(side, Ui.px(612), side, sh);
        grid.setHasFixedSize(true);
        androidx.recyclerview.widget.DefaultItemAnimator anim = new androidx.recyclerview.widget.DefaultItemAnimator();
        anim.setSupportsChangeAnimations(false);
        anim.setMoveDuration(220);
        anim.setAddDuration(0);
        anim.setRemoveDuration(120);
        grid.setItemAnimator(anim);
        grid.setItemViewCacheSize(48);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalScrollBarEnabled(false);
        grid.setFocusable(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        grid.setLayoutManager(gridLayout = new GridLayoutManager(this, COLS) {
            // We scroll by row ourselves; stop RecyclerView from chasing the focused child.
            @Override
            public boolean onRequestChildFocus(@NonNull RecyclerView parent, @NonNull RecyclerView.State st,
                                               @NonNull View child, View focused) {
                return true;
            }

            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull android.graphics.Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                return false;
            }
        });
        adapter = new AppAdapter(this, cellW);
        grid.setAdapter(adapter);
        // A row that was off screen gets its focus the moment its tile is attached.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {
                if (pendingFocus >= 0 && grid.getChildAdapterPosition(view) == pendingFocus) {
                    pendingFocus = -1;
                    RecyclerView.ViewHolder h = grid.getChildViewHolder(view);
                    if (h instanceof AppAdapter.Holder) ((AppAdapter.Holder) h).card.requestFocus();
                }
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
            }
        });
        // MOD 1.2.8-wallpaper-sound-rows: the app menu shows 2 rows only
        // (user 03:28: it currently shows 3). The grid itself keeps its
        // exact geometry — full screen height, first row at y = 612 — so
        // layout, scroll and focus code are untouched; only its drawing
        // is clipped by this viewport ancestor: height 612 + 126 + 53 +
        // 126 = 917, so row 0 (612..738) and row 1 (791..917) are fully
        // visible and row 2 (top 970) is clipped out. All apps stay in
        // the adapter and remain reachable by the existing vertical
        // scroll. Honest limits: the focused row-1 card's zoom (+1.6 px
        // past the tile bottom) and its soft shadow tail are clipped at
        // y = 917, and in the already-scrolled "deep" state earlier rows
        // can still slide up into the clock area above y = 612, as they
        // do today — the 2-row window is exact at the resting home
        // position, not a strict invariant while scrolled deep.
        FrameLayout gridViewport = new FrameLayout(this);
        gridViewport.addView(grid, new FrameLayout.LayoutParams(-1, sh));
        root.addView(gridViewport, new FrameLayout.LayoutParams(-1, sh));
        this.gridViewport = gridViewport;
        // MOD 1.2.9-widget-layout: the viewport height, grid padding and
        // span are now driven by the menu settings (see applyMenuLayout);
        // the 1.2.8 fixed 917 px window is just the default (Bawah, 2 baris).
        applyMenuLayout();

        // MOD: transparent clock + live weather on the left, approved v6
        // order: clock, weather image + condition + temperature, date.
        // No panel, no box: white text with a shadow directly over the
        // background. The grid's first row sits at y = 612, so this block
        // (y = 80..~330) plus the small widget below it (~350..~505)
        // cannot overlap a tile. OwnTV's optional "Large clock" stays off
        // by default; turning it on in Settings would overlap this block,
        // so leave it off.
        buildModClockWeather();
        handler.post(modClockTick);
        handler.post(modWeatherTick);
        // MOD 1.2.10-weather-widget: small transparent system widget
        // below the clock/weather block, drawn above the grid but below
        // the picker/panel/scrim (those are added later).
        buildModWidget();

        // MOD: wallpaper picker overlay, hidden until opened from Settings.
        modPicker = new WallpaperPicker(this, new WallpaperPicker.Host() {
            @Override
            public void onWallpaperPicked(String choice) {
                applyWallpaperChoice(choice, true);
            }

            @Override
            public void onPickerClosedAtRoot() {
                // MOD 1.2.3-navfix: Back at root returns to the Settings
                // panel with focus on its Wallpaper row — never to a grid
                // tile (a tile that a stray key-up could click) and never
                // to nowhere. The panel rebuild shows the current choice.
                openSettings();
            }

            @Override
            public void onPickerClosedAfterPick() {
                // MOD 1.2.3-navfix: the CENTER DOWN that applied the file
                // already closed the picker; its ACTION_UP is still in
                // flight and must be swallowed (see dispatchKeyEvent),
                // otherwise it clicks whatever focus lands on next.
                suppressCenterUp = true;
                openSettings();
            }
        }, modChoice);
        modPicker.setVisibility(View.GONE);
        root.addView(modPicker, new FrameLayout.LayoutParams(-1, -1));

        buildBigClock();
        // While a panel is open the rest of Home steps back, so the panel is the only thing that reads.
        scrim = new View(this);
        scrim.setBackgroundColor(0x800B0B0F);
        scrim.setAlpha(0f);
        scrim.setVisibility(View.GONE);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        buildBar();
        buildPanel();
        buildHint();
        zoom = new Zoom(root, sw, sh);
    }

    private void buildBar() {
        bar = new GlassLayout(this);
        bar.setRadius(Ui.f(38));
        bar.setClipChildren(false);
        FrameLayout inner = new FrameLayout(this);
        inner.setClipChildren(false);

        // One droplet glides between the buttons; the buttons themselves never change size, so the bar
        // never jumps. It sits behind the row and is moved with a spring (a few % overshoot, then still).
        platter = new View(this);
        platter.setBackground(new Droplet(Ui.f(32)));
        platter.setAlpha(0f);
        platter.setScaleX(0.82f);
        platter.setScaleY(0.82f);
        FrameLayout.LayoutParams plp0 = new FrameLayout.LayoutParams(Ui.px(64), Ui.px(64));
        plp0.leftMargin = Ui.px(6);
        plp0.topMargin = Ui.px(6);
        inner.addView(platter, plp0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.px(6), Ui.px(6), Ui.px(6), Ui.px(6));
        // MOD 1.2.6-boost: pill 0 is Boost, not Inputs (user decision 02:15:
        // native OwnTV boost, Inputs moved to the Settings panel). Focus
        // order stays 0-2 with LEFT/RIGHT and DOWN to the grid, so the
        // 1.2.3-navfix pill key handling is unchanged.
        int[] icons = {R.drawable.ic_rocket, R.drawable.ic_wifi, R.drawable.ic_settings};
        String[] labels = {getString(R.string.boost), getString(R.string.wifi), getString(R.string.settings)};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            FrameLayout pill = new FrameLayout(this);
            pill.setFocusable(true);
            pill.setClickable(true);
            pill.setDefaultFocusHighlightEnabled(false);
            pill.setContentDescription(labels[i]);
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            ic.setColorFilter(Ui.INK);
            pill.addView(ic, new FrameLayout.LayoutParams(Ui.px(30), Ui.px(30), Gravity.CENTER));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.px(64), Ui.px(64));
            if (i > 0) lp.leftMargin = Ui.px(4);
            row.addView(pill, lp);
            pills[i] = pill;
            pillIcons[i] = ic;
            pill.setOnFocusChangeListener((v, has) -> onPillFocus(idx, has));
            pill.setOnKeyListener((v, code, e) -> onPillKey(idx, code, e));
            pill.setOnClickListener(v -> onPillClick(idx));
        }
        // MOD 1.2.11-menu-cpu-bar: the small top-right bar clock is gone
        // (user 05:08: no second clock next to Settings) — the bar ends
        // cleanly at the Settings pill. The big top-left clock/weather
        // block is untouched.
        inner.addView(row, new FrameLayout.LayoutParams(-2, -2));

        // The focused button's name, centred just under it, travelling with the droplet. Placed with
        // translation (not margins) so it never changes the bar's size.
        caption = Ui.text(this, 22, true, 0xE6F2F2F5);
        caption.setShadowLayer(Ui.f(8), 0, Ui.f(1), 0x66000000);
        caption.setAlpha(0f);
        caption.setTranslationY(Ui.f(88));
        inner.addView(caption, new FrameLayout.LayoutParams(-2, -2));

        bar.addView(inner, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        blp.topMargin = Ui.px(72);
        blp.rightMargin = Ui.px(96);
        root.addView(bar, blp);
    }

    /** Optional large date and time at the top left (Settings > Home screen). */
    private void buildBigClock() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextClock date = new TextClock(this);
        date.setFormat24Hour(getString(R.string.date_format));
        date.setFormat12Hour(getString(R.string.date_format));
        date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.f(30));
        date.setTypeface(Ui.semibold);
        date.setTextColor(0xCCF2F2F5);
        date.setIncludeFontPadding(false);
        block.addView(date, new LinearLayout.LayoutParams(-2, -2));
        TextClock time = new TextClock(this);
        time.setFormat24Hour("H:mm");
        time.setFormat12Hour("H:mm");
        time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.f(156));
        time.setTypeface(Ui.display);
        time.setTextColor(0xF2F2F2F5);
        time.setLetterSpacing(-0.038f);
        time.setFontFeatureSettings("tnum");
        time.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = Ui.px(8);
        tl.leftMargin = -Ui.px(6);
        block.addView(time, tl);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.leftMargin = Ui.px(96);
        lp.topMargin = Ui.px(80);
        block.setAlpha(0f);
        block.setVisibility(View.GONE);
        root.addView(block, lp);
        bigClock = block;
    }

    private void applyHomeStyle(boolean animate) {
        // MOD 1.2.19-no-large-clock (user decision 2026-10-27 07:31): the Large clock style is
        // removed entirely. It stacked OwnTV's own giant clock + date line over the mod's own
        // clock/weather/date block and therefore always overlapped; Compact is the only correct
        // style for this mod. This method is the single funnel for the big-clock view's
        // visibility (onCreate + setDeep), so forcing false here guarantees the view can never
        // render, and any stale stored true carried over from an older install is cleared.
        if (prefs.getBoolean("bigClock", false)) prefs.edit().putBoolean("bigClock", false).apply();
        bigStyle = false;
        boolean show = bigStyle && !deep;
        if (animate) {
            if (show) bigClock.setVisibility(View.VISIBLE);
            bigClock.animate().alpha(show ? 1f : 0f).translationY(show ? 0f : -Ui.f(10)).setDuration(show ? 260 : 180)
                    .setInterpolator(EASE_OUT).withEndAction(() -> {
                        if (!bigStyle || deep) bigClock.setVisibility(View.GONE);
                    }).start();
        } else {
            bigClock.setVisibility(show ? View.VISIBLE : View.GONE);
            bigClock.setAlpha(show ? 1f : 0f);
            bigClock.setTranslationY(0f);
        }
        // MOD 1.2.11: no bar clock / separator any more, nothing to step aside here.
    }

    private void buildPanel() {
        panel = new GlassLayout(this);
        panel.setRadius(Ui.f(40));
        panel.setVisibility(View.GONE);
        panel.setClipChildren(false);
        panel.setFocusable(true);   // holds focus when a panel has no rows (status only)
        panel.setDefaultFocusHighlightEnabled(false);
        panel.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP) {
                if (e.getAction() == KeyEvent.ACTION_UP) closePanel();
                return true;
            }
            return isDpad(code);
        });
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setPadding(Ui.px(12), Ui.px(12), Ui.px(12), Ui.px(12));
        panelTitle = Ui.text(this, 24, true, 0x99F2F2F5);
        panelTitle.setPadding(Ui.px(22), Ui.px(10), Ui.px(22), Ui.px(8));
        col.addView(panelTitle, new LinearLayout.LayoutParams(-1, -2));
        panelStatus = new LinearLayout(this);
        panelStatus.setOrientation(LinearLayout.VERTICAL);
        col.addView(panelStatus, new LinearLayout.LayoutParams(-1, -2));
        panelRows = new LinearLayout(this);
        panelRows.setOrientation(LinearLayout.VERTICAL);
        panelRows.setClipChildren(false);
        col.addView(panelRows, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(col, new FrameLayout.LayoutParams(-1, -2));
        // Under the bar, aligned to its right edge: the bar and its selected button stay in view.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.px(480), -2, Gravity.TOP | Gravity.END);
        lp.topMargin = Ui.px(162);
        lp.rightMargin = Ui.px(96);
        root.addView(panel, lp);
    }

    private void buildHint() {
        hint = new GlassLayout(this);
        hint.setRadius(Ui.f(32));
        hint.setAlpha(0f);
        hint.setVisibility(View.GONE);
        TextView t = Ui.text(this, 28, false, Ui.INK);
        t.setText(R.string.hint_hold);
        t.setPadding(Ui.px(32), Ui.px(18), Ui.px(32), Ui.px(18));
        hint.addView(t, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Ui.px(60);
        root.addView(hint, lp);
    }

    private static GradientDrawable capsule(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(Ui.f(32));
        d.setColor(color);
        return d;
    }

    // ---- MOD: clock + weather, wallpaper choice, picker entry ------------------------------------
    //
    // Truncation fix (the previous custom launcher showed "18:5" and a date
    // without its year on the real TV even though its formats were correct):
    // the clock and date views get an explicit minimum width measured from
    // their own Paint for the longest strings they must show ("19:00" and
    // "Senin, 26 Oktober 2026"), single-line text, no ellipsize, and a
    // parent block at least that wide, so no parent can measure them narrower
    // than their content.
    private void buildModClockWeather() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        blp.leftMargin = Ui.px(96);
        blp.topMargin = Ui.px(80);
        block.setLayoutParams(blp);

        modClockView = new TextView(this);
        modClockView.setTypeface(Ui.semibold);
        modClockView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.f(120));
        modClockView.setTextColor(0xFFF2F2F5);
        modClockView.setShadowLayer(Ui.f(6), 0, Ui.f(3), 0x80000000);
        modClockView.setIncludeFontPadding(false);
        modClockView.setSingleLine(true);
        modClockView.setEllipsize(null);
        int clockMin = (int) Math.ceil(modClockView.getPaint().measureText("19:00")) + Ui.px(16);
        modClockView.setMinWidth(clockMin);
        block.addView(modClockView, new LinearLayout.LayoutParams(-2, -2));

        // MOD 1.2.10-weather-widget: approved v6 order — clock, then the
        // weather IMAGE + condition + temperature on one line directly
        // below the clock, then a small date line below the weather.
        // The icon is dynamic (WeatherIconView maps the live WMO code);
        // while weather is unavailable the line shows a dash and the
        // icon stays GONE — never an invented value or a stale icon.
        LinearLayout weatherRow = new LinearLayout(this);
        weatherRow.setOrientation(LinearLayout.HORIZONTAL);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.topMargin = Ui.px(16);
        weatherRow.setLayoutParams(wlp);
        modWeatherIcon = new WeatherIconView(this);
        modWeatherIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.px(72), Ui.px(72));
        ilp.rightMargin = Ui.px(16);
        weatherRow.addView(modWeatherIcon, ilp);
        modWeatherLine = new TextView(this);
        modWeatherLine.setText("–");
        modWeatherLine.setTypeface(Ui.semibold);
        modWeatherLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.f(36));
        modWeatherLine.setTextColor(0xFFF2F2F5);
        modWeatherLine.setShadowLayer(Ui.f(4), 0, Ui.f(2), 0x80000000);
        modWeatherLine.setIncludeFontPadding(false);
        modWeatherLine.setSingleLine(true);
        modWeatherLine.setEllipsize(null);
        int weatherMin = (int) Math.ceil(modWeatherLine.getPaint().measureText("Berawan sebagian · 24°")) + Ui.px(16);
        modWeatherLine.setMinWidth(weatherMin);
        weatherRow.addView(modWeatherLine, new LinearLayout.LayoutParams(-2, -2));
        block.addView(weatherRow);

        modDateView = new TextView(this);
        modDateView.setTypeface(Ui.medium);
        modDateView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.f(24));
        modDateView.setTextColor(0xFFF2F2F5);
        modDateView.setShadowLayer(Ui.f(4), 0, Ui.f(2), 0x80000000);
        modDateView.setIncludeFontPadding(false);
        modDateView.setSingleLine(true);
        modDateView.setEllipsize(null);
        int dateMin = (int) Math.ceil(modDateView.getPaint().measureText("Senin, 26 Oktober 2026")) + Ui.px(16);
        modDateView.setMinWidth(dateMin);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = Ui.px(12);
        block.addView(modDateView, dlp);

        // The block itself is at least as wide as its widest text, so the
        // clock's last digit and the date's year cannot be clipped by it.
        block.setMinimumWidth(Math.max(clockMin, dateMin));
        modClockBlock = block;
        // MOD 1.2.17-widget-anchor-fix: the widget anchor TRACKS this
        // block. The block grows when weather arrives (icon GONE ->
        // VISIBLE, ~30 px) and could shrink again if weather is lost,
        // so any height change re-runs the anchor computation; the
        // guard inside applyWidgetAnchor() makes every other layout
        // pass (position-only changes, text redraws) a no-op. Menu
        // layouts never touch the anchor — only this listener and the
        // explicit placement-change path do.
        block.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) applyWidgetAnchor();
        });
        root.addView(block, blp);
    }

    // ---- MOD 1.2.9-widget-layout + 1.2.10-weather-widget: menu layout
    // settings + system widget -------------------------------------------------
    //
    // The user demanded nothing rigid: both the menu's row window and its
    // placement are adjustable from Settings, applied immediately, no
    // restart, no wallpaper reselect. Prefs live in "home" (same file as
    // navSound/wallpaperSound): "menuRows" int (1, 2, or 0 = Semua,
    // default 2 = the 1.2.8 behaviour), "menuPlacement" string ("bottom",
    // "right", "left", "top", default "bottom"), "widgetSystem"
    // boolean (default true; 1.2.10 renamed the 1.2.9 "widgetBottomLeft"
    // row and falls back to its value when the new key is absent).
    private String menuPlacement() {
        return prefs.getString("menuPlacement", "bottom");
    }

    private int menuRows() {
        return prefs.getInt("menuRows", 2);
    }

    private boolean isSidebarPlacement() {
        String p = menuPlacement();
        return p.equals("left") || p.equals("right");
    }

    /** Current grid span: 7 columns for horizontal layouts, 1 for sidebars. */
    private int menuSpan() {
        return isSidebarPlacement() ? 1 : COLS;
    }

    /** Design-y of the first tile row per placement. Bawah keeps 612 (the
     * 1.2.8 position); sidebars start below the top bar (170); Atas sits
     * below the left clock/weather block (~y 80..432) at 460, so the strip
     * never covers the clock text — it is "moved up" from 612 and still
     * below the top bar, but not immediately under it. */
    private int menuTopPad() {
        String p = menuPlacement();
        if (p.equals("top")) return 460;
        if (isSidebarPlacement()) return 170;
        return 612;
    }

    private String menuRowsLabel() {
        int r = menuRows();
        return r == 1 ? "1 baris" : r == 0 ? "Semua" : "2 baris";
    }

    private String menuPlacementLabel() {
        String p = menuPlacement();
        if (p.equals("right")) return "Kanan";
        if (p.equals("left")) return "Kiri";
        if (p.equals("top")) return "Atas";
        return "Bawah";
    }

    /** Applies the menu settings to the grid: padding (position), span
     * (columns vs single-column sidebar) and viewport height/clipping
     * (row window). Called once at build and on every Settings change.
     * Focus is never touched here — these changes only ever happen from
     * a Settings panel row, so focus correctly stays in the panel.
     * MOD 1.2.12-menu-bottom-cpu: Bawah is BOTTOM-ANCHORED (explicit
     * user requirement): rows grow UP from a fixed bottom edge (60 px
     * margin), never down from y=612. 1 baris = one row at the very
     * bottom; 2 baris = two rows stacked upward from that same fixed
     * bottom edge, row 0 above row 1; Semua = a bottom-anchored,
     * scrollable window whose top edge is the widget's measured
     * bottom edge (MOD 1.2.16: never overlapping the widget) and
     * whose height is the content height or the widget-to-margin
     * space, whichever is smaller — short content hugs the bottom
     * edge, taller content scrolls, and scrollToRowBottom brings the
     * last row fully into view (user decision 06:36). Grid top
     * padding is 0 inside that container (rows lay out from the
     * container top). Kiri/Kanan/Atas keep the exact 1.2.11 geometry. */
    private void applyMenuLayout() {
        if (grid == null || gridViewport == null || gridLayout == null) return;
        int span = menuSpan();
        if (gridLayout.getSpanCount() != span) gridLayout.setSpanCount(span);
        String p = menuPlacement();
        int side = modGridSide, cellW = modGridCellW;
        int rows = menuRows();
        if (p.equals("bottom")) {
            // Bottom-anchored block: height = block height, gravity
            // BOTTOM, bottom margin 60 px (the UI's standard bottom
            // margin, same as the widget's). Rows hug the bottom edge.
            grid.setPadding(side, 0, side, 0);
            int rowH = Ui.px(AppAdapter.TILE_H + AppAdapter.ROW_GAP);
            int tileH = Ui.px(AppAdapter.TILE_H);
            int containerH;
            int totalRows = adapter == null ? 0
                    : (adapter.getItemCount() + menuSpan() - 1) / menuSpan();
            int contentH = totalRows > 0 ? (totalRows - 1) * rowH + tileH : 0;
            if (rows == 1) {
                containerH = tileH;
                // 1/2 baris keep the device-proven 1.2.12 behaviour
                // exactly (unclipped grid, zoom tail intact).
                grid.setClipChildren(false);
                gridViewport.setClipChildren(false);
            } else if (rows == 2) {
                containerH = tileH + rowH; // 126 + 179 = 305
                grid.setClipChildren(false);
                gridViewport.setClipChildren(false);
            } else {
                // MOD 1.2.16-anchor-semua-freqdiag, Semua only (user
                // decision 06:36: scrollable is fine, but the last row
                // must show FULLY when selected). The old code capped
                // the window at 560 px (y 460..1020) regardless of
                // content: with 4 rows (663 px) the grid started at
                // y 460, OVERLAPPED the widget's DISK row, and the
                // 4th row was cut at the screen edge in the resting
                // view (grid clip is off, so overflow drew past the
                // window to the screen edge). Now the window top is
                // the widget's measured bottom edge, so the grid can
                // never cover the widget, and the window is clipped
                // so overflow rows stay hidden until scrolled into
                // view instead of drawing cut at the screen edge.
                // Short content still hugs the bottom edge; taller
                // content scrolls, and scrollToRowBottom (via
                // modBottomWinH below) brings the last row fully into
                // view with its bottom at the 60 px margin.
                int winBottom = modScreenH - Ui.px(60);
                int wBottom = semuaWidgetBottom();
                if (totalRows == 0) {
                    containerH = 560; // empty adapter at build; reload() re-applies
                } else if (wBottom < 0) {
                    // Widget not yet measurable (startup, or parked
                    // at a pre-placement-change position): provisional
                    // old 560 px window, corrected by a single posted
                    // re-apply after the pending layout pass, when
                    // the widget is at its final position.
                    containerH = Math.min(contentH, 560);
                    if (!modSemuaRepostPending) {
                        modSemuaRepostPending = true;
                        gridViewport.post(() -> {
                            modSemuaRepostPending = false;
                            applyMenuLayout();
                        });
                    }
                } else {
                    containerH = Math.min(contentH, winBottom - wBottom);
                }
                grid.setClipChildren(true);
                gridViewport.setClipChildren(true);
            }
            modBottomWinH = containerH;
            FrameLayout.LayoutParams vp =
                    new FrameLayout.LayoutParams(-1, containerH, Gravity.BOTTOM);
            vp.bottomMargin = Ui.px(60);
            gridViewport.setLayoutParams(vp);
            grid.setLayoutParams(new FrameLayout.LayoutParams(-1, containerH));
            // MOD 1.2.17: applyMenuLayout never re-anchors the widget
            // — the height-tracked anchor (see applyWidgetAnchor)
            // makes that both unnecessary and a jitter source. Only
            // the clock block's layout listener and the
            // placement-change action re-anchor.
            scrollTarget = Integer.MIN_VALUE; // geometry changed: re-evaluate scroll on next focus
            return;
        }
        int top = menuTopPad();
        // Sidebar content is exactly one tile wide; the opposite padding
        // pushes the column to the chosen edge.
        if (p.equals("left")) grid.setPadding(side, Ui.px(top), modScreenW - side - cellW, modScreenH);
        else if (p.equals("right")) grid.setPadding(modScreenW - side - cellW, Ui.px(top), side, modScreenH);
        else grid.setPadding(side, Ui.px(top), side, modScreenH);
        FrameLayout.LayoutParams vp;
        if (rows == 0) {
            // Semua: no viewport clipping, exactly the pre-1.2.8 grid.
            gridViewport.setClipChildren(false);
            vp = new FrameLayout.LayoutParams(-1, modScreenH);
        } else {
            gridViewport.setClipChildren(true);
            gridViewport.setClipToPadding(true);
            int h = top + Ui.px(AppAdapter.TILE_H)
                    + (rows - 1) * Ui.px(AppAdapter.TILE_H + AppAdapter.ROW_GAP);
            vp = new FrameLayout.LayoutParams(-1, h);
        }
        gridViewport.setLayoutParams(vp);
        // MOD 1.2.11-menu-cpu-bar: the 1.2.8-1.2.10 viewport clip alone had
        // NO visible effect on the G1 (device-verified 1.2.10 symptom:
        // menuRows=1 persisted, yet the grid always showed 3 rows with
        // row 3 cut at the screen edge). So the GRID itself now takes
        // the window height too: rows beyond the window are not laid
        // out visibly at all, not merely clipped by an ancestor. The
        // viewport clip above stays as belt-and-braces. Semua keeps
        // the full screen height exactly as before.
        grid.setLayoutParams(new FrameLayout.LayoutParams(-1, vp.height));
        // MOD 1.2.16: restore the unclipped grid when leaving Bawah +
        // Semua (its clip is scoped to that mode only); and, as in
        // the Bawah branch, no unconditional widget re-anchor here.
        grid.setClipChildren(false);
        scrollTarget = Integer.MIN_VALUE; // geometry changed: re-evaluate scroll on next focus
    }

    /** MOD 1.2.16-anchor-semua-freqdiag: measured bottom edge of the
     * system widget in device px, or -1 when it cannot be known right
     * now (no widget yet, widget hidden, or not laid out yet) or when
     * it would leave no usable window (widget still parked at a
     * pre-placement-change position, e.g. bottom-right from a sidebar
     * placement). Callers use the old 560 px window provisionally and
     * post a re-apply for the laid-out case. */
    private int semuaWidgetBottom() {
        if (modWidget == null || modWidget.getVisibility() != View.VISIBLE) return -1;
        if (modWidget.getHeight() <= 0) return -1;
        int bottom = modWidget.getTop() + modWidget.getHeight();
        int winBottom = modScreenH - Ui.px(60);
        if (bottom < 0 || bottom >= winBottom) return -1;
        return bottom;
    }

    /** MOD 1.2.10-weather-widget: fully transparent widget — NO
     * background drawable at all, only shadowed text + meter bars, so
     * the wallpaper shows through completely. Three small rows (CPU,
     * RAM, DISK), stacked vertically, each a label + bar + percent with
     * a detail line under the bar. Never focusable, never a key target:
     * focusability is explicitly switched off on every child. */
    private void buildModWidget() {
        modWidget = new LinearLayout(this);
        modWidget.setOrientation(LinearLayout.VERTICAL);
        modWidget.setFocusable(false);
        modWidget.setFocusableInTouchMode(false);
        modCpuBar = addWidgetRow("CPU", 0xFF00E5FF);
        modRamBar = addWidgetRow("RAM", 0xFF00E5FF);
        modDiskBar = addWidgetRow("DISK", 0xFFFF78DC);
        root.addView(modWidget, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        sampleCpu();
        // MOD 1.2.16-anchor-semua-freqdiag: one-shot frequency probe,
        // logging only (see probeCpuFreq). MOD 1.2.18-cpu-freq-display:
        // the row's display now reads scaling_cur_freq on every tick;
        // this startup probe log stays as a diagnostic.
        probeCpuFreq();
        // MOD 1.2.11-menu-cpu-bar: seed the first CPU value from a real
        // 500 ms interval — a second sample half a second after the
        // first, then a widget refresh, so the first displayed CPU %
        // is a measured delta, not a first-sample dash.
        handler.postDelayed(() -> {
            if (modCpuPct != null) updateModWidget();
        }, 500);
        applyWidgetAnchor();
        applyWidgetVisibility();
    }

    /** One widget row: [label | bar track + fill | percent] over a
     * detail line aligned with the bar. Returns the fill view; the
     * percent and detail TextViews are stored in the matching fields
     * by row label. All children are explicitly non-focusable. */
    private View addWidgetRow(String label, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFocusable(false);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setFocusable(false);
        TextView lab = Ui.text(this, 20, true, color);
        lab.setText(label);
        lab.setFocusable(false);
        LinearLayout.LayoutParams labLp = new LinearLayout.LayoutParams(Ui.px(72), -2);
        top.addView(lab, labLp);
        FrameLayout track = new FrameLayout(this);
        track.setFocusable(false);
        track.setBackgroundColor(0x40F2F2F5);
        View fill = new View(this);
        fill.setFocusable(false);
        fill.setBackgroundColor(color);
        track.addView(fill, new FrameLayout.LayoutParams(0, -1));
        top.addView(track, new LinearLayout.LayoutParams(Ui.px(135), Ui.px(10)));
        TextView pct = Ui.text(this, 20, true, Ui.INK);
        pct.setText("–");
        pct.setFocusable(false);
        pct.setShadowLayer(Ui.f(3), 0, Ui.f(2), 0x80000000);
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(-2, -2);
        pctLp.leftMargin = Ui.px(12);
        top.addView(pct, pctLp);
        TextView sub = Ui.text(this, 17, false, 0xD8F2F2F5);
        sub.setText("");
        sub.setFocusable(false);
        sub.setShadowLayer(Ui.f(3), 0, Ui.f(2), 0x80000000);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.leftMargin = Ui.px(72);
        col.addView(top, new LinearLayout.LayoutParams(-2, -2));
        col.addView(sub, subLp);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(-2, -2);
        if (modWidget.getChildCount() > 0) colLp.topMargin = Ui.px(10);
        modWidget.addView(col, colLp);
        if (label.equals("CPU")) {
            modCpuPct = pct;
            modCpuSub = sub;
        } else if (label.equals("RAM")) {
            modRamPct = pct;
            modRamSub = sub;
        } else {
            modDiskPct = pct;
            modDiskSub = sub;
        }
        return fill;
    }

    /** Reads /proc/stat CPU counters into [total, idle].
     * MOD 1.2.11-menu-cpu-bar: the 1.2.10 reader took only the first
     * line and demanded the aggregate "cpu " line, so any unparseable
     * or missing aggregate line meant a permanent dash even though
     * the file itself is readable. Now the whole file is read: the
     * aggregate line is preferred, but if it is missing or too short
     * the per-core "cpuN" lines from the same file are summed instead.
     * A line only counts when it carries at least user+nice+system+
     * idle (4 counters); idle is fields 4+5 (idle+iowait) when both
     * exist, field 4 alone otherwise. Returns null when nothing
     * usable can be read (CPU row then shows a dash, never an
     * invented number). */
    private long[] readCpuStat() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/stat"));
            long total = 0, idle = 0;
            long coreTotal = 0, coreIdle = 0;
            boolean haveAgg = false, haveCore = false;
            int lines = 0, skipped = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("cpu")) continue;
                lines++;
                String[] p = line.trim().split("\\s+");
                if (p.length < 5) { skipped++; continue; } // name + at least user, nice, system, idle
                long t = 0, id = 0;
                for (int i = 1; i < p.length; i++) {
                    long v = Long.parseLong(p[i]);
                    t += v;
                    if (i == 4) id += v; // idle
                    else if (i == 5) id += v; // iowait
                }
                if (p[0].equals("cpu")) {
                    total = t;
                    idle = id;
                    haveAgg = true;
                    break; // aggregate line is authoritative; later lines are per-core
                }
                coreTotal += t;
                coreIdle += id;
                haveCore = true;
            }
            r.close();
            // MOD 1.2.14-wallbg-cpudiag: diagnostic only — counts and
            // source, never raw file contents.
            Log.w("OwnTV-CPU", "stat opened OK lines=" + lines + " skipped=" + skipped
                    + " agg=" + haveAgg + " cores=" + haveCore);
            if (haveAgg) return new long[]{total, idle};
            if (haveCore) return new long[]{coreTotal, coreIdle};
            return null;
        } catch (Exception e) {
            // MOD 1.2.14-wallbg-cpudiag: diagnostic only — exception
            // class + message proves whether the app context can open
            // the file at all; no behavior change.
            Log.w("OwnTV-CPU", "stat FAILED " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return null;
        }
    }

    private void sampleCpu() {
        long[] s = readCpuStat();
        if (s != null) {
            modPrevCpuTotal = s[0];
            modPrevCpuIdle = s[1];
        }
    }

    /** CPU load percent from the /proc/stat delta since the last
     * sample, or -1 when it cannot be computed (first sample or
     * unreadable stat file) — the row then shows a dash, never a
     * fabricated number. */
    private int cpuLoadPct() {
        long[] s = readCpuStat();
        if (s == null || modPrevCpuTotal < 0) {
            if (s != null) {
                modPrevCpuTotal = s[0];
                modPrevCpuIdle = s[1];
            }
            return -1;
        }
        long totalDelta = s[0] - modPrevCpuTotal;
        long idleDelta = s[1] - modPrevCpuIdle;
        modPrevCpuTotal = s[0];
        modPrevCpuIdle = s[1];
        if (totalDelta <= 0) return -1;
        return (int) Math.round((totalDelta - idleDelta) * 100.0 / totalDelta);
    }

    /** MOD 1.2.12-menu-bottom-cpu: FALLBACK CPU source, used ONLY when
     * /proc/stat yields no value at all (the device-verified 1.2.11
     * symptom: permanent dash despite /proc/stat being shell-readable
     * and no SELinux denial logged — i.e. the read is silently
     * blocked from this app's context, likely a dontaudit denial).
     * Reads the 1-minute load average from /proc/loadavg, divides by
     * the core count and scales to a 0..100 percent, or -1 when
     * unreadable. This is an estimate, not a utilization measurement:
     * callers must label it "beban", never "N core". */
    private int loadAvgPct() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/loadavg"));
            String line = r.readLine();
            r.close();
            if (line == null) {
                Log.w("OwnTV-CPU", "loadavg opened OK but empty");
                return -1;
            }
            double load = Double.parseDouble(line.trim().split("\\s+")[0]);
            int cores = Runtime.getRuntime().availableProcessors();
            if (cores <= 0) return -1;
            int pct = Math.max(0, Math.min(100, (int) Math.round(load * 100.0 / cores)));
            // MOD 1.2.14-wallbg-cpudiag: diagnostic only — parsed
            // 1-minute value and core count, never raw file contents.
            Log.w("OwnTV-CPU", "loadavg opened OK load1=" + load + " cores=" + cores + " pct=" + pct);
            return pct;
        } catch (Exception e) {
            // MOD 1.2.14-wallbg-cpudiag: diagnostic only.
            Log.w("OwnTV-CPU", "loadavg FAILED " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return -1;
        }
    }

    /** MOD 1.2.16-anchor-semua-freqdiag: one-shot diagnostic probe of
     * the CPU frequency sysfs files, run once per activity build from
     * buildModWidget() (effectively once per process start on a TV,
     * where no configuration change recreates the activity). LOGGING
     * ONLY: nothing here is displayed anywhere, and the CPU row's dash
     * rules, "beban"/"N core" labels and last-value retention are
     * untouched. /proc/stat and /proc/loadavg are both EACCES-denied
     * from this app's context on this firmware (device-proven), so
     * this probe answers whether the frequency files are any more
     * readable before the user decides the row's final display (show
     * frequency / hide row / keep dash). Only parsed numbers (or a
     * capped prefix of the available-frequency list) are logged. */
    private void probeCpuFreq() {
        String base = "/sys/devices/system/cpu/cpu0/cpufreq/";
        probeCpuFreqFile(base + "scaling_cur_freq", "cur");
        probeCpuFreqFile(base + "scaling_max_freq", "max");
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(base + "scaling_available_frequencies"));
            String line = r.readLine();
            r.close();
            if (line == null) {
                Log.w("OwnTV-CPU", "freq probe avail opened OK but empty");
            } else {
                String v = line.trim();
                if (v.length() > 200) v = v.substring(0, 200);
                Log.w("OwnTV-CPU", "freq probe avail opened OK values=" + v);
            }
        } catch (Exception e) {
            Log.w("OwnTV-CPU", "freq probe avail FAILED " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private void probeCpuFreqFile(String path, String name) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path));
            String line = r.readLine();
            r.close();
            if (line == null) {
                Log.w("OwnTV-CPU", "freq probe " + name + " opened OK but empty");
            } else {
                Log.w("OwnTV-CPU", "freq probe " + name + " opened OK value=" + line.trim());
            }
        } catch (Exception e) {
            Log.w("OwnTV-CPU", "freq probe " + name + " FAILED " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    /** MOD 1.2.18-cpu-freq-display: current CPU clock in kHz, or -1 if unreadable. */
    private int readCpuFreqKhz() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                    "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"));
            String line = r.readLine();
            r.close();
            if (line == null) return -1;
            return (int) Long.parseLong(line.trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** MOD 1.2.18-cpu-freq-display: max step of the available-frequencies
     * table in kHz, cached once read; re-read only while still unknown,
     * or -1 if the table cannot be read. */
    private int freqMaxKhz() {
        if (modFreqMaxKhz > 0) return modFreqMaxKhz;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                    "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies"));
            String line = r.readLine();
            r.close();
            if (line != null) {
                int max = -1;
                for (String tok : line.trim().split("\\s+")) {
                    try {
                        int v = Integer.parseInt(tok);
                        if (v > max) max = v;
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (max > 0) modFreqMaxKhz = max;
            }
        } catch (Exception ignored) {
        }
        return modFreqMaxKhz;
    }

    /** MOD 1.2.10-weather-widget anchors (approved v6 is the Bawah one):
     * Bawah/Kanan — directly below the clock/weather block, left-aligned,
     * above the menu grid (the block's measured bottom + a gap; a
     * design estimate is used until the first layout pass, then the
     * real bottom, re-applied via post). Kiri — bottom-right, so the
     * widget never covers the left sidebar. Atas — bottom-left below
     * the raised grid, so it never covers the grid strip or the clock
     * block. In every mode the widget stays non-focusable. */
    private void applyWidgetAnchor() {
        if (modWidget == null) return;
        String p = menuPlacement();
        FrameLayout.LayoutParams lp;
        // MOD 1.2.17-widget-anchor-fix: true only when this call
        // actually moves the widget (Bawah path, new margin applied).
        // In that case the Semua grid window top — derived from the
        // widget's measured bottom edge — must be re-applied after
        // layout, once, guarded so it cannot loop (see below).
        boolean reapplySemua = false;
        if (p.equals("left")) {
            lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            lp.rightMargin = Ui.px(96);
            lp.bottomMargin = Ui.px(60);
        } else if (p.equals("top")) {
            lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            lp.leftMargin = Ui.px(96);
            lp.bottomMargin = Ui.px(60);
        } else {
            lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            lp.leftMargin = Ui.px(96);
            if (modClockBlock != null && modClockBlock.getHeight() > 0) {
                // MOD 1.2.17-widget-anchor-fix: TRACK the block, do
                // not freeze it. An unchanged measurement is a no-op
                // (no setLayoutParams, so no relayout jitter at all),
                // but a genuine block resize — e.g. the weather icon
                // appearing and pushing the date line down ~30 px —
                // re-anchors the widget so the CPU row clears the
                // date line again. The +20 px gap is unchanged.
                int measured = modClockBlock.getTop() + modClockBlock.getHeight() + Ui.px(20);
                if (measured == modAnchorAppliedTop) return;
                modAnchorAppliedTop = measured;
                lp.topMargin = measured;
                reapplySemua = true;
            } else {
                // Not laid out yet: provisional anchor only until the
                // first stable measurement; never overwrite an applied
                // anchor with a transient (zero-height) reading.
                if (modAnchorAppliedTop != Integer.MIN_VALUE) return;
                lp.topMargin = Ui.px(350);
                if (modClockBlock != null) modClockBlock.post(this::applyWidgetAnchor);
            }
        }
        modWidget.setLayoutParams(lp);
        // MOD 1.2.17-widget-anchor-fix: the Semua window top is the
        // widget's measured bottom edge, so a widget move must
        // re-apply it once the widget is at its new position (the
        // post runs after layout). Loop-proof by construction plus
        // the shared flag: applyMenuLayout never calls
        // applyWidgetAnchor (1.2.16), a grid layout change never
        // fires the clock block's layout listener, and at most one
        // re-apply may be pending. Gated on a visible widget: with
        // the widget hidden the Semua window uses its provisional
        // size and there is nothing to track.
        if (reapplySemua && menuRows() == 0 && gridViewport != null
                && modWidget.getVisibility() == View.VISIBLE
                && !modSemuaRepostPending) {
            modSemuaRepostPending = true;
            gridViewport.post(() -> {
                modSemuaRepostPending = false;
                applyMenuLayout();
            });
        }
    }

    /** "Widget sistem" (1.2.10; falls back to the 1.2.9
     * "widgetBottomLeft" value on installs that already toggled it). */
    private boolean widgetSystemOn() {
        if (prefs.contains("widgetSystem")) return prefs.getBoolean("widgetSystem", true);
        return prefs.getBoolean("widgetBottomLeft", true);
    }

    /** Shows/hides the widget and starts/stops its 5 s refresh. Only
     * refreshes while resumed (see onResume/onPause). */
    private void applyWidgetVisibility() {
        handler.removeCallbacks(modWidgetTick);
        if (modWidget == null) return;
        boolean on = widgetSystemOn();
        modWidget.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on && resumed) {
            updateModWidget();
            handler.postDelayed(modWidgetTick, 5000);
        }
    }

    /** MOD 1.2.10-weather-widget rows (no clock, no weather — both sit
     * above the widget already): CPU clock in GHz + "frekuensi", RAM used/
     * total GB + %, storage used % + free GB. A dash whenever a value
     * cannot be read, never an invented number. Bar widths follow the
     * same percents (CPU bar: current clock over the table max).
     * MOD 1.2.18-cpu-freq-display: the CPU row shows the live clock
     * rate, honestly labelled "frekuensi" — a clock rate, never a
     * utilisation percentage. /proc/stat and /proc/loadavg are EACCES
     * on this firmware and are NO LONGER consulted for the display. */
    private void updateModWidget() {
        if (modCpuPct == null || modRamPct == null || modDiskPct == null) return;
        Locale id = new Locale("in", "ID");
        // MOD 1.2.18-cpu-freq-display: read scaling_cur_freq on every
        // tick; retain the last displayed frequency across a failed
        // read; only a row that never read any frequency shows the dash.
        int freqKhz = readCpuFreqKhz();
        String freqSource;
        if (freqKhz >= 0) {
            freqSource = "freq";
            modLastCpuFreqKhz = freqKhz;
        } else if (modLastCpuFreqKhz >= 0) {
            freqKhz = modLastCpuFreqKhz;
            freqSource = "retained";
        } else {
            freqSource = "none";
        }
        int freqMax = freqMaxKhz();
        Log.w("OwnTV-CPU", "display " + freqSource + " khz=" + freqKhz + " max=" + freqMax);
        if (freqKhz < 0) {
            modCpuPct.setText("–");
            setBarFill(modCpuBar, 0);
            modCpuSub.setText("");
        } else {
            modCpuPct.setText(String.format(id, "%.2f GHz", freqKhz / 1000000.0));
            int fill = freqMax > 0 ? (int) Math.round(freqKhz * 100.0 / freqMax) : 0;
            setBarFill(modCpuBar, fill);
            modCpuSub.setText("frekuensi");
        }
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        double total = mi.totalMem / 1073741824.0;
        double used = (mi.totalMem - mi.availMem) / 1073741824.0;
        int pct = mi.totalMem > 0 ? (int) Math.round((mi.totalMem - mi.availMem) * 100.0 / mi.totalMem) : 0;
        modRamPct.setText(pct + "%");
        setBarFill(modRamBar, pct);
        modRamSub.setText(String.format(id, "%.1f / %.1f GB", used, total));
        StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
        double free = sf.getAvailableBytes() / 1073741824.0;
        double tot = sf.getTotalBytes() / 1073741824.0;
        int usedPct = tot > 0 ? (int) Math.round((tot - free) * 100.0 / tot) : 0;
        modDiskPct.setText(usedPct + "%");
        setBarFill(modDiskBar, usedPct);
        modDiskSub.setText(String.format(id, "Bebas %.1f GB", free));
    }

    /** Sets a widget bar fill's width to pct% of the 135 design-px track. */
    private void setBarFill(View fill, int pct) {
        if (fill == null || fill.getParent() == null) return;
        ViewGroup.LayoutParams lp = fill.getLayoutParams();
        lp.width = Ui.px(135 * Math.max(0, Math.min(100, pct)) / 100);
        fill.setLayoutParams(lp);
    }

    /** Live Open-Meteo current weather. No API key. Never invents a value. */
    private void fetchWeather() {
        io.execute(() -> {
            String body = null;
            for (int attempt = 0; attempt < 3 && body == null; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
                HttpURLConnection c = null;
                try {
                    URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=-0.53&longitude=101.45&current=temperature_2m,weather_code");
                    c = (HttpURLConnection) url.openConnection();
                    c.setRequestProperty("User-Agent", "RZ-Launcher/1.0");
                    c.setRequestProperty("Accept", "application/json");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    if (c.getResponseCode() == 200) {
                        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        body = sb.toString();
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
            if (body == null) return; // keeps the previous text; "--" / "Cuaca tidak tersedia" on first run
            try {
                org.json.JSONObject cur = new org.json.JSONObject(body).getJSONObject("current");
                final double temp = cur.getDouble("temperature_2m");
                final int code = cur.getInt("weather_code");
                handler.post(() -> {
                    if (modWeatherLine != null) {
                        modWeatherLine.setText(conditionFor(code) + " · " + Math.round(temp) + "°");
                    }
                    if (modWeatherIcon != null) {
                        modWeatherIcon.setVisibility(View.VISIBLE);
                        modWeatherIcon.setCode(code);
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    private static String conditionFor(int code) {
        if (code == 0) return "Cerah";
        if (code == 1 || code == 2) return "Berawan sebagian";
        if (code == 3) return "Mendung";
        if (code == 45 || code == 48) return "Berkabut";
        if (code >= 51 && code <= 67) return "Hujan";
        if (code >= 71 && code <= 77) return "Salju";
        if (code >= 80 && code <= 82) return "Hujan";
        if (code >= 95) return "Badai petir";
        return "Mendung";
    }

    /**
     * MOD 1.2-builtin-chainsaw: bundled builtin live wallpaper is an MP4
     * (res/raw/builtin_live.mp4, 15 detik cuplikan video Chainsaw Man Girls 3 dari Moewalls
     * 1080p60, dengan audio). It is copied once to internal
     * storage and played through the same TextureView path as any other MP4,
     * looping. "builtin" = bundled Chainsaw Man MP4; otherwise an absolute
     * file path. Gambar bawaan lama tidak lagi dipakai di aplikasi ini.
     */
    private File builtinVideoFile() {
        File out = new File(getFilesDir(), "builtin_live.mp4");
        try {
            android.content.res.Resources res = getResources();
            int id = res.getIdentifier("builtin_live", "raw", getPackageName());
            if (id != 0 && (!out.exists() || out.length() == 0)) {
                java.io.InputStream in = res.openRawResource(id);
                java.io.OutputStream o = new java.io.FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                o.close();
                in.close();
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // MOD 1.2.8-wallpaper-sound-rows: live-wallpaper audio toggle,
    // persisted in the "home" prefs as "wallpaperSound" (default true, so
    // existing installs keep wallpaper audio). Applied to the playing
    // MediaPlayer only — navigation tick/knock (Sounds) and static image
    // wallpapers are untouched, and the bundled Bawaan MP4 has no audio
    // track, so it is unaffected in practice.
    private boolean wallpaperSoundOn() {
        return prefs.getBoolean("wallpaperSound", true);
    }

    private void applyWallpaperVolume() {
        if (modVideoPlayer != null) {
            try {
                if (wallpaperSoundOn()) modVideoPlayer.setVolume(1f, 1f);
                else modVideoPlayer.setVolume(0f, 0f);
            } catch (Exception ignored) {
            }
        }
    }

    // MOD 1.2.13-video-texture: release the owned player, if any.
    // Safe to call when nothing is playing.
    private void releaseVideoPlayer() {
        if (modVideoPlayer != null) {
            try {
                modVideoPlayer.release();
            } catch (Exception ignored) {
            }
            modVideoPlayer = null;
        }
    }

    // Shared video-failure path: hide the texture, show the static
    // Bawaan composition (wall) — never a black screen. The
    // pending URI is cleared so a failed video is not retried in a
    // loop; picking the file again (applyWallpaperChoice) retries it.
    private void showVideoFallback() {
        modPendingVideoUri = null;
        if (modVideoTexture != null) modVideoTexture.setVisibility(View.GONE);
        wall.setVisibility(View.VISIBLE);
        wallBlur.setVisibility(View.VISIBLE);
        // MOD 1.2.14-wallbg-cpudiag: diagnostic only — the one
        // legitimate path that shows the static Bawaan image.
        Log.w("OwnTV-Wall", "fallback wall shown (video failed)");
    }

    // FIT the video into the texture view preserving aspect ratio
    // (the old VideoView behavior): scale = min(view/video) about
    // the view centre. For the real content (1920x1080 on a
    // 1920x1080 screen) this is the identity transform.
    private void applyVideoFitTransform(int videoW, int videoH) {
        if (modVideoTexture == null || videoW <= 0 || videoH <= 0) return;
        int vw = modVideoTexture.getWidth(), vh = modVideoTexture.getHeight();
        if (vw <= 0 || vh <= 0) return;
        float scale = Math.min((float) vw / videoW, (float) vh / videoH);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale, vw / 2f, vh / 2f);
        modVideoTexture.setTransform(m);
    }

    // MOD 1.2.13-video-texture: create the player on the texture's
    // surface. Looping, volume (wallpaper-sound toggle) and the
    // static fallback behave exactly as the old VideoView path did.
    private void startVideoPlayer(android.net.Uri uri) {
        releaseVideoPlayer();
        if (!modVideoSurfaceReady || modVideoTexture == null || !modVideoTexture.isAvailable()) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setSurface(new Surface(modVideoTexture.getSurfaceTexture()));
            mp.setDataSource(this, uri);
            mp.setLooping(true);
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                    applyVideoFitTransform(w, h);
                }
            });
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    applyVideoFitTransform(m.getVideoWidth(), m.getVideoHeight());
                    applyWallpaperVolume();
                    try {
                        m.start();
                    } catch (Exception ignored) {
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    // MOD 1.2.14-wallbg-cpudiag: diagnostic only.
                    Log.w("OwnTV-Wall", "player error what=" + what + " extra=" + extra);
                    releaseVideoPlayer();
                    showVideoFallback();
                    return true; // handled: no system error dialog
                }
            });
            modVideoPlayer = mp;
            mp.prepareAsync();
        } catch (Exception e) {
            // MOD 1.2.14-wallbg-cpudiag: diagnostic only.
            Log.w("OwnTV-Wall", "player setup FAILED " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            releaseVideoPlayer();
            showVideoFallback();
        }
    }

    private void playVideoUri(android.net.Uri uri) {
        modPendingVideoUri = uri;
        if (modVideoTexture == null) return;
        modVideoTexture.setVisibility(View.VISIBLE);
        // If the surface is already available, start now; otherwise
        // the SurfaceTextureListener starts it when the surface
        // appears (first build, or after pause/resume/recreation).
        if (modVideoSurfaceReady && modVideoTexture.isAvailable()) {
            startVideoPlayer(uri);
        }
    }

    /** Applies a wallpaper choice. "builtin" = bundled Bawaan MP4; otherwise a file path. */
    private void applyWallpaperChoice(String choice, boolean persist) {
        modChoice = (choice == null) ? "builtin" : choice;
        if (persist) {
            getSharedPreferences("rzlauncher_wallpaper", MODE_PRIVATE).edit().putString("choice", modChoice).apply();
        }
        // MOD 1.2.13-video-texture: release the old owned player, so
        // the sound toggle must not touch it afterwards.
        modPendingVideoUri = null;
        releaseVideoPlayer();
        if (modVideoTexture != null) modVideoTexture.setVisibility(View.GONE);
        modFitView.setImagePath(null);
        wall.setVisibility(View.GONE);
        wallBlur.setVisibility(View.GONE);
        // MOD 1.2.14-wallbg-cpudiag: diagnostic only — builtin vs
        // file matters for correlating a reported flash; the full
        // path is deliberately not logged.
        Log.w("OwnTV-Wall", "apply choice, wall hidden kind="
                + (modChoice.equals("builtin") ? "builtin" : "file"));
        if (modChoice.equals("builtin")) {
            // Bundled Bawaan live wallpaper: the MP4 itself, looping.
            // If the bundled copy is somehow unusable, fall back to the
            // static Bawaan composition (wall) — never a black screen,
            // and never an overlay over anything else.
            File bv = builtinVideoFile();
            if (bv.exists() && bv.length() > 0) {
                // Decode error inside the player falls back to the
                // static composition via startVideoPlayer's error
                // listener — same behavior as the old VideoView path.
                playVideoUri(Uri.fromFile(bv));
            } else {
                // MOD 1.2.14-wallbg-cpudiag: diagnostic only.
                Log.w("OwnTV-Wall", "builtin video file missing, wall shown");
                wall.setVisibility(View.VISIBLE);
                wallBlur.setVisibility(View.VISIBLE);
            }
            return;
        }
        File f = new File(modChoice);
        if (!f.exists()) { // deleted/unreadable: silent fallback to bundled Bawaan MP4, never black
            modChoice = "builtin";
            if (persist) {
                getSharedPreferences("rzlauncher_wallpaper", MODE_PRIVATE).edit().putString("choice", "builtin").apply();
            }
            applyWallpaperChoice("builtin", false);
            return;
        }
        if (FitBackgroundView.isVideoFile(modChoice)) {
            // Video failed: the player's error listener falls back to
            // the static Bawaan image, never crash, never black.
            // The choice stays the video file, so picking it again
            // retries the video.
            playVideoUri(Uri.fromFile(f));
        } else {
            modFitView.setImagePath(modChoice);
        }
    }

    private void openWallpaperPicker() {
        // MOD 1.2.3-navfix: pass the CURRENT choice, so the picker marks
        // the actually active file instead of the construction-time one.
        if (modPicker != null) modPicker.open(modChoice);
    }

    // ---- apps ------------------------------------------------------------------------------------

    private void reload() {
        dirty = false;
        final Context app = getApplicationContext();
        io.execute(() -> {
            final List<AppAdapter.Entry> list = loadApps(app);
            handler.post(() -> {
                if (isFinishing()) return;
                if (adapter.getItemCount() == 0) inBar = false;
                adapter.setItems(list);
                // MOD 1.2.12: re-apply the menu geometry once the app
                // list is known — the bottom-anchored Semua container
                // height depends on the row count, and at build time
                // the adapter is still empty (fallback 560 px).
                applyMenuLayout();
                resyncGrid();
                grid.post(this::restoreFocus);
            });
        });
    }

    private List<AppAdapter.Entry> loadApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> hiddenPkgs = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hiddenPkgs.addAll(Arrays.asList(getResources().getStringArray(R.array.live_tv_apps)));   // already under Inputs
        Set<String> hiddenKeys = prefs.getStringSet("hidden", Collections.emptySet());
        List<String> dock = Arrays.asList(getResources().getStringArray(R.array.dock_apps));
        List<String> order = savedOrder();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<AppAdapter.Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hiddenPkgs.contains(pkg)) continue;
            if (!seen.add(pkg)) continue;      // one tile per app (Sony's Timers & Clock lists itself twice)
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            if (hiddenKeys.contains(cn.flattenToShortString())) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            out.add(new AppAdapter.Entry(String.valueOf(ri.loadLabel(pm)), cn, banner, ri.loadIcon(pm)));
        }
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(phone, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hiddenPkgs.contains(pkg)) continue;
            if ((ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;  // only apps you installed
            if (!seen.add(pkg)) continue;
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            if (hiddenKeys.contains(cn.flattenToShortString())) continue;
            out.add(new AppAdapter.Entry(String.valueOf(ri.loadLabel(pm)), cn, ri.activityInfo.applicationInfo.loadBanner(pm), ri.loadIcon(pm)));
        }
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rank.put(order.get(i), i);
        Collections.sort(out, (a, b) -> {
            Integer ra = rank.get(a.key), rb = rank.get(b.key);
            if (ra != null || rb != null) {
                if (ra == null) return 1;
                if (rb == null) return -1;
                return Integer.compare(ra, rb);
            }
            int da = dock.indexOf(a.pkg), db = dock.indexOf(b.pkg);
            if (da >= 0 || db >= 0) {
                if (da < 0) return 1;
                if (db < 0) return -1;
                return Integer.compare(da, db);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        return out;
    }

    private List<String> savedOrder() {
        String s = prefs.getString("order", "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    private void saveOrder() {
        List<String> keys = new ArrayList<>();
        for (AppAdapter.Entry e : adapter.items()) keys.add(e.key);
        prefs.edit().putString("order", TextUtils.join("\n", keys)).apply();
    }

    private void restoreFocus() {
        // MOD 1.2.3-navfix: never steal focus back to the grid while the
        // picker is visible (e.g. after a reload posted from onResume).
        if (modPicker != null && modPicker.getVisibility() == View.VISIBLE) {
            modPicker.reassertFocus();
            return;
        }
        if (panelOpen) return;
        int n = adapter.getItemCount();
        if (n == 0) return;          // apps still loading: focus lands on the first tile when they arrive
        int p = adapter.positionOf(lastKey);
        if (p < 0) p = Math.min(lastPos, n - 1);
        if (inBar) focusPill(barIndex, false);
        else focusTile(p, false);
    }

    // ---- AppAdapter.Host -------------------------------------------------------------------------

    @Override
    public boolean isFocusedPos(int pos) {
        return !inBar && !panelOpen && pos == focusPos;
    }

    @Override
    public boolean isMoving() {
        return moving;
    }

    @Override
    public void onTileFocus(int pos, AppAdapter.Holder h, boolean focused) {
        if (!focused) {
            // Opening the side panel takes focus away, but the tile it is about stays lifted.
            if (!panelOpen) h.applyState(false, false, true);
            return;
        }
        h.applyState(true, moving, true);
        boolean changed = pos != focusPos || inBar;
        focusPos = pos;
        inBar = false;
        lastPos = pos;
        AppAdapter.Entry e = adapter.at(pos);
        lastKey = e != null ? e.key : null;
        if (changed && !firstFocus) sounds.tick();
        firstFocus = false;
        scrollToRow(pos / menuSpan());
        glide(pos % menuSpan(), false);
        setDeep(pos / menuSpan() > 1);
        maybeShowHint();
    }

    @Override
    public boolean onTileKey(int pos, AppAdapter.Holder h, int code, KeyEvent e) {
        if (zoom.isBusy()) return true;          // an app is opening or Home is coming back: ignore keys
        if (code == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP && !moving) onTileHold(pos);
            return true;
        }
        if (!isDpad(code)) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (throttled(e)) return true;
        int n = adapter.getItemCount(), col = pos % menuSpan(), row = pos / menuSpan(), target = -1;
        // Left and right read the grid like a line of text: past the end of a row they carry on at the
        // start of the next (or the end of the one above). Right on the last app comes back to the
        // first; left on the first app stays put with the usual nudge.
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && pos > 0) target = pos - 1;
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && pos + 1 < n) target = pos + 1;
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && !moving && n > 1 && e.getRepeatCount() == 0) target = 0;
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN && (row + 1) * menuSpan() < n) target = Math.min(pos + menuSpan(), n - 1);
        else if (code == KeyEvent.KEYCODE_DPAD_UP) {
            if (row > 0) target = pos - menuSpan();
            else if (!moving) {
                focusPill(barIndex, true);
                return true;
            }
        }
        if (target < 0) {
            bump(h.itemView, code);
            return true;
        }
        if (moving) moveTo(pos, target);
        else focusTile(target, true);
        return true;
    }

    @Override
    public void onTileClick(int pos) {
        if (zoom.isBusy()) return;
        if (moving) {
            endMove(true);
            return;
        }
        AppAdapter.Entry e = adapter.at(pos);
        if (e == null) return;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(e.pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(e.pkg);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(e.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        final Intent launch = i;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh == null) {
            startApp(launch, e, null);
            return;
        }
        zoom.open((AppAdapter.Holder) vh, e.key, () -> startApp(launch, e,
                android.app.ActivityOptions.makeCustomAnimation(this, R.anim.app_in, R.anim.hold).toBundle()));
    }

    /** The tile now showing this app, if it is on screen. */
    private AppAdapter.Holder holderFor(String key) {
        int pos = adapter.positionOf(key);
        if (pos < 0) return null;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        return vh instanceof AppAdapter.Holder ? (AppAdapter.Holder) vh : null;
    }

    private void startApp(Intent i, AppAdapter.Entry e, android.os.Bundle opts) {
        try {
            startActivity(i, opts);
            // Safety net: if Home never actually went to the background (an app that opens nothing
            // on screen), do not leave the plate up and the tiles locked.
            handler.postDelayed(() -> {
                if (resumed && zoom.isOpen()) zoom.close(holderFor(zoom.key()));
            }, 2500);
        } catch (Exception ex) {
            zoom.reset();
            Toast.makeText(this, getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onTileHold(int pos) {
        if (moving) return;
        final AppAdapter.Entry e = adapter.at(pos);
        if (e == null) return;
        prefs.edit().putBoolean("hinted", true).apply();
        hideHint();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(getString(R.string.move), null, 0, () -> startMove(pos)));
        rows.add(new Row(getString(R.string.hide), null, 0, () -> hideApp(e)));
        rows.add(new Row(getString(R.string.app_info), null, 0, () -> open(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)))));
        openPanel(e.label, rows);
    }

    // ---- focus and motion ------------------------------------------------------------------------

    private static boolean isDpad(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    /** Held keys repeat at the remote's rate; cap it so releasing the key stops focus at once. */
    private boolean throttled(KeyEvent e) {
        long now = SystemClock.uptimeMillis();
        if (e.getRepeatCount() > 0 && now - lastMoveAt < REPEAT_MIN_MS) return true;
        lastMoveAt = now;
        return false;
    }

    private void focusTile(int pos, boolean sound) {
        int n = adapter.getItemCount();
        if (n == 0) return;
        final int p = Math.max(0, Math.min(pos, n - 1));
        if (!sound) firstFocus = true;
        scrollToRow(p / menuSpan());
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(p);
        if (vh != null) {
            pendingFocus = -1;
            View card = ((AppAdapter.Holder) vh).card;
            if (card.isFocused()) onTileFocus(p, (AppAdapter.Holder) vh, true);
            else card.requestFocus();
        } else {
            pendingFocus = p;   // focused by the attach listener once the scroll brings it in
        }
    }

    /** Row 0 sits at y = 612 with the wallpaper in full view; deeper rows slide up under a blurred backdrop.
     * MOD 1.2.12: Bawah placement no longer uses this top-anchored
     * formula at all — see scrollToRowBottom(), which scrolls
     * minimally inside the bottom-anchored window so the focused row
     * is always fully visible. */
    private void scrollToRow(int row) {
        if (menuPlacement().equals("bottom")) {
            scrollToRowBottom(row);
            return;
        }
        int rowH = Ui.px(AppAdapter.TILE_H + AppAdapter.ROW_GAP);
        // MOD 1.2.9-widget-layout: in the 1-row window the focused row
        // must sit at the window's first row, or it would be clipped out
        // (the old "rows 0 and 1 need no scroll" rule only holds for the
        // 2-row window). 2-row and Semua keep the original formula.
        int target = menuRows() == 1 ? row * rowH
                : row <= 1 ? 0 : Ui.px(470) + (row - 2) * rowH;
        // Already gliding to this row: let it finish rather than restart the ease on every key press.
        if (target == scrollTarget && grid.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
        scrollTarget = target;
        int dy = target - currentOffset();
        if (dy != 0) grid.smoothScrollBy(0, dy, EASE_OUT, 320);
    }

    /** MOD 1.2.12-menu-bottom-cpu: scroll path for the bottom-anchored
     * Bawah window only. The window shows a vertical slice of the
     * content (window height = the applied bottom container height);
     * this scrolls minimally — only when the focused row would
     * otherwise be partly or fully outside the window — so the
     * focused row is always fully visible, including the last row
     * (which the old top-anchored Semua layout cut by 16 px at the
     * screen edge). At rest (rows 0..windowRows-1 focused) the offset
     * stays 0 and the rows hug the bottom edge. */
    private void scrollToRowBottom(int row) {
        int rowH = Ui.px(AppAdapter.TILE_H + AppAdapter.ROW_GAP);
        int tileH = Ui.px(AppAdapter.TILE_H);
        int winH = modBottomWinH;
        if (winH <= 0) {
            int r = menuRows();
            winH = r == 1 ? tileH : r == 2 ? tileH + rowH : 560;
        }
        int totalRows = adapter == null ? 0
                : (adapter.getItemCount() + menuSpan() - 1) / menuSpan();
        // Last tile bottom in content coordinates (item pitch includes
        // the gap, but the last tile itself has no trailing gap).
        int contentH = totalRows > 0 ? (totalRows - 1) * rowH + tileH : 0;
        int maxScroll = Math.max(0, contentH - winH);
        int cur = currentOffset();
        if (cur < 0) cur = 0;
        if (cur > maxScroll) cur = maxScroll;
        int rowTop = row * rowH, rowBottom = rowTop + tileH;
        int target = cur;
        if (rowBottom > cur + winH) target = rowBottom - winH;
        else if (rowTop < cur) target = rowTop;
        if (target < 0) target = 0;
        if (target > maxScroll) target = maxScroll;
        // Already gliding to this row: let it finish rather than restart the ease on every key press.
        if (target == scrollTarget && grid.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
        scrollTarget = target;
        int dy = target - cur;
        if (dy != 0) grid.smoothScrollBy(0, dy, EASE_OUT, 320);
    }

    /**
     * Moving, hiding or reloading apps can shift the rows without a key press (RecyclerView keeps its
     * anchor tile in place). Once the reflow has settled, put the grid back where the focused row belongs.
     */
    private void resyncGrid() {
        handler.removeCallbacks(resync);
        handler.postDelayed(resync, 280);   // after the 220 ms move animation
    }

    private final Runnable resync = () -> {
        scrollTarget = Integer.MIN_VALUE;
        scrollToRow(Math.max(0, focusPos) / menuSpan());
    };

    /**
     * How far the grid is scrolled, read from where the rows actually are. A running total of scroll
     * events drifts when apps are hidden or reordered (the layout shifts without a scroll event), which
     * left the rows stranded halfway up the screen; measuring every time cannot drift.
     */
    private int currentOffset() {
        // Read any laid-out tile directly. findFirstVisibleItemPosition() cannot be used here: the grid's
        // bottom padding is a full screen, so the "visible" band between the paddings is empty and it
        // always answered NO_POSITION, which made every deep scroll overshoot until the grid ran away.
        for (int i = 0, c = grid.getChildCount(); i < c; i++) {
            View v = grid.getChildAt(i);
            int pos = grid.getChildAdapterPosition(v);   // NO_POSITION for a tile animating out
            if (pos == RecyclerView.NO_POSITION || v.getHeight() == 0) continue;
            return (pos / menuSpan()) * v.getHeight() + grid.getPaddingTop() - v.getTop();
        }
        return 0;
    }

    /** The wallpaper glides a little against the focus: near layers seem to move, the scene gains depth. */
    private void glide(int col, boolean bar) {
        float x = bar ? -2 * Ui.f(14) : (2 - col) * Ui.f(14);
        float y = bar ? Ui.f(8) : 0f;
        Ui.spring(wall, R.id.spring_tx, DynamicAnimation.TRANSLATION_X, x, 120f, 1f);
        Ui.spring(wall, R.id.spring_ty, DynamicAnimation.TRANSLATION_Y, y, 120f, 1f);
    }

    private void setDeep(boolean d) {
        if (d == deep) return;
        deep = d;
        // MOD 1.2.14-wallbg-cpudiag: diagnostic only — one line per
        // transition (guarded by the early return above), so a
        // user-reported flash can be correlated with wall alpha
        // animation timing afterwards.
        Log.w("OwnTV-Wall", "setDeep " + (d ? "deep" : "shallow"));
        wall.animate().alpha(d ? 0.45f : 1f).setDuration(300).start();
        bar.animate().alpha(d ? 0f : 1f).setDuration(250).start();
        applyHomeStyle(true);
    }

    /** At an edge the focused item nudges 6 px toward the press and comes back: "nothing more this way". */
    private void bump(View v, int code) {
        // Always from and back to rest, cancelling a nudge still in flight, so held keys never leave a view off-place.
        Object old = v.getTag(R.id.bump);
        if (old instanceof ObjectAnimator) ((ObjectAnimator) old).cancel();
        float d = Ui.f(6);
        boolean horizontal = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        String prop = horizontal ? "translationX" : "translationY";
        float s = (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP) ? -d : d;
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat(prop, 0f, s, 0f));
        a.setDuration(150);
        a.setInterpolator(EASE_OUT);
        v.setTag(R.id.bump, a);
        a.start();
        sounds.knock();
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void focusPill(int i, boolean sound) {
        if (!sound) firstFocus = true;
        pills[i].requestFocus();
    }

    private void onPillFocus(int i, boolean has) {
        if (has) {
            boolean changed = !inBar || barIndex != i;
            inBar = true;
            barIndex = i;
            focusPos = -1;
            if (changed && !firstFocus) sounds.tick();
            firstFocus = false;
            glide(0, true);
        }
        handler.post(this::updateBarFocus);   // after focus has fully moved (leaving one pill, entering the next)
    }

    /** Droplet, icon colours and caption follow the bar's state: focused in the bar, or its panel open. */
    private void updateBarFocus() {
        boolean fromBar = panelOpen && panelOpener != null && Arrays.asList(pills).contains(panelOpener);
        boolean show = (inBar && !panelOpen && pills[barIndex].isFocused()) || fromBar;
        int idx = fromBar ? Arrays.asList(pills).indexOf(panelOpener) : barIndex;
        float x = idx * Ui.f(68);
        if (show && !platterShown) {
            Ui.set(platter, R.id.spring_tx, DynamicAnimation.TRANSLATION_X, x);   // appear where it is needed
        } else {
            Ui.spring(platter, R.id.spring_tx, DynamicAnimation.TRANSLATION_X, x, 520f, 0.72f);
        }
        if (show != platterShown) {
            platterShown = show;
            platter.animate().alpha(show ? 1f : 0f).scaleX(show ? 1.06f : 0.82f).scaleY(show ? 1.06f : 0.82f)
                    .setDuration(show ? 240 : 140).setInterpolator(show ? SPRING_OUT : EASE_OUT).start();
        }
        for (int k = 0; k < 3; k++) tintIcon(k, show && k == idx);

        updateCaption(show && !panelOpen, pills[idx].getContentDescription().toString(), Ui.f(6) + x + Ui.f(32));
    }

    /**
     * The name under the focused button. Moving to another button: the old name fades out as it starts
     * to travel, the new one fades in as it arrives, centred under its button.
     */
    private void updateCaption(boolean on, String text, float centre) {
        final int seq = ++captionSeq;
        if (!on) {
            caption.animate().cancel();
            caption.animate().alpha(0f).translationY(Ui.f(84)).setStartDelay(0).setDuration(110)
                    .setInterpolator(EASE_OUT).start();
            return;
        }
        boolean hidden = caption.getAlpha() < 0.01f;
        if (hidden || text.contentEquals(caption.getText())) {
            if (!text.contentEquals(caption.getText())) {
                caption.setText(text);
                caption.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            }
            float tx = centre - caption.getMeasuredWidth() / 2f;
            if (hidden) Ui.set(caption, R.id.caption_tx, DynamicAnimation.TRANSLATION_X, tx);
            else Ui.spring(caption, R.id.caption_tx, DynamicAnimation.TRANSLATION_X, tx, 520f, 0.72f);
            caption.animate().cancel();
            caption.animate().alpha(1f).translationY(Ui.f(88)).setStartDelay(hidden ? 70 : 0).setDuration(180)
                    .setInterpolator(EASE_OUT).start();
            return;
        }
        Ui.spring(caption, R.id.caption_tx, DynamicAnimation.TRANSLATION_X,
                centre - caption.getMeasuredWidth() / 2f, 520f, 0.72f);
        caption.animate().cancel();
        caption.animate().alpha(0f).setStartDelay(0).setDuration(90).setInterpolator(EASE_OUT).withEndAction(() -> {
            if (seq != captionSeq) return;
            caption.setText(text);
            caption.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            Ui.spring(caption, R.id.caption_tx, DynamicAnimation.TRANSLATION_X,
                    centre - caption.getMeasuredWidth() / 2f, 520f, 0.72f);
            caption.animate().alpha(1f).translationY(Ui.f(88)).setDuration(160).setInterpolator(EASE_OUT).start();
        }).start();
    }

    private void tintIcon(int k, boolean dark) {
        if (iconDark[k] == dark) return;
        iconDark[k] = dark;
        if (iconTint[k] != null) iconTint[k].cancel();
        int from = dark ? Ui.INK : Ui.DARK, to = dark ? Ui.DARK : Ui.INK;
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        a.setDuration(140);
        a.setStartDelay(dark ? 60 : 0);
        a.addUpdateListener(v -> pillIcons[k].setColorFilter((int) v.getAnimatedValue()));
        a.start();
        iconTint[k] = a;
    }

    private boolean onPillKey(int i, int code, KeyEvent e) {
        if (!isDpad(code)) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (throttled(e)) return true;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && i > 0) focusPill(i - 1, true);
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && i < 2) focusPill(i + 1, true);
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN && adapter.getItemCount() > 0) {
            inBar = false;
            focusTile(Math.min(lastPos, menuSpan() - 1), true);
        } else bump(bar, code);
        return true;
    }

    private void onPillClick(int i) {
        if (i == 0) runBoost();
        else if (i == 1) openNetwork();
        else openSettings();
    }

    /** MOD 1.2.25-boost-root: Boost yang benar-benar berpengaruh.
     * Versi 1.2.6 hanya killBackgroundProcesses dari aplikasi biasa; di
     * Android 11 daftar proses yang terlihat sangat terbatas, sehingga
     * tes langsung di DV8910 membebaskan 0 MB (MemAvailable 2.386.584 kB
     * sebelum, 2.386.700 kB sesudah). Versi ini, atas persetujuan user
     * 2026-10-27, memakai su (Magisk): pertama "am kill-all", lalu
     * force-stop daftar aplikasi latar yang aman di bawah. Tes via adb
     * su di perangkat yang sama: kill-all saja +283 MB, ditambah
     * force-stop YouTube/Spotify/launcher bawaan +117 MB, total
     * ~400 MB, tanpa mengganggu launcher ini.
     * TIDAK PERNAH dihentikan: paket ini sendiri, Tailscale, Magisk,
     * AnyDesk, ZeroTier, input method, systemui, dan semua paket
     * com.android.* / com.google.android.gms. Jika su ditolak atau
     * tidak ada, kembali ke Boost biasa dan toast jujur, bukan angka
     * karangan. Prompt Magisk muncul di TV pada pemakaian pertama;
     * user harus tap Izinkan. */
    private static final String[] BOOST_ROOT_PKGS = {
        "com.google.android.youtube.tv",
        "com.spotify.tv.android",
        "com.spotify.music",
        // com.google.android.tvlauncher sengaja TIDAK di-force-stop:
        // tes 1.2.25 menunjukkan setelah force-stop launcher bawaan,
        // foreground berpindah ke aplikasi sebelumnya (Tailscale),
        // sehingga user harus Back sekali. kill-all saja sudah +283 MB.
        "com.google.android.apps.mediashell",
        "com.google.android.tvrecommendations",
        "slim.box.tv.project",
        "com.netflix.ninja",
        "com.amazon.avod.thirdpartyclient",
        "org.videolan.vlc",
        "com.mxtech.videoplayer.ad",
        "com.lagradost.cloudstream3",
        "dev.akash.skystream",
        "com.liskovsoft.smarttubetv.beta",
    };

    private void runBoost() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        long before = boostAvailMem(am);
        boolean rootOk = runBoostRoot();
        if (!rootOk) {
            // Fallback lama: killBackgroundProcesses biasa.
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo pr : procs) {
                        if (pr == null || pr.pkgList == null) continue;
                        boolean own = false;
                        for (String pkg : pr.pkgList) {
                            if (getPackageName().equals(pkg)) { own = true; break; }
                        }
                        if (own) continue;
                        for (String pkg : pr.pkgList) am.killBackgroundProcesses(pkg);
                    }
                }
            }
        }
        long after = boostAvailMem(am);
        long releasedMb = (before >= 0 && after >= 0) ? (after - before) / 1048576 : 0;
        if (releasedMb > 0) {
            Toast.makeText(this, getString(R.string.boost_released, releasedMb), Toast.LENGTH_SHORT).show();
        } else if (!rootOk) {
            Toast.makeText(this, getString(R.string.boost_root_denied), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.boost_none), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean runBoostRoot() {
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            os.writeBytes("am kill-all\n");
            PackageManager pm = getPackageManager();
            for (String pkg : BOOST_ROOT_PKGS) {
                try { pm.getPackageInfo(pkg, 0); } catch (Exception e) { continue; }
                if (pkg.equals(getPackageName())) continue;
                os.writeBytes("am force-stop " + pkg + "\n");
            }
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            int code = su.waitFor();
            return code == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static long boostAvailMem(ActivityManager am) {
        if (am == null) return -1;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    /** What the network is doing right now, for the Wi-Fi panel. */
    private Row networkStatus() {
        String title = getString(R.string.offline), sub = getString(R.string.not_connected);
        int kind = 0;   // 0 offline, 1 ethernet, 2 wifi
        boolean validated = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                boolean v = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    kind = 1;
                    validated |= v;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && kind != 1) {
                    kind = 2;
                    validated |= v;
                }
            }
            if (kind == 1) {
                title = getString(R.string.ethernet);
            } else if (kind == 2) {
                title = getString(R.string.wifi);
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                if (info != null) {
                    String ssid = info.getSSID();
                    if (ssid != null) {
                        ssid = ssid.replace("\"", "").trim();
                        if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) title = ssid;
                    }
                }
            }
            if (kind != 0) sub = getString(validated ? R.string.connected_internet : R.string.no_internet);
        } catch (Exception ignored) {
        }
        return new Row(title, sub, kind, null);
    }

    private void openNetwork() {
        List<Row> rows = new ArrayList<>();
        Intent net = firstResolvable(new Intent(Settings.ACTION_WIFI_SETTINGS), new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        if (net != null) rows.add(Row.ext(getString(R.string.network_settings), () -> open(net)));
        openPanel(getString(R.string.wifi), networkStatus(), rows);
    }

    /** Settings people reach for every day; anything this TV cannot open is simply left out. */
    private void openSettings() {
        List<Row> rows = new ArrayList<>();
        // MOD: wallpaper picker entry, first row of Settings. The panel closes
        // before the picker opens; closing the picker restores grid focus.
        rows.add(new Row("Wallpaper", modChoice.equals("builtin") ? "Chainsaw Man (bawaan)" : new File(modChoice).getName(), 0, () -> {
            closePanel();
            openWallpaperPicker();
        }));
        // MOD 1.2.6-boost: Inputs moved here from the top bar (user
        // decision 02:15), immediately after Wallpaper, calling the same
        // openInputs() the pill used to call. It is row index 1, so the
        // 1.2.3-navfix return-to-Wallpaper-row path (openSettings() on
        // picker close) still lands on row 0, and panel navigation is
        // unchanged apart from the one extra row.
        rows.add(new Row(getString(R.string.inputs), null, 0, () -> {
            closePanel();
            openInputs();
        }));
        // MOD 1.2.7-tiles-sound: navigation-sound toggle, immediately after
        // Inputs. stay=true so the panel stays open and the subtitle
        // updates in place; persisted in the "home" prefs as "navSound"
        // (default true) and honoured by Sounds.tick()/knock(). This is
        // the OwnTV navigation click/knock only — video wallpaper audio
        // (TextureView) is untouched, and the wav files are not replaced.
        // Note: the "Sound" row below opens the TV's own system sound
        // settings; this row is the separate OwnTV toggle.
        final Row navSound = new Row("Suara navigasi",
                getString(prefs.getBoolean("navSound", true) ? R.string.sound_on : R.string.sound_off), 0, null);
        navSound.stay = true;
        navSound.action = () -> {
            boolean on = !prefs.getBoolean("navSound", true);
            prefs.edit().putBoolean("navSound", on).apply();
            navSound.sub = getString(on ? R.string.sound_on : R.string.sound_off);
            if (navSound.subView != null) navSound.subView.setText(navSound.sub);
        };
        rows.add(navSound);
        // MOD 1.2.8-wallpaper-sound-rows: live-wallpaper audio toggle,
        // immediately after "Suara navigasi". Same stay=true pattern and
        // the same "home" prefs (key "wallpaperSound", default true), but
        // a separate flag: this mutes/unmutes the MP4 wallpaper's own
        // audio via the playing MediaPlayer (applyWallpaperVolume(),
        // applied immediately, no reselect needed) and does NOT touch
        // navigation tick/knock. Static image wallpapers have no audio.
        final Row wallpaperSound = new Row("Suara wallpaper",
                getString(prefs.getBoolean("wallpaperSound", true) ? R.string.sound_on : R.string.sound_off), 0, null);
        wallpaperSound.stay = true;
        wallpaperSound.action = () -> {
            boolean on = !prefs.getBoolean("wallpaperSound", true);
            prefs.edit().putBoolean("wallpaperSound", on).apply();
            wallpaperSound.sub = getString(on ? R.string.sound_on : R.string.sound_off);
            if (wallpaperSound.subView != null) wallpaperSound.subView.setText(wallpaperSound.sub);
            applyWallpaperVolume();
        };
        rows.add(wallpaperSound);
        // MOD 1.2.10-weather-widget: system widget toggle, immediately
        // after "Suara wallpaper" (renamed from the 1.2.9 "Widget kiri
        // bawah" row, because the widget no longer lives in the bottom
        // corner). Same stay=true pattern and "home" prefs (key
        // "widgetSystem", default true, falling back to the old
        // "widgetBottomLeft" value). Applies immediately via
        // applyWidgetVisibility(); the widget itself is never focusable
        // and never consumes keys.
        final Row widgetRow = new Row("Widget sistem",
                getString(widgetSystemOn() ? R.string.sound_on : R.string.sound_off), 0, null);
        widgetRow.stay = true;
        widgetRow.action = () -> {
            boolean on = !widgetSystemOn();
            prefs.edit().putBoolean("widgetSystem", on).apply();
            widgetRow.sub = getString(on ? R.string.sound_on : R.string.sound_off);
            if (widgetRow.subView != null) widgetRow.subView.setText(widgetRow.sub);
            applyWidgetVisibility();
        };
        rows.add(widgetRow);
        // MOD 1.2.9-widget-layout: menu row window, cycled by OK in the
        // listed order 1 baris -> 2 baris -> Semua -> 1 baris. Persisted
        // in "home" as "menuRows" (1, 2, or 0 = Semua; default 2, the
        // 1.2.8 behaviour). Applies immediately via applyMenuLayout();
        // no app is ever removed or hidden, scrolling still works.
        final Row menuRowsRow = new Row("Baris menu", menuRowsLabel(), 0, null);
        menuRowsRow.stay = true;
        menuRowsRow.action = () -> {
            int cur = menuRows();
            int next = cur == 1 ? 2 : cur == 2 ? 0 : 1;
            prefs.edit().putInt("menuRows", next).apply();
            menuRowsRow.sub = menuRowsLabel();
            if (menuRowsRow.subView != null) menuRowsRow.subView.setText(menuRowsRow.sub);
            applyMenuLayout();
        };
        rows.add(menuRowsRow);
        // MOD 1.2.9-widget-layout: menu placement, cycled by OK in the
        // listed order Bawah -> Kanan -> Kiri -> Atas -> Bawah. Persisted
        // in "home" as "menuPlacement" ("bottom"/"right"/"left"/"top";
        // default "bottom"). Kanan/Kiri are single-column vertical
        // sidebars (span 1, up/down navigates); Atas is the same
        // 7-column horizontal grid moved up below the clock block so it
        // never covers the clock/weather text. Applies immediately.
        final Row menuPlaceRow = new Row("Letak menu", menuPlacementLabel(), 0, null);
        menuPlaceRow.stay = true;
        menuPlaceRow.action = () -> {
            String cur = menuPlacement();
            String next = cur.equals("bottom") ? "right" : cur.equals("right") ? "left"
                    : cur.equals("left") ? "top" : "bottom";
            prefs.edit().putString("menuPlacement", next).apply();
            menuPlaceRow.sub = menuPlacementLabel();
            if (menuPlaceRow.subView != null) menuPlaceRow.subView.setText(menuPlaceRow.sub);
            applyMenuLayout();
            // MOD 1.2.17-widget-anchor-fix: placement changes move the
            // widget, so the applied anchor must be reset and
            // re-applied explicitly (applyMenuLayout no longer
            // re-anchors). The reset also forces fresh TOP params on
            // return to Bawah even if the margin value is unchanged.
            // The posted re-apply recomputes the Semua window once
            // the widget is at its new position.
            modAnchorAppliedTop = Integer.MIN_VALUE;
            applyWidgetAnchor();
            gridViewport.post(this::applyMenuLayout);
        };
        rows.add(menuPlaceRow);
        Intent picture = firstResolvable(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        if (picture != null) rows.add(Row.ext(getString(R.string.picture), () -> open(picture)));
        // MOD 1.2.20-hide-sound-row (user decision, widget tap 2026-10-27 07:42):
        // the stock "Sound" external row (Settings.ACTION_SOUND_SETTINGS) is REMOVED.
        // On this box's firmware the target SoundActivity does not exist in
        // DroidTvSettings, so the row silently did nothing (proven by a
        // ClassNotFoundException crash log) — a firmware bug, not fixable from
        // OwnTV, and the user chose removal over redirection. The mod's own
        // working sound toggles ("Suara navigasi", "Suara wallpaper") are
        // separate rows above and are untouched, as are Picture, sleep
        // timer, stock home, and All settings below.
        for (String pkg : getResources().getStringArray(R.array.sleep_timer_apps)) {
            Intent t = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (t == null) t = getPackageManager().getLaunchIntentForPackage(pkg);
            if (t != null) {
                final Intent ti = t;
                rows.add(Row.ext(getString(R.string.sleep_timer), () -> open(ti)));
                break;
            }
        }
        int hidden = prefs.getStringSet("hidden", Collections.emptySet()).size();
        if (hidden > 0) {
            rows.add(new Row(getString(R.string.show_hidden), String.valueOf(hidden), 0, () -> {
                prefs.edit().remove("hidden").apply();
                reload();
            }));
        }
        for (String pkg : getResources().getStringArray(R.array.stock_launchers)) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
            if (getPackageManager().resolveActivity(home, 0) != null) {
                rows.add(Row.ext(getString(R.string.stock_home), () -> {
                    HomeKeyService.allowStockHome(this);
                    open(home);
                }));
                break;
            }
        }
        // MOD 1.2.27-stock-toggle (user 2026-10-27 16:37, "buat saja seperti
        // yang saya bilang tadi, agar mudah"): satu baris di panel Pengaturan
        // untuk mengaktifkan/menonaktifkan launcher bawaan dari TV saja,
        // tanpa Muse/adb. RZ Launcher sudah jadi default, jadi menonaktifkan
        // launcher bawaan aman: Home langsung ke RZ, tanpa dialog. Baris ini
        // memakai su (Magisk) seperti Boost root; pemakaian pertama memunculkan
        // prompt Magisk, user tap Izinkan. Mengaktifkan lagi TIDAK otomatis
        // menjadikan launcher bawaan default — tekan Home lalu pilih
        // Beranda Android TV + Selalu kalau mau pindah default ke sana.
        // stay=true supaya panel tetap terbuka dan sub berubah di tempat.
        final Row stockLauncherRow = new Row("Launcher bawaan",
                isStockLauncherEnabled() ? "Aktif" : "Nonaktif", 0, null);
        stockLauncherRow.stay = true;
        stockLauncherRow.action = () -> {
            boolean enabled = isStockLauncherEnabled();
            boolean ok = setStockLauncherEnabled(!enabled);
            if (ok) {
                stockLauncherRow.sub = !enabled ? "Aktif" : "Nonaktif";
                if (stockLauncherRow.subView != null) stockLauncherRow.subView.setText(stockLauncherRow.sub);
                Toast.makeText(this, getString(!enabled ? R.string.stock_launcher_enabled : R.string.stock_launcher_disabled), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.stock_launcher_root_denied), Toast.LENGTH_SHORT).show();
            }
        };
        rows.add(stockLauncherRow);
        // MOD 1.2.30-donate-settings (user 2026-10-27 20:11, "coba buatkan"):
        // baris Dukung di panel Pengaturan, supaya link Saweria tetap
        // bisa ditemukan setelah dialog donasi pertama dicentang
        // "Jangan tampilkan lagi". Membuka https://saweria.co/rzmong
        // lewat browser TV jika ada; kalau tidak ada, toast, bukan crash.
        // Panel ditutup dulu (closePanel) seperti baris Wallpaper, supaya
        // browser yang dibuka tidak tertutup panel.
        rows.add(Row.ext("☕ Support Developer", () -> {
            closePanel();
            try {
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("https://saweria.co/rzmong"));
                if (getPackageManager().resolveActivity(view, 0) != null) open(view);
                else Toast.makeText(this, "Tidak ada browser di TV ini", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Tidak bisa membuka Saweria", Toast.LENGTH_SHORT).show();
            }
        }));
        // MOD 1.2.32-about-owntv (user 2026-10-27 20:38):
        // Baris Tentang di panel Pengaturan. Hanya menampilkan satu
        // baris atribusi dalam bahasa Inggris, tanpa aksi — menekan
        // baris ini tidak membuka apa-apa. Atribusi lengkap (nama
        // pemegang hak cipta + teks lisensi MIT) tetap ada di file
        // LICENSE dan README repo GitHub, baris ini hanya penanda
        // singkat di dalam APK.
        // MOD 1.0-about-dialog (user 2026-10-27 21:01): baris About
        // TIDAK lagi menampilkan atribusi langsung sebagai subjudul.
        // Diklik baru muncul dialog penjelasan yang agak panjang,
        // termasuk profil pengembang. Panel ditutup dulu supaya dialog
        // tidak tertutup panel, seperti baris Support Developer.
        rows.add(Row.ext("About", () -> {
            closePanel();
            showAboutDialog();
        }));
        rows.add(Row.ext(getString(R.string.all_settings), () -> open(new Intent(Settings.ACTION_SETTINGS))));
        openPanel(getString(R.string.settings), null, rows);
    }

    private boolean isStockLauncherEnabled() {
        try {
            return getPackageManager().getApplicationInfo("com.google.android.tvlauncher", 0).enabled;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean setStockLauncherEnabled(boolean enable) {
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            os.writeBytes((enable ? "pm enable " : "pm disable ") + "com.google.android.tvlauncher\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            return su.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private Intent firstResolvable(Intent... candidates) {
        for (Intent i : candidates) if (getPackageManager().resolveActivity(i, 0) != null) return i;
        return null;
    }

    // ---- panel -----------------------------------------------------------------------------------

    private static final class Row {
        final String title;
        String sub;
        final int dot;          // input rows: 0 none, 1 connected. Status row: 0 offline, 1 wired, 2 Wi-Fi
        Runnable action;
        boolean ext;            // opens a screen of the TV's own Settings: shows a chevron
        boolean stay;           // acts in place; the panel stays open
        TextView subView;

        Row(String title, String sub, int dot, Runnable action) {
            this.title = title;
            this.sub = sub;
            this.dot = dot;
            this.action = action;
        }

        static Row ext(String title, Runnable action) {
            Row r = new Row(title, null, 0, action);
            r.ext = true;
            return r;
        }
    }

    private void openPanel(String title, List<Row> rows) {
        openPanel(title, null, rows);
    }

    /** A frosted panel that drops from under the bar; Home behind it dims. */
    private void openPanel(String title, Row status, List<Row> rows) {
        if (rows.isEmpty() && status == null) return;
        panelOpener = getCurrentFocus();
        panelRows.removeAllViews();
        panelTitle.setText(title);
        panelTitle.setVisibility(title == null ? View.GONE : View.VISIBLE);
        panelStatus.removeAllViews();
        panelStatus.setVisibility(status == null ? View.GONE : View.VISIBLE);
        if (status != null) panelStatus.addView(statusView(status));
        boolean dots = false;
        for (Row r : rows) dots |= r.dot != 0;
        for (int i = 0; i < rows.size(); i++) panelRows.addView(rowView(rows.get(i), i, rows.size(), dots));
        panelOpen = true;
        panel.setVisibility(View.VISIBLE);
        panel.setPivotX(Ui.f(480));
        panel.setPivotY(0f);
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.setTranslationY(-Ui.f(12));
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(40).setDuration(320)
                .setInterpolator(SPRING_OUT).start();
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(220).setInterpolator(EASE_OUT).start();
        bar.animate().alpha(1f).setDuration(200).start();   // the panel hangs from the bar, so show it even deep in the grid
        firstFocus = true;
        if (panelRows.getChildCount() > 0) panelRows.getChildAt(0).requestFocus();
        else panel.requestFocus();
        updateBarFocus();
    }

    private void closePanel() {
        if (!panelOpen) return;
        panelOpen = false;
        panel.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).translationY(-Ui.f(8)).setStartDelay(0).setDuration(160)
                .setInterpolator(EASE_OUT).withEndAction(() -> {
                    if (!panelOpen) panel.setVisibility(View.GONE);
                }).start();
        scrim.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (!panelOpen) scrim.setVisibility(View.GONE);
        }).start();
        bar.animate().alpha(deep ? 0f : 1f).setDuration(200).start();
        firstFocus = true;
        if (panelOpener != null && panelOpener.isAttachedToWindow()) panelOpener.requestFocus();
        else restoreFocus();
        handler.postDelayed(this::updateBarFocus, 120);   // the caption returns once the panel has gone
    }

    private View statusView(Row r) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(Ui.px(20), Ui.px(18), Ui.px(20), Ui.px(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.f(24));
        bg.setColor(0x12FFFFFF);
        v.setBackground(bg);
        int tint = r.dot == 0 ? 0xFFFF453A : Ui.OK_GREEN;
        FrameLayout badge = new FrameLayout(this);
        GradientDrawable bd = new GradientDrawable();
        bd.setShape(GradientDrawable.OVAL);
        bd.setColor((tint & 0x00FFFFFF) | 0x2E000000);
        badge.setBackground(bd);
        ImageView ic = new ImageView(this);
        ic.setImageResource(r.dot == 2 ? R.drawable.ic_wifi : (r.dot == 1 ? R.drawable.ic_ethernet : R.drawable.ic_offline));
        ic.setColorFilter(tint);
        badge.addView(ic, new FrameLayout.LayoutParams(Ui.px(28), Ui.px(28), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.px(52), Ui.px(52));
        blp.rightMargin = Ui.px(18);
        v.addView(badge, blp);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, 27, true, Ui.INK);
        t.setText(r.title);
        t.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(t);
        TextView s = Ui.text(this, 22, false, 0xA8F2F2F5);
        s.setText(r.sub);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = Ui.px(6);
        text.addView(s, sl);
        v.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = Ui.px(8);
        lp.rightMargin = Ui.px(8);
        lp.topMargin = Ui.px(4);
        lp.bottomMargin = Ui.px(8);
        v.setLayoutParams(lp);
        return v;
    }

    private View rowView(Row r, int index, int count, boolean dots) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(Ui.px(22), 0, Ui.px(22), 0);
        v.setFocusable(true);
        v.setClickable(true);
        v.setDefaultFocusHighlightEnabled(false);
        final Droplet drop = new Droplet(Ui.f(24));
        drop.setAlpha(0);
        v.setBackground(drop);
        View dot = null;
        if (dots) {
            dot = new View(this);
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(r.dot == 1 ? Ui.OK_GREEN : 0x42F2F2F5);
            dot.setBackground(dd);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.px(12), Ui.px(12));
            dlp.rightMargin = Ui.px(18);
            v.addView(dot, dlp);
        }
        final View dotView = dot;
        final TextView t = Ui.text(this, 27, true, Ui.INK);
        t.setText(r.title);
        t.setEllipsize(TextUtils.TruncateAt.END);
        v.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView s = Ui.text(this, 25, false, 0x9EF2F2F5);
        if (r.sub != null) {
            s.setText(r.sub);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.leftMargin = Ui.px(16);
            v.addView(s, sl);
            r.subView = s;
        }
        final ImageView chev = new ImageView(this);
        if (r.ext) {
            chev.setImageResource(R.drawable.ic_chevron);
            chev.setColorFilter(0x66F2F2F5);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.px(22), Ui.px(22));
            cl.leftMargin = Ui.px(14);
            v.addView(chev, cl);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.px(64));
        if (index > 0) lp.topMargin = Ui.px(4);
        v.setLayoutParams(lp);
        v.setOnFocusChangeListener((view, has) -> {
            Object old = view.getTag(R.id.bump_fade);
            if (old instanceof ValueAnimator) ((ValueAnimator) old).cancel();
            ValueAnimator a = ValueAnimator.ofInt(drop.getAlpha(), has ? 255 : 0);
            a.setDuration(has ? 140 : 100);
            a.addUpdateListener(an -> drop.setAlpha((int) an.getAnimatedValue()));
            view.setTag(R.id.bump_fade, a);
            a.start();
            t.setTextColor(has ? Ui.DARK : Ui.INK);
            s.setTextColor(has ? 0x8C0B0B0F : 0x9EF2F2F5);
            chev.setColorFilter(has ? 0x730B0B0F : 0x66F2F2F5);
            if (dotView != null && r.dot != 1) ((GradientDrawable) dotView.getBackground()).setColor(has ? 0x380B0B0F : 0x42F2F2F5);
            if (has && !firstFocus) sounds.tick();
            if (has) firstFocus = false;
        });
        v.setOnKeyListener((view, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (e.getAction() == KeyEvent.ACTION_UP) closePanel();
                return true;
            }
            if (!isDpad(code)) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (throttled(e)) return true;
            if (code == KeyEvent.KEYCODE_DPAD_UP && index > 0) panelRows.getChildAt(index - 1).requestFocus();
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN && index < count - 1) panelRows.getChildAt(index + 1).requestFocus();
            else bump(view, code);
            return true;
        });
        v.setOnClickListener(view -> {
            if (r.stay) {
                sounds.tick();
                r.action.run();
                return;
            }
            closePanel();
            if (r.action != null) r.action.run();
        });
        return v;
    }

    private void openInputs() {
        List<Row> rows = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> all = new ArrayList<>(tim.getTvInputList());
            // HDMI-CEC devices show up as child inputs of a port: use their name as the port's subtitle.
            Map<String, String> deviceName = new HashMap<>();
            for (TvInputInfo i : all) {
                if (i.getParentId() != null && !i.isHidden(this)) {
                    CharSequence l = i.loadLabel(this);
                    if (l != null) deviceName.put(i.getParentId(), l.toString());
                }
            }
            Collections.sort(all, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony among them) report every HDMI port as connected whether or not anything is
            // plugged in: then show no dots rather than wrong ones.
            int ports = 0, connected = 0;
            for (TvInputInfo i : all) {
                if (!i.isPassthroughInput() || i.getType() != TvInputInfo.TYPE_HDMI || i.getParentId() != null) continue;
                ports++;
                if (tim.getInputState(i.getId()) == TvInputManager.INPUT_STATE_CONNECTED) connected++;
            }
            boolean bogus = ports > 1 && connected == ports;
            for (TvInputInfo i : all) {
                if (!i.isPassthroughInput() || i.getParentId() != null || i.isHidden(this)) continue;
                CharSequence l = i.loadLabel(this);
                String label = l == null ? i.getId() : l.toString();
                boolean hdmi = i.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                int dot = !bogus && tim.getInputState(i.getId()) == TvInputManager.INPUT_STATE_CONNECTED ? 1 : 0;
                final String id = i.getId();
                rows.add(new Row(label, deviceName.get(id), dot, () -> open(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel"))));
            }
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null) rows.add(new Row(getString(R.string.live_tv), null, 0, () -> open(liveTv)));
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        openPanel(getString(R.string.inputs), rows);
    }

    /** The TV's own Live TV app: the first known one installed (Sony, Google, AOSP, MediaTek, TCL...). */
    private Intent liveTvIntent() {
        PackageManager pm = getPackageManager();
        for (String pkg : getResources().getStringArray(R.array.live_tv_apps)) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i;
        }
        return null;
    }

    // ---- move and hide ---------------------------------------------------------------------------

    private void startMove(int pos) {
        savedOrder = new ArrayList<>();
        for (AppAdapter.Entry e : adapter.items()) savedOrder.add(e.key);
        moveStart = pos;
        moving = true;
        showMoveHint(true);
        handler.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
            if (vh != null) ((AppAdapter.Holder) vh).applyState(true, true, true);
        });
    }

    private void moveTo(int from, int to) {
        // The lifted tile keeps its view (and focus); RecyclerView slides the neighbours into place.
        adapter.move(from, to);
        resyncGrid();
        focusPos = to;
        lastPos = to;
        scrollToRow(to / menuSpan());
        glide(to % menuSpan(), false);
        setDeep(to / menuSpan() > 1);
        sounds.tick();
    }

    private void endMove(boolean keep) {
        moving = false;
        showMoveHint(false);
        if (keep) {
            saveOrder();
            AppAdapter.Entry e = adapter.at(focusPos);
            lastKey = e != null ? e.key : null;
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(focusPos);
            if (vh != null) {
                // Drop: the tile settles back to its focused size with a soft knock, like setting it down.
                ((AppAdapter.Holder) vh).applyState(true, false, true);
            }
            sounds.knock();
        } else {
            Map<String, AppAdapter.Entry> byKey = new HashMap<>();
            for (AppAdapter.Entry e : adapter.items()) byKey.put(e.key, e);
            List<AppAdapter.Entry> back = new ArrayList<>();
            for (String k : savedOrder) if (byKey.containsKey(k)) back.add(byKey.get(k));
            adapter.setItems(back);
            resyncGrid();
            final int p = moveStart;
            grid.post(() -> focusTile(p, false));
        }
        savedOrder = null;
    }

    private void showMoveHint(boolean on) {
        TextView t = (TextView) hint.getChildAt(0);
        if (on) {
            handler.removeCallbacksAndMessages(HINT_TOKEN);
            t.setText(R.string.hint_move);
            hint.setVisibility(View.VISIBLE);
            hint.animate().alpha(1f).setDuration(200).start();
        } else {
            hint.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                hint.setVisibility(View.GONE);
                t.setText(R.string.hint_hold);
            }).start();
        }
    }

    private void hideApp(AppAdapter.Entry e) {
        Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
        hidden.add(e.key);
        prefs.edit().putStringSet("hidden", hidden).apply();
        int pos = adapter.positionOf(e.key);
        if (pos >= 0) adapter.remove(pos);
        resyncGrid();
        final int p = Math.min(Math.max(0, pos), adapter.getItemCount() - 1);
        grid.post(() -> focusTile(p, false));
    }

    // ---- hint, keys, helpers ---------------------------------------------------------------------

    /** Tell people once that holding OK does more. */
    private void maybeShowHint() {
        if (prefs.getBoolean("hinted", false) || hint.getVisibility() == View.VISIBLE) return;
        prefs.edit().putBoolean("hinted", true).apply();
        handler.postDelayed(() -> {
            hint.setVisibility(View.VISIBLE);
            hint.animate().alpha(1f).setDuration(300).start();
            handler.postDelayed(this::hideHint, 5000);
        }, 1200);
    }

    private void hideHint() {
        if (moving || hint.getVisibility() != View.VISIBLE) return;
        hint.animate().alpha(0f).setDuration(300).withEndAction(() -> hint.setVisibility(View.GONE)).start();
    }

    /**
     * Safety net: if the focused tile ever scrolls away and is recycled before the next one arrives,
     * nothing on screen holds focus and the remote would seem dead. Put focus back on the first press.
     */
    /** True when the currently focused view is inside the picker overlay. */
    private boolean focusInsidePicker() {
        View v = getCurrentFocus();
        while (v != null) {
            if (v == modPicker) return true;
            android.view.ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        // MOD 1.2.3-navfix — MODAL picker guard. This is the fix for the
        // 1.2.2 MP4 test, where picker keys reached the home grid and a
        // CENTER launched a browser tile: previously there was NO
        // activity-level interception while the picker was visible, so
        // keys went to the focused picker row, whose listener returned
        // false for DPAD/UP events, and Android's window-global focus
        // search plus the default CENTER key-up click did the rest.
        // Now, while the picker is visible, EVERY key is routed to the
        // picker by index and consumed here — tiles, pills and panel
        // rows never see a picker key, in either action, from any
        // sender (ADB keyevents or the user's own remote alike). If
        // focus somehow stopped being a picker row, the key is consumed
        // while focus is put back, never acted on by the home menu.
        if (modPicker != null && modPicker.getVisibility() == View.VISIBLE) {
            if (!focusInsidePicker()) {
                modPicker.reassertFocus();
                return true;
            }
            return modPicker.handleKey(code, e);
        }
        // MOD 1.2.3-navfix: swallow the ACTION_UP of a CENTER that closed
        // the picker on its ACTION_DOWN (see onPickerClosedAfterPick).
        // Without this, that UP clicks the Settings panel row that focus
        // just landed on and would reopen the picker.
        if (suppressCenterUp && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                && e.getAction() == KeyEvent.ACTION_UP) {
            suppressCenterUp = false;
            return true;
        }
        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) {
            // The remote's microphone: open voice search when the key comes back up.
            if (e.getAction() == KeyEvent.ACTION_UP && !zoom.isBusy()) openVoice();
            return true;
        }
        if (getCurrentFocus() == null && isDpad(e.getKeyCode()) && e.getAction() == KeyEvent.ACTION_DOWN
                && !panelOpen && !zoom.isBusy() && adapter.getItemCount() > 0
                && (modPicker == null || modPicker.getVisibility() != View.VISIBLE)) {
            if (inBar) focusPill(barIndex, false);
            else if (pendingFocus >= 0) focusTile(pendingFocus, false);
            else restoreFocus();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onSearchRequested(android.view.SearchEvent event) {
        openVoice();
        return true;
    }

    private void openVoice() {
        if (panelOpen) closePanel();
        try {
            startActivity(new Intent(this, VoiceActivity.class));
            overridePendingTransition(0, 0);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** Back steps back exactly once: close the panel, cancel a move, leave the bar, return to row one. */
    @Override
    public void onBackPressed() {
        // MOD: Back inside the picker goes to the parent folder, or closes it
        // at the root; the picker itself restores grid focus on close.
        if (modPicker != null && modPicker.getVisibility() == View.VISIBLE) {
            modPicker.onBackPressed();
        } else if (panelOpen) {
            closePanel();
        } else if (moving) {
            endMove(false);
        } else if (inBar) {
            focusTile(Math.min(lastPos, menuSpan() - 1), true);
        } else if (focusPos >= menuSpan()) {
            focusTile(focusPos % menuSpan(), true);
        }
        // On row one already: a home screen has nowhere to go back to.
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
