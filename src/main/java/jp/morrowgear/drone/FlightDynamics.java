package jp.morrowgear.drone;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class FlightDynamics {
	private static final double CRUISE_SPEED = 0.44;
	private static final double CATCH_UP_SPEED = 0.96;
	private static final double DOCK_APPROACH_SPEED = 0.28;
	private static final double DOCK_FINAL_SPEED = 0.14;
	private static final double DOCK_RETURN_CRUISE_SPEED = 1.08;
	static final double DOCK_CRUISE_HANDOFF_DISTANCE = 24.0;

	private FlightDynamics() {
	}

	static double speedLimit(double distance, boolean docking, boolean finalApproach) {
		if (finalApproach) return DOCK_FINAL_SPEED;
		if (docking) {
			double progress = Mth.clamp((distance - 5.0)
				/ (DOCK_CRUISE_HANDOFF_DISTANCE - 5.0), 0.0, 1.0);
			progress = progress * progress * (3.0 - 2.0 * progress);
			return Mth.lerp(progress, DOCK_APPROACH_SPEED, DOCK_RETURN_CRUISE_SPEED);
		}
		double progress = Mth.clamp((distance - 8.0) / 12.0, 0.0, 1.0);
		progress = progress * progress * (3.0 - 2.0 * progress);
		return Mth.lerp(progress, CRUISE_SPEED, CATCH_UP_SPEED);
	}

	static Vec3 steer(Vec3 current, Vec3 position, Vec3 target, Vec3 separation, double speedLimit, boolean finalApproach) {
		return steer(current, position, target, separation, speedLimit, finalApproach ? 0.46 : 0.36, finalApproach);
	}

	static Vec3 steerUrgent(Vec3 current, Vec3 position, Vec3 target, Vec3 separation, double speedLimit) {
		return steer(current, position, target, separation, speedLimit, 0.72, false,
			position.distanceTo(target));
	}

	static Vec3 steerRoute(Vec3 current, Vec3 position, Vec3 waypoint, Vec3 separation,
		double speedLimit, double remainingDistance) {
		return steer(current, position, waypoint, separation, speedLimit, 0.48, false,
			Math.max(position.distanceTo(waypoint), remainingDistance));
	}

	static Vec3 steerPredictiveRoute(Vec3 current, Vec3 position, Vec3 target,
		Vec3 separation, double speedLimit, double remainingDistance) {
		Vec3 requested = steer(current, position, target, separation, speedLimit, 0.22, false,
			Math.max(position.distanceTo(target), remainingDistance));
		Vec3 change = requested.subtract(current);
		Vec3 horizontal = change.multiply(1, 0, 1);
		if (horizontal.length() > 0.055) horizontal = horizontal.normalize().scale(0.055);
		double vertical = Mth.clamp(change.y, -0.032, 0.032);
		Vec3 next = current.add(horizontal.x, vertical, horizontal.z);
		return next.length() > speedLimit ? next.normalize().scale(speedLimit) : next;
	}

	static Vec3 steerConverging(Vec3 current, Vec3 position, Vec3 slot, Vec3 separation,
		double speedLimit, Vec3 leaderVelocity) {
		Vec3 error = slot.subtract(position);
		double distance = error.length();
		if (distance < 0.01) return matchVelocity(current, leaderVelocity, speedLimit, 0.48);
		if (distance >= 7.0) return steer(current, position, slot, separation,
			speedLimit, 0.54, false, distance);

		double closureSpeed = Math.min(0.34, 0.06 + distance * 0.055);
		Vec3 desired = leaderVelocity.add(error.scale(closureSpeed / distance)).add(separation);
		if (desired.length() > speedLimit) desired = desired.normalize().scale(speedLimit);
		return matchVelocity(current, desired, speedLimit, distance < 3.2 ? 0.56 : 0.48);
	}

	static Vec3 steerMovingOrbit(Vec3 current, Vec3 position, Vec3 slot,
		Vec3 slotVelocity, double speedLimit) {
		Vec3 error = slot.subtract(position);
		double distance = error.length();
		Vec3 correction = distance < 0.01 ? Vec3.ZERO
			: error.scale(Math.min(0.24, 0.045 + distance * 0.055) / distance);
		Vec3 desired = slotVelocity.add(correction);
		if (desired.length() > speedLimit) desired = desired.normalize().scale(speedLimit);
		Vec3 next = matchVelocity(current, desired, speedLimit, distance < 1.2 ? 0.24 : 0.34);
		Vec3 change = next.subtract(current);
		if (change.length() > 0.12) next = current.add(change.normalize().scale(0.12));
		return next.length() > speedLimit ? next.normalize().scale(speedLimit) : next;
	}

	static Vec3 steerCombatBreakaway(Vec3 current, Vec3 position, Vec3 target,
		double speedLimit) {
		Vec3 requested = steerUrgent(current, position, target, Vec3.ZERO, speedLimit);
		Vec3 change = requested.subtract(current);
		if (change.length() > 0.085) change = change.normalize().scale(0.085);
		Vec3 next = current.add(change);
		return next.length() > speedLimit ? next.normalize().scale(speedLimit) : next;
	}

	static Vec3 steerLaserFormation(Vec3 current, Vec3 position, Vec3 slot,
		Vec3 slotVelocity, double speedLimit) {
		Vec3 error = slot.subtract(position);
		double distance = error.length();
		Vec3 correction = error.scale(Math.min(0.18, distance * 0.09));
		Vec3 desired = slotVelocity.add(correction);
		if (desired.length() > speedLimit) desired = desired.normalize().scale(speedLimit);
		return matchVelocity(current, desired, speedLimit, distance < 1.5 ? 0.18 : 0.28);
	}

	private static Vec3 matchVelocity(Vec3 current, Vec3 desired, double speedLimit, double response) {
		Vec3 next = current.scale(1.0 - response).add(desired.scale(response));
		return next.length() > speedLimit ? next.normalize().scale(speedLimit) : next;
	}

	private static Vec3 steer(Vec3 current, Vec3 position, Vec3 target, Vec3 separation,
		double speedLimit, double response, boolean finalApproach) {
		return steer(current, position, target, separation, speedLimit, response, finalApproach,
			position.distanceTo(target));
	}

	private static Vec3 steer(Vec3 current, Vec3 position, Vec3 target, Vec3 separation,
		double speedLimit, double response, boolean finalApproach, double speedDistance) {
		Vec3 error = target.subtract(position);
		double distance = error.length();
		if (distance < 0.01) return brake(current);

		double requestedSpeed = Math.min(speedLimit, 0.03 + distance * 0.075);
		if (speedDistance > distance + 1.0) {
			requestedSpeed = Math.min(speedLimit,
				0.08 + distance * 0.09 + Math.min(24.0, speedDistance) * 0.014);
		}
		Vec3 desired = error.scale(requestedSpeed / distance).add(separation);
		if (desired.length() > speedLimit) desired = desired.normalize().scale(speedLimit);

		Vec3 next = current.scale(1.0 - response).add(desired.scale(response));
		if (distance < 0.35) next = next.scale(0.35);
		return next.length() > speedLimit ? next.normalize().scale(speedLimit) : next;
	}

	static Vec3 brake(Vec3 current) {
		Vec3 next = current.scale(0.22);
		return next.lengthSqr() < 0.000025 ? Vec3.ZERO : next;
	}
}
