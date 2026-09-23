package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import jp.morrowgear.drone.network.SupplyNetworkStatusPayload;
import jp.morrowgear.drone.network.SupplyNetworkStatusRequest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class SupplyNetworkTransport {
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
    private SupplyNetworkTransport() {}
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(SupplyNetworkStatusPayload.TYPE, SupplyNetworkStatusPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SupplyNetworkStatusRequest.TYPE, SupplyNetworkStatusRequest.CODEC);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_REQUEST.remove(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_REQUEST.clear());
        ServerPlayNetworking.registerGlobalReceiver(SupplyNetworkStatusRequest.TYPE, (request, context) -> context.server().execute(() -> {
            var player = context.player();
            long tick = context.server().getTickCount();
            Long previous = LAST_REQUEST.get(player.getUUID());
            if (previous != null && tick >= previous && tick - previous < 20
                || !ServerPlayNetworking.canSend(player, SupplyNetworkStatusPayload.TYPE)) return;
            LAST_REQUEST.put(player.getUUID(), tick);
            var docks = SupplyNetworkRuntime.summary(player.level(), player.getUUID()).stream().map(summary ->
                new SupplyNetworkStatusPayload.DockStatus(summary.configuration().route().source(),
                    summary.configuration().route().dock(), summary.configuration().enabled(), summary.status(),
                    summary.stock().stream().map(stock -> new SupplyNetworkStatusPayload.Stock(
                        new SupplyNetworkPolicy.Rule(stock.kind(), stock.minimum(), stock.priority()),
                        stock.stock(), stock.incoming(), stock.missing())).toList(),
                    summary.jobs().stream().map(job -> new SupplyNetworkStatusPayload.Delivery(job.drone(), job.status(),
                        job.kind(), job.remaining())).toList())).toList();
            ServerPlayNetworking.send(player, new SupplyNetworkStatusPayload(request.requestId(), player.getUUID(),
                player.level().dimension().identifier().toString(), docks));
        }));
    }
}
