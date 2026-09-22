package dev.yuliang.zymbot.core.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.yuliang.zymbot.core.config.ConfigIO;
import dev.yuliang.zymbot.core.pack.PackLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreAndPackTest {
    @TempDir Path dir;

    public static final class V2 {
        public int schemaVersion;
        public String botName;      // v1 called it "name"
        public int visits;          // new in v2
    }

    @Test
    void oldFilesMigrateForward() throws Exception {
        Path f = dir.resolve("m.json");
        Files.writeString(f, "{ \"schema_version\": 1, \"name\": \"Bot1\" }");
        VersionedStore<V2> store = new VersionedStore<>(ConfigIO.GSON, V2.class, 2)
                .migration(1, doc -> {
                    doc.add("bot_name", doc.remove("name"));
                    doc.addProperty("visits", 0);
                    return doc;
                });
        V2 m = store.load(f, new V2());
        assertEquals("Bot1", m.botName);
        assertEquals(2, m.schemaVersion);
        store.save(f, m);
        assertTrue(Files.readString(f).contains("\"schema_version\": 2"));
    }

    @Test
    void newerFileIsLeftAlone() throws Exception {
        Path f = dir.resolve("m.json");
        Files.writeString(f, "{ \"schema_version\": 99 }");
        V2 fresh = new V2();
        assertSame(fresh, new VersionedStore<>(ConfigIO.GSON, V2.class, 2).load(f, fresh));
        assertTrue(Files.readString(f).contains("99"), "never clobber data from a newer mod version");
    }

    @Test
    void laterPacksOverrideEarlierOnes() throws Exception {
        Path vanilla = Files.createDirectories(dir.resolve("vanilla"));
        Path zymciv = Files.createDirectories(dir.resolve("zymciv"));
        Files.writeString(vanilla.resolve("foods.json"),
                "{ \"schema_version\": 1, \"baked_potato\": { \"nutrition\": 5, \"saturation\": 6.0 }, \"bread\": { \"nutrition\": 5 } }");
        Files.writeString(zymciv.resolve("foods.json"),
                "{ \"schema_version\": 1, \"baked_potato\": { \"nutrition\": 4 } }");
        Files.writeString(zymciv.resolve("future.json"), "{ \"schema_version\": 7 }");

        Map<String, JsonObject> packs = PackLoader.load(List.of(vanilla, zymciv));
        JsonObject potato = packs.get("foods.json").getAsJsonObject("baked_potato");
        assertEquals(4, potato.get("nutrition").getAsInt(), "zymciv wins");
        assertEquals(6.0, potato.get("saturation").getAsDouble(), "untouched keys survive the merge");
        assertTrue(packs.get("foods.json").has("bread"));
        assertFalse(packs.containsKey("future.json"), "unsupported schema is skipped");
    }
}
