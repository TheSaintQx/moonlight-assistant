package com.limelight;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;
import android.util.Base64;
import android.util.Log;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FrameCapture {
    private static final String TAG = "FrameCapture";
    private static final String FLAG_FILE = "capture_interval.txt";
    private static final String PROP_NAME = "debug.moonlight.capture";
    private static final String CLOUD_ENDPOINT =
            "https://functions.yandexcloud.net/d4el6hpg7nh0cui52kd2";
    private static final String CLOUD_TOKEN = "moon-7f3k9p2xq8v4m1w6r5t0zc4";
    private static final long CACHE_TTL_MS = 600_000L;

    private static final int CAPTURE_SIZE = 512;
    private static final int MAX_FILES = 20;
    private static final int JPEG_QUALITY = 85;
    private static final long COPY_TIMEOUT_MS = 2000;
    private static final long NETWORK_TIMEOUT_MS = 5000;
    private static final String CAPTURE_SUBDIR = "capture";
    private static final String BURST_SUBDIR = "burst";
    private static final int BURST_MAX = 15;
    private static final long BURST_INTERVAL_MS = 3000;
    private static final long POLL_INTERVAL_MS = 1500;

    private final SurfaceView streamView;
    private final android.content.Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Handler captureHandler;
    private Handler netHandler;
    private HandlerThread captureThread;
    private HandlerThread netThread;
    private int intervalS = 0;
    private long netCacheAt = 0;
    private long lastBgCaptureAt = 0;
    private long lastBurstCaptureAt = 0;
    private int lastSentQid = 0;
    private long lastNonQueryAt = 0;
    private long lastPollDiagAt = 0;
    // Последний ответ, за который мы уже поблагодарили сервер (ack).
    private volatile int lastAckedQid = 0;

    private volatile OverlayView overlay;
    private volatile Runnable hideOverlayRunnable;

    public FrameCapture(Object owner, SurfaceView view) {
        this.streamView = view;
        this.context = (owner instanceof android.content.Context)
                ? (android.content.Context) owner : null;
    }

    // ---------- lifecycle ----------

    public void start() {
        if (running.getAndSet(true)) {
            scheduleCapture();
            return;
        }
        ensureThreads();
        intervalS = readIntervalSeconds();
        if (intervalS > 0) {
            Log.i(TAG, "capture enabled, interval=" + intervalS + "s, dir=" + captureDir());
            scheduleCapture();
            schedulePoll();
        } else {
            running.set(false);
            Log.i(TAG, "no flag file, prop not set - waiting for cloud config (async)");
            Log.i(TAG, "capture disabled for now - cloud config check in progress (async)");
            scheduleNetworkConfigCheck();
        }
    }

    public void stop() {
        running.set(false);
        if (captureHandler != null) captureHandler.removeCallbacksAndMessages(null);
        if (netHandler != null) netHandler.removeCallbacksAndMessages(null);
    }

    public void destroy() {
        stop();
        if (captureThread != null) captureThread.quitSafely();
        if (netThread != null) netThread.quitSafely();
        captureThread = null;
        netThread = null;
        captureHandler = null;
        netHandler = null;
        final Activity act = asActivity();
        final OverlayView o = overlay;
        overlay = null;
        if (act != null && o != null) {
            act.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        ViewGroup p = (ViewGroup) o.getParent();
                        if (p != null) p.removeView(o);
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    private Activity asActivity() {
        return (context instanceof Activity) ? (Activity) context : null;
    }

    private void ensureThreads() {
        if (captureThread == null) {
            captureThread = new HandlerThread("moon-capture");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());
        }
        if (netThread == null) {
            netThread = new HandlerThread("moon-capture-net");
            netThread.start();
            netHandler = new Handler(netThread.getLooper());
        }
    }

    // ---------- интервал: три приоритета ----------

    private int readIntervalSeconds() {
        Log.w(TAG, "DIAG: readIntervalSeconds() called");
        // приоритет 1: файл-флаг
        try {
            File f = new File(externalFilesDir(), FLAG_FILE);
            if (f.exists()) {
                String s = readText(f).trim();
                int v = Integer.parseInt(s);
                if (v > 0) {
                    Log.i(TAG, "flag file: interval=" + v + "s");
                    return v;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "flag read failed: " + e);
        }
        // приоритет 2: системный проп
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = c.getMethod("get", String.class);
            String v = (String) m.invoke(null, PROP_NAME);
            if (v != null && !v.trim().isEmpty()) {
                int n = Integer.parseInt(v.trim());
                if (n > 0) {
                    Log.i(TAG, "prop: interval=" + n + "s");
                    return n;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "prop read failed: " + t);
        }
        // приоритет 3: сеть (из кеша, без запроса здесь)
        if (intervalS > 0 && netCacheAt > 0
                && System.currentTimeMillis() - netCacheAt < CACHE_TTL_MS) {
            return intervalS;
        }
        return 0;
    }

    private void scheduleNetworkConfigCheck() {
        if (netHandler == null) return;
        netHandler.removeCallbacks(netConfigRunnable);
        netHandler.postDelayed(netConfigRunnable, 1000);
    }

    private final Runnable netConfigRunnable = new Runnable() {
        @Override public void run() {
            fetchIntervalFromNetwork();
        }
    };

    private void fetchIntervalFromNetwork() {
        Log.w(TAG, "DIAG: fetchIntervalFromNetwork() start");
        try {
            String url = CLOUD_ENDPOINT + "?token=" + CLOUD_TOKEN;
            Log.w(TAG, "DIAG: HTTP request to " + CLOUD_ENDPOINT + "?token=***");
            String[] r = httpCall("GET", url, null, (int) NETWORK_TIMEOUT_MS);
            Log.w(TAG, "DIAG: cloud config HTTP status: " + r[0]);
            Log.w(TAG, "DIAG: cloud config body: " + r[1]);
            if ("200".equals(r[0])) {
                JSONObject j = new JSONObject(r[1]);
                // Готовый ответ приходит и в конфиге: показываем его даже без захвата.
                handleReadyIfAny(j);
                int iv = j.optInt("interval", 0);
                if (iv > 0) {
                    intervalS = iv;
                    netCacheAt = System.currentTimeMillis();
                    Log.i(TAG, "cloud config OK: interval=" + iv + "s");
                    if (!running.get()) {
                        Log.i(TAG, "DIAG: async cloud config returned interval="
                                + iv + "s, enabling capture");
                        running.set(true);
                        scheduleCapture();
                        schedulePoll();
                    }
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cloud config failed: " + e);
        }
        if (!running.get()) scheduleNetworkConfigCheck();
    }

    // ---------- цикл захвата: фон + бурст из одного тика ----------

    private void scheduleCapture() {
        if (captureHandler == null) return;
        captureHandler.removeCallbacks(captureRunnable);
        captureHandler.postDelayed(captureRunnable, 1000);
    }

    private final Runnable captureRunnable = new Runnable() {
        @Override public void run() {
            if (!running.get()) return;
            long now = System.currentTimeMillis();
            boolean bgDue = lastBgCaptureAt == 0
                    || now - lastBgCaptureAt >= intervalS * 1000L;
            boolean burstDue = lastBurstCaptureAt == 0
                    || now - lastBurstCaptureAt >= BURST_INTERVAL_MS;
            if (bgDue) {
                File f = captureOnce(CAPTURE_SUBDIR);
                if (f != null) {
                    lastBgCaptureAt = now;
                    rotate(captureDir(), MAX_FILES);
                    postBackground(f);
                }
            }
            if (burstDue) {
                File f2 = captureOnce(BURST_SUBDIR);
                if (f2 != null) {
                    lastBurstCaptureAt = now;
                    rotate(burstDir(), BURST_MAX);
                }
            }
            scheduleCapture();
        }
    };

    private File captureOnce(String subdir) {
        if (Build.VERSION.SDK_INT < 26) {
            Log.w(TAG, "PixelCopy requires API 26+, capture skipped");
            return null;
        }
        if (streamView == null) return null;
        int w = CAPTURE_SIZE;
        int h = 144;
        try {
            int sw = streamView.getWidth();
            int sh = streamView.getHeight();
            if (sw > 0 && sh > 0) h = Math.max(1, w * sh / sw);
        } catch (Exception ignored) {
        }
        Bitmap dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (!pixelCopy(dst)) return null;
        try {
            File dir = new File(externalFilesDir(), subdir);
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "frame_" + System.currentTimeMillis() + ".jpg");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            dst.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bo);
            FileOutputStream fo = new FileOutputStream(out);
            fo.write(bo.toByteArray());
            fo.close();
            Log.i(TAG, "captured " + out.getName() + " " + w + "x" + h);
            return out;
        } catch (Exception e) {
            Log.w(TAG, "save failed: " + e);
            return null;
        }
    }

    private boolean pixelCopy(final Bitmap dst) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        // ВАЖНО: колбэк PixelCopy доставляется в ГЛАВНЫЙ looper (урок 27).
        // Передача captureHandler = дедлок: поток спит в latch.await и
        // никогда не обработает собственный колбэк.
        try {
            PixelCopy.request(streamView, dst, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int result) {
                    ok.set(result == PixelCopy.SUCCESS);
                    latch.countDown();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            Log.w(TAG, "pixelCopy request failed: " + e);
            return false;
        }
        try {
            if (!latch.await(COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "pixelCopy timeout");
                return false;
            }
        } catch (InterruptedException ie) {
            return false;
        }
        return ok.get();
    }

    // ---------- фон: авто-POST кадра в облако ----------

    private void postBackground(final File frame) {
        netHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    String b64 = fileToBase64(frame);
                    if (b64 == null) return;
                    String url = CLOUD_ENDPOINT + "?token=" + CLOUD_TOKEN;
                    String body = "{\"image\":\"" + b64 + "\"}";
                    String[] r = httpCall("POST", url, body, (int) NETWORK_TIMEOUT_MS);
                    Log.i(TAG, "DIAG: background POST status: " + r[0] + " body: "
                            + r[1].substring(0, Math.min(200, r[1].length())));
                } catch (Exception e) {
                    Log.w(TAG, "background POST failed: " + e);
                }
            }
        });
    }

    // ---------- опросник: есть вопрос? ----------

    private void schedulePoll() {
        if (netHandler == null) return;
        netHandler.removeCallbacks(pollRunnable);
        netHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            try {
                // БЕЗ кеша: каждый тик реальный GET (HTTP-хелпер чистый, без CACHE_TTL)
                String url = CLOUD_ENDPOINT + "?token=" + CLOUD_TOKEN + "&poll=1";
                String[] r = httpCall("GET", url, null, (int) NETWORK_TIMEOUT_MS);
                if ("200".equals(r[0])) {
                    JSONObject j = new JSONObject(r[1]);
                    String status = j.optString("status");

                    // ПЕРВИЧНЫЙ КАНАЛ: marker из poll-ответа -> сразу оверлей
                    JSONObject marker = j.optJSONObject("marker");
                    if (marker != null) {
                        Log.i(TAG, "DIAG: marker received " + marker.toString());
                        handleMarkerFromPoll(marker);
                    } else {
                        hideOverlay();
                    }

                    // ГОТОВЫЙ ОТВЕТ: сервер сам сообщает, когда ответ собран.
                    // Маркеры и ответ независимы: ответ живёт дольше и не гаснет
                    // вместе с маркером.
                    handleReadyIfAny(j);

                    // РЕЗЕРВНЫЙ КАНАЛ: burst по status:"query" (guard не трогать)
                    if ("query".equals(status)) {
                        lastNonQueryAt = 0;
                        int qid = j.optInt("qid", 0);
                        String q = j.optString("q", "");
                        if (qid > 0 && qid != lastSentQid) {
                            lastSentQid = qid;
                            Log.i(TAG, "DIAG: query received qid=" + qid + ", sending burst");
                            sendBurst(qid, q);
                        }
                    } else {
                        // сброс lastSentQid через 60 с без query (повторный показ)
                        long now = System.currentTimeMillis();
                        if (lastSentQid != 0 && lastNonQueryAt == 0) {
                            lastNonQueryAt = now;   // старт отсчёта без-query
                        }
                        if (lastSentQid != 0 && lastNonQueryAt != 0
                                && now - lastNonQueryAt > 60_000L) {
                            lastSentQid = 0;
                            lastNonQueryAt = 0;
                        }
                    }

                    // Диагностический лог не чаще 1 раза в 10 с
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastPollDiagAt >= 5_000L) {
                        lastPollDiagAt = nowMs;
                        Log.i(TAG, "DIAG: poll status=" + status
                                + " marker=" + (marker != null ? "yes" : "no"));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "poll failed: " + e);
            }
            schedulePoll();
        }
    };

    private void sendBurst(final int qid, final String q) {
        netHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    File dir = burstDir();
                    File[] files = dir.listFiles();
                    if (files == null || files.length < 2) {
                        Log.w(TAG, "burst: only " + (files == null ? 0 : files.length)
                                + " frames, skip");
                        return;
                    }
                    Arrays.sort(files, new Comparator<File>() {
                        @Override public int compare(File a, File b) {
                            return Long.compare(a.lastModified(), b.lastModified());
                        }
                    });
                    JSONArray arr = new JSONArray();
                    int n = Math.min(files.length, BURST_MAX);
                    for (int i = files.length - n; i < files.length; i++) {
                        String b64 = fileToBase64(files[i]);
                        if (b64 != null) arr.put(b64);
                    }
                    JSONObject req = new JSONObject();
                    req.put("qid", qid);
                    req.put("images", arr);
                    String url = CLOUD_ENDPOINT + "?token=" + CLOUD_TOKEN + "&debug=1";
                    String[] r = httpCall("POST", url, req.toString(), 25000);
                    Log.i(TAG, "DIAG: burst POST status: " + r[0] + " qid=" + qid
                            + " images=" + arr.length());
                    Log.i(TAG, "DIAG: burst answer: " + r[1].substring(0,
                            Math.min(400, r[1].length())));
                    if ("200".equals(r[0])) {
                        try {
                            JSONObject j = new JSONObject(r[1]);
                            JSONObject marker = j.optJSONObject("marker");
                            if (marker != null) {
                                Log.i(TAG, "DIAG: marker received " + marker.toString());
                                handleMarkerFromPoll(marker);
                            }
                        } catch (Exception me) {
                            Log.w(TAG, "marker parse failed: " + me);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "burst send failed: " + e);
                }
            }
        });
    }

    // ---------- оверлей: маркеры поверх стрима ----------

    private void handleMarkerFromPoll(final JSONObject marker) {
        final Activity act = asActivity();
        if (act == null) return;
        final String t = marker.optString("type", "");
        final String d = marker.optString("dir", "");
        final double x = marker.optDouble("x", -1);
        final double y = marker.optDouble("y", -1);
        final double x1 = marker.optDouble("x1", -1);
        final double y1 = marker.optDouble("y1", -1);
        final double x2 = marker.optDouble("x2", -1);
        final double y2 = marker.optDouble("y2", -1);

        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    ensureOverlay();
                    if (overlay == null) return;

                    if (hideOverlayRunnable != null) {
                        overlay.removeCallbacks(hideOverlayRunnable);
                    }
                    hideOverlayRunnable = new Runnable() {
                        @Override public void run() { hideOverlay(); }
                    };
                    overlay.postDelayed(hideOverlayRunnable, 5_000L);

                    if ("marker".equals(t)) {
                        if (x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0
                                && x2 > x1 && y2 > y1) {
                            overlay.showHighlight((float) x1, (float) y1,
                                    (float) x2, (float) y2);
                            Log.i(TAG, "DIAG: overlay drawn highlight rect="
                                    + (int) x1 + "," + (int) y1 + ","
                                    + (int) x2 + "," + (int) y2);
                        } else if (x >= 0 && y >= 0) {
                            overlay.showMarker((float) x, (float) y);
                            Log.i(TAG, "DIAG: overlay drawn point x=" + x + " y=" + y);
                        } else {
                            hideOverlay();
                        }
                    } else if ("arrow".equals(t)) {
                        if ("center".equals(d)) {
                            overlay.showMarker(50f, 50f);
                            Log.i(TAG, "DIAG: overlay drawn arrow-center");
                        } else {
                            overlay.showArrow(d);
                            Log.i(TAG, "DIAG: overlay drawn arrow-" + d);
                        }
                    } else {
                        // Неизвестный тип маркера — скрываем оверлей
                        hideOverlay();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "overlay failed: " + e);
                }
            }
        });
    }

    // ---------- готовый ответ: оверлей + подтверждение ----------

    private void handleReadyIfAny(JSONObject j) {
        if (j == null) return;

        JSONObject ready = j.optJSONObject("ready");

        if (ready == null) return;

        handleAnswerReady(ready);
    }

    private void handleAnswerReady(final JSONObject ready) {
        final int qid = ready.optInt("qid", 0);
        final String text = ready.optString("text", "");

        if (text.trim().isEmpty()) return;
        // Уже показали и подтвердили — повторно не всплываем.
        if (qid > 0 && qid == lastAckedQid) return;

        final Activity act = asActivity();

        if (act == null) return;

        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    ensureOverlay();
                    if (overlay == null) return;

                    overlay.showAnswer(text);
                    Log.i(TAG, "DIAG: answer shown qid=" + qid
                            + " len=" + text.length());

                    if (qid > 0) {
                        sendAck(qid);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "answer overlay failed: " + e);
                }
            }
        });
    }

    // Подтверждение показа: сервер перестаёт повторять это уведомление.
    // Если сеть подвела, уведомление придёт снова и оверлей просто обновится.
    private void sendAck(final int qid) {
        if (netHandler == null) return;

        netHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    String url = CLOUD_ENDPOINT + "?token=" + CLOUD_TOKEN
                            + "&ack=1&qid=" + qid;
                    String[] r = httpCall("GET", url, null, (int) NETWORK_TIMEOUT_MS);

                    if ("200".equals(r[0])) {
                        lastAckedQid = qid;
                        Log.i(TAG, "DIAG: ack ok qid=" + qid);
                    } else {
                        Log.w(TAG, "ack failed qid=" + qid + " status=" + r[0]);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "ack failed: " + e);
                }
            }
        });
    }

    private void ensureOverlay() {
        final Activity act = asActivity();
        if (act == null) return;
        if (overlay == null) {
            ViewGroup root = (ViewGroup) act.findViewById(android.R.id.content);
            if (root == null) return;
            overlay = new OverlayView(act);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setFocusableInTouchMode(false);
            root.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            Log.i(TAG, "DIAG: overlay created");
        }
    }

    private void hideOverlay() {
        final OverlayView ov = overlay;
        final Activity act = asActivity();
        if (ov == null || act == null) return;
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!ov.hasMarker()) return;
                ov.clearMarker();
                Log.i(TAG, "DIAG: overlay hidden");
            }
        });
    }

    // ---------- утилиты ----------

    private File externalFilesDir() {
        if (context == null) return null;
        return context.getExternalFilesDir(null);
    }

    private File captureDir() {
        return new File(externalFilesDir(), CAPTURE_SUBDIR);
    }

    private File burstDir() {
        return new File(externalFilesDir(), BURST_SUBDIR);
    }

    private void rotate(File dir, int maxFiles) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= maxFiles) return;
            Arrays.sort(files, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (int i = maxFiles; i < files.length; i++) files[i].delete();
        } catch (Exception e) {
            Log.w(TAG, "rotate failed: " + e);
        }
    }

    private String fileToBase64(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private String readText(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String[] httpCall(String method, String urlStr, String body, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (is != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
            }
            return new String[]{ String.valueOf(code), bo.toString("UTF-8") };
        } catch (Exception e) {
            return new String[]{ "0", "EXC: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage() };
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }
}
