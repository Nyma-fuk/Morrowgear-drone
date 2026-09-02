package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class DockOperationalPolicyTest {
	@Test
	void emptyFullySuppliedDockIsReady() {
		DockOperationalPolicy.Status status = DockOperationalPolicy.status(false, 0, "",
			3000, 6000, true, true);
		assertEquals("READY", status.label());
		assertEquals(50, status.powerPercent());
		assertEquals(DockOperationalPolicy.Severity.READY, status.severity());
	}

	@Test
	void dockedAircraftShowsChargingUntilNormalSortieThreshold() {
		DockOperationalPolicy.Status status = DockOperationalPolicy.status(true, 72, "DOCK SERVICE / IN PROGRESS",
			6000, 6000, true, true);
		assertEquals("CHARGE 72%", status.label());
		assertEquals(DockOperationalPolicy.Severity.SERVICING, status.severity());
	}

	@Test
	void explicitResourceBlockerTakesPriorityOverGenericChargeState() {
		assertEquals("WAIT REPAIR", DockOperationalPolicy.status(true, 30,
			"DOCK WAIT / REPAIR MATERIAL", 0, 4000, false, false).label());
		assertEquals("WAIT AMMO", DockOperationalPolicy.status(true, 100,
			"DOCK WAIT / AMMUNITION", 4000, 4000, true, true).label());
		assertEquals("WAIT POWER", DockOperationalPolicy.status(true, 30,
			"DOCK WAIT / POWER SUPPLY", 0, 4000, false, true).label());
	}

	@Test
	void emptyDockReportsMissingPowerButTreatsOptionalSuppliesAsMissionSpecific() {
		DockOperationalPolicy.Status blocked = DockOperationalPolicy.status(false, 0, "", 0, 4000, false, true);
		assertEquals("WAIT POWER", blocked.label());
		assertEquals(DockOperationalPolicy.Severity.BLOCKED, blocked.severity());
		assertEquals(DockOperationalPolicy.Severity.READY,
			DockOperationalPolicy.status(false, 0, "", 1000, 4000, true, false).severity());
	}
}
