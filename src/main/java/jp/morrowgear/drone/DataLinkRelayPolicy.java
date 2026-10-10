package jp.morrowgear.drone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded, owner-scoped radio graph used by controller telemetry and commands. */
public final class DataLinkRelayPolicy {
	public static final double DIRECT_RANGE = 256.0;
	public static final double RELAY_RANGE = 256.0;
	public static final int MAX_RELAY_NODES = 64;
	public static final int MAX_HOPS = 16;

	private DataLinkRelayPolicy() {}

	public static Coverage coverage(Point origin, List<Node> candidates) {
		List<Node> nodes = candidates.stream()
			.sorted(Comparator.comparingDouble(node -> origin.distanceTo(node.point())))
			.limit(MAX_RELAY_NODES).toList();
		Map<UUID, Integer> hops = new HashMap<>();
		ArrayDeque<Node> open = new ArrayDeque<>();
		for (Node node : nodes) {
			if (origin.distanceTo(node.point()) <= RELAY_RANGE) {
				hops.put(node.id(), 1);
				open.addLast(node);
			}
		}
		while (!open.isEmpty()) {
			Node current = open.removeFirst();
			int nextHop = hops.get(current.id()) + 1;
			if (nextHop > MAX_HOPS) continue;
			for (Node candidate : nodes) {
				if (hops.containsKey(candidate.id())) continue;
				if (current.point().distanceTo(candidate.point()) > RELAY_RANGE) continue;
				hops.put(candidate.id(), nextHop);
				open.addLast(candidate);
			}
		}
		List<ReachableNode> reachable = new ArrayList<>();
		for (Node node : nodes) {
			Integer hop = hops.get(node.id());
			if (hop != null) reachable.add(new ReachableNode(node, hop));
		}
		reachable.sort(Comparator.comparingInt(ReachableNode::hops)
			.thenComparing(entry -> entry.node().id().toString()));
		return new Coverage(origin, List.copyOf(reachable));
	}

	public record Point(double x, double y, double z) {
		double distanceTo(Point other) {
			double dx = x - other.x, dy = y - other.y, dz = z - other.z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	public record Node(UUID id, Point point, int energyPercent) {}
	public record ReachableNode(Node node, int hops) {}
	public record Link(boolean connected, int hops, int quality, UUID relayId) {
		static Link disconnected() { return new Link(false, -1, 0, null); }
	}

	public record Coverage(Point origin, List<ReachableNode> relays) {
		public Link link(Point target) {
			double direct = origin.distanceTo(target);
			if (direct <= DIRECT_RANGE) return new Link(true, 0, quality(direct, DIRECT_RANGE, 0), null);
			ReachableNode best = null;
			double bestDistance = Double.MAX_VALUE;
			for (ReachableNode relay : relays) {
				double distance = relay.node().point().distanceTo(target);
				if (distance <= RELAY_RANGE && (best == null || relay.hops() < best.hops()
					|| relay.hops() == best.hops() && distance < bestDistance)) {
					best = relay;
					bestDistance = distance;
				}
			}
			return best == null ? Link.disconnected()
				: new Link(true, best.hops(), quality(bestDistance, RELAY_RANGE, best.hops()), best.node().id());
		}

		private static int quality(double distance, double range, int hops) {
			int segment = (int)Math.round(100.0 - Math.min(1.0, distance / range) * 45.0);
			return Math.max(20, Math.min(100, segment - Math.max(0, hops - 1) * 4));
		}
	}
}
