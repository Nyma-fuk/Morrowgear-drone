package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class DroneRoleTest {
	@Test
	void stableRoleIdsRoundTripAndUnknownRolesMigrateToField() {
		for (DroneRole role : DroneRole.values()) assertEquals(role, DroneRole.byId(role.id()));
		assertEquals(DroneRole.FIELD, DroneRole.byId("legacy_unknown"));
		assertEquals(DroneRole.SCOUT, DroneRole.byId("SCOUT"));
	}
}
