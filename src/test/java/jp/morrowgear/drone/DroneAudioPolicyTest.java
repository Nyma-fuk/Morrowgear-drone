package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class DroneAudioPolicyTest {
	@Test void cannonRequiresActualRecentShotsNotJustAnAttackState() {
		var gate = new DroneAudioPolicy.CannonGate();
		assertEquals(new DroneAudioPolicy.CannonChange(false, false, false),
			gate.update(CombatState.GUN_RUN, 100, -1));
		assertFalse(gate.update(CombatState.GUN_RUN, 100, 96).playing());
		assertFalse(gate.update(CombatState.GUN_RUN, 100, 101).playing());
		assertTrue(gate.update(CombatState.GUN_RUN, 100, 100).started());
	}

	@Test void cannonTwoTickCadenceHoldsOneBurstAndStopsAfterTheLastRound() {
		var gate = new DroneAudioPolicy.CannonGate();
		for (int tick = 100; tick <= 123; tick++) {
			long shot = Math.min(120, tick - tick % 2);
			var change = gate.update(CombatState.GUN_RUN, tick, shot);
			assertTrue(change.playing());
			assertEquals(tick == 100, change.started());
			assertFalse(change.stopped());
		}
		assertTrue(gate.update(CombatState.GUN_RUN, 124, 120).stopped());
		assertFalse(gate.update(CombatState.GUN_RUN, 125, 120).stopped());
		assertTrue(gate.update(CombatState.GUN_RUN, 126, 126).started());
	}

	@Test void leavingCannonPassStopsImmediatelyEvenIfTheLastShotIsFresh() {
		for (CombatState state : CombatState.values()) {
			if (state == CombatState.GUN_RUN) continue;
			var gate = new DroneAudioPolicy.CannonGate();
			gate.update(CombatState.GUN_RUN, 100, 100);
			assertTrue(gate.update(state, 101, 100).stopped(), state.name());
			assertFalse(gate.update(state, 102, 100).playing(), state.name());
		}
	}

	@Test void missileMotorOnlyRunsBetweenIgnitionAndImpact() {
		for (int mask = 0; mask < 16; mask++) {
			boolean alive = (mask & 1) != 0, silent = (mask & 2) != 0;
			boolean ignited = (mask & 4) != 0, impacted = (mask & 8) != 0;
			assertEquals(mask == 5, DroneAudioPolicy.missileMotorActive(alive, silent, ignited, impacted));
		}
	}

	@Test void dockedDeadSilentAndPowerlessCraftDoNotRunEngines() {
		assertTrue(DroneAudioPolicy.flightActive(true, false, false, false));
		assertFalse(DroneAudioPolicy.flightActive(false, false, false, false));
		assertFalse(DroneAudioPolicy.flightActive(true, true, false, false));
		assertFalse(DroneAudioPolicy.flightActive(true, false, true, false));
		assertFalse(DroneAudioPolicy.flightActive(true, false, false, true));
	}

	@Test void flightCrossfadeAndPitchRemainBoundedAtAllSpeeds() {
		float lastCruise = 0, lastIdle = 1;
		for (int n = 0; n <= 1000; n++) {
			var mix = DroneAudioPolicy.flightMix(n / 100.0, 1);
			assertTrue(mix.idle() <= lastIdle && mix.cruise() >= lastCruise);
			assertTrue(mix.idle() >= 0 && mix.cruise() <= 0.24f);
			assertTrue(mix.pitch() >= 0.94f && mix.pitch() <= 1.04f);
			assertTrue(mix.idle() * mix.idle() + mix.cruise() * mix.cruise() <= 0.058);
			assertTrue(mix.idle() + mix.cruise() <= 0.240001f, "aligned loops must not boost the crossfade midpoint");
			lastIdle = mix.idle();
			lastCruise = mix.cruise();
		}
		assertEquals(DroneAudioPolicy.flightMix(0, 1), DroneAudioPolicy.flightMix(Double.NaN, 1));
		assertEquals(DroneAudioPolicy.flightMix(0, 1), DroneAudioPolicy.flightMix(Double.POSITIVE_INFINITY, 1));
	}

	@Test void formationGainKeepsLoopEnergyWithinTheSingleEmitterBudget() {
		for (int count : new int[] {1, 2, 4, 8, 32, 64}) {
			var mix = DroneAudioPolicy.flightMix(0.8, count);
			assertTrue(count * (mix.idle() * mix.idle() + mix.cruise() * mix.cruise()) <= 0.058);
		}
		assertEquals(1.0f, DroneAudioPolicy.fleetGain(0));
		assertEquals(1.0f, DroneAudioPolicy.fleetGain(-5));
	}

	@Test void gainAndPitchSlewCannotOvershoot() {
		assertEquals(0.225f, DroneAudioPolicy.approach(0.2f, 1.0f, 0.025f), 0.00001f);
		assertEquals(0.19f, DroneAudioPolicy.approach(0.2f, 0.19f, 0.025f));
		assertEquals(0.0f, DroneAudioPolicy.approach(0.01f, 0.0f, 0.025f));
	}

	@Test void lateTrackingAndFutureStateTicksDoNotReplayDischarge() {
		assertTrue(DroneAudioPolicy.dischargeStarted(null, CombatState.LASER_FIRE, 100, 100));
		assertTrue(DroneAudioPolicy.dischargeStarted(CombatState.LASER_CHARGE, CombatState.LASER_FIRE, 103, 100));
		assertFalse(DroneAudioPolicy.dischargeStarted(null, CombatState.LASER_FIRE, 104, 100));
		assertFalse(DroneAudioPolicy.dischargeStarted(null, CombatState.LASER_FIRE, 99, 100));
		assertFalse(DroneAudioPolicy.dischargeStarted(CombatState.LASER_FIRE, CombatState.LASER_FIRE, 100, 100));
		assertFalse(DroneAudioPolicy.freshEvent(0, -1));
	}

	@Test void rechargingAfterFiringStillProducesCooling() {
		assertTrue(DroneAudioPolicy.coolingStarted(CombatState.LASER_FIRE, CombatState.LASER_CHARGE));
		assertTrue(DroneAudioPolicy.chargeStarted(CombatState.LASER_FIRE, CombatState.LASER_CHARGE, 100, 100));
		assertTrue(DroneAudioPolicy.coolingStarted(CombatState.LASER_FIRE, CombatState.REJOIN));
		assertFalse(DroneAudioPolicy.coolingStarted(null, CombatState.LASER_CHARGE));
		assertFalse(DroneAudioPolicy.coolingStarted(CombatState.LASER_FIRE, CombatState.LASER_FIRE));
		assertFalse(DroneAudioPolicy.chargeStarted(CombatState.LASER_CHARGE, CombatState.LASER_CHARGE, 100, 100));
	}
}
