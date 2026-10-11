package jp.morrowgear.drone.carrier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Airborne exterior. Blocks and block entities live in the UUID-assigned cabin, never on this entity. */
public final class CarrierEntity extends Entity {
    private record SavedExpansion(int hardpoint,ItemStack stack) {
        static final com.mojang.serialization.Codec<SavedExpansion> CODEC=
            com.mojang.serialization.codecs.RecordCodecBuilder.create(i->i.group(
                com.mojang.serialization.Codec.intRange(0,CarrierExpansionPolicy.HARDPOINTS-1)
                    .fieldOf("hardpoint").forGetter(SavedExpansion::hardpoint),
                ItemStack.CODEC.fieldOf("stack").forGetter(SavedExpansion::stack)
            ).apply(i,SavedExpansion::new));
    }
    private record SavedMissileLaunch(java.util.UUID target,int shot,int hardpoint,int cell) {
        static final com.mojang.serialization.Codec<SavedMissileLaunch> CODEC=
            com.mojang.serialization.codecs.RecordCodecBuilder.create(i->i.group(
                CarrierPolicy.UUID_CODEC.fieldOf("target").forGetter(SavedMissileLaunch::target),
                com.mojang.serialization.Codec.intRange(0,CarrierMissileModulePolicy.MAX_SALVO-1)
                    .fieldOf("shot").forGetter(SavedMissileLaunch::shot),
                com.mojang.serialization.Codec.INT.optionalFieldOf("hardpoint",-1).forGetter(SavedMissileLaunch::hardpoint),
                com.mojang.serialization.Codec.INT.optionalFieldOf("cell",-1).forGetter(SavedMissileLaunch::cell)
            ).apply(i,SavedMissileLaunch::new));
        boolean validSource(){
            return hardpoint>=0&&hardpoint<CarrierExpansionPolicy.HARDPOINTS&&cell>=0
                &&cell<CarrierMissileMagazinePolicy.CAPACITY&&CarrierCargoVisualPolicy.ordnanceCapable(hardpoint);
        }
        CarrierExpansionBay.MissileCell source(){return new CarrierExpansionBay.MissileCell(hardpoint,cell);}
    }
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
    private static final EntityDataAccessor<Integer> COMBAT_WEAPON = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> AIM_COUNT = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final java.util.List<EntityDataAccessor<org.joml.Vector3fc>> AIMS = java.util.stream.IntStream.range(0, CarrierPolicy.COMBAT_TARGETS)
        .mapToObj(i -> SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.VECTOR3)).toList();
    private static final EntityDataAccessor<Integer> MINED = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CAPTURED = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> CAPTURE_POS = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Integer> BEAM_SEQUENCE = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MINING_SURFACE_Y = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> PLASMA_RESPONSE = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CARGO_MODULES = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CARGO_HARDPOINTS = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MISSILE_HARDPOINTS = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> TRANSFER_ACTIVE = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> TRANSFER_PHASE = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TRANSFER_TICK = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TRANSFER_DIRECTION = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TRANSFER_TYPE = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TRANSFER_HARDPOINT = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> TRANSFER_POS = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Integer> MISSILE_LAUNCH_TICKS = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MISSILE_SALVO_SIZE = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MISSILE_FIRED = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MISSILE_SOURCE_MASK = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MISSILE_LOCK_COUNT = SynchedEntityData.defineId(CarrierEntity.class, EntityDataSerializers.INT);
    private static final java.util.List<EntityDataAccessor<Integer>> MISSILE_LOCKS = java.util.stream.IntStream
        .range(0,CarrierMissileModulePolicy.MAX_PARALLEL_LOCKS)
        .mapToObj(i->SynchedEntityData.defineId(CarrierEntity.class,EntityDataSerializers.INT)).toList();
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
    private CarrierExpansionBay expansionBay = new CarrierExpansionBay(java.util.List.of(), this::markShipDirty);
    private ContainerTransfer containerTransfer;
    private String containerTransferDetail = "READY";
    private final java.util.ArrayList<SavedMissileLaunch> heavyMissileQueue = new java.util.ArrayList<>();
    private int heavyMissileSequenceTick = -1;
    private int heavyMissilesFired;
    private boolean expansionLedgerSynchronized;
    private static final class ContainerTransfer {
        final CarrierContainerTransferPolicy.Direction direction;
        CarrierContainerTransferPolicy.Phase phase;
        final BlockPos containerPos;
        final int hardpoint;
        final CarrierExpansionPolicy.Type type;
        int ticks;
        int recoveryAttempts;
        int stagnantTicks;
        double lastDistance=Double.POSITIVE_INFINITY;
        double approachY=Double.NaN;
        float alignmentYaw=Float.NaN;
        java.util.List<Vec3> route=java.util.List.of();
        int routeIndex;
        ContainerTransfer(CarrierContainerTransferPolicy.Direction direction, BlockPos pos, int hardpoint,
                          CarrierExpansionPolicy.Type type) {
            this(direction,pos,hardpoint,type,CarrierContainerTransferPolicy.Phase.APPROACH,0);
        }
        ContainerTransfer(CarrierContainerTransferPolicy.Direction direction, BlockPos pos, int hardpoint,
                          CarrierExpansionPolicy.Type type,CarrierContainerTransferPolicy.Phase phase,int ticks) {
            this.direction=direction; this.containerPos=pos.immutable(); this.hardpoint=hardpoint;
            this.type=type; this.phase=phase; this.ticks=Math.max(0,ticks);
        }
    }
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
        return op == null || !op.contains(pos.getX(), Math.clamp(pos.getY(), op.minY(), op.maxY()), pos.getZ());
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
        builder.define(COMBAT_RADIUS, 0); builder.define(COMBAT_WEAPON, 0); builder.define(AIM_COUNT, 0);
        for (var aim : AIMS) builder.define(aim, new org.joml.Vector3f());
        builder.define(MINED, 0); builder.define(CAPTURED, 0); builder.define(CAPTURE_POS, BlockPos.ZERO);
        builder.define(BEAM_SEQUENCE, 0);
        builder.define(MINING_SURFACE_Y, 0);
        builder.define(PLASMA_RESPONSE, CarrierProtection.PlasmaResponse.VAPORIZE.ordinal());
        builder.define(CARGO_MODULES, 0);
        builder.define(CARGO_HARDPOINTS, 0); builder.define(MISSILE_HARDPOINTS, 0);
        builder.define(TRANSFER_ACTIVE, false); builder.define(TRANSFER_PHASE, CarrierContainerTransferPolicy.Phase.COMPLETE.ordinal());
        builder.define(TRANSFER_TICK, 0); builder.define(TRANSFER_DIRECTION, 0); builder.define(TRANSFER_TYPE, 0);
        builder.define(TRANSFER_HARDPOINT, -1); builder.define(TRANSFER_POS, BlockPos.ZERO);
        builder.define(MISSILE_LAUNCH_TICKS,0);
        builder.define(MISSILE_SALVO_SIZE,0); builder.define(MISSILE_FIRED,0); builder.define(MISSILE_SOURCE_MASK,0);
        builder.define(MISSILE_LOCK_COUNT,0);
        for(var lock:MISSILE_LOCKS)builder.define(lock,-1);
    }
    public CarrierPolicy.Mode workMode() { return CarrierPolicy.Mode.values()[entityData.get(MODE)]; }
    public CarrierPolicy.Stop workStatus() { return CarrierPolicy.Stop.values()[entityData.get(STATUS)]; }
    public int workCursor() { return entityData.get(CURSOR); }
    public int workTotal() { return entityData.get(TOTAL); }
    public BlockPos beamTarget() { return entityData.get(TARGET); }
    public BlockPos operationMin() { return entityData.get(AREA_MIN); }
    public BlockPos operationMax() { return entityData.get(AREA_MAX); }
    public int miningSurfaceY() { return entityData.get(MINING_SURFACE_Y); }
    public CarrierProtection.PlasmaResponse plasmaResponse() {
        return CarrierProtection.PlasmaResponse.values()[Math.clamp(entityData.get(PLASMA_RESPONSE), 0,
            CarrierProtection.PlasmaResponse.values().length - 1)];
    }
    public int cargoModules() { return Math.clamp(entityData.get(CARGO_MODULES), 0, CarrierPolicy.CARGO_MODULE_SLOTS); }
    public int cargoHardpoints() { return entityData.get(CARGO_HARDPOINTS); }
    public int missileHardpoints() { return entityData.get(MISSILE_HARDPOINTS); }
    public int expansionModules(CarrierExpansionPolicy.Type type) { return expansionBay.count(type); }
    public java.util.List<CarrierExpansionBay.Attached> attachedExpansions() { return expansionBay.attached(); }
    public int carrierMissiles() { return expansionBay.missiles(); }
    public int carrierMissiles(int hardpoint){return expansionBay.missiles(hardpoint);}
    public boolean launchHeavyMissile(ServerPlayer owner,net.minecraft.world.entity.LivingEntity target,int shot){
        CarrierShip ship=ship();
        if(!(level() instanceof ServerLevel level)||ship==null||owner==null||!ship.owner.equals(owner.getUUID())
            ||containerTransfer!=null||target==null||!target.isAlive()
            ||heavyMissileQueue.size()>=CarrierMissileModulePolicy.MAX_SALVO)return false;
        java.util.Set<CarrierExpansionBay.MissileCell> reserved=new java.util.HashSet<>();
        for(SavedMissileLaunch queued:heavyMissileQueue)if(queued.validSource())reserved.add(queued.source());
        CarrierExpansionBay.MissileCell source=expansionBay.availableMissiles().stream()
            .filter(cell->!reserved.contains(cell)).findFirst().orElse(null);
        if(source==null)return false;
        boolean starting=heavyMissileQueue.isEmpty();
        heavyMissileQueue.add(new SavedMissileLaunch(target.getUUID(),shot,source.hardpoint(),source.cell()));
        if(starting){
            heavyMissileSequenceTick=0; heavyMissilesFired=0;
            Vec3 hatch=toWorld(new Vec3(0,1.2,0));
            level.playSound(null,hatch.x,hatch.y,hatch.z,jp.morrowgear.drone.MorrowgearDrone.CARRIER_MISSILE_HATCH_SOUND,
                net.minecraft.sounds.SoundSource.PLAYERS,1.0f,.96f);
        }
        syncHeavyMissileState(level);
        markShipDirty();
        return true;
    }
    public boolean transferActive() { return entityData.get(TRANSFER_ACTIVE); }
    public CarrierContainerTransferPolicy.Phase transferPhase() {
        return CarrierContainerTransferPolicy.Phase.values()[Math.clamp(entityData.get(TRANSFER_PHASE), 0,
            CarrierContainerTransferPolicy.Phase.values().length - 1)];
    }
    public int transferTick() { return entityData.get(TRANSFER_TICK); }
    public CarrierContainerTransferPolicy.Direction transferDirection() {
        return CarrierContainerTransferPolicy.Direction.values()[Math.clamp(entityData.get(TRANSFER_DIRECTION), 0, 1)];
    }
    public CarrierExpansionPolicy.Type transferType() {
        return CarrierExpansionPolicy.Type.values()[Math.clamp(entityData.get(TRANSFER_TYPE), 0,
            CarrierExpansionPolicy.Type.values().length - 1)];
    }
    public int transferHardpoint() { return entityData.get(TRANSFER_HARDPOINT); }
    public int missileLaunchTicks() { return entityData.get(MISSILE_LAUNCH_TICKS); }
    public int missileSalvoSize(){return entityData.get(MISSILE_SALVO_SIZE);}
    public int missilesFired(){return entityData.get(MISSILE_FIRED);}
    public int missileSourceMask(){return entityData.get(MISSILE_SOURCE_MASK);}
    public jp.morrowgear.drone.CarrierHeavyMissilePolicy.LaunchPhase missileLaunchPhase(){
        return jp.morrowgear.drone.CarrierHeavyMissilePolicy.launchPhase(missileLaunchTicks(),missileSalvoSize());
    }
    public java.util.List<Integer> missileLockedTargetIds(){
        int count=Math.clamp(entityData.get(MISSILE_LOCK_COUNT),0,MISSILE_LOCKS.size());
        var result=new java.util.ArrayList<Integer>(count);
        for(int i=0;i<count;i++){int id=entityData.get(MISSILE_LOCKS.get(i));if(id>=0)result.add(id);}
        return java.util.List.copyOf(result);
    }
    public boolean missileSequenceActive(){return !heavyMissileQueue.isEmpty();}
    public BlockPos transferContainerPos() {
        return containerTransfer==null?entityData.get(TRANSFER_POS):containerTransfer.containerPos;
    }
    public String transferDetail() { return containerTransferDetail; }
    public int transferPhaseDuration() { return containerTransfer == null ? 0 : transferDuration(containerTransfer.phase); }
    public boolean transferCancellable() {
        if(containerTransfer==null)return false;
        return containerTransfer.direction==CarrierContainerTransferPolicy.Direction.LOAD
            ? !expansionBay.occupied(containerTransfer.hardpoint)
            : !(level().getBlockEntity(containerTransfer.containerPos) instanceof CarrierExpansionContainer);
    }
    public String operationGeneration() { return entityData.get(GENERATION); }
    public boolean beamActive() { return entityData.get(BEAM); }
    public CarrierPolicy.WorkPhase workPhase() { return CarrierPolicy.WorkPhase.values()[entityData.get(PHASE)]; }
    public int phaseTick() { return entityData.get(PHASE_TICK); }
    public int phaseDuration() {
        if (workMode() != CarrierPolicy.Mode.MINING) return CarrierPolicy.phaseDuration(workPhase(), combatWeapon());
        return switch (workPhase()) {
            case SCAN -> CarrierPolicy.SCAN_TICKS;
            case CHARGE -> CarrierPolicy.MINING_CHARGE_TICKS;
            case FIRE -> CarrierMiningEffectPolicy.RELEASE_TICKS;
            default -> 0;
        };
    }
    public int workPhaseTick() { return phaseTick(); }
    public int workPhaseDuration() { return phaseDuration(); }
    public int sampleTick() { return entityData.get(SAMPLE_TICK); }
    public int combatRadius() { return entityData.get(COMBAT_RADIUS); }
    public CarrierPolicy.CombatWeapon combatWeapon() {
        return CarrierPolicy.CombatWeapon.values()[Math.clamp(entityData.get(COMBAT_WEAPON), 0,
            CarrierPolicy.CombatWeapon.values().length - 1)];
    }
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
        long items = drops.stream().mapToLong(net.minecraft.world.item.ItemStack::getCount).sum();
        recordCaptureBatch(pos, drops, 1, items, CarrierProtection.PlasmaResponse.VAPORIZE);
    }
    void recordCaptureBatch(BlockPos pos, java.util.List<net.minecraft.world.item.ItemStack> drops,
                            int blocks, long items) {
        recordCaptureBatch(pos, drops, blocks, items, CarrierProtection.PlasmaResponse.VAPORIZE);
    }
    void recordCaptureBatch(BlockPos pos, java.util.List<net.minecraft.world.item.ItemStack> drops,
                            int blocks, long items, CarrierProtection.PlasmaResponse response) {
        if (blocks <= 0) return;
        lastCaptureDrops = drops.stream().map(net.minecraft.world.item.ItemStack::copy).toList();
        entityData.set(MINED, (int) Math.min(Integer.MAX_VALUE, (long) minedBlocks() + blocks));
        entityData.set(CAPTURED, (int) Math.min(Integer.MAX_VALUE, (long) capturedItems() + Math.max(0, items)));
        entityData.set(CAPTURE_POS, pos.immutable());
        entityData.set(PLASMA_RESPONSE, response.ordinal());
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
            && (ship.mode == CarrierPolicy.Mode.MINING || CarrierPolicy.phaseTick(ship.combatRemaining, ship.combatWeapon)
                % CarrierPolicy.COMBAT_PULSE_TICKS == 0))
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
        entityData.set(COMBAT_WEAPON, ship.combatWeapon.ordinal());
        entityData.set(CARGO_MODULES, expansionBay.count(CarrierExpansionPolicy.Type.CARGO));
        int cargoMask=0, missileMask=0;
        for(var module:expansionBay.attached()) {
            if(module.type()==CarrierExpansionPolicy.Type.CARGO) cargoMask|=1<<module.hardpoint();
            else if(module.type()==CarrierExpansionPolicy.Type.MISSILE_BATTERY) missileMask|=1<<module.hardpoint();
        }
        entityData.set(CARGO_HARDPOINTS,cargoMask); entityData.set(MISSILE_HARDPOINTS,missileMask);
        entityData.set(TRANSFER_ACTIVE,containerTransfer!=null);
        if(containerTransfer!=null){
            entityData.set(TRANSFER_PHASE,containerTransfer.phase.ordinal()); entityData.set(TRANSFER_TICK,containerTransfer.ticks);
            entityData.set(TRANSFER_DIRECTION,containerTransfer.direction.ordinal()); entityData.set(TRANSFER_TYPE,containerTransfer.type.ordinal());
            entityData.set(TRANSFER_HARDPOINT,containerTransfer.hardpoint); entityData.set(TRANSFER_POS,containerTransfer.containerPos);
        } else {
            entityData.set(TRANSFER_PHASE,CarrierContainerTransferPolicy.Phase.COMPLETE.ordinal()); entityData.set(TRANSFER_TICK,0);
            entityData.set(TRANSFER_HARDPOINT,-1);
        }
        var op = ship.preview != null ? ship.preview : ship.activeCombat != null ? ship.activeCombat
            : ship.progress == null ? null : ship.progress.operation();
        int cursor = ship.progress != null && ship.progress.operation() == op ? ship.progress.cursor() : 0;
        entityData.set(CURSOR, cursor); entityData.set(TOTAL, op == null ? 0 : op.volume());
        entityData.set(GENERATION, op == null ? "" : op.generation().toString());
        if (op != null) {
            entityData.set(AREA_MIN, new BlockPos(op.minX(), op.minY(), op.minZ()));
            entityData.set(AREA_MAX, new BlockPos(op.maxX(), op.maxY(), op.maxZ()));
            entityData.set(MINING_SURFACE_Y, op.surfaceY());
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
        migrateLegacyCargoModule(ship);
        ship.attachedCargoModules = expansionBay.count(CarrierExpansionPolicy.Type.CARGO);
        if(!expansionLedgerSynchronized)syncExpansionLedger(level,ship);
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
        if (ship.mode == CarrierPolicy.Mode.IDLE && ship.destination == null
            && ship.arrivalOperation != CarrierPolicy.Mode.IDLE && ship.preview != null
            && level.getServer().getTickCount() > ship.previewUntil) {
            ship.arrivalOperation = CarrierPolicy.Mode.IDLE;
            ship.preview = null;
            ship.previewUntil = 0;
            ship.stop = CarrierPolicy.Stop.EXPIRED;
            ship.dirty.run();
        }
        if (ship.destination != null && !ship.navigationPaused) {
            Vec3 destination = CarrierNavigation.destination(ship.destination);
            Vec3 delta = destination.subtract(position());
            if (CarrierNavigation.arrived(position(), destination)) {
                CarrierPolicy.Mode arrivalOperation = ship.arrivalOperation;
                ship.destination = null;
                ship.stop = CarrierPolicy.Stop.COMPLETE;
                if (arrivalOperation != CarrierPolicy.Mode.IDLE) {
                    CarrierCommands.preparePreview(ship, level, this, arrivalOperation,
                        blockPosition().getX(), blockPosition().getZ(), level.getServer().getTickCount());
                }
                ship.dirty.run();
            }
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
                } else if (!flightSweepClear(level, position(), getYRot(), position().add(step), motion.yaw())) {
                    if (!replanContainerTransferRoute(level, ship)) ship.pauseNavigation(CarrierPolicy.Stop.OBSTRUCTED);
                }
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
        tickContainerTransfer(level, ship);
        tickHeavyMissileLaunch(level,owner);
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

    public String requestContainerLoad(ServerPlayer player, BlockPos pos) {
        CarrierShip ship=ship();
        if(ship==null||!controls(player)||ship.mode!=CarrierPolicy.Mode.IDLE||containerTransfer!=null||missileSequenceActive()) return transferRejected("CARRIER_BUSY");
        BlockPos anchor=CarrierContainerFootprint.resolveAnchor(level(),pos);
        if(anchor==null||!(level().getBlockEntity(anchor) instanceof CarrierExpansionContainer container)) return transferRejected("CONTAINER_NOT_FOUND");
        if(!container.ownerId().equals(player.getUUID())) return transferRejected("OWNER_MISMATCH");
        if(container.transferReserved()) return transferRejected("CONTAINER_RESERVED");
        int hardpoint=expansionBay.firstFree(container.moduleType()); if(hardpoint<0)return transferRejected("NO_COMPATIBLE_HARDPOINT");
        double approachY=initialTransferAltitude(anchor);
        if(!Double.isFinite(approachY))return transferRejected("NO_CLEAR_TRANSFER_ALTITUDE");
        container.setTransferReserved(true);
        containerTransfer=new ContainerTransfer(CarrierContainerTransferPolicy.Direction.LOAD,anchor,hardpoint,container.moduleType());
        containerTransfer.approachY=approachY;
        containerTransfer.alignmentYaw=CarrierCargoVisualPolicy.nearestAlignmentYaw(
            position(),anchor,hardpoint,getYRot());
        containerTransferDetail="LOAD_ACCEPTED";
        setTransferDestination(ship,containerTransfer);
        markShipDirty(); return "ACCEPTED";
    }

    public String requestContainerUnload(ServerPlayer player, int hardpoint, BlockPos pos) {
        CarrierShip ship=ship();
        if(ship==null||!controls(player)||ship.mode!=CarrierPolicy.Mode.IDLE||containerTransfer!=null||missileSequenceActive()) return transferRejected("CARRIER_BUSY");
        if(!expansionBay.occupied(hardpoint)) return transferRejected("HARDPOINT_EMPTY");
        BlockPos landing=resolvedContainerLanding((ServerLevel)level(),pos);
        if(landing==null||!landingFootprintClear((ServerLevel)level(),landing)) return transferRejected("LANDING_FOOTPRINT_OCCUPIED");
        var identity=CarrierExpansionItemData.read(expansionBay.get(hardpoint));
        if(identity==null) return transferRejected("MODULE_IDENTITY_INVALID");
        if(identity!=null&&identity.type()==CarrierExpansionPolicy.Type.CARGO
            && hardpoint!=expansionBay.highest(CarrierExpansionPolicy.Type.CARGO)) return transferRejected("UNLOAD_CARGO_IN_REVERSE_ORDER");
        double approachY=initialTransferAltitude(landing);
        if(!Double.isFinite(approachY))return transferRejected("NO_CLEAR_TRANSFER_ALTITUDE");
        containerTransfer=new ContainerTransfer(CarrierContainerTransferPolicy.Direction.UNLOAD,landing,hardpoint,identity.type());
        containerTransfer.approachY=approachY;
        containerTransfer.alignmentYaw=CarrierCargoVisualPolicy.nearestAlignmentYaw(
            position(),landing,hardpoint,getYRot());
        containerTransferDetail="UNLOAD_ACCEPTED";
        setTransferDestination(ship,containerTransfer);
        markShipDirty(); return "ACCEPTED";
    }
    public String requestContainerUnload(ServerPlayer player, java.util.UUID moduleId, BlockPos pos) {
        int slot=expansionBay.slot(moduleId);
        return slot<0?transferRejected("MODULE_NOT_ATTACHED"):requestContainerUnload(player,slot,pos);
    }

    public String cancelContainerTransfer(ServerPlayer player) {
        CarrierShip ship=ship();
        if(ship==null||!controls(player))return transferRejected("CONTROL_DENIED");
        if(containerTransfer==null)return transferRejected("NO_ACTIVE_TRANSFER");
        boolean committed=containerTransfer.direction==CarrierContainerTransferPolicy.Direction.LOAD
            ? expansionBay.occupied(containerTransfer.hardpoint)
            : level().getBlockEntity(containerTransfer.containerPos) instanceof CarrierExpansionContainer;
        if(committed)return transferRejected("TRANSFER_COMMITTED");
        releaseContainerReservation((ServerLevel)level(),containerTransfer);
        containerTransfer=null;
        ship.destination=null; ship.navigationPaused=false; ship.arrivalOperation=CarrierPolicy.Mode.IDLE;
        ship.stop=CarrierPolicy.Stop.COMPLETE; containerTransferDetail="TRANSFER_CANCELLED";
        markShipDirty(); return "ACCEPTED";
    }

    private String transferRejected(String detail){containerTransferDetail=detail;markShipDirty();return detail;}

    private void setTransferDestination(CarrierShip ship, ContainerTransfer transfer) {
        transfer.route=java.util.List.of(); transfer.routeIndex=0;
        setTransferNavigationDestination(ship,transferCarrierPosition(transfer));
    }

    private void setTransferNavigationDestination(CarrierShip ship, Vec3 target) {
        ship.destination=new CarrierAnchor(level().dimension().identifier().toString(),
            (int)Math.round(target.x-.5), (int)Math.round(target.y), (int)Math.round(target.z-.5));
        ship.arrivalOperation=CarrierPolicy.Mode.IDLE; ship.navigationPaused=false; ship.stop=CarrierPolicy.Stop.RUNNING;
        CarrierChunkLeases.requestResume(getUUID());
    }

    private double initialTransferAltitude(BlockPos pos) {
        return CarrierContainerTransferPolicy.initialApproachAltitude(getY(),pos.getY(),
            level().getMinY(),level().getMaxY());
    }

    private Vec3 transferCarrierPosition(ContainerTransfer transfer) {
        if(!Double.isFinite(transfer.approachY))transfer.approachY=initialTransferAltitude(transfer.containerPos);
        if(!Float.isFinite(transfer.alignmentYaw))
            transfer.alignmentYaw=CarrierCargoVisualPolicy.nearestAlignmentYaw(
                position(),transfer.containerPos,transfer.hardpoint,getYRot());
        return CarrierCargoVisualPolicy.alignedCarrierPosition(transfer.containerPos,transfer.approachY,
            transfer.alignmentYaw,transfer.hardpoint);
    }

    private boolean replanContainerTransferRoute(ServerLevel level,CarrierShip ship){
        if(containerTransfer==null||switch(containerTransfer.phase){
            case APPROACH,ALIGN,HOLD -> false; default -> true;
        })return false;
        ContainerTransfer transfer=containerTransfer;
        double ceiling=level.getMaxY()-CarrierPolicy.HEIGHT-2.0;
        var plan=CarrierTransferRoutePlanner.plan(position(),transferCarrierPosition(transfer),ceiling,getYRot(),
            (from,to)->transferCorridorClear(level,from,to));
        if(plan.isEmpty()){
            containerTransferDetail="NO_CLEAR_TRANSFER_ROUTE";
            return false;
        }
        var route=plan.orElseThrow();
        transfer.approachY=route.approachY();
        transfer.route=route.waypoints(); transfer.routeIndex=0;
        transfer.phase=CarrierContainerTransferPolicy.Phase.APPROACH;
        transfer.ticks=0;transfer.stagnantTicks=0;transfer.lastDistance=Double.POSITIVE_INFINITY;
        ship.navigationWaitTicks=0;
        setTransferNavigationDestination(ship,transfer.route.getFirst());
        containerTransferDetail="ROUTE_"+route.strategy()+"_"+route.waypoints().size();
        markShipDirty();return true;
    }

    private boolean transferCorridorClear(ServerLevel level,Vec3 from,Vec3 to){
        Vec3 delta=to.subtract(from);
        float yaw=CarrierNavigation.heading(delta,getYRot());
        if(from.distanceToSqr(position())<1.0e-8){
            float error=CarrierNavigation.angleDifference(yaw,getYRot());
            float next=getYRot()+Math.clamp(error,-CarrierNavigation.YAW_SLEW,CarrierNavigation.YAW_SLEW);
            if(!flightSweepClear(level,from,getYRot(),from,next))return false;
        }
        return flightSweepClear(level,from,yaw,to,yaw);
    }

    private boolean advanceTransferRoute(CarrierShip ship,ContainerTransfer transfer){
        if(transfer.route.isEmpty())return false;
        transfer.routeIndex++;
        if(transfer.routeIndex>=transfer.route.size()){
            transfer.route=java.util.List.of();transfer.routeIndex=0;
            return false;
        }
        setTransferNavigationDestination(ship,transfer.route.get(transfer.routeIndex));
        transfer.lastDistance=Double.POSITIVE_INFINITY;transfer.stagnantTicks=0;
        return true;
    }

    /** Horizontal crane error used by runtime verification and the controller telemetry. */
    public double transferAlignmentError() {
        if(containerTransfer==null)return 0;
        Vec3 hardpoint=CarrierCargoVisualPolicy.hardpointWorld(position(),getYRot(),containerTransfer.hardpoint);
        Vec3 center=Vec3.atBottomCenterOf(containerTransfer.containerPos);
        return Math.hypot(hardpoint.x-center.x,hardpoint.z-center.z);
    }

    private void tickContainerTransfer(ServerLevel level, CarrierShip ship) {
        if(containerTransfer==null)return;
        ContainerTransfer transfer=containerTransfer;
        if(transfer.direction==CarrierContainerTransferPolicy.Direction.LOAD
            &&(switch(transfer.phase){
                case APPROACH,ALIGN,HATCH_OPEN,WINCH_DEPLOY,CONNECT,TENSION,HOLD -> true;
                default -> false;
            })&&level.getBlockEntity(transfer.containerPos) instanceof CarrierExpansionContainer container)
            container.setTransferReserved(true);
        if(transfer.phase==CarrierContainerTransferPolicy.Phase.HOLD){
            if(++transfer.ticks<CarrierContainerTransferPolicy.HOLD_RETRY_TICKS)return;
            transfer.ticks=0;
            if(++transfer.recoveryAttempts>CarrierContainerTransferPolicy.MAX_RECOVERY_ATTEMPTS){
                containerTransferDetail="TRANSFER_RECOVERY_EXHAUSTED";
                transfer.phase=CarrierContainerTransferPolicy.Phase.ABORTED;
            }
            if(transfer.phase!=CarrierContainerTransferPolicy.Phase.ABORTED)
                recoverContainerTransfer(level,ship,transfer);
            if(containerTransfer==null)return;
            transfer=containerTransfer;
            if(transfer.phase==CarrierContainerTransferPolicy.Phase.HOLD)return;
        }
        if(transfer.phase==CarrierContainerTransferPolicy.Phase.ABORTED){
            releaseContainerReservation(level,transfer); containerTransfer=null;
            ship.stop=CarrierPolicy.Stop.EMERGENCY; markShipDirty(); return;
        }
        if(transfer.phase==CarrierContainerTransferPolicy.Phase.APPROACH) {
            Vec3 target=transferCarrierPosition(transfer);
            Vec3 progressTarget=ship.destination==null?target:CarrierNavigation.destination(ship.destination);
            double distance=position().distanceTo(progressTarget);
            if(distance+0.25<transfer.lastDistance){transfer.lastDistance=distance;transfer.stagnantTicks=0;}
            else transfer.stagnantTicks++;
            if(transfer.stagnantTicks>=CarrierContainerTransferPolicy.APPROACH_STALL_TICKS){
                containerTransferDetail="CARRIER_APPROACH_BLOCKED";
                transfer.phase=CarrierContainerTransferPolicy.Phase.HOLD;transfer.ticks=0;return;
            }
            if(ship.destination!=null)return;
            if(advanceTransferRoute(ship,transfer))return;
            if(position().distanceToSqr(target)>CarrierContainerTransferPolicy.CARRIER_ALIGNMENT_TOLERANCE
                *CarrierContainerTransferPolicy.CARRIER_ALIGNMENT_TOLERANCE){
                if(position().distanceToSqr(target)>CarrierContainerTransferPolicy.NAVIGATION_SNAP_TOLERANCE
                    *CarrierContainerTransferPolicy.NAVIGATION_SNAP_TOLERANCE){
                    setTransferDestination(ship,transfer);return;
                }
            }
            transfer.phase=CarrierContainerTransferPolicy.Phase.ALIGN; transfer.ticks=0; transfer.stagnantTicks=0;
            transfer.lastDistance=Double.POSITIVE_INFINITY; markShipDirty(); return;
        }
        if(transfer.phase==CarrierContainerTransferPolicy.Phase.ALIGN) {
            Vec3 target=transferCarrierPosition(transfer);
            float yawError=CarrierNavigation.angleDifference(transfer.alignmentYaw,getYRot());
            if(Math.abs(yawError)>.05f){
                float nextYaw=getYRot()+Math.clamp(yawError,-CarrierNavigation.YAW_SLEW,CarrierNavigation.YAW_SLEW);
                if(!flightSweepClear(level,position(),getYRot(),position(),nextYaw)){
                    containerTransferDetail="CARRIER_ALIGNMENT_TURN_BLOCKED";
                    if(!replanContainerTransferRoute(level,ship)){
                        transfer.phase=CarrierContainerTransferPolicy.Phase.HOLD;transfer.ticks=0;
                    }
                    return;
                }
                setYRot(nextYaw);setDeltaMovement(Vec3.ZERO);transfer.stagnantTicks=0;
                ship.stop=CarrierPolicy.Stop.RUNNING;markShipDirty();return;
            }
            Vec3 correction=target.subtract(position());
            if(correction.lengthSqr()<=CarrierContainerTransferPolicy.CONTAINER_ALIGNMENT_TOLERANCE
                * CarrierContainerTransferPolicy.CONTAINER_ALIGNMENT_TOLERANCE){
                setPos(target); setYRot(transfer.alignmentYaw); setDeltaMovement(Vec3.ZERO);
                transfer.phase=CarrierContainerTransferPolicy.Phase.HATCH_OPEN; transfer.ticks=0;
                ship.stop=CarrierPolicy.Stop.RUNNING; markShipDirty(); return;
            }
            if(++transfer.stagnantTicks>=CarrierContainerTransferPolicy.APPROACH_STALL_TICKS){
                containerTransferDetail="CARRIER_ALIGNMENT_BLOCKED";
                transfer.phase=CarrierContainerTransferPolicy.Phase.HOLD;transfer.ticks=0;return;
            }
            Vec3 step=correction.length()<=.18?correction:correction.normalize().scale(.18);
            if(!flightSweepClear(level,position(),getYRot(),position().add(step),getYRot())){
                containerTransferDetail="CARRIER_ALIGNMENT_BLOCKED";
                if(!replanContainerTransferRoute(level,ship)){
                    transfer.phase=CarrierContainerTransferPolicy.Phase.HOLD;transfer.ticks=0;
                }
                return;
            }
            setPos(position().add(step)); setDeltaMovement(step); ship.stop=CarrierPolicy.Stop.RUNNING;
            markShipDirty(); return;
        }
        if(++transfer.ticks<transferDuration(transfer.phase))return;
        transfer.ticks=0;
        transfer.phase=switch(transfer.phase) {
            case ALIGN -> CarrierContainerTransferPolicy.Phase.HATCH_OPEN;
            case HATCH_OPEN -> transfer.direction==CarrierContainerTransferPolicy.Direction.LOAD
                ? CarrierContainerTransferPolicy.Phase.WINCH_DEPLOY
                : CarrierContainerTransferPolicy.Phase.TENSION;
            case WINCH_DEPLOY -> CarrierContainerTransferPolicy.Phase.CONNECT;
            case CONNECT -> CarrierContainerTransferPolicy.Phase.TENSION;
            case TENSION -> transfer.direction==CarrierContainerTransferPolicy.Direction.LOAD
                ? commitLoad(level,ship,transfer) ? CarrierContainerTransferPolicy.Phase.HOIST
                    : CarrierContainerTransferPolicy.Phase.HOLD
                : CarrierContainerTransferPolicy.Phase.PURGE;
            case HOIST -> CarrierContainerTransferPolicy.Phase.ATTACH;
            case ATTACH, GROUND_CONTACT, RELEASE -> CarrierContainerTransferPolicy.Phase.WINCH_RETRACT;
            case PURGE -> CarrierContainerTransferPolicy.Phase.DEPLOY;
            case DEPLOY -> commitUnload(level,ship,transfer) ? CarrierContainerTransferPolicy.Phase.GROUND_CONTACT
                : CarrierContainerTransferPolicy.Phase.HOLD;
            case WINCH_RETRACT -> CarrierContainerTransferPolicy.Phase.HATCH_CLOSE;
            case HATCH_CLOSE -> CarrierContainerTransferPolicy.Phase.COMPLETE;
            default -> transfer.phase;
        };
        if(transfer.phase==CarrierContainerTransferPolicy.Phase.COMPLETE){
            containerTransferDetail=transfer.direction==CarrierContainerTransferPolicy.Direction.LOAD
                ?"LOAD_COMPLETE":"UNLOAD_COMPLETE";
            containerTransfer=null;ship.stop=CarrierPolicy.Stop.COMPLETE;
        }
        markShipDirty();
    }

    private void recoverContainerTransfer(ServerLevel level,CarrierShip ship,ContainerTransfer transfer){
        boolean attached=expansionBay.occupied(transfer.hardpoint);
        boolean placed=level.getBlockEntity(transfer.containerPos) instanceof CarrierExpansionContainer;
        if(transfer.direction==CarrierContainerTransferPolicy.Direction.LOAD){
            if(attached){transfer.phase=CarrierContainerTransferPolicy.Phase.HOIST;transfer.ticks=0;transfer.stagnantTicks=0;return;}
            if(placed){
                ((CarrierExpansionContainer)level.getBlockEntity(transfer.containerPos)).setTransferReserved(true);
                transfer.phase=CarrierContainerTransferPolicy.Phase.APPROACH;transfer.ticks=0;
                transfer.stagnantTicks=0;transfer.lastDistance=Double.POSITIVE_INFINITY;
                if(!replanContainerTransferRoute(level,ship))setTransferDestination(ship,transfer);return;
            }
        }else{
            if(placed){transfer.phase=CarrierContainerTransferPolicy.Phase.GROUND_CONTACT;transfer.ticks=0;transfer.stagnantTicks=0;return;}
            if(attached&&landingFootprintClear(level,transfer.containerPos)){
                transfer.phase=CarrierContainerTransferPolicy.Phase.PURGE;transfer.ticks=0;transfer.stagnantTicks=0;return;
            }
        }
        transfer.phase=CarrierContainerTransferPolicy.Phase.ABORTED;
    }

    private static void releaseContainerReservation(ServerLevel level,ContainerTransfer transfer){
        if(transfer.direction==CarrierContainerTransferPolicy.Direction.LOAD
            &&level.getBlockEntity(transfer.containerPos) instanceof CarrierExpansionContainer container)
            container.setTransferReserved(false);
    }

    private void tickHeavyMissileLaunch(ServerLevel level,ServerPlayer owner){
        if(heavyMissileQueue.isEmpty()){
            heavyMissileSequenceTick=-1; heavyMissilesFired=0; syncHeavyMissileState(level); return;
        }
        if(owner==null)return;
        heavyMissileSequenceTick++;
        if(heavyMissilesFired<heavyMissileQueue.size()
            &&jp.morrowgear.drone.CarrierHeavyMissilePolicy.launchDue(heavyMissileSequenceTick,heavyMissilesFired)){
            SavedMissileLaunch launch=heavyMissileQueue.get(heavyMissilesFired);
            Entity entity=level.getEntity(launch.target());
            if(!(entity instanceof net.minecraft.world.entity.LivingEntity target)||!target.isAlive()){
                heavyMissilesFired++;
            }else if(!launch.validSource()){
                jp.morrowgear.drone.MorrowgearDrone.LOGGER.error(
                    "Carrier {} has a heavy missile queue entry without a source cell",getUUID());
                heavyMissilesFired++;
            }else if(jp.morrowgear.drone.MorrowgearMissileEntity.launchCarrier(level,this,owner,target,
                launch.hardpoint(),launch.cell())){
                if(!expansionBay.consumeMissile(launch.source())){
                    jp.morrowgear.drone.MorrowgearDrone.LOGGER.error("Carrier {} launched an unreserved heavy missile",getUUID());
                }
                heavyMissilesFired++;
            }
        }
        int duration=jp.morrowgear.drone.CarrierHeavyMissilePolicy.sequenceDuration(heavyMissileQueue.size());
        if(heavyMissilesFired>=heavyMissileQueue.size()&&heavyMissileSequenceTick>=duration){
            heavyMissileQueue.clear(); heavyMissileSequenceTick=-1; heavyMissilesFired=0;
        }
        syncHeavyMissileState(level);
    }

    private void syncHeavyMissileState(ServerLevel level){
        entityData.set(MISSILE_LAUNCH_TICKS,Math.max(0,heavyMissileSequenceTick));
        entityData.set(MISSILE_SALVO_SIZE,heavyMissileQueue.size());
        entityData.set(MISSILE_FIRED,heavyMissilesFired);
        int sourceMask=0;
        for(int i=0;i<heavyMissileQueue.size();i++){
            SavedMissileLaunch launch=heavyMissileQueue.get(i);
            if(launch.validSource())sourceMask|=1<<launch.hardpoint();
        }
        entityData.set(MISSILE_SOURCE_MASK,sourceMask);
        var ids=new java.util.ArrayList<Integer>();
        for(int i=heavyMissilesFired;i<heavyMissileQueue.size()&&ids.size()<MISSILE_LOCKS.size();i++){
            java.util.UUID target=heavyMissileQueue.get(i).target();
            Entity entity=level.getEntity(target);
            if(entity instanceof net.minecraft.world.entity.LivingEntity living&&living.isAlive())ids.add(living.getId());
        }
        entityData.set(MISSILE_LOCK_COUNT,ids.size());
        for(int i=0;i<MISSILE_LOCKS.size();i++)entityData.set(MISSILE_LOCKS.get(i),i<ids.size()?ids.get(i):-1);
        CarrierShip current=ship();
        if(current!=null){
            var locks=heavyMissileQueue.stream().skip(heavyMissilesFired).map(SavedMissileLaunch::target)
                .distinct().limit(CarrierMissileModulePolicy.MAX_PARALLEL_LOCKS).toList();
            if(!current.missileLockedTargets.equals(locks)){
                current.missileLockedTargets=locks;
                current.dirty.run();
            }
        }
    }

    private static int transferDuration(CarrierContainerTransferPolicy.Phase phase) {
        return switch(phase){
            case ALIGN -> 10; case HATCH_OPEN,HATCH_CLOSE -> CarrierContainerTransferPolicy.HATCH_TICKS;
            case WINCH_DEPLOY,WINCH_RETRACT -> CarrierContainerTransferPolicy.WINCH_DEPLOY_TICKS;
            case CONNECT,TENSION,GROUND_CONTACT,RELEASE -> CarrierContainerTransferPolicy.CONNECT_TICKS;
            case PURGE -> CarrierContainerTransferPolicy.PURGE_TICKS;
            case HOIST,DEPLOY -> CarrierContainerTransferPolicy.HOIST_TICKS; default -> 1;
        };
    }

    private boolean commitLoad(ServerLevel level, CarrierShip ship, ContainerTransfer transfer) {
        if(!(level.getBlockEntity(transfer.containerPos) instanceof CarrierExpansionContainer container)
            || !container.transferReserved() || !container.ownerId().equals(ship.owner)) return false;
        ItemStack module=container.recoveryItem(); var identity=CarrierExpansionItemData.read(module);
        if(identity==null)return false;
        var ledgerMove=CarrierExpansionTransactions.install(level,identity,transfer.containerPos,getUUID(),transfer.hardpoint);
        if(!ledgerMove.success()){
            jp.morrowgear.drone.MorrowgearDrone.LOGGER.warn("Carrier {} rejected container install: {}",getUUID(),ledgerMove.detail());
            return false;
        }
        int moduleIndex=-1;
        if(identity.type()==CarrierExpansionPolicy.Type.CARGO){
            moduleIndex=expansionBay.count(CarrierExpansionPolicy.Type.CARGO);
            ship.attachedCargoModules=moduleIndex+1;
            NonNullList<ItemStack> contents=NonNullList.withSize(CarrierPolicy.STORAGE_SLOTS_PER_MODULE,ItemStack.EMPTY);
            module.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).copyInto(contents);
            ship.cargo.importModule(moduleIndex,contents); module.remove(DataComponents.CONTAINER);
        }
        if(!expansionBay.install(transfer.hardpoint,module)){
            if(moduleIndex>=0){ship.cargo.clearModule(moduleIndex);ship.attachedCargoModules=moduleIndex;}
            CarrierExpansionTransactions.rollbackToPlaced(level,identity,transfer.containerPos);
            return false;
        }
        container.setTransferReserved(false); CarrierContainerFootprint.removeContainer(level,transfer.containerPos);
        ship.dirty.run(); return true;
    }

    private boolean commitUnload(ServerLevel level, CarrierShip ship, ContainerTransfer transfer) {
        if(!landingFootprintClear(level,transfer.containerPos))return transferFailure("LANDING_FOOTPRINT_OCCUPIED");
        ItemStack module=expansionBay.get(transfer.hardpoint).copy(); var identity=CarrierExpansionItemData.read(module);
        if(identity==null)return transferFailure("MODULE_IDENTITY_INVALID");
        if(identity.type()==CarrierExpansionPolicy.Type.CARGO){
            int moduleIndex=ship.attachedCargoModules-1;
            if(moduleIndex<0||transfer.hardpoint!=expansionBay.highest(CarrierExpansionPolicy.Type.CARGO))
                return transferFailure("CARGO_MODULE_ORDER_CHANGED");
            module.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(ship.cargo.moduleContents(moduleIndex)));
        }
        var ledgerMove=CarrierExpansionTransactions.unload(level,identity,getUUID(),transfer.hardpoint,transfer.containerPos);
        if(!ledgerMove.success())return transferFailure("LEDGER_"+ledgerMove.status()+"_"+ledgerMove.detail());
        var block=identity.type()==CarrierExpansionPolicy.Type.CARGO?CarrierModule.CARGO_CONTAINER_BLOCK:CarrierModule.MISSILE_CONTAINER_BLOCK;
        if(!level.setBlock(transfer.containerPos,block.defaultBlockState(),3)){
            CarrierExpansionTransactions.install(level,identity,transfer.containerPos,getUUID(),transfer.hardpoint);
            return transferFailure("BLOCK_PLACE_REJECTED");
        }
        if(!(level.getBlockEntity(transfer.containerPos) instanceof CarrierExpansionContainer placed)){
            level.removeBlock(transfer.containerPos,false);
            CarrierExpansionTransactions.install(level,identity,transfer.containerPos,getUUID(),transfer.hardpoint);
            return transferFailure("BLOCK_ENTITY_MISSING");
        }
        if(placed instanceof CarrierCargoContainerBlockEntity cargo)cargo.initialize(ship.owner,module);
        else if(placed instanceof CarrierMissileContainerBlockEntity missiles)missiles.initialize(ship.owner,module);
        CarrierContainerFootprint.ensure(level,transfer.containerPos);
        expansionBay.remove(transfer.hardpoint);
        if(identity.type()==CarrierExpansionPolicy.Type.CARGO){int moduleIndex=ship.attachedCargoModules-1;ship.cargo.clearModule(moduleIndex);ship.attachedCargoModules--;}
        containerTransferDetail="READY"; ship.dirty.run(); return true;
    }

    private boolean transferFailure(String detail){
        containerTransferDetail=detail;
        return false;
    }

    private static boolean landingFootprintClear(ServerLevel level, BlockPos center) {
        for(int x=-2;x<=2;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=2;y++){
            BlockPos pos=center.offset(x,y,z);
            if(!level.getBlockState(pos).canBeReplaced())return false;
        }
        return true;
    }

    /**
     * Resolve a requested X/Z onto the current terrain surface. The five-by-three container
     * rests on the highest supporting cell in its footprint, so a missile crater cannot leave
     * it suspended at the old command Y or intersecting a surviving rim block.
     */
    private static BlockPos resolvedContainerLanding(ServerLevel level, BlockPos requested) {
        int[] surfaces=new int[15]; int cursor=0;
        for(int x=-2;x<=2;x++)for(int z=-1;z<=1;z++){
            surfaces[cursor++]=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                requested.getX()+x,requested.getZ()+z);
        }
        int y=CarrierContainerTransferPolicy.supportPlane(level.getMinY(),level.getMaxY(),surfaces);
        if(y==CarrierContainerTransferPolicy.NO_SUPPORT)return null;
        return new BlockPos(requested.getX(),y,requested.getZ());
    }

    private void markShipDirty(){CarrierShip current=ship();if(current!=null)current.dirty.run();}
    private void syncExpansionLedger(ServerLevel level,CarrierShip ship){
        expansionLedgerSynchronized=true;
        CarrierExpansionLedger ledger=CarrierExpansionLedger.get(level.getServer());
        for(var attached:expansionBay.attached()){
            ItemStack stack=expansionBay.get(attached.hardpoint());
            var identity=CarrierExpansionItemData.read(stack);
            if(identity==null)continue;
            var current=ledger.get(identity.id());
            CarrierExpansionLedger.Result result=current==null
                ?ledger.claimInstalled(identity,getUUID(),attached.hardpoint(),
                    "carrier/"+getUUID()+"/"+attached.hardpoint(),level.getGameTime())
                :ledger.claimPhysical(current.install(getUUID(),attached.hardpoint()),
                    "carrier/"+getUUID()+"/"+attached.hardpoint(),level.getGameTime());
            if(!result.success())jp.morrowgear.drone.MorrowgearDrone.LOGGER.warn(
                "Carrier {} expansion {} quarantined during ledger sync: {}",getUUID(),identity.id(),result.detail());
        }
    }
    private void migrateLegacyCargoModule(CarrierShip ship){
        ItemStack legacy=ship.supplies.getItem(CarrierSupplies.CARGO_MODULE);
        if(legacy.isEmpty()||!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(legacy.getItem()).toString()
            .equals("morrowgear_drone:carrier_cargo_module"))return;
        while(!legacy.isEmpty()&&expansionBay.firstFree()>=0){
            ItemStack module=legacy.split(1);
            CarrierExpansionItemData.write(module,new CarrierExpansionItemData.Identity(java.util.UUID.randomUUID(),ship.owner,
                CarrierExpansionPolicy.Type.CARGO));
            expansionBay.install(expansionBay.firstFree(),module);
        }
        ship.supplies.setChanged(); ship.dirty.run();
    }
    public boolean setDestination(ServerPlayer player, BlockPos target) {
        return setDestination(player, target, CarrierPolicy.Mode.IDLE);
    }
    public boolean setDestination(ServerPlayer player, BlockPos target, CarrierPolicy.Mode arrivalOperation) {
        return setDestination(player, target, arrivalOperation, CarrierPolicy.CombatWeapon.LASER);
    }
    public boolean setDestination(ServerPlayer player, BlockPos target, CarrierPolicy.Mode arrivalOperation,
                                  CarrierPolicy.CombatWeapon combatWeapon) {
        CarrierShip ship = ship();
        if (!"READY".equals(destinationRefusalReason(player, target))) return false;
        ship.stop(CarrierPolicy.Stop.READY);
        ship.destination = new CarrierAnchor(level().dimension().identifier().toString(), target.getX(), target.getY(), target.getZ());
        ship.arrivalOperation = arrivalOperation == CarrierPolicy.Mode.MINING || arrivalOperation == CarrierPolicy.Mode.COMBAT
            ? arrivalOperation : CarrierPolicy.Mode.IDLE;
        ship.combatWeapon = ship.arrivalOperation == CarrierPolicy.Mode.COMBAT && combatWeapon != null
            ? combatWeapon : CarrierPolicy.CombatWeapon.LASER;
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
                if (Math.min(from.y, position.y) < CarrierTerrainQueryCache.height(level, x, z)
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
    @Override protected void addAdditionalSaveData(ValueOutput output) {
        output.putFloat("CarrierHull", hull);
        ValueOutput.TypedOutputList<SavedExpansion> modules=output.list("CarrierExpansionSlots",SavedExpansion.CODEC);
        var snapshot=expansionBay.snapshot();
        for(int slot=0;slot<snapshot.size();slot++)if(!snapshot.get(slot).isEmpty())
            modules.add(new SavedExpansion(slot,snapshot.get(slot)));
        ValueOutput.TypedOutputList<SavedMissileLaunch> launches=output.list("CarrierMissileQueue",SavedMissileLaunch.CODEC);
        heavyMissileQueue.forEach(launches::add);
        output.putInt("CarrierMissileSequenceTick",heavyMissileSequenceTick);
        output.putInt("CarrierMissilesFired",heavyMissilesFired);
        if(containerTransfer!=null){
            output.putBoolean("CarrierTransferActive",true);
            output.putString("CarrierTransferDirection",containerTransfer.direction.name());
            output.putString("CarrierTransferPhase",containerTransfer.phase.name());
            output.putLong("CarrierTransferPosition",containerTransfer.containerPos.asLong());
            output.putInt("CarrierTransferHardpoint",containerTransfer.hardpoint);
            output.putString("CarrierTransferType",containerTransfer.type.name());
            output.putInt("CarrierTransferTicks",containerTransfer.ticks);
            output.putInt("CarrierTransferRetries",containerTransfer.recoveryAttempts);
            output.putDouble("CarrierTransferApproachY",containerTransfer.approachY);
            output.putFloat("CarrierTransferAlignmentYaw",containerTransfer.alignmentYaw);
        }
        output.putString("CarrierTransferDetail",containerTransferDetail);
    }
    @Override protected void readAdditionalSaveData(ValueInput input) {
        hull = Math.clamp(input.getFloatOr("CarrierHull", 400), 1, 400);
        java.util.List<ItemStack> modules=new java.util.ArrayList<>(java.util.Collections.nCopies(
            CarrierExpansionPolicy.HARDPOINTS,ItemStack.EMPTY));
        for(SavedExpansion saved:input.listOrEmpty("CarrierExpansionSlots",SavedExpansion.CODEC))
            if(!saved.stack().isEmpty())modules.set(saved.hardpoint(),saved.stack());
        // Compatibility with the pre-hardpoint prototype, which stored a compact positional list.
        int legacySlot=0;
        for(ItemStack stack:input.listOrEmpty("CarrierExpansions",ItemStack.CODEC))
            if(!stack.isEmpty()&&legacySlot<modules.size())modules.set(legacySlot++,stack);
        expansionBay=new CarrierExpansionBay(modules,this::markShipDirty);
        containerTransfer=readContainerTransfer(input);
        if(containerTransfer!=null&&!Float.isFinite(containerTransfer.alignmentYaw))
            containerTransfer.alignmentYaw=CarrierCargoVisualPolicy.nearestAlignmentYaw(
                position(),containerTransfer.containerPos,containerTransfer.hardpoint,getYRot());
        containerTransferDetail=input.getStringOr("CarrierTransferDetail",containerTransfer==null?"READY":"RELOADED");
        heavyMissileQueue.clear();
        for(SavedMissileLaunch launch:input.listOrEmpty("CarrierMissileQueue",SavedMissileLaunch.CODEC))
            if(heavyMissileQueue.size()<CarrierMissileModulePolicy.MAX_SALVO)heavyMissileQueue.add(launch);
        heavyMissilesFired=Math.clamp(input.getIntOr("CarrierMissilesFired",0),0,heavyMissileQueue.size());
        restoreLegacyMissileSources(heavyMissilesFired);
        heavyMissileSequenceTick=heavyMissileQueue.isEmpty()?-1:Math.max(0,input.getIntOr("CarrierMissileSequenceTick",0));
        heavyMissilesFired=Math.min(heavyMissilesFired,heavyMissileQueue.size());
        setNoGravity(true);
        setXRot(0); setDeltaMovement(Vec3.ZERO);
        priorFlightYaw = getYRot(); maneuvering = false; bayClearWait = 0;
    }

    private void restoreLegacyMissileSources(int firstPending){
        if(heavyMissileQueue.isEmpty())return;
        var available=expansionBay.availableMissiles();
        var used=new java.util.HashSet<CarrierExpansionBay.MissileCell>();
        for(int i=Math.clamp(firstPending,0,heavyMissileQueue.size());i<heavyMissileQueue.size();i++){
            SavedMissileLaunch launch=heavyMissileQueue.get(i);
            CarrierExpansionBay.MissileCell source=launch.validSource()?launch.source():null;
            if(source==null||!available.contains(source)||!used.add(source)){
                source=available.stream().filter(cell->!used.contains(cell)).findFirst().orElse(null);
                if(source==null){heavyMissileQueue.subList(i,heavyMissileQueue.size()).clear();break;}
                used.add(source);
                heavyMissileQueue.set(i,new SavedMissileLaunch(launch.target(),launch.shot(),
                    source.hardpoint(),source.cell()));
            }
        }
    }

    private static ContainerTransfer readContainerTransfer(ValueInput input){
        if(!input.getBooleanOr("CarrierTransferActive",false))return null;
        try{
            var direction=CarrierContainerTransferPolicy.Direction.valueOf(
                input.getStringOr("CarrierTransferDirection",CarrierContainerTransferPolicy.Direction.LOAD.name()));
            var phase=CarrierContainerTransferPolicy.Phase.valueOf(
                input.getStringOr("CarrierTransferPhase",CarrierContainerTransferPolicy.Phase.HOLD.name()));
            var type=CarrierExpansionPolicy.Type.byName(input.getStringOr("CarrierTransferType","CARGO"));
            int hardpoint=CarrierExpansionPolicy.checkedHardpoint(input.getIntOr("CarrierTransferHardpoint",-1));
            ContainerTransfer transfer=new ContainerTransfer(direction,
                BlockPos.of(input.getLongOr("CarrierTransferPosition",BlockPos.ZERO.asLong())),
                hardpoint,type,phase,input.getIntOr("CarrierTransferTicks",0));
            transfer.recoveryAttempts=Math.clamp(input.getIntOr("CarrierTransferRetries",0),0,
                CarrierContainerTransferPolicy.MAX_RECOVERY_ATTEMPTS);
            transfer.approachY=input.getDoubleOr("CarrierTransferApproachY",Double.NaN);
            transfer.alignmentYaw=input.getFloatOr("CarrierTransferAlignmentYaw",Float.NaN);
            return transfer;
        }catch(IllegalArgumentException malformed){
            return null;
        }
    }
}
