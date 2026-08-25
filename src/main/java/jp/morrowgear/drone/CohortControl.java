package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CohortControl {
	private CohortControl() {
	}

	static String dominantCohort(List<String> cohortIds) {
		Map<String, Integer> counts = new HashMap<>();
		cohortIds.stream().filter(id -> id != null && !id.isBlank())
			.forEach(id -> counts.merge(id, 1, Integer::sum));
		return counts.entrySet().stream()
			.max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
				.thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
			.map(Map.Entry::getKey).orElse("");
	}

	static boolean shouldMerge(int firstSize, int secondSize, double closestDistance) {
		return firstSize > 0 && secondSize > 0 && closestDistance <= mergeDistance(firstSize + secondSize);
	}

	static double mergeDistance(int combinedSize) {
		return Math.min(10.0, 4.5 + Math.sqrt(Math.max(1, combinedSize)) * 1.35);
	}

	static int retainedCohortSize(int firstSize, int secondSize) {
		return Math.max(firstSize, secondSize);
	}

	static double assemblySpeedLimit(double requestedSpeed, int joined, int available,
		double distanceToDestination) {
		if (available <= 1 || joined >= available) return requestedSpeed;
		double ratio = Math.max(0.0, Math.min(1.0, joined / (double) available));
		double limit = 0.24 + ratio * 0.32;
		if (distanceToDestination < 18.0) limit = Math.min(limit, 0.22);
		return Math.min(requestedSpeed, limit);
	}

	static double convergenceSpeedLimit(double requestedSpeed, double slotDistance,
		double leaderSpeed) {
		if (slotDistance >= 16.0) return Math.max(requestedSpeed, 1.08);
		if (slotDistance >= 8.0) {
			double progress = (slotDistance - 8.0) / 8.0;
			return Math.max(requestedSpeed, 0.78 + progress * 0.30);
		}
		double closure = slotDistance <= 3.0 ? 0.10 : 0.10 + (slotDistance - 3.0) * 0.065;
		return Math.max(0.34, Math.min(0.82, leaderSpeed + closure));
	}

	static boolean formationComplete(int joined, int available) {
		return available > 0 && joined >= available;
	}

	static boolean patrolReleaseAllowed(int joined, int assigned, boolean joinedCohortArrived,
		boolean recoveryStraggler) {
		if (!joinedCohortArrived || joined <= 0 || assigned <= 0) return false;
		if (joined >= assigned) return true;
		if (recoveryStraggler) return true;
		return joined >= SwarmFormation.convergenceQuorum(assigned);
	}

	static boolean wingReadyForPatrol(int moving, int assigned, boolean allMovingArrived,
		boolean healthyConverging, boolean recoveryStraggler) {
		if (moving <= 0 || assigned <= 0 || !allMovingArrived || healthyConverging) return false;
		if (moving >= assigned) return true;
		return recoveryStraggler && moving >= SwarmFormation.convergenceQuorum(assigned);
	}

	static boolean readyToJoin(double slotDistance) {
		return slotDistance <= 2.8;
	}

	static boolean synchronizedJoinReady(List<Double> slotDistances) {
		return !slotDistances.isEmpty() && slotDistances.stream().allMatch(CohortControl::readyToJoin);
	}

	static int destinationLeaderIndex(List<Double> destinationDistances, List<String> unitIds) {
		if (destinationDistances.isEmpty() || destinationDistances.size() != unitIds.size()) return -1;
		int best = 0;
		for (int index = 1; index < destinationDistances.size(); index++) {
			int distanceOrder = Double.compare(destinationDistances.get(index), destinationDistances.get(best));
			if (distanceOrder < 0 || distanceOrder == 0 && unitIds.get(index).compareTo(unitIds.get(best)) < 0) {
				best = index;
			}
		}
		return best;
	}
}
