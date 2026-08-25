package jp.morrowgear.drone;

import net.minecraft.util.Mth;

final class DroneSubsystemPolicy {
	static final int MAX = 1000;
	static final int SERVICE_THRESHOLD = 420;
	static final int CRITICAL_THRESHOLD = 220;

	private DroneSubsystemPolicy() {}

	static int wear(int condition, float damage, boolean primary) {
		int loss = Math.max(1, Math.round(Math.max(0.0f, damage) * (primary ? 18.0f : 7.0f)));
		return Mth.clamp(condition - loss, 0, MAX);
	}

	static int repair(int condition, int supplied) {
		return Mth.clamp(condition + Math.max(0, supplied), 0, MAX);
	}

	static boolean serviceRequired(int propulsion, int sensor, int payload) {
		return Math.min(propulsion, Math.min(sensor, payload)) <= SERVICE_THRESHOLD;
	}

	static int powerPenalty(int propulsion) {
		if (propulsion <= CRITICAL_THRESHOLD) return 2;
		return propulsion <= SERVICE_THRESHOLD ? 1 : 0;
	}

	static double sensorScale(int sensor) {
		return sensor <= CRITICAL_THRESHOLD ? 0.55 : sensor <= SERVICE_THRESHOLD ? 0.75 : 1.0;
	}

	static double payloadScale(int payload) {
		return payload <= CRITICAL_THRESHOLD ? 0.50 : payload <= SERVICE_THRESHOLD ? 0.75 : 1.0;
	}
}
