package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

final class BatteryTierTest {
	@Test void tiersChangeCapacityWithoutChangingAFlightDynamicsParameter() {
		assertEquals(1000, BatteryTier.STANDARD.capacity());
		assertEquals(1800, BatteryTier.REINFORCED.capacity());
		assertEquals(3000, BatteryTier.HIGH_DENSITY.capacity());
		assertEquals(50, BatteryTier.REINFORCED.storedForPercent(50) * 100
			/ BatteryTier.REINFORCED.capacity());
	}

	@Test void unknownSavedTiersMigrateToStandard() {
		assertEquals(BatteryTier.STANDARD, BatteryTier.byId("legacy"));
	}
}
