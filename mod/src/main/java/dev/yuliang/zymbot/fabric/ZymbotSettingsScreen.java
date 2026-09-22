package dev.yuliang.zymbot.fabric;

import dev.yuliang.zymbot.core.Bot;
import dev.yuliang.zymbot.core.config.AutostartPolicy;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.config.ZymbotConfig.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Zymbot settings (via Mod Menu), four tabs:
 * <ul>
 *   <li><b>Team key</b> — paste your bots' key, compare fingerprints, save. Hidden unless shown.</li>
 *   <li><b>Accounts</b> — which accounts are a Bot (the bot plays it) or a Teammate (you play; it announces).</li>
 *   <li><b>Servers</b> — where Zymbot is active at all.</li>
 *   <li><b>Body</b> — leash, when to eat, what counts as critical health (PHASE1.md).</li>
 * </ul>
 * Everything applies immediately, except that giving an account the Bot role never grabs the
 * controls from a menu — it takes effect on the next join (or /zbot start).
 */
final class ZymbotSettingsScreen extends Screen {
    private enum Tab { KEY, ACCOUNTS, SERVERS, BODY }

    private static final int GREY = 0xA0A0A0, WHITE = 0xFFFFFF, RED = 0xFF6060, GREEN = 0x70E070;
    private static final int ROW_H = 22, MAX_ROWS = 6;

    private final Screen parent;
    private Tab tab = Tab.KEY;
    private EditBox keyBox, serverBox;
    private String keyDraft, serverDraft = "";
    private boolean shown;
    private String message = "";
    private int messageColor = GREY;
    private final List<RowLabel> labels = new ArrayList<>();
    private final java.util.Map<String, EditBox> tunableBoxes = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, String> tunableDrafts = new java.util.HashMap<>();
    private int contentBottom;                             // lowest y used by this tab's controls

    /** Space-aware layout: how many list rows fit above the add-controls and the Done button. */
    private int rowsThatFit(int top, int belowRows) {
        int room = (height - 34) - top - belowRows;        // Done sits at height - 28
        return Math.max(1, Math.min(MAX_ROWS, room / ROW_H));
    }

    private record RowLabel(String text, int x, int y, int color) {}

    ZymbotSettingsScreen(Screen parent) {
        super(Component.literal("Zymbot"));
        this.parent = parent;
        this.keyDraft = cfg().teamKey;
    }

    private static ZymbotConfig cfg() { return ZymbotClient.get().config(); }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        keepDrafts();                                      // survives resizes and tab switches
        keyBox = null;
        serverBox = null;
        tunableBoxes.clear();
        labels.clear();
        int cx = width / 2;
        tabButton("Team key", Tab.KEY, cx - 153, 36);
        tabButton("Accounts", Tab.ACCOUNTS, cx - 76, 36);
        tabButton("Servers", Tab.SERVERS, cx + 1, 36);
        tabButton("Body", Tab.BODY, cx + 78, 36);
        int top = 68;
        switch (tab) {
            case KEY -> initKey(cx, top);
            case ACCOUNTS -> initAccounts(cx, top);
            case SERVERS -> initServers(cx, top);
            case BODY -> initBody(cx, top);
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx - 50, height - 28, 100, 20).build());
    }

    private void tabButton(String name, Tab t, int x, int y) {
        Button b = addRenderableWidget(Button.builder(Component.literal(name), btn -> {
            keepDrafts();
            tab = t;
            message = "";
            rebuildWidgets();
        }).bounds(x, y, 74, 20).build());
        b.active = tab != t;                               // the current tab shows as pressed
    }

    private void keepDrafts() {
        if (keyBox != null) keyDraft = keyBox.getValue();
        if (serverBox != null) serverDraft = serverBox.getValue();
        tunableBoxes.forEach((k, box) -> tunableDrafts.put(k, box.getValue()));
    }

    // ------------------------------------------------------------------ team key

    private void initKey(int cx, int top) {
        keyBox = new EditBox(font, cx - 150, top + 12, 300, 20, Component.literal("Team key"));
        keyBox.setMaxLength(128);
        keyBox.setValue(keyDraft);
        keyBox.setFormatter((text, start) ->
                FormattedCharSequence.forward(shown ? text : "•".repeat(text.length()), Style.EMPTY));
        addRenderableWidget(keyBox);
        addRenderableWidget(Button.builder(Component.literal("Paste"), b -> {
            keyBox.setValue(minecraft.keyboardHandler.getClipboard().strip());
            say("pasted — check the fingerprint, then Save", GREY);
        }).bounds(cx - 150, top + 38, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal(shown ? "Hide" : "Show"), b -> {
            shown = !shown;
            b.setMessage(Component.literal(shown ? "Hide" : "Show"));
        }).bounds(cx - 76, top + 38, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save key"), b -> {
            String problem = ZymbotClient.get().applyTeamKey(keyBox.getValue());
            if (problem != null) say(problem, RED);
            else say("saved — in use now (fingerprint " + ZymbotConfig.fingerprint(keyBox.getValue().strip()) + ")", GREEN);
        }).bounds(cx + 80, top + 38, 70, 20).build());
        contentBottom = top + 80;                           // includes the fingerprint line
    }

    // ------------------------------------------------------------------ accounts

    private void initAccounts(int cx, int top) {
        UUID me = minecraft.getUser().getProfileId();
        String myName = minecraft.getUser().getName();
        List<ZymbotConfig.Account> accounts = cfg().accounts;
        int shown = rowsThatFit(top, 30);
        int y = top;
        if (accounts.isEmpty()) {
            label("No accounts listed — Zymbot is silent everywhere.", cx - 150, y + 6, GREY);
            y += ROW_H;
        }
        for (int i = 0; i < Math.min(accounts.size(), shown); i++, y += ROW_H) {
            ZymbotConfig.Account a = accounts.get(i);
            boolean isMe = a.uuid.equalsIgnoreCase(me.toString());
            label(a.name + (isMe ? "  (you, now)" : ""), cx - 150, y + 6, isMe ? WHITE : GREY);
            addRenderableWidget(Button.builder(Component.literal(a.parsedRole().label()), b -> {
                Role next = a.parsedRole() == Role.BOT ? Role.TEAMMATE : Role.BOT;
                a.role = next.name().toLowerCase(java.util.Locale.ROOT);
                ZymbotClient.get().settingsEdited();
                say(a.name + " is now a " + next.label() + roleNote(next, isMe), GREEN);
                rebuildWidgets();
            }).bounds(cx + 20, y, 70, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
                accounts.remove(a);
                ZymbotClient.get().settingsEdited();
                say("removed " + a.name, GREY);
                rebuildWidgets();
            }).bounds(cx + 94, y, 56, 20).build());
        }
        if (accounts.size() > shown) {
            label("+ " + (accounts.size() - shown) + " more (bigger window, or config/zymbot.json)", cx - 150, y + 6, GREY);
            y += ROW_H;
        }

        y += 6;
        contentBottom = y + 20;
        if (cfg().roleOf(me) == Role.NONE) {
            addRenderableWidget(Button.builder(Component.literal("+ " + myName + " as Teammate"), b -> addMe(me, myName, Role.TEAMMATE))
                    .bounds(cx - 150, y, 148, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+ " + myName + " as Bot"), b -> addMe(me, myName, Role.BOT))
                    .bounds(cx + 2, y, 148, 20).build());
        } else {
            label("This account (" + myName + ") is listed as " + cfg().roleOf(me).label() + ".", cx - 150, y + 6, GREY);
        }
    }

    private void addMe(UUID me, String name, Role role) {
        Bot bot = ZymbotClient.get().bot();
        if (bot != null && bot.selfId().equals(me)) {
            say(bot.setOwnRole(role), GREEN);                // saves and applies live
        } else {
            cfg().setRole(me, name, role);
            ZymbotClient.get().settingsEdited();
            say(name + " is now a " + role.label() + roleNote(role, true), GREEN);
        }
        rebuildWidgets();
    }

    private static String roleNote(Role r, boolean isMe) {
        if (!isMe) return "";
        return r == Role.BOT ? " — takes control next time you join a listed server" : " — announces on listed servers";
    }

    // ------------------------------------------------------------------ servers

    private void initServers(int cx, int top) {
        List<String> servers = cfg().autostartServers;
        Bot liveBot = ZymbotClient.get().bot();
        String here = liveBot == null ? null : liveBot.currentServer();
        boolean offerHere = here != null && !AutostartPolicy.shouldAutostart(servers, here);
        int shown = rowsThatFit(top, offerHere ? 54 : 30);
        int y = top;
        if (servers.isEmpty()) {
            label("No servers listed — Zymbot is silent everywhere.", cx - 150, y + 6, GREY);
            y += ROW_H;
        }
        for (int i = 0; i < Math.min(servers.size(), shown); i++, y += ROW_H) {
            String s = servers.get(i);
            label(AutostartPolicy.normalize(s), cx - 150, y + 6, WHITE);
            addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
                servers.remove(s);
                ZymbotClient.get().settingsEdited();
                say("removed " + AutostartPolicy.normalize(s), GREY);
                rebuildWidgets();
            }).bounds(cx + 94, y, 56, 20).build());
        }
        if (servers.size() > shown) {
            label("+ " + (servers.size() - shown) + " more (bigger window, or config/zymbot.json)", cx - 150, y + 6, GREY);
            y += ROW_H;
        }

        y += 6;
        contentBottom = y + (offerHere ? 44 : 20);
        serverBox = new EditBox(font, cx - 150, y, 186, 20, Component.literal("Server address"));
        serverBox.setMaxLength(255);
        serverBox.setValue(serverDraft);
        serverBox.setHint(Component.literal("play.example.net or singleplayer"));
        addRenderableWidget(serverBox);
        addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
            addServer(serverBox.getValue());
            serverDraft = "";
            rebuildWidgets();
        }).bounds(cx + 40, y, 110, 20).build());

        if (offerHere) {
            addRenderableWidget(Button.builder(Component.literal("+ This server (" + AutostartPolicy.normalize(here)
                    .replace(":25565", "") + ")"), b -> {
                addServer(here);
                rebuildWidgets();
            }).bounds(cx - 150, y + 24, 300, 20).build());
        }
    }

    private void addServer(String raw) {
        if (raw == null || raw.isBlank()) { say("type an address first", RED); return; }
        String key = AutostartPolicy.normalize(raw);
        if (AutostartPolicy.shouldAutostart(cfg().autostartServers, raw)) { say(key + " is already listed", GREY); return; }
        cfg().autostartServers.add(key);
        ZymbotClient.get().settingsEdited();
        say("added " + key, GREEN);
    }

    // ------------------------------------------------------------------ body

    private static final String[][] TUNABLE_ROWS = {
            {"leash", "Leash — max blocks from a human while working"},
            {"eat", "Eat when hunger is at or below (of 20)"},
            {"critical", "Critical health — heal or retreat (of 20)"}};

    private void initBody(int cx, int top) {
        int y = top;
        for (String[] row : TUNABLE_ROWS) {
            String key = row[0];
            int[] range = ZymbotConfig.TUNABLES.get(key);
            label(row[1], cx - 150, y + 6, WHITE);
            EditBox box = new EditBox(font, cx + 60, y, 44, 20, Component.literal(row[1]));
            box.setMaxLength(4);
            box.setFilter(t -> t.chars().allMatch(Character::isDigit));
            box.setValue(tunableDrafts.getOrDefault(key, String.valueOf(cfg().tunable(key))));
            addRenderableWidget(box);
            tunableBoxes.put(key, box);
            label(range[0] + "–" + range[1], cx + 110, y + 6, GREY);
            y += ROW_H;
        }
        y += 6;
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveTunables()).bounds(cx - 50, y, 100, 20).build());
        contentBottom = y + 20;
    }

    private void saveTunables() {
        Bot bot = ZymbotClient.get().bot();
        List<String> saved = new ArrayList<>();
        for (var e : tunableBoxes.entrySet()) {
            String text = e.getValue().getValue();
            if (text.isEmpty()) { say(e.getKey() + ": type a number", RED); return; }
            int v = Integer.parseInt(text);
            if (v == cfg().tunable(e.getKey())) continue;
            String problem;
            if (bot != null) {                               // saves and logs it
                String r = bot.set(e.getKey(), v);
                problem = r.equals(e.getKey() + " = " + v) ? null : r;
            } else {
                problem = cfg().setTunable(e.getKey(), v);
            }
            if (problem != null) { say(problem, RED); return; }
            saved.add(e.getKey() + " " + v);
        }
        if (bot == null) ZymbotClient.get().settingsEdited();
        tunableDrafts.clear();
        say(saved.isEmpty() ? "nothing changed" : "saved — " + String.join(", ", saved) + " (in use now)", GREEN);
        rebuildWidgets();
    }

    // ------------------------------------------------------------------ drawing

    private void label(String text, int x, int y, int color) {
        labels.add(new RowLabel(text, x, y, color));
    }

    private void say(String text, int color) {
        message = text;
        messageColor = color;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = width / 2;
        g.drawCenteredString(font, title, cx, 14, WHITE);
        for (RowLabel l : labels) g.drawString(font, l.text(), l.x(), l.y(), l.color());
        String[] help = switch (tab) {
            case KEY -> {
                g.drawString(font, "Team key", cx - 150, 68, GREY);
                g.drawString(font, "fingerprint: " + ZymbotConfig.fingerprint(keyBox.getValue().strip()), cx - 150, 136, WHITE);
                yield new String[] {
                        "Bots only hear bots with the same key; it also encrypts their messages.",
                        "Get your bots' key with  headless/team-key.sh  and paste it here.",
                        "Matching fingerprints = matching keys. Never share the key in public chat."};
            }
            case ACCOUNTS -> new String[] {
                    "Bot: the bot plays this account on listed servers.",
                    "Teammate: you play; Zymbot tells your bots you're on the team. Never takes control.",
                    "Unlisted accounts: Zymbot stays silent."};
            case SERVERS -> new String[] {
                    "Zymbot is only active on these servers — silent everywhere else.",
                    "Add \"singleplayer\" to include your own worlds."};
            case BODY -> new String[] {
                    "No natural regeneration here: only healing food brings health back.",
                    "The leash never applies to your direct orders, or to an idle bot.",
                    "Also: /" + cfg().commandRoot + " set leash|eat|critical <n>"};
        };
        // help and the status message go between the controls and Done — only as much as fits
        int floor = height - 32;
        int y = Math.max(contentBottom + 6, floor - 12 * help.length - 14);
        if (y + 12 * help.length + 14 <= floor) {
            for (String h : help) {
                g.drawCenteredString(font, h, cx, y, GREY);
                y += 12;
            }
        } else {
            y = Math.min(contentBottom + 6, floor - 12);    // no room for help: keep the message
        }
        if (!message.isEmpty()) g.drawCenteredString(font, message, cx, y + 2, messageColor);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
