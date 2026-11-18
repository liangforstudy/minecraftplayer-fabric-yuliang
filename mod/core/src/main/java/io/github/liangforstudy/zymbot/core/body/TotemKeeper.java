package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import java.util.Optional;

/**
 * Auto totem (issue #8), under {@code /zbot start}: no totem in the offhand (it popped, or something
 * else is there) and one in the inventory, so swap it in. One click, no turning or walking, so it runs
 * every tick the bot runs, even while a human holds the movement keys or a reflex is busy; not with a
 * container open (the click would go to the wrong menu). After a swap it waits {@link #WAIT_MS} for
 * the server's answer before trying again.
 */
public final class TotemKeeper {
    public static final String TOTEM = LogOutInterrupt.TOTEM;
    static final long WAIT_MS = 500;

    private final ZymbotConfig config;
    private long nextTry;

    public TotemKeeper(ZymbotConfig config) {
        this.config = config;
    }

    /** Swaps one in if it should. Returns what it did, for the log, or empty. */
    public Optional<String> tick(WorldView world, Hands hands, long now) {
        if (!config.autoTotem || now < nextTry || world.isDead() || world.openContainerFilled() >= 0) return Optional.empty();
        String off = world.offhandItem();
        if (TOTEM.equals(off)) return Optional.empty();
        Optional<ItemView> totem = world.inventory().stream().filter(i -> TOTEM.equals(i.id())).findFirst();
        if (totem.isEmpty()) return Optional.empty();
        hands.swapToOffhand(totem.get().slot());
        nextTry = now + WAIT_MS;
        return Optional.of("totem from slot " + totem.get().slot() + " into the offhand"
                + (off.isEmpty() ? "" : " (" + off.replaceFirst("^minecraft:", "") + " moved to the inventory)"));
    }
}
