package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

public final class PowerLossPolicy {
	public static final int FIELD_TRANSFER_MINIMUM_PERCENT = 18;
	public static final int FIELD_TRANSFER_RECEIVER_MAXIMUM_PERCENT = 8;
	public static final int MANUAL_RECOVERY_MAX_DISTANCE = 4;
	static final double HORIZONTAL_INERTIA = 0.985;
	static final double FALL_ACCELERATION = 0.045;
	static final double TERMINAL_FALL_SPEED = -0.92;

	private PowerLossPolicy() {
	}

	public static boolean powerLost(int storedPower) {
		return storedPower <= 0;
	}

	public static boolean manuallyRecoverable(boolean owned, boolean powerLost, double distance) {
		return owned && powerLost && distance <= MANUAL_RECOVERY_MAX_DISTANCE;
	}

	public static boolean salvageEligible(boolean owned, boolean powerLost, boolean alreadyReserved) {
		return owned && powerLost && !alreadyReserved;
	}

	public static Vec3 fallVelocity(Vec3 current, boolean onGround) {
		if (onGround) return new Vec3(current.x * 0.72, 0.0, current.z * 0.72);
		return new Vec3(current.x * HORIZONTAL_INERTIA,
			Math.max(TERMINAL_FALL_SPEED, current.y - FALL_ACCELERATION),
			current.z * HORIZONTAL_INERTIA);
	}

	public static boolean fieldTransferAllowed(int donorPercent, int receiverPercent) {
		return donorPercent > FIELD_TRANSFER_MINIMUM_PERCENT
			&& receiverPercent > 0 && receiverPercent <= FIELD_TRANSFER_RECEIVER_MAXIMUM_PERCENT;
	}
}
