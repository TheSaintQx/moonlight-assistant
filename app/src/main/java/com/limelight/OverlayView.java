public class OverlayView extends View {
    public static final int TYPE_ARROW = 1;
    public static final int TYPE_MARKER = 2;
    public static final int TYPE_HIGHLIGHT = 3;

    private static final long LIVE_MS = 5000;   // сколько висит маркер
    private static final long FADE_MS = 800;    // плавное затухание
    private static final float DIM_ALPHA = 0.55f; // затемнение фона при хайлайте

    private int type = 0;
    private String dir = "center";
    private float xPct = 50f, yPct = 50f;
    private float x1Pct = 0f, y1Pct = 0f, x2Pct = 0f, y2Pct = 0f;
    private long shownAt = 0;

    private final Paint stroke;
    private final Paint scan;
    private final Paint band;
    private final Paint fill;
    private final Paint glow;
    private final Paint dim;
    private final Paint text;
    private final Path path = new Path();
    private final RectF rect = new RectF();

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

    private void clear() {
        type = 0;
        invalidate();
    }

    private void clearMarker() {
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
            float edge = dp(4);
            float rr = dp(12);

            dim.setAlpha((int) (DIM_ALPHA * 255 * alpha));
            path.reset();
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addRect(rx1, ry1, rx2, ry2, Path.Direction.CCW);
            canvas.drawPath(path, dim);
            stroke.setAlpha(a);
            stroke.setStrokeWidth(dp(4));
            canvas.drawRoundRect(rx1 - dp(4), ry1 - dp(4), rx2 + dp(4), ry2 + dp(4), rr, rr);
            stroke.setStrokeWidth(dp(5));
            canvas.drawRoundRect(rx1 - dp(4), ry1 - dp(4), rx2 + dp(4), ry2 + dp(4), rr, rr);
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

        postInvalidateDelayed(50);
    }

    private float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private float dp(int v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
