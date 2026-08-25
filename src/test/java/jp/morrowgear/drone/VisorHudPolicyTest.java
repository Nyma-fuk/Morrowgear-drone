package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VisorHudPolicyTest {
	@Test
	void densityScalesAtDocumentedFleetBoundaries() {
		assertEquals(VisorHudPolicy.Density.INDIVIDUAL, VisorHudPolicy.density(8));
		assertEquals(VisorHudPolicy.Density.WING, VisorHudPolicy.density(9));
		assertEquals(VisorHudPolicy.Density.WING, VisorHudPolicy.density(32));
		assertEquals(VisorHudPolicy.Density.OPERATION, VisorHudPolicy.density(33));
		assertEquals(VisorHudPolicy.Density.OPERATION, VisorHudPolicy.density(128));
		assertEquals(VisorHudPolicy.Density.FLEET, VisorHudPolicy.density(129));
	}

	@Test
	void denseFleetsNeverCreateUnboundedWorldMarkers() {
		assertEquals(8, VisorHudPolicy.markerBudget(8));
		assertEquals(12, VisorHudPolicy.markerBudget(24));
		assertEquals(8, VisorHudPolicy.markerBudget(96));
		assertEquals(8, VisorHudPolicy.markerBudget(512));
		assertEquals(2, VisorHudPolicy.worldLabelBudget(512));
	}

	@Test
	void ordinaryContactsFadeInsideReticleSafeZoneWithoutHidingAlerts() {
		assertEquals(VisorHudPolicy.NORMAL_SAFE_ZONE_ALPHA,
			VisorHudPolicy.markerAlpha(18, 50, false, false));
		assertEquals(VisorHudPolicy.NORMAL_ALPHA,
			VisorHudPolicy.markerAlpha(18, 50, true, false));
		assertEquals(VisorHudPolicy.NORMAL_ALPHA,
			VisorHudPolicy.markerAlpha(18, 50, false, true));
	}

	@Test
	void alertPrioritySeparatesImmediateDangerFromOperationalWarnings() {
		assertEquals(0, VisorHudPolicy.alertPriority(28, 0, 100, false));
		assertEquals(0, VisorHudPolicy.alertPriority(0, 0, 0, false));
		assertEquals(1, VisorHudPolicy.alertPriority(0, 1, 80, false));
		assertEquals(2, VisorHudPolicy.alertPriority(8, 0, 80, false));
		assertEquals(3, VisorHudPolicy.alertPriority(0, 0, 80, false));
	}

	@Test
	void clusterHysteresisPreventsRapidSplitAndMerge() {
		assertTrue(VisorHudPolicy.clusterTogether(3.9, 7.9, true, false));
		assertFalse(VisorHudPolicy.clusterTogether(4.1, 7.9, true, false));
		assertTrue(VisorHudPolicy.clusterTogether(5.9, 7.9, true, true));
		assertFalse(VisorHudPolicy.clusterTogether(6.1, 7.9, true, true));
	}

	@Test
	void physicalPixelLayoutMatchesApprovedMockupAtFullHd() {
		assertEquals(1836, VisorHudPolicy.topStripWidth(1920));
		assertEquals(380, VisorHudPolicy.sidePanelWidth(1920));
		assertEquals(820, VisorHudPolicy.actionRailWidth(1920));
		assertTrue(VisorHudPolicy.sidePanelWidth(1920) < 1920 * 0.20);
	}

	@Test
	void adaptivePlanCollapsesLargeFleetsWithoutHidingExceptions() {
		var solo = VisorHudPolicy.displayPlan(6, 1, 0, 0, 1920, 1080);
		assertEquals(VisorHudPolicy.Detail.UNIT, solo.detail());
		assertEquals(6, solo.markerBudget());

		var operation = VisorHudPolicy.displayPlan(96, 12, 7, 18, 1920, 1080);
		assertEquals(VisorHudPolicy.Detail.OPERATION, operation.detail());
		assertEquals(14, operation.markerBudget());
		assertEquals(3, operation.alertRows());
		assertEquals(6, operation.edgeBudget());

		var constrained = VisorHudPolicy.displayPlan(96, 12, 7, 18, 1024, 576);
		assertTrue(constrained.compactContext());
		assertEquals(8, constrained.markerBudget());
		assertEquals(1, constrained.alertRows());
	}

	@Test
	void persistentBeaconCannotCrashAnEmptyLocalRoster() {
		assertEquals(0, VisorHudPolicy.visibleAlertCount(1, 0));
		assertEquals(1, VisorHudPolicy.visibleAlertCount(3, 1));
		assertEquals(0, VisorHudPolicy.visibleAlertCount(0, 3));
	}

	@Test
	void informationPriorityKeepsContextStableButSurfacesOperationalExceptions() {
		assertEquals(0, VisorHudPolicy.informationPriority(true, false, false,
			0, 0, 100, 100, false));
		assertEquals(1, VisorHudPolicy.informationPriority(false, false, true,
			12, 0, 70, 80, false));
		assertEquals(1, VisorHudPolicy.informationPriority(false, false, false,
			0, 1, 70, 80, false));
		assertEquals(2, VisorHudPolicy.informationPriority(false, true, false,
			0, 0, 100, 100, false));
		assertEquals(3, VisorHudPolicy.informationPriority(false, false, false,
			0, 0, 100, 100, false));
	}

	@Test
	void detailLevelChangesOnlyAtFleetBoundaries() {
		assertEquals(VisorHudPolicy.Detail.UNIT,
			VisorHudPolicy.displayPlan(8, 1, 0, 0, 1920, 1080).detail());
		assertEquals(VisorHudPolicy.Detail.WING,
			VisorHudPolicy.displayPlan(9, 2, 0, 0, 1920, 1080).detail());
		assertEquals(VisorHudPolicy.Detail.WING,
			VisorHudPolicy.displayPlan(32, 4, 0, 0, 1920, 1080).detail());
		assertEquals(VisorHudPolicy.Detail.OPERATION,
			VisorHudPolicy.displayPlan(33, 5, 0, 0, 1920, 1080).detail());
		assertEquals(VisorHudPolicy.Detail.OPERATION,
			VisorHudPolicy.displayPlan(128, 16, 0, 0, 1920, 1080).detail());
		assertEquals(VisorHudPolicy.Detail.FLEET,
			VisorHudPolicy.displayPlan(129, 17, 0, 0, 1920, 1080).detail());
		assertEquals(VisorHudPolicy.Detail.FLEET,
			VisorHudPolicy.displayPlan(512, 64, 100, 100, 1920, 1080).detail());
		assertTrue(VisorHudPolicy.displayPlan(512, 64, 100, 100, 1920, 1080).markerBudget() <= 14);
	}

	@Test
	void combatAssignmentsScaleFromUnitsToMultiWingTaskForces() {
		assertEquals(VisorHudPolicy.CombatAssignment.UNIT_IDS,
			VisorHudPolicy.combatAssignment(1, 1));
		assertEquals(VisorHudPolicy.CombatAssignment.UNIT_IDS,
			VisorHudPolicy.combatAssignment(2, 2));
		assertEquals(VisorHudPolicy.CombatAssignment.WING,
			VisorHudPolicy.combatAssignment(3, 1));
		assertEquals(VisorHudPolicy.CombatAssignment.TASK_FORCE,
			VisorHudPolicy.combatAssignment(3, 2));
		assertEquals(6, VisorHudPolicy.engagementDisplayLimit(VisorHudPolicy.Detail.OPERATION));
		assertEquals(4, VisorHudPolicy.engagementDisplayLimit(VisorHudPolicy.Detail.FLEET));
	}
}
