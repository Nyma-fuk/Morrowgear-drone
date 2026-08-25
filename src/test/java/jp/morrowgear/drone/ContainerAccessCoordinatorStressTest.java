package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

final class ContainerAccessCoordinatorStressTest {
	@TestFactory
	Stream<DynamicTest> everyFleetSizeServicesTheQueueExactlyOnceWithoutLeakingLanes() {
		return IntStream.rangeClosed(1, 128).mapToObj(size -> DynamicTest.dynamicTest("cargo fleet " + size, () -> {
			ContainerAccessCoordinator<String> coordinator = new ContainerAccessCoordinator<>(40, 200);
			List<UUID> units = IntStream.range(0, size).mapToObj(ignored -> UUID.randomUUID()).toList();
			assertTrue(coordinator.request("endpoint", units.getFirst(), 0).granted());
			for (int index = 1; index < size; index++) {
				assertEquals(index, coordinator.request("endpoint", units.get(index), 0).queuePosition());
			}
			for (int index = 0; index < size; index++) {
				UUID unit = units.get(index);
				assertTrue(coordinator.request("endpoint", unit, index).granted());
				coordinator.release("endpoint", unit);
			}
			assertEquals(0, coordinator.laneCount());
		}));
	}

	@TestFactory
	Stream<DynamicTest> randomDisconnectsNeverBlockRemainingCargoUnits() {
		return IntStream.range(0, 64).mapToObj(seed -> DynamicTest.dynamicTest("disconnect seed " + seed, () -> {
			Random random = new Random(seed);
			ContainerAccessCoordinator<String> coordinator = new ContainerAccessCoordinator<>(20, 60);
			List<UUID> units = new ArrayList<>(IntStream.range(0, 24)
				.mapToObj(ignored -> UUID.randomUUID()).toList());
			for (UUID unit : units) coordinator.request("source", unit, 0);
			for (UUID unit : units) if (random.nextBoolean()) coordinator.releaseEverywhere(unit);
			for (UUID unit : units) coordinator.releaseEverywhere(unit);
			assertEquals(0, coordinator.laneCount());
		}));
	}
}
