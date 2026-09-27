package dev.yuliang.zymbot.core.api;

/** One block the survey scan picked out: where, and its registry id. */
public record BlockHit(BlockPos pos, String id) {}
