package jp.morrowgear.drone;

import java.util.ArrayList;
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
				status, drone.missionId(), link.hops(), link.quality()));
		}
		var relays = coverage.relays().stream().limit(FleetLinkPayload.MAX_RELAYS)
			.map(entry -> new FleetLinkPayload.Relay(entry.node().id(), entry.node().point().x(),
				entry.node().point().y(), entry.node().point().z(), entry.node().energyPercent(), entry.hops()))
			.toList();
		if (ServerPlayNetworking.canSend(player, FleetLinkPayload.TYPE))
			ServerPlayNetworking.send(player, new FleetLinkPayload(player.getUUID(), token, tick,
				player.level().dimension().toString(), truncated, units, relays));
	}
}
