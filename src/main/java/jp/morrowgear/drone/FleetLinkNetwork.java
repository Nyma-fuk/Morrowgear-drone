package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import jp.morrowgear.drone.network.FleetLinkPayload;
import jp.morrowgear.drone.network.FleetLinkRequest;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

final class FleetLinkNetwork {
	private static final WeakHashMap<ServerPlayer, Long> LAST = new WeakHashMap<>();
	private FleetLinkNetwork() {}

	static void register() {
		PayloadTypeRegistry.serverboundPlay().register(FleetLinkRequest.TYPE, FleetLinkRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(FleetLinkPayload.TYPE, FleetLinkPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(FleetLinkRequest.TYPE, (request, context) ->
			context.server().execute(() -> send(context.player(), request.token())));
	}

	private static void send(ServerPlayer player, long token) {
		long clock = player.level().getServer().getTickCount();
		Long previous = LAST.get(player);
		if (previous != null && clock - previous < 10L) return;
		LAST.put(player, clock);
		long tick = player.level().getGameTime();
		var meshSnapshot = FieldRelayRuntime.snapshot(player);
		var meshStatus = FieldRelayRuntime.status(player);
		var coverage = DataLinkRelayNetwork.coverage(player);
		var all = MorrowgearDrone.ownedDrones(player.level(), player, 512);
		var units = new ArrayList<FleetLinkPayload.Unit>();
		boolean truncated = false;
		for (DroneEntity drone : all) {
			var link = coverage.link(new DataLinkRelayPolicy.Point(drone.getX(), drone.getY(), drone.getZ()));
			if (!link.connected()) continue;
			if (units.size() >= FleetLinkPayload.MAX_UNITS) { truncated = true; break; }
			String status = drone.hasActiveFieldOperation() ? drone.fieldStatusLabel()
				: drone.dataLinkStatus().isBlank() ? drone.operationalState().name() : drone.dataLinkStatus();
			units.add(new FleetLinkPayload.Unit(drone.getId(), drone.getUUID(), drone.unitId(),
				drone.getName().getString(), drone.groupId(), drone.role().id(), drone.mode().id(),
				drone.getX(), drone.getY(), drone.getZ(), drone.batteryPercent(), drone.isDocked(),
				status, drone.missionId(), link.hops(), link.quality(), link.relayId()));
		}
		Map<java.util.UUID, DroneEntity> byId = new HashMap<>();
		for (DroneEntity drone : all) byId.put(drone.getUUID(), drone);
		Map<java.util.UUID, DataLinkRelayPolicy.ReachableNode> reached = new HashMap<>();
		for (var entry : coverage.relays()) reached.put(entry.node().id(), entry);
		var relays = meshSnapshot.assignments().values().stream().limit(FleetLinkPayload.MAX_RELAYS)
			.map(assignment -> {
				DroneEntity drone = byId.get(assignment.fieldId());
				if (drone == null) return null;
				var link = reached.get(assignment.fieldId());
				boolean established = FieldRelayFlightPolicy.established(drone.position(), assignment.target());
				return new FleetLinkPayload.Relay(drone.getUUID(), drone.getX(), drone.getY(), drone.getZ(),
					drone.batteryPercent(), link == null ? 255 : link.hops(), link == null ? 0 : link.quality(),
					assignment.duty().name(), assignment.target().x, assignment.target().y, assignment.target().z,
					established, link == null ? null : link.parentId());
			}).filter(java.util.Objects::nonNull).toList();
		var mesh = new FleetLinkPayload.Mesh(meshStatus.state(), meshStatus.assigned(), meshStatus.established(),
			meshStatus.baseline(), meshStatus.reserve(), meshStatus.dynamic(), meshStatus.shortage(),
			meshStatus.minX(), meshStatus.maxX(), meshStatus.minZ(), meshStatus.maxZ());
		if (ServerPlayNetworking.canSend(player, FleetLinkPayload.TYPE))
			ServerPlayNetworking.send(player, new FleetLinkPayload(player.getUUID(), token, tick,
				player.level().dimension().toString(), truncated, units, relays, mesh));
	}
}
