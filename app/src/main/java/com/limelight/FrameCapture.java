package com.limelight;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.HttpURLConnection;
import java.net.URL;

public class FrameCapture {
    private static final String TAG = "FrameCapture";
    private static final String PROP_NAME = "debug.moonlight.capture";
    private static final String FLAG_FILE = "capture_interval.txt";
    private static final int JPEG_QUALITY = 85;
    private static final long COPY_TIMEOUT_MS = 2000;
    private static final String CAPTURE_SUBDIR = "capture";
    private static final int CAPTURE_SIZE = 256;
    private static final int MAX_FILES = 20;

    private static final String CLOUD_ENDPOINT = "https://functions.yandexcloud.net/d4el6hpg7nh0cui52kd2";
    private static final String CLOUD_TOKEN = "moon-7f3k9p2xq8v4m1w6r5t0zc4";
    private static final int NETWORK_TIMEOUT_MS = 5000;
    private static final long CACHE_TTL_MS = 600_000L; // 10 мин

    private static volatile int sNetworkInterval = -1; // -1 = не запрашивали
    private static volatile long sNetworkFetchedAtMs = 0L;

    private final Activity activity;
    private final SurfaceView surfaceView;
    private final HandlerThread workerThread;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private int intervalS = 0;
    private boolean networkCheckScheduled = false;
    private Method sysPropMethod = null;
    private Class<?> sysPropClass = null;

    public FrameCapture(Activity activity, SurfaceView surfaceView) {
        this.activity = activity;
        this.surfaceView = surfaceView;
        this.workerThread = new HandlerThread("frame-capture");
        this.workerThread.start();
        this.handler = new Handler(workerThread.getLooper());
    }

    private int readIntervalSeconds() {
        Log.w(TAG, "DIAG: readIntervalSeconds() called");
        // Приоритет 1: файл-флаг <external files>/capture_interval.txt
        // (создаётся файловым менеджером, без adb и root).
        try {
            File base = activity.getExternalFilesDir(null);
            if (base != null) {
                File flag = new File(base, FLAG_FILE);
                if (flag.exists()) {
                    FileInputStream fin = new FileInputStream(flag);
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[64];
                    int n;
                    while ((n = fin.read(b)) > 0) {
                        buf.write(b, 0, n);
                    }
                    fin.close();
                    return Math.max(Integer.parseInt(buf.toString("UTF-8").trim()), 0);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "flag file unreadable, falling back to prop", t);
        }
        // Приоритет 2: системный проп debug.moonlight.capture (adb setprop).
        try {
            if (sysPropClass == null) {
                sysPropClass = Class.forName("android.os.SystemProperties");
                sysPropMethod = sysPropClass.getMethod("get", String.class, String.class);
            }
            String raw = (String) sysPropMethod.invoke(null, PROP_NAME, "0");
            int val = Math.max(Integer.parseInt(raw.trim()), 0);
            if (val > 0) return val;
        } catch (Throwable t) {
            Log.w(TAG, "SystemProperties unavailable", t);
        }
        // Приоритет 3: сетевой конфиг через Cloud Function. Запрос идёт асинхронно,
        // чтобы не упереться в NetworkOnMainThreadException; если локальные флаги не дали
        // интервал, захват не включается сразу и будет включён, когда придёт ответ.
        scheduleNetworkConfigCheck();
        Log.i(TAG, "no flag file, prop not set - waiting for cloud config (async)");
        return 0;
    }

    /**
     * Запрашивает сетевой конфиг (приоритет 3). Тихо возвращает 0 при любой ошибке:
     * нет сети, HTTP не 200, битый JSON, поле interval отсутствует или = 0.
     * Кеш 600 с, повторный опрос не чаще этого периода.
     */
    private int fetchIntervalFromNetwork() {
        long now = System.currentTimeMillis();
        if (sNetworkInterval >= 0 && now - sNetworkFetchedAtMs < CACHE_TTL_MS) {
            Log.i(TAG, "cloud config CACHED: interval=" + sNetworkInterval + "s, age=" + ((now - sNetworkFetchedAtMs) / 1000) + "s");
            return sNetworkInterval;
        }
        Log.w(TAG, "DIAG: fetchIntervalFromNetwork() start");
        HttpURLConnection conn = null;
        try {
            String fullUrl = CLOUD_ENDPOINT + "?token=" + CLOUD_TOKEN;
            Log.w(TAG, "DIAG: HTTP request to " + CLOUD_ENDPOINT + "?token=***" + CLOUD_TOKEN);
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(NETWORK_TIMEOUT_MS);
            conn.setReadTimeout(NETWORK_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            Log.w(TAG, "DIAG: cloud config HTTP status: " + code);
            if (code != 200) {
                sNetworkInterval = 0;
                sNetworkFetchedAtMs = now;
                // Try to read error body for diagnostics
                String errBody = "";
                try {
                    java.io.InputStream es = conn.getErrorStream();
                    if (es != null) {
                        ByteArrayOutputStream eb = new ByteArrayOutputStream();
                        byte[] ec = new byte[512];
                        int en;
                        while ((en = es.read(ec)) > 0) eb.write(ec, 0, en);
                        errBody = new String(eb.toByteArray(), "UTF-8").trim();
                        if (errBody.length() > 200) errBody = errBody.substring(0, 200);
                    }
                } catch (Exception ignored) {}
                Log.i(TAG, "cloud config GET returned " + code + " body=" + errBody + ", capture stays off");
                return 0;
            }
            java.io.InputStream is = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
            is.close();
            String body = new String(buf.toByteArray(), "UTF-8");
            Log.w(TAG, "DIAG: cloud config body: " + (body.length() > 300 ? body.substring(0, 300) + "..." : body));
            org.json.JSONObject json;
            try {
                json = new org.json.JSONObject(body);
            } catch (org.json.JSONException je) {
                Log.w(TAG, "DIAG: JSON parse error", je);
                sNetworkInterval = 0;
                sNetworkFetchedAtMs = now;
                Log.w(TAG, "cloud config bad JSON: " + je.getMessage());
                return 0;
            }
            int interval = json.optInt("interval", 0);
            interval = Math.max(0, interval);
            sNetworkInterval = interval;
            sNetworkFetchedAtMs = now;
            if (interval > 0) {
                Log.i(TAG, "cloud config OK: interval=" + interval + "s");
            } else {
                Log.i(TAG, "cloud config: interval=0, capture stays off");
            }
            return interval;
        } catch (Throwable t) {
            sNetworkInterval = 0;
            sNetworkFetchedAtMs = now;
            Log.w(TAG, "DIAG: cloud config fetch FAILED: " + t.getClass().getName() + " -> " + t.getMessage(), t);
            return 0;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public void start() {
        if (running.getAndSet(true)) {
            // Already running (e.g. network check already enabled it) - just re-schedule capture
            if (intervalS > 0) {
                scheduleCapture();
            }
            return;
        }
        if (Build.VERSION.SDK_INT < 26) {
            running.set(false);
            Log.i(TAG, "PixelCopy requires API 26+, capture disabled (sdk="
                    + Build.VERSION.SDK_INT + ")");
            return;
        }
        intervalS = readIntervalSeconds();
        if (intervalS <= 0) {
            // Let the async network result enable capture later; keep the flag clear so
            // scheduleNetworkConfigCheck's condition (running == false) can turn it on.
            running.set(false);
            Log.i(TAG, "capture disabled for now - cloud config check in progress (async)");
            return;
        }
        Log.i(TAG, "capture enabled, interval=" + intervalS + "s, dir=" + captureDir());
        scheduleCapture();
    }

    private void scheduleCapture() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                captureLoop();
            }
        }, intervalS * 1000L);
    }

    /**
     * schedules a one-shot network config check on the worker thread.
     */
    private void scheduleNetworkConfigCheck() {
        if (networkCheckScheduled) return;
        networkCheckScheduled = true;
        Log.i(TAG, "DIAG: scheduling async cloud config check");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                networkCheckScheduled = false;
                int net = fetchIntervalFromNetwork();
                if (net > 0 && !running.get()) {
                    Log.i(TAG, "DIAG: async cloud config returned interval=" + net + "s, enabling capture");
                    intervalS = net;
                    running.set(true);
                    scheduleCapture();
                } else if (net <= 0) {
                    Log.i(TAG, "DIAG: async cloud config not available yet, capture stays off");
                }
            }
        }, 1000);
    }

    public void stop() {
        running.set(false);
        handler.removeCallbacksAndMessages(null);
    }

    public void destroy() {
        stop();
        workerThread.quitSafely();
    }

    private void captureLoop() {
        if (!running.get()) {
            return;
        }
        try {
            captureOnce();
        } catch (Throwable t) {
            Log.w(TAG, "captureOnce failed", t);
        }
        if (running.get()) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    captureLoop();
                }
            }, intervalS * 1000L);
        }
    }

    private boolean captureOnce() {
        int w = surfaceView.getWidth();
        int h = surfaceView.getHeight();
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "surface not ready yet");
            return false;
        }
        Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (!pixelCopy(surfaceView, full)) {
            full.recycle();
            return false;
        }
        int sw;
        int sh;
        if (w >= h) {
            sw = CAPTURE_SIZE;
            sh = Math.max(1, (int) ((long) h * CAPTURE_SIZE / w));
        } else {
            sh = CAPTURE_SIZE;
            sw = Math.max(1, (int) ((long) w * CAPTURE_SIZE / h));
        }
        Bitmap small = Bitmap.createScaledBitmap(full, sw, sh, true);
        File dir = captureDir();
        boolean ok = false;
        if (dir != null) {
            File out = new File(dir, "frame_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                ok = small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            } catch (Throwable t) {
                Log.w(TAG, "write capture failed", t);
            }
            if (ok) {
                Log.i(TAG, "captured " + out.getName() + " " + sw + "x" + sh);
                rotate(dir);
            }
        }
        if (small != full) {
            small.recycle();
        }
        full.recycle();
        return ok;
    }

    private void rotate(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_FILES) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_FILES; i++) {
            files[i].delete();
        }
    }

    private File captureDir() {
        File base = activity.getExternalFilesDir(null);
        if (base == null) {
            return null;
        }
        File dir = new File(base, CAPTURE_SUBDIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private boolean pixelCopy(SurfaceView view, Bitmap target) {
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] result = {-1};
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        PixelCopy.request(view, target,
                new PixelCopy.OnPixelCopyFinishedListener() {
                    @Override
                    public void onPixelCopyFinished(int copyResult) {
                        result[0] = copyResult;
                        latch.countDown();
                    }
                }, mainHandler);
        try {
            if (!latch.await(COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "PixelCopy timed out");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (result[0] != PixelCopy.SUCCESS) {
            Log.w(TAG, "PixelCopy failed, code=" + result[0]);
            return false;
        }
        return true;
    }
}
