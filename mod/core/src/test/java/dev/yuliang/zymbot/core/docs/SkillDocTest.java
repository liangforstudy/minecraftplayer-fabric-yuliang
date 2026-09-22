package dev.yuliang.zymbot.core.docs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * .claude/skills/zymbot/SKILL.md tells future agents how to drive the bot. Its /zbot table must
 * list exactly the subcommands ZymbotCommands registers: add a row when a command is added,
 * delete it when one is removed.
 */
public class SkillDocTest {
    static Path repo() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(".claude/skills/zymbot/SKILL.md"))) p = p.getParent();
        assertNotNull(p, "no .claude/skills/zymbot/SKILL.md above " + Path.of("").toAbsolutePath());
        return p;
    }

    @Test
    void theSkillsCommandTable_matchesTheRegisteredCommands() throws IOException {
        Path root = repo();
        String code = Files.readString(root.resolve("mod/src/main/java/dev/yuliang/zymbot/fabric/ZymbotCommands.java"));
        Set<String> registered = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^ {16}\\.then\\(literal\\(\"([a-z]+)\"\\)").matcher(code);   // top-level subcommands
        while (m.find()) registered.add(m.group(1));
        assertTrue(registered.size() > 10, "parsed too few commands: " + registered);

        String skill = Files.readString(root.resolve(".claude/skills/zymbot/SKILL.md"));
        int start = skill.indexOf("## `/zbot` commands");
        assertTrue(start >= 0, "SKILL.md lost its '## `/zbot` commands' section");
        int end = skill.indexOf("\n## ", start + 1);
        String section = skill.substring(start, end < 0 ? skill.length() : end);
        Set<String> documented = new TreeSet<>();
        Matcher row = Pattern.compile("(?m)^\\| (.*?) \\|").matcher(section);
        while (row.find()) {
            Matcher cmd = Pattern.compile("`([a-z]+)").matcher(row.group(1));
            while (cmd.find()) documented.add(cmd.group(1));
        }
        documented.remove("command");                                                   // the header row

        Set<String> missing = new TreeSet<>(registered);
        missing.removeAll(documented);
        Set<String> stale = new TreeSet<>(documented);
        stale.removeAll(registered);
        assertTrue(missing.isEmpty(), "add these /zbot commands to SKILL.md's table: " + missing);
        assertTrue(stale.isEmpty(), "these are in SKILL.md's table but no longer registered — remove them: " + stale);
    }

    @Test
    void theCopyNextToTheOtherDocs_isTheSame() throws IOException {
        Path root = repo();
        assertEquals(Files.readString(root.resolve(".claude/skills/zymbot/SKILL.md")), Files.readString(root.resolve("SKILL.md")),
                "SKILL.md (repo root) is a copy of .claude/skills/zymbot/SKILL.md — edit one, then copy it over the other");
    }
}
