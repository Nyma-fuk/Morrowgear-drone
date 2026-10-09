package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class WeaponFiringRoutePolicyTest {
	private static final Vec3 TARGET = new Vec3(.5, 32, .5);
	private static final Vec3 AIRCRAFT = new Vec3(-24, 36, 0);
	private static final CombatPolicy.AirspaceSlot AIRSPACE = new CombatPolicy.AirspaceSlot(0, 1);

	@Test void laserProvidesUpperOrbitAndSideOnCavernSolutions() {
		List<WeaponFiringRoutePolicy.Candidate> candidates = WeaponFiringRoutePolicy.candidates(
			CombatWeapon.LASER, TARGET, AIRCRAFT, TARGET.add(10, 10, 0), Vec3.ZERO, 0, 4, AIRSPACE);
		assertTrue(candidates.stream().anyMatch(candidate -> candidate.point().y >= TARGET.y + 10));
		assertTrue(candidates.stream().anyMatch(candidate -> Math.abs(candidate.point().y - TARGET.y) < .01));
		assertTrue(candidates.stream().anyMatch(candidate -> candidate.point().y < TARGET.y));
	}

	@Test void missileCandidatesRemainInsideLaunchEnvelope() {
		List<WeaponFiringRoutePolicy.Candidate> candidates = WeaponFiringRoutePolicy.candidates(
			CombatWeapon.MISSILE, TARGET, AIRCRAFT, null, Vec3.ZERO, 1, 4, AIRSPACE);
		assertFalse(candidates.isEmpty());
		assertTrue(candidates.stream().allMatch(candidate ->
			WeaponFiringRoutePolicy.missileEnvelope(candidate.point(), TARGET)));
	}

	@Test void gunDestinationsCreateMultipleForwardAttackAxes() {
		List<WeaponFiringRoutePolicy.Candidate> candidates = WeaponFiringRoutePolicy.candidates(
			CombatWeapon.AUTOCANNON, TARGET, AIRCRAFT, null, new Vec3(1, 0, 0), 0, 3, AIRSPACE);
		assertTrue(candidates.size() >= 24);
		long distinctQuadrants = candidates.stream().map(candidate ->
			(Math.signum(candidate.point().x - TARGET.x) + 1) * 3
				+ Math.signum(candidate.point().z - TARGET.z)).distinct().count();
		assertTrue(distinctQuadrants >= 4);
		assertTrue(candidates.stream().allMatch(candidate -> candidate.attackAxis().lengthSqr() > .9));
	}

	@Test void retainedValidRouteWinsAgainstEquivalentReplacement() {
		var candidate = new WeaponFiringRoutePolicy.Candidate(new Vec3(10, 4, 0), new Vec3(1, 0, 0), false);
		double fresh = WeaponFiringRoutePolicy.score(candidate, Vec3.ZERO, null, true, false);
		double retained = WeaponFiringRoutePolicy.score(candidate, Vec3.ZERO, null, true, true);
		assertTrue(retained < fresh);
	}
}
