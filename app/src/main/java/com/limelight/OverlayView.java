package com.limelight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Оверлей-маркеры для «Лунного Ассистента Игр».
 * Рисует стрелку, пульсирующее кольцо или хайлайт-рамку поверх SurfaceView.
 * Не фокусируемый, не кликабельный: тач проходит в игру насквозь.
 * Гарантия: любые ошибки рисования не могут обрушить стрим (try/catch снаружи).
 */
public class OverlayView extends View {
    public static final int TYPE_ARROW = 1;
    public static final int TYPE_MARKER = 2;
    public static final int TYPE_HIGHLIGHT = 3;

    private static final long LIVE_MS = 10000;  // сколько висит маркер (по ТЗ 40)
    private static final long FADE_MS = 800;    // плавное затухание
    private static final float DIM_ALPHA = 0.55f; // затемнение фона при хайлайте

    private int type = 0;
    private String dir = "center";
    private float xPct = 50f, yPct = 50f;
    private float x1Pct = 0f, y1Pct = 0f, x2Pct = 0f, y2Pct = 0f;
    private long shownAt = 0;

    private final Paint stroke;
    private final Paint fill;
    private final Paint dim;
    private final Path path = new Path();
    private final RectF rect = new RectF();

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
        dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        dim.setColor(Color.BLACK);
        dim.setStyle(Paint.Style.FILL);
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

    /**
     * Хайлайт: затемнение + скруглённая рамка вокруг области.
     * Координаты в процентах 0..100 (x1<x2, y1<y2).
     */
    public void showHighlight(float x1, float y1, float x2, float y2) {
        type = TYPE_HIGHLIGHT;
        x1Pct = clamp(Math.min(x1, x2), 0f, 100f);
        y1Pct = clamp(Math.min(y1, y2), 0f, 100f);
        x2Pct = clamp(Math.max(x1, x2), 0f, 100f);
        y2Pct = clamp(Math.max(y1, y2), 0f, 100f);
        show();
    }

    /** Убрать маркер немедленно (алиас для FrameCapture.hideOverlay). */
    public void clearMarker() {
        type = 0;
        invalidate();
    }

    /** Есть ли активный маркер (без спама при пустом poll). */
    public boolean hasMarker() {
        return type != 0;
    }

    /** Убрать маркер немедленно (старое имя). */
    public void clear() {
        clearMarker();
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

        if (type == TYPE_HIGHLIGHT) {
            float rx1 = x1Pct / 100f * w;
            float ry1 = y1Pct / 100f * h;
            float rx2 = x2Pct / 100f * w;
            float ry2 = y2Pct / 100f * h;
            rect.set(rx1, ry1, rx2, ry2);
            float radius = dp(12);

            // затемнение всего фона (сквозь вырезается область)
            dim.setAlpha((int) (DIM_ALPHA * 255 * alpha));
            path.reset();
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addRect(rect.left, rect.top, rect.right, rect.bottom, Path.Direction.CCW);
            canvas.drawPath(path, dim);

            // скруглённая рамка
            float rr = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
            rect.set(rect.left - dp(4), rect.top - dp(4),
                    rect.right + dp(4), rect.bottom + dp(4));
            stroke.setAlpha(a);
            stroke.setStrokeWidth(dp(4));
            canvas.drawRoundRect(rect, rr, rr, stroke);
            stroke.setStrokeWidth(dp(5));
        } else if (type == TYPE_MARKER) {
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
