package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class TacticalUiPolicyTest {
	@Test
	void everyControllerRoleAndCommandHasAnIntrinsicLabel() {
		assertEquals(List.of("FIELD", "SCT", "CRG", "ENG", "SEC", "SLV"),
			Stream.of(DroneRole.values()).map(TacticalUiPolicy::moduleLabel).toList());
		assertEquals(List.of("FOLLOW", "STANDBY", "RETURN", "DOCK", "ORBIT", "STORE"),
			Stream.of("follow", "standby", "return", "dock", "orbit", "decommission")
				.map(TacticalUiPolicy::actionLabel).toList());
		assertEquals("---", TacticalUiPolicy.moduleLabel(null));
		assertEquals("UNKNOWN", TacticalUiPolicy.actionLabel(null));
		assertEquals("UNKNOWN", TacticalUiPolicy.actionLabel("future-command"));
	}

	@Test
	void rosterKeepsUrgentAircraftVisibleAndUsesStableOperationalBands() {
		assertEquals(0, TacticalUiPolicy.rosterPriority(true, false, 0, true, false, 100));
		assertEquals(0, TacticalUiPolicy.rosterPriority(false, true, 0, true, false, 100));
		assertEquals(1, TacticalUiPolicy.rosterPriority(false, false, 1, true, false, 100));
		assertEquals(1, TacticalUiPolicy.rosterPriority(false, false, 0, false, false, 24));
		assertEquals(2, TacticalUiPolicy.rosterPriority(false, false, 0, true, false, 100));
		assertEquals(3, TacticalUiPolicy.rosterPriority(false, false, 0, false, false, 100));
		assertEquals(4, TacticalUiPolicy.rosterPriority(false, false, 0, false, true, 100));
	}
	@TestFactory
	Stream<DynamicTest> everyRoleSelectionCombinationExposesOnlyCompatibleCommands() {
		DroneRole[] roles = DroneRole.values();
		return IntStream.range(0, 1 << roles.length).mapToObj(mask -> DynamicTest.dynamicTest(
			"role selection mask " + Integer.toBinaryString(mask), () -> {
				List<DroneRole> selected = new ArrayList<>();
				for (int index = 0; index < roles.length; index++) {
					if ((mask & 1 << index) != 0) selected.add(roles[index]);
				}
				TacticalUiPolicy.CommandAvailability availability = TacticalUiPolicy.availability(selected);
				assertEquals(selected.size(), availability.selected());
				assertEquals(selected.stream().filter(role -> role == DroneRole.FIELD || role == DroneRole.SCOUT
					|| role == DroneRole.CARGO || role == DroneRole.ENGINEER
					|| role == DroneRole.SECURITY).count(), availability.field());
				assertEquals(selected.stream().filter(role -> role == DroneRole.CARGO).count(), availability.cargo());
				assertEquals(selected.stream().filter(role -> role == DroneRole.SECURITY).count(), availability.security());
				assertEquals(selected.stream().filter(role -> role == DroneRole.SCOUT).count(), availability.scouts());
				assertEquals(selected.stream().filter(role -> role == DroneRole.ENGINEER).count(), availability.engineers());
				assertEquals(!selected.isEmpty(), availability.hasSelection());
				assertEquals(availability.field() > 0, availability.fieldEnabled());
				for (FieldOperationType type : List.of(FieldOperationType.ORE,
					FieldOperationType.EXCAVATE, FieldOperationType.FORESTRY)) {
					assertEquals(availability.field() > 0, availability.workEnabled(type));
				}
				assertEquals(availability.cargo() > 0, availability.cargoEnabled());
				assertEquals(availability.security() > 0, availability.securityEnabled());
			}));
	}

	@Test
	void emptyAndNullSelectionsDisableEveryCommandFamily() {
		for (TacticalUiPolicy.CommandAvailability availability : List.of(
			TacticalUiPolicy.availability(List.of()), TacticalUiPolicy.availability(null))) {
			assertFalse(availability.hasSelection());
			assertFalse(availability.fieldEnabled());
			assertFalse(availability.cargoEnabled());
			assertFalse(availability.securityEnabled());
		}
	}

	@Test
	void everyMissionRoleIsRepresentedInTheCommandPanel() {
		TacticalUiPolicy.CommandAvailability availability = TacticalUiPolicy.availability(List.of(DroneRole.values()));
		assertTrue(availability.hasSelection());
		assertEquals(5, availability.field());
		assertEquals(1, availability.scouts());
		assertEquals(1, availability.engineers());
		assertEquals(1, availability.cargo());
		assertEquals(1, availability.security());
	}

	@Test
	void fieldMissionsAllowSpecialistsAndFieldSupportAircraft() {
		TacticalUiPolicy.CommandAvailability scoutOnly = TacticalUiPolicy.availability(List.of(DroneRole.SCOUT));
		assertTrue(scoutOnly.workEnabled(FieldOperationType.FORESTRY));
		assertEquals("", scoutOnly.workBlockReason(FieldOperationType.FORESTRY));
		TacticalUiPolicy.CommandAvailability fieldOnly = TacticalUiPolicy.availability(List.of(DroneRole.FIELD));
		assertTrue(fieldOnly.workEnabled(FieldOperationType.ORE));
		TacticalUiPolicy.CommandAvailability engineerOnly = TacticalUiPolicy.availability(List.of(DroneRole.ENGINEER));
		assertTrue(engineerOnly.workEnabled(FieldOperationType.FORESTRY));
		assertEquals("", engineerOnly.workBlockReason(FieldOperationType.FORESTRY));
	}

	@Test
	void radiusLabelsAreExplicitAndNeverNegative() {
		assertEquals("半径 0 blocks", TacticalUiPolicy.radiusLabel(-1));
		assertEquals("半径 16 blocks", TacticalUiPolicy.radiusLabel(16));
		assertEquals("半径 48 blocks", TacticalUiPolicy.radiusLabel(48));
	}

	@Test
	void selectionTabsAlwaysMatchTheirVisibleScope() {
		List<Integer> available = List.of(10, 20, 30, 40);
		assertEquals(Set.of(20), TacticalUiPolicy.normalizeSelection(
			TacticalUiPolicy.SelectionScope.UNIT, List.of(20, 30, 40), available));
		assertEquals(Set.of(10), TacticalUiPolicy.normalizeSelection(
			TacticalUiPolicy.SelectionScope.UNIT, List.of(999), available));
		assertEquals(Set.of(20, 30), TacticalUiPolicy.normalizeSelection(
			TacticalUiPolicy.SelectionScope.WING, List.of(20, 999, 30), available));
		assertEquals(Set.copyOf(available), TacticalUiPolicy.normalizeSelection(
			TacticalUiPolicy.SelectionScope.ALL, List.of(20), available));
		for (TacticalUiPolicy.SelectionScope scope : TacticalUiPolicy.SelectionScope.values()) {
			assertTrue(TacticalUiPolicy.normalizeSelection(scope, List.of(10), List.of()).isEmpty());
		}
	}
}
