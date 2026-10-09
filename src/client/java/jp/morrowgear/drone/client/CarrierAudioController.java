package jp.morrowgear.drone.client;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jp.morrowgear.drone.CarrierAudioPolicy;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.carrier.CarrierEntity;
import jp.morrowgear.drone.carrier.CarrierMiningEffectPolicy;
import jp.morrowgear.drone.carrier.CarrierPolicy;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Carrier-local flight and beam lifecycle audio. All selection is bounded near the listener. */
public final class CarrierAudioController {
    private static final Map<UUID, Track> TRACKS = new HashMap<>();
    private static ClientLevel level;
    private static long tick = Long.MIN_VALUE;
    private static boolean registered;

    private CarrierAudioController() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(CarrierAudioController::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear(client));
    }

    static void tick(Minecraft client) {
        if (level != client.level || client.level == null || client.player == null) {
            clear(client);
            level = client.level;
        }
        if (client.level == null || client.player == null || client.isPaused()) return;
        long now = client.level.getGameTime();
        if (now == tick) return;
        if (now < tick) clear(client);
        tick = now;
        Vec3 listener = client.getSoundManager().getListenerTransform().position();
        var carriers = client.level.getEntitiesOfClass(CarrierEntity.class,
            new AABB(listener, listener).inflate(CarrierAudioPolicy.RANGE), carrier -> carrier.isAlive() && !carrier.isSilent())
            .stream().filter(carrier -> CarrierAudioPolicy.audible(carrier.isAlive(), carrier.isSilent(),
                carrier.position().distanceToSqr(listener)))
            .sorted(Comparator.comparingDouble((CarrierEntity carrier) -> carrier.position().distanceToSqr(listener)
                * (TRACKS.containsKey(carrier.getUUID()) ? .81 : 1)).thenComparingInt(CarrierEntity::getId))
            .limit(CarrierAudioPolicy.MAX_EMITTERS).toList();
        Set<UUID> active = new HashSet<>();
        for (CarrierEntity carrier : carriers) {
            active.add(carrier.getUUID());
            TRACKS.computeIfAbsent(carrier.getUUID(), ignored -> new Track(carrier)).update(client, carrier, now);
        }
        TRACKS.entrySet().removeIf(entry -> {
            if (active.contains(entry.getKey())) return false;
            entry.getValue().stop(client);
            return true;
        });
    }

    static void clear(Minecraft client) {
        TRACKS.values().forEach(track -> track.stop(client));
        TRACKS.clear();
        level = null;
        tick = Long.MIN_VALUE;
    }

    private static final class Track {
        private final Loop idle;
        private final Loop cruise;
        private final Loop fire;
        private final Loop miningCore;
        private CarrierPolicy.WorkPhase previous = CarrierPolicy.WorkPhase.IDLE;
        private int previousPhaseTick = -1;
        private int sequence = -1;

        private Track(CarrierEntity carrier) {
            idle = new Loop(carrier, MorrowgearDrone.CARRIER_FLIGHT_IDLE_SOUND);
            cruise = new Loop(carrier, MorrowgearDrone.CARRIER_FLIGHT_CRUISE_SOUND);
            fire = new Loop(carrier, MorrowgearDrone.CARRIER_LASER_FIRE_SOUND);
            miningCore = new Loop(carrier, MorrowgearDrone.CARRIER_MINING_CORE_SOUND);
        }

        private void update(Minecraft client, CarrierEntity carrier, long now) {
            CarrierAudioPolicy.FlightMix mix = CarrierAudioPolicy.flightMix(carrier.getDeltaMovement().length());
            idle.update(client, mix.idle(), mix.pitch(), now);
            cruise.update(client, mix.cruise(), mix.pitch(), now);
            CarrierPolicy.WorkPhase phase = carrier.workPhase();
            boolean mining = carrier.workMode() == CarrierPolicy.Mode.MINING;
            if (!mining && CarrierAudioPolicy.chargeStarted(previous, phase))
                play(client, carrier.position(), MorrowgearDrone.CARRIER_LASER_CHARGE_SOUND, .52f, .92f, 70);
            if (mining && phase == CarrierPolicy.WorkPhase.CHARGE) {
                int active = CarrierMiningEffectPolicy.visual(phase, carrier.phaseTick(), carrier.phaseDuration()).activeEmitters();
                int previousActive = previous == phase && previousPhaseTick >= 0
                    ? CarrierMiningEffectPolicy.visual(phase, previousPhaseTick, carrier.phaseDuration()).activeEmitters() : 0;
                if (active > previousActive)
                    play(client, carrier.position(), MorrowgearDrone.CARRIER_MINING_EMITTER_SOUND,
                        .34f, .84f + active * .009f, 18);
                float coreVolume = (float)(.12 + .38 * Math.clamp(
                    (carrier.phaseTick() + 1.0) / CarrierPolicy.MINING_CHARGE_TICKS, 0, 1));
                miningCore.update(client, coreVolume, .86f + .08f * coreVolume, now);
            } else if (mining && phase == CarrierPolicy.WorkPhase.FIRE
                && carrier.phaseTick() < CarrierMiningEffectPolicy.RELEASE_TICKS) {
                miningCore.update(client, .48f, .96f, now);
            } else miningCore.stop(client);
            if (mining && phase == CarrierPolicy.WorkPhase.FIRE && previous != phase)
                play(client, carrier.beamPath().origin(), MorrowgearDrone.CARRIER_MINING_RELEASE_SOUND, .58f, .94f,
                    CarrierMiningEffectPolicy.RELEASE_TICKS + 8);
            if (mining && phase == CarrierPolicy.WorkPhase.FIRE
                && previousPhaseTick < CarrierMiningEffectPolicy.RELEASE_TICKS
                && carrier.phaseTick() >= CarrierMiningEffectPolicy.RELEASE_TICKS)
                play(client, carrier.beamPath().target(), MorrowgearDrone.CARRIER_MINING_IMPACT_SOUND, .68f, .9f, 100);
            if (!mining && CarrierAudioPolicy.fireActive(phase, carrier.beamActive())) fire.update(client, .58f, .94f, now);
            else fire.stop(client);
            if (!mining && CarrierAudioPolicy.fireActive(phase, carrier.beamActive())
                && CarrierAudioPolicy.hitStarted(sequence, carrier.beamSequence())) {
                Vec3 impact = carrier.beamPath().target();
                play(client, impact, MorrowgearDrone.CARRIER_LASER_HIT_SOUND, .56f, .9f, 18);
            }
            if (!mining && CarrierAudioPolicy.cooldownStarted(previous, phase))
                play(client, carrier.beamPath().origin(), MorrowgearDrone.CARRIER_LASER_COOLDOWN_SOUND, .48f, .9f, 58);
            previous = phase;
            previousPhaseTick = carrier.phaseTick();
            sequence = carrier.beamSequence();
        }

        private void stop(Minecraft client) {
            idle.stop(client);
            cruise.stop(client);
            fire.stop(client);
            miningCore.stop(client);
        }
    }

    private static void play(Minecraft client, Vec3 position, SoundEvent event, float volume, float pitch, int duration) {
        client.getSoundManager().play(new FixedSound(event, position, volume, pitch,
            client.level == null ? 0 : client.level.getGameTime() + duration));
    }

    private static final class Loop {
        private final CarrierEntity carrier;
        private final SoundEvent event;
        private FollowingSound sound;
        private long retryAt = Long.MIN_VALUE;

        private Loop(CarrierEntity carrier, SoundEvent event) { this.carrier = carrier; this.event = event; }

        private void update(Minecraft client, float volume, float pitch, long now) {
            if ((sound == null || !client.getSoundManager().isActive(sound)) && now >= retryAt) {
                stop(client);
                sound = new FollowingSound(carrier, event);
                sound.target(volume, pitch);
                client.getSoundManager().play(sound);
                retryAt = now + CarrierAudioPolicy.RETRY_TICKS;
            }
            if (sound != null) sound.target(volume, pitch);
        }

        private void stop(Minecraft client) {
            if (sound != null) {
                sound.finish();
                client.getSoundManager().stop(sound);
            }
            sound = null;
            retryAt = Long.MIN_VALUE;
        }
    }

    private static final class FollowingSound extends AbstractTickableSoundInstance {
        private final CarrierEntity carrier;
        private float targetVolume;
        private float targetPitch = 1;

        private FollowingSound(CarrierEntity carrier, SoundEvent event) {
            super(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.carrier = carrier;
            looping = true;
            delay = 0;
            volume = 0;
            relative = false;
            attenuation = SoundInstance.Attenuation.LINEAR;
            position();
        }

        private void target(float volume, float pitch) {
            targetVolume = Math.clamp(volume, 0, .7f);
            targetPitch = Math.clamp(pitch, .75f, 1.05f);
        }

        @Override public boolean canStartSilent() { return true; }

        @Override public void tick() {
            Minecraft client = Minecraft.getInstance();
            if (client.level != carrier.level() || !carrier.isAlive() || carrier.isSilent()) { stop(); return; }
            position();
            volume += Math.clamp(targetVolume - volume, -.04f, .04f);
            pitch += Math.clamp(targetPitch - pitch, -.01f, .01f);
        }

        private void position() {
            x = carrier.getX(); y = carrier.getY() + 5; z = carrier.getZ();
        }
        private void finish() { stop(); }
    }

    private static final class FixedSound extends AbstractTickableSoundInstance {
        private final long expires;

        private FixedSound(SoundEvent event, Vec3 position, float volume, float pitch, long expires) {
            super(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.expires = expires;
            this.x = position.x; this.y = position.y; this.z = position.z;
            this.volume = volume; this.pitch = pitch;
            looping = false;
            relative = false;
            attenuation = SoundInstance.Attenuation.LINEAR;
        }

        @Override public void tick() {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.level.getGameTime() > expires) stop();
        }
    }
}
