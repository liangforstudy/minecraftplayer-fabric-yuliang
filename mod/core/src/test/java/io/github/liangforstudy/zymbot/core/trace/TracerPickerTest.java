package io.github.liangforstudy.zymbot.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.trace.TracerPicker.Kind;
import io.github.liangforstudy.zymbot.core.trace.TracerPicker.Target;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #7: 20 lines by default, players first, then the nearest. */
class TracerPickerTest {
    @Test
    void playersFirst_thenNearest_upToTheLimit() {
        List<Target<String>> c = new ArrayList<>();
        c.add(new Target<>(Kind.BLOCK, 3, "chest"));
        c.add(new Target<>(Kind.PLAYER, 90, "far player"));
        c.add(new Target<>(Kind.ITEM, 5, "elytra"));
        c.add(new Target<>(Kind.PLAYER, 40, "near player"));
        c.add(new Target<>(Kind.ENTITY, 1, "donkey"));
        assertEquals(List.of("near player", "far player", "donkey"),
                TracerPicker.pick(c, 3).stream().map(Target::what).toList());
    }

    @Test
    void defaults() {
        ZymbotConfig cfg = new ZymbotConfig();
        assertEquals(20, cfg.tunable("tracelimit"));
        assertEquals(true, cfg.tracePlayers);
        assertEquals(false, cfg.tracers, "off until switched on");
    }
}
