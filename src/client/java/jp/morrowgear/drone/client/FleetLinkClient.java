package jp.morrowgear.drone.client;

import java.util.List;
import jp.morrowgear.drone.network.FleetLinkPayload;
import jp.morrowgear.drone.network.FleetLinkRequest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

final class FleetLinkClient {
	private static FleetLinkPayload snapshot;
	private static long nextRequest;
	private static long token;
	private FleetLinkClient() {}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(FleetLinkPayload.TYPE, (payload, context) ->
			context.client().execute(() -> {
				if (context.client().player != null && payload.owner().equals(context.client().player.getUUID())
					&& (snapshot == null || payload.token() >= snapshot.token())) snapshot = payload;
			}));
	}

	static List<FleetLinkPayload.Unit> units(Minecraft client) {
		request(client);
		return current(client) ? snapshot.units() : List.of();
	}

	static List<FleetLinkPayload.Relay> relays(Minecraft client) {
		request(client);
		return current(client) ? snapshot.relays() : List.of();
	}

	private static boolean current(Minecraft client) {
		return client.level != null && client.player != null && snapshot != null
			&& snapshot.owner().equals(client.player.getUUID())
			&& snapshot.dimension().equals(client.level.dimension().toString())
			&& client.level.getGameTime() - snapshot.tick() <= 60L;
	}

	private static void request(Minecraft client) {
		if (client.level == null || client.player == null) return;
		long now = client.level.getGameTime();
		if (now < nextRequest || !ClientPlayNetworking.canSend(FleetLinkRequest.TYPE)) return;
		nextRequest = now + 10L;
		ClientPlayNetworking.send(new FleetLinkRequest(++token));
	}

	static void clear() { snapshot = null; nextRequest = 0; token = 0; }
}
