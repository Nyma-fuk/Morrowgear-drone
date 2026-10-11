package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded owner-scoped relay graph used by controller telemetry and commands. */
public final class DataLinkRelayPolicy {
	public static final int MAX_RELAY_NODES = 64;
	public static final double DIRECT_RANGE = 128.0;
	public static final double FIELD_RANGE = 128.0;

	private DataLinkRelayPolicy() {}

	public static Coverage coverage(Point origin, List<Node> candidates) {
		List<Node> nodes = candidates.stream()
			.sorted(Comparator.comparingDouble((Node node) -> node.point().distanceTo(origin))
				.thenComparing(Comparator.comparingInt(Node::energyPercent).reversed())
				.thenComparing(node -> node.id().toString()))
			.limit(MAX_RELAY_NODES).toList();
		Map<UUID, ReachableNode> reached = new HashMap<>();
		while (true) {
			ReachableNode best = null;
			for (Node node : nodes) {
				if (reached.containsKey(node.id())) continue;
				ReachableNode candidate = candidateFromOrigin(origin, node);
				for (ReachableNode parent : reached.values()) {
					ReachableNode via = candidateFromRelay(parent, node);
					if (better(via, candidate)) candidate = via;
				}
				if (better(candidate, best)) best = candidate;
			}
			if (best == null) break;
			reached.put(best.node().id(), best);
		}
		List<ReachableNode> ordered = new ArrayList<>(reached.values());
		ordered.sort(Comparator.comparingInt(ReachableNode::hops)
			.thenComparing(entry -> entry.node().id().toString()));
		return new Coverage(origin, List.copyOf(ordered));
	}

	private static ReachableNode candidateFromOrigin(Point origin, Node node) {
		int quality = linkQuality(origin.distanceTo(node.point()));
		return quality <= 0 ? null : new ReachableNode(node, 1, quality, null);
	}

	private static ReachableNode candidateFromRelay(ReachableNode parent, Node node) {
		int edgeQuality = linkQuality(parent.node().point().distanceTo(node.point()));
		if (edgeQuality <= 0) return null;
		int quality = Math.max(0, Math.min(parent.quality(), edgeQuality) - 3);
		return quality <= 0 ? null : new ReachableNode(node, parent.hops() + 1,
			quality, parent.node().id());
	}

	private static boolean better(ReachableNode candidate, ReachableNode current) {
		if (candidate == null) return false;
		if (current == null) return true;
		if (candidate.quality() != current.quality()) return candidate.quality() > current.quality();
		if (candidate.hops() != current.hops()) return candidate.hops() < current.hops();
		return candidate.node().id().toString().compareTo(current.node().id().toString()) < 0;
	}

	static int linkQuality(double distance) {
		if (!Double.isFinite(distance) || distance > FIELD_RANGE) return 0;
		if (distance <= 64.0) return 100;
		if (distance <= 96.0) return 90;
		if (distance <= 112.0) return 75;
		if (distance <= 120.0) return 55;
		return 30;
	}

	public record Point(double x, double y, double z) {
		double distanceTo(Point other) {
			double dx = x - other.x, dy = y - other.y, dz = z - other.z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	public record Node(UUID id, Point point, int energyPercent) {}
	public record ReachableNode(Node node, int hops, int quality, UUID parentId) {
		public ReachableNode(Node node, int hops) { this(node, hops, 100, null); }
	}
	public record Link(boolean connected, int hops, int quality, UUID relayId) {
		static Link disconnected() { return new Link(false, -1, 0, null); }
	}

	public record Coverage(Point origin, List<ReachableNode> relays) {
		public Link link(Point target) {
			int directQuality = linkQuality(origin.distanceTo(target));
			Link best = directQuality <= 0 ? Link.disconnected()
				: new Link(true, 0, directQuality, null);
			for (ReachableNode relay : relays) {
				int edgeQuality = linkQuality(relay.node().point().distanceTo(target));
				if (edgeQuality <= 0) continue;
				int quality = Math.max(0, Math.min(relay.quality(), edgeQuality) - 3);
				Link candidate = new Link(true, relay.hops(), quality, relay.node().id());
				if (!best.connected() || candidate.quality() > best.quality()
					|| candidate.quality() == best.quality() && candidate.hops() < best.hops()
					|| candidate.quality() == best.quality() && candidate.hops() == best.hops()
						&& candidate.relayId().toString().compareTo(best.relayId().toString()) < 0) best = candidate;
			}
			return best;
		}
	}
}
