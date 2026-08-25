package jp.morrowgear.drone;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class SolarOrbitGuidance {
	static final double ACTIVE_RADIUS = 5.4;
	static final double ACTIVE_HEIGHT = 1.65;
	static final double ACTIVE_ANGULAR_SPEED = 0.035;
	static final double HOLD_ANGULAR_SPEED = 0.022;
	static final double ORBIT_CAPTURE_DISTANCE = 1.35;

	private SolarOrbitGuidance() {
	}

	static Profile profile(int slot, int queuePosition) {
		if (slot >= 0) return new Profile(ACTIVE_RADIUS, ACTIVE_HEIGHT, ACTIVE_ANGULAR_SPEED);
		int queue = Math.max(1, queuePosition);
		return new Profile(8.0 + Math.min(4, queue) * 1.4,
			3.0 + Math.min(4, queue) * 0.7, HOLD_ANGULAR_SPEED);
	}

	static double initialPhase(Vec3 station, Vec3 aircraft, int stableIndex) {
		Vec3 radial = aircraft.subtract(station).multiply(1, 0, 1);
		if (radial.lengthSqr() > 0.01) return Math.atan2(radial.z, radial.x);
		return Math.floorMod(stableIndex, 4096) / 4096.0 * Math.PI * 2.0;
	}

	static Vec3 insertionTarget(Vec3 station, double phase, Profile profile) {
		return point(station, phase, profile);
	}

	static Sample orbitSample(Vec3 station, double phase, Profile profile) {
		Vec3 target = point(station, phase, profile);
		Vec3 next = point(station, phase + profile.angularSpeed(), profile);
		return new Sample(target, next.subtract(target));
	}

	static boolean captured(Vec3 position, Vec3 insertionTarget) {
		return position.distanceTo(insertionTarget) <= ORBIT_CAPTURE_DISTANCE;
	}

	static double advancePhase(double phase, long elapsedTicks, Profile profile) {
		long elapsed = Mth.clamp(elapsedTicks, 0L, 5L);
		return wrap(phase + profile.angularSpeed() * elapsed);
	}

	private static Vec3 point(Vec3 station, double phase, Profile profile) {
		return station.add(Math.cos(phase) * profile.radius(), profile.height(),
			Math.sin(phase) * profile.radius());
	}

	private static double wrap(double phase) {
		double wrapped = phase % (Math.PI * 2.0);
		return wrapped < 0.0 ? wrapped + Math.PI * 2.0 : wrapped;
	}

	record Profile(double radius, double height, double angularSpeed) {}
	record Sample(Vec3 target, Vec3 velocity) {}
}
