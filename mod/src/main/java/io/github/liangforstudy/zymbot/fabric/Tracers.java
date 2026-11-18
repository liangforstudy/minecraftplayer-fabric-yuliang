package io.github.liangforstudy.zymbot.fabric;

import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.trace.TracerPicker;
import io.github.liangforstudy.zymbot.core.trace.TracerPicker.Kind;
import io.github.liangforstudy.zymbot.core.trace.TracerPicker.Target;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Issue #7: lines on screen from the crosshair to players and to the ticked blocks, dropped items and
 * entities (the Features tab), at most {@code tracelimit} (20) at once with players first. Drawn with the
 * game's own gizmos through walls, as Chest Tracker's tracers are (the reference; liangforstudy/Chest_Tracker).
 * Client-side only; nothing is sent.
 * <p>Blocks are found by a slow sweep: a few loaded chunks per tick around the player, sections whose
 * palette can't hold a ticked block skipped, results kept per chunk. Entities and items are read each frame.
 * <p>26.2 only (gizmos and the frame extractor); the paused versions compile to nothing.
 */
final class Tracers {
    private static final int PLAYER = 0xFFFF5050, ENTITY = 0xFFFFC040, ITEM = 0xFF40E0F0, BLOCK = 0xFFFFFFFF;
    private static final int CHUNKS_PER_TICK = 4;
    private static final double LABEL_SCALE_PER_BLOCK = 0.06;

    private static final Map<Long, List<BlockPos>> found = new HashMap<>();
    private static final ArrayDeque<Long> queue = new ArrayDeque<>();
    private static Set<Block> blocks = Set.of();
    private static List<String> blocksFor = List.of();
    private static Object levelFor;

    private Tracers() {}

    private static ZymbotConfig cfg() { return ZymbotClient.get().config(); }

    /** Main thread, each client tick: the block sweep. */
    static void tick(Minecraft mc) {
        ZymbotConfig c = cfg();
        if (!c.tracers || mc.level == null || mc.player == null || c.traceBlocks.isEmpty()) {
            found.clear();
            queue.clear();
            return;
        }
        if (mc.level != levelFor || !c.traceBlocks.equals(blocksFor)) {      // a new world, or new blocks ticked
            levelFor = mc.level;
            blocksFor = List.copyOf(c.traceBlocks);
            Set<Block> want = new HashSet<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                if (blocksFor.contains(BuiltInRegistries.BLOCK.getKey(b).toString())) want.add(b);
            }
            blocks = want;
            found.clear();
            queue.clear();
        }
        int radius = Math.max(2, mc.options.getEffectiveRenderDistance());
        int cx = mc.player.blockPosition().getX() >> 4, cz = mc.player.blockPosition().getZ() >> 4;
        if (queue.isEmpty()) {                                    // a new sweep, nearest chunks first
            found.keySet().removeIf(k -> Math.abs(chunkX(k) - cx) > radius || Math.abs(chunkZ(k) - cz) > radius);
            for (int r = 0; r <= radius; r++) {
                for (int x = -r; x <= r; x++) {
                    for (int z = -r; z <= r; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) == r) queue.add(key(cx + x, cz + z));
                    }
                }
            }
        }
        for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) scan(mc, queue.poll());
    }

    private static void scan(Minecraft mc, long k) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(chunkX(k), chunkZ(k), false);
        if (chunk == null) {
            found.remove(k);
            return;
        }
        List<BlockPos> hits = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSectionY();
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(st -> blocks.contains(st.getBlock()))) continue;
            int baseY = (minSection + s) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (blocks.contains(section.getBlockState(x, y, z).getBlock())) {
                            hits.add(new BlockPos((chunkX(k) << 4) + x, baseY + y, (chunkZ(k) << 4) + z));
                        }
                    }
                }
            }
        }
        if (hits.isEmpty()) found.remove(k);
        else found.put(k, hits);
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    private static int chunkX(long k) { return (int) (k >> 32); }
    private static int chunkZ(long k) { return (int) k; }

    /** What a line goes to: its end, an outline, a label. */
    private record Line(Vec3 end, AABB box, String label, int color) {}

    /** This frame's targets, players first, at most the limit. */
    private static List<Target<Line>> targets(Minecraft mc, Vec3 eye, float partialTick) {
        ZymbotConfig c = cfg();
        List<Target<Line>> all = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e.isRemoved()) continue;
            Kind kind;
            String label;
            int color;
            if (e instanceof Player p) {
                if (!c.tracePlayers) continue;
                kind = Kind.PLAYER;
                label = p.getName().getString();
                color = PLAYER;
            } else if (e instanceof ItemEntity item) {
                String id = BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString();
                if (!c.traceItems.contains(id)) continue;
                kind = Kind.ITEM;
                label = item.getItem().getHoverName().getString() + (item.getItem().getCount() > 1 ? " ×" + item.getItem().getCount() : "");
                color = ITEM;
            } else {
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                if (!c.traceEntities.contains(id)) continue;
                kind = Kind.ENTITY;
                label = e.getName().getString();
                color = ENTITY;
            }
            Vec3 at = e.getPosition(partialTick);
            AABB box = e.getBoundingBox().move(at.subtract(e.position()));
            Vec3 end = at.add(0, e.getBbHeight() / 2, 0);
            all.add(new Target<>(kind, end.distanceTo(eye), new Line(end, box, label, color)));
        }
        for (List<BlockPos> list : found.values()) {
            for (BlockPos p : list) {
                AABB box = new AABB(p);
                Vec3 end = box.getCenter();
                String label = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).getPath().replace('_', ' ');
                all.add(new Target<>(Kind.BLOCK, end.distanceTo(eye), new Line(end, box, label, BLOCK)));
            }
        }
        return TracerPicker.pick(all, c.traceLimit);
    }

    /** Each frame, right after the camera is placed (mixin.GameRendererTracerMixin). */
    static void emit(Minecraft mc, float partialTick) {
        //? if >=26.2 {
        /*if (!cfg().tracers || mc.level == null || mc.player == null) return;
        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return;
        Vec3 eye = camera.position();
        Vec3 start = crosshair(mc, camera, eye);
        List<Target<Line>> lines = targets(mc, eye, partialTick);
        if (lines.isEmpty()) return;
        try (var collection = mc.levelExtractor.collectPerFrameMainThreadGizmos()) {
            for (Target<Line> t : lines) draw(t, start);
        }
        *///?}
    }

    //? if >=26.2 {
    /*// The point drawn exactly under the crosshair: the game's walking bob and hurt shake undone (as Chest Tracker does).
    private static Vec3 crosshair(Minecraft mc, net.minecraft.client.Camera camera, Vec3 eye) {
        var renderer = (io.github.liangforstudy.zymbot.fabric.mixin.TracerRendererAccessor) mc.gameRenderer;
        com.mojang.blaze3d.vertex.PoseStack bob = new com.mojang.blaze3d.vertex.PoseStack();
        var state = renderer.zymbot$gameRenderState().levelRenderState.cameraRenderState;
        renderer.zymbot$bobHurt(state, bob);
        if (mc.options.bobView().get()) renderer.zymbot$bobView(state, bob);
        org.joml.Vector3f view = new org.joml.Matrix4f(bob.last().pose()).invert().transformPosition(new org.joml.Vector3f(0, 0, -0.5f));
        camera.rotation().transform(view);
        return eye.add(view.x(), view.y(), view.z());
    }

    private static void draw(Target<Line> t, Vec3 start) {
        Line l = t.what();
        net.minecraft.gizmos.Gizmos.line(start, l.end(), l.color(), 2f).setAlwaysOnTop();
        net.minecraft.gizmos.Gizmos.cuboid(l.box().inflate(0.02), net.minecraft.gizmos.GizmoStyle.stroke(l.color(), 2f)).setAlwaysOnTop();
        String text = l.label() + " · " + Math.round(t.distance()) + " m";
        float scale = (float) Math.max(net.minecraft.gizmos.TextGizmo.Style.DEFAULT_SCALE, t.distance() * LABEL_SCALE_PER_BLOCK);
        Vec3 labelAt = l.end().add(0, l.box().getYsize() / 2 + 0.3 * scale, 0);
        net.minecraft.gizmos.Gizmos.billboardText(text, labelAt,
                net.minecraft.gizmos.TextGizmo.Style.forColorAndCentered(l.color()).withScale(scale)).setAlwaysOnTop();
    }
    *///?}
}
