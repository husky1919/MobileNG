package com.ng.emu;

public final class Native {
    public static native int init(String corePath, String sysDir, String saveDir);
    public static native int loadRom(String romPath);
    public static native void start();
    public static native void stop();
    public static native void reset();
    public static native void deinit();
    public static native void glInit();
    public static native void glDraw(int w, int h);
    public static native void setButton(int port, int id, boolean pressed);
    public static native int readAudio(short[] buf, int frames);
    public static native int saveState(String path);
    public static native int loadState(String path);
    public static native int setCheats(String text);
    public static native void setLogFile(String path);
    public static native void setVideo(int scaleMode, int filter, float bright, float contrast, float sat);
    public static native void setCoreVariable(String key, String value);
    public static native String getCoreVariable(String key);
    public static native String getDriver();
    public static native void netEnable(int mode, int localPort);
    public static native void netRestart();
    public static native void netPause(int on);
    public static native long netPoll();
    public static native void netDeliver(int frame, int mask);
    public static native int netState();
    public static native void setNetDelay(int delay);
    public static native float getFps();
    public static native float getTargetFps();
    public static native long getFrames();
    public static native byte[] readMem(int addr, int len);
    public static native int writeMem(int addr, byte[] data);
    public static native String getMemRanges();
}
