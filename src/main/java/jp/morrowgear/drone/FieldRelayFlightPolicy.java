package jp.morrowgear.drone;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Stable terrain-plane flight and capture envelope for mobile FIELD relays. */
final class FieldRelayFlightPolicy {
	static final double HOLD_CLEARANCE = 28.0;
	static final double LOITER_RADIUS = 7.5;
	static final double LOITER_CAPTURE = 12.0;
	static final double LOITER_VERTICAL_CAPTURE = 4.0;
	static final double LOITER_ANGULAR_SPEED = Math.PI * 2.0 / 600.0;
	static final double TRANSIT_SPEED = 0.88;
	static final double CAPTURE_SPEED = 0.34;

	private FieldRelayFlightPolicy() {}

	static Vec3 guidance(Vec3 slot) { return slot; }

	static double speed(double distance) {
		return distance > 18.0 ? TRANSIT_SPEED : CAPTURE_SPEED;
	}

	static Vec3 loiterTarget(Vec3 slot, UUID fieldId, long tick) {
		double phase = Math.floorMod(fieldId.hashCode(), 65_536) * Math.PI * 2.0 / 65_536.0;
		double angle = phase + tick * LOITER_ANGULAR_SPEED;
		return slot.add(Math.cos(angle) * LOITER_RADIUS, 0.0, Math.sin(angle) * LOITER_RADIUS);
	}

	static Vec3 loiterVelocity(Vec3 slot, UUID fieldId, long tick) {
		return loiterTarget(slot, fieldId, tick + 1L).subtract(loiterTarget(slot, fieldId, tick));
	}

	static boolean loiterReady(Vec3 position, Vec3 slot) {
		return position.subtract(slot).horizontalDistance() <= LOITER_CAPTURE
			&& Math.abs(position.y - slot.y) <= LOITER_VERTICAL_CAPTURE;
	}

	static boolean established(Vec3 position, Vec3 slot) {
		return loiterReady(position, slot);
	}
}
