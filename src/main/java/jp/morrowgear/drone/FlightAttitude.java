package jp.morrowgear.drone;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class FlightAttitude {
	public static final float MAX_BANK = 0.65f;
	static final double LATERAL_REFERENCE_ACCELERATION = 0.10;
	private FlightAttitude() {
	}

	public static float movementYaw(Vec3 velocity, float fallbackYaw) {
		fallbackYaw = Float.isFinite(fallbackYaw) ? fallbackYaw : 0.0f;
		velocity = finite(velocity);
		Vec3 horizontal = velocity.multiply(1, 0, 1);
		if (horizontal.lengthSqr() <= 0.0016) return fallbackYaw;
		return (float) (Mth.atan2(horizontal.z, horizontal.x) * 180.0 / Math.PI) - 90.0f;
	}

	public static float yawTurnLimit(Vec3 velocity) {
		double horizontalSpeed = finite(velocity).multiply(1, 0, 1).length();
		return (float) Math.min(7.0, 2.5 + horizontalSpeed * 6.0);
	}

	public static float nextYaw(float current, float previous, float target, Vec3 velocity) {
		current = Float.isFinite(current) ? current : 0.0f;
		previous = Float.isFinite(previous) ? previous : current;
		target = Float.isFinite(target) ? target : current;
		float error = Mth.wrapDegrees(target - current);
		float limit = yawTurnLimit(velocity);
		float rate = Mth.clamp(Mth.wrapDegrees(current - previous), -7.0f, 7.0f);
		float requested = Mth.clamp(error * 0.24f, -limit, limit);
		return current + rate + Mth.clamp(requested - rate, -0.65f, 0.65f);
	}

	public static float pitch(Vec3 velocity, float yawDegrees) {
		velocity = finite(velocity);
		yawDegrees = Float.isFinite(yawDegrees) ? yawDegrees : 0.0f;
		double yaw = Math.toRadians(yawDegrees);
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		double forwardSpeed = velocity.dot(forward);
		double pathAngle = Math.atan2(velocity.y, Math.max(0.16, velocity.horizontalDistance()));
		return Mth.clamp((float) (forwardSpeed * 0.10 - pathAngle * 0.30), -0.24f, 0.24f);
	}

	public static float roll(Vec3 velocity, float yawDegrees) {
		return roll(velocity, Vec3.ZERO, yawDegrees);
	}

	public static float roll(Vec3 velocity, Vec3 acceleration, float yawDegrees) {
		velocity = finite(velocity);
		acceleration = finite(acceleration);
		yawDegrees = Float.isFinite(yawDegrees) ? yawDegrees : 0.0f;
		double yaw = Math.toRadians(yawDegrees);
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		double rightwardSpeed = velocity.dot(right);
		double bank = Math.atan2(acceleration.dot(right), 0.10);
		return Mth.clamp((float) (bank + rightwardSpeed * 0.16), -MAX_BANK, MAX_BANK);
	}

	static double turnRateLimit(Vec3 velocity) {
		double speed = finite(velocity).horizontalDistance();
		if (speed < 0.10) return Math.PI;
		double lateral = LATERAL_REFERENCE_ACCELERATION * Math.tan(MAX_BANK);
		return Math.min(Math.toRadians(yawTurnLimit(velocity)),
			2.0 * Math.asin(Math.min(1.0, lateral / (2.0 * speed))));
	}

	static Vec3 coordinatedVelocity(Vec3 current, Vec3 next) {
		current = finite(current);
		next = finite(next);
		double speed = next.horizontalDistance();
		if (current.horizontalDistance() < 0.10 || speed < 0.10) return next;
		double angle = Math.atan2(current.x * next.z - current.z * next.x,
			current.x * next.x + current.z * next.z);
		double limit = Math.min(turnRateLimit(current), turnRateLimit(next));
		if (Math.abs(angle) <= limit) return next;
		double turn = Math.copySign(limit, angle);
		Vec3 direction = current.multiply(1, 0, 1).normalize();
		return new Vec3((direction.x * Math.cos(turn) - direction.z * Math.sin(turn)) * speed,
			next.y, (direction.x * Math.sin(turn) + direction.z * Math.cos(turn)) * speed);
	}

	public static float coordinatedRoll(Vec3 velocity, float yawDegrees, float yawRateDegreesPerTick) {
		velocity = finite(velocity);
		yawDegrees = Float.isFinite(yawDegrees) ? yawDegrees : 0.0f;
		double rate = Float.isFinite(yawRateDegreesPerTick)
			? Math.toRadians(Mth.clamp(yawRateDegreesPerTick, -7.0f, 7.0f)) : 0.0;
		double yaw = Math.toRadians(yawDegrees);
		double lateralAcceleration = velocity.horizontalDistance() * rate;
		return roll(velocity, new Vec3(-Math.cos(yaw) * lateralAcceleration, 0,
			-Math.sin(yaw) * lateralAcceleration), yawDegrees);
	}

	/** Radians, with elapsed time measured in ticks (including partial ticks). */
	public static float smoothAngle(float current, float target, float elapsedTicks) {
		current = Float.isFinite(current) ? current : 0.0f;
		target = Float.isFinite(target) ? target : 0.0f;
		if (!Float.isFinite(elapsedTicks) || elapsedTicks <= 0.0f) return current;
		float elapsed = Math.min(elapsedTicks, 5.0f);
		float change = (target - current) * (float) -Math.expm1(-0.24 * elapsed);
		return current + Mth.clamp(change, -0.035f * elapsed, 0.035f * elapsed);
	}

	private static Vec3 finite(Vec3 value) {
		return value != null && Double.isFinite(value.x) && Double.isFinite(value.y)
			&& Double.isFinite(value.z) && Double.isFinite(value.lengthSqr()) ? value : Vec3.ZERO;
	}
}
