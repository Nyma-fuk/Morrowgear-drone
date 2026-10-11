package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StrikeBeaconMagazinePolicyTest {
	@Test void magazineFiresExactlyThreeRounds() {
		int rounds = StrikeBeaconMagazinePolicy.CAPACITY;
		rounds = StrikeBeaconMagazinePolicy.afterShot(rounds);
		rounds = StrikeBeaconMagazinePolicy.afterShot(rounds);
		rounds = StrikeBeaconMagazinePolicy.afterShot(rounds);
		assertEquals(0, rounds);
		assertEquals(0, StrikeBeaconMagazinePolicy.afterShot(rounds));
	}

	@Test void reloadIsPartialAndBounded() {
		assertEquals(3, StrikeBeaconMagazinePolicy.reloadDemand(0));
		assertEquals(1, StrikeBeaconMagazinePolicy.afterReload(0, 1));
		assertEquals(3, StrikeBeaconMagazinePolicy.afterReload(1, 99));
		assertEquals(0, StrikeBeaconMagazinePolicy.afterReload(-4, -2));
	}
}
