package com.limelight;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.View;

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

public class FrameCapture {
    private static final String TAG = "FrameCapture";
    private static final String PROP_NAME = "debug.moonlight.capture";
    private static final String FLAG_FILE = "capture_interval.txt";
    private static final int JPEG_QUALITY = 85;
    private static final long COPY_TIMEOUT_MS = 2000;
    private static final String CAPTURE_SUBDIR = "capture";
    private static final int CAPTURE_SIZE = 256;
    private static final int MAX_FILES = 20;

    private final Activity activity;
    private final View surfaceView;
    private final HandlerThread workerThread;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private int intervalS = 0;
    private Method sysPropMethod = null;
    private Class<?> sysPropClass = null;

    public FrameCapture(Activity activity, View surfaceView) {
        this.activity = activity;
        this.surfaceView = surfaceView;
        this.workerThread = new HandlerThread("frame-capture");
        this.workerThread.start();
        this.handler = new Handler(workerThread.getLooper());
    }

    private int readIntervalSeconds() {
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
            return Math.max(Integer.parseInt(raw.trim()), 0);
        } catch (Throwable t) {
            Log.w(TAG, "SystemProperties unavailable, capture stays off", t);
            return 0;
        }
    }

    public void start() {
        if (running.getAndSet(true)) {
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
            running.set(false);
            Log.i(TAG, "capture disabled (no flag file, prop not set)");
            return;
        }
        Log.i(TAG, "capture enabled, interval=" + intervalS + "s, dir=" + captureDir());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                captureLoop();
            }
        }, intervalS * 1000L);
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
        View v = surfaceView;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "surface not ready yet");
            return false;
        }
        Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (!pixelCopy(v, full)) {
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

    private boolean pixelCopy(View view, Bitmap target) {
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
