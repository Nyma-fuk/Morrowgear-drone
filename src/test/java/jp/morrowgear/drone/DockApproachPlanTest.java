package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Direction;

final class DockApproachPlanTest {
	@Test
	void topEntryMovesFromHoldingPointToFinalApproach() {
		DockApproachPlan.Phase phase = DockApproachPlan.phase(true, 0);
		assertEquals(DockApproachPlan.Phase.TOP_HOLD, phase);
		assertEquals(2, DockApproachPlan.nextStage(phase));
		assertEquals(DockApproachPlan.Phase.FINAL, DockApproachPlan.phase(true, 2));
	}

	@Test
	void sideEntryUsesOuterAndGateWaypointsBeforeLanding() {
		DockApproachPlan.Phase outer = DockApproachPlan.phase(false, 0);
		assertEquals(DockApproachPlan.Phase.SIDE_OUTER, outer);
		assertEquals(1, DockApproachPlan.nextStage(outer));
		DockApproachPlan.Phase gate = DockApproachPlan.phase(false, 1);
		assertEquals(DockApproachPlan.Phase.SIDE_GATE, gate);
		assertEquals(2, DockApproachPlan.nextStage(gate));
		assertEquals(DockApproachPlan.Phase.FINAL, DockApproachPlan.phase(false, 2));
	}

	@Test
	void sideEntryEvaluatesEveryHorizontalDirection() {
		List<Direction> directions = DockApproachPlan.sideDirections(Direction.NORTH);
		assertEquals(List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST), directions);
		assertEquals(4, new HashSet<>(directions).size());
	}

	@Test
	void missingDockFacingUsesStableNorthFallback() {
		assertEquals(DockApproachPlan.sideDirections(Direction.NORTH), DockApproachPlan.sideDirections(null));
	}
}
