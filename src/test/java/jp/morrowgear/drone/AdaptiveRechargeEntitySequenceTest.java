package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AdaptiveRechargeEntitySequenceTest {
	@Test void landedAircraftWithPreservedPatrolCannotDriftOutOfItsServiceDock() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String tick = source.substring(source.indexOf("protected void customServerAiStep("),
			source.indexOf("private Vec3 smoothFlightMotion("));
		int combat = tick.indexOf("updateCombat(level, owner, formation)");
		int landedHold = tick.indexOf("if (isDocked()) {", combat);
		int navigation = tick.indexOf("targetPosition(level, owner", landedHold);

		assertTrue(combat >= 0 && landedHold > combat && navigation > landedHold);
		assertTrue(tick.substring(landedHold, navigation).contains("setDeltaMovement(Vec3.ZERO)"));
		assertTrue(tick.substring(landedHold, navigation).contains("return;"));
	}

	@Test void observedTenTickDriftExplains919CreditAndLandedHoldCompletesInheritance() {
		int legacyWeapon = 0;
		int legacyCredit = DockSupplyPolicy.LASER_CELL_ENERGY - 1;
		double legacyDistance = 0.0;
		for (int tick = 0; tick < 1400; tick++) {
			if (legacyDistance * legacyDistance <= 4.0) {
				DockSupplyPolicy.PackTransfer transfer = DockSupplyPolicy.takePack(legacyCredit,
					Math.min(DockServicePolicy.WEAPON_CHARGE_PER_TICK, 1000 - legacyWeapon),
					DockSupplyPolicy.LASER_CELL_ENERGY, false);
				legacyWeapon += transfer.supplied();
				legacyCredit = transfer.credit();
			}
			legacyDistance += 0.22;
		}
		assertEquals(80, legacyWeapon);
		assertEquals(919, legacyCredit);

		int heldWeapon = 0;
		int heldCredit = DockSupplyPolicy.LASER_CELL_ENERGY - 1;
		int serviceTicks = 0;
		while (heldWeapon < 900) {
			DockSupplyPolicy.PackTransfer transfer = DockSupplyPolicy.takePack(heldCredit,
				Math.min(DockServicePolicy.WEAPON_CHARGE_PER_TICK, 1000 - heldWeapon),
				DockSupplyPolicy.LASER_CELL_ENERGY, false);
			heldWeapon += transfer.supplied();
			heldCredit = transfer.credit();
			serviceTicks++;
		}

		assertEquals(113, serviceTicks);
		assertEquals(904, heldWeapon);
		assertEquals(GuardDispatchPolicy.RechargeCompletion.INHERIT_RELIEF_MISSION,
			GuardDispatchPolicy.rechargeCompletion(true, true, true, true, true, true));
	}
}
