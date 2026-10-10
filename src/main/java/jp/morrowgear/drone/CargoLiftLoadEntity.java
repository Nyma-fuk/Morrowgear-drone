package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jp.morrowgear.drone.CargoLiftPolicy.Corner;
import jp.morrowgear.drone.CargoLiftPolicy.Phase;
import jp.morrowgear.drone.carrier.CarrierCargoContainerBlockEntity;
import jp.morrowgear.drone.carrier.CarrierExpansionItemData;
import jp.morrowgear.drone.carrier.CarrierExpansionTransactions;
import jp.morrowgear.drone.carrier.CarrierContainerFootprint;
import jp.morrowgear.drone.carrier.CarrierModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Persistent authority for a four-salvage-aircraft container lift. */
public final class CargoLiftLoadEntity extends Entity {
    private static final EntityDataAccessor<String> MISSION = SynchedEntityData.defineId(
        CargoLiftLoadEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> OWNER = SynchedEntityData.defineId(
        CargoLiftLoadEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(
        CargoLiftLoadEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> SOURCE = SynchedEntityData.defineId(
        CargoLiftLoadEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DESTINATION = SynchedEntityData.defineId(
        CargoLiftLoadEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Boolean> CARRIED = SynchedEntityData.defineId(
        CargoLiftLoadEntity.class, EntityDataSerializers.BOOLEAN);
    private static final int PARTNER_GRACE_TICKS = 200;
    private static final double RENDEZVOUS_TOLERANCE = 1.15;

    private final EnumMap<Corner, UUID> aircraft = new EnumMap<>(Corner.class);
    private ItemStack cargo = ItemStack.EMPTY;
    private UUID cargoId;
    private int phaseTicks;
    private int partnerMissingTicks;
    private boolean cancelRequested;
    private BlockPos emergencyLanding;
    private String lastReason = "";
    private Vec3 rigGuide;
    private Vec3 rigGuideVelocity = Vec3.ZERO;

    public CargoLiftLoadEntity(EntityType<? extends CargoLiftLoadEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    public void initialize(ServerPlayer owner, BlockPos source, BlockPos destination,
            CarrierCargoContainerBlockEntity container, List<DroneEntity> selected) {
        if (selected.size() != CargoLiftPolicy.REQUIRED_AIRCRAFT) throw new IllegalArgumentException("four aircraft required");
        entityData.set(MISSION, UUID.randomUUID().toString());
        entityData.set(OWNER, owner.getUUID().toString());
        entityData.set(SOURCE, source.asLong());
        entityData.set(DESTINATION, destination.asLong());
        cargoId = container.cargoId();
        setPhase(Phase.RESERVING, "MISSION ACCEPTED");
        setPos(Vec3.atBottomCenterOf(source));
        rigGuide = position();
        rigGuideVelocity = Vec3.ZERO;
        int index = 0;
        for (Corner corner : Corner.values()) {
            DroneEntity drone = selected.get(index++);
            aircraft.put(corner, drone.getUUID());
            drone.assignCargoLift(this, corner);
        }
        container.setTransferReserved(true);
    }

    public UUID missionId() { return parseUuid(entityData.get(MISSION)); }
    public UUID ownerId() { return parseUuid(entityData.get(OWNER)); }
    public Phase phase() { return Phase.values()[Mth.clamp(entityData.get(PHASE), 0, Phase.values().length - 1)]; }
    public BlockPos sourcePos() { return BlockPos.of(entityData.get(SOURCE)); }
    public BlockPos destinationPos() { return BlockPos.of(entityData.get(DESTINATION)); }
    public boolean carried() { return entityData.get(CARRIED); }
    public String lastReason() { return lastReason; }
    public Map<Corner, UUID> aircraft() { return Map.copyOf(aircraft); }

    public Vec3 cableAnchor(Corner corner) {
        CargoLiftPolicy.Offset offset = CargoLiftPolicy.attachmentOffset(corner);
        return position().add(offset.x(), CargoLiftPolicy.LOAD_HALF_HEIGHT * 2.0, offset.z());
    }

    public Vec3 aircraftTarget(Corner corner) {
        CargoLiftPolicy.Offset offset = CargoLiftPolicy.formationOffset(corner);
        Vec3 centre = rigGuide == null ? position() : rigGuide;
        return centre.add(offset.x(), CargoLiftPolicy.LOAD_HALF_HEIGHT * 2.0
            + CargoLiftPolicy.CABLE_LENGTH + SalvageTowPolicy.HOOK_DROP, offset.z());
    }

    public void requestCancel(ServerPlayer player) {
        if (player != null && ownerId().equals(player.getUUID())) cancelRequested = true;
    }

    void cancelBeforeSpawn(ServerLevel level, String reason) {
        CarrierCargoContainerBlockEntity container = sourceContainer(level);
        if (container != null) container.setTransferReserved(false);
        releaseAssignments(level, reason);
        discard();
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MISSION, "");
        builder.define(OWNER, "");
        builder.define(PHASE, Phase.RESERVING.ordinal());
        builder.define(SOURCE, BlockPos.ZERO.asLong());
        builder.define(DESTINATION, BlockPos.ZERO.asLong());
        builder.define(CARRIED, false);
    }

    @Override public void tick() {
        super.tick();
        setNoGravity(true);
        fallDistance = 0;
        if (!(level() instanceof ServerLevel level)) return;
        OperationChunkLeaseRuntime.refresh(level, chunkPosition());
        List<DroneEntity> members = members(level);
        restoreAssignments(members);
        phaseTicks++;
		if (phase() == Phase.RENDEZVOUS && phaseTicks == CargoLiftPolicy.RENDEZVOUS_NOTICE_TICKS)
			lastReason = "RENDEZVOUS ROUTE REPLAN";
		if (phase() == Phase.RENDEZVOUS && phaseTicks >= CargoLiftPolicy.RENDEZVOUS_ABORT_TICKS)
			setPhase(Phase.ABORTED, "RENDEZVOUS ROUTE BLOCKED");

        if (cancelRequested) {
            if (carried()) setPhase(Phase.EMERGENCY_DESCENT, "OPERATOR CANCEL / SAFE LANDING");
            else setPhase(Phase.ABORTED, "OPERATOR CANCEL");
            cancelRequested = false;
        }
        if (members.size() != CargoLiftPolicy.REQUIRED_AIRCRAFT) {
            if (++partnerMissingTicks <= PARTNER_GRACE_TICKS) {
                holdPosition();
                return;
            }
            setPhase(carried() ? Phase.EMERGENCY_DESCENT : Phase.ABORTED, "AIRCRAFT LINK LOST");
        } else partnerMissingTicks = 0;

        boolean loadPresent = phase().ordinal() >= Phase.RELEASE.ordinal()
            || (carried() ? !cargo.isEmpty() : sourceContainer(level) != null);
        BlockPos resolvedDestination = resolvedDestination(level);
        double distanceToDestination = horizontalDistance(position(), Vec3.atBottomCenterOf(resolvedDestination));
        // A blocked landing zone must not strand a newly lifted load over its source. Travel to
        // the destination holding area first, then orbit there until the footprint becomes clear.
        boolean destinationClear = !CargoLiftPolicy.landingClearRequired(phase(), distanceToDestination)
            || landingClear(level, resolvedDestination);
        boolean ready = physicalPhaseReady(level, members);
        List<CargoLiftPolicy.Aircraft> assessment = assessment(members);
        CargoLiftPolicy.Assessment result = CargoLiftPolicy.assess(phase(), assessment,
            destinationClear, loadPresent, ready);
        if (result.next() == Phase.ABORTED || result.next() == Phase.EMERGENCY_DESCENT) {
            setPhase(result.next(), result.reason());
        } else if (result.next() == Phase.HOLD && phase() != Phase.HOLD) {
            setPhase(Phase.HOLD, result.reason());
        } else if (result.ready() && result.next() != phase()) {
            transition(level, result.next());
        }

        switch (phase()) {
            case RESERVING, RENDEZVOUS, CONNECTING, TENSIONING -> holdAtSource(level);
            case LIFT -> tickLift(level, members);
            case TRANSIT -> tickTransit(level, members);
            case DESCENT -> tickDescent(level, members);
            case RELEASE -> {
                setDeltaMovement(Vec3.ZERO);
                if (phaseTicks >= CargoLiftPolicy.RELEASE_TICKS) transition(level, Phase.COMPLETE);
            }
            case HOLD -> tickHold(level, members);
            case EMERGENCY_DESCENT -> tickEmergencyDescent(level, members);
            case COMPLETE -> finish(level, "CONTAINER DELIVERY COMPLETE");
            case ABORTED -> abort(level);
        }
    }

    private void transition(ServerLevel level, Phase next) {
        if (next == Phase.LIFT && !captureContainer(level)) {
            setPhase(Phase.ABORTED, lastReason.isBlank() ? "CONTAINER CAPTURE FAILED" : lastReason);
            return;
        }
        setPhase(next, "READY");
    }

    private void setPhase(Phase next, String reason) {
        if (phase() == next && reason.equals(lastReason)) return;
        entityData.set(PHASE, next.ordinal());
        phaseTicks = 0;
        lastReason = reason;
        if (level() instanceof ServerLevel level) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId());
            if (owner != null) owner.sendSystemMessage(Component.literal("[MORROWGEAR] CONTAINER LIFT / "
                + next.name() + " / " + reason));
        }
    }

    private boolean physicalPhaseReady(ServerLevel level, List<DroneEntity> members) {
        return switch (phase()) {
            case RESERVING -> members.size() == CargoLiftPolicy.REQUIRED_AIRCRAFT;
            case RENDEZVOUS -> members.stream().allMatch(this::inTolerance);
            case CONNECTING -> phaseTicks >= CargoLiftPolicy.CONNECT_TICKS
                && members.stream().allMatch(this::inTolerance);
            case TENSIONING -> phaseTicks >= CargoLiftPolicy.TENSION_TICKS;
            // The load, not only the aircraft, must reach the complete terrain
            // clearance before any horizontal transit begins.
            case LIFT -> position().y >= transitBaseY(level, position());
            case TRANSIT -> horizontalDistance(position(), Vec3.atBottomCenterOf(resolvedDestination(level))) < 1.2;
            // DESCENT performs the inventory-bearing block placement atomically before RELEASE.
            case DESCENT -> false;
            case RELEASE -> phaseTicks >= CargoLiftPolicy.RELEASE_TICKS;
            default -> false;
        };
    }

    private List<CargoLiftPolicy.Aircraft> assessment(List<DroneEntity> members) {
        List<CargoLiftPolicy.Aircraft> result = new ArrayList<>();
        for (Corner corner : Corner.values()) {
            DroneEntity drone = member(members, corner);
            if (drone == null) continue;
            result.add(new CargoLiftPolicy.Aircraft(drone.getUUID(), corner,
                drone.batteryPercent() / 100.0, drone.cargoLiftOperational(missionId()),
                inTolerance(drone), phase().ordinal() >= Phase.CONNECTING.ordinal()
                    && phaseTicks >= CargoLiftPolicy.CONNECT_TICKS));
        }
        return result;
    }

    private boolean inTolerance(DroneEntity drone) {
        Corner corner = cornerFor(drone.getUUID());
        return corner != null && drone.position().distanceTo(aircraftTarget(corner)) <= RENDEZVOUS_TOLERANCE
            && drone.getDeltaMovement().length() < .42;
    }

    private void holdAtSource(ServerLevel level) {
        BlockPos source = sourcePos();
        if (!level.hasChunkAt(source)) { holdPosition(); return; }
        setPos(Vec3.atBottomCenterOf(source));
        setDeltaMovement(Vec3.ZERO);
        rigGuide = position();
        rigGuideVelocity = Vec3.ZERO;
    }

    private void tickLift(ServerLevel level, List<DroneEntity> members) {
        Vec3 guide = guidePosition();
        Vec3 target = new Vec3(guide.x, transitBaseY(level, guide), guide.z);
        advanceRigGuide(target, .12, CargoLiftPolicy.MAX_VERTICAL_SPEED);
        followAircraftSupport(members, .26, CargoLiftPolicy.MAX_VERTICAL_SPEED);
    }

    private void tickTransit(ServerLevel level, List<DroneEntity> members) {
        BlockPos destination = resolvedDestination(level);
        Vec3 horizontalTarget = Vec3.atBottomCenterOf(destination);
        Vec3 guide = guidePosition();
        double baseY = Math.max(transitBaseY(level, guide),
            transitBaseY(level, guide.lerp(horizontalTarget, .15)));
        Vec3 target = guide.y < baseY - .01
            ? new Vec3(guide.x, baseY, guide.z)
            : new Vec3(horizontalTarget.x, Math.max(baseY, guide.y - .05), horizontalTarget.z);
        advanceRigGuide(target, CargoLiftPolicy.MAX_TRANSIT_SPEED, CargoLiftPolicy.MAX_VERTICAL_SPEED);
        followAircraftSupport(members, CargoLiftPolicy.MAX_TRANSIT_SPEED,
            CargoLiftPolicy.MAX_VERTICAL_SPEED);
        enforceTransitClearance(level);
    }

    private void enforceTransitClearance(ServerLevel level) {
        double minimumY = transitBaseY(level, position());
        if (getY() >= minimumY) return;
        setPos(getX(), minimumY, getZ());
        Vec3 velocity = getDeltaMovement();
        if (velocity.y < 0) setDeltaMovement(velocity.x, 0, velocity.z);
    }

    private void tickDescent(ServerLevel level, List<DroneEntity> members) {
        BlockPos destination = resolvedDestination(level);
        if (!landingClear(level, destination)) { setPhase(Phase.HOLD, "LANDING FOOTPRINT OCCUPIED"); return; }
        Vec3 target = Vec3.atBottomCenterOf(destination);
        advanceRigGuide(target, .22, .16);
        followAircraftSupport(members, .22, .16);
        if (position().distanceTo(target) < .16 && placeContainer(level, destination)) {
            setPos(target);
            setDeltaMovement(Vec3.ZERO);
            setPhase(Phase.RELEASE, "CONTAINER GROUNDED");
        }
    }

    private void tickHold(ServerLevel level, List<DroneEntity> members) {
        if (!carried()) { setPhase(Phase.ABORTED, "SOURCE UNAVAILABLE"); return; }
        BlockPos destination = resolvedDestination(level);
        if (landingClear(level, destination)) { setPhase(Phase.DESCENT, "LANDING CLEAR"); return; }
        double angle = level.getGameTime() * .018;
        Vec3 center = Vec3.atBottomCenterOf(destination);
        Vec3 hold = center.add(Math.cos(angle) * 10.0,
            transitBaseY(level, center) + 2.0, Math.sin(angle) * 10.0);
        advanceRigGuide(hold, .32, .16);
        followAircraftSupport(members, .32, .16);
        enforceTransitClearance(level);
    }

    private void tickEmergencyDescent(ServerLevel level, List<DroneEntity> members) {
        if (!carried()) { setPhase(Phase.ABORTED, "NO SUSPENDED LOAD"); return; }
        if (emergencyLanding == null || !landingClear(level, emergencyLanding))
            emergencyLanding = findSafeLanding(level, blockPosition(), 18);
        if (emergencyLanding == null) { holdPosition(); return; }
        Vec3 target = Vec3.atBottomCenterOf(emergencyLanding);
        advanceRigGuide(target, .18, .12);
        followAircraftSupport(members, .18, .12);
        if (position().distanceTo(target) < .18 && placeContainer(level, emergencyLanding)) {
            setPhase(Phase.RELEASE, "EMERGENCY LANDING COMPLETE");
        }
    }

    private boolean captureContainer(ServerLevel level) {
        CarrierCargoContainerBlockEntity container = sourceContainer(level);
        if (container == null || !container.ownerId().equals(ownerId())) return false;
        ItemStack snapshot = container.recoveryItem();
        CarrierExpansionItemData.Identity identity = CarrierExpansionItemData.read(snapshot);
        if (identity == null) return false;
        CarrierExpansionTransactions.Decision begun = CarrierExpansionTransactions.beginLift(
            level, identity, sourcePos(), missionId());
        if (!begun.success()) { lastReason = begun.detail(); return false; }
        cargoId = container.cargoId();
        container.setTransferReserved(true);
        if (!CarrierContainerFootprint.removeContainer(level, sourcePos())) {
            CarrierExpansionTransactions.rollbackToPlaced(level, identity, sourcePos());
            return false;
        }
        cargo = snapshot;
        entityData.set(CARRIED, true);
        return true;
    }

    private boolean placeContainer(ServerLevel level, BlockPos destination) {
        if (!carried() || cargo.isEmpty() || !landingClear(level, destination)) return false;
        CarrierExpansionItemData.Identity identity = CarrierExpansionItemData.read(cargo);
        if (identity == null) return false;
        CarrierExpansionTransactions.Decision finished = CarrierExpansionTransactions.finishLift(
            level, identity, missionId(), destination);
        if (!finished.success()) { lastReason = finished.detail(); return false; }
        if (!level.setBlockAndUpdate(destination, CarrierModule.CARGO_CONTAINER_BLOCK.defaultBlockState())) {
            CarrierExpansionTransactions.beginLift(level, identity, destination, missionId());
            return false;
        }
        if (!(level.getBlockEntity(destination) instanceof CarrierCargoContainerBlockEntity placed)) {
            level.removeBlock(destination, false);
            CarrierExpansionTransactions.beginLift(level, identity, destination, missionId());
            return false;
        }
        placed.initialize(ownerId(), cargo);
        placed.setTransferReserved(false);
        CarrierContainerFootprint.ensure(level, destination);
        cargo = ItemStack.EMPTY;
        entityData.set(CARRIED, false);
        return true;
    }

    private CarrierCargoContainerBlockEntity sourceContainer(ServerLevel level) {
        if (!level.hasChunkAt(sourcePos())
            || !(level.getBlockEntity(sourcePos()) instanceof CarrierCargoContainerBlockEntity container)) return null;
        return cargoId == null || cargoId.equals(container.cargoId()) ? container : null;
    }

    private void finish(ServerLevel level, String reason) {
        releaseAssignments(level, reason);
        discard();
    }

    private void abort(ServerLevel level) {
        CarrierCargoContainerBlockEntity container = sourceContainer(level);
        if (container != null) container.setTransferReserved(false);
        if (carried()) { setPhase(Phase.EMERGENCY_DESCENT, "ABORT WITH LOAD / SAFE LANDING"); return; }
        releaseAssignments(level, "CONTAINER LIFT ABORTED / " + lastReason);
        discard();
    }

    private void releaseAssignments(ServerLevel level, String reason) {
        for (UUID id : aircraft.values()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof DroneEntity drone) drone.completeCargoLift(missionId(), reason);
        }
    }

    private void restoreAssignments(List<DroneEntity> members) {
        for (DroneEntity drone : members) {
            Corner corner = cornerFor(drone.getUUID());
            if (corner != null && !drone.cargoLiftAssignedTo(missionId())) drone.assignCargoLift(this, corner);
        }
    }

	void reportApproachReplan(UUID aircraftId) {
		if (phase() != Phase.RENDEZVOUS || aircraftId == null || !aircraft.containsValue(aircraftId)) return;
		lastReason = "RENDEZVOUS ROUTE REPLAN";
	}

    private List<DroneEntity> members(ServerLevel level) {
        List<DroneEntity> result = new ArrayList<>(CargoLiftPolicy.REQUIRED_AIRCRAFT);
        for (UUID id : aircraft.values()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof DroneEntity drone && drone.isAlive()) result.add(drone);
        }
        return result;
    }

    private DroneEntity member(List<DroneEntity> members, Corner corner) {
        UUID id = aircraft.get(corner);
        return id == null ? null : members.stream().filter(drone -> id.equals(drone.getUUID())).findFirst().orElse(null);
    }

    private Corner cornerFor(UUID drone) {
        return aircraft.entrySet().stream().filter(entry -> entry.getValue().equals(drone))
            .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private BlockPos resolvedDestination(ServerLevel level) {
        BlockPos requested = destinationPos();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, requested.getX(), requested.getZ());
        return new BlockPos(requested.getX(), y, requested.getZ());
    }

    private double transitBaseY(ServerLevel level, Vec3 sample) {
        int highest = level.getMinY();
        int centerX = Mth.floor(sample.x), centerZ = Mth.floor(sample.z);
        for (int x = centerX - 3; x <= centerX + 3; x++) for (int z = centerZ - 2; z <= centerZ + 2; z++)
            highest = Math.max(highest, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
        return CargoLiftPolicy.loadBaseAltitude(highest);
    }

    private boolean landingClear(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || pos.getY() <= level.getMinY() || pos.getY() + 3 >= level.getMaxY()) return false;
        if (!level.getBlockState(pos).canBeReplaced()) return false;
        BlockPos support = pos.below();
        if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) return false;
        AABB box = boundsAt(Vec3.atBottomCenterOf(pos)).inflate(-.04);
        return !level.getBlockCollisions(null, box).iterator().hasNext();
    }

    private BlockPos findSafeLanding(ServerLevel level, BlockPos center, int radius) {
        for (int ring = 0; ring <= radius; ring += 3) {
            for (int x = -ring; x <= ring; x += Math.max(1, ring)) for (int z = -ring; z <= ring; z += Math.max(1, ring)) {
                int wx = center.getX() + x, wz = center.getZ() + z;
                BlockPos candidate = new BlockPos(wx,
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz), wz);
                if (landingClear(level, candidate)) return candidate;
            }
        }
        return null;
    }

    private AABB boundsAt(Vec3 base) {
        return new AABB(base.x - CargoLiftPolicy.LOAD_HALF_LENGTH, base.y,
            base.z - CargoLiftPolicy.LOAD_HALF_WIDTH, base.x + CargoLiftPolicy.LOAD_HALF_LENGTH,
            base.y + CargoLiftPolicy.LOAD_HALF_HEIGHT * 2.0, base.z + CargoLiftPolicy.LOAD_HALF_WIDTH);
    }

    private Vec3 guidePosition() {
        if (rigGuide == null || !Double.isFinite(rigGuide.x) || !Double.isFinite(rigGuide.y)
            || !Double.isFinite(rigGuide.z)) {
            rigGuide = position();
            rigGuideVelocity = Vec3.ZERO;
        }
        return rigGuide;
    }

    private void advanceRigGuide(Vec3 target, double horizontalLimit, double verticalLimit) {
        Vec3 guide = guidePosition();
        Vec3 delta = target.subtract(guide);
        Vec3 horizontal = new Vec3(delta.x, 0, delta.z);
        if (horizontal.length() > horizontalLimit) horizontal = horizontal.normalize().scale(horizontalLimit);
        double vertical = Mth.clamp(delta.y, -verticalLimit, verticalLimit);
        Vec3 requested = new Vec3(horizontal.x, vertical, horizontal.z);
        Vec3 velocity = rigGuideVelocity.scale(.70).add(requested.scale(.30));
        Vec3 velocityHorizontal = velocity.multiply(1, 0, 1);
        if (velocityHorizontal.length() > horizontalLimit)
            velocityHorizontal = velocityHorizontal.normalize().scale(horizontalLimit);
        velocity = new Vec3(velocityHorizontal.x, Mth.clamp(velocity.y, -verticalLimit, verticalLimit),
            velocityHorizontal.z);
        if (horizontal.length() < velocityHorizontal.length())
            velocity = new Vec3(horizontal.x, velocity.y, horizontal.z);
        if (Math.abs(delta.y) < Math.abs(velocity.y))
            velocity = new Vec3(velocity.x, delta.y, velocity.z);
        Vec3 next = guide.add(velocity);
        Vec3 lead = next.subtract(position());
        Vec3 horizontalLead = lead.multiply(1, 0, 1);
        if (horizontalLead.length() > CargoLiftPolicy.MAX_GUIDANCE_LEAD) {
            horizontalLead = horizontalLead.normalize().scale(CargoLiftPolicy.MAX_GUIDANCE_LEAD);
            next = new Vec3(getX() + horizontalLead.x, next.y, getZ() + horizontalLead.z);
        }
        next = new Vec3(next.x, Mth.clamp(next.y,
            getY() - CargoLiftPolicy.MAX_VERTICAL_GUIDANCE_LEAD,
            getY() + CargoLiftPolicy.MAX_VERTICAL_GUIDANCE_LEAD), next.z);
        rigGuideVelocity = next.subtract(guide);
        rigGuide = next;
    }

    /**
     * The suspended entity follows the actual four-aircraft support centroid. It never follows
     * the navigation guide directly, so the load cannot run ahead when entity tick order changes.
     */
    private void followAircraftSupport(List<DroneEntity> members, double horizontalLimit,
            double verticalLimit) {
        if (members.size() != CargoLiftPolicy.REQUIRED_AIRCRAFT) { holdPosition(); return; }
        double x = 0, y = 0, z = 0;
        for (Corner corner : Corner.values()) {
            DroneEntity drone = member(members, corner);
            if (drone == null) { holdPosition(); return; }
            CargoLiftPolicy.Offset offset = CargoLiftPolicy.formationOffset(corner);
            x += drone.getX() - offset.x();
            y += drone.getY() - (CargoLiftPolicy.LOAD_HALF_HEIGHT * 2.0
                + CargoLiftPolicy.CABLE_LENGTH + SalvageTowPolicy.HOOK_DROP);
            z += drone.getZ() - offset.z();
        }
        Vec3 support = new Vec3(x / CargoLiftPolicy.REQUIRED_AIRCRAFT,
            y / CargoLiftPolicy.REQUIRED_AIRCRAFT, z / CargoLiftPolicy.REQUIRED_AIRCRAFT);
        Vec3 delta = support.subtract(position());
        Vec3 horizontal = delta.multiply(1, 0, 1);
        if (horizontal.length() > horizontalLimit)
            horizontal = horizontal.normalize().scale(horizontalLimit);
        Vec3 step = new Vec3(horizontal.x, Mth.clamp(delta.y, -verticalLimit, verticalLimit), horizontal.z);
        setDeltaMovement(step);
        setPos(position().add(step));
    }

    private void holdPosition() {
        setDeltaMovement(getDeltaMovement().scale(.72));
        if (getDeltaMovement().lengthSqr() < .0001) setDeltaMovement(Vec3.ZERO);
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static UUID parseUuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return new UUID(0, 0); }
    }

	@Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (amount <= 0) return false;
		cancelRequested = true;
		return true;
	}

    @Override protected void addAdditionalSaveData(ValueOutput output) {
        output.putString("Mission", entityData.get(MISSION));
        output.putString("Owner", entityData.get(OWNER));
        output.putInt("Phase", phase().ordinal());
        output.putLong("Source", entityData.get(SOURCE));
        output.putLong("Destination", entityData.get(DESTINATION));
        output.putBoolean("Carried", carried());
        output.putInt("PhaseTicks", phaseTicks);
        output.putString("Reason", lastReason);
        if (rigGuide != null) {
            output.putBoolean("HasRigGuide", true);
            output.putDouble("RigGuideX", rigGuide.x);
            output.putDouble("RigGuideY", rigGuide.y);
            output.putDouble("RigGuideZ", rigGuide.z);
            output.putDouble("RigGuideVelocityX", rigGuideVelocity.x);
            output.putDouble("RigGuideVelocityY", rigGuideVelocity.y);
            output.putDouble("RigGuideVelocityZ", rigGuideVelocity.z);
        }
        if (cargoId != null) output.putString("CargoId", cargoId.toString());
        if (!cargo.isEmpty()) output.store("Cargo", ItemStack.CODEC, cargo);
        for (Map.Entry<Corner, UUID> entry : aircraft.entrySet())
            output.putString("Aircraft" + entry.getKey().ordinal(), entry.getValue().toString());
    }

    @Override protected void readAdditionalSaveData(ValueInput input) {
        entityData.set(MISSION, input.getStringOr("Mission", UUID.randomUUID().toString()));
        entityData.set(OWNER, input.getStringOr("Owner", new UUID(0, 0).toString()));
        entityData.set(PHASE, Mth.clamp(input.getIntOr("Phase", Phase.RESERVING.ordinal()), 0, Phase.values().length - 1));
        entityData.set(SOURCE, input.getLongOr("Source", BlockPos.ZERO.asLong()));
        entityData.set(DESTINATION, input.getLongOr("Destination", BlockPos.ZERO.asLong()));
        entityData.set(CARRIED, input.getBooleanOr("Carried", false));
        phaseTicks = Math.max(0, input.getIntOr("PhaseTicks", 0));
        lastReason = input.getStringOr("Reason", "RELOADED");
        if (input.getBooleanOr("HasRigGuide", false)) {
            rigGuide = new Vec3(input.getDoubleOr("RigGuideX", getX()),
                input.getDoubleOr("RigGuideY", getY()), input.getDoubleOr("RigGuideZ", getZ()));
            rigGuideVelocity = new Vec3(input.getDoubleOr("RigGuideVelocityX", 0),
                input.getDoubleOr("RigGuideVelocityY", 0), input.getDoubleOr("RigGuideVelocityZ", 0));
        } else {
            rigGuide = null;
            rigGuideVelocity = Vec3.ZERO;
        }
        try { cargoId = UUID.fromString(input.getStringOr("CargoId", "")); }
        catch (IllegalArgumentException ignored) { cargoId = null; }
        cargo = input.read("Cargo", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        aircraft.clear();
        for (Corner corner : Corner.values()) {
            try { aircraft.put(corner, UUID.fromString(input.getStringOr("Aircraft" + corner.ordinal(), ""))); }
            catch (IllegalArgumentException ignored) { }
        }
    }
}
