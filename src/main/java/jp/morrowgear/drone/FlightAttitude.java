package jp.morrowgear.drone;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class FlightAttitude {
	private FlightAttitude() {
	}

	public static float movementYaw(Vec3 velocity, float fallbackYaw) {
		Vec3 horizontal = velocity.multiply(1, 0, 1);
		if (horizontal.lengthSqr() <= 0.0016) return fallbackYaw;
		return (float) (Mth.atan2(horizontal.z, horizontal.x) * 180.0 / Math.PI) - 90.0f;
	}

	public static float yawTurnLimit(Vec3 velocity) {
		double horizontalSpeed = velocity.multiply(1, 0, 1).length();
		return (float) Math.min(18.0, 6.0 + horizontalSpeed * 20.0);
	}

	public static float pitch(Vec3 velocity, float yawDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		double forwardSpeed = velocity.dot(forward);
		return Mth.clamp((float) (forwardSpeed * 0.52 - velocity.y * 0.18), -0.22f, 0.34f);
	}

	public static float roll(Vec3 velocity, float yawDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		double rightwardSpeed = velocity.dot(right);
		return Mth.clamp((float) (rightwardSpeed * 0.85), -0.26f, 0.26f);
	}
}
