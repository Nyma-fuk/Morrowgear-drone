package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DroneTaskStackTest {
	@Test
	void interruptedSalvageResumesAfterServiceWithoutBecomingStandby() {
		DroneTaskStack stack = new DroneTaskStack();
		UUID target = UUID.randomUUID();
		stack.suspend(new DroneTaskStack.Task(DroneTaskStack.Kind.SALVAGE,
			DroneMode.WAYPOINT, SalvageState.INTERCEPT, target, "salvage-target"));
		DroneTaskStack.Task resumed = stack.resume().orElseThrow();
		assertEquals(DroneTaskStack.Kind.SALVAGE, resumed.kind());
		assertEquals(DroneMode.WAYPOINT, resumed.mode());
		assertEquals(SalvageState.INTERCEPT, resumed.salvageState());
		assertEquals(target, resumed.targetId());
	}

	@Test
	void higherPriorityQueuedTaskRunsBeforeLowerPrioritySuspendedRoute() {
		DroneTaskStack stack = new DroneTaskStack();
		stack.suspend(new DroneTaskStack.Task(DroneTaskStack.Kind.ROUTE,
			DroneMode.WAYPOINT, SalvageState.IDLE, null, "route"));
		stack.queue(new DroneTaskStack.Task(DroneTaskStack.Kind.SALVAGE,
			DroneMode.WAYPOINT, SalvageState.INTERCEPT, UUID.randomUUID(), "salvage"));
		assertEquals(DroneTaskStack.Kind.SALVAGE, stack.resume().orElseThrow().kind());
		assertEquals(DroneTaskStack.Kind.ROUTE, stack.resume().orElseThrow().kind());
	}

	@Test
	void duplicateStateUpdatesDoNotGrowTheStackOrQueue() {
		DroneTaskStack stack = new DroneTaskStack();
		DroneTaskStack.Task salvage = new DroneTaskStack.Task(DroneTaskStack.Kind.SALVAGE,
			DroneMode.WAYPOINT, SalvageState.INTERCEPT, UUID.randomUUID(), "salvage");
		stack.suspend(salvage);
		stack.suspend(salvage);
		stack.queue(salvage);
		assertEquals(1, stack.suspendedCount());
		assertEquals(0, stack.pendingCount());
	}

	@Test
	void resumeSkipsTargetsThatNoLongerExist() {
		DroneTaskStack stack = new DroneTaskStack();
		UUID vanished = UUID.randomUUID();
		stack.suspend(new DroneTaskStack.Task(DroneTaskStack.Kind.ROUTE,
			DroneMode.WAYPOINT, SalvageState.IDLE, null, "route-1"));
		stack.queue(new DroneTaskStack.Task(DroneTaskStack.Kind.TRACKING,
			DroneMode.WAYPOINT, SalvageState.IDLE, vanished, "track-1"));

		DroneTaskStack.Task resumed = stack.resume(task -> task.targetId() == null
			? java.util.Optional.of(task) : java.util.Optional.empty()).orElseThrow();

		assertEquals(DroneTaskStack.Kind.ROUTE, resumed.kind());
		assertEquals("route-1", resumed.missionId());
	}

	@Test
	void invalidTaskCanResolveToSafeFallback() {
		DroneTaskStack stack = new DroneTaskStack();
		stack.suspend(new DroneTaskStack.Task(DroneTaskStack.Kind.COMBAT,
			DroneMode.WAYPOINT, SalvageState.IDLE, UUID.randomUUID(), "combat-1"));

		DroneTaskStack.Task resumed = stack.resume(task -> java.util.Optional.of(
			new DroneTaskStack.Task(DroneTaskStack.Kind.ROUTE, DroneMode.WAYPOINT,
				SalvageState.IDLE, null, "route-fallback"))).orElseThrow();

		assertEquals(DroneTaskStack.Kind.ROUTE, resumed.kind());
		assertEquals("route-fallback", resumed.missionId());
	}

	@Test
	void completedReliefSkipsCombatAndRestoresUnderlyingSecurityMission() {
		DroneTaskStack stack = new DroneTaskStack();
		stack.suspend(new DroneTaskStack.Task(DroneTaskStack.Kind.SECURITY_PATROL,
			DroneMode.STANDBY, SalvageState.IDLE, null, "relief-order"));
		stack.suspend(new DroneTaskStack.Task(DroneTaskStack.Kind.COMBAT,
			DroneMode.STANDBY, SalvageState.IDLE, UUID.randomUUID(), "front"));

		DroneTaskStack.Task resumed = stack.resume(task -> task.kind() == DroneTaskStack.Kind.COMBAT
			? java.util.Optional.empty() : java.util.Optional.of(task)).orElseThrow();

		assertEquals(DroneTaskStack.Kind.SECURITY_PATROL, resumed.kind());
		assertEquals("relief-order", resumed.missionId());
		assertTrue(stack.resume().isEmpty());
	}

	@Test
	void discardRemovesSupersededAssignmentsFromBothCollections() {
		DroneTaskStack stack = new DroneTaskStack();
		stack.suspend(new DroneTaskStack.Task(DroneTaskStack.Kind.ROUTE, DroneMode.WAYPOINT,
			SalvageState.IDLE, null, "old-route"));
		stack.queue(new DroneTaskStack.Task(DroneTaskStack.Kind.FIELD, DroneMode.STANDBY,
			SalvageState.IDLE, null, "old-field"));

		stack.discard(task -> task.missionId().startsWith("old-"));

		assertTrue(stack.resume().isEmpty());
	}
}
