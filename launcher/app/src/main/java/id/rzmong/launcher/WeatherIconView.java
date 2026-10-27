package id.rzmong.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * MOD (OwnTV fork): tiny weather glyph drawn in code (no emoji, no asset).
 * WMO weather code mapping (same codes as MainActivity.conditionFor):
 * 0 = clear sun, 1-2 = sun behind cloud, 3 = overcast cloud, 45/48 = fog
 * cloud with lines, 51-67 = rain cloud with drops, 71-77 = snow cloud
 * with flakes, 80-82 = rain cloud with drops, 95-99 = storm cloud with a
 * lightning bolt. Anything else = plain cloud.
 */
final class WeatherIconView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int code = 3;

    public WeatherIconView(Context c) {
        super(c);
    }

    public WeatherIconView(Context c, AttributeSet a) {
        super(c, a);
    }

    void setCode(int code) {
        this.code = code;
        postInvalidate();
    }

    private void cloud(Canvas c, float cx, float cy, float r, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx - r * 0.7f, cy + r * 0.15f, r * 0.62f, paint);
        c.drawCircle(cx, cy - r * 0.25f, r * 0.85f, paint);
        c.drawCircle(cx + r * 0.7f, cy + r * 0.15f, r * 0.62f, paint);
        c.drawRect(cx - r * 1.3f, cy - r * 0.1f, cx + r * 1.3f, cy + r * 0.75f, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) * 0.26f;
        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean snow = code >= 71 && code <= 77;
        boolean storm = code >= 95;
        if (code == 0) {
            // Sun.
            paint.setColor(0xFFFFD54F);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStrokeWidth(4f);
            paint.setColor(0xFFFFD54F);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                canvas.drawLine(cx + (float) Math.cos(a) * r * 1.5f, cy + (float) Math.sin(a) * r * 1.5f,
                        cx + (float) Math.cos(a) * r * 2f, cy + (float) Math.sin(a) * r * 2f, paint);
            }
        } else if (code == 1 || code == 2) {
            // Sun behind a cloud.
            paint.setColor(0xFFFFD54F);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx - r * 0.7f, cy - r * 0.6f, r * 0.8f, paint);
            cloud(canvas, cx + r * 0.3f, cy + r * 0.3f, r, 0xFFFFFFFF);
        } else {
            cloud(canvas, cx, cy, r, 0xFFFFFFFF);
        }
        if (rain || snow) {
            paint.setColor(rain ? 0xFF4FC3F7 : 0xFFFFFFFF);
            paint.setStrokeWidth(4f);
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * r * 0.7f;
                if (rain) canvas.drawLine(x, cy + r * 0.9f, x - r * 0.2f, cy + r * 1.5f, paint);
                else canvas.drawCircle(x, cy + r * 1.2f, 4f, paint);
            }
        }
        if (storm) {
            // Lightning bolt below the cloud.
            paint.setColor(0xFFFFEB3B);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(0f);
            android.graphics.Path bolt = new android.graphics.Path();
            bolt.moveTo(cx + r * 0.2f, cy + r * 0.8f);
            bolt.lineTo(cx - r * 0.3f, cy + r * 1.6f);
            bolt.lineTo(cx + r * 0.05f, cy + r * 1.6f);
            bolt.lineTo(cx - r * 0.2f, cy + r * 2.3f);
            bolt.lineTo(cx + r * 0.4f, cy + r * 1.4f);
            bolt.lineTo(cx + r * 0.05f, cy + r * 1.4f);
            bolt.close();
            canvas.drawPath(bolt, paint);
        }
        if (code == 45 || code == 48) {
            paint.setColor(0xFFE0E0E0);
            paint.setStrokeWidth(4f);
            for (int i = 0; i < 3; i++) {
                float y = cy + r * (0.9f + i * 0.35f);
                canvas.drawLine(cx - r * 1.1f, y, cx + r * 1.1f, y, paint);
            }
        }
    }
}
