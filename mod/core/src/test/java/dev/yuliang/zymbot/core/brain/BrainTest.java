package dev.yuliang.zymbot.core.brain;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.FakeClock;
import dev.yuliang.zymbot.core.FakeWorld;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.task.Task;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BrainTest {
    /** A task that runs until cancelled, and remembers whether it was. */
    static final class Forever implements Task {
        final String what;
        boolean cancelled;
        Forever(String what) { this.what = what; }
        public Status tick() { return cancelled ? Status.DONE : Status.RUNNING; }
        public void cancel() { cancelled = true; }
        public String describe() { return what; }
    }

    @Test
    void interruptPreemptsObjective_cancelsIt_andSaysWhy() {
        Forever farming = new Forever("farming");
        Forever shelter = new Forever("walking to shelter");
        Objective farm = new Objective() {
            public String name() { return "farm"; }
            public Task start(WorldView w, Hands h) { return farming; }
            public String why() { return "wheat is ripe"; }
        };
        Interrupt bloodMoon = new Interrupt() {
            public String name() { return "blood-moon"; }
            public boolean triggered(WorldView w) { return w.timeOfDay() > 12000; }
            public Task respond(WorldView w, Hands h) { return shelter; }
            public String why(WorldView w) { return "blood moon likely (81%)"; }
        };
        FakeWorld w = new FakeWorld("Bot1");
        DecisionLog log = new DecisionLog(10, new FakeClock());
        Brain brain = new Brain(List.of(bloodMoon), world -> Optional.of(farm), log);

        brain.tick(w, w);
        assertEquals("farming — wheat is ripe", brain.describe());

        w.time = 12500;
        brain.tick(w, w);
        assertTrue(farming.cancelled, "the objective's task must be cancelled, not abandoned");
        assertEquals("walking to shelter — blood moon likely (81%)", brain.describe());
        assertEquals("walking to shelter — because blood moon likely (81%)", log.latest(1).get(0).toString());

        brain.tick(w, w);                                   // still sheltering: no re-decision spam
        assertEquals(2, log.latest(10).size());
    }

    @Test
    void idleWithoutObjectives() {
        FakeWorld w = new FakeWorld("Bot1");
        Brain brain = new Brain(List.of(), Planner.EMPTY, new DecisionLog(10, new FakeClock()));
        brain.tick(w, w);
        assertEquals("idle — no objectives", brain.describe());
    }

    @Test
    void failedTaskIsLoggedWithReason() {
        FakeWorld w = new FakeWorld("Bot1");
        DecisionLog log = new DecisionLog(10, new FakeClock());
        Objective walk = new Objective() {
            public String name() { return "walk"; }
            public Task start(WorldView wv, Hands h) { return h.walkTo(new dev.yuliang.zymbot.core.api.BlockPos(1, 2, 3)); }
            public String why() { return "test"; }
        };
        Brain brain = new Brain(List.of(), world -> Optional.of(walk), log);
        brain.tick(w, w);
        brain.tick(w, w);
        assertTrue(log.latest(10).stream().anyMatch(e -> e.why().contains("no pathfinder yet")));
    }
}
