package jp.morrowgear.drone;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class FlightDynamics {
	private static final double CRUISE_SPEED = 0.374;
	private static final double CATCH_UP_SPEED = 0.816;
	private static final double DOCK_APPROACH_SPEED = 0.238;
	private static final double DOCK_FINAL_SPEED = 0.119;
	private static final double DOCK_RETURN_CRUISE_SPEED = 0.918;
	static final double DOCK_CRUISE_HANDOFF_DISTANCE = 24.0;
	static final double HORIZONTAL_ACCELERATION = 0.065;
	static final double VERTICAL_ACCELERATION = 0.035;
	static final double HORIZONTAL_JERK = 0.014;
	static final double VERTICAL_JERK = 0.009;
	// Transient state must survive mission changes without changing saved entity data.
	private static final Map<DroneEntity, Motion> MOTION = Collections.synchronizedMap(new WeakHashMap<>());

	private FlightDynamics() {
	}

	static double speedLimit(double distance, boolean docking, boolean finalApproach) {
		distance = Double.isFinite(distance) ? Math.max(0.0, distance) : 0.0;
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
		Vec3 error = finite(target.subtract(position));
		double distance = error.length();
		Vec3 desired = distance < 0.01 ? finite(separation)
			: error.scale(Math.min(safeLimit(speedLimit), 0.03 + distance * 0.075) / distance)
				.add(finite(separation));
		return finite(current).scale(0.28).add(limit(desired, safeLimit(speedLimit)).scale(0.72));
	}

	static Vec3 steerRoute(Vec3 current, Vec3 position, Vec3 waypoint, Vec3 separation,
		double speedLimit, double remainingDistance) {
		return steer(current, position, waypoint, separation, speedLimit, 0.48, false,
			Math.max(position.distanceTo(waypoint), remainingDistance));
	}

	static Vec3 steerPredictiveRoute(Vec3 current, Vec3 position, Vec3 target,
		Vec3 separation, double speedLimit, double remainingDistance) {
		current = finite(current);
		Vec3 requested = steer(current, position, target, separation, speedLimit, 0.22, false,
			Math.max(position.distanceTo(target), remainingDistance));
		Vec3 change = requested.subtract(current);
		Vec3 horizontal = change.multiply(1, 0, 1);
		if (horizontal.length() > 0.055) horizontal = horizontal.normalize().scale(0.055);
		double vertical = Mth.clamp(change.y, -0.032, 0.032);
		Vec3 next = current.add(horizontal.x, vertical, horizontal.z);
		return next;
	}

	static Vec3 steerConverging(Vec3 current, Vec3 position, Vec3 slot, Vec3 separation,
		double speedLimit, Vec3 leaderVelocity) {
		Vec3 error = slot.subtract(position);
		double distance = error.length();
		if (distance < 0.01) return matchVelocity(current, leaderVelocity.add(separation), speedLimit, 0.48);
		if (distance >= 7.0) return steer(current, position, slot, separation,
			speedLimit, 0.54, false, distance);

		double closureSpeed = Math.min(0.34, 0.06 + distance * 0.055);
		Vec3 desired = leaderVelocity.add(error.scale(closureSpeed / distance)).add(separation);
		if (desired.length() > speedLimit) desired = desired.normalize().scale(speedLimit);
		return matchVelocity(current, desired, speedLimit, distance < 3.2 ? 0.56 : 0.48);
	}

	static Vec3 steerMovingOrbit(Vec3 current, Vec3 position, Vec3 slot,
		Vec3 slotVelocity, double speedLimit) {
		current = finite(current);
		Vec3 error = slot.subtract(position);
		double distance = error.length();
		Vec3 correction = distance < 0.01 ? Vec3.ZERO
			: error.scale(Math.min(0.24, 0.045 + distance * 0.055) / distance);
		Vec3 desired = slotVelocity.add(correction);
		if (desired.length() > speedLimit) desired = desired.normalize().scale(speedLimit);
		Vec3 next = matchVelocity(current, desired, speedLimit, distance < 1.2 ? 0.24 : 0.34);
		Vec3 change = next.subtract(current);
		if (change.length() > 0.12) next = current.add(change.normalize().scale(0.12));
		return next;
	}

	static Vec3 steerCombatBreakaway(Vec3 current, Vec3 position, Vec3 target,
		double speedLimit) {
		current = finite(current);
		Vec3 requested = steerUrgent(current, position, target, Vec3.ZERO, speedLimit);
		Vec3 change = requested.subtract(current);
		if (change.length() > 0.085) change = change.normalize().scale(0.085);
		Vec3 next = current.add(change);
		return next;
	}

	static Vec3 steerLaserFormation(Vec3 current, Vec3 position, Vec3 slot,
		Vec3 slotVelocity, double speedLimit) {
		Vec3 error = slot.subtract(position);
		double distance = error.length();
		Vec3 correction = distance < 0.000001 ? Vec3.ZERO
			: error.scale(Math.min(0.18, distance * 0.09) / distance);
		Vec3 desired = slotVelocity.add(correction);
		if (desired.length() > speedLimit) desired = desired.normalize().scale(speedLimit);
		// The dedicated laser lane is also a hard safety envelope. Generic velocity
		// matching may retain entry speed for several ticks while it decelerates.
		return limit(matchVelocity(current, desired, speedLimit,
			distance < 1.5 ? 0.18 : 0.28), safeLimit(speedLimit));
	}

	static Vec3 limitLaserVelocity(Vec3 velocity, double speedLimit) {
		return limit(finite(velocity), safeLimit(speedLimit));
	}

	private static Vec3 matchVelocity(Vec3 current, Vec3 desired, double speedLimit, double response) {
		current = finite(current);
		desired = limit(finite(desired), safeLimit(speedLimit));
		Vec3 change = desired.subtract(current).scale(response);
		return current.add(limitAcceleration(change));
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
		if (distance < 0.01) {
			Vec3 stopped = matchVelocity(current, separation, speedLimit, 0.46);
			return finalApproach ? captureLandingCenter(current, error, stopped) : stopped;
		}

		double requestedSpeed = Math.min(speedLimit, 0.03 + distance * 0.075);
		if (speedDistance > distance + 1.0) {
			requestedSpeed = Math.min(speedLimit,
				0.08 + distance * 0.09 + Math.min(24.0, speedDistance) * 0.014);
		}
		Vec3 desired = error.scale(requestedSpeed / distance).add(separation);
		Vec3 next = matchVelocity(current, desired, speedLimit, response);
		return finalApproach ? captureLandingCenter(current, error, next) : next;
	}

	private static Vec3 captureLandingCenter(Vec3 current, Vec3 error, Vec3 requested) {
		// The wide Dock's inner deck exactly fits the hull. Finish lateral alignment
		// in finite ticks so an arbitrarily small residual cannot catch its raised rim.
		return error.horizontalDistance() <= 0.002 && finite(current).horizontalDistance() <= 0.008
			? new Vec3(error.x, requested.y, error.z) : requested;
	}

	static Vec3 brake(Vec3 current) {
		current = finite(current);
		return current.add(limitAcceleration(current.scale(-1)));
	}

	static Motion motion(DroneEntity drone) {
		return MOTION.computeIfAbsent(drone, ignored -> new Motion());
	}

	static Vec3 dockDepartureTarget(Vec3 position, Vec3 deckCenter) {
		if (!isFinite(position) || !isFinite(deckCenter)) return null;
		Vec3 offset = position.subtract(deckCenter);
		if (offset.horizontalDistance() > 0.3 || offset.y < -0.02 || offset.y >= 0.25) return null;
		// Lift the full hull above the raised rim before allowing lateral formation closure.
		return new Vec3(position.x, deckCenter.y + 0.55, position.z);
	}

	static boolean requiresSafetyRecovery(boolean horizontalCollision, boolean verticalCollision,
		boolean onGround, boolean fluidDanger, boolean dockDeparture) {
		// Recovery timers retain their route, not permission to accelerate without limits.
		// Ground contact at launch is not an impact against a wall or ceiling.
		return fluidDanger || !dockDeparture && (horizontalCollision || verticalCollision && !onGround);
	}

	static boolean touchdownReady(double distance, Vec3 velocity) {
		return Double.isFinite(distance) && distance >= 0.0 && distance <= 0.035
			&& isFinite(velocity) && velocity.lengthSqr() <= 0.018 * 0.018;
	}

	static AABB landingSweep(AABB body, Vec3 displacement) {
		return body.expandTowards(displacement).deflate(1e-7);
	}

	static Vec3 collisionSafeVelocity(Vec3 next, Vec3 requested, boolean finalApproach,
		Predicate<Vec3> clear) {
		if (clear.test(next)) return next;
		if (clear.test(requested)) return requested;
		if (finalApproach) {
			Vec3 lateral = requested.multiply(1, 0, 1);
			if (clear.test(lateral)) return lateral;
		}
		return Vec3.ZERO;
	}

	private static Vec3 limitAcceleration(Vec3 change) {
		Vec3 horizontal = limit(change.multiply(1, 0, 1), HORIZONTAL_ACCELERATION);
		return new Vec3(horizontal.x, Mth.clamp(change.y, -VERTICAL_ACCELERATION,
			VERTICAL_ACCELERATION), horizontal.z);
	}

	private static double safeLimit(double value) {
		return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
	}

	private static Vec3 limit(Vec3 value, double maximum) {
		double length = value.length();
		return length > maximum ? value.scale(maximum / length) : value;
	}

	private static boolean isFinite(Vec3 value) {
		return value != null && Double.isFinite(value.x) && Double.isFinite(value.y)
			&& Double.isFinite(value.z) && Double.isFinite(value.lengthSqr());
	}

	private static Vec3 finite(Vec3 value) {
		return isFinite(value) ? value : Vec3.ZERO;
	}

	static final class Motion {
		private Vec3 acceleration = Vec3.ZERO;
		private long lastTick = Long.MIN_VALUE;
		private float yawRate;
		private long lastHeadingTick = Long.MIN_VALUE;

		float heading(float current, float target, Vec3 velocity, long tick) {
			if (lastHeadingTick != tick - 1) yawRate = 0.0f;
			lastHeadingTick = tick;
			float next = FlightAttitude.nextYaw(current, current - yawRate, target, velocity);
			yawRate = Mth.wrapDegrees(next - current);
			return next;
		}

		Vec3 step(Vec3 current, Vec3 requested, long tick, boolean safetyOverride) {
			if (lastTick != tick - 1 || !isFinite(current)) acceleration = Vec3.ZERO;
			lastTick = tick;
			current = finite(current);
			requested = finite(requested);
			if (safetyOverride) {
				// Collision/fluid recovery and hard terrain stops cannot inherit turn inertia.
				acceleration = Vec3.ZERO;
				return requested;
			}
			// Limit the requested curvature before the jerk filter, never snap an existing turn.
			requested = FlightAttitude.coordinatedVelocity(current, requested);
			Vec3 desiredAcceleration = limitAcceleration(requested.subtract(current));
			Vec3 change = desiredAcceleration.subtract(acceleration);
			Vec3 horizontal = limit(change.multiply(1, 0, 1), HORIZONTAL_JERK);
			acceleration = acceleration.add(horizontal.x,
				Mth.clamp(change.y, -VERTICAL_JERK, VERTICAL_JERK), horizontal.z);
			return current.add(acceleration);
		}
	}
}
