package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class EngineerAssignmentPolicyTest {
	@Test
	void assignsOneEngineerPerDamagedTarget() {
		assertEquals(0, EngineerAssignmentPolicy.targetIndex(0, 3));
		assertEquals(1, EngineerAssignmentPolicy.targetIndex(1, 3));
		assertEquals(2, EngineerAssignmentPolicy.targetIndex(2, 3));
	}

	@Test
	void surplusEngineersCycleAcrossDamagedTargets() {
		assertEquals(0, EngineerAssignmentPolicy.targetIndex(3, 3));
		assertEquals(1, EngineerAssignmentPolicy.targetIndex(4, 3));
		assertEquals(0, EngineerAssignmentPolicy.targetIndex(5, 1));
	}

	@Test
	void rejectsInvalidAssignments() {
		assertEquals(-1, EngineerAssignmentPolicy.targetIndex(0, 0));
		assertEquals(-1, EngineerAssignmentPolicy.targetIndex(-1, 2));
	}
}
