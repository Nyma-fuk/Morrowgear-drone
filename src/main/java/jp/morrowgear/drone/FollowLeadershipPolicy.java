package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.List;

final class FollowLeadershipPolicy {
	private static final long LEVEL_THREE_FAILOVER_TICKS = 100;
	private static final long GENERAL_FAILOVER_TICKS = 240;

	private FollowLeadershipPolicy() {
	}

	static boolean leaseExpired(int recoveryLevel, long recoveryDurationTicks) {
		if (recoveryLevel <= 0) return false;
		if (recoveryLevel >= 3 && recoveryDurationTicks >= LEVEL_THREE_FAILOVER_TICKS) return true;
		return recoveryDurationTicks >= GENERAL_FAILOVER_TICKS;
	}

	static int successorIndex(List<Candidate> candidates) {
		return candidates.stream()
			.filter(candidate -> !candidate.recovering())
			.min(Comparator.comparingDouble(Candidate::distanceToOwner)
				.thenComparing(Comparator.comparingInt(Candidate::batteryPercent).reversed())
				.thenComparing(Candidate::unitId))
			.map(candidates::indexOf).orElse(-1);
	}

	static int removalSuccessorIndex(List<Candidate> candidates) {
		int healthy = successorIndex(candidates);
		if (healthy >= 0) return healthy;
		return candidates.stream()
			.min(Comparator.comparingInt(Candidate::recoveryLevel)
				.thenComparingDouble(Candidate::distanceToOwner)
				.thenComparing(Comparator.comparingInt(Candidate::batteryPercent).reversed())
				.thenComparing(Candidate::unitId))
			.map(candidates::indexOf).orElse(-1);
	}

	record Candidate(int recoveryLevel, double distanceToOwner, int batteryPercent, String unitId) {
		boolean recovering() {
			return recoveryLevel > 0;
		}
	}
}
