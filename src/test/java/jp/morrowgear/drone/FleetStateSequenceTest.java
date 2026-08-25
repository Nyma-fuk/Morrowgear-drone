package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class FleetStateSequenceTest {
	@TestFactory
	Stream<DynamicTest> randomizedRoleMissionAndWingSequencesPreserveAllInvariants() {
		return IntStream.range(0, 128).mapToObj(seed -> DynamicTest.dynamicTest("sequence seed " + seed, () -> {
			Random random = new Random(seed);
			Fleet fleet = new Fleet(40);
			for (int step = 0; step < 1_000; step++) {
				Unit unit = fleet.units.get(random.nextInt(fleet.units.size()));
				switch (random.nextInt(4)) {
					case 0 -> fleet.changeRole(unit, DroneRole.values()[random.nextInt(DroneRole.values().length)]);
					case 1 -> fleet.assignMission(unit, Mission.values()[random.nextInt(Mission.values().length)]);
					case 2 -> fleet.join(unit, "WING-" + (char) ('A' + random.nextInt(8)));
					default -> fleet.leave(unit);
				}
				fleet.assertInvariants();
			}
		}));
	}

	@Test
	void roleChangesDropOnlyIncompatibleMissions() {
		Fleet fleet = new Fleet(5);
		Unit unit = fleet.units.getFirst();
		fleet.changeRole(unit, DroneRole.SCOUT);
		fleet.assignMission(unit, Mission.FIELD);
		fleet.changeRole(unit, DroneRole.CARGO);
		assertEquals(Mission.FIELD, unit.mission);
		fleet.changeRole(unit, DroneRole.SECURITY);
		assertEquals(Mission.FIELD, unit.mission);
		fleet.assignMission(unit, Mission.SECURITY);
		fleet.changeRole(unit, DroneRole.FIELD);
		assertEquals(Mission.NONE, unit.mission);
	}

	@Test
	void joiningAndLeavingActiveMixedWingNeverExceedsCapacityOrLeaksSpecialistMission() {
		Fleet fleet = new Fleet(12);
		for (int index = 0; index < 8; index++) {
			Unit unit = fleet.units.get(index);
			fleet.changeRole(unit, index % 2 == 0 ? DroneRole.SCOUT : DroneRole.SECURITY);
			fleet.join(unit, "WING-ACTIVE");
			fleet.assignMission(unit, index % 2 == 0 ? Mission.FIELD : Mission.SECURITY);
		}
		Unit ninth = fleet.units.get(8);
		fleet.changeRole(ninth, DroneRole.CARGO);
		fleet.assignMission(ninth, Mission.CARGO);
		fleet.join(ninth, "WING-ACTIVE");
		assertEquals("ALPHA", ninth.wing);
		fleet.leave(fleet.units.get(2));
		fleet.join(ninth, "WING-ACTIVE");
		assertEquals("WING-ACTIVE", ninth.wing);
		fleet.assertInvariants();
	}

	private enum Mission {
		NONE(null), GENERAL(MissionAssignmentPolicy.MissionKind.GENERAL_FLIGHT),
		FIELD(MissionAssignmentPolicy.MissionKind.FIELD_OPERATION),
		CARGO(MissionAssignmentPolicy.MissionKind.CARGO_ROUTE),
		ENGINEER(MissionAssignmentPolicy.MissionKind.ENGINEERING_SUPPORT),
		SECURITY(MissionAssignmentPolicy.MissionKind.SECURITY_PATROL);

		private final MissionAssignmentPolicy.MissionKind kind;

		Mission(MissionAssignmentPolicy.MissionKind kind) {
			this.kind = kind;
		}
	}

	private static final class Unit {
		private final String id;
		private DroneRole role = DroneRole.FIELD;
		private Mission mission = Mission.NONE;
		private String wing = "ALPHA";

		private Unit(String id) {
			this.id = id;
		}
	}

	private static final class Fleet {
		private final List<Unit> units = new ArrayList<>();

		private Fleet(int size) {
			for (int index = 0; index < size; index++) units.add(new Unit("UNIT-" + index));
		}

		private void changeRole(Unit unit, DroneRole role) {
			unit.role = role;
			if (unit.mission.kind != null && !MissionAssignmentPolicy.allows(role, unit.mission.kind))
				unit.mission = Mission.NONE;
		}

		private void assignMission(Unit unit, Mission mission) {
			if (mission == Mission.NONE || MissionAssignmentPolicy.allows(unit.role, mission.kind)) unit.mission = mission;
		}

		private void join(Unit unit, String target) {
			int size = (int) units.stream().filter(member -> member.wing.equals(target)).count();
			if (WingMembershipPolicy.evaluate(unit.wing, target, size) == WingMembershipPolicy.Result.ACCEPTED)
				unit.wing = target;
		}

		private void leave(Unit unit) {
			unit.wing = "ALPHA";
		}

		private void assertInvariants() {
			assertEquals(units.size(), units.stream().map(unit -> unit.id).distinct().count());
			Map<String, Integer> counts = new HashMap<>();
			for (Unit unit : units) {
				if (unit.wing.startsWith("WING-")) counts.merge(unit.wing, 1, Integer::sum);
				if (unit.mission.kind != null) assertTrue(MissionAssignmentPolicy.allows(unit.role, unit.mission.kind),
					() -> unit.id + " has incompatible " + unit.role + " / " + unit.mission);
			}
			assertTrue(counts.values().stream().allMatch(size -> size <= WingMembershipPolicy.MAX_MEMBERS), counts::toString);
		}
	}
}
