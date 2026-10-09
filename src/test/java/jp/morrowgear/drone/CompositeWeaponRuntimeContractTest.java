package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CompositeWeaponRuntimeContractTest {
	private static final Path SOURCE = Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java");

	@Test void auxiliaryPowerIsIndependentPersistentAndRecoversWithoutDocking() throws Exception {
		String source = Files.readString(SOURCE);
		assertTrue(source.contains("builder.define(AUXILIARY_POWER, 1000)"));
		assertTrue(source.contains("output.putInt(\"AuxiliaryPower\""));
		assertTrue(source.contains("input.getIntOr(\"AuxiliaryPower\", 1000)"));
		String recovery = section(source, "private void tickAuxiliaryPower()", "private void tickPowerLoss(");
		assertTrue(recovery.contains("CombatState.GUN_RUN"));
		assertTrue(recovery.contains("CombatState.MISSILE_APPROACH"));
		assertFalse(recovery.contains("beginWeaponRecharge"));
	}

	@Test void eachWeaponConsumesOnlyItsOwnedPersistentResource() throws Exception {
		String source = Files.readString(SOURCE);
		String gun = section(source, "private void tickAutocannon(", "private void applyAutocannonImpact(");
		assertTrue(gun.contains("entityData.set(GUN_AMMO"));
		assertTrue(gun.contains("entityData.set(AUXILIARY_POWER"));
		assertFalse(gun.contains("entityData.set(WEAPON_POWER"));

		String laser = section(source, "private void tickLaserFire(", "private boolean laserGroupCoolingRequired(");
		assertTrue(laser.contains("entityData.set(WEAPON_POWER"));
		assertFalse(laser.contains("entityData.set(GUN_AMMO"));
		assertFalse(laser.contains("entityData.set(MISSILES"));

		String missile = section(source, "private void tickMissileAttack(", "private boolean missilePassCommitted(");
		assertTrue(missile.contains("entityData.set(MISSILES"));
		assertTrue(missile.contains("entityData.set(AUXILIARY_POWER"));
		assertFalse(missile.contains("entityData.set(WEAPON_POWER"));
	}

	@Test void temporaryAuxiliaryShortageHoldsAtWaveBoundaryInsteadOfReturningToDock() throws Exception {
		String source = Files.readString(SOURCE);
		String auxiliary = section(source, "if (combatWeapon() != CombatWeapon.NONE", "CombatState next = switch");
		assertTrue(auxiliary.contains("setCombatState(CombatState.WAVE_ASSESS)"));
		assertTrue(auxiliary.contains("AUXILIARY POWER RECOVERY / WAVE HELD"));
		assertFalse(auxiliary.contains("beginWeaponRecharge"),
			"auxiliary recovery must not turn into an immediate Dock return");
	}

	@Test void committedLaserEnergyLossReleasesTheWholeFormationAtTheWaveBoundary() throws Exception {
		String source = Files.readString(SOURCE);
		String charge = section(source, "private void tickLaserCharge(", "private void tickLaserFire(");
		String fire = section(source, "private void tickLaserFire(", "private boolean laserGroupCoolingRequired(");
		String group = section(source, "private void beginLaserGroupAssessment(", "private void tickLaserCoolOrbit(");
		assertTrue(charge.contains("beginLaserGroupAssessment"));
		assertTrue(fire.contains("beginLaserGroupAssessment"));
		assertFalse(charge.contains("beginWeaponRecharge"));
		assertFalse(fire.contains("beginWeaponRecharge"));
		assertTrue(group.contains("laserFormationMembers"));
		assertTrue(group.contains("CombatState.WAVE_ASSESS"));
	}

	@Test void chargedLaserUsesItsOwnClearFiringWindowWithoutWaitingForFormationCompletion() throws Exception {
		String source = Files.readString(SOURCE);
		String charge = section(source, "private void tickLaserCharge(", "private void tickLaserFire(");
		String window = section(source, "private boolean laserFiringWindowReady(", "private int laserSynchronizedCount(");
		assertTrue(charge.contains("laserFiringWindowReady(level, target)"));
		assertTrue(charge.contains("position().distanceTo(center) <= CombatPolicy.LASER_MAX_RANGE"));
		assertTrue(window.contains("lineClear(level, laserOutletPosition(), center)"));
		assertFalse(window.contains("laserFormationReady"),
			"formation completion must improve coordination, not block an aircraft with a clear shot");
	}

	@Test void gunDepletionCompletesBreakawayBeforeAnotherWeaponIsSelected() throws Exception {
		String source = Files.readString(SOURCE);
		String gun = section(source, "private void tickAutocannon(", "private void applyAutocannonImpact(");
		assertTrue(gun.contains("CombatState.GUN_BREAKAWAY"));
		assertTrue(source.contains("case GUN_BREAKAWAY -> CombatPolicy.egress"));
		assertTrue(source.contains("if (combatElapsed(level) >= 28) setCombatState(CombatState.WAVE_ASSESS)"));
	}

	private static String section(String source, String start, String end) {
		int from = source.indexOf(start);
		int to = source.indexOf(end, from + start.length());
		if (from < 0 || to < 0) throw new AssertionError("section not found: " + start);
		return source.substring(from, to);
	}
}
