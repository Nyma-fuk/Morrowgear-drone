package jp.morrowgear.drone;

import java.util.List;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class DataLinkRelayNetwork {
	private static final WeakHashMap<ServerPlayer, CachedCoverage> CACHE = new WeakHashMap<>();
	private DataLinkRelayNetwork() {}

	static synchronized DataLinkRelayPolicy.Coverage coverage(ServerPlayer player) {
		long tick = player.level().getGameTime();
		String dimension = player.level().dimension().toString();
		CachedCoverage cached = CACHE.get(player);
		if (cached != null && cached.tick() == tick && cached.dimension().equals(dimension)
			&& cached.origin().distanceToSqr(player.position()) < 0.01) return cached.coverage();
		List<DataLinkRelayPolicy.Node> nodes = SolarServiceRegistry
			.relayNodes(player.getUUID(), dimension, tick).stream()
			.map(node -> new DataLinkRelayPolicy.Node(node.id(), point(node.x(), node.y(), node.z()),
				node.energyPercent()))
			.toList();
		DataLinkRelayPolicy.Coverage coverage = DataLinkRelayPolicy.coverage(point(player.position()), nodes);
		CACHE.put(player, new CachedCoverage(tick, dimension, player.position(), coverage));
		return coverage;
	}

	static DataLinkRelayPolicy.Link link(ServerPlayer player, Vec3 target) {
		return coverage(player).link(point(target));
	}

	static boolean canReach(ServerPlayer player, Vec3 target) {
		return link(player, target).connected();
	}

	private static DataLinkRelayPolicy.Point point(Vec3 position) {
		return point(position.x, position.y, position.z);
	}

	private static DataLinkRelayPolicy.Point point(double x, double y, double z) {
		return new DataLinkRelayPolicy.Point(x, y, z);
	}

	private record CachedCoverage(long tick, String dimension, Vec3 origin,
		DataLinkRelayPolicy.Coverage coverage) {}
}
