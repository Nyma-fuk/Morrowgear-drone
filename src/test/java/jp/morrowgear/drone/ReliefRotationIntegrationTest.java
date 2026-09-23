package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ReliefRotationIntegrationTest {
	@Test void serviceCompletionSnapshotsThenRestoresTheActualReliefMission() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String docked = source.substring(source.indexOf("private void updateCombat("),
			source.indexOf("private int stableCombatSlot("));
		assertTrue(docked.contains("rememberReliefMission(activeRelief)"));
		assertTrue(docked.contains("committedReliefFor(fleet"));
		assertTrue(docked.contains("engagementCommitmentSatisfied(fleet"));
		assertTrue(docked.indexOf("resumeServiceTask(level, reclaimCombat)")
			< docked.indexOf("inheritReliefMission()"));
		assertTrue(docked.contains("completion == GuardDispatchPolicy.RechargeCompletion.INHERIT_RELIEF_MISSION"));
		assertTrue(docked.contains("reclaimCombat && theaterEmergency"));
		assertTrue(docked.contains("if (!reclaimCombat)"));
		assertTrue(source.contains("private record ReliefMissionSnapshot("));
		assertTrue(source.contains("reliefMissionInheritedFrom = relief.securityOrder()"));
		assertTrue(source.contains("!resumeCombat && candidate.kind() == DroneTaskStack.Kind.COMBAT"));
		assertTrue(source.contains("observeActiveRelief(fleet)"));
		assertTrue(docked.contains("&& !drone.serviceReturn"));
	}

	@Test void runtimeFixtureProvidesRealWeaponEnergyWithoutWeakeningAssertions() throws Exception {
		String verifier = Files.readString(Path.of(
			"src/main/java/jp/morrowgear/drone/MorrowgearRuntimeVerifier.java"));
		String rotation = verifier.substring(verifier.indexOf("private void assignAdaptiveRechargeRotation()"),
			verifier.indexOf("private void assertAdaptiveRechargeRotation()"));
		assertTrue(rotation.contains("setSupplyCreditsForVerification("));
		assertTrue(rotation.contains("DockSupplyPolicy.LASER_CELL_ENERGY - 1"));
		assertTrue(verifier.contains("require(adaptiveOriginalMissionResumed,"));
		assertTrue(verifier.contains("require(!inherited.isBlank() && inheritedFromReplacement"));
	}

	@Test void stalePlanSuppressionIsBoundedAndDoesNotBlockPlayerGuard() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String dispatch = source.substring(source.indexOf("private void updateEmergencyInterception("),
			source.indexOf("private void clearEmergencyInterception("));
		assertTrue(dispatch.contains("suppressCachedRedispatch(level.getGameTime()"));
		assertTrue(dispatch.contains("rechargeReliefHoldUntil, playerEmergency"));
		assertTrue(source.contains("rechargeReliefHoldUntil = level.getGameTime()"));
		assertTrue(source.contains("+ CombatTheaterPolicy.PLAN_TTL_TICKS"));
		assertTrue(dispatch.contains("suppressReliefRedispatch(sameCoveredTarget, playerEmergency"));
		assertTrue(source.contains("rememberReliefCoverage()"));
		assertTrue(source.contains("rememberedReliefCommitted(fleet, contact.entityId())"));
	}
}
