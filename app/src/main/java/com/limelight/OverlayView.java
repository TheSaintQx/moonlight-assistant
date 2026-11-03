package com.limelight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Оверлей-маркеры для «Лунного Ассистента Игр».
 * Рисует стрелку направления или пульсирующее кольцо поверх SurfaceView.
 * Не фокусируемый, не кликабельный: тач проходит в игру насквозь.
 * Гарантия: любые ошибки рисования не могут обрушить стрим (try/catch снаружи).
 */
public class OverlayView extends View {
    public static final int TYPE_ARROW = 1;
    public static final int TYPE_MARKER = 2;

    private static final long LIVE_MS = 6000;   // сколько висит маркер
    private static final long FADE_MS = 800;    // плавное затухание

    private int type = 0;
    private String dir = "center";
    private float xPct = 50f, yPct = 50f;
    private long shownAt = 0;

    private final Paint stroke;
    private final Paint fill;
    private final Path path = new Path();

    public OverlayView(Context c) {
        super(c);
        stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(5));
        stroke.setShadowLayer(dp(4), 0, 0, 0xB3000000);
        fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
    }

    /** Стрелка у края экрана, остриём к краю: left/right/up/down. */
    public void showArrow(String direction) {
        type = TYPE_ARROW;
        dir = (direction == null || direction.isEmpty()) ? "down" : direction;
        show();
    }

    /** Пульсирующее кольцо в точке кадра, x/y в процентах 0..100. */
    public void showMarker(float x, float y) {
        type = TYPE_MARKER;
        xPct = clamp(x, 0f, 100f);
        yPct = clamp(y, 0f, 100f);
        show();
    }

    /** Убрать маркер немедленно. */
    public void clear() {
        type = 0;
        invalidate();
    }

    private void show() {
        shownAt = System.currentTimeMillis();
        invalidate();
    }

    private float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private float dp(int v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (type == 0 || shownAt == 0) return;
        long age = System.currentTimeMillis() - shownAt;
        if (age >= LIVE_MS) {
            type = 0;
            return;
        }
        float alpha = 1f;
        long fadeStart = LIVE_MS - FADE_MS;
        if (age > fadeStart) {
            alpha = 1f - (age - fadeStart) / (float) FADE_MS;
        }
        int a = (int) (255 * alpha);

        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) * 0.055f;

        if (type == TYPE_MARKER) {
            float mx = xPct / 100f * w;
            float my = yPct / 100f * h;
            float pulse = 1f + 0.15f * (float) Math.sin(age / 180.0);
            fill.setAlpha((int) (90 * alpha));
            stroke.setAlpha(a);
            canvas.drawCircle(mx, my, r * pulse, fill);
            canvas.drawCircle(mx, my, r * pulse, stroke);
            fill.setAlpha(a);
            canvas.drawCircle(mx, my, dp(4), fill);
        } else {
            float edge = dp(30), len = dp(40), half = dp(17);
            float tipX, tipY, tailX, tailY;
            if ("left".equals(dir)) {
                tipX = edge; tipY = cy; tailX = edge + len; tailY = cy;
            } else if ("right".equals(dir)) {
                tipX = w - edge; tipY = cy; tailX = w - edge - len; tailY = cy;
            } else if ("up".equals(dir)) {
                tipX = cx; tipY = edge; tailX = cx; tailY = edge + len;
            } else {
                tipX = cx; tipY = h - edge; tailX = cx; tailY = h - edge - len;
            }
            double ang = Math.atan2(tipY - tailY, tipX - tailX);
            float bx = tipX - (float) (len * 0.5 * Math.cos(ang));
            float by = tipY - (float) (len * 0.5 * Math.sin(ang));
            path.reset();
            path.moveTo(tipX, tipY);
            path.lineTo(tipX - (float) (half * Math.cos(ang - 0.55)),
                        tipY - (float) (half * Math.sin(ang - 0.55)));
            path.lineTo(tipX - (float) (half * Math.cos(ang + 0.55)),
                        tipY - (float) (half * Math.sin(ang + 0.55)));
            path.close();
            fill.setAlpha((int) (120 * alpha));
            stroke.setAlpha(a);
            canvas.drawLine(tailX, tailY, bx, by, stroke);
            canvas.drawPath(path, fill);
            canvas.drawPath(path, stroke);
        }
        // тик анимации (пульс + затухание)
        postInvalidateDelayed(50);
    }
}