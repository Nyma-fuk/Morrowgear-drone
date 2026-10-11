package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class StrikeBeaconPolicyTest {
	@Test void recruitmentHasNoGeometricDistanceInput() {
		assertTrue(recruitable());
	}

	@Test void recruitRequiresIdleArmedHealthySecurityAircraft() {
		assertFalse(StrikeBeaconPolicy.recruitable(DroneRole.SCOUT, true, true, false,
			false, false, false, true, DroneMode.STANDBY, 100, 100, 10, 2));
		assertFalse(StrikeBeaconPolicy.recruitable(DroneRole.SECURITY, true, true, false,
			false, true, false, true, DroneMode.STANDBY, 100, 100, 10, 2));
		assertFalse(StrikeBeaconPolicy.recruitable(DroneRole.SECURITY, true, true, false,
			false, false, false, true, DroneMode.STANDBY, 100, 0, 0, 0));
		assertFalse(StrikeBeaconPolicy.recruitable(DroneRole.SECURITY, true, true, false,
			false, false, false, true, DroneMode.STANDBY,
			StrikeBeaconPolicy.MIN_RETURN_FLIGHT_POWER, 100, 0, 0));
	}

	@Test void threeBeaconsReceiveAStableEvenDistribution() {
		int[] counts = new int[3];
		for (int aircraft = 0; aircraft < 17; aircraft++)
			counts[StrikeBeaconPolicy.balancedBeaconIndex(aircraft, counts.length)]++;
		assertArrayEquals(new int[] {6, 6, 5}, counts);
		assertThrows(IllegalArgumentException.class, () -> StrikeBeaconPolicy.balancedBeaconIndex(0, 0));
	}

	@Test void musterNeverWaitsForeverForAHealthyStraggler() {
		assertFalse(StrikeBeaconPolicy.beginBarrage(40, 4, 3));
		assertTrue(StrikeBeaconPolicy.beginBarrage(40, 4, 4));
		assertTrue(StrikeBeaconPolicy.beginBarrage(StrikeBeaconPolicy.MUSTER_TIMEOUT_TICKS, 4, 3));
		assertFalse(StrikeBeaconPolicy.beginBarrage(StrikeBeaconPolicy.MUSTER_TIMEOUT_TICKS, 0, 0));
	}

	@Test void eachBeaconStartsFromItsOwnArrivalStateWithoutAGlobalBarrier() {
		assertTrue(StrikeBeaconPolicy.beginBarrage(30, 2, 2));
		assertFalse(StrikeBeaconPolicy.beginBarrage(30, 2, 1));
	}

	@Test void everyAircraftUsesTheSamePerBeaconSalvoClock() {
		for (int tick = 0; tick < 24; tick++) {
			boolean expectedGun = tick % StrikeBeaconPolicy.GUN_SALVO_INTERVAL_TICKS == 0;
			boolean expectedMissile = tick % StrikeBeaconPolicy.MISSILE_SALVO_INTERVAL_TICKS == 0;
			for (int aircraft = 0; aircraft < 32; aircraft++) {
				assertEquals(expectedGun, StrikeBeaconPolicy.gunSalvoTick(tick));
				assertEquals(expectedMissile, StrikeBeaconPolicy.missileSalvoTick(tick));
			}
		}
		assertFalse(StrikeBeaconPolicy.gunSalvoTick(-1));
		assertFalse(StrikeBeaconPolicy.missileSalvoTick(-1));
	}

	@Test void synchronizedSalvoKeepsTheFormerAverageConsumptionRate() {
		int gunShots = 0, missileShots = 0;
		for (int tick = 0; tick < 60; tick++) {
			if (StrikeBeaconPolicy.gunSalvoTick(tick)) gunShots++;
			if (StrikeBeaconPolicy.missileSalvoTick(tick)) missileShots++;
		}
		assertEquals(30, gunShots);
		assertEquals(20, missileShots);
	}

	@Test void formationSlotsAreSeparatedAndMaintainAltitude() {
		Vec3 anchor = new Vec3(10, 20, -4);
		Vec3 first = StrikeBeaconPolicy.musterPoint(anchor, 0, 8, 100);
		Vec3 second = StrikeBeaconPolicy.musterPoint(anchor, 1, 8, 100);
		assertTrue(first.distanceTo(second) > 8.0);
		assertEquals(anchor.y + StrikeBeaconPolicy.MUSTER_ALTITUDE, first.y, 1.0e-9);
		assertTrue(StrikeBeaconPolicy.arrived(first.add(StrikeBeaconPolicy.ARRIVAL_TOLERANCE, 0, 0), first));
		assertFalse(StrikeBeaconPolicy.arrived(first.add(Math.nextUp(StrikeBeaconPolicy.ARRIVAL_TOLERANCE), 0, 0), first));
	}

	@Test void musterTransitUsesCombatIngressSpeedAndControlledArrival() {
		assertEquals(StrikeBeaconPolicy.MUSTER_TRANSIT_SPEED,
			StrikeBeaconPolicy.musterFlightSpeed(80.0), 1.0e-9);
		assertEquals(StrikeBeaconPolicy.MUSTER_APPROACH_SPEED,
			StrikeBeaconPolicy.musterFlightSpeed(16.0), 1.0e-9);
		assertEquals(StrikeBeaconPolicy.MUSTER_ORBIT_SPEED,
			StrikeBeaconPolicy.musterFlightSpeed(6.0), 1.0e-9);
		assertTrue(StrikeBeaconPolicy.musterFlightSpeed(80.0) > 0.918,
			"beacon ingress must not inherit the Dock-return cruise cap");
	}

	@Test void lowAircraftClimbsBeforeAdvancingAcrossTerrain() {
		Vec3 current = new Vec3(10, 65, 10);
		Vec3 transit = new Vec3(35, 74, 10);
		assertEquals(new Vec3(10, 74, 10), StrikeBeaconPolicy.climbBeforeCruise(current, transit));
		assertEquals(transit, StrikeBeaconPolicy.climbBeforeCruise(new Vec3(10, 73, 10), transit));
	}

	@Test void runtimeUsesTerrainAwareCombatTransitInsteadOfCarrierDockGuidance() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		int start = source.indexOf("private boolean tickStrikeBeaconMission");
		int end = source.indexOf("private void tickStrikeBeaconWeapons", start);
		assertTrue(start >= 0 && end > start);
		String mission = source.substring(start, end);
		assertTrue(mission.contains("flyStrikeBeaconMuster(level, owner"));
		assertTrue(mission.contains("MissionFlightPlan.transitTarget"));
		assertTrue(mission.contains("Heightmap.Types.MOTION_BLOCKING"));
		assertTrue(mission.contains("DroneNavigator.corridorClear"));
		assertFalse(mission.contains("flyCarrierLocal(level, slot"));
	}

	@Test void strikeSpreadIsDeterministicBoundedAndNotASinglePoint() {
		Vec3 anchor = new Vec3(3, 70, 9);
		Vec3 a = StrikeBeaconPolicy.strikePoint(anchor, 0, 17);
		Vec3 b = StrikeBeaconPolicy.strikePoint(anchor, 0, 17);
		Vec3 c = StrikeBeaconPolicy.strikePoint(anchor, 1, 17);
		assertEquals(a, b);
		assertNotEquals(a, c);
		assertTrue(a.subtract(anchor).multiply(1, 0, 1).length() <= StrikeBeaconPolicy.STRIKE_RADIUS + 1.0e-9);
	}

	@Test void allWeaponPoolsMustBeEmptyBeforeRelease() {
		assertFalse(StrikeBeaconPolicy.resourcesExhausted(1, 0, 0));
		assertFalse(StrikeBeaconPolicy.resourcesExhausted(0, 1, 0));
		assertFalse(StrikeBeaconPolicy.resourcesExhausted(0, 0, 1));
		assertTrue(StrikeBeaconPolicy.resourcesExhausted(0, 0, 0));
	}

	@Test void beaconAutocannonUsesAnIndependentAudioAndTracerEvent() throws Exception {
		String drone = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String renderer = Files.readString(Path.of(
			"src/client/java/jp/morrowgear/drone/client/CombatEffectRenderer.java"));
		String audio = Files.readString(Path.of(
			"src/client/java/jp/morrowgear/drone/client/DroneAudioController.java"));
		int start = drone.indexOf("private void tickStrikeBeaconWeapons");
		int end = drone.indexOf("private void setStrikeVisualAim", start);
		assertTrue(start >= 0 && end > start);
		assertTrue(drone.substring(start, end).contains("recordAutocannonShot(gunImpact, -2)"));
		assertTrue(drone.substring(start, end).contains("StrikeBeaconPolicy.gunSalvoTick(barrageTick)"));
		assertTrue(drone.substring(start, end).contains("StrikeBeaconPolicy.missileSalvoTick(barrageTick)"));
		assertFalse(drone.substring(start, end).contains("now + strikeBeaconSlot()"));
		assertTrue(renderer.contains("state.gunTargetOffset"));
		assertTrue(renderer.contains("state.gunShotAge"));
		assertTrue(audio.contains("drone.gunShotTick()"));
	}

	private static boolean recruitable() {
		return StrikeBeaconPolicy.recruitable(DroneRole.SECURITY, true, true, false,
			false, false, false, true, DroneMode.STANDBY, 100, 100, 10, 2);
	}
}
