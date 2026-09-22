package dev.yuliang.zymbot.core.store;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one bot remembers about one server/world between sessions. Grows each phase (known beds,
 * farms, graves, the explored map...) — each addition is a schema bump with a migration.
 */
public final class BotMemory {
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;

    /** Other bots heard on the bus, by uuid. */
    public Map<String, RosterEntry> roster = new LinkedHashMap<>();

    public static final class RosterEntry {
        public String name;
        public long lastSeenMillis;
        public int x;
        public int z;
        public String phase;

        public RosterEntry() {}

        public RosterEntry(String name, long lastSeenMillis, int x, int z, String phase) {
            this.name = name;
            this.lastSeenMillis = lastSeenMillis;
            this.x = x;
            this.z = z;
            this.phase = phase;
        }
    }
}
