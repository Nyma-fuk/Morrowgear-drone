package jp.morrowgear.drone;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Pure rules for the bounded security-drone strike-beacon operation. */
final class StrikeBeaconPolicy {
	static final int MAX_ACTIVE_BEACONS = 3;
	static final double MUSTER_RADIUS = 24.0;
	static final double MUSTER_ALTITUDE = 14.0;
	static final double ARRIVAL_TOLERANCE = 6.0;
	static final int ARM_TICKS = 16;
	static final int MUSTER_TIMEOUT_TICKS = 200;
	static final int OPERATION_TIMEOUT_TICKS = 20 * 60 * 30;
	static final int RETIRE_DELAY_TICKS = 80;
	static final int MIN_RETURN_FLIGHT_POWER = 12;
	static final double STRIKE_RADIUS = 7.5;
	static final double MUSTER_ROUTE_CLEARANCE_BOOST = 4.0;
	static final double MUSTER_TRANSIT_SPEED = 1.30;
	static final double MUSTER_APPROACH_SPEED = 1.05;
	static final double MUSTER_ORBIT_SPEED = 0.72;
	static final int GUN_SALVO_INTERVAL_TICKS = 2;
	static final int MISSILE_SALVO_INTERVAL_TICKS = 3;

	private StrikeBeaconPolicy() {}

	static boolean recruitable(DroneRole role, boolean owned, boolean alive, boolean powerLost,
		boolean serviceReturn, boolean combat, boolean emergency, boolean docked, DroneMode mode,
		int flightPower, int laserPower, int gunAmmo, int missiles) {
		if (role != DroneRole.SECURITY || !owned || !alive || powerLost || serviceReturn
			|| combat || emergency || flightPower <= MIN_RETURN_FLIGHT_POWER) return false;
		boolean waiting = docked || mode == DroneMode.STANDBY || mode == DroneMode.FOLLOW
			|| mode == DroneMode.ORBIT;
		return waiting && (laserPower > 0 || gunAmmo > 0 || missiles > 0);
	}

	static int balancedBeaconIndex(int sortedAircraftIndex, int beaconCount) {
		if (sortedAircraftIndex < 0 || beaconCount <= 0) throw new IllegalArgumentException("Invalid allocation");
		return sortedAircraftIndex % beaconCount;
	}

	static Vec3 musterPoint(Vec3 anchor, int slot, int count, long tick) {
		int size = Math.max(1, count);
		double phase = slot * Math.PI * 2.0 / size + tick * 0.018;
		double layer = Math.floorDiv(Math.max(0, slot), 8) * 2.5;
		return anchor.add(Math.cos(phase) * (MUSTER_RADIUS + layer),
			MUSTER_ALTITUDE + Math.floorDiv(Math.max(0, slot), 8) * 3.0,
			Math.sin(phase) * (MUSTER_RADIUS + layer));
	}

	static boolean arrived(Vec3 position, Vec3 slot) {
		return position != null && slot != null
			&& position.distanceToSqr(slot) <= ARRIVAL_TOLERANCE * ARRIVAL_TOLERANCE;
	}

	static double musterFlightSpeed(double distance) {
		if (!Double.isFinite(distance) || distance <= 0.0) return MUSTER_ORBIT_SPEED;
		if (distance > 24.0) return MUSTER_TRANSIT_SPEED;
		if (distance > 8.0) return MUSTER_APPROACH_SPEED;
		return MUSTER_ORBIT_SPEED;
	}

	static Vec3 climbBeforeCruise(Vec3 current, Vec3 transit) {
		if (current == null || transit == null) return transit;
		return transit.y - current.y > 2.0 && transit.subtract(current).horizontalDistance() > 0.5
			? new Vec3(current.x, transit.y, current.z) : transit;
	}

	static boolean beginBarrage(int elapsedTicks, int assigned, int arrived) {
		return assigned > 0 && (arrived >= assigned || elapsedTicks >= MUSTER_TIMEOUT_TICKS);
	}

	static boolean resourcesExhausted(int laserPower, int gunAmmo, int missiles) {
		return laserPower <= 0 && gunAmmo <= 0 && missiles <= 0;
	}

	static boolean gunSalvoTick(long barrageTick) {
		return barrageTick >= 0 && Math.floorMod(barrageTick, GUN_SALVO_INTERVAL_TICKS) == 0;
	}

	static boolean missileSalvoTick(long barrageTick) {
		return barrageTick >= 0 && Math.floorMod(barrageTick, MISSILE_SALVO_INTERVAL_TICKS) == 0;
	}

	static Vec3 strikePoint(Vec3 anchor, int slot, long sequence) {
		long mixed = sequence * 0x9E3779B97F4A7C15L + slot * 0xC2B2AE3D27D4EB4FL;
		double unit = ((mixed >>> 11) & 0x1fffff) / (double)0x1fffff;
		double radius = STRIKE_RADIUS * Math.sqrt(unit);
		double angle = (mixed & 0xffff) / 65535.0 * Math.PI * 2.0;
		return anchor.add(Math.cos(angle) * radius, Mth.clamp((slot % 3) * 0.18, 0, 0.36),
			Math.sin(angle) * radius);
	}
}
