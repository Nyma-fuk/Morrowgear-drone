package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jp.morrowgear.drone.network.OperationCuePayload;
import jp.morrowgear.drone.network.OperationCueRequestPayload;
import jp.morrowgear.drone.network.OperationEventsPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Independently registered observation and transport. No mission, ownership or control writes. */
public final class OperationCommunicationsServer {
    public static final int POLL_TICKS = 5;
    public static final int LOADED_LIMIT = 4096;
    private static final Map<UUID, DroneEntity> LOADED = new LinkedHashMap<>();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static boolean registered;

    private OperationCommunicationsServer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.clientboundPlay().register(OperationEventsPayload.TYPE, OperationEventsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OperationCuePayload.TYPE, OperationCuePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(OperationCueRequestPayload.TYPE, OperationCueRequestPayload.CODEC);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof DroneEntity drone && LOADED.size() < LOADED_LIMIT) LOADED.put(drone.getUUID(), drone);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> LOADED.remove(entity.getUUID(), entity));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> connect(server, handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SESSIONS.remove(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { LOADED.clear(); SESSIONS.clear(); });
        ServerTickEvents.END_SERVER_TICK.register(OperationCommunicationsServer::tick);
        ServerPlayNetworking.registerGlobalReceiver(OperationCueRequestPayload.TYPE, (payload, context) ->
                authorize(context.player(), payload));
    }

    private static void connect(MinecraftServer server, ServerPlayer player) {
        UUID owner = player.getUUID();
        Session session = new Session(owner, player.level().dimension().toString(), OperationHistoryData.get(server, owner));
        SESSIONS.put(owner, session);
        if (ServerPlayNetworking.canSend(player, OperationEventsPayload.TYPE)) {
            ServerPlayNetworking.send(player, new OperationEventsPayload(session.id, owner,
                    server.overworld().getGameTime(), true, session.history.events()));
        }
    }

    private static void tick(MinecraftServer server) {
        long tick = server.overworld().getGameTime();
        if (tick % POLL_TICKS != 0) return;
        Map<UUID, List<OperationObservation>> samples = new HashMap<>();
        for (DroneEntity drone : LOADED.values()) {
            UUID owner = drone.ownerId();
            if (!SESSIONS.containsKey(owner) || !drone.isAlive() || drone.isRemoved()) continue;
            List<OperationObservation> fleet = samples.computeIfAbsent(owner, ignored -> new ArrayList<>());
            if (fleet.size() < OperationEvent.FLEET_LIMIT) fleet.add(sample(drone));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Session session = SESSIONS.get(player.getUUID());
            if (session == null || !session.dimension.equals(player.level().dimension().toString())) {
                connect(server, player);
                session = SESSIONS.get(player.getUUID());
            }
            List<OperationEvent> events = session.observer.observe(samples.getOrDefault(player.getUUID(), List.of()),
                    tick, System.currentTimeMillis());
            events.forEach(session.history::append);
            if (!events.isEmpty() && ServerPlayNetworking.canSend(player, OperationEventsPayload.TYPE)) {
                ServerPlayNetworking.send(player, new OperationEventsPayload(session.id, player.getUUID(), tick, false, events));
            }
        }
    }

    private static void authorize(ServerPlayer sender, OperationCueRequestPayload request) {
        Session session = SESSIONS.get(sender.getUUID());
        if (session == null || !session.id.equals(request.session())
                || !session.dimension.equals(sender.level().dimension().toString())
                || !ServerPlayNetworking.canSend(sender, OperationCuePayload.TYPE)) return;
        long tick = sender.level().getServer().overworld().getGameTime();
        if (tick - session.requestWindow >= 20) { session.requestWindow = tick; session.requests = 0; }
        if (++session.requests > 4) return;
        List<OperationObservation> fresh = new ArrayList<>();
        for (DroneEntity drone : LOADED.values()) {
            // The ServerPlayer overload migrates ownership. Use the pure UUID check instead.
            if (fresh.size() < OperationEvent.FLEET_LIMIT && drone.isAlive() && !drone.isRemoved()
                    && drone.isOwnedBy(sender.getUUID())) fresh.add(sample(drone));
        }
        OperationEvent approved = session.observer.authorize(request.eventId(), fresh, tick).orElse(null);
        ServerPlayNetworking.send(sender, new OperationCuePayload(session.id, request.eventId(), approved));
    }

    static OperationObservation sample(DroneEntity drone) {
        EnumSet<OperationEvent.Kind> states = EnumSet.noneOf(OperationEvent.Kind.class);
        Entity target = drone.combatTargetId() >= 0 ? drone.level().getEntity(drone.combatTargetId()) : null;
        Entity salvage = drone.salvageTargetEntityId() >= 0 ? drone.level().getEntity(drone.salvageTargetEntityId()) : null;
        if (target == null && salvage != null) target = salvage;
        UUID targetId = target == null ? null : target.getUUID();
        boolean targetAlive = target != null && target.isAlive() && !target.isRemoved();
        if (target == null && drone.hasTrackingTarget()) {
            targetId = drone.trackingTargetId();
            target = ((net.minecraft.server.level.ServerLevel) drone.level()).getEntity(targetId);
            targetAlive = target != null && target.isAlive() && !target.isRemoved();
            if (!targetAlive) states.add(OperationEvent.Kind.TARGET_LOST);
        }
        if (drone.isPowerLost()) states.add(OperationEvent.Kind.POWER_LOST);
        if (drone.getHealth() / drone.getMaxHealth() <= DroneServicePolicy.CRITICAL_HEALTH_RATIO) states.add(OperationEvent.Kind.DANGER);
        if (drone.combatActive() || drone.emergencyInterceptActive()) states.add(OperationEvent.Kind.INTERCEPTING);
        if (drone.combatState() == CombatState.RAM_APPROACH) states.add(OperationEvent.Kind.EMERGENCY_ATTACK);
        OperationShotPolicy.confirmed(drone.combatWeapon(), drone.combatState(), drone.level().getGameTime(),
                drone.combatShotTick(), drone.combatStateTick()).ifPresent(states::add);
        boolean missionRunning = !drone.isPowerLost() && !drone.isDocked() && !drone.serviceReturnActive()
                && !drone.combatState().controlsFlight() && !drone.emergencyInterceptActive();
        if (missionRunning) {
            switch (drone.operationalState()) {
                case FOLLOW -> states.add(OperationEvent.Kind.FOLLOW_STARTED);
                case WAYPOINT, ORBIT -> states.add(OperationEvent.Kind.ROUTE_STARTED);
                case SECURITY_PATROL -> states.add(OperationEvent.Kind.PATROL_STARTED);
                case RETURN -> states.add(OperationEvent.Kind.RETURN_STARTED);
                default -> { }
            }
            if (drone.hasFieldOperation()) switch (drone.fieldOperationState()) {
                case SCANNING, SCOUT_SURVEY -> states.add(OperationEvent.Kind.SURVEY_STARTED);
                case WORKING, PLANTING -> states.add(OperationEvent.Kind.WORK_STARTED);
                case COMPLETE -> states.add(OperationEvent.Kind.OPERATION_COMPLETE);
                case BLOCKED -> states.add(OperationEvent.Kind.OPERATION_BLOCKED);
                default -> { }
            }
            if (drone.cargoState() != CargoState.UNASSIGNED && !drone.cargoPaused()) {
                states.add(OperationEvent.Kind.CARGO_STARTED);
                if (drone.cargoState() == CargoState.WAIT_SOURCE || drone.cargoState() == CargoState.WAIT_TARGET
                        || drone.cargoState().queued()) states.add(OperationEvent.Kind.CARGO_STALLED);
            }
            if (drone.engineerState() == EngineerState.REPAIRING) states.add(OperationEvent.Kind.REPAIR_STARTED);
            if (drone.engineerState() == EngineerState.MATERIAL_LOW) states.add(OperationEvent.Kind.MATERIAL_LOW);
        }
        if (drone.serviceReturnActive() && !drone.isDocked()) states.add(OperationEvent.Kind.SERVICE_RETURN);
        if (drone.isDocked()) states.add(OperationEvent.Kind.DOCKED);
        if (salvage != null && drone.salvageState() != SalvageState.IDLE) {
            states.add(OperationEvent.Kind.SALVAGE_STARTED);
            if (SalvageMissionRegistry.heldBy(salvage.getUUID(), drone.getUUID())
                    && SalvageMissionRegistry.isSuspended(salvage.getUUID())) states.add(OperationEvent.Kind.SALVAGE_ATTACHED);
        }
        boolean serviceReady = drone.role() == DroneRole.SECURITY
                ? CombatPolicy.sortieReady(drone.securityLoadout(), drone.getHealth() / drone.getMaxHealth(),
                        drone.batteryPercent(), drone.weaponPowerPercent(), drone.gunAmmo(), drone.missiles(), drone.laserHeat(), false)
                : DroneServicePolicy.nonCombatSortieReady(drone.getHealth() / drone.getMaxHealth(), drone.batteryPercent());
        String wing = OperationEvent.bounded(drone.groupId(), OperationEvent.TEXT_LIMIT);
        String number = (wing.isBlank() ? drone.unitId() : wing).replaceAll("[^0-9]", "");
        if (number.isBlank()) number = Integer.toString(Math.floorMod((wing.isBlank() ? drone.unitId() : wing).hashCode(), 100));
        if (number.length() > 3) number = number.substring(number.length() - 3);
        return new OperationObservation(drone.ownerId(), drone.getUUID(), missionKey(drone, salvage), wing,
                drone.level().dimension().toString(),
                OperationEvent.bounded(drone.hasCustomName() ? drone.getName().getString() : drone.unitId(), OperationEvent.TEXT_LIMIT),
                OperationEvent.bounded(number, OperationEvent.TEXT_LIMIT), drone.blockPosition().getX(),
                drone.blockPosition().getY(), drone.blockPosition().getZ(), targetId, targetAlive, states,
                drone.combatState().rejoining(), drone.serviceReturnActive(), drone.isDocked(), serviceReady,
                missionRunning && (drone.hasActiveFieldOperation() || drone.hasSecurityPatrol()
                        || drone.mode() == DroneMode.FOLLOW || drone.mode() == DroneMode.WAYPOINT
                        || drone.mode() == DroneMode.ORBIT || drone.cargoState() != CargoState.UNASSIGNED), drone.cargoItemCount());
    }

    private static String missionKey(DroneEntity drone, Entity salvage) {
        if (drone.hasFieldOperation()) return "field/" + drone.fieldOrderId() + "/" + drone.fieldOperationType()
                + "/" + drone.fieldAnchor().asLong() + "/" + drone.fieldRadius();
        if (drone.hasSecurityPatrol()) return "patrol/" + drone.securityOrderId()
                + "/" + drone.securityAnchor().asLong() + "/" + drone.securityRadius();
        if (salvage != null) return "salvage/" + salvage.getUUID();
        if (drone.hasCargoSource() && drone.hasCargoTarget()) {
            return "cargo/" + drone.cargoSource().asLong() + "/" + drone.cargoTarget().asLong() + "/" + drone.cargoPaused();
        }
        if (!drone.missionId().isBlank()) return "mission/" + drone.missionId()
                + "/" + (drone.hasTrackingTarget() ? drone.trackingTargetId()
                        : drone.hasPatrolRoute() ? drone.patrolRoutePoints()
                        : drone.hasWaypoint() ? drone.waypointPos().asLong() : drone.mode());
        return "mode/" + drone.mode().name() + "/" + drone.role().name();
    }

    private static final class Session {
        final UUID id = UUID.randomUUID();
        final String dimension;
        final OperationEventObserver observer;
        final OperationHistoryData history;
        long requestWindow;
        int requests;
        Session(UUID owner, String dimension, OperationHistoryData history) {
            this.dimension = dimension;
            this.observer = new OperationEventObserver(owner);
            this.history = history;
        }
    }
}
