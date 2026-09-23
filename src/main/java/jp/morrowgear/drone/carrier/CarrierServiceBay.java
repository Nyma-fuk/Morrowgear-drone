package jp.morrowgear.drone.carrier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import jp.morrowgear.drone.DockSupplyPolicy;

/** Temporary service reservations never replace HomeDock, Wing or the suspended mission. */
public final class CarrierServiceBay {
    public record Identity(UUID drone, UUID owner, Optional<CarrierAnchor> homeDock, String wing, String mission) {
        public static final Codec<Identity> CODEC = RecordCodecBuilder.create(i -> i.group(
            CarrierPolicy.UUID_CODEC.fieldOf("drone").forGetter(Identity::drone),
            CarrierPolicy.UUID_CODEC.fieldOf("owner").forGetter(Identity::owner),
            CarrierAnchor.CODEC.optionalFieldOf("home_dock").forGetter(Identity::homeDock),
            Codec.STRING.fieldOf("wing").forGetter(Identity::wing),
            Codec.STRING.fieldOf("mission").forGetter(Identity::mission)
        ).apply(i, Identity::new));
    }
    public record Lease(Identity identity, int slot) {
        public static final Codec<Lease> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identity.CODEC.fieldOf("identity").forGetter(Lease::identity),
            Codec.intRange(0, CarrierPolicy.BAY_SLOTS - 1).fieldOf("slot").forGetter(Lease::slot)
        ).apply(i, Lease::new));
    }
    public enum Release { COMPLETE, EXPIRED, SHIP_UNAVAILABLE, IDENTITY_CHANGED, CANCELLED }
    public enum Phase { HOLDING, APPROACH, DOCKING, SERVICE }
    public record Needs(int flight, int weapon, int gun, int missiles, boolean repair) {
        public Needs {
            if (flight < 0 || weapon < 0 || gun < 0 || missiles < 0) throw new IllegalArgumentException("Negative bay service demand");
        }
        public boolean complete() { return flight == 0 && weapon == 0 && gun == 0 && missiles == 0 && !repair; }
    }
    public record Stock(int gun, int missiles) {
        public static final Codec<Stock> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(0, DockSupplyPolicy.MAGAZINE_ROUNDS - 1).fieldOf("gun").forGetter(Stock::gun),
            Codec.intRange(0, DockSupplyPolicy.MISSILE_PACK_ROUNDS - 1).fieldOf("missiles").forGetter(Stock::missiles)
        ).apply(i, Stock::new));
    }
    public interface DronePort {
        Identity identity();
        Vec3 position();
        Vec3 velocity();
        boolean available();
        Needs needs();
        /** Transfer at most offered units, returning the amount actually accepted. */
        int receiveEnergy(int offered);
        int receiveWeaponEnergy(int offered);
        int receiveAutocannonRounds(int offered);
        int receiveMissiles(int offered);
        /** One material, consumed by the carrier only when an actual repair returns true. */
        boolean repairWith(ItemStack oneMaterial);
        void approach(Vec3 position, Vec3 carrierVelocity);
        /** Restore the suspended mission, or return to HomeDock/recovery wait if unavailable. */
        void release(Release reason);
    }

    private final List<Lease> leases;
    private final java.util.Map<UUID, Long> renewed = new java.util.HashMap<>();
    private final java.util.Set<UUID> approachReady = new java.util.HashSet<>();
    private final Runnable dirty;

    public CarrierServiceBay(List<Lease> saved, Runnable dirty) {
        if (saved.size() > CarrierPolicy.BAY_SLOTS || saved.stream().map(Lease::slot).distinct().count() != saved.size()
            || saved.stream().map(l -> l.identity.drone).distinct().count() != saved.size())
            throw new IllegalArgumentException("Duplicate carrier bay lease");
        leases = new ArrayList<>(saved);
        this.dirty = dirty;
    }
    public List<Lease> snapshot() { return List.copyOf(leases); }
    public Optional<Lease> reserve(UUID owner, Identity identity, long tick) {
        if (!owner.equals(identity.owner)) return Optional.empty();
        Lease old = leases.stream().filter(l -> l.identity.drone.equals(identity.drone)).findFirst().orElse(null);
        if (old != null) {
            if (!old.identity.equals(identity)) return Optional.empty();
            renewed.put(identity.drone, tick);
            return Optional.of(old);
        }
        for (int slot = 0; slot < CarrierPolicy.BAY_SLOTS; slot++) {
            int candidate = slot;
            if (leases.stream().anyMatch(l -> l.slot == candidate)) continue;
            Lease lease = new Lease(identity, slot);
            leases.add(lease);
            renewed.put(identity.drone, tick);
            dirty.run();
            return Optional.of(lease);
        }
        return Optional.empty();
    }
    public boolean release(UUID drone) {
        approachReady.remove(drone);
        renewed.remove(drone);
        boolean removed = leases.removeIf(l -> l.identity.drone.equals(drone));
        if (removed) dirty.run();
        return removed;
    }
    public void cancelAll(CarrierEntity carrier, Release reason) {
        for (Lease lease : snapshot()) {
            DronePort port = CarrierModule.dronePorts().find(carrier, lease.identity.drone);
            release(lease.identity.drone);
            if (port != null) port.release(reason);
        }
    }
    public static boolean stable(Vec3 position, Vec3 velocity, Vec3 bay, Vec3 carrierVelocity) {
        return position.distanceToSqr(bay) <= .75 * .75 && velocity.subtract(carrierVelocity).lengthSqr() <= .08 * .08;
    }
    boolean aligned(CarrierEntity carrier, Lease lease) {
        if (!carrier.bayServiceReady() || !approachReady.contains(lease.identity.drone)) return false;
        DronePort port = CarrierModule.dronePorts().find(carrier, lease.identity.drone);
        return port != null && port.available() && lease.identity.equals(port.identity())
            && stable(port.position(), port.velocity(), carrier.bayPosition(lease.slot), carrier.bayVelocity(lease.slot));
    }
    Phase phase(CarrierEntity carrier, Lease lease) {
        if (!carrier.bayServiceReady()) return Phase.HOLDING;
        if (!approachReady.contains(lease.identity.drone)) return Phase.APPROACH;
        return aligned(carrier, lease) ? Phase.SERVICE : Phase.DOCKING;
    }
    boolean clearForTurn(CarrierEntity carrier) {
        for (Lease lease : snapshot()) {
            DronePort port = CarrierModule.dronePorts().find(carrier, lease.identity.drone);
            // The approved service drone is 0.95 tall; retain the full 1-block hull safety margin.
            if (port == null || port.position().y + .95 > carrier.getY() - 1) return false;
        }
        return true;
    }
    public void tick(CarrierEntity carrier, CarrierShip ship, long tick) {
        for (Lease lease : snapshot()) {
            DronePort port = CarrierModule.dronePorts().find(carrier, lease.identity.drone);
            Long last = renewed.get(lease.identity.drone);
            Release failure = ship.destroyed ? Release.SHIP_UNAVAILABLE
                : last == null || tick - last > 100 || tick < last ? Release.EXPIRED
                : port == null || !port.available() ? Release.SHIP_UNAVAILABLE
                : !lease.identity.equals(port.identity()) ? Release.IDENTITY_CHANGED : null;
            if (failure != null) {
                release(lease.identity.drone);
                if (port != null) port.release(failure);
                continue;
            }
            renewed.put(lease.identity.drone, tick);
            if (port.needs().complete()) {
                release(lease.identity.drone); port.release(Release.COMPLETE); continue;
            }
            Vec3 bay = carrier.bayPosition(lease.slot);
            if (!carrier.bayServiceReady()) approachReady.remove(lease.identity.drone);
            if (!approachReady.contains(lease.identity.drone)) {
                Vec3 staging = carrier.bayApproachPosition(lease.slot);
                port.approach(staging, carrier.bayVelocity(lease.slot));
                if (carrier.bayServiceReady() && stable(port.position(), port.velocity(), staging, carrier.bayVelocity(lease.slot)))
                    approachReady.add(lease.identity.drone);
                continue;
            }
            port.approach(bay, carrier.bayVelocity(lease.slot));
            if (!lease.identity.equals(port.identity())) {
                release(lease.identity.drone); port.release(Release.IDENTITY_CHANGED); continue;
            }
            if (tick % 20 != 0 || !stable(port.position(), port.velocity(), bay, carrier.bayVelocity(lease.slot))) continue;
            Needs needs = port.needs();
            int offered = CarrierPowerPolicy.serviceOffer(ship.energy, needs.flight, false);
            if (offered > 0) ship.energy -= accepted(port.receiveEnergy(offered), offered);
            offered = CarrierPowerPolicy.serviceOffer(ship.weaponEnergy, needs.weapon, true);
            if (offered > 0) ship.weaponEnergy -= accepted(port.receiveWeaponEnergy(offered), offered);
            int gun = transferPack(ship, CarrierSupplies.GUN, ship.bayStock.gun,
                Math.min(needs.gun, 40), DockSupplyPolicy.MAGAZINE_ROUNDS, port::receiveAutocannonRounds);
            int missiles = transferPack(ship, CarrierSupplies.MISSILES, ship.bayStock.missiles,
                Math.min(needs.missiles, 2), DockSupplyPolicy.MISSILE_PACK_ROUNDS, port::receiveMissiles);
            ship.bayStock = new Stock(gun, missiles);
            if (needs.repair) {
                var material = CarrierSupplies.find(ship, CarrierSupplies.REPAIR);
                if (material != null && port.repairWith(material.one())) material.consume();
            }
            ship.dirty.run();
        }
    }
    static int transferPack(CarrierShip ship, int kind, int credit, int requested, int units,
                                    java.util.function.IntUnaryOperator receiver) {
        if (requested <= 0) return credit;
        var source = CarrierSupplies.find(ship, kind);
        var plan = DockSupplyPolicy.takePack(credit, requested, units, source != null);
        if (plan.supplied() == 0) return credit;
        int taken = accepted(receiver.applyAsInt(plan.supplied()), plan.supplied());
        if (taken == 0) return credit;
        if (plan.consumeItem()) source.consume();
        return (plan.consumeItem() ? units : credit) - taken;
    }
    private static int accepted(int actual, int offered) {
        if (actual < 0 || actual > offered) throw new IllegalStateException("Carrier service adapter violated conservation contract");
        return actual;
    }
}
