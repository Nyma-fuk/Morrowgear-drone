package jp.morrowgear.drone.carrier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Either;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Authoritative world-saved state. Exterior entities carry only the ship UUID. */
public final class CarrierShip {
    public static final Codec<CarrierShip> CODEC = RecordCodecBuilder.create(i -> i.group(
        CarrierPolicy.UUID_CODEC.fieldOf("owner").forGetter(s -> s.owner),
        Codec.intRange(0, CarrierPolicy.MAX_CABINS - 1).fieldOf("cabin").forGetter(s -> s.cabin),
        Codec.BOOL.fieldOf("cabin_ready").forGetter(s -> s.cabinReady),
        Codec.BOOL.fieldOf("destroyed").forGetter(s -> s.destroyed),
        CarrierAnchor.CODEC.fieldOf("exterior").forGetter(s -> s.exterior),
        CarrierPolicy.UUID_CODEC.listOf(0, 32).fieldOf("guests").forGetter(s -> List.copyOf(s.guests)),
        CarrierPolicy.Progress.CODEC.optionalFieldOf("progress").forGetter(s -> Optional.ofNullable(s.progress)),
        ItemStack.OPTIONAL_CODEC.listOf(0, CarrierPolicy.MAX_STORAGE_SLOTS).fieldOf("cargo").forGetter(s -> s.cargo.snapshot()),
        Codec.intRange(0, CarrierPolicy.MAX_ENERGY).fieldOf("energy").forGetter(s -> s.energy),
        Codec.intRange(0, CarrierPolicy.MAX_ENERGY).optionalFieldOf("weapon_energy", 0).forGetter(s -> s.weaponEnergy),
        Pending.CODEC.optionalFieldOf("pending").forGetter(s -> Optional.ofNullable(s.pending)),
        CarrierServiceBay.Lease.CODEC.listOf(0, CarrierPolicy.BAY_SLOTS).fieldOf("bay").forGetter(s -> s.bay.snapshot()),
        CarrierServiceBay.Stock.CODEC.optionalFieldOf("bay_stock", new CarrierServiceBay.Stock(0, 0)).forGetter(s -> s.bayStock),
        ItemStack.OPTIONAL_CODEC.listOf(0, CarrierSupplies.SIZE).optionalFieldOf("supplies", List.of()).forGetter(s -> s.supplies.snapshot()),
        TravelIntent.CODEC.optionalFieldOf("navigation_intent").forGetter(s -> s.destination == null
            ? Optional.empty() : Optional.of(new TravelIntent(s.destination, s.arrivalOperation, s.combatWeapon))),
        Codec.BOOL.optionalFieldOf("mining_startup_charged", false).forGetter(s -> s.miningStartupCharged)
    ).apply(i, CarrierShip::new));

    public final UUID owner;
    public final int cabin;
    public final Set<UUID> guests;
    public final CarrierCargoStorage cargo;
    public final CarrierSupplies supplies;
    public final CarrierServiceBay bay;
    public int attachedCargoModules;
    public boolean cabinReady, destroyed;
    public CarrierAnchor exterior;
    public CarrierPolicy.Progress progress;
    public Pending pending;
    public int energy;
    public int weaponEnergy;
    public CarrierServiceBay.Stock bayStock;
    public CarrierPolicy.Mode mode = CarrierPolicy.Mode.IDLE;
    public CarrierPolicy.Stop stop = CarrierPolicy.Stop.RELOAD;
    public CarrierPolicy.Operation preview;
    public CarrierPolicy.Mode previewMode = CarrierPolicy.Mode.IDLE;
    public long previewUntil;
    public int combatRemaining;
    public CarrierPolicy.Operation activeCombat;
    public CarrierAnchor destination;
    /** Operation requested together with travel. Kept server-side so closing the screen cannot lose it. */
    public CarrierPolicy.Mode arrivalOperation = CarrierPolicy.Mode.IDLE;
    /** Selected combat system. It travels with queued navigation and never changes during an active operation. */
    public CarrierPolicy.CombatWeapon combatWeapon = CarrierPolicy.CombatWeapon.LASER;
    public boolean navigationPaused;
    public int navigationWaitTicks, combatCursor;
    public boolean miningStartupCharged;
    public UUID miningScanGeneration;
    public int miningScanTicks;
    /** Stable combat centre; replaced only when the locked entity is no longer a safe target. */
    public UUID combatPrimaryTarget;
    public List<Vec3> combatScanAims = List.of();
    /** Monotonic session-local acknowledgement for commands issued through an open menu. */
    public int commandRevision;
    /** SCAN identities are visible candidates; FIRE identities are successful area impacts. */
    public List<UUID> combatBeamTargets = List.of();
    /** One immutable heavy-missile assignment pass per combat operation generation. */
    public UUID missileSalvoGeneration;
    public List<UUID> missileLockedTargets = List.of();
    /** Entity sync should use this for mining too; SCAN endpoints are not damaging beams. */
    public CarrierPolicy.WorkPhase workPhase() {
        if (mode == CarrierPolicy.Mode.COMBAT) return CarrierPolicy.combatPhase(combatRemaining, combatWeapon);
        if (mode != CarrierPolicy.Mode.MINING) return CarrierPolicy.WorkPhase.IDLE;
        if (progress == null || miningScanGeneration == null
            || !progress.operation().generation().equals(miningScanGeneration)
            || miningScanTicks < CarrierPolicy.SCAN_TICKS) return CarrierPolicy.WorkPhase.SCAN;
        return miningScanTicks < CarrierPolicy.SCAN_TICKS + CarrierPolicy.MINING_CHARGE_TICKS
            ? CarrierPolicy.WorkPhase.CHARGE : CarrierPolicy.WorkPhase.FIRE;
    }
    public int workPhaseTick() {
        if (mode == CarrierPolicy.Mode.COMBAT) return CarrierPolicy.phaseTick(combatRemaining, combatWeapon);
        if (progress == null || miningScanGeneration == null
            || !progress.operation().generation().equals(miningScanGeneration)) return 0;
        return workPhase() == CarrierPolicy.WorkPhase.SCAN ? miningScanTicks
            : workPhase() == CarrierPolicy.WorkPhase.CHARGE ? miningScanTicks - CarrierPolicy.SCAN_TICKS
            : workPhase() == CarrierPolicy.WorkPhase.FIRE ? Math.min(CarrierMiningEffectPolicy.RELEASE_TICKS,
                Math.max(0, miningScanTicks - CarrierPolicy.SCAN_TICKS - CarrierPolicy.MINING_CHARGE_TICKS)) : 0;
    }
    public int workPhaseDuration() {
        CarrierPolicy.WorkPhase phase = workPhase();
        if (mode == CarrierPolicy.Mode.COMBAT) return CarrierPolicy.phaseDuration(phase, combatWeapon);
        return switch (phase) {
            case SCAN -> CarrierPolicy.SCAN_TICKS;
            case CHARGE -> CarrierPolicy.MINING_CHARGE_TICKS;
            case FIRE -> CarrierMiningEffectPolicy.RELEASE_TICKS;
            default -> 0;
        };
    }
    public Runnable dirty = () -> {};

    public CarrierShip(UUID owner, int cabin, CarrierAnchor exterior) {
        this(owner, cabin, false, false, exterior, List.of(), Optional.empty(), List.of(), 0, 0,
            Optional.empty(), List.of(), new CarrierServiceBay.Stock(0, 0), List.of(), Optional.empty(), false);
        stop = CarrierPolicy.Stop.READY;
    }

    private CarrierShip(UUID owner, int cabin, boolean ready, boolean destroyed, CarrierAnchor exterior,
                        List<UUID> guests, Optional<CarrierPolicy.Progress> progress, List<ItemStack> cargo,
                        int energy, int weaponEnergy, Optional<Pending> pending, List<CarrierServiceBay.Lease> leases,
                        CarrierServiceBay.Stock stock, List<ItemStack> supplies, Optional<TravelIntent> navigationIntent,
                        boolean miningStartupCharged) {
        this.owner = owner;
        this.cabin = cabin;
        this.cabinReady = ready;
        this.destroyed = destroyed;
        this.exterior = exterior;
        this.guests = new HashSet<>(guests);
        this.progress = progress.orElse(null);
        this.energy = energy;
        this.weaponEnergy = weaponEnergy;
        this.bayStock = stock;
        TravelIntent travel = navigationIntent.orElse(null);
        this.destination = travel == null ? null : travel.destination();
        this.navigationPaused = destination != null;
        this.arrivalOperation = travel == null ? CarrierPolicy.Mode.IDLE : travel.arrivalOperation();
        this.combatWeapon = travel == null ? CarrierPolicy.CombatWeapon.LASER : travel.combatWeapon();
        this.miningStartupCharged = miningStartupCharged;
        this.pending = pending.orElse(null);
        this.supplies = new CarrierSupplies(supplies, () -> dirty.run());
        this.cargo = new CarrierCargoStorage(cargo, () -> dirty.run(), () -> attachedCargoModules);
        this.bay = new CarrierServiceBay(leases, () -> dirty.run());
    }

    public boolean permits(UUID player) { return owner.equals(player) || guests.contains(player); }
    public void stop(CarrierPolicy.Stop reason) {
        mode = CarrierPolicy.Mode.IDLE;
        stop = reason;
        navigationPaused = destination != null;
        navigationWaitTicks = 0;
        preview = null;
        previewUntil = 0;
        combatRemaining = 0;
        activeCombat = null;
        combatBeamTargets = List.of();
        combatPrimaryTarget = null;
        combatScanAims = List.of();
        missileSalvoGeneration = null;
        missileLockedTargets = List.of();
        miningScanTicks = 0;
        miningScanGeneration = null;
        dirty.run();
    }

    public void pauseNavigation(CarrierPolicy.Stop reason) {
        navigationPaused = true;
        navigationWaitTicks = 0;
        stop = reason;
        dirty.run();
    }

    /** Retains the active operation and its visual phase while an external resource is unavailable. */
    public void pauseWork(CarrierPolicy.Stop reason) {
        if (mode == CarrierPolicy.Mode.IDLE) return;
        if (stop != reason) {
            stop = reason;
            dirty.run();
        }
    }

    public void resumeWork() {
        if (mode != CarrierPolicy.Mode.IDLE && stop != CarrierPolicy.Stop.RUNNING) {
            stop = CarrierPolicy.Stop.RUNNING;
            dirty.run();
        }
    }

    public record Pending(BlockPos pos, BlockState state, List<ItemStack> drops) {
        public static final Codec<Pending> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(Pending::pos),
            BlockState.CODEC.fieldOf("state").forGetter(Pending::state),
            ItemStack.CODEC.listOf(0, 256).fieldOf("drops").forGetter(Pending::drops)
        ).apply(i, Pending::new));
        public Pending { pos = pos.immutable(); drops = drops.stream().map(ItemStack::copy).toList(); }
    }

    public record TravelIntent(CarrierAnchor destination, CarrierPolicy.Mode arrivalOperation,
                               CarrierPolicy.CombatWeapon combatWeapon) {
        private static final Codec<TravelIntent> CURRENT = RecordCodecBuilder.create(i -> i.group(
            CarrierAnchor.CODEC.fieldOf("destination").forGetter(TravelIntent::destination),
            Codec.intRange(0, CarrierPolicy.Mode.values().length - 1).optionalFieldOf("arrival_operation", 0)
                .forGetter(value -> value.arrivalOperation.ordinal()),
            Codec.intRange(0, CarrierPolicy.CombatWeapon.values().length - 1).optionalFieldOf("combat_weapon", 0)
                .forGetter(value -> value.combatWeapon.ordinal())
        ).apply(i, (destination, mode, weapon) -> new TravelIntent(destination,
            CarrierPolicy.Mode.values()[mode], CarrierPolicy.CombatWeapon.values()[weapon])));
        /** Reads legacy saves where navigation_intent contained only a CarrierAnchor. */
        public static final Codec<TravelIntent> CODEC = Codec.either(CarrierAnchor.CODEC, CURRENT).xmap(
            value -> value.map(anchor -> new TravelIntent(anchor, CarrierPolicy.Mode.IDLE,
                CarrierPolicy.CombatWeapon.LASER), intent -> intent),
            Either::right);
        public TravelIntent(CarrierAnchor destination, CarrierPolicy.Mode arrivalOperation) {
            this(destination, arrivalOperation, CarrierPolicy.CombatWeapon.LASER);
        }
        public TravelIntent {
            if (arrivalOperation == null) arrivalOperation = CarrierPolicy.Mode.IDLE;
            if (combatWeapon == null) combatWeapon = CarrierPolicy.CombatWeapon.LASER;
        }
    }
}
