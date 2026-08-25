package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

final class OrbitAltitudePlanner {
	private static final int SAMPLE_INTERVAL_TICKS = 20;
	private static final int STALE_TICKS = 1200;
	private static final Map<Key, Plan> PLANS = new HashMap<>();

	private OrbitAltitudePlanner() {
	}

	static Vec3 stabilize(ServerLevel level, String missionId, Vec3 center, int count) {
		long tick = level.getGameTime();
		Key key = new Key(level.dimension().toString(), missionId);
		Plan plan = PLANS.get(key);
		boolean centerMoved = plan == null || horizontalDistance(plan.sampleCenter(), center) > 3.0;
		if (plan == null || centerMoved || tick - plan.sampleTick() >= SAMPLE_INTERVAL_TICKS) {
			double required = MissionFlightPlan.requiredOrbitCenterAltitude(level, center, count);
			OrbitAltitudeStabilizer.State state = OrbitAltitudeStabilizer.update(
				plan == null ? null : plan.state(), required);
			plan = new Plan(state, center, tick, tick);
			PLANS.put(key, plan);
		} else if (plan.lastUsedTick() != tick) {
			plan = new Plan(plan.state(), plan.sampleCenter(), plan.sampleTick(), tick);
			PLANS.put(key, plan);
		}
		if ((tick & 255L) == 0L) removeStale(tick);
		return new Vec3(center.x, plan.state().altitude(), center.z);
	}

	private static void removeStale(long tick) {
		Iterator<Plan> plans = PLANS.values().iterator();
		while (plans.hasNext()) {
			if (tick - plans.next().lastUsedTick() > STALE_TICKS) plans.remove();
		}
	}

	private static double horizontalDistance(Vec3 first, Vec3 second) {
		return Math.hypot(first.x - second.x, first.z - second.z);
	}

	private record Key(String dimension, String missionId) {
	}

	private record Plan(OrbitAltitudeStabilizer.State state, Vec3 sampleCenter,
		long sampleTick, long lastUsedTick) {
	}
}
