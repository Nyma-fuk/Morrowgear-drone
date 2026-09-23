package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import jp.morrowgear.drone.carrier.CarrierPolicy;
import org.junit.jupiter.api.Test;

class CarrierAudioPolicyTest {
    @Test void audibleRangeRejectsInvalidRemovedAndSilentEmitters() {
        assertTrue(CarrierAudioPolicy.audible(true, false, 0));
        assertTrue(CarrierAudioPolicy.audible(true, false, CarrierAudioPolicy.RANGE * CarrierAudioPolicy.RANGE));
        assertFalse(CarrierAudioPolicy.audible(false, false, 0));
        assertFalse(CarrierAudioPolicy.audible(true, true, 0));
        assertFalse(CarrierAudioPolicy.audible(true, false, Double.NaN));
        assertFalse(CarrierAudioPolicy.audible(true, false, Math.pow(CarrierAudioPolicy.RANGE + .01, 2)));
        assertTrue(CarrierAudioPolicy.MAX_EMITTERS > 0 && CarrierAudioPolicy.MAX_EMITTERS <= 4);
    }

    @Test void flightMixCrossfadesWithoutClippingOrPitchDiscontinuity() {
        var stopped = CarrierAudioPolicy.flightMix(0);
        var cruise = CarrierAudioPolicy.flightMix(.42);
        var faster = CarrierAudioPolicy.flightMix(10);
        assertTrue(stopped.idle() > cruise.idle());
        assertTrue(stopped.cruise() < cruise.cruise());
        assertTrue(cruise.pitch() > stopped.pitch());
        assertEquals(cruise, faster);
        for (double speed = 0; speed <= .6; speed += .01) {
            var mix = CarrierAudioPolicy.flightMix(speed);
            assertTrue(mix.idle() >= 0 && mix.idle() <= .7);
            assertTrue(mix.cruise() >= 0 && mix.cruise() <= .7);
            assertTrue(mix.pitch() >= .75 && mix.pitch() <= 1.05);
        }
    }

    @Test void beamLifecycleHasSingleEntryEdgesAndRequiresAVisibleFireBeam() {
        assertTrue(CarrierAudioPolicy.chargeStarted(CarrierPolicy.WorkPhase.IDLE, CarrierPolicy.WorkPhase.CHARGE));
        assertFalse(CarrierAudioPolicy.chargeStarted(CarrierPolicy.WorkPhase.CHARGE, CarrierPolicy.WorkPhase.CHARGE));
        assertTrue(CarrierAudioPolicy.fireActive(CarrierPolicy.WorkPhase.FIRE, true));
        assertFalse(CarrierAudioPolicy.fireActive(CarrierPolicy.WorkPhase.FIRE, false));
        assertFalse(CarrierAudioPolicy.fireActive(CarrierPolicy.WorkPhase.CHARGE, true));
        assertTrue(CarrierAudioPolicy.cooldownStarted(CarrierPolicy.WorkPhase.FIRE, CarrierPolicy.WorkPhase.COOLDOWN));
        assertFalse(CarrierAudioPolicy.cooldownStarted(CarrierPolicy.WorkPhase.COOLDOWN, CarrierPolicy.WorkPhase.COOLDOWN));
        assertFalse(CarrierAudioPolicy.hitStarted(-1, 0));
        assertFalse(CarrierAudioPolicy.hitStarted(4, 4));
        assertTrue(CarrierAudioPolicy.hitStarted(4, 5));
        assertTrue(CarrierAudioPolicy.hitStarted(Integer.MAX_VALUE, 1));
    }
}
