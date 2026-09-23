package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jp.morrowgear.drone.CombatState;
import jp.morrowgear.drone.DroneAudioPolicy;
import jp.morrowgear.drone.DroneAudioSounds;
import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.MorrowgearMissileEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Owns moving engine and weapon loops; server events retain impacts and explosions. */
public final class DroneAudioController {
	private static final Map<UUID, Flight> FLIGHTS = new HashMap<>();
	private static final Map<UUID, Laser> LASERS = new HashMap<>();
	private static final Map<UUID, Cannon> CANNONS = new HashMap<>();
	private static final Map<UUID, Loop> MOTORS = new HashMap<>();
	private static final List<FollowingSound> TAILS = new ArrayList<>();
	private static ClientLevel world;
	private static long lastTick = Long.MIN_VALUE;
	private static boolean registered;

	private DroneAudioController() {}

	public static void register() {
		if (registered) return;
		registered = true;
		ClientTickEvents.END_CLIENT_TICK.register(DroneAudioController::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear(client));
	}

	public static void tick(Minecraft client) {
		if (world != client.level || client.level == null || client.player == null) {
			clear(client);
			world = client.level;
		}
		if (client.level == null || client.player == null || client.isPaused()) return;
		long now = client.level.getGameTime();
		if (now == lastTick) return;
		if (now < lastTick) clear(client);
		world = client.level;
		lastTick = now;
		Vec3 listener = client.getSoundManager().getListenerTransform().position();
		List<DroneEntity> nearby = client.level.getEntitiesOfClass(DroneEntity.class,
			new AABB(listener, listener).inflate(DroneAudioPolicy.FLIGHT_RANGE),
			drone -> DroneAudioPolicy.flightActive(drone.isAlive(), drone.isDocked(),
				drone.isPowerLost(), drone.isSilent()));
		List<DroneEntity> flying = nearest(nearby, listener, DroneAudioPolicy.FLIGHT_RANGE,
			DroneAudioPolicy.MAX_FLIGHT_EMITTERS, FLIGHTS.keySet());
		Set<UUID> selected = new HashSet<>();
		for (DroneEntity drone : flying) {
			selected.add(drone.getUUID());
			FLIGHTS.computeIfAbsent(drone.getUUID(), ignored -> new Flight(drone))
				.update(client, drone, flying.size(), now);
		}
		FLIGHTS.entrySet().removeIf(entry -> {
			if (selected.contains(entry.getKey())) return false;
			entry.getValue().stop(client);
			return true;
		});

		List<DroneEntity> lensCandidates = nearby.stream().filter(drone ->
			drone.combatState() == CombatState.LASER_CHARGE || drone.combatState() == CombatState.LASER_FIRE
				|| LASERS.containsKey(drone.getUUID())).toList();
		List<DroneEntity> lenses = nearest(lensCandidates, listener, DroneAudioPolicy.LASER_RANGE,
			DroneAudioPolicy.MAX_LASER_EMITTERS, LASERS.keySet());
		Set<UUID> lensIds = new HashSet<>();
		for (DroneEntity drone : lenses) {
			lensIds.add(drone.getUUID());
			LASERS.computeIfAbsent(drone.getUUID(), ignored -> new Laser(drone))
				.update(client, drone, lenses.size(), now);
		}
		LASERS.entrySet().removeIf(entry -> {
			if (lensIds.contains(entry.getKey()) && entry.getValue().engaged()) return false;
			entry.getValue().stop(client);
			return true;
		});
		updateCannons(client, nearby, listener, now);
		updateMotors(client, listener, now);
		TAILS.removeIf(sound -> {
			if (!sound.isStopped() && now <= sound.expires && sound.entity.isAlive()) return false;
			stop(client, sound);
			return true;
		});
	}

	private static void updateCannons(Minecraft client, List<DroneEntity> nearby, Vec3 listener, long now) {
		List<DroneEntity> candidates = nearby.stream().filter(drone ->
			(drone.combatState() == CombatState.GUN_RUN && DroneAudioPolicy.freshEvent(now, drone.combatShotTick()))
				|| CANNONS.containsKey(drone.getUUID())).toList();
		List<DroneEntity> emitters = nearest(candidates, listener, DroneAudioPolicy.WEAPON_RANGE,
			DroneAudioPolicy.MAX_CANNON_EMITTERS, CANNONS.keySet());
		Set<UUID> ids = new HashSet<>();
		int firing = (int)emitters.stream().filter(drone -> drone.combatState() == CombatState.GUN_RUN
			&& DroneAudioPolicy.freshEvent(now, drone.combatShotTick())).count();
		for (DroneEntity drone : emitters) {
			ids.add(drone.getUUID());
			CANNONS.computeIfAbsent(drone.getUUID(), ignored -> new Cannon(drone))
				.update(client, drone, firing, now);
		}
		CANNONS.entrySet().removeIf(entry -> {
			if (ids.contains(entry.getKey()) && entry.getValue().playing) return false;
			entry.getValue().fire.stop(client);
			return true;
		});
	}

	private static void updateMotors(Minecraft client, Vec3 listener, long now) {
		List<MorrowgearMissileEntity> emitters = client.level.getEntitiesOfClass(MorrowgearMissileEntity.class,
			new AABB(listener, listener).inflate(DroneAudioPolicy.WEAPON_RANGE), DroneAudioController::audible)
			.stream().filter(missile -> missile.position().distanceToSqr(listener)
				< DroneAudioPolicy.WEAPON_RANGE * DroneAudioPolicy.WEAPON_RANGE)
			.sorted(Comparator.comparingDouble((MorrowgearMissileEntity missile) ->
				missile.position().distanceToSqr(listener) * (MOTORS.containsKey(missile.getUUID()) ? .81 : 1))
				.thenComparingInt(Entity::getId)).limit(DroneAudioPolicy.MAX_MOTOR_EMITTERS).toList();
		Set<UUID> ids = new HashSet<>();
		for (MorrowgearMissileEntity missile : emitters) {
			ids.add(missile.getUUID());
			MOTORS.computeIfAbsent(missile.getUUID(), ignored -> new Loop(missile, MorrowgearDrone.MISSILE_MOTOR_SOUND))
				.update(client, .28f * DroneAudioPolicy.fleetGain(emitters.size()), 1.0f, now);
		}
		MOTORS.entrySet().removeIf(entry -> {
			if (ids.contains(entry.getKey())) return false;
			entry.getValue().stop(client);
			return true;
		});
	}

	private static boolean audible(Entity entity) {
		if (entity instanceof DroneEntity drone) return DroneAudioPolicy.flightActive(drone.isAlive(),
			drone.isDocked(), drone.isPowerLost(), drone.isSilent());
		if (entity instanceof MorrowgearMissileEntity missile) return DroneAudioPolicy.missileMotorActive(
			missile.isAlive(), missile.isSilent(), missile.motorIgnited(), missile.impacted());
		return false;
	}

	private static void tail(Minecraft client, FollowingSound sound) {
		while (TAILS.size() >= DroneAudioPolicy.MAX_TAILS) stop(client, TAILS.removeFirst());
		TAILS.add(sound);
	}

	private static List<DroneEntity> nearest(List<DroneEntity> candidates, Vec3 listener,
		float range, int limit, Set<UUID> retained) {
		return candidates.stream().filter(drone -> drone.position().distanceToSqr(listener) < range * range)
			.sorted(Comparator.comparingDouble((DroneEntity drone) -> drone.position().distanceToSqr(listener)
				* (retained.contains(drone.getUUID()) ? 0.81 : 1.0)).thenComparingInt(DroneEntity::getId))
			.limit(limit).toList();
	}

	public static void clear(Minecraft client) {
		FLIGHTS.values().forEach(flight -> flight.stop(client));
		LASERS.values().forEach(laser -> laser.stop(client));
		CANNONS.values().forEach(cannon -> cannon.fire.stop(client));
		MOTORS.values().forEach(motor -> motor.stop(client));
		TAILS.forEach(sound -> stop(client, sound));
		FLIGHTS.clear();
		LASERS.clear();
		CANNONS.clear();
		MOTORS.clear();
		TAILS.clear();
		world = null;
		lastTick = Long.MIN_VALUE;
	}

	private static void stop(Minecraft client, FollowingSound sound) {
		if (sound == null) return;
		sound.finish();
		client.getSoundManager().stop(sound);
	}

	private static FollowingSound oneShot(Minecraft client, DroneEntity drone, SoundEvent event,
		float volume, int ticks, long now) {
		FollowingSound sound = new FollowingSound(drone, event, false, now + ticks);
		sound.target(volume, 1.0f);
		client.getSoundManager().play(sound);
		return sound;
	}

	private static final class Loop {
		private final Entity drone;
		private final SoundEvent event;
		private FollowingSound sound;
		private long retryAt = Long.MIN_VALUE;

		private Loop(Entity drone, SoundEvent event) { this.drone = drone; this.event = event; }

		private void update(Minecraft client, float volume, float pitch, long now) {
			if ((sound == null || !client.getSoundManager().isActive(sound)) && now >= retryAt) {
				stop(client);
				sound = new FollowingSound(drone, event, true, Long.MAX_VALUE);
				sound.target(volume, pitch);
				client.getSoundManager().play(sound);
				retryAt = now + DroneAudioPolicy.RETRY_TICKS;
			}
			if (sound != null) sound.target(volume, pitch);
		}

		private void stop(Minecraft client) {
			DroneAudioController.stop(client, sound);
			sound = null;
			retryAt = Long.MIN_VALUE;
		}
	}

	private static final class Flight {
		private final Loop idle, cruise;
		private Flight(DroneEntity drone) {
			idle = new Loop(drone, MorrowgearDrone.FLIGHT_IDLE_SOUND);
			cruise = new Loop(drone, MorrowgearDrone.FLIGHT_CRUISE_SOUND);
		}
		private void update(Minecraft client, DroneEntity drone, int count, long now) {
			DroneAudioPolicy.FlightMix mix = DroneAudioPolicy.flightMix(drone.getDeltaMovement().length(), count);
			float detune = 0.995f + Math.floorMod(drone.getUUID().hashCode(), 11) * 0.001f;
			idle.update(client, mix.idle(), mix.pitch() * detune, now);
			cruise.update(client, mix.cruise(), mix.pitch() * detune, now);
		}
		private void stop(Minecraft client) { idle.stop(client); cruise.stop(client); }
	}

	private static final class Laser {
		private final Loop beam;
		private FollowingSound charge;
		private CombatState previous;
		private Laser(DroneEntity drone) { beam = new Loop(drone, MorrowgearDrone.LASER_FIRE_SOUND); }

		private void update(Minecraft client, DroneEntity drone, int count, long now) {
			CombatState state = drone.combatState();
			float gain = DroneAudioPolicy.fleetGain(count);
			if (DroneAudioPolicy.coolingStarted(previous, state)) {
				// Cooling also runs on FIRE -> CHARGE, independently of the new charge.
				tail(client, oneShot(client, drone, MorrowgearDrone.LASER_SHUTDOWN_SOUND, 0.30f * gain, 46, now));
			}
			if (state != CombatState.LASER_CHARGE) {
				DroneAudioController.stop(client, charge);
				charge = null;
			}
			if (DroneAudioPolicy.chargeStarted(previous, state, now, drone.combatStateTick())) {
				charge = oneShot(client, drone, MorrowgearDrone.LASER_CHARGE_SOUND, 0.32f * gain, 56, now);
			}
			if (DroneAudioPolicy.dischargeStarted(previous, state, now, drone.combatStateTick())) {
				tail(client, oneShot(client, drone, DroneAudioSounds.LASER_DISCHARGE, 0.40f * gain, 10, now));
			}
			if (charge != null) charge.target(0.32f * gain, 1.0f);
			if (state == CombatState.LASER_FIRE) beam.update(client, 0.26f * gain, 1.0f, now);
			else beam.stop(client);
			previous = state;
		}

		private boolean engaged() {
			return previous == CombatState.LASER_CHARGE || previous == CombatState.LASER_FIRE;
		}
		private void stop(Minecraft client) {
			beam.stop(client);
			DroneAudioController.stop(client, charge);
		}
	}

	private static final class Cannon {
		private final Loop fire;
		private boolean playing;
		private final DroneAudioPolicy.CannonGate gate = new DroneAudioPolicy.CannonGate();
		private Cannon(DroneEntity drone) { fire = new Loop(drone, MorrowgearDrone.AUTOCANNON_FIRE_SOUND); }
		private void update(Minecraft client, DroneEntity drone, int count, long now) {
			var change = gate.update(drone.combatState(), now, drone.combatShotTick());
			playing = change.playing();
			float gain = DroneAudioPolicy.fleetGain(count);
			if (change.started()) tail(client, oneShot(client, drone, MorrowgearDrone.AUTOCANNON_START_SOUND,
				.40f * gain, 4, now));
			if (change.playing()) fire.update(client, .42f * gain, 1.0f, now);
			else fire.stop(client);
			if (change.stopped()) tail(client, oneShot(client, drone, MorrowgearDrone.AUTOCANNON_STOP_SOUND,
				.30f * gain, 9, now));
		}
	}

	private static final class FollowingSound extends AbstractTickableSoundInstance {
		private final Entity entity;
		private final long expires;
		private final boolean fastAttack;
		private float targetVolume, targetPitch = 1.0f;

		private FollowingSound(Entity entity, SoundEvent event, boolean loop, long expires) {
			super(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
			this.entity = entity;
			this.expires = expires;
			fastAttack = event == MorrowgearDrone.AUTOCANNON_FIRE_SOUND || event == MorrowgearDrone.MISSILE_MOTOR_SOUND;
			looping = loop;
			delay = 0;
			volume = 0;
			pitch = 1;
			relative = false;
			attenuation = SoundInstance.Attenuation.LINEAR;
			position();
		}

		private void target(float volume, float pitch) {
			targetVolume = Math.clamp(volume, 0.0f, 0.42f);
			targetPitch = Math.clamp(pitch, 0.9f, 1.06f);
			if (!looping) this.volume = targetVolume;
		}

		@Override public boolean canStartSilent() { return true; }

		@Override public void tick() {
			Minecraft client = Minecraft.getInstance();
			if (client.level != entity.level() || !audible(entity) || entity.level().getGameTime() > expires) {
				stop();
				return;
			}
			position();
			volume = DroneAudioPolicy.approach(volume, targetVolume, fastAttack ? 0.14f : 0.025f);
			pitch = DroneAudioPolicy.approach(pitch, targetPitch, 0.004f);
		}

		private void position() {
			x = entity.getX();
			y = entity.getY() + entity.getBbHeight() * 0.45;
			z = entity.getZ();
		}
		private void finish() { stop(); }
	}
}
