package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DockAllocationPolicyTest {
	@Test void queueIsPriorityThenFifoThenStableIdentity() {
		UUID service = UUID.fromString("00000000-0000-0000-0000-000000000003");
		UUID first = UUID.fromString("00000000-0000-0000-0000-000000000002");
		UUID second = UUID.fromString("00000000-0000-0000-0000-000000000001");
		List<DockAllocationPolicy.Request> requests = List.of(
			new DockAllocationPolicy.Request(first, 10, 1),
			new DockAllocationPolicy.Request(second, 10, 1),
			new DockAllocationPolicy.Request(service, 20, 2));
		assertEquals(1, DockAllocationPolicy.queuePosition(requests, service));
		assertEquals(2, DockAllocationPolicy.queuePosition(requests, second));
		assertEquals(3, DockAllocationPolicy.queuePosition(requests, first));
	}

	@Test void onlyHealthyLongParkedIdleAircraftYields() {
		long grace = DockAllocationPolicy.PARKED_YIELD_GRACE_TICKS;
		assertTrue(DockAllocationPolicy.mayYield(true, false, false, false, 80, grace));
		assertFalse(DockAllocationPolicy.mayYield(true, true, false, false, 100, grace));
		assertFalse(DockAllocationPolicy.mayYield(true, false, true, false, 100, grace));
		assertFalse(DockAllocationPolicy.mayYield(true, false, false, true, 100, grace));
		assertFalse(DockAllocationPolicy.mayYield(true, false, false, false, 79, grace));
		assertFalse(DockAllocationPolicy.mayYield(true, false, false, false, 100, grace - 1));
	}

	@Test void holdingPatternScalesInWideEightAircraftLayers() {
		assertEquals(14.0, DockAllocationPolicy.holdingSlot(1).radius());
		assertEquals(14.0, DockAllocationPolicy.holdingSlot(8).radius());
		assertEquals(18.0, DockAllocationPolicy.holdingSlot(9).radius());
		assertEquals(14.0, DockAllocationPolicy.holdingSlot(9).height());
	}
}
