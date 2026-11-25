package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * A glass frame: crisp rim and a faint outer glow, nothing inside.
 * One instance sits on the focused tile (and the app library's highlight); it is pinned in place, never animated.
 */
public class GlassView extends View {

    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Glow colour (0xRRGGBB) and its strength; white and faint, so the slab reads as glass, not paint. */
    private int glowRgb = 0xFFFFFF;
    private int glowAlpha = 0x30;
    /** glass_style rim or halo: the rim drawn heavier (and, for halo, a lighter outer glow). */
    private boolean flat;
    private boolean halo;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        rim.setColor(0xCCFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        // Cache the frame as a texture. The spring only changes translation, so the GPU can
        // move it without replaying the rim and the glow on every frame. That path
        // is software-rendered on the MT5895 and is what makes the highlight crawl.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** One of Config's GLASS_* styles. */
    void setStyle(String style) {
        flat = Config.GLASS_RIM.equals(style) || Config.GLASS_HALO.equals(style);
        halo = Config.GLASS_HALO.equals(style);
        rim.setStrokeWidth((flat ? 2f : 1.5f) * density);
        invalidate();
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    /** The glow takes the focused app's colour while a festival skin runs; 0 returns it to plain white. */
    void setAccent(int rgb) {
        int c = rgb == 0 ? 0xFFFFFF : (rgb & 0xFFFFFF);
        int a = rgb == 0 ? 0x30 : 0x44;   // a colour needs more body than white light does
        if (c == glowRgb && a == glowAlpha) return;
        glowRgb = c;
        glowAlpha = a;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the glow
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // Outer glow: thin rings across the inset, each a little fainter, so it fades out smoothly. Three wide
        // strokes left visible bands once the glow took an app's colour at a stronger alpha. The hardware layer
        // caches the result, so the rings cost one draw, not one per frame of the spring.
        if (!flat || halo) {
            int rings = 12;
            float step = inset / rings;
            int alpha = flat ? glowAlpha / 2 : glowAlpha;
            glow.setStrokeWidth(step + 0.5f);
            for (int i = 0; i < rings; i++) {
                float o = step * (i + 0.5f);
                float t = 1f - (float) i / rings;
                glow.setColor(Math.round(alpha * t) << 24 | glowRgb);
                canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow);
            }
        }
        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
