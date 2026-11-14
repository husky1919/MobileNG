package com.ng.emu;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 极简日志：同时打到 logcat 和 filesDir/emu.log */
public final class Logger {
    private static File file;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Logger() {}

    public static void init(File f) { file = f; }

    public static File file() { return file; }

    public static synchronized void log(String s) {
        android.util.Log.i("NGE", s);
        if (file == null) return;
        try (FileOutputStream o = new FileOutputStream(file, true)) {
            o.write((FMT.format(new Date()) + " [java] " + s + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    public static void log(String s, Throwable t) {
        log(s + ": " + t);
    }
}
