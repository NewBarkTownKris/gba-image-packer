package io.github.newbarktownkris.gbaimagepacker;

public final class Native {
    static { System.loadLibrary("gba_packer"); }
    private Native() { }
    public static native void process(String source, String settings, String destination);
    public static native long build(String frames, String title, int interval, String destination);
}
