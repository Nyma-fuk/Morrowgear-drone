package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class DroneCommandPolicyTest {
	@TestFactory
	Stream<DynamicTest> acceptsEverySupportedMissionSizeAndIndex() {
		return IntStream.rangeClosed(1, DroneCommandPolicy.MAX_MISSION_SIZE).mapToObj(expected ->
			DynamicTest.dynamicTest("mission size " + expected, () -> {
				for (int index = 0; index < expected; index++) {
					assertTrue(DroneCommandPolicy.validMission("MISSION-" + expected, expected, index));
				}
			}));
	}

	@TestFactory
	Stream<DynamicTest> acceptsEverySupportedFieldRadius() {
		return Stream.of(
			radiusCases(FieldOperationType.ORE, 12, 48),
			radiusCases(FieldOperationType.FORESTRY, 6, 24),
			radiusCases(FieldOperationType.EXCAVATE, 2, 8)
		).flatMap(stream -> stream);
	}

	private static Stream<DynamicTest> radiusCases(FieldOperationType type, int minimum, int maximum) {
		return IntStream.rangeClosed(minimum, maximum).mapToObj(radius ->
			DynamicTest.dynamicTest(type + " radius " + radius,
				() -> assertTrue(DroneCommandPolicy.validFieldRadius(type, radius))));
	}

	@Test
	void rejectsMissionBoundaryViolations() {
		assertFalse(DroneCommandPolicy.validMission("MISSION", 0, 0));
		assertFalse(DroneCommandPolicy.validMission("MISSION", 129, 0));
		assertFalse(DroneCommandPolicy.validMission("MISSION", 8, -1));
		assertFalse(DroneCommandPolicy.validMission("MISSION", 8, 8));
		assertFalse(DroneCommandPolicy.validMission("", 1, 0));
		assertFalse(DroneCommandPolicy.validMission("MISSION:INJECT", 1, 0));
		assertFalse(DroneCommandPolicy.validMission("X".repeat(97), 1, 0));
	}

	@Test
	void rejectsRadiusBoundaryViolations() {
		assertFalse(DroneCommandPolicy.validFieldRadius(FieldOperationType.ORE, 11));
		assertFalse(DroneCommandPolicy.validFieldRadius(FieldOperationType.ORE, 49));
		assertFalse(DroneCommandPolicy.validFieldRadius(FieldOperationType.FORESTRY, 5));
		assertFalse(DroneCommandPolicy.validFieldRadius(FieldOperationType.FORESTRY, 25));
		assertFalse(DroneCommandPolicy.validFieldRadius(FieldOperationType.EXCAVATE, 1));
		assertFalse(DroneCommandPolicy.validFieldRadius(FieldOperationType.EXCAVATE, 9));
		assertFalse(DroneCommandPolicy.validFieldRadius(FieldOperationType.NONE, 8));
		assertFalse(DroneCommandPolicy.validFieldRadius(null, 8));
		assertFalse(DroneCommandPolicy.validSecurityRadius(5));
		assertTrue(DroneCommandPolicy.validSecurityRadius(6));
		assertTrue(DroneCommandPolicy.validSecurityRadius(32));
		assertFalse(DroneCommandPolicy.validSecurityRadius(33));
	}

	@Test
	void rejectsUnknownAndOversizedPayloads() {
		assertFalse(DroneCommandPolicy.acceptablePayload(null));
		assertFalse(DroneCommandPolicy.acceptablePayload(""));
		assertFalse(DroneCommandPolicy.acceptablePayload(" "));
		assertTrue(DroneCommandPolicy.acceptablePayload("x".repeat(256)));
		assertFalse(DroneCommandPolicy.acceptablePayload("x".repeat(257)));
		assertTrue(DroneCommandPolicy.isSimpleAction("standby"));
		assertTrue(DroneCommandPolicy.isSimpleAction("return"));
		assertTrue(DroneCommandPolicy.isSimpleAction("dock"));
		assertTrue(DroneCommandPolicy.isSimpleAction("orbit"));
		assertTrue(DroneCommandPolicy.isSimpleAction("decommission"));
		assertFalse(DroneCommandPolicy.isSimpleAction("follow"));
		assertFalse(DroneCommandPolicy.isSimpleAction("unknown"));
		assertTrue(DroneCommandPolicy.acceptablePayload("weapon_module:laser"));
		assertTrue(SecurityLoadout.isValidId("autocannon"));
		assertTrue(SecurityLoadout.isValidId("laser"));
		assertTrue(SecurityLoadout.isValidId("missile"));
		assertFalse(SecurityLoadout.isValidId("railgun"));
	}
}
