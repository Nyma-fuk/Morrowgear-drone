package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class FleetCompatibilityMatrixTest {
	@TestFactory
	Stream<DynamicTest> everyRoleMissionPairHasAnExplicitResult() {
		return Stream.of(DroneRole.values()).flatMap(role ->
			Stream.of(MissionAssignmentPolicy.MissionKind.values()).map(mission ->
				DynamicTest.dynamicTest(role + " x " + mission, () ->
					assertEquals(expectedMissionCompatibility(role, mission),
						MissionAssignmentPolicy.allows(role, mission)))));
	}

	@TestFactory
	Stream<DynamicTest> roleModuleTransitionsCoverDockInventoryCreativeAndIdempotence() {
		List<DynamicTest> cases = new ArrayList<>();
		for (boolean docked : List.of(false, true)) {
			for (boolean creative : List.of(false, true)) {
				for (boolean available : List.of(false, true)) {
					for (DroneRole current : DroneRole.values()) {
						for (DroneRole requested : DroneRole.values()) {
							String name = "dock=" + docked + " creative=" + creative + " item=" + available
								+ " " + current + " -> " + requested;
							cases.add(DynamicTest.dynamicTest(name, () -> assertEquals(
								expectedModuleResult(docked, creative, available, current, requested),
								RoleModulePolicy.evaluate(docked, creative, available, current, requested))));
						}
					}
				}
			}
		}
		return cases.stream();
	}

	@Test
	void mixedRoleWingOnlyReceivesCompatibleSpecialistMissions() {
		List<DroneRole> wing = List.of(DroneRole.FIELD, DroneRole.SCOUT, DroneRole.CARGO,
			DroneRole.ENGINEER, DroneRole.SECURITY);
		List<DroneRole> fieldWorkers = wing.stream().filter(role -> MissionAssignmentPolicy.allows(role,
			MissionAssignmentPolicy.MissionKind.FIELD_OPERATION)).toList();
		List<DroneRole> guards = wing.stream().filter(role -> MissionAssignmentPolicy.allows(role,
			MissionAssignmentPolicy.MissionKind.SECURITY_PATROL)).toList();
		assertEquals(List.of(DroneRole.FIELD, DroneRole.SCOUT, DroneRole.CARGO, DroneRole.ENGINEER, DroneRole.SECURITY), fieldWorkers);
		assertEquals(List.of(DroneRole.SECURITY), guards);
		assertTrue(wing.stream().allMatch(role -> MissionAssignmentPolicy.allows(role,
			MissionAssignmentPolicy.MissionKind.GENERAL_FLIGHT)));
	}

	@Test
	void nullOrUnknownAssignmentsFailClosed() {
		assertFalse(MissionAssignmentPolicy.allows(null, MissionAssignmentPolicy.MissionKind.GENERAL_FLIGHT));
		assertFalse(MissionAssignmentPolicy.allows(DroneRole.FIELD, null));
	}

	private static boolean expectedMissionCompatibility(DroneRole role,
		MissionAssignmentPolicy.MissionKind mission) {
		return switch (mission) {
			case GENERAL_FLIGHT -> true;
			case FIELD_OPERATION -> role == DroneRole.FIELD || role == DroneRole.SCOUT || role == DroneRole.CARGO
				|| role == DroneRole.ENGINEER || role == DroneRole.SECURITY;
			case CARGO_ROUTE -> role == DroneRole.CARGO;
			case ENGINEERING_SUPPORT -> role == DroneRole.ENGINEER;
			case SECURITY_PATROL -> role == DroneRole.SECURITY;
			case SALVAGE_RECOVERY -> role == DroneRole.SALVAGE;
		};
	}

	private static RoleModulePolicy.Result expectedModuleResult(boolean docked, boolean creative,
		boolean available, DroneRole current, DroneRole requested) {
		if (current == requested) return RoleModulePolicy.Result.UNCHANGED;
		if (!docked) return RoleModulePolicy.Result.NOT_DOCKED;
		if (!creative && requested != DroneRole.FIELD && !available) return RoleModulePolicy.Result.MODULE_MISSING;
		return RoleModulePolicy.Result.ALLOWED;
	}
}
