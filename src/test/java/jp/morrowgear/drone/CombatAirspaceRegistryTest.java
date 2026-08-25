package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CombatAirspaceRegistryTest {
	@AfterEach
	void clearRegistry() {
		CombatAirspaceRegistry.clear();
	}

	@Test
	void existingLaserLaneDoesNotMoveWhenCrossingGroupJoinsOrLeaves() {
		UUID owner = UUID.randomUUID();
		CombatPolicy.AirspaceSlot laser = CombatAirspaceRegistry.reserve(
			"overworld", owner, 42, "LASER-WING", 0);
		CombatAirspaceRegistry.reserve("overworld", owner, 42, "GUN-CROSSING", 1);
		CombatPolicy.AirspaceSlot whileCrossing = CombatAirspaceRegistry.reserve(
			"overworld", owner, 42, "LASER-WING", 50);
		CombatPolicy.AirspaceSlot afterCrossing = CombatAirspaceRegistry.reserve(
			"overworld", owner, 42, "LASER-WING", 103);

		assertEquals(laser.index(), whileCrossing.index());
		assertEquals(laser.index(), afterCrossing.index());
		assertEquals(laser.heightOffset(), whileCrossing.heightOffset(), 0.0001);
		assertEquals(laser.radiusOffset(), afterCrossing.radiusOffset(), 0.0001);
	}
}
