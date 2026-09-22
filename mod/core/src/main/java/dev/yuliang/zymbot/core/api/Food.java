package dev.yuliang.zymbot.core.api;

/**
 * What eating an item does. {@code harmful}: any possible effect is harmful (poison, hunger,
 * nausea...). {@code heals}: an effect restores health (instant health, regeneration, absorption) —
 * with natural regeneration off, these are the only way back up (SURVIVAL_EARLY_GAME §1.3).
 */
public record Food(int nutrition, float saturation, boolean canAlwaysEat, boolean harmful, boolean heals) {}
