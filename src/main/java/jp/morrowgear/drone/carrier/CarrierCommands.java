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
            if (CarrierSupplies.canCharge(weapon ? ship.weaponEnergy : ship.energy)) {
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    var stack = player.getInventory().getItem(slot);
                    if (!stack.is(cell)) continue;
                    stack.shrink(1);
                    player.getInventory().setChanged();
                    if (weapon) ship.weaponEnergy += 1000; else ship.energy += 1000;
                    break;
                }
            }
        } else {
            CarrierEntity entity = CarrierModule.find(server, packet.ship());
            boolean handshake = action == CarrierCommandPayload.Action.MOVE
                || action == CarrierCommandPayload.Action.PREVIEW_MINING
                || action == CarrierCommandPayload.Action.PREVIEW_COMBAT
                || action == CarrierCommandPayload.Action.RESUME_PREVIEW
                || action == CarrierCommandPayload.Action.ACTIVATE;
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
            } else if (action == CarrierCommandPayload.Action.MOVE) {
                if (Math.abs((long) packet.x()) > 29_999_000 || Math.abs((long) packet.z()) > 29_999_000
                    || packet.y() < level.getMinY() || packet.y() >= level.getMaxY()) {
                    reject(ship, menu, player, CarrierPolicy.Stop.INVALID_TARGET); return;
                }
                if (ship.mode != CarrierPolicy.Mode.IDLE) { reject(ship, menu, player, CarrierPolicy.Stop.BUSY); return; }
                if (!entity.setDestination(player, new BlockPos(packet.x(), packet.y(), packet.z()))) {
                    reject(ship, menu, player, CarrierPolicy.Stop.INVALID_TARGET); return;
                }
            } else if (action == CarrierCommandPayload.Action.PREVIEW_MINING || action == CarrierCommandPayload.Action.PREVIEW_COMBAT) {
                if (Math.floorDiv(packet.x(), 16) != entity.blockPosition().getX() >> 4
                    || Math.floorDiv(packet.z(), 16) != entity.blockPosition().getZ() >> 4) {
                    reject(ship, menu, player, CarrierPolicy.Stop.INVALID_TARGET); return;
                }
                ship.stop(CarrierPolicy.Stop.PREVIEW);
                int top = Math.min(level.getMaxY() - 1, CarrierPolicy.operationTop(entity.getY()));
                if (top < level.getMinY()) { reject(ship, menu, player, CarrierPolicy.Stop.INVALID_TARGET); return; }
                ship.previewMode = action == CarrierCommandPayload.Action.PREVIEW_MINING ? CarrierPolicy.Mode.MINING : CarrierPolicy.Mode.COMBAT;
                ship.preview = ship.previewMode == CarrierPolicy.Mode.MINING
                    ? CarrierPolicy.miningOperation(UUID.randomUUID(), level.dimension().identifier().toString(),
                        packet.x(), packet.z(), entity.getY(), level.getMinY(), level.getMaxY())
                    : CarrierPolicy.Operation.at(UUID.randomUUID(), level.dimension().identifier().toString(),
                        packet.x(), packet.z(), level.getMinY(), top);
                ship.previewUntil = (long) server.getTickCount() + CarrierPolicy.PREVIEW_TICKS;
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
                boolean loaded = op != null && level.getChunkSource().getChunkNow(op.chunkX(), op.chunkZ()) != null;
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
                } else { ship.activeCombat = op; ship.combatRemaining = CarrierPolicy.COMBAT_TICKS; ship.combatCursor = 0; }
                ship.mode = ship.previewMode;
                ship.stop = CarrierPolicy.Stop.RUNNING;
                ship.preview = null;
                ship.previewUntil = 0;
            }
            if (handshake) ship.commandRevision = ship.commandRevision == Integer.MAX_VALUE ? 1 : ship.commandRevision + 1;
        }
        ship.dirty.run();
        menu.sync(player);
    }
    private static void reject(CarrierShip ship, CarrierMenu menu, ServerPlayer player, CarrierPolicy.Stop reason) {
        if (ship.mode == CarrierPolicy.Mode.IDLE) ship.stop = reason;
        ship.commandRevision = ship.commandRevision == Integer.MAX_VALUE ? 1 : ship.commandRevision + 1;
        ship.dirty.run();
        menu.sync(player);
    }
}
