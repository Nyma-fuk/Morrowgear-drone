package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

public final class SalvageTowPolicy {
	public static final double ROPE_LENGTH = 2.15;
	public static final double HOOK_DROP = 1.15;
	public static final double LOAD_CLEARANCE = 0.85;
	public static final double MAX_LOAD_SPEED = 0.72;

	private SalvageTowPolicy() {}

	public static double requiredFlightY(double surfaceY, double loadHeight) {
		return surfaceY + HOOK_DROP + ROPE_LENGTH + Math.max(0.0, loadHeight) + LOAD_CLEARANCE;
	}

	public static boolean readyForDockTransfer(Vec3 carrier, Vec3 dock) {
		double horizontal = carrier.multiply(1, 0, 1).distanceTo(dock.multiply(1, 0, 1));
		double relativeY = carrier.y - dock.y;
		return horizontal <= 5.5 && relativeY >= -0.5 && relativeY <= 12.0;
	}

	public static Step step(Vec3 loadPosition, Vec3 loadVelocity, Vec3 hook,
		Vec3 carrierVelocity, Vec3 previousCarrierVelocity, double response) {
		return step(loadPosition, loadVelocity, hook, carrierVelocity, previousCarrierVelocity,
			response, false);
	}

	public static Step step(Vec3 loadPosition, Vec3 loadVelocity, Vec3 hook,
		Vec3 carrierVelocity, Vec3 previousCarrierVelocity, double response, boolean taut) {
		Vec3 offset = loadPosition.subtract(hook);
		if (offset.lengthSqr() < 0.01) offset = new Vec3(0, -ROPE_LENGTH, 0);
		Vec3 acceleration = hook.subtract(loadPosition).scale(response)
			.add(0, -0.038, 0).add(carrierVelocity.subtract(previousCarrierVelocity).scale(-0.32));
		Vec3 velocity = loadVelocity.scale(0.965).add(acceleration);
		Vec3 predicted = loadPosition.add(velocity);
		Vec3 rope = predicted.subtract(hook);
		if (rope.length() > ROPE_LENGTH || taut && rope.lengthSqr() > 0.0001) {
			Vec3 direction = rope.normalize();
			predicted = hook.add(direction.scale(ROPE_LENGTH));
			velocity = predicted.subtract(loadPosition);
			double outward = velocity.dot(direction);
			if (outward > 0) velocity = velocity.subtract(direction.scale(outward));
		}
		if (velocity.length() > MAX_LOAD_SPEED) velocity = velocity.normalize().scale(MAX_LOAD_SPEED);
		return new Step(velocity, predicted);
	}

	public record Step(Vec3 velocity, Vec3 predictedPosition) {}
}
