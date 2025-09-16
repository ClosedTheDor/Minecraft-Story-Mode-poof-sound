package com.example.storymodepoof;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * PoofMod - improved timing:
 *  - play on AreaEffectCloudEntity spawn when particle is a poof/smoke
 *  - fallback only after a longer delay (so cloud has time to spawn)
 *  - radius and delays tuned to avoid early playing
 */
public class PoofMod implements ModInitializer {
    public static final String MODID = "storymodepoof";

    public static final Identifier POOF1_ID = new Identifier(MODID, "poof1");
    public static final Identifier POOF2_ID = new Identifier(MODID, "poof2");
    public static final Identifier POOF3_ID = new Identifier(MODID, "poof3");

    public static final SoundEvent POOF1_SOUND = SoundEvent.of(POOF1_ID);
    public static final SoundEvent POOF2_SOUND = SoundEvent.of(POOF2_ID);
    public static final SoundEvent POOF3_SOUND = SoundEvent.of(POOF3_ID);

    private static final Random RANDOM = new Random();

    // tuning constants
    private static final int FALLBACK_DELAY_TICKS = 20;   // wait this many ticks before fallback
    private static final int STALE_LIMIT_TICKS = 80;     // drop pending entries after this many ticks
    private static final double MATCH_RADIUS = 5.5;     // matching radius in blocks for cloud -> death pos

    private static class Pending {
        final ServerWorld world;
        final BlockPos pos;
        final long tickRecorded;
        boolean played = false;

        Pending(ServerWorld world, BlockPos pos, long tickRecorded) {
            this.world = world;
            this.pos = pos;
            this.tickRecorded = tickRecorded;
        }
    }

    private final List<Pending> pending = new LinkedList<>();

    @Override
    public void onInitialize() {
        // Register sound events
        Registry.register(Registries.SOUND_EVENT, POOF1_ID, POOF1_SOUND);
        Registry.register(Registries.SOUND_EVENT, POOF2_ID, POOF2_SOUND);
        Registry.register(Registries.SOUND_EVENT, POOF3_ID, POOF3_SOUND);

        // On death: record pos + tick so we can match a later cloud
        ServerLivingEntityEvents.AFTER_DEATH.register((LivingEntity entity, net.minecraft.entity.damage.DamageSource source) -> {
            if (entity == null) return;
            if (!(entity.getWorld() instanceof ServerWorld serverWorld)) return;

            BlockPos pos = entity.getBlockPos();
            long tick = serverWorld.getServer().getTicks();

            synchronized (pending) {
                pending.add(new Pending(serverWorld, pos, tick));
            }
        });

        // Prefer: when any entity loads, if it's an AreaEffectCloudEntity with a poof-like particle,
        // attempt to match it to a recent death and play immediately.
        ServerEntityEvents.ENTITY_LOAD.register((Entity entity, ServerWorld serverWorld) -> {
            if (!(entity instanceof AreaEffectCloudEntity cloud)) return;

            ParticleEffect particle = cloud.getParticleType();
            boolean isPoofParticle = particle.equals(ParticleTypes.POOF)
                    || particle.equals(ParticleTypes.CLOUD)
                    || particle.equals(ParticleTypes.LARGE_SMOKE)
                    || particle.equals(ParticleTypes.SMOKE);

            if (!isPoofParticle) return;

            BlockPos cloudPos = cloud.getBlockPos();

            synchronized (pending) {
                Iterator<Pending> it = pending.iterator();
                while (it.hasNext()) {
                    Pending p = it.next();

                    if (p.world != serverWorld) continue;

                    long now = serverWorld.getServer().getTicks();
                    if (now - p.tickRecorded > STALE_LIMIT_TICKS) {
                        it.remove();
                        continue;
                    }

                    double dx = cloudPos.getX() - p.pos.getX();
                    double dy = cloudPos.getY() - p.pos.getY();
                    double dz = cloudPos.getZ() - p.pos.getZ();
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq <= MATCH_RADIUS * MATCH_RADIUS) {
                        // Matched: play immediately and remove pending marker
                        playRandomPoof(serverWorld, cloudPos);
                        it.remove();
                        return;
                    }
                }
            }
        });

        // Tick handler: do fallback only after a slightly longer delay so the cloud has time to spawn.
        ServerTickEvents.END_WORLD_TICK.register((ServerWorld serverWorld) -> {
            long now = serverWorld.getServer().getTicks();

            synchronized (pending) {
                Iterator<Pending> it = pending.iterator();
                while (it.hasNext()) {
                    Pending p = it.next();

                    if (p.world != serverWorld) continue;

                    // Remove stale entries
                    if (now - p.tickRecorded > STALE_LIMIT_TICKS) {
                        it.remove();
                        continue;
                    }

                    // Only consider fallback once enough ticks passed
                    if (!p.played && now - p.tickRecorded >= FALLBACK_DELAY_TICKS) {
                        // Last-ditch scan for cloud first (if it exists, entity-load path would've caught it earlier)
                        Box searchBox = new Box(p.pos).expand(MATCH_RADIUS);
                        boolean foundCloud = serverWorld.getEntitiesByClass(
                                AreaEffectCloudEntity.class,
                                searchBox,
                                cloud -> {
                                    ParticleEffect particle = cloud.getParticleType();
                                    return particle.equals(ParticleTypes.POOF)
                                            || particle.equals(ParticleTypes.CLOUD)
                                            || particle.equals(ParticleTypes.LARGE_SMOKE)
                                            || particle.equals(ParticleTypes.SMOKE);
                                }
                        ).stream().findAny().isPresent();

                        if (foundCloud) {
                            it.remove();
                            continue;
                        }

                        // Play fallback (delayed) sound at the recorded death position
                        playRandomPoof(serverWorld, p.pos);
                        p.played = true;
                        // keep entry for a while so duplicates don't happen; stale removals will clean it up
                    }
                }
            }
        });
    }

    private void playRandomPoof(ServerWorld serverWorld, BlockPos pos) {
        SoundEvent[] sounds = {POOF1_SOUND, POOF2_SOUND, POOF3_SOUND};
        SoundEvent chosen = sounds[RANDOM.nextInt(sounds.length)];

        if (!serverWorld.isClient()) {
            serverWorld.playSound(
                    /* player */ null,
                    pos,
                    chosen,
                    SoundCategory.PLAYERS,
                    1.0f,
                    1.0f
            );
        }
    }
}
