package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class DroneStatePolicyTest {
	@TestFactory
	Stream<DynamicTest> sanitizesPersistedBatteryValues() {
		return List.of(-1000, -1, 0, 1, 500, 999, 1000, 1001, Integer.MAX_VALUE).stream()
			.map(value -> DynamicTest.dynamicTest("battery " + value, () ->
				assertEquals(Math.max(0, Math.min(1000, value)), DroneStatePolicy.battery(value))));
	}

	@TestFactory
	Stream<DynamicTest> sanitizesMissionSizeAndIndexTogether() {
		return List.of(-100, -1, 0, 1, 7, 8, 9, 40, 100, 128, 129, 1000).stream()
			.flatMap(expected -> List.of(-10, -1, 0, 1, 7, 8, 99, 127, 128, 1000).stream()
				.map(index -> DynamicTest.dynamicTest(expected + "/" + index, () -> {
					int safeExpected = DroneStatePolicy.missionExpected(expected);
					int safeIndex = DroneStatePolicy.missionIndex(index, expected);
					assertEquals(true, safeExpected >= 1 && safeExpected <= 128);
					assertEquals(true, safeIndex >= 0 && safeIndex < safeExpected);
				})));
	}

	@Test
	void sanitizesGroupsWithoutRenamingValidPublishedValues() {
		assertEquals("ALPHA", DroneStatePolicy.group(null));
		assertEquals("ALPHA", DroneStatePolicy.group(""));
		assertEquals("ALPHA", DroneStatePolicy.group("wing-lowercase"));
		assertEquals("ALPHA", DroneStatePolicy.group("WING:INJECT"));
		assertEquals("ALPHA", DroneStatePolicy.group("X".repeat(25)));
		assertEquals("ALPHA", DroneStatePolicy.group("ALPHA"));
		assertEquals("WING-B6KV", DroneStatePolicy.group("WING-B6KV"));
	}

	@Test
	void clampsSavedOperationRadiiToSupportedContracts() {
		assertEquals(12, DroneStatePolicy.fieldRadius(FieldOperationType.ORE, -1));
		assertEquals(64, DroneStatePolicy.fieldRadius(FieldOperationType.ORE, 999));
		assertEquals(6, DroneStatePolicy.fieldRadius(FieldOperationType.FORESTRY, -1));
		assertEquals(40, DroneStatePolicy.fieldRadius(FieldOperationType.FORESTRY, 999));
		assertEquals(2, DroneStatePolicy.fieldRadius(FieldOperationType.EXCAVATE, -1));
		assertEquals(8, DroneStatePolicy.fieldRadius(FieldOperationType.EXCAVATE, 999));
		assertEquals(0, DroneStatePolicy.fieldRadius(FieldOperationType.NONE, 999));
		assertEquals(6, DroneStatePolicy.securityRadius(-1));
		assertEquals(64, DroneStatePolicy.securityRadius(999));
	}
}
