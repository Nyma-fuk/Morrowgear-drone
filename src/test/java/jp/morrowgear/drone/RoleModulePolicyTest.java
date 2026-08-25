package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class RoleModulePolicyTest {
	@Test
	void physicalModuleChangesRequireDockingAndTheRequestedItem() {
		assertEquals(RoleModulePolicy.Result.NOT_DOCKED,
			RoleModulePolicy.evaluate(false, false, true, DroneRole.FIELD, DroneRole.SCOUT));
		assertEquals(RoleModulePolicy.Result.MODULE_MISSING,
			RoleModulePolicy.evaluate(true, false, false, DroneRole.FIELD, DroneRole.CARGO));
		assertEquals(RoleModulePolicy.Result.ALLOWED,
			RoleModulePolicy.evaluate(true, false, true, DroneRole.FIELD, DroneRole.ENGINEER));
	}

	@Test
	void fieldSelectionEjectsWithoutAReplacementAndCreativeBypassesInventory() {
		assertEquals(RoleModulePolicy.Result.ALLOWED,
			RoleModulePolicy.evaluate(true, false, false, DroneRole.SECURITY, DroneRole.FIELD));
		assertEquals(RoleModulePolicy.Result.ALLOWED,
			RoleModulePolicy.evaluate(true, true, false, DroneRole.FIELD, DroneRole.SCOUT));
		assertEquals(RoleModulePolicy.Result.UNCHANGED,
			RoleModulePolicy.evaluate(true, false, false, DroneRole.FIELD, DroneRole.FIELD));
	}

	@Test
	void selectingTheInstalledRoleIsIdempotentEvenWhenUndocked() {
		assertEquals(RoleModulePolicy.Result.UNCHANGED,
			RoleModulePolicy.evaluate(false, false, false, DroneRole.SECURITY, DroneRole.SECURITY));
	}
}
