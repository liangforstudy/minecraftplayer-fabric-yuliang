package dev.yuliang.zymbot.core.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Knowledge packs (FOUNDATION.md decision 7, ROADMAP.md → packs): one folder per mod, JSON files
 * inside, each carrying {@code schema_version}. A modpack profile is an ordered list of packs;
 * later packs override earlier ones, key by key (objects merge, everything else is replaced).
 * So {@code zymciv} can change vanilla's baked potato without vanilla knowing it exists.
 */
public final class PackLoader {
    public static final int SUPPORTED_SCHEMA = 1;
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");

    private PackLoader() {}

    /**
     * @param packDirs pack folders in profile order (earliest = lowest priority)
     * @return file name (e.g. "foods.json") → merged content, without schema_version
     */
    public static Map<String, JsonObject> load(List<Path> packDirs) {
        Map<String, JsonObject> merged = new LinkedHashMap<>();
        for (Path dir : packDirs) {
            if (!Files.isDirectory(dir)) {
                LOG.warn("[zymbot] pack {} not found — skipped", dir);
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    JsonObject doc = read(f);
                    if (doc == null) continue;
                    int v = doc.has("schema_version") ? doc.get("schema_version").getAsInt() : -1;
                    if (v < 1 || v > SUPPORTED_SCHEMA) {
                        LOG.warn("[zymbot] {}: schema_version {} unsupported (want 1..{}) — skipped", f, v, SUPPORTED_SCHEMA);
                        continue;
                    }
                    doc.remove("schema_version");
                    merged.merge(f.getFileName().toString(), doc, PackLoader::overlay);
                }
            } catch (IOException e) {
                LOG.error("[zymbot] can't list pack {}: {}", dir, e.getMessage());
            }
        }
        return merged;
    }

    /** Deep merge: {@code top} wins; nested objects merge; arrays and values are replaced. */
    static JsonObject overlay(JsonObject base, JsonObject top) {
        JsonObject out = base.deepCopy();
        for (Map.Entry<String, JsonElement> e : top.entrySet()) {
            JsonElement cur = out.get(e.getKey());
            if (cur != null && cur.isJsonObject() && e.getValue().isJsonObject()) {
                out.add(e.getKey(), overlay(cur.getAsJsonObject(), e.getValue().getAsJsonObject()));
            } else {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        return out;
    }

    private static JsonObject read(Path f) {
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(r);
            if (el.isJsonObject()) return el.getAsJsonObject();
            LOG.warn("[zymbot] {} is not a JSON object — skipped", f);
        } catch (IOException | RuntimeException e) {
            LOG.warn("[zymbot] can't read {}: {}", f, e.getMessage());
        }
        return null;
    }
}
