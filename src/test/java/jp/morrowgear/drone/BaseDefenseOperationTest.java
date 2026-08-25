package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BaseDefenseOperationTest {
	@Test
	void waveOnlyClosesAfterTheAreaIsClearAndCommitWindowElapsed() {
		assertFalse(BaseDefenseOperation.shouldEnterRecovery(BaseDefenseOperation.Phase.CONTACT, 1, 200));
		assertFalse(BaseDefenseOperation.shouldEnterRecovery(BaseDefenseOperation.Phase.CONTACT, 0, 99));
		assertTrue(BaseDefenseOperation.shouldEnterRecovery(BaseDefenseOperation.Phase.CONTACT, 0, 100));
		assertFalse(BaseDefenseOperation.shouldEnterRecovery(BaseDefenseOperation.Phase.RECOVERY, 0, 200));
	}

	@Test
	void recoveryWindowsAdvanceThroughAllThreeEscalationLevels() {
		assertEquals(BaseDefenseOperation.Phase.ASSAULT, BaseDefenseOperation.nextAfterRecovery(0));
		assertEquals(BaseDefenseOperation.Phase.SIEGE, BaseDefenseOperation.nextAfterRecovery(1));
		assertEquals(BaseDefenseOperation.Phase.COMPLETE, BaseDefenseOperation.nextAfterRecovery(2));
	}
}
