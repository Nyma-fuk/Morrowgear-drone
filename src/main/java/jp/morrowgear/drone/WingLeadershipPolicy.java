package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.List;

final class WingLeadershipPolicy {
	private WingLeadershipPolicy() {
	}

	static boolean temporarilyDetached(boolean serviceReturn, boolean emergencyIntercept,
		boolean combatActive) {
		return serviceReturn || emergencyIntercept || combatActive;
	}

	static int successorIndex(List<Candidate> candidates) {
		List<Candidate> available = candidates.stream().filter(candidate -> !candidate.detached()).toList();
		if (available.isEmpty()) return -1;
		List<Candidate> healthy = available.stream().filter(candidate -> candidate.recoveryLevel() <= 0).toList();
		List<Candidate> pool = healthy.isEmpty() ? available : healthy;
		Candidate selected = pool.stream().min(Comparator
			.comparingDouble(Candidate::distanceToObjective)
			.thenComparing(Comparator.comparingInt(Candidate::batteryPercent).reversed())
			.thenComparingInt(Candidate::currentRank)
			.thenComparing(Candidate::unitId)).orElse(null);
		return selected == null ? -1 : candidates.indexOf(selected);
	}

	static boolean leaderAvailable(List<Candidate> candidates, String leaderUnitId) {
		return leaderUnitId != null && !leaderUnitId.isBlank() && candidates.stream()
			.anyMatch(candidate -> !candidate.detached() && candidate.recoveryLevel() <= 0
				&& candidate.unitId().equals(leaderUnitId));
	}

	record Candidate(boolean detached, int recoveryLevel, double distanceToObjective,
		int batteryPercent, int currentRank, String unitId) {
	}
}
