package dev.yuliang.cas.mixin;

import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cobblemon's animation clock is age/20 seconds, one tick per tick regardless of how fast the
 * entity moves. This keeps a parallel clock that advances by the entity's speed multiplier
 * (current / base movement speed) while it is moving, and serves that from getAnimationSeconds,
 * which both looping pose animations and one-shot animations read.
 */
@Mixin(value = PosableState.class, remap = false)
public abstract class PosableStateMixin {
    @Unique private static final float MIN_RATE = 0.25f;
    @Unique private static final float MAX_RATE = 4.0f;
    // Species whose animations have been checked against this fix; others keep Cobblemon's clock.
    @Unique private static final java.util.Set<String> SPECIES = java.util.Set.of("quaquaval");

    @Unique
    private static String cas$species(PokemonEntity pokemon) {
        try {
            return pokemon.getPokemon().getSpecies().getName().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Shadow public abstract float getPartialTicks();
    @Shadow protected abstract int getAge();

    @Unique private boolean cas$started;
    @Unique private float cas$ticks;
    @Unique private float cas$rate = 1.0f;

    @Inject(method = "incrementAge", at = @At("HEAD"))
    private void cas$advance(Entity entity, CallbackInfo ci) {
        if (!cas$started) {
            cas$started = true;
            cas$ticks = getAge();
        }
        cas$ticks += cas$rate;
        cas$rate = cas$rateFor(entity);
    }

    @Inject(method = "getAnimationSeconds", at = @At("HEAD"), cancellable = true)
    private void cas$seconds(CallbackInfoReturnable<Float> cir) {
        if (cas$started) cir.setReturnValue((cas$ticks + getPartialTicks() * cas$rate) / 20f);
    }

    @Unique
    private static float cas$rateFor(Entity entity) {
        if (!(entity instanceof PokemonEntity pokemon) || !SPECIES.contains(cas$species(pokemon))) return 1.0f;
        LivingEntity driver = cas$driver(entity);
        if (driver == null) return 1.0f;
        double dx = driver.getX() - driver.prevX, dz = driver.getZ() - driver.prevZ;
        if (dx * dx + dz * dz < 1.0e-5) return 1.0f; // standing still: idle at normal speed
        EntityAttributeInstance speed = driver.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed == null || speed.getBaseValue() <= 0) return 1.0f;
        float rate = (float) (speed.getValue() / speed.getBaseValue());
        return Math.max(MIN_RATE, Math.min(MAX_RATE, rate));
    }

    /**
     * The entity whose speed drives the animation. A Pokemon that isn't in the world is a render
     * stand-in (Synchro Machine's morph); it sits on the morphed player, so that player drives it.
     */
    @Unique
    private static LivingEntity cas$driver(Entity entity) {
        if (entity instanceof PokemonEntity pokemon && entity.getWorld() != null
                && entity.getWorld().getEntityById(entity.getId()) != entity) {
            PlayerEntity closest = null;
            double best = 1.0;
            for (PlayerEntity player : entity.getWorld().getPlayers()) {
                double d = player.squaredDistanceTo(pokemon);
                if (d < best) { best = d; closest = player; }
            }
            return closest;
        }
        return entity instanceof LivingEntity living ? living : null;
    }
}
