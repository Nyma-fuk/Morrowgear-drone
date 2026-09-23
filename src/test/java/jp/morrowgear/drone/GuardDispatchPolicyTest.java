package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GuardDispatchPolicyTest {
	@Test void sameWingAvailableGuardWinsAndResponseScalesWithThreat() {
		var ranked = GuardDispatchPolicy.ranked(List.of(
			new GuardDispatchPolicy.Candidate("A", false, true, false, false, false, false, 100, 100, 0, 9),
			new GuardDispatchPolicy.Candidate("B", true, false, true, false, false, false, 80, 100, 0, 16),
			new GuardDispatchPolicy.Candidate("C", true, true, false, true, false, false, 90, 100, 0, 25)));
		assertEquals("C", ranked.getFirst().unitId());
		assertEquals(1, GuardDispatchPolicy.responderCount(8, ranked.size()));
		assertEquals(3, GuardDispatchPolicy.responderCount(30, ranked.size()));
	}

	@Test
	void playerEmergencyCanUseALowBatteryGuardWhenNoNormalCandidateExists() {
		var candidates = List.of(
			new GuardDispatchPolicy.Candidate("LOW", false, true, false, false, false, false, 10, 100, 0, 4),
			new GuardDispatchPolicy.Candidate("EMPTY", true, true, false, false, false, false, 4, 100, 0, 1));
		assertTrue(GuardDispatchPolicy.ranked(candidates).isEmpty());
		assertEquals(List.of("LOW"), GuardDispatchPolicy.ranked(candidates, true).stream()
			.map(GuardDispatchPolicy.Candidate::unitId).toList());
	}

	@Test void highPowerEnemyAndCombatLossesScaleToEightResponders() {
		assertEquals(1, GuardDispatchPolicy.responderCount(10, 4, 0, 8));
		assertEquals(6, GuardDispatchPolicy.responderCount(82, 20, 0, 8));
		assertEquals(8, GuardDispatchPolicy.responderCount(82, 45, 35, 20));
	}

	@Test void activeReliefRemainsAssignedWhenRequiredStrengthIsAlreadyMet() {
		var candidates = List.of(
			new GuardDispatchPolicy.Candidate("TEMP", false, false, true, false, false, true, 90, 90, 0, 4),
			new GuardDispatchPolicy.Candidate("ORIGINAL", false, false, true, false, true, false, 85, 85, 0, 16));
		assertEquals(List.of("TEMP"), GuardDispatchPolicy.selected(candidates, 1, false).stream()
			.map(GuardDispatchPolicy.Candidate::unitId).toList());
	}

	@Test void rechargedOriginalFillsARealResponderVacancy() {
		var candidates = List.of(
			new GuardDispatchPolicy.Candidate("TEMP", false, false, true, false, false, true, 90, 90, 0, 4),
			new GuardDispatchPolicy.Candidate("ORIGINAL", false, false, true, false, true, false, 85, 85, 0, 16));
		assertEquals(List.of("TEMP", "ORIGINAL"), GuardDispatchPolicy.selected(candidates, 2, false).stream()
			.map(GuardDispatchPolicy.Candidate::unitId).toList());
	}

	@Test void theaterPlanKeepsRearmedOriginalOnMissionWhenReliefOwnsTheFront() {
		assertTrue(GuardDispatchPolicy.reliefHolds(true, true, true, false, false));
	}

	@Test void observedStrengthOverridesAStalePlanThatStillAssignsTheRearmedOriginal() {
		assertTrue(GuardDispatchPolicy.reliefHolds(true, true, true, true, true));
	}

	@Test void actualVacancyStillRecallsTheRearmedOriginal() {
		assertTrue(!GuardDispatchPolicy.reliefHolds(true, true, true, true, false));
		assertTrue(!GuardDispatchPolicy.reliefHolds(true, false, true, false, true));
	}

	@Test void localStrengthRemainsFallbackWithoutATheaterPlan() {
		assertTrue(GuardDispatchPolicy.reliefHolds(true, true, false, false, true));
		assertTrue(!GuardDispatchPolicy.reliefHolds(true, true, false, false, false));
	}

	@Test void rechargeCompletionInheritsOnlyAnObservedReliefMissionAndOtherwiseFallsBackSafely() {
		assertEquals(GuardDispatchPolicy.RechargeCompletion.INHERIT_RELIEF_MISSION,
			GuardDispatchPolicy.rechargeCompletion(true, true, true, true, true, true));
		assertEquals(GuardDispatchPolicy.RechargeCompletion.RESUME_ORIGINAL_MISSION,
			GuardDispatchPolicy.rechargeCompletion(true, true, true, true, true, false));
		assertEquals(GuardDispatchPolicy.RechargeCompletion.RECLAIM_COMBAT,
			GuardDispatchPolicy.rechargeCompletion(true, false, true, true, false, false));
		assertEquals(GuardDispatchPolicy.RechargeCompletion.RESUME_ORIGINAL_MISSION,
			GuardDispatchPolicy.rechargeCompletion(false, false, true, false, false, false));
	}

	@Test void cachedRedispatchHoldIsBoundedAndPlayerEmergencyBypassesIt() {
		assertTrue(GuardDispatchPolicy.suppressCachedRedispatch(100, 110, false));
		assertTrue(GuardDispatchPolicy.suppressCachedRedispatch(110, 110, false));
		assertTrue(!GuardDispatchPolicy.suppressCachedRedispatch(111, 110, false));
		assertTrue(!GuardDispatchPolicy.suppressCachedRedispatch(100, 110, true));
	}

	@Test void actualReliefCoverageOutlivesTheaterCacheButNotItsRealCommitment() {
		assertTrue(GuardDispatchPolicy.suppressReliefRedispatch(true, false, true, true));
		assertTrue(!GuardDispatchPolicy.suppressReliefRedispatch(false, false, true, true));
		assertTrue(!GuardDispatchPolicy.suppressReliefRedispatch(true, true, true, true));
		assertTrue(!GuardDispatchPolicy.suppressReliefRedispatch(true, false, false, true));
		assertTrue(!GuardDispatchPolicy.suppressReliefRedispatch(true, false, true, false));
	}

	@Test void onlyAnActiveSameTargetAircraftOutsideServiceCountsAsRelief() {
		assertTrue(GuardDispatchPolicy.activeRelief(true, true, false, false));
		assertTrue(!GuardDispatchPolicy.activeRelief(false, true, false, false));
		assertTrue(!GuardDispatchPolicy.activeRelief(true, false, false, false));
		assertTrue(!GuardDispatchPolicy.activeRelief(true, true, true, false));
		assertTrue(!GuardDispatchPolicy.activeRelief(true, true, false, true));
	}

	@Test void anInterceptingReliefRemainsCommittedBetweenWeaponPasses() {
		assertTrue(GuardDispatchPolicy.committedRelief(false, true, false, true, false, false));
		assertTrue(GuardDispatchPolicy.committedRelief(true, false, true, false, false, false));
		assertTrue(!GuardDispatchPolicy.committedRelief(false, true, false, false, false, false));
		assertTrue(!GuardDispatchPolicy.committedRelief(true, false, true, false, true, false));
		assertTrue(!GuardDispatchPolicy.committedRelief(true, false, true, false, false, true));
	}
}
