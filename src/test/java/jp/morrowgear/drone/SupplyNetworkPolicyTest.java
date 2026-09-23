package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.SupplyNetworkPolicy.Readiness;
import jp.morrowgear.drone.SupplyNetworkPolicy.Status;
import org.junit.jupiter.api.Test;

final class SupplyNetworkPolicyTest {
	private static Readiness state(int blocked) {
		return new Readiness(blocked != 0, blocked != 1, blocked != 2, blocked == 3, blocked == 4,
			blocked == 5, blocked == 6, blocked == 7, blocked == 8, blocked == 9, blocked == 10,
			blocked == 11, false, blocked == 12 ? 10 : 100);
	}

	@Test void everyActiveTaskBoundaryPreventsDispatch() {
		assertTrue(SupplyNetworkPolicy.dispatchable(state(-1)));
		for (int i = 0; i <= 12; i++) assertFalse(SupplyNetworkPolicy.dispatchable(state(i)), "boundary " + i);
	}

	@Test void manualWingCannotBeBorrowedEvenWhenIdleAndFullyCharged() {
		assertFalse(SupplyNetworkPolicy.dispatchable(state(3)));
		assertTrue(SupplyNetworkPolicy.protectedGroup("BETA"));
		assertTrue(SupplyNetworkPolicy.protectedGroup("MY_CARGO"));
		assertTrue(SupplyNetworkPolicy.protectedGroup("WING-1"));
		assertFalse(SupplyNetworkPolicy.protectedGroup("ALPHA"));
	}

	@Test void dockedCargoRequiresNormalSortieCharge() {
		assertFalse(SupplyNetworkPolicy.dispatchable(new Readiness(true, true, true, false, false,
			false, false, false, false, false, false, false, true, 35)));
	}

	@Test void suspendedTaskBlocksDispatchWithoutChangingTheTaskStack() {
		DroneTaskStack stack = new DroneTaskStack();
		DroneTaskStack.Task wing = new DroneTaskStack.Task(DroneTaskStack.Kind.ROUTE, DroneMode.WAYPOINT,
			SalvageState.IDLE, null, "wing-generation-7");
		stack.suspend(wing);
		assertFalse(SupplyNetworkPolicy.dispatchable(state(5)));
		assertTrue(stack.resume().orElseThrow().sameAssignment(wing));
	}

	@Test void demandSubtractsIncomingAndNeverOverflows() {
		assertEquals(3, SupplyNetworkPolicy.demand(16, 8, 5));
		assertEquals(0, SupplyNetworkPolicy.demand(16, 32, 0));
		assertEquals(0, SupplyNetworkPolicy.demand(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
	}

	@Test void defaultsPrioritizeFuelAndRepairAndExposeEverySupplyKind() {
		var defaults = SupplyNetworkPolicy.defaults();
		assertEquals(SupplyKind.values().length, defaults.size());
		assertEquals(SupplyKind.FUEL, defaults.getFirst().kind());
		assertTrue(defaults.getFirst().priority() > defaults.getLast().priority());
	}

	@Test void shortagesAndRetainedCargoAreVisibleErrors() {
		for (Status status : List.of(Status.SOURCE_SHORTAGE, Status.SOURCE_LOST, Status.DOCK_LOST,
			Status.DOCK_FULL, Status.CARGO_MISMATCH, Status.ASSIGNMENT_LOST, Status.RUNTIME_UNAVAILABLE))
			assertTrue(status.error());
		assertFalse(Status.READY.error());
		assertFalse(Status.RECHARGING.error());
	}
}
