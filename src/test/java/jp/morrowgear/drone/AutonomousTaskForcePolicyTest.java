package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.util.stream.Stream;

final class AutonomousTaskForcePolicyTest {
	@Test
	void standardOreOperationBuildsBalancedSixAircraftTaskForce() {
		List<AutonomousTaskForcePolicy.Candidate> candidates = List.of(
			candidate(1, DroneRole.ENGINEER, 90), candidate(2, DroneRole.ENGINEER, 80),
			candidate(3, DroneRole.SCOUT, 95), candidate(4, DroneRole.CARGO, 85),
			candidate(5, DroneRole.SECURITY, 88), candidate(6, DroneRole.FIELD, 82));
		AutonomousTaskForcePolicy.Plan plan = AutonomousTaskForcePolicy.plan(FieldOperationType.ORE, 24, candidates);
		assertTrue(plan.executable());
		assertEquals(6, plan.selected().size());
		assertEquals("ENG 2 / SCT 1 / CRG 1 / SEC 1 / FLD 1", plan.rosterLabel());
		assertEquals("FULL CAPABILITY", plan.missingLabel());
	}

	@Test
	void excavationAddsASecondCargoAircraftAndNeverExceedsWingCapacity() {
		List<AutonomousTaskForcePolicy.Candidate> candidates = new ArrayList<>();
		int id = 1;
		for (DroneRole role : DroneRole.values()) {
			for (int count = 0; count < 4; count++) candidates.add(candidate(id++, role, 100 - count));
		}
		AutonomousTaskForcePolicy.Plan plan = AutonomousTaskForcePolicy.plan(FieldOperationType.EXCAVATE, 8, candidates);
		assertTrue(plan.executable());
		assertEquals(8, plan.selected().size());
		assertEquals(2, plan.assigned().get(DroneRole.CARGO));
		assertEquals(2, plan.assigned().get(DroneRole.SECURITY));
	}

	@Test
	void activeLowPowerAndUnderServicedDockedAircraftAreNotStolen() {
		List<AutonomousTaskForcePolicy.Candidate> candidates = List.of(
			new AutonomousTaskForcePolicy.Candidate(1, "ACTIVE", DroneRole.ENGINEER, false, false, 100, 100, 1),
			new AutonomousTaskForcePolicy.Candidate(2, "LOW", DroneRole.ENGINEER, true, false, 34, 100, 2),
			new AutonomousTaskForcePolicy.Candidate(3, "DOCK", DroneRole.ENGINEER, true, true, 89, 100, 3),
			candidate(4, DroneRole.ENGINEER, 70));
		AutonomousTaskForcePolicy.Plan plan = AutonomousTaskForcePolicy.plan(FieldOperationType.FORESTRY, 12, candidates);
		assertTrue(plan.executable());
		assertEquals(List.of("U4"), plan.selected().stream().map(AutonomousTaskForcePolicy.Candidate::unitId).toList());
	}

	@Test
	void missingEngineerBlocksLaunchInsteadOfCreatingOrphanMission() {
		AutonomousTaskForcePolicy.Plan plan = AutonomousTaskForcePolicy.plan(FieldOperationType.ORE, 24, List.of(
			candidate(1, DroneRole.SCOUT, 100), candidate(2, DroneRole.CARGO, 100)));
		assertFalse(plan.executable());
		assertEquals("ENGINEER UNAVAILABLE", plan.status());
	}

	@Test
	void degradedTaskForceReportsEveryMissingCapability() {
		AutonomousTaskForcePolicy.Plan plan = AutonomousTaskForcePolicy.plan(FieldOperationType.FORESTRY, 12, List.of(
			candidate(1, DroneRole.ENGINEER, 100)));
		assertTrue(plan.executable());
		assertTrue(plan.missingLabel().contains("ENG x1"));
		assertTrue(plan.missingLabel().contains("SCT x1"));
		assertTrue(plan.missingLabel().contains("CRG x1"));
		assertTrue(plan.missingLabel().contains("SEC x1"));
		assertTrue(plan.missingLabel().contains("FIELD x1"));
	}

	@Test
	void selectionOrderIsStableAndPrefersAirborneThenEnergyThenDistance() {
		AutonomousTaskForcePolicy.Plan plan = AutonomousTaskForcePolicy.plan(FieldOperationType.ORE, 24, List.of(
			new AutonomousTaskForcePolicy.Candidate(1, "DOCKED", DroneRole.ENGINEER, true, true, 100, 100, 1),
			new AutonomousTaskForcePolicy.Candidate(2, "FAR", DroneRole.ENGINEER, true, false, 90, 100, 100),
			new AutonomousTaskForcePolicy.Candidate(3, "NEAR", DroneRole.ENGINEER, true, false, 90, 100, 4)));
		assertEquals(List.of("NEAR", "FAR"), plan.selected().stream()
			.map(AutonomousTaskForcePolicy.Candidate::unitId).toList());
	}

	@TestFactory
	Stream<DynamicTest> everyExclusiveOperationalStatePreventsAutomaticReassignment() {
		String[] names = { "power lost", "service return", "combat", "emergency intercept",
			"recovery", "field operation", "security patrol", "salvage", "cargo route" };
		return IntStream.range(0, names.length).mapToObj(blocker -> DynamicTest.dynamicTest(names[blocker], () -> {
			boolean[] flags = new boolean[names.length];
			flags[blocker] = true;
			assertFalse(AutonomousTaskForcePolicy.operationallyIdle(flags[0], flags[1], flags[2], flags[3],
				flags[4] ? 1 : 0, flags[5], flags[6], flags[7], flags[8], DroneMode.STANDBY, false));
		}));
	}

	@Test
	void standbyAndDockedAircraftAreEligibleButOtherFlightModesAreNot() {
		assertTrue(AutonomousTaskForcePolicy.operationallyIdle(false, false, false, false, 0,
			false, false, false, false, DroneMode.STANDBY, false));
		assertTrue(AutonomousTaskForcePolicy.operationallyIdle(false, false, false, false, 0,
			false, false, false, false, DroneMode.DOCK, true));
		for (DroneMode mode : DroneMode.values()) {
			if (mode == DroneMode.STANDBY) continue;
			assertFalse(AutonomousTaskForcePolicy.operationallyIdle(false, false, false, false, 0,
				false, false, false, false, mode, false));
		}
	}

	private static AutonomousTaskForcePolicy.Candidate candidate(int id, DroneRole role, int battery) {
		return new AutonomousTaskForcePolicy.Candidate(id, "U" + id, role, true, false,
			battery, 100, id * id);
	}
}
