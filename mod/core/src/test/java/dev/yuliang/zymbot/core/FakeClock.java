package dev.yuliang.zymbot.core;

import java.util.function.LongSupplier;

public final class FakeClock implements LongSupplier {
    public long now = 1_000_000;
    @Override public long getAsLong() { return now; }
    public void advance(long millis) { now += millis; }
}
