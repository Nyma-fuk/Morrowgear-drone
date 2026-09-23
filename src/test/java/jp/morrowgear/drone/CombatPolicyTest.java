package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class CombatPolicyTest {
	@Test
	void laserOrbitAltitudeChargeCannotJumpWhenAnEngagementResets() {
		int charge = CombatPolicy.smoothLaserOrbitCharge(1000, 0, 1);
		assertEquals(996, charge);
		charge = CombatPolicy.smoothLaserOrbitCharge(charge, 0, 10);
		assertEquals(956, charge);
		assertEquals(24, CombatPolicy.smoothLaserOrbitCharge(0, 1000, 1));
	}

	@Test
	void combatTrailIsLimitedToTheFlareTransition() {
		assertTrue(CombatState.FLARE_ENTRY.showsCombatTrail());
		for (CombatState state : CombatState.values()) {
			if (state != CombatState.FLARE_ENTRY) assertTrue(!state.showsCombatTrail(), state.name());
		}
	}

	@Test
	void attackerAllocationScalesWithoutConsumingAnUnlimitedFleet() {
		assertEquals(1, CombatPolicy.requiredAttackers(1, 20, 100));
		assertEquals(4, CombatPolicy.requiredAttackers(3, 40, 100));
		assertEquals(8, CombatPolicy.requiredAttackers(8, 100, 100));
	}

	@Test
	void mixedWingReceivesDistinctWeapons() {
		assertEquals(CombatWeapon.AUTOCANNON, CombatPolicy.weaponFor(0, 4, 1, 20, 240, 4, 0));
		assertEquals(CombatWeapon.LASER, CombatPolicy.weaponFor(1, 4, 1, 20, 240, 4, 0));
		assertEquals(CombatWeapon.MISSILE, CombatPolicy.weaponFor(2, 4, 1, 20, 240, 4, 0));
	}

	@Test
	void installedSecurityLoadoutOverridesAutomaticAllocation() {
		assertEquals(CombatWeapon.NONE, CombatPolicy.weaponFor(SecurityLoadout.UNARMED,
			0, 4, 1, 20, 240, 4, 0));
		assertEquals(CombatWeapon.AUTOCANNON, CombatPolicy.weaponFor(SecurityLoadout.AUTOCANNON,
			1, 4, 1, 20, 240, 4, 0));
		assertEquals(CombatWeapon.LASER, CombatPolicy.weaponFor(SecurityLoadout.LASER,
			0, 4, 1, 20, 240, 4, 0));
		assertEquals(CombatWeapon.MISSILE, CombatPolicy.weaponFor(SecurityLoadout.MISSILE,
			0, 4, 1, 20, 240, 4, 0));
		assertEquals(CombatWeapon.NONE, CombatPolicy.weaponFor(SecurityLoadout.AUTOCANNON,
			0, 4, 1, 20, 0, 4, 0));
	}

	@Test void unarmedSecurityAircraftCannotSortieBeforeAModuleIsInstalled() {
		assertFalse(CombatPolicy.hasUsableWeapon(SecurityLoadout.UNARMED, 240, 4, 0));
		assertFalse(CombatPolicy.sortieReady(SecurityLoadout.UNARMED, 1.0f,
			100, 100, 240, 4, 0, true));
	}

	@Test
	void autoReturnsNoWeaponInsteadOfSacrificingTheAircraft() {
		assertEquals(CombatWeapon.NONE, CombatPolicy.weaponFor(0, 1,
			0.25f, 20, 0, 0, 900));
		assertEquals(CombatWeapon.AUTOCANNON, CombatPolicy.weaponFor(0, 1,
			1.0f, 20, 10, 0, 900));
	}

	@Test
	void autoFallsBackAcrossAvailableWeaponsBeforeService() {
		assertEquals(CombatWeapon.MISSILE, CombatPolicy.weaponFor(0, 1,
			1.0f, 20, 0, 2, 900));
		assertEquals(CombatWeapon.LASER, CombatPolicy.weaponFor(0, 1,
			1.0f, 20, 0, 0, 200));
		assertTrue(CombatPolicy.hasUsableWeapon(SecurityLoadout.AUTO, 1, 0, 1000));
		assertFalse(CombatPolicy.hasUsableWeapon(SecurityLoadout.AUTO, 0, 0, 1000));
	}

	@Test
	void weaponServiceDecisionIsIndependentFromFlightSafety() {
		assertTrue(CombatPolicy.needsWeaponService(SecurityLoadout.AUTO,
			14, 240, 4, 0));
		assertTrue(CombatPolicy.needsWeaponService(SecurityLoadout.AUTO,
			100, 0, 0, 1000));
		assertFalse(CombatPolicy.needsWeaponService(SecurityLoadout.AUTO,
			100, 240, 4, 0));
	}

	@Test
	void eachInstalledWeaponReportsItsExactDockServiceReason() {
		assertEquals(DroneServicePolicy.Need.WEAPON_POWER, CombatPolicy.weaponServiceNeed(
			SecurityLoadout.AUTO, 14, 240, 4, 0));
		assertEquals(DroneServicePolicy.Need.AUTOCANNON_AMMO, CombatPolicy.weaponServiceNeed(
			SecurityLoadout.AUTOCANNON, 100, 0, 4, 0));
		assertEquals(DroneServicePolicy.Need.MISSILE_AMMO, CombatPolicy.weaponServiceNeed(
			SecurityLoadout.MISSILE, 100, 240, 0, 0));
		assertEquals(DroneServicePolicy.Need.NONE, CombatPolicy.weaponServiceNeed(
			SecurityLoadout.LASER, 100, 240, 4, CombatPolicy.LASER_SWITCH_HEAT));
		assertEquals(DroneServicePolicy.Need.NONE, CombatPolicy.weaponServiceNeed(
			SecurityLoadout.AUTO, 100, 1, 0, 1000));
		assertEquals(DroneServicePolicy.Need.WEAPON_REARM, CombatPolicy.weaponServiceNeed(
			SecurityLoadout.AUTO, 100, 0, 0, 1000));
	}

	@Test
	void overheatedLaserCoolsInTheFieldInsteadOfRequestingDockService() {
		assertFalse(CombatPolicy.hasUsableWeapon(SecurityLoadout.LASER,
			CombatPolicy.GUN_CAPACITY, CombatPolicy.MISSILE_CAPACITY, CombatPolicy.LASER_SWITCH_HEAT));
		assertEquals(DroneServicePolicy.Need.NONE, CombatPolicy.weaponServiceNeed(
			SecurityLoadout.LASER, 100, CombatPolicy.GUN_CAPACITY,
			CombatPolicy.MISSILE_CAPACITY, CombatPolicy.LASER_SWITCH_HEAT));
		assertTrue(CombatPolicy.hasUsableWeapon(SecurityLoadout.LASER,
			CombatPolicy.GUN_CAPACITY, CombatPolicy.MISSILE_CAPACITY,
			CombatPolicy.LASER_SWITCH_HEAT - 1));
	}

	@Test
	void autocannonFiresTenRoundsPerSecondAtTwentyTicksPerSecond() {
		long shots = java.util.stream.IntStream.range(0, 20)
			.filter(CombatPolicy::autocannonFireTick).count();
		assertEquals(10, shots);
	}

	@Test
	void normalAndUrgentSortiesUseDifferentReadinessThresholds() {
		assertFalse(CombatPolicy.sortieReady(SecurityLoadout.AUTO, 1.0f,
			90, 90, 143, 2, 0, false));
		assertTrue(CombatPolicy.sortieReady(SecurityLoadout.AUTO, 1.0f,
			90, 90, 144, 2, 200, false));
		assertTrue(CombatPolicy.sortieReady(SecurityLoadout.AUTO, 1.0f,
			90, 90, 240, 2, 1000, false));
		assertFalse(CombatPolicy.sortieReady(SecurityLoadout.LASER, 1.0f,
			90, 90, 240, 2, CombatPolicy.LASER_SWITCH_HEAT, false));
		assertTrue(CombatPolicy.sortieReady(SecurityLoadout.AUTO, 1.0f,
			65, 65, 1, 0, 1000, true));
		assertFalse(CombatPolicy.sortieReady(SecurityLoadout.AUTO, 1.0f,
			64, 100, 240, 4, 0, true));
		assertFalse(CombatPolicy.sortieReady(SecurityLoadout.AUTO, 0.29f,
			100, 100, 240, 4, 0, true));
	}

	@Test
	void exhaustedDockCanReleaseAMinimallyReadyAircraftIntoAnActiveBattle() {
		assertTrue(CombatPolicy.resourceLimitedSortieReady(SecurityLoadout.AUTOCANNON,
			0.75f, 65, 65, 1, 0, 0, true, true));
		assertFalse(CombatPolicy.resourceLimitedSortieReady(SecurityLoadout.AUTOCANNON,
			0.75f, 65, 65, 1, 0, 0, false, true));
		assertFalse(CombatPolicy.resourceLimitedSortieReady(SecurityLoadout.AUTOCANNON,
			0.75f, 64, 65, 1, 0, 0, true, true));
		assertFalse(CombatPolicy.resourceLimitedSortieReady(SecurityLoadout.AUTOCANNON,
			0.75f, 65, 65, 0, 0, 0, true, true));
	}

	@Test
	void autoCanUseItsRemainingGunWhenMissileResupplyIsExhausted() {
		assertFalse(CombatPolicy.normalPayloadReady(SecurityLoadout.AUTO, 144, 0, 0));
		assertTrue(CombatPolicy.resourceLimitedSortieReady(SecurityLoadout.AUTO,
			1.0f, 70, 70, 12, 0, 0, true, true));
	}

	@Test
	void casElementFollowsOneFigureEightWithOrderedTrailSlots() {
		Vec3 target = new Vec3(10, 64, 10);
		Vec3 first = CombatPolicy.casFormation(target, new Vec3(0, 0, 1), 0, 4, 80);
		assertTrue(java.util.stream.LongStream.range(81, 1800).anyMatch(tick ->
			Math.signum(CombatPolicy.casFormation(target, new Vec3(0, 0, 1), 0, 4, tick).x - target.x)
				!= Math.signum(first.x - target.x)), "both lobes must be flown within one full pass");
		Vec3 leader = CombatPolicy.casFormation(target, new Vec3(0, 0, 1), 0, 4, 110);
		Vec3 wingman = CombatPolicy.casFormation(target, new Vec3(0, 0, 1), 1, 4, 110);
		assertTrue(leader.distanceTo(wingman) > 5.0 && leader.distanceTo(wingman) < 10.0);
		assertTrue(java.util.stream.LongStream.range(0, 520)
			.anyMatch(tick -> CombatPolicy.casGunWindow(tick, 0)));
	}

	@Test
	void casFigureEightIsLongerAlongTheAttackAxis() {
		Vec3 target = new Vec3(0, 64, 0);
		double maxLongitudinal = java.util.stream.LongStream.range(0, 280)
			.mapToDouble(tick -> Math.abs(CombatPolicy.casFormation(target,
				new Vec3(0, 0, 1), 0, 1, tick).z - target.z)).max().orElseThrow();
		double maxLateral = java.util.stream.LongStream.range(0, 280)
			.mapToDouble(tick -> Math.abs(CombatPolicy.casFormation(target,
				new Vec3(0, 0, 1), 0, 1, tick).x - target.x)).max().orElseThrow();
		assertTrue(maxLongitudinal > 50.0);
		assertTrue(maxLongitudinal > maxLateral * 4.5);
	}

	@Test void casDescendsForTheLinearStrikeAndClimbsForEgress() {
		Vec3 target = new Vec3(0, 64, 0);
		double minimum = java.util.stream.LongStream.range(0, 500)
			.mapToDouble(tick -> CombatPolicy.casFormation(target, new Vec3(0, 0, 1),
				0, 1, tick).y).min().orElseThrow();
		double maximum = java.util.stream.LongStream.range(0, 500)
			.mapToDouble(tick -> CombatPolicy.casFormation(target, new Vec3(0, 0, 1),
				0, 1, tick).y).max().orElseThrow();
		assertTrue(maximum - minimum > 11.0);
		List<Vec3> impacts = java.util.stream.LongStream.range(0, 500)
			.filter(tick -> CombatPolicy.casGunWindow(tick, 0))
			.mapToObj(tick -> CombatPolicy.casStrikePoint(target, new Vec3(0, 0, 1), 0,
				tick, CombatPolicy.AirspaceSlot.single())).toList();
		assertTrue(impacts.stream().mapToDouble(point -> Math.abs(point.z)).max().orElse(0) <= 7.5);
		assertTrue(impacts.stream().mapToDouble(point -> Math.abs(point.z)).max().orElse(0) > 4.0);
		assertTrue(impacts.stream().allMatch(point -> Math.abs(point.x) < 0.001));
	}

	@Test
	void casFormationPublishesSharedBoundedVelocity() {
		Vec3 target = new Vec3(0, 70, 0);
		Vec3 leader = CombatPolicy.casFormationVelocity(target, new Vec3(1, 0, 0), 0, 4, 110);
		Vec3 wingman = CombatPolicy.casFormationVelocity(target, new Vec3(1, 0, 0), 1, 4, 110);
		assertTrue(leader.length() > 0.35 && leader.length() < 1.20);
		assertTrue(leader.normalize().dot(wingman.normalize()) > 0.90);
	}

	@Test
	void casPathMaintainsNearlyConstantSpeedAfterSmoothAcceleration() {
		Vec3 target = new Vec3(0, 70, 0);
		double minimum = java.util.stream.LongStream.range(60, 400)
			.mapToDouble(tick -> CombatPolicy.casFormationVelocity(target,
				new Vec3(1, 0, 0), 0, 4, tick).length()).min().orElseThrow();
		double maximum = java.util.stream.LongStream.range(60, 400)
			.mapToDouble(tick -> CombatPolicy.casFormationVelocity(target,
				new Vec3(1, 0, 0), 0, 4, tick).length()).max().orElseThrow();
		assertTrue(minimum >= 0.88, "minimum=" + minimum);
		assertTrue(maximum <= 0.92, "maximum=" + maximum);
		assertTrue(maximum - minimum < 0.02, "variation=" + (maximum - minimum));
	}

	@Test
	void fastOrTeleportingTargetCannotDragTheCasCenterInstantly() {
		Vec3 previous = new Vec3(0, 65, 0);
		Vec3 observed = new Vec3(20, 70, 20);
		Vec3 next = CombatPolicy.stabilizeCasCenter(previous, observed, new Vec3(1.4, 0, 0));
		assertTrue(next.subtract(previous).multiply(1, 0, 1).length() <= 0.7201);
		assertTrue(Math.abs(next.y - previous.y) <= 0.3501);
	}

	@Test
	void breakawayCreatesStandoffAndKeepsElementLanesSeparate() {
		Vec3 origin = new Vec3(0, 65, 0);
		Vec3 first = CombatPolicy.casBreakawayPoint(origin, new Vec3(0, 0, 1), 0, 2);
		Vec3 second = CombatPolicy.casBreakawayPoint(origin, new Vec3(0, 0, 1), 1, 2);
		assertTrue(first.multiply(1, 0, 1).distanceTo(origin.multiply(1, 0, 1)) > 24.0);
		assertTrue(first.distanceTo(second) >= 2.0);
		assertTrue(first.y > origin.y + 8.0);
	}

	@Test
	void mobileTargetsReceiveAControlledCasSlotTolerance() {
		double stationary = CombatPolicy.casSlotTolerance(Vec3.ZERO, 0.0);
		double mobile = CombatPolicy.casSlotTolerance(new Vec3(0.45, 0, 0.2), 8.0);
		assertEquals(4.5, stationary, 0.0001);
		assertTrue(mobile > stationary);
		assertTrue(mobile <= 8.0);
		assertTrue(CombatPolicy.mobileCasTarget(new Vec3(0.2, 0, 0), 0.0));
		assertTrue(CombatPolicy.mobileCasTarget(Vec3.ZERO, 3.0));
	}

	@Test
	void laserClimbsAsChargeCompletes() {
		Vec3 target = new Vec3(0, 64, 0);
		Vec3 empty = CombatPolicy.laserOrbit(target, 0, 4, 0, 0);
		Vec3 full = CombatPolicy.laserOrbit(target, 0, 4, 0, 1000);
		assertTrue(full.y > empty.y + 3.5);
		assertTrue(full.multiply(1, 0, 1).length() > empty.multiply(1, 0, 1).length());
	}

	@Test
	void laserOrbitPublishesAStableTangentVelocity() {
		Vec3 center = new Vec3(4, 70, -3);
		Vec3 velocity = CombatPolicy.laserOrbitVelocity(center, 1, 4, 200, 1000);
		assertTrue(velocity.length() > 0.20 && velocity.length() < 0.35);
		assertTrue(Math.abs(velocity.y) < 0.0001);
	}

	@Test
	void laserUnitsAreEvenlySpacedAroundOneTarget() {
		Vec3 center = new Vec3(0, 64, 0);
		Vec3 a = CombatPolicy.laserOrbit(center, 0, 3, 80, 1000);
		Vec3 b = CombatPolicy.laserOrbit(center, 1, 3, 80, 1000);
		Vec3 c = CombatPolicy.laserOrbit(center, 2, 3, 80, 1000);
		assertEquals(a.distanceTo(b), b.distanceTo(c), 0.0001);
		assertEquals(b.distanceTo(c), c.distanceTo(a), 0.0001);
		assertEquals(a.y, b.y, 0.0001);
		assertEquals(b.y, c.y, 0.0001);
	}

	@Test
	void synchronizedLaserFormationScalesFromNormalSoloToHighEightUnitOutput() {
		assertEquals(1.35f, CombatPolicy.laserPulseDamage(1), 0.0001f);
		assertEquals(1.35f, CombatPolicy.laserPulseDamage(0), 0.0001f);
		assertTrue(CombatPolicy.laserPulseDamage(4) > CombatPolicy.laserPulseDamage(2));
		assertTrue(CombatPolicy.laserPulseDamage(8) > CombatPolicy.laserPulseDamage(4));
		float soloDps = CombatPolicy.laserPulseDamage(1) * 5.0f;
		float fullFormationDps = CombatPolicy.laserPulseDamage(8) * 5.0f * 8.0f;
		assertEquals(6.75f, soloDps, 0.001f);
		assertEquals(182.52f, fullFormationDps, 0.05f);
		assertEquals(CombatPolicy.laserPulseDamage(8), CombatPolicy.laserPulseDamage(20), 0.0001f);
	}

	@Test
	void laserFormationHasTimeToAssembleAndSustainOneCoordinatedBurn() {
		assertEquals(129, CombatPolicy.laserSustainedFireTicks());
		assertTrue(CombatPolicy.laserSustainedFireTicks() >= 120);
		assertTrue(CombatPolicy.LASER_FIRE_POWER_PER_TICK
			* CombatPolicy.laserSustainedFireTicks() < 400);
	}

	@Test
	void laserReleasePrefersFullFormationButCannotDeadlockOnOneStuckAircraft() {
		assertTrue(CombatPolicy.laserReleaseReady(8, 8, false, 0));
		assertTrue(!CombatPolicy.laserReleaseReady(8, 6, false, 23));
		assertTrue(CombatPolicy.laserReleaseReady(8, 6, false, 24));
		assertTrue(!CombatPolicy.laserReleaseReady(8, 5, false, 59));
		assertTrue(CombatPolicy.laserReleaseReady(8, 1, false, 60));
		assertTrue(CombatPolicy.laserReleaseReady(8, 1, true, 0));
		assertTrue(!CombatPolicy.laserReleaseReady(8, 0, true, 80));
	}

	@Test
	void chargedLaserTimeoutReleasesOnlyAircraftThatReachedItsSlot() {
		assertTrue(!CombatPolicy.laserFallbackReleaseReady(59, 12.0, 5.0, true, true));
		assertTrue(!CombatPolicy.laserFallbackReleaseReady(60, 12.0, 5.0, true, false));
		assertTrue(CombatPolicy.laserFallbackReleaseReady(60, 12.0, 5.0, true, true));
		assertTrue(CombatPolicy.laserFallbackReleaseReady(60, 36.0, 1.65, true, false));
		assertTrue(!CombatPolicy.laserFallbackReleaseReady(60, 48.01, 5.0, true, true));
		assertTrue(!CombatPolicy.laserFallbackReleaseReady(60, 12.0, 5.0, false, true));
	}

	@Test
	void autocannonRejectsTerrainInterceptionFarFromItsStrikeLine() {
		Vec3 target = new Vec3(0, 64, 0);
		Vec3 intended = new Vec3(6, 64, 0);
		assertTrue(CombatPolicy.autocannonImpactAcceptable(intended,
			new Vec3(6.5, 64, 0.5), target));
		assertTrue(!CombatPolicy.autocannonImpactAcceptable(intended,
			new Vec3(33, 64, 0), target));
		assertTrue(!CombatPolicy.autocannonImpactAcceptable(intended,
			new Vec3(0, 64, 0), target));
	}

	@Test
	void laserFormationCarriesTargetVelocityWithoutChangingItsRotationDirection() {
		Vec3 center = new Vec3(0, 64, 0);
		Vec3 targetVelocity = new Vec3(0.12, 0.03, -0.08);
		Vec3 stationary = CombatPolicy.laserOrbitVelocity(center, 0, 4, 80, 1000);
		Vec3 moving = CombatPolicy.laserOrbitVelocity(center, targetVelocity, 0, 4, 80, 1000);
		assertEquals(targetVelocity.x, moving.x - stationary.x, 0.0001);
		assertEquals(targetVelocity.y, moving.y - stationary.y, 0.0001);
		assertEquals(targetVelocity.z, moving.z - stationary.z, 0.0001);
	}

	@Test
	void temporaryCombatGroupsReceiveStableDistinctAirspace() {
		List<String> keys = List.of("WING-B|LASER", "WING-A|LASER", "WING-B|LASER");
		CombatPolicy.AirspaceSlot alpha = CombatPolicy.airspaceSlot(keys, "WING-A|LASER");
		CombatPolicy.AirspaceSlot bravo = CombatPolicy.airspaceSlot(keys, "WING-B|LASER");
		assertEquals(0, alpha.index());
		assertEquals(1, bravo.index());
		assertEquals(2, alpha.count());
		assertEquals(2, bravo.count());

		Vec3 center = new Vec3(0, 64, 0);
		Vec3 alphaOrbit = CombatPolicy.laserOrbit(center, 0, 3, 80, 1000, alpha);
		Vec3 bravoOrbit = CombatPolicy.laserOrbit(center, 0, 3, 80, 1000, bravo);
		assertTrue(bravoOrbit.y - alphaOrbit.y >= 2.2);
		assertTrue(bravoOrbit.multiply(1, 0, 1).length()
			- alphaOrbit.multiply(1, 0, 1).length() >= 1.3);
	}

	@Test
	void autocannonAndMissileElementsUseSeparateHeightAndApproachLanes() {
		CombatPolicy.AirspaceSlot first = new CombatPolicy.AirspaceSlot(0, 2);
		CombatPolicy.AirspaceSlot second = new CombatPolicy.AirspaceSlot(1, 2);
		Vec3 center = new Vec3(0, 64, 0);
		Vec3 axis = new Vec3(0, 0, 1);
		Vec3 casA = CombatPolicy.casFormation(center, axis, 0, 1, 120, first);
		Vec3 casB = CombatPolicy.casFormation(center, axis, 0, 1, 120, second);
		assertTrue(Math.abs(casA.y - casB.y) >= 1.8);
		assertTrue(casA.multiply(1, 0, 1).distanceTo(casB.multiply(1, 0, 1)) > 3.0);

		Vec3 from = new Vec3(0, 70, -20);
		Vec3 missileA = CombatPolicy.missileApproach(center, from, 0, 1, first);
		Vec3 missileB = CombatPolicy.missileApproach(center, from, 0, 1, second);
		assertTrue(Math.abs(missileA.y - missileB.y) >= 2.2);
		assertTrue(missileA.multiply(1, 0, 1).distanceTo(missileB.multiply(1, 0, 1)) > 10.0);
	}

	@Test
	void denseCombatAirspaceRemainsInsideTheWeaponEnvelope() {
		Vec3 center = new Vec3(0, 64, 0);
		Set<String> lanes = new java.util.HashSet<>();
		for (int index = 0; index < 16; index++) {
			CombatPolicy.AirspaceSlot airspace = new CombatPolicy.AirspaceSlot(index, 16);
			Vec3 orbit = CombatPolicy.laserOrbit(center, 0, 1, 80, 1000, airspace);
			double radius = orbit.multiply(1, 0, 1).length();
			double height = orbit.y - center.y;
			assertTrue(radius <= 22.001, "radius outside laser envelope: " + radius);
			assertTrue(height <= 28.001, "height outside laser envelope: " + height);
			assertTrue(orbit.distanceTo(center) < 48.0, "all allocated layers must be able to fire");
			assertTrue(lanes.add(String.format(java.util.Locale.ROOT, "%.2f/%.2f",
				airspace.heightOffset(), airspace.radiusOffset())));
		}
	}

	@Test
	void ballisticAimIsFixedBoundedAndLeadsTheTarget() {
		Vec3 center = new Vec3(0, 10, 0);
		Vec3 aim = CombatPolicy.ballisticAim(center, new Vec3(1, 0, 0), 42, 12.0, 0.72);
		assertTrue(aim.x > 0.15);
		assertTrue(aim.distanceTo(center) < 1.5);
		assertNotEquals(center, aim);
	}

	@Test
	void eachAircraftMayFireBeforeTheWholeFormationSettles() {
		CombatPolicy.FiringSolution ideal = CombatPolicy.firingSolution(8.5, 8.5,
			1.2, 1.65, 24.0);
		CombatPolicy.FiringSolution reachable = CombatPolicy.firingSolution(19.0, 8.5,
			8.0, 1.65, 24.0);
		CombatPolicy.FiringSolution outside = CombatPolicy.firingSolution(24.1, 8.5,
			1.0, 1.65, 24.0);
		assertTrue(ideal.permitted());
		assertEquals(1.0, ideal.accuracy(), 0.0001);
		assertTrue(reachable.permitted());
		assertTrue(reachable.accuracy() >= 0.18 && reachable.accuracy() < 1.0);
		assertFalse(outside.permitted());
	}

	@Test
	void fullAccuracyAlwaysHitsWhileLowerAccuracyCanMissDeterministically() {
		for (int seed = 0; seed < 100; seed++) {
			assertTrue(CombatPolicy.accuracyHit(seed, 1.0));
		}
		assertTrue(java.util.stream.IntStream.range(0, 200)
			.anyMatch(seed -> !CombatPolicy.accuracyHit(seed, 0.35)));
		assertTrue(java.util.stream.IntStream.range(0, 200)
			.anyMatch(seed -> CombatPolicy.accuracyHit(seed, 0.35)));
	}

	@Test
	void autocannonTracerTravelsAsAFiniteSegment() {
		Vec3 muzzle = Vec3.ZERO;
		Vec3 aim = new Vec3(12, 0, 0);
		CombatPolicy.TracerSegment first = CombatPolicy.tracerSegment(muzzle, aim, 0);
		CombatPolicy.TracerSegment next = CombatPolicy.tracerSegment(muzzle, aim, 1);
		assertTrue(first.end().x < aim.x);
		assertTrue(first.end().x < next.start().x);
		assertTrue(next.end().distanceTo(next.start()) <= 2.01);
	}

	@Test
	void autocannonRejectsRearwardFiringSolutionsAfterOverflight() {
		Vec3 muzzle = Vec3.ZERO;
		Vec3 forward = new Vec3(0.8, -0.05, 0.0);
		assertTrue(CombatPolicy.forwardFiringSolution(muzzle, forward, new Vec3(12, -3, 1)));
		assertFalse(CombatPolicy.forwardFiringSolution(muzzle, forward, new Vec3(5, 0, 8)));
		assertFalse(CombatPolicy.forwardFiringSolution(muzzle, forward, new Vec3(-4, -2, 0)));
		assertFalse(CombatPolicy.forwardFiringSolution(muzzle, Vec3.ZERO, new Vec3(4, 0, 0)));
	}

	@Test
	void casGunWindowExistsOnlyOnInboundLegs() {
		Vec3 center = new Vec3(0, 64, 0);
		Vec3 axis = new Vec3(0, 0, 1);
		CombatPolicy.AirspaceSlot lane = new CombatPolicy.AirspaceSlot(2, 4);
		int samples = 0;
		int validFiringSolutions = 0;
		for (long tick = 40; tick < 900; tick++) {
			if (!CombatPolicy.casGunWindow(tick, 0, lane)) continue;
			Vec3 position = CombatPolicy.casFormation(center, axis, 0, 1, tick, lane);
			Vec3 velocity = CombatPolicy.casFormationVelocity(center, axis, 0, 1, tick, lane);
			Vec3 horizontalToTarget = center.subtract(position).multiply(1, 0, 1);
			assertTrue(velocity.multiply(1, 0, 1).dot(horizontalToTarget) > 0.0,
				"gun window must close on the target at tick " + tick);
			Vec3 strike = CombatPolicy.casStrikePoint(center, axis, 0, tick, lane);
			if (CombatPolicy.forwardFiringSolution(position, velocity, strike)) validFiringSolutions++;
			samples++;
		}
		assertTrue(samples > 10);
		assertTrue(validFiringSolutions > 10,
			"the strict forward cone must retain a usable inbound firing interval");
	}

	@Test
	void laserInterceptClimbsBeforeCrossingToTheHighStandoffPoint() {
		Vec3 center = new Vec3(0, 64, 0);
		CombatPolicy.AirspaceSlot lane = new CombatPolicy.AirspaceSlot(1, 3);
		Vec3 lowAndFar = new Vec3(60, 65, 0);
		assertTrue(CombatPolicy.laserIngressRequired(lowAndFar, center, lane));
		Vec3 climb = CombatPolicy.laserIngressWaypoint(lowAndFar, center, lane);
		assertEquals(lowAndFar.x, climb.x, 0.0001);
		assertEquals(lowAndFar.z, climb.z, 0.0001);
		assertTrue(climb.y >= center.y + CombatPolicy.LASER_INGRESS_HEIGHT);

		Vec3 highAndFar = new Vec3(60, climb.y, 0);
		Vec3 staging = CombatPolicy.laserIngressWaypoint(highAndFar, center, lane);
		assertTrue(staging.multiply(1, 0, 1).distanceTo(center.multiply(1, 0, 1)) >= 18.0);
		assertTrue(staging.multiply(1, 0, 1).distanceTo(center.multiply(1, 0, 1)) < 23.0);

		Vec3 established = new Vec3(10, center.y + 12.0 + lane.heightOffset(), 0);
		assertFalse(CombatPolicy.laserIngressRequired(established, center, lane));
	}
}
