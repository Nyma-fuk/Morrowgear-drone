package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AdaptiveRechargeRuntimeStateTest {
	@Test void realVerifierTimingRechargesThenInheritsTheObservedReliefMission() {
		int returnTicks = 151;
		int weapon = 0;
		int credit = DockSupplyPolicy.LASER_CELL_ENERGY - 1;
		String observedReliefMission = "";

		// The real log records combat at 104778, RTB at 104779 and touchdown at 104930.
		for (int tick = 0; tick < returnTicks; tick++) {
			if (tick == 1) observedReliefMission = "adapt-rotation-3";
			assertEquals(0, weapon);
		}

		int serviceTicks = 0;
		while (weapon < 900) {
			var transfer = DockSupplyPolicy.takePack(credit,
				Math.min(DockServicePolicy.WEAPON_CHARGE_PER_TICK, 1000 - weapon),
				DockSupplyPolicy.LASER_CELL_ENERGY, false);
			weapon += transfer.supplied();
			credit = transfer.credit();
			serviceTicks++;
		}

		assertEquals(113, serviceTicks);
		assertEquals(904, weapon);
		assertEquals(95, credit);
		assertEquals(GuardDispatchPolicy.RechargeCompletion.INHERIT_RELIEF_MISSION,
			GuardDispatchPolicy.rechargeCompletion(true, true, true, true, true,
				!observedReliefMission.isBlank()));
		assertTrue(returnTicks + serviceTicks < 1400);
	}

	@Test void inheritedMissionSurvivesTheRealVerifierWindowWhileReliefStillOwnsTarget() {
		String inheritedMission = "adapt-rotation-3";
		String legacyInheritedMission = inheritedMission;
		boolean serviceReturn = false;
		long theaterCacheExpiry = CombatTheaterPolicy.PLAN_TTL_TICKS;

		for (int tick = 0; tick < 1400; tick++) {
			boolean cachedHold = GuardDispatchPolicy.suppressCachedRedispatch(
				tick, theaterCacheExpiry, false);
			if (!cachedHold) legacyInheritedMission = "";
			boolean actualReliefHold = GuardDispatchPolicy.suppressReliefRedispatch(
				true, false, true, true);
			if (!cachedHold && !actualReliefHold) {
				serviceReturn = true;
				inheritedMission = "";
			}
		}

		assertEquals("", legacyInheritedMission);
		assertTrue(!serviceReturn);
		assertEquals("adapt-rotation-3", inheritedMission);
	}
}
