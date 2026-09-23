package jp.morrowgear.drone.client;

import java.util.List;
import java.util.Optional;
import jp.morrowgear.drone.network.SupplyNetworkStatusPayload;
import jp.morrowgear.drone.network.SupplyNetworkStatusRequest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class SupplyNetworkClient {
    private static SupplyNetworkStatusPayload snapshot;
    private static int sequence;
    private static long lastRequest = Long.MIN_VALUE / 2;
    private static long receivedAt;
    private SupplyNetworkClient() {}
    public static void register() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            snapshot = null; lastRequest = Long.MIN_VALUE / 2; receivedAt = 0;
        });
        ClientPlayNetworking.registerGlobalReceiver(SupplyNetworkStatusPayload.TYPE, (payload, context) -> context.client().execute(() -> {
            var player = context.client().player;
            if (player == null || payload.requestId() != sequence || !payload.owner().equals(player.getUUID())
                || !payload.dimension().equals(player.level().dimension().identifier().toString())) return;
            snapshot = payload;
            receivedAt = now();
        }));
    }
    /** Called only while a supply view is open. Does not poll during ordinary play. */
    public static void request() {
        long now = now();
        if (Minecraft.getInstance().player == null || now - lastRequest < 1200
            || !ClientPlayNetworking.canSend(SupplyNetworkStatusRequest.TYPE)) return;
        lastRequest = now;
        ClientPlayNetworking.send(new SupplyNetworkStatusRequest(++sequence));
    }
    public static List<SupplyNetworkStatusPayload.DockStatus> docks() {
        var player = Minecraft.getInstance().player;
        if (snapshot == null || player == null || !snapshot.owner().equals(player.getUUID())
            || !snapshot.dimension().equals(player.level().dimension().identifier().toString()) || now() - receivedAt > 5000)
            return List.of();
        return snapshot.docks();
    }
    public static Optional<SupplyNetworkStatusPayload.DockStatus> dock(long position) {
        return docks().stream().filter(dock -> dock.dock() == position).findFirst();
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
}
