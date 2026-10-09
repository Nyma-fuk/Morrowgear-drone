package jp.morrowgear.drone.client;

import java.util.List;
import jp.morrowgear.drone.ThreatSensorPolicy;
import jp.morrowgear.drone.network.TacticalContactPayload;
import jp.morrowgear.drone.network.TacticalContactRequest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

final class TacticalContactClient {
    private static TacticalContactPayload snapshot;
    private static long nextRequest;
    private static long token;

    private TacticalContactClient() {}

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(TacticalContactPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (context.client().player != null && payload.owner().equals(context.client().player.getUUID())
                    && (snapshot == null || payload.token() >= snapshot.token())) snapshot = payload;
            }));
    }

    static List<TacticalContactPayload.Row> contacts(Minecraft client) {
        request(client);
        if (client.level == null || client.player == null || snapshot == null
            || !snapshot.owner().equals(client.player.getUUID())
            || !snapshot.dimension().equals(client.level.dimension().toString())
            || client.level.getGameTime() - snapshot.tick() > ThreatSensorPolicy.REPORT_TTL_TICKS) return List.of();
        return snapshot.rows();
    }

    private static void request(Minecraft client) {
        if (client.level == null || client.player == null) return;
        long now = client.level.getGameTime();
        if (now < nextRequest || !ClientPlayNetworking.canSend(TacticalContactRequest.TYPE)) return;
        nextRequest = now + 10L;
        ClientPlayNetworking.send(new TacticalContactRequest(++token));
    }

    static void clear() { snapshot = null; nextRequest = 0; token = 0; }
}
