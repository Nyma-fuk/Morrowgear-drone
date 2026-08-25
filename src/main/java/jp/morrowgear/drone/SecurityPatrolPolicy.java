package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

final class SecurityPatrolPolicy {
	private SecurityPatrolPolicy() {
	}

	static Vec3 patrolPosition(Vec3 anchor, int index, int count, long tick, double radius) {
		int safeCount = Math.max(1, count);
		double patrolRadius = Math.max(4.0, Math.min(14.0, radius * 0.68));
		double phase = tick * 0.045 + Math.floorMod(index, safeCount) * Math.PI * 2.0 / safeCount;
		double layer = index / 8;
		return anchor.add(Math.cos(phase) * (patrolRadius + layer * 1.8), 3.8 + layer * 1.6,
			Math.sin(phase) * (patrolRadius + layer * 1.8));
	}

	static Vec3 interceptPosition(Vec3 anchor, Vec3 threat, int index, int count) {
		Vec3 outward = threat.subtract(anchor).multiply(1, 0, 1);
		if (outward.lengthSqr() < 0.001) outward = new Vec3(0, 0, 1);
		outward = outward.normalize();
		Vec3 right = new Vec3(-outward.z, 0, outward.x);
		double spacing = (index - (Math.max(1, count) - 1) / 2.0) * 1.45;
		return threat.subtract(outward.scale(2.4)).add(right.scale(spacing)).add(0, 1.7, 0);
	}
}
