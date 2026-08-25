package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class DroneStoragePolicyTest {
	@TestFactory
	Stream<DynamicTest> preservesEveryCargoStackAcrossStorage() {
		return Stream.of(0, 1, 2, 8, 9).map(stackCount -> DynamicTest.dynamicTest("cargo stacks " + stackCount, () -> {
			DroneStoragePolicy.Manifest manifest = DroneStoragePolicy.manifest(true, stackCount, false);
			assertEquals(true, manifest.returnUnit());
			assertEquals(true, manifest.returnModule());
			assertEquals(stackCount, manifest.cargoStacks());
		}));
	}

	@Test
	void creativeStorageReturnsCargoButDoesNotDuplicateEquipment() {
		DroneStoragePolicy.Manifest manifest = DroneStoragePolicy.manifest(true, 9, true);
		assertEquals(false, manifest.returnUnit());
		assertEquals(false, manifest.returnModule());
		assertEquals(9, manifest.cargoStacks());
	}

	@Test
	void storageWithoutModuleDoesNotCreateOne() {
		DroneStoragePolicy.Manifest manifest = DroneStoragePolicy.manifest(false, -1, false);
		assertEquals(true, manifest.returnUnit());
		assertEquals(false, manifest.returnModule());
		assertEquals(0, manifest.cargoStacks());
	}
}
