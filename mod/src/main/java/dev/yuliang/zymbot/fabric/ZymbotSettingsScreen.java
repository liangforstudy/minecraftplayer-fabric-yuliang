package dev.yuliang.zymbot.fabric;

import dev.yuliang.zymbot.core.config.ZymbotConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Zymbot settings (via Mod Menu). For now: the team key — paste it in, check the fingerprint
 * matches your bots', save. Applies immediately; no restart. The key is hidden unless you ask.
 */
final class ZymbotSettingsScreen extends Screen {
    private static final int GREY = 0xA0A0A0, WHITE = 0xFFFFFF, RED = 0xFF6060, GREEN = 0x70E070;
    private final Screen parent;
    private EditBox keyBox;
    private Button showButton;
    private boolean shown;
    private String message = "";
    private int messageColor = GREY;

    ZymbotSettingsScreen(Screen parent) {
        super(Component.literal("Zymbot"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2, y = height / 4;
        String current = keyBox != null ? keyBox.getValue() : ZymbotClient.get().config().teamKey;

        keyBox = new EditBox(font, cx - 150, y + 24, 300, 20, Component.literal("Team key"));
        keyBox.setMaxLength(128);
        keyBox.setValue(current);
        keyBox.setFormatter((text, start) ->
                FormattedCharSequence.forward(shown ? text : "•".repeat(text.length()), Style.EMPTY));
        addRenderableWidget(keyBox);

        addRenderableWidget(Button.builder(Component.literal("Paste"), b -> {
            keyBox.setValue(minecraft.keyboardHandler.getClipboard().strip());
            setMessage("pasted — check the fingerprint, then Save", GREY);
        }).bounds(cx - 150, y + 50, 96, 20).build());
        showButton = addRenderableWidget(Button.builder(Component.literal(shown ? "Hide" : "Show"), b -> {
            shown = !shown;
            b.setMessage(Component.literal(shown ? "Hide" : "Show"));
        }).bounds(cx - 48, y + 50, 96, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(cx - 102, height - 40, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(cx + 2, height - 40, 100, 20).build());
    }

    private void save() {
        String problem = ZymbotClient.get().applyTeamKey(keyBox.getValue());
        if (problem != null) {
            setMessage(problem, RED);
            return;
        }
        setMessage("saved — in use now (fingerprint " + ZymbotConfig.fingerprint(keyBox.getValue().strip()) + ")", GREEN);
    }

    private void setMessage(String text, int color) {
        message = text;
        messageColor = color;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = width / 2, y = height / 4;
        g.drawCenteredString(font, title, cx, 20, WHITE);
        g.drawString(font, "Team key", cx - 150, y + 12, GREY);
        g.drawString(font, "fingerprint: " + ZymbotConfig.fingerprint(keyBox.getValue().strip()), cx + 54, y + 56, WHITE);
        int line = y + 84;
        for (String help : new String[] {
                "Bots only hear bots with the same key; it also encrypts their messages.",
                "Get your bots' key with  headless/team-key.sh  and paste it here.",
                "Matching fingerprints = matching keys. Never share the key in public chat."}) {
            g.drawCenteredString(font, help, cx, line, GREY);
            line += 12;
        }
        if (!message.isEmpty()) g.drawCenteredString(font, message, cx, line + 8, messageColor);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
