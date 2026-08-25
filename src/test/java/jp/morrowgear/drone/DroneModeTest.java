package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DroneModeTest {
	@Test
	void mapsActionsToStableModes() {
		assertEquals(DroneMode.FOLLOW, DroneMode.fromAction("follow"));
		assertEquals(DroneMode.RETURN, DroneMode.fromAction("return"));
		assertEquals(DroneMode.ORBIT, DroneMode.fromAction("orbit"));
		assertEquals(DroneMode.DOCK, DroneMode.fromAction("dock"));
		assertEquals(DroneMode.STANDBY, DroneMode.fromAction("unknown"));
	}

	@Test
	void unknownIdsFallBackToStandby() {
		assertEquals(DroneMode.STANDBY, DroneMode.byId(999));
	}

	@Test
	void persistedDockModeKeepsStableId() {
		assertEquals(4, DroneMode.DOCK.id());
		assertEquals(DroneMode.DOCK, DroneMode.byId(4));
	}
}
