package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

public final class ChargingRelayPolicy {
	public static final double BAY_LOCK_DISTANCE = 0.40;
	public static final double BEAM_DISTANCE = 4.50;
	public static final int BEAM_STABLE_TICKS = 4;
	public static final int TRANSFER_STABLE_TICKS = 12;
	public static final int TRANSFER_INTERVAL_TICKS = 2;
	public static final int TRANSFER_AMOUNT = 5;

	private ChargingRelayPolicy() {}

	public static boolean lockToBay(boolean hasTarget, boolean alreadyLocked, double bayDistance) {
		return !hasTarget && (alreadyLocked || bayDistance <= BAY_LOCK_DISTANCE);
	}

	public static boolean showBeam(boolean hasTarget, double targetDistance) {
		return hasTarget && targetDistance <= BEAM_DISTANCE;
	}

	public static boolean beamStable(int stableTicks) {
		return stableTicks >= BEAM_STABLE_TICKS;
	}

	public static boolean transferThisTick(int stableTicks, int tickCount) {
		return stableTicks >= TRANSFER_STABLE_TICKS
			&& Math.floorMod(tickCount, TRANSFER_INTERVAL_TICKS) == 0;
	}

	public static double relativeChargeRate() {
		return (double)TRANSFER_AMOUNT / TRANSFER_INTERVAL_TICKS / 5.0;
	}

	public static Vec3 followVelocity(Vec3 current, Vec3 position, Vec3 target,
		Vec3 targetVelocity, double speedLimit) {
		Vec3 error = target.subtract(position);
		double distance = error.length();
		Vec3 correction = distance < .001 ? Vec3.ZERO
			: error.scale(Math.min(.30, .035 + distance * .12) / distance);
		Vec3 desired = targetVelocity.scale(.82).add(correction);
		if (desired.length() > speedLimit) desired = desired.normalize().scale(speedLimit);
		Vec3 next = current.scale(.72).add(desired.scale(.28));
		Vec3 change = next.subtract(current);
		if (change.length() > .065) next = current.add(change.normalize().scale(.065));
		return next.length() > speedLimit ? next.normalize().scale(speedLimit) : next;
	}
}
