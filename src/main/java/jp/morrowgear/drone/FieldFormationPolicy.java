package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

final class FieldFormationPolicy {
	private FieldFormationPolicy() {
	}

	static Vec3 orbit(Vec3 center, int slot, int count, double radius, double height,
		long gameTime, double angularSpeed, double phaseBias) {
		int safeCount = Math.max(1, count);
		double phase = gameTime * angularSpeed + phaseBias
			+ Math.PI * 2.0 * Math.max(0, slot) / safeCount;
		return center.add(Math.cos(phase) * radius, height, Math.sin(phase) * radius);
	}

	static Vec3 escort(Vec3 leader, int slot, int count, double radius, double height,
		long gameTime, double phaseBias) {
		return orbit(leader, slot, count, radius, height, gameTime, 0.022, phaseBias);
	}
}
