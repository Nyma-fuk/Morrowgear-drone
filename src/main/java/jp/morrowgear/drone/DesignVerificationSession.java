package jp.morrowgear.drone;

import java.util.*;
import jp.morrowgear.drone.block.DockBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Opt-in fixture. Refuses to overwrite blocks and only commands its six commissioned units. */
final class DesignVerificationSession {
    private static DesignVerificationSession active;
    private final ServerPlayer player;
    private final ServerLevel level;
    private final List<DroneEntity> fleet = new ArrayList<>();
    private final List<BlockPos> docks = new ArrayList<>();
    private DesignRuntimeVerification runtime;

    private DesignVerificationSession(ServerPlayer player) { this.player = player; this.level = (ServerLevel) player.level(); }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(
            Commands.literal("morrowgear_design")
                .requires(source -> source.getEntity() instanceof ServerPlayer p && p.isCreative())
                .then(Commands.literal("prepare").executes(c -> {
                    if (!(c.getSource().getEntity() instanceof ServerPlayer p) || !p.isCreative()) return 0;
                    if (active != null && active.level == p.level() && active.fleet.stream().anyMatch(d -> !d.isRemoved())) {
                        active.say("Existing fixture retained / no additional drones spawned"); return 0;
                    }
                    if (active != null && active.runtime != null && active.runtime.running()) return 0;
                    if (fixtureDocks().stream().anyMatch(pos -> p.level().getBlockEntity(pos) instanceof DockBlockEntity)) {
                        p.sendSystemMessage(Component.literal("[MORROWGEAR DESIGN] Existing fixture retained; use attach. No drones spawned."));
                        return 0;
                    }
                    active = new DesignVerificationSession(p);
                    try { active.prepare(); return 1; } catch (RuntimeException e) { active.say("FAIL " + e.getMessage()); return 0; }
                }))
                .then(Commands.literal("attach").executes(c -> attach((ServerPlayer)c.getSource().getEntity())))
                .then(Commands.literal("flight").executes(c -> start((ServerPlayer)c.getSource().getEntity(), "flight")))
                .then(Commands.literal("service").executes(c -> start((ServerPlayer)c.getSource().getEntity(), "service")))
                .then(Commands.literal("route").executes(c -> start((ServerPlayer)c.getSource().getEntity(), "route")))
                .then(Commands.literal("laser").executes(c -> start((ServerPlayer)c.getSource().getEntity(), "laser")))
                .then(Commands.literal("missile").executes(c -> start((ServerPlayer)c.getSource().getEntity(), "missile")))
                .then(Commands.literal("stop").executes(c -> {
                    if (active == null || c.getSource().getEntity() != active.player) return 0;
                    if (active.runtime != null) active.runtime.stop("STOP requested");
                    return 1;
                }))
                .then(Commands.literal("status").executes(c -> {
                    if (active == null || c.getSource().getEntity() != active.player) return 0;
                    if (active.runtime != null) active.runtime.status();
                    else active.say("Attached " + active.fleet.size() + " aircraft; no verification running");
                    active.fleet.forEach(d -> org.slf4j.LoggerFactory.getLogger("morrowgear-design-verification")
                        .info("DESIGN fixture role={} unit={} mode={} docked={} pos={}", d.role(), d.unitId(), d.mode(), d.isDocked(), d.position()));
                    return 1;
                }))));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (active == null || active.runtime == null || active.level.getServer() != server) return;
            active.runtime.tick();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (active != null && active.level.getServer() == server) {
                if (active.runtime != null && active.runtime.running()) active.runtime.stop("ABORT server stopping; return order saved, landing unverified");
                active = null;
            }
        });
    }

    private static List<BlockPos> fixtureDocks() {
        List<BlockPos> result = new ArrayList<>();
        for (int z : new int[] {6008, 6017}) for (int x : new int[] {5993, 6000, 6007})
            result.add(new BlockPos(x, 120, z));
        return List.copyOf(result);
    }

    private static int attach(ServerPlayer player) {
        try {
            require(active == null || active.runtime == null || !active.runtime.running(), "stop/wait for current verification first");
            DesignVerificationSession candidate = new DesignVerificationSession(player);
            require(player.distanceToSqr(6000.5, 123, 6004.5) <= 48 * 48, "stand near the existing six-dock fixture");
            List<BlockPos> expected = fixtureDocks();
            Set<UUID> unique = new HashSet<>();
            for (int i = 0; i < expected.size(); i++) {
                BlockPos pos = expected.get(i);
                require(candidate.level.hasChunkAt(pos), "fixture chunk not loaded: " + pos);
                require(candidate.level.getBlockEntity(pos) instanceof DockBlockEntity dock
                    && dock.isOwnedBy(player.getUUID()), "owned Dock missing: " + pos);
                for (int n = 0; n < 25; n++) require(candidate.level.getBlockState(pos.offset(n % 5 - 2, 0, n / 5 - 2))
                    .is(MorrowgearDrone.WIDE_DOCK_PARTS[n]), "5x5 fixture Dock mismatch: " + pos);
                List<DroneEntity> matches = new ArrayList<>();
                for (var entity : candidate.level.getAllEntities())
                    if (entity instanceof DroneEntity d && !d.isRemoved() && d.hasDock() && d.dockPos().equals(pos)) matches.add(d);
                require(matches.size() == 1, "expected exactly one loaded drone bound to " + pos + "; found " + matches.size());
                DroneEntity drone = matches.getFirst();
                // UUID ownership is intentional: isOwnedBy(player) can adopt unrelated single-player units.
                require(drone.isOwnedBy(player.getUUID()) && drone.role() == DroneRole.values()[i]
                    && drone.distanceToSqr(player) <= 128 * 128 && unique.add(drone.getUUID()), "fixture identity/role/owner mismatch: " + pos);
                candidate.fleet.add(drone);
                candidate.docks.add(pos);
            }
            require(candidate.fleet.stream().anyMatch(d -> d.role() == DroneRole.SECURITY
                && d.unitId().equalsIgnoreCase("MG-DRN-FD8C54")), "existing Security FD8C54 not found");
            active = candidate;
            active.say("ATTACHED existing 6 roles / no spawn / user groups unchanged / service | flight | route | laser | missile | stop | status");
            return 1;
        } catch (RuntimeException error) {
            player.sendSystemMessage(Component.literal("[MORROWGEAR DESIGN] REFUSED attach: " + error.getMessage()));
            return 0;
        }
    }

    private static int start(ServerPlayer player, String kind) {
        if (active == null || active.player != player || active.level != player.level()) {
            player.sendSystemMessage(Component.literal("[MORROWGEAR DESIGN] Use attach first beside the existing fixture."));
            return 0;
        }
        if (active.runtime != null && active.runtime.running()) { active.say("Verification/return already running; use status or stop"); return 0; }
        active.runtime = new DesignRuntimeVerification(player, active.fleet, active.docks, active::say);
        return active.runtime.start(kind) ? 1 : 0;
    }

    private void prepare() {
        BlockPos base = player.blockPosition().offset(-7, -1, 8);
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-3, 0, -3), base.offset(17, 7, 12)))
            require(level.getBlockState(p).isAir(), "fixture needs empty air above terrain: " + p);
        for (int x = -3; x <= 17; x++) for (int z = -3; z <= 12; z++)
            level.setBlockAndUpdate(base.offset(x, 0, z), Blocks.IRON_BLOCK.defaultBlockState());
        for (int i = 0; i < DroneRole.values().length; i++) {
            BlockPos center = base.offset(i % 3 * 7, 1, i / 3 * 9);
            for (int n = 0; n < 25; n++) level.setBlockAndUpdate(center.offset(n % 5 - 2, 0, n / 5 - 2), MorrowgearDrone.WIDE_DOCK_PARTS[n].defaultBlockState());
            DockBlockEntity dock = (DockBlockEntity) level.getBlockEntity(center);
            dock.initialize(player);
            DroneRole role = DroneRole.values()[i];
            var module = MorrowgearDrone.moduleForRole(role);
            require(MorrowgearDrone.deployAtDock(player, new ItemStack(MorrowgearDrone.DRONE_UNIT), center,
                new ItemStack(MorrowgearDrone.STANDARD_BATTERY_PACK), module == null ? ItemStack.EMPTY : new ItemStack(module), ItemStack.EMPTY), "commission " + role);
            DroneEntity drone = level.getEntitiesOfClass(DroneEntity.class, new AABB(center).inflate(3),
                d -> d.isOwnedBy(player) && d.hasDock() && d.dockPos().equals(center)).getFirst();
            require(drone.role() == role && drone.isDocked(), "role / docking " + role);
            require(Math.abs(drone.getBbWidth() - 3) < .001, "aircraft width");
            require(Math.abs(drone.getY() - center.getY() - .316) < .001, "initial contact height");
            require(level.noCollision(drone, drone.getBoundingBox().deflate(.002)), "deck intersection " + role);
            drone.assignGroup("DESIGN-V23"); fleet.add(drone); docks.add(center);
            say("PASS commissioned " + role + " / 3x3 envelope / 5x5 Dock / clear contact");
        }
        say("PREPARED 6 roles / use flight to run takeoff and return / UI remains available");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private void say(String text) {
        player.sendSystemMessage(Component.literal("[MORROWGEAR DESIGN] " + text));
        org.slf4j.LoggerFactory.getLogger("morrowgear-design-verification").info(text);
    }
}
