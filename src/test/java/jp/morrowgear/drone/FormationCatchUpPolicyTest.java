package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FormationCatchUpPolicyTest {
	@Test
	void catchUpUsesSeparateEntryAndExitThresholds() {
		assertFalse(FormationCatchUpPolicy.update(false, true, 6.9));
		assertTrue(FormationCatchUpPolicy.update(false, true, 7.0));
		assertTrue(FormationCatchUpPolicy.update(true, true, 5.0));
		assertFalse(FormationCatchUpPolicy.update(true, true, 3.5));
	}

	@Test
	void leadersAndNonMovingUnitsCannotEnterCatchUp() {
		assertFalse(FormationCatchUpPolicy.update(true, false, 20.0));
	}
}
