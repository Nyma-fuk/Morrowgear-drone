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

	static boolean reliefHolds(boolean liveEngagement, boolean activeRelief,
		boolean theaterPlanKnown, boolean originalAssigned, boolean observedStrength) {
		if (!liveEngagement || !activeRelief) return false;
		// The allocator is cached. Actual strength on the target is newer evidence than a
		// plan which still contains the aircraft that is completing service.
		return observedStrength || theaterPlanKnown && !originalAssigned;
	}

	static RechargeCompletion rechargeCompletion(boolean liveEngagement, boolean activeRelief,
		boolean theaterPlanKnown, boolean originalAssigned, boolean observedStrength,
		boolean reliefMissionAvailable) {
		if (reliefHolds(liveEngagement, activeRelief, theaterPlanKnown, originalAssigned, observedStrength)) {
			return reliefMissionAvailable ? RechargeCompletion.INHERIT_RELIEF_MISSION
				: RechargeCompletion.RESUME_ORIGINAL_MISSION;
		}
		return liveEngagement ? RechargeCompletion.RECLAIM_COMBAT
			: RechargeCompletion.RESUME_ORIGINAL_MISSION;
	}

	static boolean suppressCachedRedispatch(long now, long holdUntil, boolean playerEmergency) {
		return !playerEmergency && now <= holdUntil;
	}

	static boolean suppressReliefRedispatch(boolean sameTarget, boolean playerEmergency,
		boolean reliefCommitted, boolean committedStrength) {
		return sameTarget && !playerEmergency && reliefCommitted && committedStrength;
	}

	static boolean activeRelief(boolean combatActive, boolean sameTarget,
		boolean docked, boolean serviceReturn) {
		return combatActive && sameTarget && !docked && !serviceReturn;
	}

	static boolean committedRelief(boolean combatActive, boolean emergencyInterceptActive,
		boolean sameCombatTarget, boolean sameEmergencyTarget, boolean docked, boolean serviceReturn) {
		return !docked && !serviceReturn
			&& (combatActive && sameCombatTarget || emergencyInterceptActive && sameEmergencyTarget);
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

	enum RechargeCompletion { INHERIT_RELIEF_MISSION, RESUME_ORIGINAL_MISSION, RECLAIM_COMBAT }
}
