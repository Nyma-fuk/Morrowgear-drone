package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CarrierMissileMagazinePolicyTest {
    @Test void magazineIsTenByTwoAndReloadsOnlyOnGround() {
        assertEquals(20, CarrierMissileMagazinePolicy.CAPACITY);
        assertEquals(6, CarrierMissileMagazinePolicy.insertable(14, 20, true, false));
        assertEquals(0, CarrierMissileMagazinePolicy.insertable(14, 20, false, false));
        assertEquals(0, CarrierMissileMagazinePolicy.insertable(14, 20, true, true));
    }
    @Test void undergroundLockHasNoHeightLimitButStillRequiresARealFreshScanContact() {
        assertTrue(CarrierMissileMagazinePolicy.undergroundContact(true, true, true, 1, Integer.MIN_VALUE));
        assertTrue(CarrierMissileMagazinePolicy.undergroundContact(true, true, true, 1, Integer.MAX_VALUE));
        assertFalse(CarrierMissileMagazinePolicy.undergroundContact(true, false, true, 1, -4096));
    }
}
