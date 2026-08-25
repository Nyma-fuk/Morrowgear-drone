package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class SolarOrbitGuidanceTest {
	@Test
	void activeSlotUsesStableFiveBlockChargingOrbit() {
		SolarOrbitGuidance.Profile profile = SolarOrbitGuidance.profile(1, 0);
		assertEquals(5.4, profile.radius(), 0.001);
		assertEquals(1.65, profile.height(), 0.001);
		assertEquals(0.035, profile.angularSpeed(), 0.0001);
	}

	@Test
	void insertionStartsOnAircraftRadialWithoutCrossingTheStation() {
		Vec3 station = new Vec3(20, 80, 20);
		Vec3 aircraft = new Vec3(35, 83, 20);
		double phase = SolarOrbitGuidance.initialPhase(station, aircraft, 0);
		Vec3 target = SolarOrbitGuidance.insertionTarget(station, phase,
			SolarOrbitGuidance.profile(0, 0));
		assertTrue(target.x > station.x);
		assertEquals(station.z, target.z, 0.001);
		assertFalse(SolarOrbitGuidance.captured(aircraft, target));
	}

	@Test
	void orbitSampleProvidesContinuousTangentialMotion() {
		Vec3 station = new Vec3(0, 64, 0);
		SolarOrbitGuidance.Profile profile = SolarOrbitGuidance.profile(0, 0);
		SolarOrbitGuidance.Sample first = SolarOrbitGuidance.orbitSample(station, 0.0, profile);
		double nextPhase = SolarOrbitGuidance.advancePhase(0.0, 1, profile);
		SolarOrbitGuidance.Sample second = SolarOrbitGuidance.orbitSample(station, nextPhase, profile);
		assertEquals(first.target().add(first.velocity()).x, second.target().x, 0.0001);
		assertEquals(first.target().add(first.velocity()).z, second.target().z, 0.0001);
		assertTrue(first.velocity().length() > 0.18);
		assertTrue(first.velocity().length() < 0.20);
	}

	@Test
	void delayedTicksAreClampedInsteadOfJumpingAroundTheOrbit() {
		SolarOrbitGuidance.Profile profile = SolarOrbitGuidance.profile(0, 0);
		assertEquals(profile.angularSpeed() * 5,
			SolarOrbitGuidance.advancePhase(0.0, 200, profile), 0.0001);
	}
}
