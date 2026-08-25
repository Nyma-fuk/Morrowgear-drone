package jp.morrowgear.drone;

import java.util.List;
import net.minecraft.core.Direction;

final class DockApproachPlan {
	enum Phase { TOP_HOLD, SIDE_OUTER, SIDE_GATE, FINAL }

	private DockApproachPlan() {
	}

	static Phase phase(boolean topClear, int stage) {
		if (stage >= 2) return Phase.FINAL;
		if (topClear) return Phase.TOP_HOLD;
		return stage == 0 ? Phase.SIDE_OUTER : Phase.SIDE_GATE;
	}

	static int nextStage(Phase phase) {
		return phase == Phase.SIDE_OUTER ? 1 : 2;
	}

	static List<Direction> sideDirections(Direction facing) {
		Direction forward = facing == null ? Direction.NORTH : facing;
		return List.of(forward, forward.getOpposite(), forward.getClockWise(), forward.getCounterClockWise());
	}
}
