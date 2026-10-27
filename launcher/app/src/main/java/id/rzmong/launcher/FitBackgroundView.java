package id.rzmong.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.io.File;

/**
 * MOD (OwnTV fork): draws a user-chosen JPG/PNG background FIT (contain) on a
 * 1920x1080 screen, never centre-cropped. A darkened, blurred cover fill from
 * the same image is drawn full-screen behind it, so there are no black bars
 * and the whole image (face, car, flowers) stays visible. When no image is
 * set, it draws nothing. Bitmaps are scaled once per image and recycled on
 * change, so a 5120x2880 photo does not stay at full size in memory.
 */
final class FitBackgroundView extends View {

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dim = new Paint();
    private Bitmap fit, cover;
    private int w, h;

    public FitBackgroundView(Context c) {
        super(c);
        init();
    }

    public FitBackgroundView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        dim.setColor(0x6E000000); // darken the cover fill so the fit image reads
    }

    /** Loads and scales {@code path}, or clears the view when {@code path} is null. */
    void setImagePath(String path) {
        clearBitmaps();
        if (path == null) {
            postInvalidate();
            return;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
            // Downsample huge photos (e.g. 5120x2880) to at most ~2x screen size.
            int sample = 1;
            while (bounds.outWidth / sample > 3840 || bounds.outHeight / sample > 2160) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap src = BitmapFactory.decodeFile(path, o);
            if (src == null) return;
            if (w > 0 && h > 0) scaleBitmaps(src);
            else {
                // View not laid out yet: keep the source until onSizeChanged scales it.
                fit = src;
            }
        } catch (Exception ignored) {
            clearBitmaps();
        }
        postInvalidate();
    }

    private void scaleBitmaps(Bitmap src) {
        float fitScale = Math.min((float) w / src.getWidth(), (float) h / src.getHeight());
        int fw = Math.max(1, Math.round(src.getWidth() * fitScale));
        int fh = Math.max(1, Math.round(src.getHeight() * fitScale));
        fit = Bitmap.createScaledBitmap(src, fw, fh, true);
        float coverScale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        int cw = Math.round(src.getWidth() * coverScale);
        int ch = Math.round(src.getHeight() * coverScale);
        Bitmap scaled = Bitmap.createScaledBitmap(src, cw, ch, true);
        cover = Bitmap.createBitmap(scaled, (cw - w) / 2, (ch - h) / 2, w, h);
        if (scaled != cover) scaled.recycle();
        if (src != fit) src.recycle();
    }

    private void clearBitmaps() {
        if (fit != null) {
            fit.recycle();
            fit = null;
        }
        if (cover != null) {
            cover.recycle();
            cover = null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        this.w = w;
        this.h = h;
        // If a source was loaded before layout, scale it now.
        if (fit != null && cover == null && w > 0 && h > 0) {
            Bitmap src = fit;
            fit = null;
            scaleBitmaps(src);
            postInvalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (w == 0 || h == 0) return;
        if (cover != null) {
            canvas.drawBitmap(cover, 0, 0, paint);
            canvas.drawRect(0, 0, w, h, dim);
        }
        if (fit != null) {
            canvas.drawBitmap(fit, (w - fit.getWidth()) / 2f, (h - fit.getHeight()) / 2f, paint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        clearBitmaps();
        super.onDetachedFromWindow();
    }

    static boolean isImageFile(String name) {
        String l = name.toLowerCase(java.util.Locale.ROOT);
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png");
    }

    static boolean isVideoFile(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4");
    }
}
