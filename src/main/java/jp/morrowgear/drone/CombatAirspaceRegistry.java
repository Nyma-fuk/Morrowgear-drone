package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class CombatAirspaceRegistry {
	private static final long LEASE_TICKS = 100L;
	private static final Map<EngagementKey, Map<String, Lease>> LEASES = new HashMap<>();

	private CombatAirspaceRegistry() {}

	static synchronized CombatPolicy.AirspaceSlot reserve(String dimension, UUID owner, int targetId,
		String maneuverKey, long now) {
		prune(now);
		EngagementKey engagement = new EngagementKey(dimension, owner, targetId);
		Map<String, Lease> leases = LEASES.computeIfAbsent(engagement, ignored -> new HashMap<>());
		Lease existing = leases.get(maneuverKey);
		if (existing != null) {
			leases.put(maneuverKey, new Lease(existing.index(), now + LEASE_TICKS));
			return new CombatPolicy.AirspaceSlot(existing.index(), activeCount(leases));
		}
		Set<Integer> used = new HashSet<>();
		leases.values().forEach(lease -> used.add(lease.index()));
		int index = 0;
		while (used.contains(index)) index++;
		leases.put(maneuverKey, new Lease(index, now + LEASE_TICKS));
		return new CombatPolicy.AirspaceSlot(index, activeCount(leases));
	}

	static synchronized void clear() { LEASES.clear(); }

	private static int activeCount(Map<String, Lease> leases) {
		return Math.max(1, leases.values().stream().mapToInt(Lease::index).max().orElse(0) + 1);
	}

	private static void prune(long now) {
		LEASES.values().forEach(leases -> leases.values().removeIf(lease -> lease.expiresAt() < now));
		LEASES.entrySet().removeIf(entry -> entry.getValue().isEmpty());
	}

	private record EngagementKey(String dimension, UUID owner, int targetId) {}
	private record Lease(int index, long expiresAt) {}
}
