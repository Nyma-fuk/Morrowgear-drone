package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.carrier.CarrierCargoContainerBlockEntity;
import jp.morrowgear.drone.network.CargoLiftCommandPayload;
import jp.morrowgear.drone.network.CargoLiftStatusPayload;
import jp.morrowgear.drone.network.CargoLiftStatusRequest;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public final class CargoLiftNetwork {
    private CargoLiftNetwork() {}

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(CargoLiftCommandPayload.TYPE, CargoLiftCommandPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CargoLiftStatusRequest.TYPE, CargoLiftStatusRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CargoLiftStatusPayload.TYPE, CargoLiftStatusPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CargoLiftCommandPayload.TYPE, (payload, context) ->
            context.server().execute(() -> apply(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(CargoLiftStatusRequest.TYPE, (payload, context) ->
            context.server().execute(() -> ServerPlayNetworking.send(context.player(), snapshot(context.player()))));
    }

    static void apply(ServerPlayer player, CargoLiftCommandPayload payload) {
        if (payload.action() == CargoLiftCommandPayload.Action.CANCEL) {
            CargoLiftLoadEntity mission = findMission(player.level(), player.getUUID(), payload.missionId());
            if (mission == null) { message(player, "共同輸送任務が見つかりません"); return; }
            mission.requestCancel(player);
            message(player, "共同輸送を安全中止します / 最寄りの着地可能地点へ降下");
            return;
        }
        ServerLevel level = player.level();
        BlockPos source = BlockPos.of(payload.source());
        BlockPos requested = BlockPos.of(payload.destination());
        if (source.distSqr(player.blockPosition()) > 256.0 * 256.0
            || Math.abs(requested.getX() - player.getBlockX()) > 512
            || Math.abs(requested.getZ() - player.getBlockZ()) > 512) {
            message(player, "共同輸送範囲外です / コンテナ256m・搬送先512m以内"); return;
        }
        if (!level.hasChunkAt(source)
            || !(level.getBlockEntity(source) instanceof CarrierCargoContainerBlockEntity container)
            || !container.ownerId().equals(player.getUUID())) {
            message(player, "所有するカーゴコンテナを受信できません"); return;
        }
        if (container.transferReserved()) { message(player, "このコンテナは別の荷役で予約されています"); return; }
        for (Entity entity : level.getAllEntities()) if (entity instanceof CargoLiftLoadEntity lift
            && lift.ownerId().equals(player.getUUID()) && lift.sourcePos().equals(source)) {
            message(player, "このコンテナの共同輸送はすでに進行中です"); return;
        }
        List<DroneEntity> selected = MorrowgearDrone.ownedDrones(level, player, 512).stream()
            .filter(DroneEntity::cargoLiftCandidate)
            .sorted(Comparator.comparingDouble(drone -> drone.distanceToSqr(Vec3.atCenterOf(source))))
            .limit(CargoLiftPolicy.REQUIRED_AIRCRAFT).toList();
        if (selected.size() != CargoLiftPolicy.REQUIRED_AIRCRAFT) {
            message(player, "共同輸送には飛行電力35%以上の利用可能なサルベージ機が4機必要です / 現在 "
                + selected.size() + "機"); return;
        }
        int destinationY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            requested.getX(), requested.getZ());
        BlockPos destination = new BlockPos(requested.getX(), destinationY, requested.getZ());
        CargoLiftLoadEntity mission = MorrowgearDrone.CARGO_LIFT_LOAD.create(level, EntitySpawnReason.COMMAND);
        if (mission == null) { message(player, "共同輸送管制を開始できませんでした"); return; }
        mission.initialize(player, source, destination, container, selected);
        if (!level.addFreshEntity(mission)) {
            mission.cancelBeforeSpawn(level, "CONTAINER LIFT SPAWN FAILED");
            message(player, "共同輸送管制を開始できませんでした / 予約を解除しました");
            return;
        }
        message(player, "共同輸送開始 / 4機予約 / " + source.toShortString()
            + " -> " + destination.toShortString());
    }

    private static CargoLiftLoadEntity findMission(ServerLevel level, UUID owner, String id) {
        UUID mission;
        try { mission = UUID.fromString(id); }
        catch (IllegalArgumentException ignored) { return null; }
        for (Entity entity : level.getAllEntities()) if (entity instanceof CargoLiftLoadEntity lift
            && lift.ownerId().equals(owner) && lift.missionId().equals(mission)) return lift;
        return null;
    }

    private static CargoLiftStatusPayload snapshot(ServerPlayer player) {
        ServerLevel level = player.level();
        List<CargoLiftStatusPayload.Row> rows = new java.util.ArrayList<>();
        BlockPos playerPos = player.blockPosition();
        int cx = player.chunkPosition().x(), cz = player.chunkPosition().z();
        // Operator discovery is deliberately local and bounded; it never reveals underground remote storage.
        for (int x = cx - 4; x <= cx + 4; x++) for (int z = cz - 4; z <= cz + 4; z++) {
            if (!level.hasChunk(x, z)) continue;
            for (BlockEntity blockEntity : level.getChunk(x, z).getBlockEntities().values()) {
                if (!(blockEntity instanceof CarrierCargoContainerBlockEntity cargo)
                    || !cargo.ownerId().equals(player.getUUID())) continue;
                BlockPos pos = cargo.getBlockPos();
                rows.add(new CargoLiftStatusPayload.Row(pos.asLong(),
                    "CARGO " + shortId(cargo.cargoId().toString()), "",
                    pos.toShortString() + (cargo.transferReserved() ? " / 予約中" : " / 搬送可能"),
                    distance(playerPos, pos)));
            }
        }
        for (Entity entity : level.getAllEntities()) if (entity instanceof CargoLiftLoadEntity lift
            && lift.ownerId().equals(player.getUUID())) {
            rows.add(new CargoLiftStatusPayload.Row(lift.sourcePos().asLong(),
                "共同輸送 " + shortId(lift.missionId().toString()), lift.missionId().toString(),
                lift.phase().name() + " / 搬送先 " + lift.destinationPos().toShortString(),
                distance(playerPos, lift.blockPosition())));
        }
        rows.sort(Comparator.comparing((CargoLiftStatusPayload.Row row) -> row.mission().isBlank())
            .thenComparingInt(CargoLiftStatusPayload.Row::distance));
        return new CargoLiftStatusPayload(rows);
    }

    private static int distance(BlockPos a, BlockPos b) { return (int)Math.round(Math.sqrt(a.distSqr(b))); }
    private static String shortId(String id) { return id.length() <= 8 ? id : id.substring(0, 8).toUpperCase(); }

    private static void message(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[MORROWGEAR] " + text));
    }
}
