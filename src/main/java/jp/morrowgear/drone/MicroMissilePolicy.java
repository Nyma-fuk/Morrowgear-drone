package jp.morrowgear.drone;

import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class MicroMissilePolicy {
	static final int HATCH_OPEN_TICKS = 8;
	static final int SALVO_INTERVAL_TICKS = 2;
	static final int POWER_PER_MISSILE = 15;
	static final int IMPACT_FUSE_TICKS = 12;
	static final int MAX_FLIGHT_TICKS = 240;
	static final int EJECTION_TICKS = 6;
	static final int REORIENT_TICKS = 6;
	static final int IGNITION_TICK = EJECTION_TICKS + REORIENT_TICKS + 1;
	static final int BOOST_TICKS = IGNITION_TICK + 7;
	static final double REORIENT_RADIANS = Math.toRadians(20);
	static final double SPEED = 1.45;
	static final double TERMINAL_SPEED = 1.85;
	static final double TERMINAL_RANGE = 12.0;
	static final double TERMINAL_TURN_RADIANS = Math.toRadians(3.0);
	static final double CRUISE_TURN_RADIANS = Math.toRadians(12.0);
	static final double TERMINAL_ALIGNMENT_COS = Math.cos(Math.toRadians(25.0));
	// Game explosion power, not an exact sum of simultaneous TNT explosions.
	static final float MIN_EXPLOSION_POWER = 4.0f;
	static final float MAX_EXPLOSION_POWER = 5.0f;
	static final float ENTITY_DAMAGE_MULTIPLIER = 0.5f;
	private static final double[] TUBE_Z = {0.348, 0.213, 0.078, -0.04};

	private MicroMissilePolicy() {}

	static float entityDamage(float originalDamage) {
		return Float.isFinite(originalDamage) ? Math.max(0, originalDamage) * ENTITY_DAMAGE_MULTIPLIER : 0;
	}

	static boolean renderVisible(double distanceSquared, double viewScale) {
		// Keep the 0.28 collision envelope independent from the missile's visual range.
		double range = Math.min(192.0, 128.0 * (Double.isFinite(viewScale) ? Math.max(0, viewScale) : 1));
		return distanceSquared >= 0 && distanceSquared < range * range;
	}

	static boolean launchReady(long approachElapsed, long now, long nextShotTick) {
		return approachElapsed >= HATCH_OPEN_TICKS && now >= nextShotTick;
	}

	static Vec3 launchTube(int shot) {
		int tube = Math.floorMod(shot, 8);
		return new Vec3((tube % 2 == 0 ? -1 : 1) * 0.1146, 0.56, TUBE_Z[tube / 2]);
	}

	/** The immutable plan cursor picks a lane; skipping a target never compacts the remaining launch paths. */
	record LaunchProfile(int tube, float yaw) {
		LaunchProfile {
			tube = Math.floorMod(tube, 8);
			yaw = Float.isFinite(yaw) ? Mth.wrapDegrees(yaw) : 0;
		}
		Vec3 fan() {
			return DroneHardpoints.rotate(new Vec3((tube % 2 == 0 ? -1 : 1) * .13,
				0, .12 - (tube / 2) * .08), yaw, 0, 0);
		}
		void write(ValueOutput output) {
			output.putInt("LaunchTube", tube);
			output.putFloat("LaunchYaw", yaw);
		}
		static LaunchProfile read(ValueInput input) {
			return new LaunchProfile(input.getIntOr("LaunchTube", 0), input.getFloatOr("LaunchYaw", 0));
		}
	}

	static Vec3 ejectionVelocity(LaunchProfile launch, Vec3 launcherVelocity) {
		Vec3 inherited = finite(launcherVelocity) ? capSpeed(launcherVelocity.multiply(.1, 0, .1), .08) : Vec3.ZERO;
		return launch.fan().add(0, .38, 0).add(inherited);
	}

	static int flightPhase(int age, boolean impacted) {
		return impacted ? 4 : age <= EJECTION_TICKS ? 0 : age < IGNITION_TICK ? 1 : age <= BOOST_TICKS ? 2 : 3;
	}

	static boolean motorIgnited(int age) { return age >= IGNITION_TICK; }

	static float explosionPower(float randomFraction) {
		return MIN_EXPLOSION_POWER + (MAX_EXPLOSION_POWER - MIN_EXPLOSION_POWER)
			* Mth.clamp(Float.isFinite(randomFraction) ? randomFraction : 0, 0, 1);
	}

	static FlightStep flightStep(Vec3 position, Vec3 velocity, Vec3 lastAim, int age,
		boolean terminal, boolean passedTarget) {
		return flightStep(position, velocity, lastAim, age, terminal, passedTarget, new LaunchProfile(0, 0));
	}

	static FlightStep flightStep(Vec3 position, Vec3 velocity, Vec3 lastAim, int age,
		boolean terminal, boolean passedTarget, LaunchProfile launch) {
		if (age <= EJECTION_TICKS) return new FlightStep(
			new Vec3(velocity.x * .98, Math.max(.20, .38 - age * .025), velocity.z * .98), false, false, new Vec3(0, 1, 0));
		if (age < IGNITION_TICK) return new FlightStep(
			new Vec3(velocity.x * .96, Math.max(.08, velocity.y - .025), velocity.z * .96), false, false,
			launchAttitude(position, lastAim, age, launch));
		if (age <= BOOST_TICKS) {
			Vec3 facing = launchAttitude(position, lastAim, IGNITION_TICK - 1, launch);
			Vec3 boosted = capSpeed(velocity.scale(.75).add(facing.scale(.28 + (age - IGNITION_TICK) * .035)), SPEED);
			return new FlightStep(boosted, false, false, facing);
		}
		Vec3 toAim = lastAim.subtract(position);
		// Acquire the descending attack axis before reducing steering authority.
		terminal |= velocity.y < 0 && toAim.lengthSqr() <= TERMINAL_RANGE * TERMINAL_RANGE
			&& velocity.normalize().dot(toAim.normalize()) >= TERMINAL_ALIGNMENT_COS;
		passedTarget |= terminal && velocity.dot(toAim) <= 0;
		double horizontalRange = toAim.horizontalDistance();
		Vec3 desired = (!terminal && horizontalRange > 20 ? lastAim.add(0, 10, 0) : lastAim).subtract(position);
		// A missed terminal pass continues into terrain; never loop back around a target.
		if (passedTarget) {
			Vec3 falling = capSpeed(velocity.add(0, -0.045, 0), TERMINAL_SPEED);
			return new FlightStep(falling, terminal, true, falling.normalize());
		}
		Vec3 guided = turnToward(velocity, desired,
			terminal ? TERMINAL_TURN_RADIANS : CRUISE_TURN_RADIANS)
			.scale(Math.min(terminal ? TERMINAL_SPEED : SPEED, velocity.length() + (terminal ? 0.08 : 0.045)));
		return new FlightStep(guided, terminal, false, guided.normalize());
	}

	private static Vec3 launchAttitude(Vec3 position, Vec3 aim, int age, LaunchProfile launch) {
		Vec3 loft = aim.add(launch.fan().scale(10)).add(0, aim.subtract(position).horizontalDistance() > 20 ? 10 : 0, 0);
		return turnToward(new Vec3(0, 1, 0), loft.subtract(position),
			Math.max(0, age - EJECTION_TICKS) * REORIENT_RADIANS);
	}

	static boolean launchEnvelope(double horizontalRange, double altitude) {
		return Double.isFinite(horizontalRange) && Double.isFinite(altitude)
			&& horizontalRange >= 24 && horizontalRange <= 64 && altitude >= 8;
	}

	record FlightStep(Vec3 velocity, boolean terminal, boolean passedTarget, Vec3 facing) {}

	static Vec3 turnToward(Vec3 velocity, Vec3 desired, double maxAngle) {
		if (desired.lengthSqr() < 1.0e-10) return velocity.normalize();
		Vec3 direction = velocity.lengthSqr() < 1.0e-10 ? new Vec3(0, 1, 0) : velocity.normalize();
		Vec3 aim = desired.normalize();
		double dot = Mth.clamp(direction.dot(aim), -1, 1);
		double angle = Math.acos(dot);
		if (angle <= maxAngle) return aim;
		Vec3 tangent = aim.subtract(direction.scale(dot));
		if (tangent.lengthSqr() < 1.0e-10) {
			Vec3 axis = Math.abs(direction.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
			tangent = axis.subtract(direction.scale(axis.dot(direction)));
		}
		return direction.scale(Math.cos(maxAngle)).add(tangent.normalize().scale(Math.sin(maxAngle))).normalize();
	}

	static Vec3 capSpeed(Vec3 velocity, double maximum) {
		return velocity.lengthSqr() > maximum * maximum ? velocity.normalize().scale(maximum) : velocity;
	}

	/** Sweep a finite-size missile against each block-shape/entity box, not just its endpoint. */
	static double sweepFraction(Vec3 fromCenter, Vec3 toCenter, AABB obstacle, Vec3 halfSize) {
		AABB expanded = obstacle.inflate(halfSize.x, halfSize.y, halfSize.z);
		if (fromCenter.x >= expanded.minX && fromCenter.x <= expanded.maxX
			&& fromCenter.y >= expanded.minY && fromCenter.y <= expanded.maxY
			&& fromCenter.z >= expanded.minZ && fromCenter.z <= expanded.maxZ) return 0;
		double lengthSquared = fromCenter.distanceToSqr(toCenter);
		if (lengthSquared < 1.0e-12) return Double.POSITIVE_INFINITY;
		return expanded.clip(fromCenter, toCenter)
			.map(hit -> Math.sqrt(fromCenter.distanceToSqr(hit) / lengthSquared))
			.orElse(Double.POSITIVE_INFINITY);
	}

	static boolean mayBreakBlock(boolean mobGriefing, boolean ownerPermission, boolean blockEntity,
		float hardness, boolean infrastructure) {
		return mobGriefing && ownerPermission && !blockEntity && hardness >= 0 && !infrastructure;
	}

	static boolean mayDamagePlayer(boolean selectedTarget, boolean pvpAllowed, boolean ownerCanHarm,
		TargetDisposition disposition) {
		return selectedTarget && pvpAllowed && ownerCanHarm && disposition.engageable();
	}

	static UUID uuid(String value) {
		try { return UUID.fromString(value); }
		catch (IllegalArgumentException ignored) { return null; }
	}

	static boolean finite(Vec3 value) {
		return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
	}

	static int nextFuse(int fuse) {
		return fuse < 0 ? -1 : Math.max(0, fuse - 1);
	}

	static Vec3 attachmentLocal(Vec3 impact, Vec3 host, float yaw) {
		return rotateAttachment(impact.subtract(host), Math.toRadians(yaw));
	}

	static Vec3 attachmentPosition(Vec3 local, Vec3 host, float yaw) {
		return host.add(rotateAttachment(local, -Math.toRadians(yaw)));
	}

	private static Vec3 rotateAttachment(Vec3 point, double yaw) {
		double cos = Math.cos(yaw), sin = Math.sin(yaw);
		return new Vec3(point.x * cos + point.z * sin, point.y, point.z * cos - point.x * sin);
	}

	static boolean mayFollowAttachment(Vec3 current, Vec3 next) {
		// Teleporting or unloading the host must not teleport an armed explosive.
		return finite(current) && finite(next) && current.distanceToSqr(next) <= 64;
	}

	record Attachment(UUID host, Vec3 local, float yawOffset) {
		void write(ValueOutput output) {
			output.putString("AttachedHost", host.toString());
			output.putDouble("AttachedX", local.x);
			output.putDouble("AttachedY", local.y);
			output.putDouble("AttachedZ", local.z);
			output.putFloat("AttachedYaw", yawOffset);
		}

		static Attachment read(ValueInput input) {
			UUID host = uuid(input.getStringOr("AttachedHost", ""));
			Vec3 local = new Vec3(input.getDoubleOr("AttachedX", Double.NaN),
				input.getDoubleOr("AttachedY", Double.NaN), input.getDoubleOr("AttachedZ", Double.NaN));
			float yaw = input.getFloatOr("AttachedYaw", 0);
			return host != null && finite(local) && local.lengthSqr() <= 4096 && Float.isFinite(yaw)
				? new Attachment(host, local, yaw) : null;
		}
	}

	record SavedState(UUID launcher, UUID owner, UUID target, String ownerTeam, Vec3 lastAim,
		int flightTicks, int fuseTicks, float explosionPower, boolean terminal, boolean passedTarget) {
		void write(ValueOutput output) {
			if (launcher != null) output.putString("Launcher", launcher.toString());
			if (owner != null) output.putString("Owner", owner.toString());
			if (target != null) output.putString("TargetUuid", target.toString());
			output.putString("OwnerTeam", ownerTeam);
			output.putBoolean("HasLastAim", lastAim != null);
			if (lastAim != null) {
				output.putDouble("AimX", lastAim.x);
				output.putDouble("AimY", lastAim.y);
				output.putDouble("AimZ", lastAim.z);
			}
			output.putInt("FlightTicks", flightTicks);
			output.putInt("ImpactFuse", fuseTicks);
			output.putFloat("ExplosionPower", explosionPower);
			output.putBoolean("TerminalGuidance", terminal);
			output.putBoolean("PassedTarget", passedTarget);
		}

		static SavedState read(ValueInput input) {
			Vec3 aim = input.getBooleanOr("HasLastAim", false)
				? new Vec3(input.getDoubleOr("AimX", Double.NaN), input.getDoubleOr("AimY", Double.NaN),
					input.getDoubleOr("AimZ", Double.NaN)) : null;
			if (aim != null && !finite(aim)) aim = null;
			float power = input.getFloatOr("ExplosionPower", MIN_EXPLOSION_POWER);
			return new SavedState(uuid(input.getStringOr("Launcher", "")), uuid(input.getStringOr("Owner", "")),
				uuid(input.getStringOr("TargetUuid", "")), input.getStringOr("OwnerTeam", ""), aim,
				Mth.clamp(input.getIntOr("FlightTicks", 0), 0, MAX_FLIGHT_TICKS),
				Mth.clamp(input.getIntOr("ImpactFuse", -1), -1, IMPACT_FUSE_TICKS),
				Float.isFinite(power) ? Mth.clamp(power, MIN_EXPLOSION_POWER, MAX_EXPLOSION_POWER)
					: MIN_EXPLOSION_POWER, input.getBooleanOr("TerminalGuidance", false),
				input.getBooleanOr("PassedTarget", false));
		}
	}
}
