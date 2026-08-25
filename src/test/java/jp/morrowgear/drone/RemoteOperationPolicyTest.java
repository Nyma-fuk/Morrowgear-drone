package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RemoteOperationPolicyTest {
	@Test
	void operationTicketReachesEntityTickingLevel() {
		assertEquals(2, RemoteOperationPolicy.ENTITY_TICKING_TICKET_RADIUS);
	}

	@Test
	void activeRemoteMissionsKeepTheirChunkWhileTheOwnerIsInTheDimension() {
		assertTrue(RemoteOperationPolicy.keepsChunkActive(true, false, false, false,
			false, false, true, DroneMode.WAYPOINT));
		assertTrue(RemoteOperationPolicy.keepsChunkActive(true, false, false, true,
			false, false, false, DroneMode.STANDBY));
		assertTrue(RemoteOperationPolicy.keepsChunkActive(true, false, true, false,
			false, false, false, DroneMode.DOCK));
	}

	@Test
	void offlineAndIdleAircraftDoNotHoldChunksForever() {
		assertFalse(RemoteOperationPolicy.keepsChunkActive(false, false, false, true,
			false, false, false, DroneMode.WAYPOINT));
		assertFalse(RemoteOperationPolicy.keepsChunkActive(true, true, false, false,
			false, false, false, DroneMode.STANDBY));
		assertFalse(RemoteOperationPolicy.keepsChunkActive(true, false, false, false,
			false, false, false, DroneMode.STANDBY));
	}
}
