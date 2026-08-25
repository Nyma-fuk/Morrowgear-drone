package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.List;

final class GuardDispatchPolicy {
	static final int MIN_WEAPON_RESERVE = CombatPolicy.AUTO_WEAPON_RESERVE;
	private GuardDispatchPolicy() {}

	static int responderCount(int threatScore, int available) {
		int requested = threatScore >= 24 ? 3 : threatScore >= 14 ? 2 : threatScore >= 6 ? 1 : 0;
		return Math.min(Math.max(0, available), requested);
	}

	static int responderCount(int enemyThreat, int playerDanger, int attritionPressure, int available) {
		if (available <= 0 || enemyThreat <= 0 && playerDanger <= 0) return 0;
		double demand = enemyThreat * 0.72 + playerDanger * 0.58 + attritionPressure * 0.85;
		int requested = Math.max(1, (int)Math.ceil(demand / 14.0));
		return Math.min(Math.min(8, available), requested);
	}

	static List<Candidate> ranked(List<Candidate> candidates) {
		return ranked(candidates, false);
	}

	static List<Candidate> ranked(List<Candidate> candidates, boolean playerEmergency) {
		List<Candidate> normal = rankEligible(candidates, 15);
		return normal.isEmpty() && playerEmergency ? rankEligible(candidates, 5) : normal;
	}

	static List<Candidate> selected(List<Candidate> candidates, int requested, boolean playerEmergency) {
		if (requested <= 0) return List.of();
		List<Candidate> ranked = ranked(candidates, playerEmergency);
		List<Candidate> selected = ranked.stream().filter(Candidate::engaged)
			.limit(requested).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
		if (selected.size() >= requested) return List.copyOf(selected);
		for (Candidate candidate : ranked) {
			if (candidate.engaged() || selected.contains(candidate)) continue;
			selected.add(candidate);
			if (selected.size() >= requested) break;
		}
		return List.copyOf(selected);
	}

	private static List<Candidate> rankEligible(List<Candidate> candidates, int minimumBattery) {
		return candidates.stream().filter(candidate -> candidate.recoveryLevel() <= 0
				&& candidate.battery() >= minimumBattery
				&& candidate.weaponPower() >= MIN_WEAPON_RESERVE)
			.sorted(Comparator.comparingInt(GuardDispatchPolicy::priority).reversed()
				.thenComparingDouble(Candidate::distanceSquared).thenComparing(Candidate::unitId))
			.toList();
	}

	private static int priority(Candidate candidate) {
		return (candidate.engaged() ? 220 : 0)
			+ (candidate.rechargeReclaim() ? 90 : 0)
			+ (candidate.sameWing() ? 120 : 0)
			+ (candidate.idle() ? 70 : 0)
			+ (!candidate.docked() ? 35 : 0)
			+ Math.min(30, candidate.battery() / 3)
			- (candidate.activeMission() ? 25 : 0);
	}

	record Candidate(String unitId, boolean sameWing, boolean idle, boolean activeMission,
		boolean docked, boolean rechargeReclaim, boolean engaged, int battery, int weaponPower,
		int recoveryLevel, double distanceSquared) {}
}
