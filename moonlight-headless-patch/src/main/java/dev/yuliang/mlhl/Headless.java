package dev.yuliang.mlhl;

/** Detects a HeadlessMC launch, where LWJGL (and therefore STB image decoding) is stubbed. */
public final class Headless {
    private static final boolean HEADLESS =
            "HeadlessMc".equals(System.getProperty("minecraft.launcher.brand"));

    private Headless() {}

    public static boolean isHeadless() {
        return HEADLESS;
    }
}
