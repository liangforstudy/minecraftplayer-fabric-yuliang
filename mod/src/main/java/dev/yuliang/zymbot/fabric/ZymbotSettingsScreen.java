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
import org.lwjgl.glfw.GLFW;

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
    private final java.util.Map<String, String> tunableDrafts = new java.util.LinkedHashMap<>();
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
        scrubZones.clear();
        activeZone = null;
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
            {"leash", "Leash — max blocks from the nearest teammate"},
            {"heel", "Follow: stop this close to the teammate"},
            {"eat", "Eat when hunger is at or below (of 20)"},
            {"critical", "Critical health — heal or retreat (of 20)"},
            {"downed", "Knocked out: seconds to wait for a human"},
            {"regroup", "Regroup when no teammate within (blocks)"},
            {"probewait", "Wait for a teammate's answer (s), then spawn"},
            {"plantime", "Route search time limit (ms)"},
            {"lagtps", "Server lagging below (TPS) — plan less"}};

    private int bodyPage;
    private java.util.Map<String, Integer> defaults;       // read once per screen open

    /**
     * What "reset" goes back to. A modpack's {@code defaultconfigs/zymbot.json} (the folder that
     * default-config mods ship) wins where present; anything it lacks keeps the built-in default,
     * since Gson leaves unmentioned fields at their initial values. Read only — never written, so
     * this parses directly instead of ConfigIO.load (which saves the file back).
     */
    private int defaultOf(String key) {
        if (defaults == null) {
            ZymbotConfig d = new ZymbotConfig();
            java.nio.file.Path pack = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                    .resolve("defaultconfigs").resolve("zymbot.json");
            if (java.nio.file.Files.isRegularFile(pack)) {
                try (java.io.Reader r = java.nio.file.Files.newBufferedReader(pack, java.nio.charset.StandardCharsets.UTF_8)) {
                    ZymbotConfig read = dev.yuliang.zymbot.core.config.ConfigIO.GSON.fromJson(r, ZymbotConfig.class);
                    if (read != null) { read.normalize(); d = read; }
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger("zymbot").warn("[zymbot] can't read {} — reset uses built-in defaults: {}", pack, e.getMessage());
                }
            }
            defaults = new java.util.HashMap<>();
            for (String k : ZymbotConfig.TUNABLES.keySet()) defaults.put(k, d.tunable(k));
        }
        return defaults.get(key);
    }

    private void initBody(int cx, int top) {
        int perPage = rowsThatFit(top, 0);                 // Save sits beside Done, not below the rows
        int pages = (TUNABLE_ROWS.length + perPage - 1) / perPage;
        bodyPage = Math.min(bodyPage, pages - 1);
        // label | box (44) | range ("50–5000" is the widest, ~40 px) | reset (20): all inside cx ± 150
        int labelX = cx - 150, boxX = cx + 36, rangeX = cx + 86, resetX = cx + 130;
        int y = top;
        for (int i = bodyPage * perPage; i < Math.min(TUNABLE_ROWS.length, (bodyPage + 1) * perPage); i++) {
            String key = TUNABLE_ROWS[i][0], text = TUNABLE_ROWS[i][1];
            int[] range = ZymbotConfig.TUNABLES.get(key);
            boolean cut = font.width(text) > boxX - labelX - 6;
            String fitted = !cut ? text : font.plainSubstrByWidth(text, boxX - labelX - 6 - font.width("…")) + "…";
            label(fitted, labelX, y + 6, WHITE);
            ScrubBox box = new ScrubBox(boxX, y, text, range[0], range[1]);
            String cmd = "/" + cfg().commandRoot + " set " + key + " <n>";          // the same setting as a command
            box.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal(text + "\n§7" + cmd + "\n§7drag left/right to change (Shift fine, Ctrl coarse)")));
            box.setMaxLength(4);
            box.setFilter(t -> t.chars().allMatch(Character::isDigit));
            box.setValue(tunableDrafts.getOrDefault(key, String.valueOf(cfg().tunable(key))));
            addRenderableWidget(box);
            tunableBoxes.put(key, box);
            String rangeText = range[0] + "–" + range[1];
            label(rangeText, rangeX, y + 6, GREY);
            // the label and the range scrub the box too; the whole row height, text-wide
            scrubZones.add(new ScrubZone(box, labelX, y, font.width(fitted), ROW_H - 2, text + "\n§7" + cmd));
            scrubZones.add(new ScrubZone(box, rangeX, y, font.width(rangeText), ROW_H - 2, cmd));

            int def = defaultOf(key);
            Button reset = addRenderableWidget(Button.builder(Component.literal("↺"),
                    b -> box.setValue(String.valueOf(def)))       // draft only, like scrubbing
                    .bounds(resetX, y, 20, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.literal("reset to default (" + def + ")")))
                    .build());
            box.setResponder(v -> reset.active = !v.equals(String.valueOf(def)));
            reset.active = !box.getValue().equals(String.valueOf(def));
            y += ROW_H;
        }
        if (pages > 1) {
            int p = bodyPage;
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> { keepDrafts(); bodyPage = p - 1; rebuildWidgets(); })
                    .bounds(cx - 150, height - 28, 20, 20).build()).active = p > 0;
            label((p + 1) + "/" + pages, cx - 126, height - 22, GREY);
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> { keepDrafts(); bodyPage = p + 1; rebuildWidgets(); })
                    .bounds(cx - 106, height - 28, 20, 20).build()).active = p < pages - 1;
        }
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveTunables())
                .bounds(cx + 54, height - 28, 60, 20).build());
        contentBottom = y;
    }

    /**
     * A number box you can also scrub, like Premiere's: press and drag sideways to change the
     * value. Sensitivity: max(1, range/200) per pixel, so any range spans about 200 px; Shift is
     * fine (1 per 4 px), Ctrl coarse (×10). Moving under 3 px is a plain click: focus and type.
     * Only the draft changes — Save still applies it.
     */
    private final class ScrubBox extends EditBox {
        final Scrub scrub;
        private double pressX, pressY;

        ScrubBox(int x, int y, String name, int min, int max) {
            super(font, x, y, 44, 20, Component.literal(name));
            this.scrub = new Scrub(this, min, max);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {  // wait: a click or a scrub?
            pressX = mouseX;
            pressY = mouseY;
            scrub.press();
        }

        @Override
        protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
            scrub.drag(dragX);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            if (scrub.release()) super.onClick(pressX, pressY);  // a plain click: place the cursor
        }
    }

    /** The scrub accumulator, shared by the box itself and its row's label and range text. */
    private static final class Scrub {
        private static final int DEAD_ZONE = 3, FINE_PX = 4, COARSE = 10;
        private final EditBox box;
        private final int min, max;
        private boolean pressed, scrubbing;
        private double travel, carry;

        Scrub(EditBox box, int min, int max) {
            this.box = box;
            this.min = min;
            this.max = max;
        }

        boolean scrubbing() { return scrubbing; }

        void press() {
            pressed = true;
            scrubbing = false;
            travel = 0;
            carry = 0;
        }

        void drag(double dragX) {
            if (!pressed) return;
            travel += dragX;
            if (!scrubbing) {
                if (Math.abs(travel) < DEAD_ZONE) return;
                scrubbing = true;
                box.setTextColor(0xFFFF60);
                dragX = travel;                            // the dead zone counts too
            }
            double perPx = Screen.hasShiftDown() ? 1.0 / FINE_PX : Math.max(1, (max - min) / 200);
            if (Screen.hasControlDown()) perPx *= COARSE;
            carry += dragX * perPx;
            int step = (int) carry;                        // whole steps; the rest waits
            if (step == 0) return;
            carry -= step;
            int now;
            try { now = Integer.parseInt(box.getValue()); } catch (NumberFormatException e) { now = min; }
            box.setValue(String.valueOf(Math.max(min, Math.min(max, now + step))));
        }

        /** @return true if it was a plain click (pressed, never scrubbed). */
        boolean release() {
            boolean click = pressed && !scrubbing;
            pressed = false;
            scrubbing = false;
            box.setTextColor(0xE0E0E0);                    // EditBox's default text colour
            return click;
        }
    }

    /** A row's label or range text: dragging there scrubs its box; a plain click focuses the box. */
    private record ScrubZone(ScrubBox box, int x, int y, int w, int h, String fullText) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<ScrubZone> scrubZones = new ArrayList<>();
    private ScrubZone activeZone;

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (ScrubZone z : scrubZones) {
                if (!z.contains(mouseX, mouseY)) continue;
                activeZone = z;
                z.box().scrub.press();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (activeZone != null) {
            activeZone.box().scrub.drag(dragX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (activeZone != null && button == 0) {
            ScrubBox box = activeZone.box();
            activeZone = null;
            if (box.scrub.release()) setFocused(box);      // a plain click: focus the box to type
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // the horizontal-resize cursor over scrubbable values; always handed back in removed()
    private static long resizeCursor;
    private boolean resizeShown;

    private void updateCursor(int mouseX, int mouseY) {
        boolean want = false;
        for (EditBox b : tunableBoxes.values()) {
            if (b.isMouseOver(mouseX, mouseY) || (b instanceof ScrubBox s && s.scrub.scrubbing())) want = true;
        }
        for (ScrubZone z : scrubZones) if (z.contains(mouseX, mouseY)) want = true;
        if (activeZone != null) want = true;
        if (want == resizeShown) return;
        if (want && resizeCursor == 0) resizeCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HRESIZE_CURSOR);
        GLFW.glfwSetCursor(minecraft.getWindow().getWindow(), want ? resizeCursor : 0);
        resizeShown = want;
    }

    @Override
    public void removed() {
        if (resizeShown) GLFW.glfwSetCursor(minecraft.getWindow().getWindow(), 0);
        resizeShown = false;
        super.removed();
    }

    private void saveTunables() {
        Bot bot = ZymbotClient.get().bot();
        List<String> saved = new ArrayList<>();
        keepDrafts();                                      // edits on every page, not just this one
        List<java.util.Map.Entry<String, String>> drafts = new ArrayList<>(tunableDrafts.entrySet());
        drafts.sort(java.util.Comparator.comparing((java.util.Map.Entry<String, String> e) -> !e.getKey().equals("leash")));   // leash first: heel must fit under the new leash
        for (var e : drafts) {
            String text = e.getValue();
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
        updateCursor(mouseX, mouseY);
        if (activeZone == null) {                          // label: full name + command; range: the command
            for (ScrubZone z : scrubZones) {
                if (z.fullText() != null && z.contains(mouseX, mouseY))
                    setTooltipForNextRenderPass(Component.literal(z.fullText()));
            }
        }
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
                    "An idle bot follows its nearest teammate past the leash; never your orders.",
                    "Drag a value, name or range sideways to scrub (Shift fine, Ctrl coarse).",
                    "↺ resets to default. Then Save. Also: /" + cfg().commandRoot + " set <setting> <n>"};
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
