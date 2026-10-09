package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.WeakHashMap;
import jp.morrowgear.drone.network.TacticalContactPayload;
import jp.morrowgear.drone.network.TacticalContactRequest;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

final class TacticalContactNetwork {
    private static final WeakHashMap<ServerPlayer, Long> LAST = new WeakHashMap<>();
    private TacticalContactNetwork() {}

    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TacticalContactRequest.TYPE, TacticalContactRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TacticalContactPayload.TYPE, TacticalContactPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TacticalContactRequest.TYPE, (request, context) ->
            context.server().execute(() -> send(context.player(), request.token())));
    }

    private static void send(ServerPlayer player, long token) {
        long clock = player.level().getServer().getTickCount();
        if (!TacticalContactPolicy.mayRequest(clock, LAST.get(player))) return;
        LAST.put(player, clock);
        long tick = player.level().getGameTime();
        String dimension = player.level().dimension().toString();
        var reports = FleetThreatNetwork.readAll(dimension, player.getUUID(), tick);
        boolean truncated = reports.size() > TacticalContactPolicy.LIMIT;
        var rows = new ArrayList<TacticalContactPayload.Row>();
        for (var report : reports.stream().limit(TacticalContactPolicy.LIMIT).toList()) {
            rows.add(new TacticalContactPayload.Row(report.entityId(), report.position().x, report.position().y,
                report.position().z, report.score(), report.enemyThreat(), report.playerDanger(),
                report.sourceWing(), report.sourceMission(), report.tick()));
        }
        if (ServerPlayNetworking.canSend(player, TacticalContactPayload.TYPE))
            ServerPlayNetworking.send(player, new TacticalContactPayload(player.getUUID(), token, tick,
                dimension, truncated, rows));
    }
}
