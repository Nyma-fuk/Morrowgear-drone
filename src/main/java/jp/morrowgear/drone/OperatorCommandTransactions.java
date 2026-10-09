package jp.morrowgear.drone;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import jp.morrowgear.drone.network.DroneCommandPayload;
import jp.morrowgear.drone.network.DroneCommandResultPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Tracks command outcomes independently from entity replication and UI inference. */
public final class OperatorCommandTransactions {
    static final long TIMEOUT_TICKS = 20L * 60L * 30L;
    private static final int LIMIT = 4096;
    private static final Map<Key, Active> ACTIVE = new LinkedHashMap<>();
    private static boolean registered;

    private OperatorCommandTransactions() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(OperatorCommandTransactions::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ACTIVE.clear());
    }

    public static Admission begin(ServerPlayer player, DroneCommandPayload payload) {
        return new Admission(player, payload);
    }

    public static void acceptGenerated(ServerPlayer player, UUID commandId, DroneEntity drone, String action) {
        track(player, new DroneCommandPayload(commandId, drone.getId(), action), drone, action);
    }

    public static void rejectGenerated(ServerPlayer player, UUID commandId, DroneEntity drone, String action, String reason) {
        reject(player, new DroneCommandPayload(commandId, drone == null ? -1 : drone.getId(), action),
            drone == null ? new UUID(0, 0) : drone.getUUID(), drone == null ? -1 : drone.getId(), action, reason);
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 5 != 0) return;
        Iterator<Map.Entry<Key, Active>> iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            Active active = iterator.next().getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(active.owner);
            if (player == null) continue;
            var entity = player.level().getEntity(active.entityId);
            if (!(entity instanceof DroneEntity drone) || !drone.getUUID().equals(active.unitId)) {
                send(player, active, DroneCommandResultPayload.Status.FAILED, "機体とのデータリンクを喪失");
                iterator.remove(); continue;
            }
            Evaluation evaluation = evaluate(active, drone, server.getTickCount());
            if (evaluation.status != active.lastStatus || !evaluation.reason.equals(active.lastReason)) {
                send(player, active, evaluation.status, evaluation.reason);
                active.lastStatus = evaluation.status; active.lastReason = evaluation.reason;
            }
            if (evaluation.terminal()) iterator.remove();
        }
    }

    private static Evaluation evaluate(Active active, DroneEntity drone, long now) {
        if (now - active.acceptedTick > TIMEOUT_TICKS)
            return new Evaluation(DroneCommandResultPayload.Status.FAILED, "実行期限を超過");
        String hold = drone.operatorCommandHoldReason();
        if (drone.isPowerLost() || drone.dockHolding() || drone.serviceReturnActive()
            || drone.cargoState() == CargoState.WAIT_SOURCE || drone.cargoState() == CargoState.WAIT_TARGET
            || drone.cargoState().queued())
            return new Evaluation(DroneCommandResultPayload.Status.HELD, hold);
        if (drone.serviceReturnActive() || drone.isDocked() && !goalReached(active, drone)) {
            String reason = drone.isDocked() ? "Dockで補給・整備待ち" : "Dockへ帰還中";
            return new Evaluation(DroneCommandResultPayload.Status.HELD, reason);
        }
        if (goalFailed(active, drone)) return new Evaluation(DroneCommandResultPayload.Status.FAILED, failureReason(active, drone));
        if (goalReached(active, drone)) return new Evaluation(DroneCommandResultPayload.Status.COMPLETED, completionReason(active));
        return new Evaluation(DroneCommandResultPayload.Status.EXECUTING,
            hold.isBlank() ? active.summary : hold);
    }

    private static boolean goalReached(Active active, DroneEntity drone) {
        String action = active.action;
        if (action.equals("dock")) return drone.isDocked();
        if (action.equals("standby")) return drone.mode() == DroneMode.STANDBY;
        if (action.equals("return")) return drone.mode() == DroneMode.RETURN || drone.isDocked();
        if (action.equals("orbit")) return drone.mode() == DroneMode.ORBIT;
        if (action.startsWith("work:")) return drone.fieldOperationState() == FieldOperationState.COMPLETE;
        if (action.startsWith("move:")) return !drone.hasWaypoint() && drone.missionId().equals(active.missionId);
        if (action.startsWith("track:")) return !drone.hasTrackingTarget() && drone.missionId().equals(active.missionId);
        if (action.startsWith("attack:")) return !drone.operatorAttackActive() && drone.missionId().equals(active.missionId);
        return false;
    }

    private static boolean goalFailed(Active active, DroneEntity drone) {
        return active.action.startsWith("work:") && drone.fieldOperationState() == FieldOperationState.BLOCKED;
    }
    private static String failureReason(Active active, DroneEntity drone) {
        if (active.action.startsWith("work:")) return "作業対象へ到達できない、または保護ブロックで停止";
        return "命令を継続できません";
    }
    private static String completionReason(Active active) {
        if (active.action.equals("dock")) return "Dockへ着艦";
        if (active.action.startsWith("work:")) return "作業完了";
        if (active.action.startsWith("attack:")) return "指定目標への攻撃完了";
        return "命令目標へ到達";
    }

    private static void track(ServerPlayer player, DroneCommandPayload payload, DroneEntity drone, String action) {
        if (ACTIVE.size() >= LIMIT) ACTIVE.remove(ACTIVE.keySet().iterator().next());
        String mission = missionId(action);
        Active active = new Active(player.getUUID(), payload.commandId(), drone.getUUID(), drone.getId(), action,
            mission, player.level().getServer().getTickCount(), summary(action));
        ACTIVE.put(new Key(player.getUUID(), payload.commandId(), drone.getUUID()), active);
        send(player, active, DroneCommandResultPayload.Status.ACCEPTED, "サーバーが命令を受理");
        if (immediate(action)) {
            send(player, active, DroneCommandResultPayload.Status.COMPLETED, immediateReason(action, drone));
            ACTIVE.remove(new Key(player.getUUID(), payload.commandId(), drone.getUUID()));
            return;
        }
        Evaluation first = evaluate(active, drone, active.acceptedTick);
        send(player, active, first.status, first.reason);
        active.lastStatus = first.status; active.lastReason = first.reason;
        if (first.terminal()) ACTIVE.remove(new Key(player.getUUID(), payload.commandId(), drone.getUUID()));
    }

    private static boolean immediate(String action) {
        return action.equals("decommission") || action.equals("wing_leave")
            || action.startsWith("capacity_upgrade:") || action.startsWith("weapon_module:")
            || action.startsWith("module:") || action.startsWith("wing_join:")
            || action.startsWith("wing_create:") || action.startsWith("group:")
            || action.startsWith("assign_dock:") || action.startsWith("cargo_source:")
            || action.startsWith("cargo_target:");
    }
    private static String immediateReason(String action, DroneEntity drone) {
        if (action.startsWith("cargo_source:")) return drone.hasCargoTarget()
            ? "補給元を設定 / " + drone.operatorCommandHoldReason() : "補給元を設定 / 搬入先未設定";
        if (action.startsWith("cargo_target:")) return drone.hasCargoSource()
            ? "搬入先を設定 / " + drone.operatorCommandHoldReason() : "搬入先を設定 / 補給元未設定";
        return "設定を反映";
    }

    private static String missionId(String action) {
        String[] fields = action.split(":", 8);
        try {
            if (fields[0].equals("work") && fields.length > 6) return fields[6];
            if (fields[0].equals("guard") && fields.length > 5) return fields[5];
            if (fields[0].equals("patrol") && fields.length > 1) return fields[1];
            if (fields[0].equals("move") && fields.length > 3) return fields[3];
            if ((fields[0].equals("track") || fields[0].equals("attack")) && fields.length > 2) return fields[2];
            if (fields[0].equals("follow") && fields.length > 1) return fields[1];
        } catch (RuntimeException ignored) { }
        return "";
    }
    private static String summary(String action) {
        if (action.startsWith("work:")) return "作業地点へ展開中";
        if (action.startsWith("attack:")) return "指定目標を迎撃中";
        if (action.startsWith("patrol:")) return "巡回経路を飛行中";
        if (action.equals("dock") || action.equals("return")) return "Dockへ帰還中";
        return "命令を実行中";
    }

    private static void send(ServerPlayer player, Active active, DroneCommandResultPayload.Status status, String reason) {
        if (ServerPlayNetworking.canSend(player, DroneCommandResultPayload.TYPE))
            ServerPlayNetworking.send(player, new DroneCommandResultPayload(active.commandId, active.unitId,
                active.entityId, status, active.action, reason, player.level().getServer().getTickCount()));
    }
    private static void reject(ServerPlayer player, DroneCommandPayload payload, UUID unit, int entity, String action, String reason) {
        if (ServerPlayNetworking.canSend(player, DroneCommandResultPayload.TYPE))
            ServerPlayNetworking.send(player, new DroneCommandResultPayload(payload.commandId(), unit, entity,
                DroneCommandResultPayload.Status.REJECTED, action, reason,
                player.level().getServer().getTickCount()));
    }

    public static final class Admission implements AutoCloseable {
        private final ServerPlayer player; private final DroneCommandPayload payload;
        private UUID unit = new UUID(0, 0); private int entity = -1; private String action;
        private boolean resolved;
        Admission(ServerPlayer player, DroneCommandPayload payload) { this.player = player; this.payload = payload; this.action = payload.action(); }
        public void identify(DroneEntity drone, String normalizedAction) {
            if (drone != null) { unit = drone.getUUID(); entity = drone.getId(); }
            action = normalizedAction == null ? payload.action() : normalizedAction;
        }
        public void accepted(DroneEntity drone, String normalizedAction) {
            if (resolved) return; resolved = true; identify(drone, normalizedAction); track(player, payload, drone, action);
        }
        public void rejected(String reason) {
            if (resolved) return; resolved = true; reject(player, payload, unit, entity, action, reason);
        }
        @Override public void close() { if (!resolved) rejected("命令形式または現在状態が不正"); }
    }

    private record Key(UUID owner, UUID command, UUID unit) {}
    private static final class Active {
        final UUID owner, commandId, unitId; final int entityId; final String action, missionId, summary; final long acceptedTick;
        DroneCommandResultPayload.Status lastStatus; String lastReason = "";
        Active(UUID owner, UUID commandId, UUID unitId, int entityId, String action, String missionId, long acceptedTick, String summary) {
            this.owner = owner; this.commandId = commandId; this.unitId = unitId; this.entityId = entityId;
            this.action = action; this.missionId = missionId; this.acceptedTick = acceptedTick; this.summary = summary;
        }
    }
    private record Evaluation(DroneCommandResultPayload.Status status, String reason) {
        boolean terminal() { return status == DroneCommandResultPayload.Status.COMPLETED
            || status == DroneCommandResultPayload.Status.FAILED || status == DroneCommandResultPayload.Status.REJECTED; }
    }
}
