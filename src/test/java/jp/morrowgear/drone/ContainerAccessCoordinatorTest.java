package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ContainerAccessCoordinatorTest {
	@Test
	void grantsOneHolderAndQueuesOthersInArrivalOrder() {
		ContainerAccessCoordinator<String> coordinator = new ContainerAccessCoordinator<>(40, 100);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		UUID third = UUID.randomUUID();

		assertTrue(coordinator.request("chest", first, 0).granted());
		assertEquals(1, coordinator.request("chest", second, 0).queuePosition());
		assertEquals(2, coordinator.request("chest", third, 0).queuePosition());
		coordinator.release("chest", first);
		assertTrue(coordinator.request("chest", second, 10).granted());
		assertFalse(coordinator.request("chest", third, 10).granted());
	}

	@Test
	void expiresAnUnresponsiveHolderWithoutLosingTheQueue() {
		ContainerAccessCoordinator<String> coordinator = new ContainerAccessCoordinator<>(40, 100);
		UUID stuck = UUID.randomUUID();
		UUID waiting = UUID.randomUUID();

		coordinator.request("barrel", stuck, 0);
		coordinator.request("barrel", waiting, 10);
		assertTrue(coordinator.request("barrel", waiting, 41).granted());
	}

	@Test
	void removalReleasesEveryLaneOwnedOrQueuedByAUnit() {
		ContainerAccessCoordinator<String> coordinator = new ContainerAccessCoordinator<>(40, 100);
		UUID removed = UUID.randomUUID();
		UUID next = UUID.randomUUID();

		coordinator.request("source", removed, 0);
		coordinator.request("target", removed, 0);
		coordinator.request("source", next, 0);
		coordinator.releaseEverywhere(removed);

		assertTrue(coordinator.request("source", next, 1).granted());
		assertEquals(1, coordinator.laneCount());
	}
}
