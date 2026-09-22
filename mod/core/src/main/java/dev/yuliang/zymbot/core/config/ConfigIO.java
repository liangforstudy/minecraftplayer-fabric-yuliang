package dev.yuliang.zymbot.core.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads config/zymbot.json, filling in defaults and writing the file back so new keys appear. */
public final class ConfigIO {
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");
    public static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private ConfigIO() {}

    public static ZymbotConfig load(Path file) {
        ZymbotConfig cfg = new ZymbotConfig();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                ZymbotConfig read = GSON.fromJson(r, ZymbotConfig.class);
                if (read != null) cfg = read;
            } catch (IOException | JsonParseException e) {
                LOG.error("[zymbot] can't read {} — using defaults: {}", file, e.getMessage());
                return defaults(cfg);
            }
        }
        List<String> warnings = cfg.normalize();
        warnings.forEach(w -> LOG.warn("[zymbot] config: {}", w));
        if (cfg.teamKey.isEmpty()) {
            cfg.teamKey = newTeamKey();
            LOG.warn("[zymbot] generated a new team key in {} — bots only hear bots with the SAME key. "
                    + "Copy team_key to your other bots (headless: setup does it for you).", file);
        }
        save(file, cfg);
        return cfg;
    }

    public static void save(Path file, ZymbotConfig cfg) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(cfg, w);
            }
        } catch (IOException e) {
            LOG.error("[zymbot] can't write {}: {}", file, e.getMessage());
        }
    }

    /** 144 random bits, URL-safe — easy to copy between config files. */
    public static String newTeamKey() {
        byte[] b = new byte[18];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static ZymbotConfig defaults(ZymbotConfig cfg) {
        cfg.normalize();
        return cfg;   // don't overwrite a file we couldn't parse — the user may be mid-edit
    }
}
