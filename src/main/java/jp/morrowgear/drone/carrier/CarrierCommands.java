package jp.morrowgear.drone.carrier;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class CarrierCommands {
    private CarrierCommands() {}
    /** Server-thread entry for the normal menu packet, including every ownership and preview check. */
    public static void handle(CarrierCommandPayload packet, ServerPlayer player) {
        if (!(player.containerMenu instanceof CarrierMenu menu) || menu.containerId != packet.menu()
            || !menu.shipId.equals(packet.ship()) || !menu.stillValid(player)) return;
        var server = player.level().getServer();
        CarrierShip ship = CarrierSavedData.get(server).ship(packet.ship());
        if (ship == null) return;
        boolean owner = ship.owner.equals(player.getUUID());
        var action = packet.action();
        if (action == CarrierCommandPayload.Action.EXIT) { CarrierInterior.exit(player); return; }
        if (action == CarrierCommandPayload.Action.STOP) {
            if (owner) {
                ship.arrivalOperation = CarrierPolicy.Mode.IDLE;
                ship.stop(CarrierPolicy.Stop.EMERGENCY);
                CarrierEntity exterior = CarrierModule.find(server, packet.ship());
                if (exterior != null) {
                    exterior.clearBeams();
                    ship.bay.cancelAll(exterior, CarrierServiceBay.Release.CANCELLED);
                }
            }
            menu.sync(player);
            return;
        }
        if (!menu.rateLimit(server.getTickCount())) return;
        if (action == CarrierCommandPayload.Action.BOARD) { CarrierInterior.board(player, packet.ship(), false); return; }
        if (!owner) return;
        if (action == CarrierCommandPayload.Action.CARGO_PAGE) {
            menu.changeCargoPage(player, packet.x(), packet.y(), packet.z());
            return;
        }
        if (action == CarrierCommandPayload.Action.RECOVER_CABIN) { CarrierInterior.board(player, packet.ship(), true); return; }
        if (action == CarrierCommandPayload.Action.ALLOW_GUEST) {
            if (ship.guests.size() < 32 && !packet.guest().equals(CarrierCommandPayload.NONE)
                && server.getPlayerList().getPlayer(packet.guest()) != null) ship.guests.add(packet.guest());
        } else if (action == CarrierCommandPayload.Action.REVOKE_GUEST) {
            // Do not revoke a passenger's only safe return path. They retain the visit until evacuated.
            ServerPlayer guest = server.getPlayerList().getPlayer(packet.guest());
            if (guest != null && packet.ship().equals(CarrierInterior.currentShip(guest)) && !CarrierInterior.exit(guest)) return;
            ship.guests.remove(packet.guest());
        } else if (action == CarrierCommandPayload.Action.REFUEL || action == CarrierCommandPayload.Action.CHARGE_WEAPON) {
            if (ship.destroyed) return;
            boolean weapon = action == CarrierCommandPayload.Action.CHARGE_WEAPON;
            if (CarrierSupplies.charge(ship, weapon)) { menu.sync(player); return; }
            var cell = BuiltInRegistries.ITEM.getValue(CarrierModule.id(weapon ? "laser_cell" : "power_cell"));
            if (cell == net.minecraft.world.item.Items.AIR) return;
            if (CarrierSupplies.canCharge(weapon ? ship.weaponEnergy : ship.energy, weapon)) {
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    var stack = player.getInventory().getItem(slot);
                    if (!stack.is(cell)) continue;
                    stack.shrink(1);
                    player.getInventory().setChanged();
                    if (weapon) ship.weaponEnergy += CarrierSupplies.cellEnergy(true);
                    else ship.energy += CarrierSupplies.cellEnergy(false);
                    break;
                }
            }
        } else {
            CarrierEntity entity = CarrierModule.find(server, packet.ship());
            boolean handshake = action == CarrierCommandPayload.Action.MOVE
                || action == CarrierCommandPayload.Action.MOVE_MINING
                || action == CarrierCommandPayload.Action.MOVE_COMBAT
                || action == CarrierCommandPayload.Action.MOVE_MISSILE_COMBAT
                || action == CarrierCommandPayload.Action.PREVIEW_MINING
                || action == CarrierCommandPayload.Action.PREVIEW_COMBAT
                || action == CarrierCommandPayload.Action.PREVIEW_MISSILE_COMBAT
                || action == CarrierCommandPayload.Action.RESUME_PREVIEW
                || action == CarrierCommandPayload.Action.ACTIVATE;
            handshake = handshake || action == CarrierCommandPayload.Action.LOAD_CONTAINER
                || action == CarrierCommandPayload.Action.UNLOAD_CONTAINER
                || action == CarrierCommandPayload.Action.CANCEL_CONTAINER_TRANSFER;
            if (entity == null || ship.destroyed || !entity.controls(player)) {
                if (handshake) reject(ship, menu, player, CarrierPolicy.Stop.CONTROL_LOST);
                return;
            }
            ServerLevel level = (ServerLevel) entity.level();
            if (action == CarrierCommandPayload.Action.RESERVE_BAY) {
                CarrierModule.reserveBay(player, packet.ship(), packet.guest());
            } else if (action == CarrierCommandPayload.Action.RELEASE_BAY) {
                var port = CarrierModule.dronePorts().find(entity, packet.guest());
                if (ship.bay.release(packet.guest()) && port != null) port.release(CarrierServiceBay.Release.CANCELLED);
            } else if (action == CarrierCommandPayload.Action.LOAD_CONTAINER) {
                String result=entity.requestContainerLoad(player,new BlockPos(packet.x(),packet.y(),packet.z()));
                if(!"ACCEPTED".equals(result)){reject(ship,menu,player,CarrierPolicy.Stop.INVALID_TARGET);return;}
            } else if (action == CarrierCommandPayload.Action.UNLOAD_CONTAINER) {
                BlockPos target;
                if(packet.y()==Integer.MIN_VALUE){
                    var side=entity.toWorld(new net.minecraft.world.phys.Vec3(30,0,0));
                    int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                        net.minecraft.util.Mth.floor(side.x),net.minecraft.util.Mth.floor(side.z));
                    target=new BlockPos(net.minecraft.util.Mth.floor(side.x),y,net.minecraft.util.Mth.floor(side.z));
                } else target=new BlockPos(packet.x(),packet.y(),packet.z());
                String result=entity.requestContainerUnload(player,packet.generation(),target);
                if(!"ACCEPTED".equals(result)){reject(ship,menu,player,CarrierPolicy.Stop.INVALID_TARGET);return;}
            } else if (action == CarrierCommandPayload.Action.CANCEL_CONTAINER_TRANSFER) {
                String result=entity.cancelContainerTransfer(player);
                if(!"ACCEPTED".equals(result)){reject(ship,menu,player,CarrierPolicy.Stop.INVALID_TARGET);return;}
            } else if (action == CarrierCommandPayload.Action.MOVE
                || action == CarrierCommandPayload.Action.MOVE_MINING
                || action == CarrierCommandPayload.Action.MOVE_COMBAT
                || action == CarrierCommandPayload.Action.MOVE_MISSILE_COMBAT) {
                if (Math.abs((long) packet.x()) > 29_999_000 || Math.abs((long) packet.z()) > 29_999_000
                    || packet.y() < level.getMinY() || packet.y() >= level.getMaxY()) {
                    reject(ship, menu, player, CarrierPolicy.Stop.INVALID_TARGET); return;
                }
                if (ship.mode != CarrierPolicy.Mode.IDLE) { reject(ship, menu, player, CarrierPolicy.Stop.BUSY); return; }
                CarrierPolicy.Mode arrival = action == CarrierCommandPayload.Action.MOVE_MINING
                    ? CarrierPolicy.Mode.MINING : action == CarrierCommandPayload.Action.MOVE_COMBAT
                        || action == CarrierCommandPayload.Action.MOVE_MISSILE_COMBAT
                    ? CarrierPolicy.Mode.COMBAT : CarrierPolicy.Mode.IDLE;
                CarrierPolicy.CombatWeapon weapon = action == CarrierCommandPayload.Action.MOVE_MISSILE_COMBAT
                    ? CarrierPolicy.CombatWeapon.MISSILE : CarrierPolicy.CombatWeapon.LASER;
                if (!entity.setDestination(player, new BlockPos(packet.x(), packet.y(), packet.z()), arrival, weapon)) {
                    reject(ship, menu, player, CarrierPolicy.Stop.INVALID_TARGET); return;
                }
            } else if (action == CarrierCommandPayload.Action.PREVIEW_MINING
                || action == CarrierCommandPayload.Action.PREVIEW_COMBAT
                || action == CarrierCommandPayload.Action.PREVIEW_MISSILE_COMBAT) {
                if (Math.floorDiv(packet.x(), 16) != entity.blockPosition().getX() >> 4
                    || Math.floorDiv(packet.z(), 16) != entity.blockPosition().getZ() >> 4) {
                    reject(ship, menu, player, CarrierPolicy.Stop.INVALID_TARGET); return;
                }
                CarrierPolicy.Mode previewMode = action == CarrierCommandPayload.Action.PREVIEW_MINING
                    ? CarrierPolicy.Mode.MINING : CarrierPolicy.Mode.COMBAT;
                ship.combatWeapon = action == CarrierCommandPayload.Action.PREVIEW_MISSILE_COMBAT
                    ? CarrierPolicy.CombatWeapon.MISSILE : CarrierPolicy.CombatWeapon.LASER;
                if (!preparePreview(ship, level, entity, previewMode, packet.x(), packet.z(), server.getTickCount())) {
                    reject(ship, menu, player, CarrierPolicy.Stop.INVALID_TARGET); return;
                }
            } else if (action == CarrierCommandPayload.Action.RESUME_PREVIEW) {
                if (ship.progress == null || ship.progress.complete()) {
                    reject(ship, menu, player, CarrierPolicy.Stop.INVALID_TARGET); return;
                }
                ship.stop(CarrierPolicy.Stop.PREVIEW);
                ship.preview = ship.progress.operation();
                ship.previewMode = CarrierPolicy.Mode.MINING;
                ship.previewUntil = (long) server.getTickCount() + CarrierPolicy.PREVIEW_TICKS;
            } else if (action == CarrierCommandPayload.Action.ACTIVATE) {
                var op = ship.preview;
                boolean loaded = footprintLoaded(level, op);
                if (!CarrierPolicy.canActivate(packet.generation(), op, server.getTickCount(), ship.previewUntil,
                    owner, loaded, entity.controls(player))) {
                    reject(ship, menu, player, CarrierPolicy.Stop.EXPIRED); return;
                }
                if (!CarrierPolicy.aligned(op, level.dimension().identifier().toString(), entity.getX(), entity.getY(), entity.getZ())) {
                    reject(ship, menu, player, CarrierPolicy.Stop.INVALID_TARGET); return;
                }
                if (ship.previewMode == CarrierPolicy.Mode.MINING) {
                    if (ship.progress == null || !ship.progress.operation().equals(op)) {
                        ship.progress = new CarrierPolicy.Progress(op, 0, List.of());
                        ship.pending = null;
                        ship.miningStartupCharged = false;
                    }
                } else {
                    ship.activeCombat = op;
                    ship.combatRemaining = CarrierPolicy.combatDuration(ship.combatWeapon);
                    ship.combatCursor = 0;
                }
                ship.mode = ship.previewMode;
                ship.arrivalOperation = CarrierPolicy.Mode.IDLE;
                ship.stop = CarrierPolicy.Stop.RUNNING;
                ship.preview = null;
                ship.previewUntil = 0;
            }
            if (handshake) ship.commandRevision = ship.commandRevision == Integer.MAX_VALUE ? 1 : ship.commandRevision + 1;
        }
        ship.dirty.run();
        menu.sync(player);
    }

    static boolean preparePreview(CarrierShip ship, ServerLevel level, CarrierEntity entity,
                                  CarrierPolicy.Mode mode, int blockX, int blockZ, long tick) {
        if (mode != CarrierPolicy.Mode.MINING && mode != CarrierPolicy.Mode.COMBAT) return false;
        int top = Math.min(level.getMaxY() - 1, CarrierPolicy.operationTop(entity.getY()));
        if (top < level.getMinY()) return false;
        ship.stop(CarrierPolicy.Stop.PREVIEW);
        ship.previewMode = mode;
        if (mode == CarrierPolicy.Mode.MINING) {
            var mining = CarrierPolicy.miningOperation(UUID.randomUUID(), level.dimension().identifier().toString(),
                blockX, blockZ, entity.getY(), level.getMinY(), level.getMaxY());
            int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                mining.chunkX() * 16 + 8, mining.chunkZ() * 16 + 8);
            ship.preview = mining.withSurfaceY(surface);
        } else {
            var combat = CarrierPolicy.Operation.at(UUID.randomUUID(), level.dimension().identifier().toString(),
                blockX, blockZ, level.getMinY(), top);
            int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                combat.chunkX() * 16 + 8, combat.chunkZ() * 16 + 8);
            ship.preview = combat.withSurfaceY(surface);
        }
        ship.previewUntil = tick + CarrierPolicy.PREVIEW_TICKS;
        ship.dirty.run();
        return true;
    }
    private static void reject(CarrierShip ship, CarrierMenu menu, ServerPlayer player, CarrierPolicy.Stop reason) {
        if (ship.mode == CarrierPolicy.Mode.IDLE) ship.stop = reason;
        ship.commandRevision = ship.commandRevision == Integer.MAX_VALUE ? 1 : ship.commandRevision + 1;
        ship.dirty.run();
        menu.sync(player);
    }

    private static boolean footprintLoaded(ServerLevel level, CarrierPolicy.Operation operation) {
        if (operation == null) return false;
        int minChunkX = Math.floorDiv(operation.minX(), 16);
        int maxChunkX = Math.floorDiv(operation.maxX(), 16);
        int minChunkZ = Math.floorDiv(operation.minZ(), 16);
        int maxChunkZ = Math.floorDiv(operation.maxZ(), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) return false;
            }
        }
        return true;
    }
}
