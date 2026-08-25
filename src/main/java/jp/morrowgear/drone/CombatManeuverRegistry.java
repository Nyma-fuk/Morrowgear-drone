package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.phys.Vec3;

final class CombatManeuverRegistry {
	private static final long LEASE_TICKS = 200L;
	private static final Map<Key, Plan> PLANS = new HashMap<>();

	private CombatManeuverRegistry() {}

	static synchronized long epoch(String dimension, UUID owner, int targetId,
		String maneuverKey, long now) {
		return plan(dimension, owner, targetId, maneuverKey, null, now).epoch();
	}

	static synchronized Vec3 axis(String dimension, UUID owner, int targetId,
		String maneuverKey, Vec3 suggested, long now) {
		return plan(dimension, owner, targetId, maneuverKey, suggested, now).axis();
	}

	static synchronized void clear() {
		PLANS.clear();
	}

	private static Plan plan(String dimension, UUID owner, int targetId,
		String maneuverKey, Vec3 suggested, long now) {
		prune(now);
		Key key = new Key(dimension, owner, targetId, maneuverKey == null ? "" : maneuverKey);
		Plan existing = PLANS.get(key);
		Vec3 normalized = horizontalUnit(suggested);
		if (existing == null) {
			existing = new Plan(now, normalized, now + LEASE_TICKS);
		} else {
			Vec3 axis = existing.axis() == null ? normalized : existing.axis();
			existing = new Plan(existing.epoch(), axis, now + LEASE_TICKS);
		}
		PLANS.put(key, existing);
		return existing;
	}

	private static Vec3 horizontalUnit(Vec3 value) {
		if (value == null) return null;
		Vec3 horizontal = value.multiply(1, 0, 1);
		return horizontal.lengthSqr() < 0.001 ? new Vec3(0, 0, 1) : horizontal.normalize();
	}

	private static void prune(long now) {
		PLANS.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
	}

	private record Key(String dimension, UUID owner, int targetId, String maneuverKey) {}
	private record Plan(long epoch, Vec3 axis, long expiresAt) {}
}
