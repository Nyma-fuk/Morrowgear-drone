package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

final class DockServicePolicyTest {
	@Test
	void largeAirframeTouchdownPoseRemainsInsideServiceEnvelope() {
		Vec3 dock = new Vec3(10.5, 64.5, -2.5);
		assertTrue(DockServicePolicy.serviceEnvelope(new Vec3(10.5, 66.79, -2.5), dock));
		assertTrue(DockServicePolicy.serviceEnvelope(new Vec3(12.9, 68.9, -2.5), dock));
		assertFalse(DockServicePolicy.serviceEnvelope(new Vec3(13.4, 66.0, -2.5), dock));
		assertFalse(DockServicePolicy.serviceEnvelope(new Vec3(10.5, 69.1, -2.5), dock));
	}
	@Test
	void damagedAircraftRequiresMaterialBeforeSortieCanComplete() {
		assertTrue(DockServicePolicy.needsRepair(12.0f, 40.0f));
		assertFalse(DockServicePolicy.needsRepair(40.0f, 40.0f));
		assertEquals("DOCK WAIT / REPAIR MATERIAL", DockServicePolicy.waitingStatus(
			DroneServicePolicy.Need.DAMAGE, true, false, true));
	}

	@Test
	void serviceReportsTheResourceThatActuallyBlocksReadiness() {
		assertEquals("DOCK WAIT / AMMUNITION", DockServicePolicy.waitingStatus(
			DroneServicePolicy.Need.AUTOCANNON_AMMO, true, true, false));
		assertEquals("DOCK WAIT / POWER SUPPLY", DockServicePolicy.waitingStatus(
			DroneServicePolicy.Need.WEAPON_POWER, false, true, true));
		assertEquals("DOCK SERVICE / IN PROGRESS", DockServicePolicy.waitingStatus(
			DroneServicePolicy.Need.WEAPON_POWER, true, true, true));
	}

	@Test
	void partialPowerRemainderLoadsTheNextCellBeforeServiceStalls() {
		assertTrue(DockServicePolicy.shouldLoadNextPowerCell(3, 5));
		assertFalse(DockServicePolicy.shouldLoadNextPowerCell(5, 5));
		assertTrue(DockServicePolicy.shouldLoadNextPowerCell(1, 2));
	}

	@Test
	void readinessStatusExposesEveryRemainingSortieGate() {
		assertEquals("DOCK WAIT / POWER SUPPLY / FLT 75/65 WPN 89/65 AMMO 69/1",
			DockServicePolicy.readinessStatus("DOCK WAIT / POWER SUPPLY",
				75, 89, 65, 69, 1));
	}

	@Test
	void unavailablePowerOrAmmunitionCanEndAnImpossibleServiceWait() {
		assertTrue(DockServicePolicy.completionResourceExhausted(true, false, false, true));
		assertTrue(DockServicePolicy.completionResourceExhausted(false, true, true, false));
		assertFalse(DockServicePolicy.completionResourceExhausted(true, true, true, true));
	}

	@Test
	void dedicatedWeaponCellIsIndependentOfFlightFuel() {
		assertTrue(DockServicePolicy.completionResourceExhausted(false, true, true, false, false, true));
		assertTrue(DockServicePolicy.completionResourceExhausted(true, false, false, true, false, true));
		assertFalse(DockServicePolicy.completionResourceExhausted(false, false, false, false, false, false));
		assertEquals("DOCK WAIT / LASER CELL", DockServicePolicy.waitingStatus(
			DroneServicePolicy.Need.WEAPON_POWER, false, true, true, false, true, true));
		assertEquals("DOCK WAIT / FLIGHT POWER", DockServicePolicy.waitingStatus(
			DroneServicePolicy.Need.FLIGHT_POWER, true, false, true, true, true, true));
	}

	@Test
	void autoDoesNotMistakeOneAvailableAmmoKindForAllRequiredSupplies() {
		assertFalse(DockServicePolicy.normalAmmunitionSupplyAvailable(SecurityLoadout.AUTO, 0, 0, true, false));
		assertFalse(DockServicePolicy.normalAmmunitionSupplyAvailable(SecurityLoadout.AUTO, 0, 0, false, true));
		assertTrue(DockServicePolicy.normalAmmunitionSupplyAvailable(SecurityLoadout.AUTO, 0, 2, true, false));
		assertTrue(DockServicePolicy.normalAmmunitionSupplyAvailable(SecurityLoadout.AUTO, 144, 0, false, true));
		assertTrue(DockServicePolicy.normalAmmunitionSupplyAvailable(SecurityLoadout.AUTO, 144, 2, false, false));
		assertTrue(DockServicePolicy.normalAmmunitionSupplyAvailable(SecurityLoadout.LASER, 0, 0, false, false));
	}

	@Test
	void exhaustedDedicatedSuppliesDoNotBypassMinimumCombatSafety() {
		boolean exhausted = DockServicePolicy.completionResourceExhausted(false, true, true, false, false, true);
		assertTrue(CombatPolicy.resourceLimitedSortieReady(SecurityLoadout.LASER, 1, 75, 80, 0, 0, 0, true, exhausted));
		assertFalse(CombatPolicy.resourceLimitedSortieReady(SecurityLoadout.LASER, 1, 20, 80, 0, 0, 0, true, exhausted));
		assertFalse(CombatPolicy.resourceLimitedSortieReady(SecurityLoadout.LASER, 1, 75, 10, 0, 0, 0, true, exhausted));
		assertFalse(CombatPolicy.resourceLimitedSortieReady(SecurityLoadout.LASER, 1, 75, 80, 0, 0, 0, false, exhausted));
	}
}
