package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DockAllocationUiPolicyTest {
	@Test
	void everyAuthoritativeDockStateHasStableScalableLabels() {
		for (DockAllocationUiPolicy.DockState state : DockAllocationUiPolicy.DockState.values()) {
			var view = new DockAllocationUiPolicy.DockView("DK-01", state, 2, 7, 3, "MG-DRN-01", true);
			assertEquals(state.name(), DockAllocationUiPolicy.dockStateLabel(view));
			assertEquals("Q 2/7", DockAllocationUiPolicy.queueLabel(view));
			assertEquals("H 3", DockAllocationUiPolicy.holdingLabel(view));
		}
	}

	@Test
	void localFallbackNeverPretendsQueueOrReservationDataExists() {
		var free = DockAllocationUiPolicy.localDock("DK-01", false, false, "");
		assertEquals(DockAllocationUiPolicy.DockState.FREE, free.state());
		assertEquals("Q --", DockAllocationUiPolicy.queueLabel(free));
		assertEquals("H --", DockAllocationUiPolicy.holdingLabel(free));
		assertFalse(free.synchronizedData());
		assertEquals(DockAllocationUiPolicy.DockState.SERVICE,
			DockAllocationUiPolicy.localDock("DK-02", true, true, "A1").state());
	}

	@Test
	void aircraftLabelsDistinguishAssignmentReservationAndHolding() {
		assertEquals("ASSIGNED DK-01", DockAllocationUiPolicy.aircraftLabel(new DockAllocationUiPolicy.AircraftView(
			DockAllocationUiPolicy.AircraftState.ASSIGNED, "DK-01", -1, -1, true)));
		assertEquals("RESERVED DK-02", DockAllocationUiPolicy.aircraftLabel(new DockAllocationUiPolicy.AircraftView(
			DockAllocationUiPolicy.AircraftState.RESERVED, "DK-02", 0, 4, true)));
		assertEquals("HOLDING Q 3/8", DockAllocationUiPolicy.aircraftLabel(new DockAllocationUiPolicy.AircraftView(
			DockAllocationUiPolicy.AircraftState.HOLDING, "", 3, 8, true)));
		assertEquals("NO DOCK ALLOCATION", DockAllocationUiPolicy.aircraftLabel(
			DockAllocationUiPolicy.localAircraft(false, false, "")));
	}

	@Test
	void invalidQueueCoordinatesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new DockAllocationUiPolicy.DockView(
			"DK-01", DockAllocationUiPolicy.DockState.RESERVED, 4, 3, 0, "", true));
	}

	@Test
	void tacticalDashboardConsumesAllocationViewsAndDoesNotSendLegacyFixedAssignment() throws Exception {
		String source = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/TacticalDashboard.java"));
		assertEquals(true, source.contains("DockAllocationClientStore.dock"));
		assertEquals(true, source.contains("DockAllocationClientStore.aircraft"));
		assertFalse(source.contains("assign_dock:"));
	}
}
