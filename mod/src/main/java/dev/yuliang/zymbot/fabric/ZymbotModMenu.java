package dev.yuliang.zymbot.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu → Zymbot → settings. Only loaded when Mod Menu is installed (headless bots don't have it). */
public final class ZymbotModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ZymbotSettingsScreen::new;
    }
}
