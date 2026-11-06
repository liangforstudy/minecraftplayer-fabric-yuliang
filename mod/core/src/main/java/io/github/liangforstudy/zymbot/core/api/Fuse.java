package io.github.liangforstudy.zymbot.core.api;

/**
 * A creeper's fuse, as the client sees it. {@code progress} 0 (calm) to 1 (bang); {@code lit} while it
 * counts up (hissing: it goes on until we're more than 7 blocks off or out of its sight); {@code charged}
 * after a lightning strike (twice the blast).
 */
public record Fuse(float progress, boolean lit, boolean charged) {
    public static final Fuse CALM = new Fuse(0, false, false);
}
