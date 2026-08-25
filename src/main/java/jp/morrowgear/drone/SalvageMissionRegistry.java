package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class SalvageMissionRegistry {
	private static final Map<UUID, UUID> RESERVATIONS = new HashMap<>();
	private static final Set<UUID> SUSPENDED = new HashSet<>();

	private SalvageMissionRegistry() {
	}

	static synchronized boolean reserve(UUID target, UUID responder) {
		UUID current = RESERVATIONS.get(target);
		if (current != null && !current.equals(responder)) return false;
		RESERVATIONS.put(target, responder);
		return true;
	}

	static synchronized boolean isReserved(UUID target) {
		return RESERVATIONS.containsKey(target);
	}

	static synchronized boolean heldBy(UUID target, UUID responder) {
		return responder.equals(RESERVATIONS.get(target));
	}

	static synchronized void setSuspended(UUID target, UUID responder, boolean suspended) {
		if (!heldBy(target, responder)) return;
		if (suspended) SUSPENDED.add(target);
		else SUSPENDED.remove(target);
	}

	static synchronized boolean isSuspended(UUID target) {
		return SUSPENDED.contains(target);
	}

	static synchronized void release(UUID target, UUID responder) {
		if (!responder.equals(RESERVATIONS.get(target))) return;
		RESERVATIONS.remove(target);
		SUSPENDED.remove(target);
	}

	static synchronized void clear() {
		RESERVATIONS.clear();
		SUSPENDED.clear();
	}
}
