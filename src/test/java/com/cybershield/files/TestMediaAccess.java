package com.cybershield.files;

/** Lets tests in other packages make the same sample files as TestMedia. */
public final class TestMediaAccess {

    private TestMediaAccess() {
    }

    public static byte[] png() throws Exception {
        return TestMedia.png(800, 500);
    }

    public static byte[] wav() throws Exception {
        return TestMedia.wav(2);
    }

    public static byte[] pdf() throws Exception {
        return TestMedia.pdf("First page", "Second page");
    }

}
