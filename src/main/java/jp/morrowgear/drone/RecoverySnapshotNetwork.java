package jp.morrowgear.drone;

import java.util.LinkedHashMap;
import java.util.WeakHashMap;
import jp.morrowgear.drone.network.RecoverySnapshotRequest;
import jp.morrowgear.drone.network.RecoverySnapshotPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

final class RecoverySnapshotNetwork {
    private static final WeakHashMap<ServerPlayer, Long> LAST = new WeakHashMap<>();
    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(RecoverySnapshotRequest.TYPE, RecoverySnapshotRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RecoverySnapshotPayload.TYPE, RecoverySnapshotPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RecoverySnapshotRequest.TYPE, (request, context) ->
            context.server().execute(() -> send(context.player(), request.token())));
    }
    private static void send(ServerPlayer player, long token) {
        long clock = player.level().getServer().getTickCount();
        long tick = player.level().getGameTime();
        if (!RecoverySnapshotPolicy.mayRequest(clock, LAST.get(player))) return;
        LAST.put(player, clock);
        var rows = new LinkedHashMap<String, RecoverySnapshotPayload.Row>();
        boolean truncated = false;
        for (var level : player.level().getServer().getAllLevels()) for (var entity : level.getAllEntities()) {
            if (!(entity instanceof DroneEntity drone) || !drone.isAlive()
                || !RecoverySnapshotPolicy.owned(player.getUUID(), drone.ownerId())) continue;
            var row = drone.recoverySnapshotFor(player.getUUID());
            if (row == null) continue;
            if (!rows.containsKey(row.target()) && rows.size() >= RecoverySnapshotPolicy.LIMIT) { truncated = true; continue; }
            var previous = rows.get(row.target());
            if (previous == null || !row.rescuer().isBlank()) rows.put(row.target(), row);
        }
        ServerPlayNetworking.send(player, new RecoverySnapshotPayload(player.getUUID(), token, tick, truncated, java.util.List.copyOf(rows.values())));
    }
}
