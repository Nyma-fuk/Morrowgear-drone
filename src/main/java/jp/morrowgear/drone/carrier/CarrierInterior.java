package jp.morrowgear.drone.carrier;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;

public final class CarrierInterior {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, CarrierModule.id("carrier_interior"));
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
    private static final Map<UUID, BoardingRearm> AUTO_BOARD_REARM = new HashMap<>();
    private CarrierInterior() {}
    public static boolean inside(Level level) { return level.dimension().equals(DIMENSION); }
    public static boolean allowedItem(ItemStack stack) {
        if (stack.isEmpty() || stack.is(CarrierModule.CONSOLE_ITEM)) return true;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!id.startsWith("minecraft:")) return false;
        if (id.equals("minecraft:chorus_fruit")) return false;
        if (stack.getItem() instanceof BlockItem block) {
            String blockId = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            return !blockId.contains("piston") && !blockId.contains("dispenser") && !blockId.contains("dropper")
                && !blockId.contains("tnt") && !blockId.contains("respawn_anchor") && !blockId.contains("command_block")
                && !blockId.contains("structure_block") && !blockId.contains("jigsaw") && !blockId.contains("portal");
        }
        // Buckets, pearls, ignition, eggs and mod teleport items are deliberately not usable here.
        return stack.has(DataComponents.FOOD) || stack.has(DataComponents.TOOL)
            || id.equals("minecraft:bone_meal") || id.equals("minecraft:shears");
    }
    public static void reset() { COOLDOWN.clear(); AUTO_BOARD_REARM.clear(); }
    public static void deferBoarding(ServerPlayer player) {
        COOLDOWN.put(player.getUUID(), (long) player.level().getServer().getTickCount() + 60);
    }
    static void touchBoarding(CarrierEntity carrier) {
        if (!(carrier.level() instanceof ServerLevel level) || carrier.tickCount % 5 != 0) return;
        CarrierShip ship = carrier.ship();
        if (ship == null || ship.destroyed) return;
        for (ServerPlayer player : java.util.List.copyOf(level.players())) {
            if (!ship.permits(player.getUUID()) || !ready(player) || !automaticBoardingReady(player)
                || !carrier.boardingZone(player)) continue;
            BlockPos pad = boardingPad(carrier, player);
            if (pad != null && player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(pad)) <= 1.2 * 1.2)
                board(player, carrier.getUUID(), false);
        }
    }
    public static BlockPos boardingPad(CarrierEntity carrier, ServerPlayer player) {
        if (carrier.level() != player.level()) return null;
        // Rotated aft belly projection; explicitly exclude the active mining column.
        BlockPos center = BlockPos.containing(carrier.boardingProjection());
        ServerLevel level = player.level();
        BlockPos nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int x = center.getX() + dx, z = center.getZ() + dz;
            if (!carrier.inBoardingProjection(new net.minecraft.world.phys.Vec3(x + .5, carrier.getY(), z + .5))
                || level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
            BlockPos pad = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            double gap = carrier.getY() - pad.getY();
            double candidate = player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(pad));
            if (gap > 4 && gap <= 96 && candidate < distance && carrier.outsideWorkColumn(pad) && safe(level, pad, player)) {
                nearest = pad;
                distance = candidate;
            }
        }
        return nearest;
    }

    public static boolean board(ServerPlayer player, UUID shipId, boolean recovery) {
        MinecraftServer server = player.level().getServer();
        CarrierSavedData data = CarrierSavedData.get(server);
        CarrierShip ship = data.ship(shipId);
        if (ship == null || !ship.permits(player.getUUID()) || inside(player.level()) || player.isPassenger()
            || player.isSpectator() || !player.isAlive() || !ready(player)) return false;
        CarrierEntity entity = CarrierModule.find(server, shipId);
        if (recovery) {
            if (!ship.destroyed || !ship.owner.equals(player.getUUID())) return false;
        } else {
            if (entity == null || ship.destroyed || !entity.boardingZone(player)) return false;
            BlockPos pad = boardingPad(entity, player);
            if (pad == null || player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(pad)) > 1.2 * 1.2) return false;
        }
        // Boarding cancels both work and transit before any cabin load is requested.
        ship.stop(CarrierPolicy.Stop.EMERGENCY);
        ServerLevel interior = server.getLevel(DIMENSION);
        if (interior == null || !safe(player.level(), player.blockPosition(), player)) return false;
        CarrierAnchor returnTo = CarrierAnchor.at(player);
        initialize(interior, ship);
        maintainEntry(interior, ship);
        BlockPos entry = safeEntry(interior, ship, player);
        if (entry == null || !teleport(player, interior, entry)) return false;
        data.visit(player.getUUID(), new CarrierSavedData.Visit(shipId, returnTo, recovery));
        // Explicit BOARD is allowed to override an exit latch once all normal checks succeed.
        AUTO_BOARD_REARM.remove(player.getUUID());
        COOLDOWN.put(player.getUUID(), (long) server.getTickCount() + 40);
        return true;
    }

    static boolean ready(ServerPlayer player) {
        return player.level().getServer().getTickCount() >= COOLDOWN.getOrDefault(player.getUUID(), 0L);
    }

    public static boolean exit(ServerPlayer player) {
        if (!inside(player.level()) || !ready(player)) return false;
        // Failed exit requests are throttled too; they can inspect several hundred surface candidates.
        COOLDOWN.put(player.getUUID(), (long) player.level().getServer().getTickCount() + 10);
        CarrierSavedData data = CarrierSavedData.get(player.level().getServer());
        CarrierSavedData.Visit visit = data.visit(player.getUUID());
        if (visit == null) return rescueUnassigned(player, null);
        CarrierShip ship = data.ship(visit.ship());
        CarrierEntity exterior = CarrierModule.find(player.level().getServer(), visit.ship());
        if (exterior != null && ship != null && !ship.destroyed) {
            ServerLevel level = (ServerLevel) exterior.level();
            BlockPos surface = nearbySurface(level, BlockPos.containing(exterior.boardingProjection()), player,
                pos -> exterior.toLocal(net.minecraft.world.phys.Vec3.atBottomCenterOf(pos)).z >= 16
                    && exterior.outsideWorkColumn(pos));
            if (surface != null && exitTo(player, level, surface, visit.ship())) return true;
        }
        ServerLevel returnLevel = visit.returnTo().level(player.level().getServer());
        if (returnLevel != null && !inside(returnLevel)) {
            BlockPos target = visit.returnTo().pos();
            // This is a previously occupied, saved return chunk, not a navigation frontier.
            returnLevel.getChunk(target.getX() >> 4, target.getZ() >> 4);
            if (safe(returnLevel, target, player) && exitTo(player, returnLevel, target, visit.ship())) {
                return true;
            }
            BlockPos surface = nearbySurface(returnLevel, target, player);
            if (surface != null && exitTo(player, returnLevel, surface, visit.ship())) return true;
        }
        // Keep the cabin and return record accessible when all loaded destinations are unsafe.
        if (rescueUnassigned(player, visit.ship())) return true;
        if (ship != null) ship.stop(CarrierPolicy.Stop.NO_SAFE_EXIT);
        return false;
    }

    private static boolean rescueUnassigned(ServerPlayer player, UUID shipId) {
        var server = player.level().getServer();
        var respawn = server.getRespawnData();
        ServerLevel level = server.getLevel(respawn.dimension());
        if (level == null || inside(level)) level = server.overworld();
        // A saved world-spawn chunk is a bounded rescue destination, never a moving frontier.
        level.getChunk(respawn.pos().getX() >> 4, respawn.pos().getZ() >> 4);
        BlockPos target = nearbySurface(level, respawn.pos(), player);
        if (target == null) return false;
        return exitTo(player, level, target, shipId);
    }

    public static void recover(ServerPlayer player) {
        if (!inside(player.level()) || !ready(player)) return;
        var data = CarrierSavedData.get(player.level().getServer());
        var visit = data.visit(player.getUUID());
        if (visit == null) {
            visit = data.assignment(player.getUUID());
            if (visit != null) data.visit(player.getUUID(), visit);
        }
        CarrierShip ship = visit == null ? null : data.ship(visit.ship());
        if (ship == null || !ship.permits(player.getUUID())) { exit(player); return; }
        initialize(player.level(), ship);
        maintainEntry(player.level(), ship);
        BlockPos entry = safeEntry(player.level(), ship, player);
        if (entry != null) teleport(player, player.level(), entry);
        else COOLDOWN.put(player.getUUID(), (long) player.level().getServer().getTickCount() + 10);
    }

    public static void tick(MinecraftServer server) {
        var data = CarrierSavedData.get(server);
        if (server.getTickCount() % 5 == 0) {
            AUTO_BOARD_REARM.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!inside(player.level())) automaticBoardingReady(player);
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!inside(player.level()) || !player.isAlive()) continue;
            var visit = data.visit(player.getUUID());
            if (visit == null) { recover(player); continue; }
            var ship = visit == null ? null : data.ship(visit.ship());
            if (ship == null || !ship.permits(player.getUUID())) {
                if (server.getTickCount() % 20 == 0) exit(player);
                continue;
            }
            BlockPos pos = player.blockPosition();
            if (server.getTickCount() % 20 == 0) maintainEntry(player.level(), ship);
            if (!CarrierPolicy.inCabin(ship.cabin, pos.getX(), pos.getY(), pos.getZ())) recover(player);
            BlockPos exit = exitPad(ship);
            if (ready(player) && player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(exit)) < 1)
                exit(player);
            if (ship.destroyed && !visit.recovery() && server.getTickCount() % 20 == 0) exit(player);
        }
    }

    public static boolean allowed(ServerPlayer player, BlockPos target, boolean building) {
        if (!inside(player.level())) return true;
        var data = CarrierSavedData.get(player.level().getServer());
        var visit = data.visit(player.getUUID());
        var ship = visit == null ? null : data.ship(visit.ship());
        return ship != null && ship.permits(player.getUUID())
            && CarrierPolicy.inCabin(ship.cabin, player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ())
            && CarrierPolicy.inCabin(ship.cabin, target.getX(), target.getY(), target.getZ())
            && (!building || !CarrierPolicy.reservedEntry(ship.cabin, target.getX(), target.getY(), target.getZ()));
    }

    static boolean allowedPlacement(ServerPlayer player, BlockItem item,
                                     net.minecraft.world.item.context.BlockPlaceContext context) {
        var actual = item.updatePlacementContext(context);
        if (actual == null) return false;
        BlockPos target = actual.getClickedPos();
        if (!allowed(player, target, true)) return false;
        for (BlockPos pos : placementFootprint(item.getBlock(), target, actual.getHorizontalDirection())) {
            if (!allowed(player, pos, true)) return false;
        }
        return true;
    }

    static java.util.List<BlockPos> placementFootprint(Block block, BlockPos pos, Direction facing) {
        if (block instanceof net.minecraft.world.level.block.BedBlock)
            return java.util.List.of(pos, pos.relative(facing));
        if (block instanceof net.minecraft.world.level.block.DoorBlock
            || block instanceof net.minecraft.world.level.block.DoublePlantBlock)
            return java.util.List.of(pos, pos.above());
        return java.util.List.of(pos);
    }

    public static UUID currentShip(ServerPlayer player) {
        if (!inside(player.level()) || !player.isAlive() || player.isSpectator()) return null;
        var data = CarrierSavedData.get(player.level().getServer());
        var visit = data.visit(player.getUUID());
        var ship = visit == null ? null : data.ship(visit.ship());
        var pos = player.blockPosition();
        return ship != null && ship.permits(player.getUUID())
            && CarrierPolicy.inCabin(ship.cabin, pos.getX(), pos.getY(), pos.getZ()) ? visit.ship() : null;
    }

    public static BlockPos entry(CarrierShip ship) {
        return new BlockPos(CarrierPolicy.cabinX(ship.cabin) + 2, 65, CarrierPolicy.cabinZ(ship.cabin) + 2);
    }
    public static BlockPos exitPad(CarrierShip ship) {
        return new BlockPos(CarrierPolicy.cabinX(ship.cabin) + 13, 65, CarrierPolicy.cabinZ(ship.cabin) + 13);
    }

    private static BlockPos safeEntry(ServerLevel level, CarrierShip ship, ServerPlayer player) {
        BlockPos center = entry(ship);
        if (safe(level, center, player)) return center;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos candidate = center.offset(dx, 0, dz);
            if (safe(level, candidate, player)) return candidate;
        }
        return null;
    }

    private static void initialize(ServerLevel level, CarrierShip ship) {
        if (ship.cabinReady) return;
        int x = CarrierPolicy.cabinX(ship.cabin), z = CarrierPolicy.cabinZ(ship.cabin);
        // One explicit authenticated allocation, one chunk, at most 3,328 inspected positions.
        level.getChunk(x >> 4, z >> 4);
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) for (int y = 64; y <= 76; y++) {
            if (dx != 0 && dx != 15 && dz != 0 && dz != 15 && y != 64 && y != 76) continue;
            BlockPos pos = new BlockPos(x + dx, y, z + dz);
            if (level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(new BlockPos(x + 2, 76, z + 2), Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
        ship.cabinReady = true;
        ship.dirty.run();
    }

    private static void maintainEntry(ServerLevel level, CarrierShip ship) {
        maintainPad(level, entry(ship));
        maintainPad(level, exitPad(ship));
    }
    private static void maintainPad(ServerLevel level, BlockPos entry) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            level.setBlock(entry.offset(dx, -1, dz), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            // Indirect growth must not trap occupants or silently erase its drops/contents.
            for (int dy = 0; dy <= 3; dy++) {
                BlockPos pos = entry.offset(dx, dy, dz);
                if (!level.getBlockState(pos).isAir()) level.destroyBlock(pos, true);
            }
        }
    }

    static BlockPos nearbySurface(ServerLevel level, BlockPos center, ServerPlayer player) {
        return nearbySurface(level, center, player, pos -> true);
    }
    private static BlockPos nearbySurface(ServerLevel level, BlockPos center, ServerPlayer player,
                                           java.util.function.Predicate<BlockPos> permitted) {
        for (int r = 0; r <= 8; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
            int x = center.getX() + dx, z = center.getZ() + dz;
            if (level.getChunkSource().getChunkNow(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) == null) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (permitted.test(pos) && safe(level, pos, player)) return pos;
        }
        return null;
    }

    static boolean safe(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (pos.getY() <= level.getMinY() || pos.getY() + 2 >= level.getMaxY()
            || !level.getWorldBorder().isWithinBounds(pos)
            || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return false;
        var floor = level.getBlockState(pos.below());
        if (!floor.isFaceSturdy(level, pos.below(), Direction.UP) || !floor.getFluidState().isEmpty()
            || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CACTUS) || floor.is(Blocks.CAMPFIRE)
            || floor.is(Blocks.SOUL_CAMPFIRE) || floor.is(Blocks.POWDER_SNOW)) return false;
        for (int y = 0; y <= 2; y++) {
            var state = level.getBlockState(pos.above(y));
            if (!state.isAir()) return false;
        }
        AABB body = new AABB(pos.getX() + .2, pos.getY(), pos.getZ() + .2,
            pos.getX() + .8, pos.getY() + 1.9, pos.getZ() + .8);
        return level.noCollision(player, body) && level.getEntities(player, body, e -> e.isAlive()).isEmpty();
    }

    private static boolean teleport(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!safe(level, pos, player)) return false;
        boolean departing = inside(player.level()) && !inside(level);
        player.closeContainer();
        boolean moved = player.teleportTo(level, pos.getX() + .5, pos.getY(), pos.getZ() + .5,
            Set.of(), player.getYRot(), player.getXRot(), true);
        if (moved) {
            if (departing) CarrierSavedData.get(level.getServer()).leave(player.getUUID());
            player.setDeltaMovement(0, 0, 0); player.fallDistance = 0;
            COOLDOWN.put(player.getUUID(), (long) player.level().getServer().getTickCount() + 40);
        }
        return moved;
    }

    private static boolean exitTo(ServerPlayer player, ServerLevel level, BlockPos pos, UUID shipId) {
        if (!teleport(player, level, pos)) return false;
        if (shipId != null) AUTO_BOARD_REARM.put(player.getUUID(), new BoardingRearm(shipId, level.dimension()));
        return true;
    }

    private static boolean automaticBoardingReady(ServerPlayer player) {
        BoardingRearm latch = AUTO_BOARD_REARM.get(player.getUUID());
        if (latch == null) return true;
        boolean sameDimension = player.level().dimension().equals(latch.dimension());
        CarrierEntity carrier = sameDimension ? CarrierModule.find(player.level().getServer(), latch.ship()) : null;
        boolean carrierAvailable = carrier != null && carrier.level() == player.level()
            && carrier.ship() != null && !carrier.ship().destroyed;
        boolean inBoardingZone = carrierAvailable && carrier.boardingZone(player);
        if (!rearmComplete(sameDimension, carrierAvailable, inBoardingZone)) return false;
        AUTO_BOARD_REARM.remove(player.getUUID(), latch);
        return true;
    }

    static boolean rearmComplete(boolean sameDimension, boolean carrierAvailable, boolean inBoardingZone) {
        return !sameDimension || !carrierAvailable || !inBoardingZone;
    }

    private record BoardingRearm(UUID ship, ResourceKey<Level> dimension) {}
}
