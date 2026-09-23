package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

final class SecurityPatrolPolicy {
	private SecurityPatrolPolicy() {
	}

	static Vec3 patrolPosition(Vec3 anchor, int index, int count, long tick, double radius) {
		int safeCount = Math.max(1, count);
		double patrolRadius = Math.max(AirframeEnvelope.orbitRadius(Math.min(8, count)), Math.min(28.0, radius * 0.68));
		int layerCount = Math.max(1, Math.min(8, safeCount - index / 8 * 8));
		double phase = tick * AirframeEnvelope.angularSpeed(0.035, patrolRadius + index / 8 * 2.5)
			+ Math.floorMod(index, 8) * Math.PI * 2.0 / layerCount;
		double layer = index / 8;
		return anchor.add(Math.cos(phase) * (patrolRadius + layer * 2.5), AirframeEnvelope.ORBIT_HEIGHT + layer * AirframeEnvelope.LAYER_HEIGHT,
			Math.sin(phase) * (patrolRadius + layer * 2.5));
	}

	static Vec3 interceptPosition(Vec3 anchor, Vec3 threat, int index, int count) {
		Vec3 outward = threat.subtract(anchor).multiply(1, 0, 1);
		if (outward.lengthSqr() < 0.001) outward = new Vec3(0, 0, 1);
		outward = outward.normalize();
		Vec3 right = new Vec3(-outward.z, 0, outward.x);
		double spacing = (index - (Math.max(1, count) - 1) / 2.0) * AirframeEnvelope.SLOT_DISTANCE;
		double standoff = Math.min(12, threat.subtract(anchor).horizontalDistance() * 0.55);
		return threat.subtract(outward.scale(standoff)).add(right.scale(spacing)).add(0, AirframeEnvelope.ORBIT_HEIGHT, 0);
	}
}
