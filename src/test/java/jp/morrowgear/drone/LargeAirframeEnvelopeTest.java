package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class LargeAirframeEnvelopeTest {
	@ParameterizedTest @ValueSource(ints = {1, 2, 3, 6, 8, 12, 16, 32, 64, 100, 128})
	void fullFleetTravelAndOrbitDoNotOverlap(int count) {
		var travel = new ArrayList<Vec3>();
		var orbit = new ArrayList<Vec3>();
		for (int i = 0; i < count; i++) {
			int wing = i / 8, slot = i % 8, size = Math.min(8, count - wing * 8);
			travel.add(SwarmFormation.wingTravelOffset(wing, (count + 7) / 8, new Vec3(0, 0, 1), false)
				.add(SwarmFormation.movingOffset(slot, size, new Vec3(0, 0, 1))));
			orbit.add(SwarmFormation.orbitPosition(SwarmFormation.wingOrbitCenter(Vec3.ZERO, wing),
				slot, size, 173, .038, 0));
		}
		for (int a = 0; a < count; a++) for (int b = a + 1; b < count; b++) {
			assertTrue(travel.get(a).distanceTo(travel.get(b)) >= 4.9, "travel " + a + "/" + b);
			assertTrue(orbit.get(a).distanceTo(orbit.get(b)) >= 4.9, "orbit " + a + "/" + b);
		}
	}

	@ParameterizedTest @ValueSource(ints = {2, 8, 16, 64, 100})
	void circularSpacingUsesChordRatherThanArcLength(int count) {
		double radius = AirframeEnvelope.orbitRadius(count);
		assertTrue(2 * radius * Math.sin(Math.PI / count) >= 5 - 1e-8);
		assertTrue(AirframeEnvelope.angularSpeed(.1, radius) * radius <= .360001);
	}

	@ParameterizedTest @ValueSource(ints = {0, 1, 7, 15})
	void enlargedCombatLayersRemainReachableAndTerrainClear(int group) {
		var lane = new CombatPolicy.AirspaceSlot(group, 16);
		for (int slot = 0; slot < 8; slot++) {
			Vec3 laser = CombatPolicy.laserOrbit(Vec3.ZERO, slot, 8, 310, 1000, lane);
			assertTrue(laser.y >= 10 && laser.length() < 48);
			assertFalse(CombatPolicy.laserFallbackReleaseReady(60, laser.length(), 4, true, false));
			assertTrue(CombatPolicy.laserFallbackReleaseReady(60, laser.length(), 1.65, true, false));
			Vec3 missile = CombatPolicy.missileApproach(Vec3.ZERO, new Vec3(0, 12, -50), slot, 8, lane);
			assertTrue(MicroMissilePolicy.launchEnvelope(missile.horizontalDistance(), missile.y));
			for (int tick = 0; tick < 1800; tick += 9)
				assertTrue(CombatPolicy.casFormation(Vec3.ZERO, new Vec3(0, 0, 1), slot, 8, tick, lane).y >= 8);
		}
	}

	@Test void stagedMissileEjectsBeforeIgnitionAndAcceleratesBeforeTerminalDescent() {
		var launch = new MicroMissilePolicy.LaunchProfile(0, 0);
		Vec3 position = new Vec3(0, 16, 0), aim = new Vec3(0, .8, 40);
		Vec3 velocity = MicroMissilePolicy.ejectionVelocity(launch, Vec3.ZERO);
		boolean terminal = false, passed = false;
		double ejectionSpeed = 0, boostSpeed = 0, maximumSpeed = 0;
		for (int tick = 1; tick <= 100; tick++) {
			Vec3 previous = velocity;
			var step = MicroMissilePolicy.flightStep(position, velocity, aim, tick, terminal, passed, launch);
			if (step.terminal() && !terminal) {
				assertTrue(previous.y < 0, "terminal guidance begins on the descending leg");
				assertTrue(previous.normalize().dot(aim.subtract(position).normalize()) >= MicroMissilePolicy.TERMINAL_ALIGNMENT_COS);
			}
			velocity = step.velocity(); terminal = step.terminal(); passed = step.passedTarget();
			if (tick <= MicroMissilePolicy.EJECTION_TICKS) {
				assertFalse(MicroMissilePolicy.motorIgnited(tick));
				assertTrue(velocity.y > 0 && velocity.y < previous.y, "unpowered ejection rises without motor acceleration");
				assertTrue(velocity.horizontalDistance() > .1, "the launch fan is not a single vertical line");
				assertEquals(new Vec3(0, 1, 0), step.facing());
			} else if (tick < MicroMissilePolicy.IGNITION_TICK) {
				assertFalse(MicroMissilePolicy.motorIgnited(tick));
				assertTrue(velocity.length() < previous.length(), "cold attitude change cannot add thrust");
				assertTrue(step.facing().horizontalDistance() > .1, "capsule rotates before ignition");
				assertFalse(terminal);
			} else if (tick <= MicroMissilePolicy.BOOST_TICKS) {
				assertTrue(MicroMissilePolicy.motorIgnited(tick));
				assertTrue(velocity.length() >= previous.length(), "powered boost accelerates along the turned attitude");
				assertTrue(velocity.dot(step.facing()) > 0);
				assertFalse(terminal);
			}
			if (tick == MicroMissilePolicy.EJECTION_TICKS) ejectionSpeed = velocity.length();
			if (tick == MicroMissilePolicy.IGNITION_TICK - 1)
				assertTrue(step.facing().dot(velocity.normalize()) < .5, "cold attitude is independent of the drift vector");
			if (tick == MicroMissilePolicy.IGNITION_TICK) {
				Vec3 thrust = velocity.subtract(previous.scale(.75));
				assertEquals(1, thrust.normalize().dot(step.facing()), 1e-8, "ignition pushes along the pre-turned nose");
			}
			if (tick == MicroMissilePolicy.BOOST_TICKS) boostSpeed = velocity.length();
			maximumSpeed = Math.max(maximumSpeed, velocity.length());
			assertTrue(MicroMissilePolicy.finite(velocity) && MicroMissilePolicy.finite(step.facing()));
			assertTrue(velocity.length() <= MicroMissilePolicy.TERMINAL_SPEED + 1e-8);
			position = position.add(velocity);
			if (position.y <= 0) break;
		}
		assertTrue(boostSpeed > ejectionSpeed * 3);
		assertTrue(maximumSpeed > boostSpeed && terminal && position.y <= 0);
		assertFalse(MicroMissilePolicy.launchEnvelope(23.9, 16));
		assertFalse(MicroMissilePolicy.launchEnvelope(32, 7.9));
		assertFalse(MicroMissilePolicy.launchEnvelope(Double.NaN, 16));
	}

	@ParameterizedTest @ValueSource(ints = {0, 1, 2})
	void capacityUpgradesAreBoundedAndPreserveAbsoluteStoredPower(int tier) {
		assertEquals(45 + tier * 15, PayloadCapacity.missiles(tier));
		assertEquals(240 + tier * 120, PayloadCapacity.gun(tier));
		assertEquals(1000 + tier * 500, PayloadCapacity.energy(tier));
		assertEquals(600, PayloadCapacity.power(600, tier));
		assertEquals(0, PayloadCapacity.power(-100, tier));
		assertEquals(PayloadCapacity.energy(tier), PayloadCapacity.power(Integer.MAX_VALUE, tier));
		assertEquals(2, PayloadCapacity.tier(99));
		assertEquals(0, PayloadCapacity.tier(-1));
	}
}
