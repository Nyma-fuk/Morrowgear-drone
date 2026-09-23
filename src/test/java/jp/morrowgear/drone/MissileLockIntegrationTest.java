package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Wiring checks, not a substitute for firing against entities in a running ServerLevel. */
final class MissileLockIntegrationTest {
	private static String source() throws Exception {
		return Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
	}
	private static String section(String source, String start, String end) {
		int from = source.indexOf(start);
		int to = source.indexOf(end, from + start.length());
		assertTrue(from >= 0 && to > from, "Missing method boundary: " + start);
		return source.substring(from, to);
	}

	@Test void committedPassBypassesTheaterReleaseAndOrdinaryPrimarySelection() throws Exception {
		String source = source();
		String dispatch = section(source, "private void updateEmergencyInterception(", "private void clearEmergencyInterception(");
		assertTrue(dispatch.indexOf("if (missilePassCommitted())") < dispatch.indexOf("if (assignment == null)"));
		String combat = section(source, "private void updateCombat(", "private void repairLowestSubsystem(");
		assertTrue(combat.indexOf("if (missilePassCommitted())") < combat.indexOf("committedCombatTarget("));
		assertTrue(combat.contains("tickMissileAttack(level, owner, null)"));
		assertTrue(combat.contains("nextMissilePass(level, owner)"));
		assertTrue(combat.indexOf("if (serviceReturn)") < combat.indexOf("if (missilePassCommitted())"));
	}

	@Test void candidatesUseExistingBoundedIntelAndRecheckHostilityRangeAndLine() throws Exception {
		String source = source();
		String candidates = section(source, "private List<MissileLockPolicy.Candidate> missileCandidates(", "private void nextMissilePass(");
		assertTrue(candidates.contains("FleetThreatNetwork.readAll(level.dimension().toString(), ownerId(),"));
		assertTrue(candidates.contains(".limit(128)"));
		assertFalse(candidates.contains("getEntitiesOfClass"));
		String valid = section(source, "private boolean missileTargetUsable(", "private List<MissileLockPolicy.Candidate> missileCandidates(");
		assertTrue(valid.contains("!target.isAlive()"));
		assertTrue(valid.contains("ThreatAssessment.targetDisposition"));
		assertTrue(valid.contains("distanceToSqr(target) > 76.0 * 76.0"));
		assertTrue(valid.contains("MicroMissilePolicy.launchEnvelope"));
		assertTrue(valid.contains("return lineClear("));
	}

	@Test void individualShotsDoNotChangeRepresentativeAndFailuresAdvanceWithoutRefillingPlan() throws Exception {
		String attack = section(source(), "private void tickMissileAttack(", "private boolean missilePassCommitted(");
		assertFalse(attack.contains("entityData.set(COMBAT_TARGET,"));
		assertFalse(attack.contains("emergencyTargetId ="));
		assertFalse(attack.contains("salvoSize"));
		assertTrue(attack.contains("if (missileLock == null)"));
		assertTrue(attack.contains("missileLock = missileLock.skipTarget()"));
		assertTrue(attack.contains("if (MorrowgearMissileEntity.launch("));
		assertTrue(attack.contains("entityData.set(MISSILES, missiles() - 1)"));
		assertTrue(attack.indexOf("missileLock = missileLock.advance()") > attack.indexOf("entityData.set(MISSILES,"));
		assertTrue(attack.contains("if (missileLock.complete()) setCombatState(CombatState.MISSILE_EGRESS)"));
	}

	@Test void stateChangesReloadPowerLossAndCommandsClearTheOldPlan() throws Exception {
		String source = source();
		String state = section(source, "private void setCombatState(", "private float laserEmitterVolume(");
		assertTrue(state.contains("missileLock = null;"));
		String clear = section(source, "private void clearCombatState(", "private void beginCombatRejoin(");
		assertTrue(clear.contains("missileLock = null;"));
		assertTrue(section(source, "private void tickPowerLoss(", "private void capturePowerLossTask(").contains("clearCombatState()"));
		assertTrue(section(source, "public void setMode(", "public void assignFollowFormation(").contains("cancelMissileAttack()"));
		assertTrue(section(source, "public void assignSecurityLoadout(", "public int combatTargetId(").contains("clearCombatState()"));
		assertTrue(source.substring(source.indexOf("protected void readAdditionalSaveData("))
			.contains("if (combatWeapon() == CombatWeapon.MISSILE) clearCombatState()"));
	}

	@Test void lateLockDoesNotLoseRemainingSalvoAtApproachDeadline() throws Exception {
		String attack = section(source(), "private void tickMissileAttack(", "private boolean missilePassCommitted(");
		assertTrue(attack.contains("if (missileLock == null && MissileLockPolicy.expired("));
		var plan = new MissileLockPolicy.Plan(java.util.Collections.nCopies(5, new java.util.UUID(0, 1)));
		var progress = plan.start();
		long started = 0;
		long firstShot = MissileLockPolicy.MAX_APPROACH_TICKS - 1;
		assertFalse(MissileLockPolicy.expired(started, firstShot));
		for (int shot = 0; shot < 5; shot++) {
			long now = firstShot + shot * MicroMissilePolicy.SALVO_INTERVAL_TICKS;
			assertTrue(MicroMissilePolicy.launchReady(now - started, now, now));
			assertFalse(progress.complete());
			progress = progress.advance();
		}
		assertTrue(progress.complete());
	}
}
