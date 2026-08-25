package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

final class FieldSupportPolicyTest {
	@Test void fieldAircraftRelayThenSupportThenComplete() {
		assertEquals(FieldOperationState.FIELD_DATA_RELAY, FieldSupportPolicy.state(false, false, false));
		assertEquals(FieldOperationState.FIELD_MISSION_SUPPORT, FieldSupportPolicy.state(true, false, true));
		assertEquals(FieldOperationState.COMPLETE, FieldSupportPolicy.state(true, true, true));
	}

	@Test void emergencyTransferPreservesTheDonorReserve() {
		assertTrue(FieldSupportPolicy.mayTransfer(70, 12));
		assertFalse(FieldSupportPolicy.mayTransfer(40, 12));
		assertFalse(FieldSupportPolicy.mayTransfer(70, 20));
		assertFalse(FieldSupportPolicy.mayTransfer(70, 0));
	}
}
