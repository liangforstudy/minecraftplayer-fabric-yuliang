package dev.yuliang.zymbot.core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {
    @TempDir Path dir;

    @Test
    void missingFileGetsDefaultsAndIsWritten() throws Exception {
        Path f = dir.resolve("zymbot.json");
        ZymbotConfig c = ConfigIO.load(f);
        assertEquals("zbot", c.commandRoot);
        String written = Files.readString(f);
        assertTrue(written.contains("\"command_root\": \"zbot\""), written);
        assertTrue(written.contains("\"autostart_servers\""));
    }

    @Test
    void partialFileKeepsUserValuesAndFillsTheRest() throws Exception {
        Path f = dir.resolve("zymbot.json");
        Files.writeString(f, "{ \"command_root\": \"mybot\", \"autostart_servers\": [\"singleplayer\"] }");
        ZymbotConfig c = ConfigIO.load(f);
        assertEquals("mybot", c.commandRoot);
        assertEquals(List.of("singleplayer"), c.autostartServers);
        assertEquals("!lf", c.humanForecastPrefix);
    }

    @Test
    void aTeamKeyIsGeneratedOnceAndKept() {
        Path f = dir.resolve("zymbot.json");
        String key = ConfigIO.load(f).teamKey;
        assertTrue(key.length() >= 20, "a strong random key, never empty: " + key);
        assertEquals(key, ConfigIO.load(f).teamKey, "stable across restarts");
        assertNotEquals(key, ConfigIO.load(dir.resolve("other.json")).teamKey, "each install gets its own");
    }

    @Test
    void badCommandRootFallsBack() {
        ZymbotConfig c = new ZymbotConfig();
        c.commandRoot = "My Bot!";
        c.commandAliases.add("zbot");
        c.commandAliases.add("ok_alias");
        List<String> w = c.normalize();
        assertEquals("zbot", c.commandRoot);
        assertEquals(List.of("ok_alias"), c.commandAliases, "an alias equal to the root is dropped");
        assertFalse(w.isEmpty());
    }

    @Test
    void brokenFileIsNotOverwritten() throws Exception {
        Path f = dir.resolve("zymbot.json");
        Files.writeString(f, "{ this is not json");
        ConfigIO.load(f);
        assertEquals("{ this is not json", Files.readString(f), "a half-edited file must survive");
    }

    @Test
    void addressNormalization() {
        assertEquals("play.example.net:25565", AutostartPolicy.normalize("Play.Example.NET."));
        assertEquals("127.0.0.1:25566", AutostartPolicy.normalize("127.0.0.1:25566"));
        assertEquals("[::1]:25565", AutostartPolicy.normalize("[::1]"));
        assertEquals("singleplayer", AutostartPolicy.normalize("SinglePlayer"));
        assertTrue(AutostartPolicy.shouldAutostart(List.of("127.0.0.1"), "127.0.0.1:25565"));
        assertFalse(AutostartPolicy.shouldAutostart(List.of("127.0.0.1"), "127.0.0.1:25566"));
    }
}
