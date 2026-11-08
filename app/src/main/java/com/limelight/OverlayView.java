package com.limelight;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View {
    public static final int TYPE_ARROW = 1;
    public static final int TYPE_MARKER = 2;
    public static final int TYPE_HIGHLIGHT = 3;

    private static final long LIVE_MS = 5000;   // сколько висит маркер
    private static final long FADE_MS = 800;    // плавное затухание
    private static final float DIM_ALPHA = 0.55f; // затемнение фона при хайлайте

    // Готовый ответ показываем дольше маркера: его надо успеть прочитать.
    private static final long ANSWER_LIVE_MS = 12000;
    private static final long ANSWER_FADE_MS = 1000;
    private static final int ANSWER_MAX_LINES = 9;

    private int type = 0;
    private String dir = "center";
    private float xPct = 50f, yPct = 50f;
    private float x1Pct = 0f, y1Pct = 0f, x2Pct = 0f, y2Pct = 0f;
    private long shownAt = 0;

    private String answerText = null;
    private long answerShownAt = 0;
    private float answerWidth = 0f;

    private final Paint stroke;
    private final Paint scan;
    private final Paint band;
    private final Paint fill;
    private final Paint glow;
    private final Paint dim;
    private final Paint text;
    private final Paint cardBg;
    private final Paint cardStroke;
    private final Paint cardLabel;
    private final Paint answer;
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final RectF card = new RectF();
    private final List<String> answerLines = new ArrayList<>();

    private float scanY = 0f;
    private boolean scanDown = true;

    public OverlayView(Context c) {
        super(c);
        setWillNotDraw(false);

        stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(0xFF9D4EDD);  // неоновый пурпур
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(3));
        stroke.setShadowLayer(dp(4), 0, 0, 0xB3000000);

        scan = new Paint(Paint.ANTI_ALIAS_FLAG);
        scan.setColor(0xFF00CED1);  // неоновый синий
        scan.setStrokeWidth(dp(1));
        scan.setShadowLayer(dp(3), 0, 0, 0x6600CED1);

        band = new Paint(Paint.ANTI_ALIAS_FLAG);
        band.setColor(0xFFFF00FF);  // неоновый пурпур
        band.setStrokeWidth(dp(4));
        band.setShadowLayer(dp(6), 0, 0, 0x99FF00FF);

        fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFFFF00FF);  // неоновый пурпур
        fill.setStyle(Paint.Style.FILL);
        fill.setShadowLayer(dp(6), 0, 0, 0x9900CED1);

        glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(0xFF9D4EDD);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(dp(2));
        glow.setShadowLayer(dp(12), 0, 0, 0x779D4EDD);

        dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        dim.setColor(0x88000000);

        text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(dp(15));
        text.setShadowLayer(dp(2), 0, 0, 0xFF000000);

        // Карточка готового ответа: тёмная плашка с неоновой рамкой.
        cardBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBg.setColor(0xE60C0A14);
        cardBg.setStyle(Paint.Style.FILL);

        cardStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardStroke.setColor(0xFF9D4EDD);
        cardStroke.setStyle(Paint.Style.STROKE);
        cardStroke.setStrokeWidth(dp(2));
        cardStroke.setShadowLayer(dp(10), 0, 0, 0x669D4EDD);

        cardLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardLabel.setColor(0xFF00CED1);
        cardLabel.setTextSize(dp(10));
        cardLabel.setFakeBoldText(true);
        cardLabel.setShadowLayer(dp(6), 0, 0, 0x6600CED1);

        answer = new Paint(Paint.ANTI_ALIAS_FLAG);
        answer.setColor(Color.WHITE);
        answer.setTextSize(dp(15));

        setWillNotDraw(false);
    }

    public void showHighlight(float x1, float y1, float x2, float y2) {
        type = TYPE_HIGHLIGHT;
        x1Pct = clamp(Math.min(x1, x2), 0f, 100f);
        y1Pct = clamp(Math.min(y1, y2), 0f, 100f);
        x2Pct = clamp(Math.max(x1, x2), 0f, 100f);
        y2Pct = clamp(Math.max(y1, y2), 0f, 100f);
        show();
    }

    public void showMarker(float x, float y) {
        type = TYPE_MARKER;
        xPct = clamp(x, 0f, 100f);
        yPct = clamp(y, 0f, 100f);
        show();
    }

    public void showArrow(String direction) {
        type = TYPE_ARROW;
        dir = (direction == null || direction.isEmpty()) ? "center" : direction;
        show();
    }

    /**
     * Показывает готовый ответ Лунного Ассистента поверх игры.
     * Живёт 12 секунд и не мешает маркерам: они рисуются отдельно.
     */
    public void showAnswer(String value) {
        String t = value == null ? "" : value.trim();
        if (t.isEmpty()) return;
        answerText = t.length() > 600 ? t.substring(0, 600) + "…" : t;
        answerShownAt = System.currentTimeMillis();
        answerWidth = 0f; // пересчитать перенос под текущую ширину
        invalidate();
    }

    public void clearAnswer() {
        answerText = null;
        answerShownAt = 0;
        invalidate();
    }

    public boolean hasAnswer() {
        return answerText != null;
    }

    private void clear() {
        type = 0;
        invalidate();
    }

    public void clearMarker() {
        type = 0;
        invalidate();
    }

    private void show() {
        shownAt = System.currentTimeMillis();
        invalidate();
    }

    public boolean hasMarker() {
        return type != 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean markerAlive = drawMarker(canvas);
        boolean answerAlive = drawAnswerCard(canvas);

        if (markerAlive) {
            postInvalidateDelayed(50);
        } else if (answerAlive) {
            // Карточке хватает 10 кадров в секунду — не грузим GPU во время стрима.
            postInvalidateDelayed(100);
        }
    }

    private boolean drawMarker(Canvas canvas) {
        if (type == 0 || shownAt == 0) return false;
        long age = System.currentTimeMillis() - shownAt;
        if (age >= LIVE_MS) {
            type = 0;
            return false;
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
            float edge = dp(4);
            float rr = dp(12);

            dim.setAlpha((int) (DIM_ALPHA * 255 * alpha));
            path.reset();
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addRect(rx1, ry1, rx2, ry2, Path.Direction.CCW);
            canvas.drawPath(path, dim);
            stroke.setAlpha(a);
            stroke.setStrokeWidth(dp(4));
            canvas.drawRoundRect(rx1 - dp(4), ry1 - dp(4), rx2 + dp(4), ry2 + dp(4), rr, rr, stroke);
            stroke.setStrokeWidth(dp(5));
            canvas.drawRoundRect(rx1 - dp(4), ry1 - dp(4), rx2 + dp(4), ry2 + dp(4), rr, rr, stroke);
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

        return true;
    }

    private boolean drawAnswerCard(Canvas canvas) {
        if (answerText == null || answerShownAt == 0) return false;

        long age = System.currentTimeMillis() - answerShownAt;
        if (age >= ANSWER_LIVE_MS) {
            answerText = null;
            answerShownAt = 0;
            return false;
        }

        float alpha = 1f;
        long fadeStart = ANSWER_LIVE_MS - ANSWER_FADE_MS;
        if (age > fadeStart) {
            alpha = 1f - (age - fadeStart) / (float) ANSWER_FADE_MS;
        }

        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return false;

        float margin = dp(12);
        float pad = dp(14);
        float maxWidth = w - margin * 2;

        if (Math.abs(maxWidth - answerWidth) > 1f) {
            answerWidth = maxWidth;
            wrapAnswer(maxWidth - pad * 2);
        }

        float lineH = dp(21);
        float labelH = dp(16);
        float cardH = pad * 2 + labelH + dp(6) + answerLines.size() * lineH;
        float bottom = h - dp(28);
        float top = bottom - cardH;

        card.set(margin, top, margin + maxWidth, bottom);
        int a = (int) (255 * alpha);

        cardBg.setAlpha((int) (215 * alpha));
        canvas.drawRoundRect(card, dp(14), dp(14), cardBg);

        cardStroke.setAlpha(a);
        canvas.drawRoundRect(card, dp(14), dp(14), cardStroke);

        cardLabel.setAlpha((int) (200 * alpha));
        canvas.drawText("ЛУННЫЙ АССИСТЕНТ", margin + pad, top + pad + dp(10), cardLabel);

        answer.setAlpha(a);
        float y = top + pad + labelH + dp(6) + dp(15);
        for (int i = 0; i < answerLines.size(); i++) {
            canvas.drawText(answerLines.get(i), margin + pad, y, answer);
            y += lineH;
        }

        return true;
    }

    private void wrapAnswer(float maxWidth) {
        answerLines.clear();
        if (answerText == null || maxWidth <= 0) return;

        String[] words = answerText.replace('\n', ' ').split("\\s+");
        StringBuilder line = new StringBuilder();
        boolean cut = false;

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) continue;

            String candidate = line.length() == 0 ? word : line + " " + word;

            if (answer.measureText(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }

            if (line.length() > 0) {
                answerLines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                // Одно слово шире карточки — режем по символам.
                String rest = word;
                while (rest.length() > 1 && answer.measureText(rest) > maxWidth) {
                    int cutAt = rest.length() - 1;
                    while (cutAt > 1 && answer.measureText(rest.substring(0, cutAt)) > maxWidth) {
                        cutAt--;
                    }
                    answerLines.add(rest.substring(0, cutAt));
                    rest = rest.substring(cutAt);
                    if (answerLines.size() >= ANSWER_MAX_LINES) {
                        cut = true;
                        break;
                    }
                }
                if (cut) break;
                line.append(rest);
            }

            if (answerLines.size() >= ANSWER_MAX_LINES) {
                cut = true;
                break;
            }
        }

        if (!cut && line.length() > 0) {
            answerLines.add(line.toString());
        }

        if (cut) {
            if (answerLines.isEmpty()) {
                answerLines.add("…");
            } else {
                int last = answerLines.size() - 1;
                answerLines.set(last, answerLines.get(last) + "…");
            }
        }
    }

    private float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private float dp(int v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
