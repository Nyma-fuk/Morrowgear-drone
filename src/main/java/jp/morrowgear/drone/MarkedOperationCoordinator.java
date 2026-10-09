package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Converts a validated marker envelope into the existing cooperative field-operation workflow. */
public final class MarkedOperationCoordinator {
    public static final int MAX_SIDE = 128;
    public static final long MAX_VOLUME = 2_000_000L;
    private MarkedOperationCoordinator() {}

    public static boolean start(ServerPlayer player, BlockPos min, BlockPos max) {
        ServerLevel level = player.level();
        int sx = max.getX() - min.getX() + 1, sy = max.getY() - min.getY() + 1, sz = max.getZ() - min.getZ() + 1;
        long volume = (long)sx * sy * sz;
        if (sx < 1 || sy < 1 || sz < 1 || sx > MAX_SIDE || sy > MAX_SIDE || sz > MAX_SIDE || volume > MAX_VOLUME) {
            player.sendSystemMessage(Component.literal("[MORROWGEAR] 採掘範囲は各辺128ブロック、合計200万ブロック以内にしてください。"));
            return false;
        }
        BlockPos anchor = new BlockPos((min.getX() + max.getX()) / 2, (min.getY() + max.getY()) / 2,
            (min.getZ() + max.getZ()) / 2);
        List<DroneEntity> fleet = MorrowgearDrone.ownedDrones(level, player, 512).stream()
            .filter(DroneEntity::acceptsOperatorFlightCommand)
            .filter(drone -> !drone.isPowerLost() && !drone.serviceReturnActive() && !drone.combatActive()
                && !drone.hasActiveFieldOperation() && !drone.hasSecurityPatrol()
                && (drone.mode() == DroneMode.STANDBY || drone.isDocked()))
            .sorted(Comparator.comparingInt((DroneEntity drone) -> roleOrder(drone.role()))
                .thenComparingDouble(drone -> drone.distanceToSqr(Vec3.atCenterOf(anchor))))
            .toList();
        List<AutonomousTaskForcePolicy.Candidate> candidates = fleet.stream().map(drone ->
            new AutonomousTaskForcePolicy.Candidate(drone.getId(), drone.unitId(), drone.role(), true,
                drone.isDocked(), drone.batteryPercent(), Math.round(drone.getHealth() * 100 / drone.getMaxHealth()),
                drone.distanceToSqr(Vec3.atCenterOf(anchor)))).toList();
        var plan = AutonomousTaskForcePolicy.plan(FieldOperationType.EXCAVATE, 8, candidates);
        if (!plan.executable()) {
            player.sendSystemMessage(Component.literal("[MORROWGEAR] 採掘開始不可 / " + plan.status()));
            return false;
        }
        String order = "MARK-" + Long.toString(level.getGameTime(), 36).toUpperCase() + "-"
            + Integer.toUnsignedString(anchor.hashCode(), 36).toUpperCase();
        MarkedOperationData.get(level.getServer()).put(player.getUUID(), order,
            level.dimension().toString(), min, max, level.getGameTime());
        Map<Integer, DroneEntity> byId = new HashMap<>();
        fleet.forEach(drone -> byId.put(drone.getId(), drone));
        UUID command = UUID.randomUUID();
        int index = 0;
        for (var selected : plan.selected()) {
            DroneEntity drone = byId.get(selected.entityId());
            if (drone == null) continue;
            drone.clearSecurityPatrol();
            if (drone.role() == DroneRole.CARGO) drone.pauseCargoRoute();
            drone.assignFieldOperation(FieldOperationType.EXCAVATE, anchor, 8, order);
            String action = "work:excavate:" + anchor.getX() + ":" + anchor.getY() + ":" + anchor.getZ()
                + ":8:" + order;
            OperatorCommandTransactions.acceptGenerated(player, command, drone, action);
            index++;
        }
        player.sendSystemMessage(Component.literal("[MORROWGEAR] 立体採掘オペレーション開始 / " + index
            + "機 / " + sx + "x" + sy + "x" + sz + " / " + plan.rosterLabel()));
        return true;
    }

    private static int roleOrder(DroneRole role) {
        return switch (role) { case ENGINEER -> 0; case SCOUT -> 1; case CARGO -> 2;
            case FIELD -> 3; case SECURITY -> 4; default -> 9; };
    }
}
