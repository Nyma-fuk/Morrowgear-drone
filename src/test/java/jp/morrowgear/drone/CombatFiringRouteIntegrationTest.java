package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CombatFiringRouteIntegrationTest {
	private static String entity() throws Exception {
		return Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
	}

	@Test void formationIsPreferredButIndividualRouteOwnsPersistentOcclusion() throws Exception {
		String source = entity();
		String route = source.substring(source.indexOf("private Vec3 combatFiringTarget("),
			source.indexOf("private Vec3 combatFlightTarget("));
		assertTrue(route.contains("formationPointViable"));
		assertTrue(route.contains("WeaponFiringRoutePolicy.candidates"));
		assertTrue(route.contains("combatFiringRoutePoint"));
		assertTrue(route.contains("NO_SOLUTION_TIMEOUT_TICKS"));
		assertTrue(route.contains("setCombatState(CombatState.WAVE_ASSESS)"));
	}

	@Test void laserAuthorizationDependsOnOwnSafeWindowNotCircleCompletion() throws Exception {
		String source = entity();
		String window = source.substring(source.indexOf("private boolean laserFiringWindowReady("),
			source.indexOf("private int laserSynchronizedCount("));
		assertTrue(window.contains("combatCharge() < 1000"));
		assertTrue(window.contains("LASER_MAX_RANGE"));
		assertTrue(window.contains("lineClear"));
		assertFalse(window.contains("laserFormationSpacingReady"));
	}

	@Test void missileKeepsAnEligibleLockWhileItSearchesForLaunchGeometry() throws Exception {
		String source = entity();
		String attack = source.substring(source.indexOf("private void tickMissileAttack("),
			source.indexOf("private boolean missilePassCommitted("));
		assertTrue(attack.contains("missileCandidates(level, owner, primary, false)"));
		assertTrue(attack.contains("missileTargetUsable(level, owner, living, false)"));
		assertTrue(attack.contains("if (!missileTargetUsable(level, owner, target, true)) return;"));
	}

	@Test void runtimeScopeCoversCaveShaftSealedAndMultiWingCases() throws Exception {
		String verifier = Files.readString(Path.of(
			"src/main/java/jp/morrowgear/drone/MorrowgearRuntimeVerifier.java"));
		assertTrue(verifier.contains("cave laser acquires an individual firing aperture"));
		assertTrue(verifier.contains("cave autocannon uses a forward alternate ingress"));
		assertTrue(verifier.contains("shaft laser engages vertically without clipping"));
		assertTrue(verifier.contains("sealed cave terminates missile search and replans"));
		assertTrue(verifier.contains("multi-Wing wall routes remain independent"));
	}
}
