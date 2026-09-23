package jp.morrowgear.drone.carrier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Airborne exterior. Blocks and block entities live in the UUID-assigned cabin, never on this entity. */
public final class CarrierEntity extends Entity {
    private static final EntityDataAccessor<Integer> MODE = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> STATUS = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CURSOR = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TOTAL = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> TARGET = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<BlockPos> AREA_MIN = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<BlockPos> AREA_MAX = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<String> GENERATION = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> BEAM = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<org.joml.Vector3fc> AIM = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<java.util.Optional<BlockPos>> BOARDING_PAD = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<String> OWNER = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> ROLL = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> PHASE_TICK = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SAMPLE_TICK = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> COMBAT_RADIUS = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> AIM_COUNT = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final java.util.List<EntityDataAccessor<org.joml.Vector3fc>> AIMS = java.util.stream.IntStream.range(0, CarrierPolicy.COMBAT_TARGETS)
        .mapToObj(i -> SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.VECTOR3)).toList();
    private static final EntityDataAccessor<Integer> MINED = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CAPTURED = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> CAPTURE_POS = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Integer> BEAM_SEQUENCE = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private final CarrierBoardingPreview boardingPreview = new CarrierBoardingPreview();
    private final InterpolationHandler interpolation = new InterpolationHandler(this, 2);
    private float hull = 400;
    private int beamTicks;
    private CarrierPolicy.WorkPhase sampledWorkPhase = CarrierPolicy.WorkPhase.IDLE;
    private int sampledPhaseTick, sampledWorkTick = -1;
    private float priorFlightYaw;
    private boolean maneuvering;
    private int bayClearWait;
    private long flightChecks, flightNanos, maxFlightNanos, heightQueries;
    private java.util.List<net.minecraft.world.item.ItemStack> lastCaptureDrops = java.util.List.of();
    public CarrierEntity(EntityType<? extends CarrierEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }
    public boolean deploy(ServerPlayer owner, Vec3 position) {
        if (!(level() instanceof ServerLevel level) || CarrierInterior.inside(level)) return false;
        setPos(position);
        if (!flightClear(level, position)) return false;
        CarrierSavedData.get(level.getServer()).assign(getUUID(), owner.getUUID(), CarrierAnchor.at(this));
        CarrierInterior.deferBoarding(owner);
        return true;
    }
    public CarrierShip ship() {
        return level() instanceof ServerLevel level ? CarrierSavedData.get(level.getServer()).ship(getUUID()) : null;
    }
    public record FlightMetrics(long checks, long totalNanos, long maxNanos, long heightQueries) {}
    public FlightMetrics flightMetrics() { return new FlightMetrics(flightChecks, flightNanos, maxFlightNanos, heightQueries); }
    /** Belly-plane origin; local +X starboard, +Z aft, nose -Z. Positive Y rotation matches the renderer. */
    public Vec3 toWorld(Vec3 local) { return CarrierNavigation.toWorld(position(), getYRot(), local); }
    public Vec3 toLocal(Vec3 world) { return CarrierNavigation.toLocal(position(), getYRot(), world); }
    public float flightRoll() { return entityData.get(ROLL); }
    public float flightRoll(float partialTick) { return flightRoll(); }
    public Vec3 beamOrigin() { return CarrierBeamPath.emitter(position(), getYRot()); }
    public Vec3 boardingProjection() { return toWorld(new Vec3(CarrierPolicy.BOARDING_X, 0, CarrierPolicy.BOARDING_Z)); }
    public Vec3 bayPosition(int slot) {
        return toWorld(new Vec3(CarrierPolicy.bayX(slot), 2.4, CarrierPolicy.bayZ(slot)));
    }
    public Vec3 bayApproachPosition(int slot) {
        return toWorld(new Vec3(CarrierPolicy.bayX(slot), -2, CarrierPolicy.bayZ(slot)));
    }
    public Vec3 bayVelocity(int slot) {
        Vec3 local = new Vec3(CarrierPolicy.bayX(slot), 0, CarrierPolicy.bayZ(slot));
        return getDeltaMovement().add(CarrierNavigation.rotate(local, getYRot()).subtract(CarrierNavigation.rotate(local, priorFlightYaw)));
    }
    public boolean bayServiceReady() { return !maneuvering; }
    public boolean inBoardingProjection(Vec3 world) {
        Vec3 local = toLocal(world);
        return CarrierPolicy.inBoardingProjection(local.x, local.z);
    }
    /** Rotation can put an aft projection over the mining chunk. Never use that column as a landing pad. */
    public boolean outsideWorkColumn(BlockPos pos) {
        CarrierShip ship = ship();
        if (ship == null) return false;
        var op = ship.mode == CarrierPolicy.Mode.MINING && ship.progress != null ? ship.progress.operation() : ship.preview;
        return op == null || (pos.getX() >> 4) != op.chunkX() || (pos.getZ() >> 4) != op.chunkZ();
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MODE, 0); builder.define(STATUS, 0); builder.define(CURSOR, 0); builder.define(TOTAL, 0);
        builder.define(TARGET, BlockPos.ZERO); builder.define(AREA_MIN, BlockPos.ZERO); builder.define(AREA_MAX, BlockPos.ZERO);
        builder.define(GENERATION, "");
        builder.define(BEAM, false);
        builder.define(AIM, new org.joml.Vector3f());
        builder.define(BOARDING_PAD, java.util.Optional.empty());
        builder.define(OWNER, "");
        builder.define(ROLL, 0f);
        builder.define(PHASE, 0); builder.define(PHASE_TICK, 0); builder.define(SAMPLE_TICK, 0);
        builder.define(COMBAT_RADIUS, 0); builder.define(AIM_COUNT, 0);
        for (var aim : AIMS) builder.define(aim, new org.joml.Vector3f());
        builder.define(MINED, 0); builder.define(CAPTURED, 0); builder.define(CAPTURE_POS, BlockPos.ZERO);
        builder.define(BEAM_SEQUENCE, 0);
    }
    public CarrierPolicy.Mode workMode() { return CarrierPolicy.Mode.values()[entityData.get(MODE)]; }
    public CarrierPolicy.Stop workStatus() { return CarrierPolicy.Stop.values()[entityData.get(STATUS)]; }
    public int workCursor() { return entityData.get(CURSOR); }
    public int workTotal() { return entityData.get(TOTAL); }
    public BlockPos beamTarget() { return entityData.get(TARGET); }
    public BlockPos operationMin() { return entityData.get(AREA_MIN); }
    public BlockPos operationMax() { return entityData.get(AREA_MAX); }
    public String operationGeneration() { return entityData.get(GENERATION); }
    public boolean beamActive() { return entityData.get(BEAM); }
    public CarrierPolicy.WorkPhase workPhase() { return CarrierPolicy.WorkPhase.values()[entityData.get(PHASE)]; }
    public int phaseTick() { return entityData.get(PHASE_TICK); }
    public int phaseDuration() {
        if (workMode() != CarrierPolicy.Mode.MINING) return CarrierPolicy.phaseDuration(workPhase());
        return switch (workPhase()) {
            case SCAN -> CarrierPolicy.SCAN_TICKS;
            case CHARGE -> CarrierPolicy.MINING_CHARGE_TICKS;
            default -> 0;
        };
    }
    public int workPhaseTick() { return phaseTick(); }
    public int workPhaseDuration() { return phaseDuration(); }
    public int sampleTick() { return entityData.get(SAMPLE_TICK); }
    public int combatRadius() { return entityData.get(COMBAT_RADIUS); }
    /** Session counters; only actual block removal followed by successful inventory commit increments them. */
    public int minedBlocks() { return entityData.get(MINED); }
    public int capturedItems() { return entityData.get(CAPTURED); }
    public int captureSequence() { return minedBlocks(); }
    public int beamSequence() { return entityData.get(BEAM_SEQUENCE); }
    public BlockPos lastCapturePosition() { return entityData.get(CAPTURE_POS); }
    public java.util.List<net.minecraft.world.item.ItemStack> lastCaptureDrops() {
        return lastCaptureDrops.stream().map(net.minecraft.world.item.ItemStack::copy).toList();
    }
    void recordCapture(BlockPos pos, java.util.List<net.minecraft.world.item.ItemStack> drops) {
        lastCaptureDrops = drops.stream().map(net.minecraft.world.item.ItemStack::copy).toList();
        long items = drops.stream().mapToLong(net.minecraft.world.item.ItemStack::getCount).sum();
        entityData.set(MINED, (int) Math.min(Integer.MAX_VALUE, (long) minedBlocks() + 1));
        entityData.set(CAPTURED, (int) Math.min(Integer.MAX_VALUE, (long) capturedItems() + items));
        entityData.set(CAPTURE_POS, pos.immutable());
    }
    public java.util.List<Vec3> beamAims() {
        return beamPaths().stream().map(CarrierBeamPath::target).toList();
    }
    public java.util.List<CarrierBeamPath> beamPaths() {
        if (!beamActive()) return java.util.List.of();
        Vec3 origin = beamOrigin();
        var result = new java.util.ArrayList<CarrierBeamPath>();
        for (int i = 0; i < entityData.get(AIM_COUNT); i++) {
            var aim = entityData.get(AIMS.get(i));
            CarrierBeamPath path = CarrierBeamPath.decode(origin, new Vec3(aim.x(), aim.y(), aim.z()));
            if (path.visibleDownwardRay()) result.add(path);
        }
        return java.util.List.copyOf(result);
    }
    /** Safe feet position sampled for the online owner; not an authorization token for other viewers. */
    public java.util.Optional<BlockPos> visualBoardingPad() { return entityData.get(BOARDING_PAD); }
    public boolean isOwnedBy(java.util.UUID player) { return player != null && entityData.get(OWNER).equals(player.toString()); }
    private void updateBoardingVisual(ServerLevel level, CarrierShip ship, ServerPlayer owner) {
        entityData.set(OWNER, ship.owner.toString());
        boolean available = owner != null && ship.owner.equals(owner.getUUID()) && !ship.destroyed && !isRemoved()
            && owner.level() == level && owner.isAlive() && !owner.isSpectator() && !owner.isPassenger()
            && CarrierInterior.ready(owner) && distanceToSqr(owner) <= 128 * 128;
        var pad = boardingPreview.update(level.getGameTime(), boardingProjection(), available,
            pos -> level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
                && inBoardingProjection(Vec3.atBottomCenterOf(pos)) && outsideWorkColumn(pos)
                && getY() - pos.getY() > 4 && getY() - pos.getY() <= 96,
            () -> CarrierInterior.boardingPad(this, owner));
        entityData.set(BOARDING_PAD, pad);
    }
    private void clearBoardingVisual() {
        boardingPreview.clear();
        entityData.set(BOARDING_PAD, java.util.Optional.empty());
    }
    public Vec3 beamAim() {
        return beamPath().target();
    }
    public CarrierBeamPath beamPath() {
        var aim = entityData.get(AIM);
        Vec3 origin = beamOrigin();
        return CarrierBeamPath.decode(origin, new Vec3(aim.x(), aim.y(), aim.z()));
    }
    void recordBeam(BlockPos target) { recordBeam(Vec3.atCenterOf(target)); }
    void recordBeam(Vec3 target) {
        setBeam(target);
        advanceBeamSequence();
        beamTicks = 3;
    }
    private void setBeam(Vec3 target) {
        entityData.set(TARGET, BlockPos.containing(target));
        // Relative coordinates keep sub-block accuracy even at the world border.
        Vec3 relative = CarrierBeamPath.encodeTarget(beamOrigin(), target);
        entityData.set(AIM, new org.joml.Vector3f((float) relative.x, (float) relative.y, (float) relative.z));
        entityData.set(AIMS.get(0), new org.joml.Vector3f((float) relative.x, (float) relative.y, (float) relative.z));
        entityData.set(AIM_COUNT, 1);
    }
    void recordBeams(java.util.List<Vec3> targets) {
        if (targets.isEmpty()) { clearBeams(); return; }
        setBeam(targets.getFirst());
        int count = Math.min(targets.size(), CarrierPolicy.COMBAT_TARGETS);
        for (int i = 0; i < count; i++) {
            Vec3 relative = CarrierBeamPath.encodeTarget(beamOrigin(), targets.get(i));
            entityData.set(AIMS.get(i), new org.joml.Vector3f((float) relative.x, (float) relative.y, (float) relative.z));
        }
        entityData.set(AIM_COUNT, count);
        CarrierShip ship = ship();
        if (ship != null && ship.workPhase() == CarrierPolicy.WorkPhase.FIRE
            && (ship.mode == CarrierPolicy.Mode.MINING || CarrierPolicy.phaseTick(ship.combatRemaining) % CarrierPolicy.COMBAT_PULSE_TICKS == 0))
            advanceBeamSequence();
        beamTicks = 3;
    }
    private void advanceBeamSequence() {
        int sequence = entityData.get(BEAM_SEQUENCE);
        entityData.set(BEAM_SEQUENCE, sequence == Integer.MAX_VALUE ? 1 : sequence + 1);
    }
    void clearBeams() {
        beamTicks = 0;
        entityData.set(AIM_COUNT, 0);
        entityData.set(BEAM, false);
    }
    private void syncWork(CarrierShip ship) {
        entityData.set(MODE, ship.mode.ordinal()); entityData.set(STATUS, ship.stop.ordinal());
        entityData.set(BEAM, ship.mode != CarrierPolicy.Mode.IDLE && beamTicks > 0);
        // Endpoints belong to the phase that produced them, not the next tick's phase.
        var phase = ship.mode != CarrierPolicy.Mode.IDLE && sampledWorkTick == tickCount
            ? sampledWorkPhase : ship.workPhase();
        entityData.set(PHASE, phase.ordinal());
        entityData.set(PHASE_TICK, ship.mode != CarrierPolicy.Mode.IDLE && sampledWorkTick == tickCount
            ? sampledPhaseTick : ship.workPhaseTick());
        entityData.set(SAMPLE_TICK, tickCount);
        boolean combat = ship.mode == CarrierPolicy.Mode.COMBAT || ship.preview != null && ship.previewMode == CarrierPolicy.Mode.COMBAT;
        entityData.set(COMBAT_RADIUS, combat ? CarrierPolicy.COMBAT_RADIUS : 0);
        var op = ship.preview != null ? ship.preview : ship.activeCombat != null ? ship.activeCombat
            : ship.progress == null ? null : ship.progress.operation();
        int cursor = ship.progress != null && ship.progress.operation() == op ? ship.progress.cursor() : 0;
        entityData.set(CURSOR, cursor); entityData.set(TOTAL, op == null ? 0 : op.volume());
        entityData.set(GENERATION, op == null ? "" : op.generation().toString());
        if (op != null) {
            entityData.set(AREA_MIN, new BlockPos(op.chunkX() * 16, op.minY(), op.chunkZ() * 16));
            entityData.set(AREA_MAX, new BlockPos(op.chunkX() * 16 + 15, op.maxY(), op.chunkZ() * 16 + 15));
            int current = Math.min(cursor, op.volume() - 1);
            if (beamTicks <= 0) entityData.set(TARGET, new BlockPos(op.x(current), op.y(current), op.z(current)));
        }
    }
    @Override protected AABB makeBoundingBox(Vec3 position) {
        return CarrierNavigation.bounds(position, getYRot());
    }
    public static AABB exteriorBounds(Vec3 position) {
        return new AABB(position.x - CarrierPolicy.WIDTH / 2, position.y,
            position.z - CarrierPolicy.LENGTH / 2, position.x + CarrierPolicy.WIDTH / 2,
            position.y + CarrierPolicy.HEIGHT, position.z + CarrierPolicy.LENGTH / 2);
    }
    @Override public boolean isPickable() { return true; }
    @Override public InterpolationHandler getInterpolation() { return interpolation; }
    @Override public boolean canUsePortal(boolean allowVehicles) { return false; }
    public boolean boardingZone(ServerPlayer player) {
        return player.level() == level() && inBoardingProjection(player.position()) && outsideWorkColumn(player.blockPosition())
            && player.getY() <= getY()
            && getY() - player.getY() <= 96;
    }
    public boolean controls(ServerPlayer player) {
        return "READY".equals(controlRefusalReason(player));
    }
    public String controlRefusalReason(ServerPlayer player) {
        CarrierShip ship = ship();
        if (ship == null) return "SHIP_UNAVAILABLE";
        if (player == null) return "OWNER_OFFLINE";
        if (!ship.owner.equals(player.getUUID())) return "OWNER_MISMATCH";
        if (!player.isAlive()) return "OWNER_NOT_ALIVE";
        if (player.isSpectator()) return "OWNER_SPECTATING";
        if (getUUID().equals(CarrierInterior.currentShip(player))) return "READY";
        if (player.level() != level()) return "WRONG_DIMENSION";
        double distanceSquared = player.distanceToSqr(this);
        if (distanceSquared > 128 * 128) return "OUT_OF_RANGE_" + Math.round(Math.sqrt(distanceSquared));
        return "READY";
    }
    boolean hasOperationalOwner(ServerPlayer player) {
        CarrierShip ship = ship();
        return ship != null && player != null && ship.owner.equals(player.getUUID())
            && player.level().getServer() != null;
    }
    @Override public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (player instanceof ServerPlayer serverPlayer) CarrierMenu.open(serverPlayer, getUUID());
        return InteractionResult.SUCCESS;
    }
    @Override public void tick() {
        super.tick();
        setNoGravity(true);
        if (level().isClientSide()) {
            interpolation.interpolate();
            setXRot(0);
            setBoundingBox(makeBoundingBox(position()));
            return;
        }
        setXRot(0);
        setBoundingBox(makeBoundingBox(position()));
        if (beamTicks > 0) beamTicks--;
        if (!(level() instanceof ServerLevel level)) return;
        Vec3 previousVelocity = getDeltaMovement();
        priorFlightYaw = getYRot(); maneuvering = false;
        setDeltaMovement(Vec3.ZERO);
        entityData.set(ROLL, 0f);
        CarrierShip ship = ship();
        if (ship == null || ship.destroyed || CarrierInterior.inside(level)) { discard(); return; }
        CarrierInterior.touchBoarding(this);
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ship.owner);
        updateBoardingVisual(level, ship, owner);
        // Range authorizes a new command. It must not cancel an accepted autonomous
        // journey as soon as the carrier leaves the 128-block command radius.
        if (owner == null) {
            if (ship.mode != CarrierPolicy.Mode.IDLE || ship.destination != null && !ship.navigationPaused) ship.stop(CarrierPolicy.Stop.OWNER_ABSENT);
            ship.bay.cancelAll(this, CarrierServiceBay.Release.SHIP_UNAVAILABLE);
            syncWork(ship);
            return;
        }
        CarrierSupplies.tick(ship, level.getGameTime());
        if (ship.destination != null && !ship.navigationPaused) {
            Vec3 destination = CarrierNavigation.destination(ship.destination);
            Vec3 delta = destination.subtract(position());
            if (CarrierNavigation.arrived(position(), destination)) { ship.destination = null; ship.stop = CarrierPolicy.Stop.COMPLETE; ship.dirty.run(); }
            else {
                double speedLimit = CarrierPowerPolicy.flightSpeed(ship.energy);
                var motion = CarrierNavigation.advance(position(), destination, getYRot(), previousVelocity, speedLimit);
                Vec3 step = motion.velocity();
                maneuvering = Math.abs(CarrierNavigation.angleDifference(CarrierNavigation.heading(delta, getYRot()), getYRot())) > .0001;
                boolean turning = Math.abs(CarrierNavigation.angleDifference(motion.yaw(), getYRot())) > .0001;
                AABB sweep = CarrierNavigation.sweep(position(), getYRot(), position().add(step), motion.yaw());
                // A 50 m hull's swept AABB contains its deck, nearby escorts and a large
                // volume below it. Treating every living entity in that box as solid made
                // the owner's own presence cancel every turn. Bay hardware and blocks are
                // the safety interlocks; ordinary entities are handled by normal movement.
                if (turning && !ship.bay.clearForTurn(this)) {
                    if (++bayClearWait >= CarrierNavigation.LOAD_WAIT_TICKS) ship.pauseNavigation(CarrierPolicy.Stop.OBSTRUCTED);
                } else if (!envelopeLoaded(level, sweep)) {
                    ship.stop = CarrierPolicy.Stop.UNLOADED;
                    if (++ship.navigationWaitTicks >= CarrierNavigation.LOAD_WAIT_TICKS) ship.pauseNavigation(CarrierPolicy.Stop.UNLOADED);
                } else if (!flightSweepClear(level, position(), getYRot(), position().add(step), motion.yaw())) ship.pauseNavigation(CarrierPolicy.Stop.OBSTRUCTED);
                else {
                    bayClearWait = 0;
                    ship.navigationWaitTicks = 0;
                    ship.stop = speedLimit < CarrierNavigation.SPEED ? CarrierPolicy.Stop.NO_POWER : CarrierPolicy.Stop.RUNNING;
                    setYRot(motion.yaw()); setDeltaMovement(step); setPos(position().add(step));
                    int boostCost = CarrierPowerPolicy.boostCost(previousVelocity, step);
                    if (boostCost > 0) ship.energy = Math.max(0, ship.energy - boostCost);
                    ship.dirty.run();
                }
            }
        } else bayClearWait = 0;
        if (ship.mode != CarrierPolicy.Mode.IDLE) {
            sampledWorkPhase = ship.workPhase();
            sampledPhaseTick = ship.workPhaseTick();
            sampledWorkTick = tickCount;
            try { CarrierWork.tick(this, ship, level, owner); }
            catch (RuntimeException failure) {
                ship.stop(CarrierPolicy.Stop.EMERGENCY);
                jp.morrowgear.drone.MorrowgearDrone.LOGGER.error("Carrier {} work stopped after an operation failure", getUUID(), failure);
            }
        }
        ship.bay.tick(this, ship, level.getGameTime());
        updateBoardingVisual(level, ship, owner);
        syncWork(ship);
        if (tickCount % 20 == 0) { ship.exterior = CarrierAnchor.at(this); ship.dirty.run(); }
    }
    public boolean setDestination(ServerPlayer player, BlockPos target) {
        CarrierShip ship = ship();
        if (!"READY".equals(destinationRefusalReason(player, target))) return false;
        ship.stop(CarrierPolicy.Stop.READY);
        ship.destination = new CarrierAnchor(level().dimension().identifier().toString(), target.getX(), target.getY(), target.getZ());
        ship.navigationPaused = false;
        bayClearWait = 0;
        ship.stop = CarrierPolicy.Stop.RUNNING;
        CarrierChunkLeases.requestResume(getUUID());
        ship.dirty.run();
        return true;
    }

    public String destinationRefusalReason(ServerPlayer player, BlockPos target) {
        CarrierShip ship = ship();
        if (ship == null) return "SHIP_UNAVAILABLE";
        String control = controlRefusalReason(player);
        if (!"READY".equals(control)) return "CONTROL_DENIED_" + control;
        if (ship.mode != CarrierPolicy.Mode.IDLE) return "MODE_" + ship.mode;
        if (!CarrierNavigation.validTarget(CarrierNavigation.destination(target), level().getMinY(), level().getMaxY()))
            return "INVALID_ALTITUDE_OR_COORDINATE";
        ServerLevel serverLevel = (ServerLevel) level();
        if (!serverLevel.getWorldBorder().isWithinBounds(target)) return "TARGET_OUTSIDE_WORLD_BORDER";
        if (!serverLevel.getWorldBorder().isWithinBounds(target.offset(30, 0, 30))) return "POSITIVE_ENVELOPE_OUTSIDE_WORLD_BORDER";
        if (!serverLevel.getWorldBorder().isWithinBounds(target.offset(-30, 0, -30))) return "NEGATIVE_ENVELOPE_OUTSIDE_WORLD_BORDER";
        return "READY";
    }
    private boolean flightClear(ServerLevel level, Vec3 position) {
        return flightSweepClear(level, position, getYRot(), position, getYRot());
    }
    private static boolean envelopeLoaded(ServerLevel level, AABB box) {
        for (int x = (int) Math.floor(box.minX / 16); x <= (int) Math.floor(box.maxX / 16); x++)
            for (int z = (int) Math.floor(box.minZ / 16); z <= (int) Math.floor(box.maxZ / 16); z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }
    private boolean flightSweepClear(ServerLevel level, Vec3 from, float fromYaw, Vec3 position, float toYaw) {
        long start = System.nanoTime();
        flightChecks++;
        try { return evaluateFlightSweep(level, from, fromYaw, position, toYaw); }
        finally {
            long elapsed = System.nanoTime() - start;
            flightNanos += elapsed;
            maxFlightNanos = Math.max(maxFlightNanos, elapsed);
        }
    }
    private boolean evaluateFlightSweep(ServerLevel level, Vec3 from, float fromYaw, Vec3 position, float toYaw) {
        AABB box = CarrierNavigation.sweep(from, fromYaw, position, toYaw);
        if (position.y < level.getMinY() + 16 || box.maxY >= level.getMaxY()
            || !level.getWorldBorder().isWithinBounds(BlockPos.containing(box.minX, box.minY, box.minZ))
            || !level.getWorldBorder().isWithinBounds(BlockPos.containing(box.maxX, box.maxY, box.maxZ))) return false;
        if (!envelopeLoaded(level, box)) return false;
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++)
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                heightQueries++;
                if (Math.min(from.y, position.y) < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                    + CarrierNavigation.GROUND_CLEARANCE) return false;
            }
        return level.noCollision(this, box);
    }
    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        CarrierShip ship = ship();
        if (ship == null || !Float.isFinite(amount) || amount <= 0) return false;
        if (source.getEntity() instanceof Player player && !ship.owner.equals(player.getUUID())) return false;
        hull -= amount;
        ship.stop(CarrierPolicy.Stop.EMERGENCY);
        if (hull <= 0) remove(RemovalReason.KILLED);
        return true;
    }
    @Override public void remove(RemovalReason reason) {
        clearBoardingVisual();
        if (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED) {
            CarrierShip ship = ship();
            if (ship != null && !ship.destroyed) {
                ship.destroyed = true;
                ship.stop(CarrierPolicy.Stop.DESTROYED);
                ship.bay.tick(this, ship, level().getGameTime());
            }
        } else {
            CarrierShip ship = ship();
            if (ship != null) {
                ship.stop(CarrierPolicy.Stop.RELOAD);
                ship.bay.cancelAll(this, CarrierServiceBay.Release.SHIP_UNAVAILABLE);
            }
        }
        super.remove(reason);
    }
    @Override protected void addAdditionalSaveData(ValueOutput output) { output.putFloat("CarrierHull", hull); }
    @Override protected void readAdditionalSaveData(ValueInput input) {
        hull = Math.clamp(input.getFloatOr("CarrierHull", 400), 1, 400);
        setNoGravity(true);
        setXRot(0); setDeltaMovement(Vec3.ZERO);
        priorFlightYaw = getYRot(); maneuvering = false; bayClearWait = 0;
    }
}
