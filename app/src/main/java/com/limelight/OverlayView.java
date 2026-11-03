package com.limelight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.View;

/**
 * Оверлей «Лунного Ассистента Игр», v2.
 * Основной режим — ПОДСВЕТКА (TYPE_HIGHLIGHT): экран притемняется тёмно-
 * фиолетовым, а объект остаётся ярким внутри неоновой рамки со свечением.
 * Стрелка и точка сохранены как запасные режимы.
 * Не фокусируемый, не кликабельный: тач проходит в игру насквозь.
 * Гарантия: любые ошибки рисования не могут обрушить стрим (try/catch снаружи).
 */
public class OverlayView extends View {
    public static final int TYPE_ARROW = 1;
    public static final int TYPE_MARKER = 2;
    public static final int TYPE_HIGHLIGHT = 3;

    private static final long LIVE_MS = 6000;       // стрелка/точка
    private static final long LIVE_HL_MS = 9000;    // подсветка живёт дольше
    private static final long FADE_MS = 800;

    private int type = 0;
    private String dir = "center";
    private float xPct = 50f, yPct = 50f;
    private float x1 = 20f, y1 = 20f, x2 = 80f, y2 = 80f;
    private long shownAt = 0;

    private final Paint stroke;
    private final Paint fill;
    private final Paint dim;
    private final Paint punch;
    private final Paint glow;
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
        // Тёмно-фиолетовое притемнение всего кадра (как на референсах)
        dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        dim.setColor(0xE60D0726);
        // «Прожигатель»: вырезает окно в затемнении, кадр игры просвечивает
        punch = new Paint(Paint.ANTI_ALIAS_FLAG);
        punch.setStyle(Paint.Style.FILL);
        punch.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        // Неоновая рамка со свечением
        glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(0xFFC24BFF);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(dp(4));
        glow.setShadowLayer(dp(14), 0, 0, 0xFFB44BFF);
        glow.setStrokeCap(Paint.Cap.ROUND);
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

    /** Подсветка объекта: прямоугольник в процентах кадра (x1,y1)-(x2,y2). */
    public void showHighlight(float a, float b, float c, float d) {
        type = TYPE_HIGHLIGHT;
        // нормализация: модель могла перепутать порядок углов
        float lx = clamp(Math.min(a, c), 0f, 100f);
        float rx = clamp(Math.max(a, c), 0f, 100f);
        float ty = clamp(Math.min(b, d), 0f, 100f);
        float by = clamp(Math.max(b, d), 0f, 100f);
        x1 = lx; y1 = ty; x2 = rx; y2 = by;
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
        long live = (type == TYPE_HIGHLIGHT) ? LIVE_HL_MS : LIVE_MS;
        long age = System.currentTimeMillis() - shownAt;
        if (age >= live) {
            type = 0;
            return;
        }
        float alpha = 1f;
        long fadeStart = live - FADE_MS;
        if (age > fadeStart) {
            alpha = 1f - (age - fadeStart) / (float) FADE_MS;
        }
        int a = (int) (255 * alpha);

        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) * 0.055f;

        if (type == TYPE_HIGHLIGHT) {
            float l = x1 / 100f * w;
            float t = y1 / 100f * h;
            float ri = x2 / 100f * w;
            float bo = y2 / 100f * h;
            rect.set(l - dp(8), t - dp(8), ri + dp(8), bo + dp(8));
            float radius = dp(22);
            // слой, внутри которого «прожигаем» окно вокруг объекта
            canvas.saveLayer(0, 0, w, h, null);
            dim.setAlpha((int) (230 * alpha));
            canvas.drawRect(0, 0, w, h, dim);
            path.reset();
            path.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.drawPath(path, punch);
            canvas.restore();
            glow.setAlpha(a);
            canvas.drawRoundRect(rect, radius, radius, glow);
            postInvalidateDelayed(50);
            return;
        }

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
        postInvalidateDelayed(50);
    }
}