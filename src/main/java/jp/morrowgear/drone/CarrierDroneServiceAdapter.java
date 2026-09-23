package jp.morrowgear.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import jp.morrowgear.drone.carrier.CarrierAnchor;
import jp.morrowgear.drone.carrier.CarrierEntity;
import jp.morrowgear.drone.carrier.CarrierInterior;
import jp.morrowgear.drone.carrier.CarrierModule;
import jp.morrowgear.drone.carrier.CarrierPolicy;
import jp.morrowgear.drone.carrier.CarrierServiceBay;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Explicit bay requests only. Installing this bridge does not register the carrier module. */
public final class CarrierDroneServiceAdapter implements CarrierModule.DronePorts {
    static final long APPROACH_LEASE_TICKS = 40;
    static final long MAX_APPROACH_TICKS = 2400;
    static final long NO_SERVICE_PROGRESS_TICKS = 600;
    static final long MAX_RECOVERY_TICKS = 1200;
    static final long MAX_EXIT_TICKS = 200;

    record Session(UUID ship, CarrierServiceBay.Identity identity, int mode, String role,
                   CarrierAnchor departure, long started, int slot) {
        Session(UUID ship, CarrierServiceBay.Identity identity, int mode, String role,
                CarrierAnchor departure, long started) {
            this(ship, identity, mode, role, departure, started, -1);
        }
        static final Codec<Session> CODEC = RecordCodecBuilder.create(i -> i.group(
            CarrierPolicy.UUID_CODEC.fieldOf("ship").forGetter(Session::ship),
            CarrierServiceBay.Identity.CODEC.fieldOf("identity").forGetter(Session::identity),
            Codec.intRange(0, 5).fieldOf("mode").forGetter(Session::mode),
            Codec.STRING.fieldOf("role").forGetter(Session::role),
            CarrierAnchor.CODEC.fieldOf("departure").forGetter(Session::departure),
            Codec.LONG.validate(t -> t >= 0 ? DataResult.success(t)
                : DataResult.error(() -> "Negative carrier service tick"))
                .fieldOf("started").forGetter(Session::started),
            Codec.intRange(-1, CarrierPolicy.BAY_SLOTS - 1).optionalFieldOf("slot", -1).forGetter(Session::slot)
        ).apply(i, Session::new));

        boolean matches(CarrierServiceBay.Identity current, int currentMode, String currentRole) {
            return identity.equals(current) && mode == currentMode && role.equals(currentRole);
        }
    }

    record WorkState(boolean alive, boolean powerLost, boolean combat, boolean salvage,
                     boolean recovery, boolean runningCargo, boolean laden, boolean service,
                     boolean queued, boolean fieldOrEngineer, boolean trackingOrPatrol) {
        boolean available() {
            return alive && !powerLost && !combat && !salvage && !recovery && !runningCargo
                && !laden && !service && !queued && !fieldOrEngineer && !trackingOrPatrol;
        }
    }

    static final class Progress {
        private final long started;
        private long lastProgress;
        private long bestMissing = Long.MAX_VALUE;
        private boolean reachedBay;

        Progress(long started) { this.started = started; this.lastProgress = started; }

        boolean timedOut(long now, boolean stableAtBay, long missing) {
            if (now < started || now < lastProgress) return true;
            if (stableAtBay && !reachedBay) {
                reachedBay = true;
                bestMissing = missing;
                lastProgress = now;
            } else if (reachedBay && missing < bestMissing) {
                bestMissing = missing;
                lastProgress = now;
            }
            return reachedBay ? expired(now, lastProgress, NO_SERVICE_PROGRESS_TICKS)
                : expired(now, started, MAX_APPROACH_TICKS);
        }
    }

    public static void install() {
        CarrierModule.installDronePorts(new CarrierDroneServiceAdapter());
    }

    /** Only the owner's own linked cabin grants access to this particular exterior world. */
    public static boolean ownerSupportsExterior(ServerLevel exterior, ServerPlayer owner) {
        if (owner == null) return false;
        if (owner.level() == exterior) return true;
        return ownedCabinCarrier(exterior, owner) != null;
    }

    static CarrierEntity ownedCabinCarrier(ServerLevel exterior, ServerPlayer owner) {
        if (!ownerInCabin(owner)) return null;
        UUID shipId = CarrierInterior.currentShip(owner);
        if (shipId == null || !(exterior.getEntity(shipId) instanceof CarrierEntity carrier)) return null;
        var ship = carrier.ship();
        return !carrier.isRemoved() && ship != null && !ship.destroyed
            && exterior.hasChunkAt(carrier.blockPosition())
            && ship.owner.equals(owner.getUUID()) && carrier.controls(owner) ? carrier : null;
    }

    static boolean ownerInCabin(ServerPlayer owner) {
        return owner != null && CarrierInterior.inside(owner.level());
    }

    enum ExteriorAction { HOLD, CARGO, WAYPOINT, FOLLOW, DOCK }

    static ExteriorAction exteriorAction(DroneMode mode, boolean service, boolean protectedWork,
                                         boolean waypoint, boolean cargoAssigned, boolean ownsCargo, boolean paused) {
        if (protectedWork) return ExteriorAction.HOLD;
        if (service || mode == DroneMode.DOCK) return ExteriorAction.DOCK;
        if (cargoAssigned) return mode == DroneMode.WAYPOINT && ownsCargo && !paused
            ? ExteriorAction.CARGO : ExteriorAction.HOLD;
        return switch (mode) {
            case WAYPOINT -> waypoint ? ExteriorAction.WAYPOINT : ExteriorAction.HOLD;
            case FOLLOW, RETURN, ORBIT -> ExteriorAction.FOLLOW;
            default -> ExteriorAction.HOLD;
        };
    }

    /** Exterior stand-off, not the player's coordinates in the cabin or a replacement Wing. */
    static Vec3 exteriorFollowTarget(AABB hull, int identityHash) {
        int slot = Math.floorMod(identityHash, 8);
        double x = (slot & 1) == 0 ? hull.minX - 6 : hull.maxX + 6;
        double z = hull.minZ + (hull.maxZ - hull.minZ) * (.2 + (slot / 2) * .2);
        return new Vec3(x, hull.minY - 3, z);
    }

    /** Reach the belly from outside the current hull, then use the assigned vertical bay aperture. */
    static Vec3 approachWaypoint(AABB hull, Vec3 current, Vec3 requested, Vec3 staging) {
        double bayOffset = current.subtract(staging).multiply(1, 0, 1).lengthSqr();
        if (current.y <= staging.y + .5 || bayOffset <= 1 && current.y < hull.maxY) return requested;
        boolean outside = current.x <= hull.minX - 3 || current.x >= hull.maxX + 3
            || current.z <= hull.minZ - 3 || current.z >= hull.maxZ + 3;
        if (outside) return new Vec3(current.x, staging.y, current.z);
        double side = current.x < (hull.minX + hull.maxX) * .5 ? hull.minX - 4 : hull.maxX + 4;
        return new Vec3(side, current.y, current.z);
    }

    static Vec3 exitWaypoint(AABB hull, Vec3 current, Vec3 staging) {
        if (current.y > staging.y + .5) return staging;
        double side = staging.x < (hull.minX + hull.maxX) * .5 ? hull.minX - 4 : hull.maxX + 4;
        return new Vec3(side, staging.y, staging.z);
    }

    @Override public CarrierServiceBay.DronePort find(CarrierEntity carrier, UUID droneId) {
        if (!(carrier.level() instanceof ServerLevel level)
            || !(level.getEntity(droneId) instanceof DroneEntity drone)) return null;
        return new Port(carrier, drone);
    }

    static CarrierServiceBay.Identity identity(DroneEntity drone) {
        Optional<CarrierAnchor> home = drone.hasDock()
            ? Optional.of(new CarrierAnchor(drone.level().dimension().identifier().toString(),
                drone.dockPos().getX(), drone.dockPos().getY(), drone.dockPos().getZ())) : Optional.empty();
        return new CarrierServiceBay.Identity(drone.getUUID(), drone.ownerId(), home,
            drone.groupId(), drone.missionId());
    }

    static CarrierServiceBay.Lease lease(CarrierEntity carrier, DroneEntity drone) {
        if (!(carrier.level() instanceof ServerLevel level) || drone.level() != level
            || carrier.isRemoved() || !level.hasChunkAt(carrier.blockPosition())
            || !level.hasChunkAt(drone.blockPosition())) return null;
        var ship = carrier.ship();
        if (ship == null || ship.destroyed || !drone.isOwnedBy(ship.owner)) return null;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ship.owner);
        if (owner == null || !ownerSupportsExterior(level, owner) || !carrier.controls(owner)) return null;
        var identity = identity(drone);
        return ship.bay.snapshot().stream().filter(l -> l.identity().equals(identity)).findFirst().orElse(null);
    }

    static boolean expired(long now, long then, long duration) {
        return then < 0 || duration < 0 || now < then || now - then > duration;
    }

    static int accepted(int offered, int stored, int capacity) {
        return (int)Math.min(Math.max(0L, offered), Math.max(0L, (long)capacity - Math.max(0, stored)));
    }

    // A small flight-power deadband lets completion survive the bay/drone tick ordering.
    static int flightDemand(int stored, int capacity) {
        return stored >= Math.ceil(capacity * .98) ? 0 : accepted(Integer.MAX_VALUE, stored, capacity);
    }

    static int supplyDemand(DroneRole role, SecurityLoadout loadout, int kind, int stored, int capacity) {
        if (kind == 0) return flightDemand(stored, capacity);
        if (role != DroneRole.SECURITY) return 0;
        SecurityLoadout selected = loadout == null ? SecurityLoadout.AUTO : loadout;
        boolean used = switch (kind) {
            // CombatPolicy's common weapon-energy reserve also applies to UNARMED.
            case 1 -> true;
            case 2 -> selected == SecurityLoadout.AUTO || selected == SecurityLoadout.AUTOCANNON;
            case 3 -> selected == SecurityLoadout.AUTO || selected == SecurityLoadout.MISSILE;
            default -> false;
        };
        return used ? accepted(Integer.MAX_VALUE, stored, capacity) : 0;
    }

    static int repairValue(String item) {
        return switch (item) {
            case "minecraft:copper_ingot" -> 4;
            case "minecraft:iron_ingot" -> 8;
            case "morrowgear_drone:morrow_alloy" -> 20;
            default -> 0;
        };
    }

    private record Port(CarrierEntity carrier, DroneEntity drone) implements CarrierServiceBay.DronePort {
        @Override public CarrierServiceBay.Identity identity() { return CarrierDroneServiceAdapter.identity(drone); }
        @Override public Vec3 position() { return drone.position(); }
        @Override public Vec3 velocity() { return drone.getDeltaMovement(); }
        @Override public boolean available() {
            var ship = carrier.ship();
            if (ship == null || ship.destroyed || !drone.isOwnedBy(ship.owner)
                || !(carrier.level() instanceof ServerLevel level) || drone.level() != level) return false;
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ship.owner);
            return !carrier.isRemoved() && owner != null && ownerSupportsExterior(level, owner)
                && carrier.controls(owner) && drone.carrierServiceAvailable(carrier.getUUID());
        }
        @Override public CarrierServiceBay.Needs needs() { return drone.carrierServiceNeeds(); }

        private boolean canReceive() {
            var lease = CarrierDroneServiceAdapter.lease(carrier, drone);
            return available() && lease != null && carrier.bayServiceReady() && drone.carrierServiceAssignedTo(carrier.getUUID())
                && CarrierServiceBay.stable(position(), velocity(), carrier.bayPosition(lease.slot()), carrier.bayVelocity(lease.slot()));
        }
        @Override public int receiveEnergy(int offered) { return canReceive() ? drone.receiveCarrierSupply(0, offered) : 0; }
        @Override public int receiveWeaponEnergy(int offered) { return canReceive() ? drone.receiveCarrierSupply(1, offered) : 0; }
        @Override public int receiveAutocannonRounds(int offered) { return canReceive() ? drone.receiveCarrierSupply(2, offered) : 0; }
        @Override public int receiveMissiles(int offered) { return canReceive() ? drone.receiveCarrierSupply(3, offered) : 0; }
        @Override public boolean repairWith(ItemStack material) {
            return canReceive() && !material.isEmpty() && material.getCount() == 1
                && drone.repairFromCarrier(repairValue(DockSupplyPolicy.itemId(material)));
        }
        @Override public void approach(Vec3 position, Vec3 carrierVelocity) {
            var lease = CarrierDroneServiceAdapter.lease(carrier, drone);
            if (!available() || lease == null || (!position.equals(carrier.bayPosition(lease.slot()))
                && !position.equals(carrier.bayApproachPosition(lease.slot())))) return;
            drone.approachCarrierService(carrier, position, carrierVelocity);
        }
        @Override public void release(CarrierServiceBay.Release reason) {
            drone.releaseCarrierService(carrier.getUUID(), reason);
        }
    }
}
