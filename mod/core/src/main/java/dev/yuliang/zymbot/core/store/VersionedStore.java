package dev.yuliang.zymbot.core.store;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A JSON file with a {@code schema_version}, upgraded step by step on load (FOUNDATION.md decision 8).
 * Saved memory from an older mod version keeps loading: add a migration from N to N+1, bump the
 * version, never edit old migrations. Writes go through a temp file so a crash can't corrupt it.
 */
public final class VersionedStore<T> {
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");
    private final Gson gson;
    private final Class<T> type;
    private final int currentVersion;
    private final Map<Integer, UnaryOperator<JsonObject>> migrations = new TreeMap<>();

    public VersionedStore(Gson gson, Class<T> type, int currentVersion) {
        this.gson = gson;
        this.type = type;
        this.currentVersion = currentVersion;
    }

    /** Upgrades a document from {@code fromVersion} to {@code fromVersion + 1}. */
    public VersionedStore<T> migration(int fromVersion, UnaryOperator<JsonObject> step) {
        migrations.put(fromVersion, step);
        return this;
    }

    /** Loads the file, or returns {@code fresh} if it doesn't exist or can't be read. */
    public T load(Path file, T fresh) {
        if (!Files.exists(file)) return fresh;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject doc = JsonParser.parseReader(r).getAsJsonObject();
            int v = doc.has("schema_version") ? doc.get("schema_version").getAsInt() : 0;
            if (v > currentVersion) {
                LOG.warn("[zymbot] {} is from a newer version ({} > {}); starting fresh, file kept", file, v, currentVersion);
                return fresh;
            }
            while (v < currentVersion) {
                UnaryOperator<JsonObject> step = migrations.get(v);
                if (step == null) throw new IllegalStateException("no migration from schema " + v);
                doc = step.apply(doc);
                doc.addProperty("schema_version", ++v);
            }
            return gson.fromJson(doc, type);
        } catch (IOException | RuntimeException e) {
            LOG.error("[zymbot] can't load {} — starting fresh: {}", file, e.getMessage());
            return fresh;
        }
    }

    public void save(Path file, T value) {
        try {
            Files.createDirectories(file.getParent());
            JsonElement tree = gson.toJsonTree(value);
            tree.getAsJsonObject().addProperty("schema_version", currentVersion);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(tree, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.error("[zymbot] can't save {}: {}", file, e.getMessage());
        }
    }
}
