package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

final class DockApproachPlan {
	// 5.5 block fixture extent + 1.5 hull half-width + 0.9 waypoint handoff
	// radius, with room for the production steering curve through a 90 degree turn.
	static final double PERIMETER_RADIUS = 9.0;
	static final double CORNER_ARRIVAL_DISTANCE = 0.9;
	static final double OUTER_ALIGNMENT_DISTANCE = 0.28;
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

	static double routeArrivalDistance(boolean outerLeg) {
		return outerLeg ? OUTER_ALIGNMENT_DISTANCE : CORNER_ARRIVAL_DISTANCE;
	}

	static Vec3 gateAlignmentTarget(Vec3 current, Vec3 outer, Vec3 gate,
		BiPredicate<Vec3, Vec3> corridorClear) {
		return corridorClear.test(current, gate) ? gate : outer;
	}

	static List<Direction> sideDirections(Direction facing) {
		Direction forward = facing == null ? Direction.NORTH : facing;
		return List.of(forward, forward.getOpposite(), forward.getClockWise(), forward.getCounterClockWise());
	}

	static List<Vec3> ingressRoute(Vec3 current, Vec3 landing, Vec3 outer, Direction approach,
		BiPredicate<Vec3, Vec3> corridorClear) {
		if (current == null || landing == null || outer == null || approach == null || corridorClear == null)
			return List.of();
		double y = Math.max(current.y, outer.y);
		Vec3 center = new Vec3(landing.x, y, landing.z);
		Vec3 forward = new Vec3(approach.getStepX(), 0, approach.getStepZ());
		Direction clockwise = approach.getClockWise();
		Vec3 right = new Vec3(clockwise.getStepX(), 0, clockwise.getStepZ());
		double radius = PERIMETER_RADIUS;
		List<Vec3> nodes = new ArrayList<>();
		nodes.add(current);
		nodes.add(center.add(forward.scale(radius)));
		nodes.add(center.add(forward.scale(radius)).add(right.scale(radius)));
		nodes.add(center.add(right.scale(radius)));
		nodes.add(center.add(forward.scale(-radius)).add(right.scale(radius)));
		nodes.add(center.add(forward.scale(-radius)));
		nodes.add(center.add(forward.scale(-radius)).add(right.scale(-radius)));
		nodes.add(center.add(right.scale(-radius)));
		nodes.add(center.add(forward.scale(radius)).add(right.scale(-radius)));
		nodes.add(outer);

		int target = nodes.size() - 1;
		double[] distance = new double[nodes.size()];
		int[] previous = new int[nodes.size()];
		boolean[] visited = new boolean[nodes.size()];
		Arrays.fill(distance, Double.POSITIVE_INFINITY);
		Arrays.fill(previous, -1);
		distance[0] = 0.0;
		for (int count = 0; count < nodes.size(); count++) {
			int nearest = -1;
			for (int i = 0; i < nodes.size(); i++) {
				if (!visited[i] && (nearest < 0 || distance[i] < distance[nearest])) nearest = i;
			}
			if (nearest < 0 || !Double.isFinite(distance[nearest])) break;
			if (nearest == target) break;
			visited[nearest] = true;
			for (int next = 1; next < nodes.size(); next++) {
				if (visited[next] || !corridorClear.test(nodes.get(nearest), nodes.get(next))) continue;
				double candidate = distance[nearest] + nodes.get(nearest).distanceTo(nodes.get(next));
				if (candidate < distance[next]) {
					distance[next] = candidate;
					previous[next] = nearest;
				}
			}
		}
		if (previous[target] < 0) return List.of();
		List<Vec3> route = new ArrayList<>();
		for (int cursor = target; cursor > 0; cursor = previous[cursor]) route.add(nodes.get(cursor));
		Collections.reverse(route);
		return List.copyOf(route);
	}
}
