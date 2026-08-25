package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

final class FieldCompletionPolicyTest {
	@Test
	void waitsForEveryAssignedUnitBeforeLeavingTheWorksite() {
		assertEquals(FieldCompletionPolicy.Action.HOLD,
			FieldCompletionPolicy.action(false, true));
	}

	@Test
	void returnsToDockWhenTheMissionIsComplete() {
		assertEquals(FieldCompletionPolicy.Action.DOCK,
			FieldCompletionPolicy.action(true, true));
	}

	@Test
	void returnsToOwnerWhenNoDockIsAssigned() {
		assertEquals(FieldCompletionPolicy.Action.RETURN_TO_OWNER,
			FieldCompletionPolicy.action(true, false));
	}
}
