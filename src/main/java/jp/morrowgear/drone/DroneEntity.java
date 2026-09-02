package jp.morrowgear.drone;

import java.util.List;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.Iterator;

import jp.morrowgear.drone.block.DockBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class DroneEntity extends PathfinderMob {
	public static final int MISSION_MUSTER = 0;
	public static final int MISSION_MOVING = 1;
	public static final int MISSION_ORBIT = 2;
	public static final int MISSION_CONVERGING = 3;
	public static final int MISSION_ORBIT_ENTRY = 4;
	private static final long ORBIT_ENTRY_DURATION = 50L;
	private static final double PATROL_ANGULAR_SPEED = 0.038;
	private static final double DOCK_ARRIVAL_DISTANCE = 1.2;
	private static final double DOCK_LANDING_Y = 0.29;
	private static final double STRATEGIC_NAVIGATION_DISTANCE = 10.0;
	private static final long CARGO_SERVICE_TICKS = 10L;
	private static final long CARGO_RETRY_TICKS = 40L;
	private static final long CARGO_ACCESS_TIMEOUT_TICKS = 100L;
	private static final double ENGINEER_SEARCH_RADIUS = 18.0;
	private static final double ENGINEER_SERVICE_DISTANCE = 3.25;
	private static final float ENGINEER_REPAIR_AMOUNT = 4.0f;
	private static final int ENGINEER_REPAIR_INTERVAL = 40;
	private static final ContainerAccessCoordinator<ContainerAccessKey> CARGO_ACCESS =
		new ContainerAccessCoordinator<>(40L, 120L);
	private static final EntityDataAccessor<String> OWNER = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> OWNER_NAME = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> UNIT_ID = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> MODE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> ROLE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> BATTERY = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> BATTERY_TIER = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> WEAPON_POWER = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Long> DOCK_POS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Boolean> DOCKED = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> DOCK_STAGE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> GROUP = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Long> WAYPOINT_POS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Boolean> HAS_WAYPOINT = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<String> MISSION_ID = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> MISSION_EXPECTED = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> MISSION_INDEX = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> MISSION_STAGE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Long> MISSION_ORIGIN = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Long> MISSION_ASSIGNED_TICK = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Long> ORBIT_ENTRY_TICK = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Float> ORBIT_PHASE_OFFSET = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<String> TRACK_TARGET = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> THREAT_SCORE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DEFENSE_SLOT = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> RECOVERY_LEVEL = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Long> RECOVERY_SINCE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<String> COHORT_ID = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> COHORT_LEADER = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> COHORT_RANK = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Long> RENDEZVOUS_POS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Boolean> FORMATION_CATCH_UP = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<String> DATA_LINK_STATUS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> OPERATION_SUMMARY = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> OPERATION_FRONTS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Long> CARGO_SOURCE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Long> CARGO_TARGET = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Integer> CARGO_STATE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> CARGO_COUNT = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> CARGO_QUEUE_POSITION = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> CARGO_PAUSED = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> ENGINEER_STATE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> ENGINEER_TARGET = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> FIELD_ORDER = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> FIELD_TYPE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Long> FIELD_ANCHOR = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Integer> FIELD_RADIUS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> FIELD_STATE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> FIELD_PROGRESS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> FIELD_FOUND = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> FIELD_STOCK = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> SECURITY_ORDER = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Long> SECURITY_ANCHOR = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Integer> SECURITY_RADIUS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> SECURITY_CONTACT = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> SECURITY_CONTACTS = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> SECURITY_LOADOUT = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> PATROL_ROUTE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> PATROL_ROUTE_INDEX = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> PATROL_ROUTE_FIRST_LEG = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> COMBAT_STATE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> COMBAT_WEAPON = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> COMBAT_TARGET = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> COMBAT_CHARGE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> COMBAT_SLOT = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> COMBAT_COUNT = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Long> COMBAT_STATE_TICK = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Integer> COMBAT_SHOT_TICK = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> GUN_AMMO = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> MISSILES = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> LASER_HEAT = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> COMBAT_AIM_X = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> COMBAT_AIM_Y = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> COMBAT_AIM_Z = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> COMBAT_AIM_TARGET = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> SALVAGE_TARGET = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> PROPULSION_CONDITION = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> SENSOR_CONDITION = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> PAYLOAD_CONDITION = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
	private final List<ItemStack> cargo = new ArrayList<>();
	private long cargoServiceReadyTick = -1L;
	private long cargoAccessAcquiredTick = -1L;
	private long cargoRetryTick = -1L;
	private UUID engineerTargetId;
	private BlockPos fieldWorkTarget;
	private Vec3 fieldWorkApproach;
	private FieldOperationRegistry.ReplantRequest fieldReplantTarget;
	private BlockPos fieldPickupTarget;
	private boolean fieldLocalSurveyed;
	private long fieldCargoQuietSinceTick = -1L;
	private long fieldCargoLoadedSinceTick = -1L;
	private boolean fieldCompletionDispatched;
	private BlockPos lightPos;
	private BlockPos groundLightPos;
	private boolean defenseLaunchedFromDock;
	private int protectionHoldTicks;
	private Vec3 heldThreatPosition;
	private Vec3 heldInterceptPosition;
	private Path activeFlightPath;
	private BlockPos pathGoal;
	private int pathRefreshTicks;
	private Vec3 escapeTarget;
	private int escapeTicks;
	private Vec3 progressSample;
	private int progressSampleTick;
	private int stalledTicks;
	private int recoveryAttempts;
	private boolean routedFlight;
	private boolean strategicFlight;
	private Vec3 strategicWaypoint;
	private Vec3 strategicGoal;
	private int strategicSide;
	private int strategicWaypointTicks;
	private Vec3 routeGuidanceTarget;
	private Vec3 localDetourTarget;
	private Vec3 localDetourGoal;
	private int localDetourTicks;
	private Direction dockApproachDirection;
	private MissionDataLink.Snapshot missionIntel = MissionDataLink.Snapshot.empty();
	private ScoutRoutePolicy.Decision scoutRouteDecision = ScoutRoutePolicy.Decision.local();
	private int emergencyTargetId = -1;
	private Vec3 emergencyTargetPosition;
	private long emergencyInterceptUntil = -1L;
	private int emergencyInterceptSlot;
	private int emergencyInterceptCount;
	private boolean emergencyLaunchedFromDock;
	private int verificationCombatTargetId = -1;
	private int verificationVisualTicks;
	private int verificationVisualTargetId = -1;
	private boolean serviceReturn;
	private final DroneTaskStack taskStack = new DroneTaskStack();
	private DroneServicePolicy.Need queuedServiceReason = DroneServicePolicy.Need.NONE;
	private UUID solarServiceStationId;
	private int solarServiceSlot = -1;
	private boolean solarTaskSuspended;
	private double solarOrbitPhase = Double.NaN;
	private long solarOrbitTick = -1L;
	private boolean solarOrbitEstablished;
	private int serviceResumeMode = DroneMode.STANDBY.id();
	private DroneServicePolicy.Need serviceReason = DroneServicePolicy.Need.NONE;
	private int rechargeReclaimTargetId = -1;
	private UUID rechargeReclaimTargetUuid;
	private UUID rechargeReliefUnitUuid;
	private long rechargeReclaimUntil = -1L;
	private int rechargeReclaimSlot = -1;
	private String reliefMissionInheritedFrom = "";
	private int casTrackedTargetId = -1;
	private Vec3 casManeuverCenter;
	private long casTrackTick = -1L;
	private boolean casBreakawayActive;
	private Vec3 casBreakawayPoint;
	private Vec3 casBreakawayDirection;
	private int laserOrbitCharge;
	private long laserOrbitChargeTick = -1L;
	private long laserReadySinceTick = -1L;
	private double laserClearanceLift;
	private long laserClearanceLiftTick = -1L;
	private int combatResumeMode = -1;
	private DroneOperationalState loggedOperationalState;
	private boolean powerLostBeaconReported;
	private boolean powerLossTaskCaptured;
	private int powerLossResumeMode = DroneMode.STANDBY.id();
	private UUID salvageTargetId;
	private SalvageState salvageState = SalvageState.IDLE;
	private int salvageStateTicks;
	private Vec3 salvageTowVelocity = Vec3.ZERO;

	public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
		super(type, level);
		this.moveControl = new FlyingMoveControl<>(this, 20, true);
		setNoGravity(true);
		setPathfindingMalus(PathType.WATER, 16.0f);
		setPathfindingMalus(PathType.WATER_BORDER, 8.0f);
		setPathfindingMalus(PathType.LAVA, -1.0f);
		setPathfindingMalus(PathType.FIRE, -1.0f);
		setPathfindingMalus(PathType.DAMAGING, -1.0f);
		setPathfindingMalus(PathType.LEAVES, 8.0f);
	}

	@Override
	protected PathNavigation createNavigation(Level level) {
		FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);
		navigation.setCanFloat(false);
		navigation.setCanOpenDoors(false);
		return navigation;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 24.0)
			.add(Attributes.MOVEMENT_SPEED, 0.34)
			.add(Attributes.FLYING_SPEED, 0.48)
			.add(Attributes.FOLLOW_RANGE, 64.0)
			.add(Attributes.KNOCKBACK_RESISTANCE, 0.8);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(OWNER, "");
		builder.define(OWNER_NAME, "");
		builder.define(UNIT_ID, "MG-DRN-UNSET");
		builder.define(MODE, DroneMode.STANDBY.id());
		builder.define(ROLE, DroneRole.FIELD.id());
		builder.define(BATTERY, 1000);
		builder.define(BATTERY_TIER, BatteryTier.STANDARD.id());
		builder.define(WEAPON_POWER, 1000);
		builder.define(DOCK_POS, Long.MIN_VALUE);
		builder.define(DOCKED, false);
		builder.define(DOCK_STAGE, 0);
		builder.define(GROUP, "ALPHA");
		builder.define(WAYPOINT_POS, BlockPos.ZERO.asLong());
		builder.define(HAS_WAYPOINT, false);
		builder.define(MISSION_ID, "");
		builder.define(MISSION_EXPECTED, 1);
		builder.define(MISSION_INDEX, 0);
		builder.define(MISSION_STAGE, MISSION_MUSTER);
		builder.define(MISSION_ORIGIN, BlockPos.ZERO.asLong());
		builder.define(MISSION_ASSIGNED_TICK, 0L);
		builder.define(ORBIT_ENTRY_TICK, -1L);
		builder.define(ORBIT_PHASE_OFFSET, Float.NaN);
		builder.define(TRACK_TARGET, "");
		builder.define(THREAT_SCORE, 0);
		builder.define(DEFENSE_SLOT, -1);
		builder.define(RECOVERY_LEVEL, 0);
		builder.define(RECOVERY_SINCE, -1L);
		builder.define(COHORT_ID, "");
		builder.define(COHORT_LEADER, "");
		builder.define(COHORT_RANK, -1);
		builder.define(RENDEZVOUS_POS, Long.MIN_VALUE);
		builder.define(FORMATION_CATCH_UP, false);
		builder.define(DATA_LINK_STATUS, "LOCAL");
		builder.define(OPERATION_SUMMARY, "");
		builder.define(OPERATION_FRONTS, "");
		builder.define(CARGO_SOURCE, Long.MIN_VALUE);
		builder.define(CARGO_TARGET, Long.MIN_VALUE);
		builder.define(CARGO_STATE, CargoState.UNASSIGNED.id());
		builder.define(CARGO_COUNT, 0);
		builder.define(CARGO_QUEUE_POSITION, 0);
		builder.define(CARGO_PAUSED, false);
		builder.define(ENGINEER_STATE, EngineerState.IDLE.id());
		builder.define(ENGINEER_TARGET, "");
		builder.define(FIELD_ORDER, "");
		builder.define(FIELD_TYPE, FieldOperationType.NONE.id());
		builder.define(FIELD_ANCHOR, Long.MIN_VALUE);
		builder.define(FIELD_RADIUS, 0);
		builder.define(FIELD_STATE, FieldOperationState.IDLE.id());
		builder.define(FIELD_PROGRESS, 0);
		builder.define(FIELD_FOUND, 0);
		builder.define(FIELD_STOCK, 0);
		builder.define(SECURITY_ORDER, "");
		builder.define(SECURITY_ANCHOR, Long.MIN_VALUE);
		builder.define(SECURITY_RADIUS, 0);
		builder.define(SECURITY_CONTACT, -1);
		builder.define(SECURITY_CONTACTS, 0);
		builder.define(SECURITY_LOADOUT, SecurityLoadout.UNARMED.id());
		builder.define(PATROL_ROUTE, "");
		builder.define(PATROL_ROUTE_INDEX, 0);
		builder.define(PATROL_ROUTE_FIRST_LEG, false);
		builder.define(COMBAT_STATE, CombatState.IDLE.id());
		builder.define(COMBAT_WEAPON, CombatWeapon.NONE.id());
		builder.define(COMBAT_TARGET, -1);
		builder.define(COMBAT_CHARGE, 0);
		builder.define(COMBAT_SLOT, 0);
		builder.define(COMBAT_COUNT, 1);
		builder.define(COMBAT_STATE_TICK, -1L);
		builder.define(COMBAT_SHOT_TICK, -1000);
		builder.define(GUN_AMMO, CombatPolicy.GUN_CAPACITY);
		builder.define(MISSILES, CombatPolicy.MISSILE_CAPACITY);
		builder.define(LASER_HEAT, 0);
		builder.define(COMBAT_AIM_X, 0.0f);
		builder.define(COMBAT_AIM_Y, 0.0f);
		builder.define(COMBAT_AIM_Z, 0.0f);
		builder.define(COMBAT_AIM_TARGET, -1);
		builder.define(SALVAGE_TARGET, -1);
		builder.define(PROPULSION_CONDITION, DroneSubsystemPolicy.MAX);
		builder.define(SENSOR_CONDITION, DroneSubsystemPolicy.MAX);
		builder.define(PAYLOAD_CONDITION, DroneSubsystemPolicy.MAX);
	}

	public void initializeOwner(ServerPlayer owner) {
		setPersistenceRequired();
		entityData.set(OWNER, owner.getUUID().toString());
		entityData.set(OWNER_NAME, owner.getScoreboardName());
		String suffix = getUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
		entityData.set(UNIT_ID, "MG-DRN-" + suffix);
		setMode(DroneMode.STANDBY);
		setCustomName(Component.literal(unitId()));
		setCustomNameVisible(false);
	}

	public boolean isOwnedBy(UUID uuid) {
		return ownerId().equals(uuid);
	}

	public boolean matchesOwner(UUID uuid, String playerName) {
		return isOwnedBy(uuid) || (!ownerName().isBlank() && ownerName().equalsIgnoreCase(playerName));
	}

	public boolean isOwnedBy(ServerPlayer player) {
		if (isOwnedBy(player.getUUID())) return true;
		boolean sameNamedOwner = !ownerName().isBlank() && ownerName().equalsIgnoreCase(player.getScoreboardName());
		boolean legacySingleplayer = player.level().getServer().isSingleplayer()
			&& player.level().getServer().getPlayerList().getPlayers().size() == 1;
		if (!sameNamedOwner && !legacySingleplayer) return false;
		entityData.set(OWNER, player.getUUID().toString());
		entityData.set(OWNER_NAME, player.getScoreboardName());
		return true;
	}

	public String ownerName() {
		return entityData.get(OWNER_NAME);
	}

	public UUID ownerId() {
		try {
			return UUID.fromString(entityData.get(OWNER));
		} catch (IllegalArgumentException ignored) {
			return new UUID(0, 0);
		}
	}

	public String unitId() {
		return entityData.get(UNIT_ID);
	}

	public String groupId() {
		return entityData.get(GROUP);
	}

	public DroneMode mode() {
		return DroneMode.byId(entityData.get(MODE));
	}

	public DroneRole role() {
		return DroneRole.byId(entityData.get(ROLE));
	}

	public void assignRole(DroneRole role) {
		DroneRole next = role == null ? DroneRole.FIELD : role;
		if (role() != next) {
			releaseCargoAccess();
			entityData.set(CARGO_STATE, CargoState.UNASSIGNED.id());
			entityData.set(CARGO_QUEUE_POSITION, 0);
			if (role() == DroneRole.CARGO) entityData.set(CARGO_PAUSED, true);
			clearEngineerAssignment();
			if (!MissionAssignmentPolicy.allows(next, MissionAssignmentPolicy.MissionKind.FIELD_OPERATION))
				clearFieldOperation();
			if (!MissionAssignmentPolicy.allows(next, MissionAssignmentPolicy.MissionKind.SECURITY_PATROL))
				clearSecurityPatrol();
		}
		entityData.set(ROLE, next.id());
		if (next == DroneRole.CARGO && !cargoPaused()) startCargoRouteIfReady();
	}

	public String dataLinkStatus() {
		return entityData.get(DATA_LINK_STATUS);
	}

	public String operationSummary() {
		return entityData.get(OPERATION_SUMMARY);
	}

	public String operationFronts() {
		return entityData.get(OPERATION_FRONTS);
	}

	int emergencyTargetForCoordination() {
		return emergencyInterceptActive() ? emergencyTargetId : -1;
	}

	public int batteryPercent() {
		return Math.max(0, Math.min(100, entityData.get(BATTERY) * 100 / batteryTier().capacity()));
	}

	public void receiveSolarFlightCharge(int amount) {
		if (amount <= 0 || isPowerLost()) return;
		entityData.set(BATTERY, Math.min(batteryTier().capacity(), entityData.get(BATTERY) + amount));
		entityData.set(DATA_LINK_STATUS, "SOLAR LINK / CHARGING " + batteryPercent() + "%");
	}

	public boolean isPowerLost() {
		return PowerLossPolicy.powerLost(entityData.get(BATTERY));
	}

	public SalvageState salvageState() {
		return salvageState;
	}

	public int salvageTargetEntityId() {
		return entityData.get(SALVAGE_TARGET);
	}

	public String salvageStatusLabel() {
		String target = salvageTargetUnitId();
		return "SALVAGE " + salvageState.name() + (target.isBlank() ? "" : " / " + target);
	}

	public String salvageTargetUnitId() {
		if (salvageTargetId == null || !(level() instanceof ServerLevel level)) return "";
		Entity target = level.getEntity(salvageTargetId);
		return target instanceof DroneEntity drone ? drone.unitId() : "";
	}

	public ItemStack createStoredUnit() {
		ItemStack stack = new ItemStack(MorrowgearDrone.DRONE_UNIT);
		new StoredDroneState(unitId(), role(), batteryTier(), entityData.get(BATTERY),
			entityData.get(WEAPON_POWER), groupId(), securityLoadout(), gunAmmo(), missiles(),
			laserHeat(), getHealth(), entityData.get(DOCK_POS), propulsionCondition(),
			sensorCondition(), payloadCondition()).write(stack);
		stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(cargo));
		return stack;
	}

	public void restoreStoredUnit(StoredDroneState stored) {
		if (stored == null) return;
		entityData.set(UNIT_ID, stored.unitId());
		entityData.set(ROLE, stored.role().id());
		entityData.set(BATTERY_TIER, stored.batteryTier().id());
		entityData.set(BATTERY, stored.batteryTier().clamp(stored.flightPower()));
		entityData.set(WEAPON_POWER, DroneStatePolicy.battery(stored.weaponPower()));
		entityData.set(GROUP, DroneStatePolicy.group(stored.groupId()));
		entityData.set(SECURITY_LOADOUT, stored.securityLoadout().id());
		entityData.set(GUN_AMMO, Mth.clamp(stored.gunAmmo(), 0, CombatPolicy.GUN_CAPACITY));
		entityData.set(MISSILES, Mth.clamp(stored.missiles(), 0, CombatPolicy.MISSILE_CAPACITY));
		entityData.set(LASER_HEAT, Mth.clamp(stored.laserHeat(), 0, 1000));
		entityData.set(DOCK_POS, stored.dockPos());
		entityData.set(PROPULSION_CONDITION, stored.propulsionCondition());
		entityData.set(SENSOR_CONDITION, stored.sensorCondition());
		entityData.set(PAYLOAD_CONDITION, stored.payloadCondition());
		entityData.set(DOCKED, false);
		entityData.set(DOCK_STAGE, 0);
		setHealth(Math.min(getMaxHealth(), stored.health()));
		clearCombatState();
		taskStack.clear();
		powerLossTaskCaptured = false;
		solarTaskSuspended = false;
		clearMissionAssignment();
		setMode(DroneMode.STANDBY);
	}

	public void restoreStoredCargo(ItemStack source) {
		cargo.clear();
		ItemContainerContents contents = source.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
		contents.nonEmptyItemCopyStream().limit(9).forEach(cargo::add);
		updateCargoCount();
	}

	public BatteryTier batteryTier() {
		return BatteryTier.byId(entityData.get(BATTERY_TIER));
	}

	public int weaponPowerPercent() {
		return Math.max(0, Math.min(100, entityData.get(WEAPON_POWER) / 10));
	}

	public int propulsionCondition() { return entityData.get(PROPULSION_CONDITION); }
	public int sensorCondition() { return entityData.get(SENSOR_CONDITION); }
	public int payloadCondition() { return entityData.get(PAYLOAD_CONDITION); }

	public int lowestSubsystemCondition() {
		return Math.min(propulsionCondition(), Math.min(sensorCondition(), payloadCondition()));
	}

	void setPowerForVerification(int flightPower, int weaponPower) {
		entityData.set(BATTERY, batteryTier().storedForPercent(DroneStatePolicy.battery(flightPower) / 10));
		entityData.set(WEAPON_POWER, DroneStatePolicy.battery(weaponPower));
	}

	void setSubsystemConditionForVerification(int propulsion, int sensor, int payload) {
		entityData.set(PROPULSION_CONDITION, Mth.clamp(propulsion, 0, DroneSubsystemPolicy.MAX));
		entityData.set(SENSOR_CONDITION, Mth.clamp(sensor, 0, DroneSubsystemPolicy.MAX));
		entityData.set(PAYLOAD_CONDITION, Mth.clamp(payload, 0, DroneSubsystemPolicy.MAX));
	}

	void assignSalvageTargetForVerification(DroneEntity target) {
		if (!(level() instanceof ServerLevel level) || target == null) return;
		if (salvageTargetId != null) SalvageMissionRegistry.release(salvageTargetId, getUUID());
		if (!SalvageMissionRegistry.reserve(target.getUUID(), getUUID())) return;
		salvageTargetId = target.getUUID();
		entityData.set(SALVAGE_TARGET, target.getId());
		setSalvageState(SalvageState.INTERCEPT);
		assignWaypoint(target.blockPosition().above(3), "salvage-" + target.unitId(), 1, 0,
			blockPosition(), level.getGameTime());
		entityData.set(DATA_LINK_STATUS, "SALVAGE INTERCEPT / " + target.unitId());
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (player.getItemInHand(hand).is(MorrowgearDrone.CONTROLLER) && isPowerLost()) {
			if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
			return MorrowgearDrone.recoverPowerLost(serverPlayer, this)
				? InteractionResult.SUCCESS : InteractionResult.FAIL;
		}
		BatteryTier requested = MorrowgearDrone.batteryTierForItem(player.getItemInHand(hand).getItem());
		if (requested == null) return super.mobInteract(player, hand);
		if (!(player instanceof ServerPlayer serverPlayer) || !isOwnedBy(serverPlayer)) return InteractionResult.FAIL;
		if (!isDocked() || !hasDock()) {
			serverPlayer.sendSystemMessage(Component.literal("[MORROWGEAR] Battery service requires Dock landing."));
			return InteractionResult.FAIL;
		}
		if (requested == batteryTier()) return InteractionResult.SUCCESS;
		BatteryTier previous = batteryTier();
		int retainedPercent = batteryPercent();
		if (!player.isCreative()) {
			player.getItemInHand(hand).shrink(1);
			if (previous != BatteryTier.STANDARD) {
				ItemStack returned = new ItemStack(MorrowgearDrone.itemForBatteryTier(previous));
				if (!player.getInventory().add(returned)) player.drop(returned, false);
			}
		}
		entityData.set(BATTERY_TIER, requested.id());
		entityData.set(BATTERY, requested.storedForPercent(retainedPercent));
		entityData.set(DATA_LINK_STATUS, "BATTERY " + requested.displayName() + " / " + retainedPercent + "%");
		serverPlayer.sendSystemMessage(Component.literal("[MORROWGEAR] " + unitId()
			+ " / BATTERY " + requested.displayName()));
		return InteractionResult.SUCCESS;
	}

	void setDockedForVerification(boolean docked) {
		entityData.set(DOCKED, docked);
		if (docked) setDeltaMovement(Vec3.ZERO);
	}

	public void installBatteryForCommission(BatteryTier tier) {
		BatteryTier installed = tier == null ? BatteryTier.STANDARD : tier;
		entityData.set(BATTERY_TIER, installed.id());
		entityData.set(BATTERY, installed.capacity());
	}

	public void setDockedForCommission() {
		entityData.set(DOCKED, true);
		entityData.set(DOCK_STAGE, 2);
		setDeltaMovement(Vec3.ZERO);
		setNoGravity(true);
		setMode(DroneMode.STANDBY);
	}

	void setCombatResourcesForVerification(int gunAmmo, int missiles, int laserHeat) {
		entityData.set(GUN_AMMO, Mth.clamp(gunAmmo, 0, CombatPolicy.GUN_CAPACITY));
		entityData.set(MISSILES, Mth.clamp(missiles, 0, CombatPolicy.MISSILE_CAPACITY));
		entityData.set(LASER_HEAT, Mth.clamp(laserHeat, 0, 1000));
	}

	void setEmergencyInterceptForVerification(int targetId, Vec3 targetPosition, int responders) {
		verificationCombatTargetId = targetId;
		emergencyInterceptUntil = targetId >= 0 && targetPosition != null
			? level().getGameTime() + 30L : -1L;
		emergencyTargetId = targetId;
		emergencyTargetPosition = targetPosition;
		emergencyInterceptCount = Math.max(1, responders);
		emergencyInterceptSlot = 0;
	}

	void beginServiceReturnForVerification(DroneServicePolicy.Need reason, Entity target) {
		if (target != null) setEmergencyInterceptForVerification(target.getId(), target.position(), 1);
		beginServiceReturn(reason);
		setDockedForVerification(true);
	}

	void resetCombatForVerification() {
		clearCombatState();
		clearEmergencyInterception();
		verificationCombatTargetId = -1;
		serviceReturn = false;
		serviceReason = DroneServicePolicy.Need.NONE;
		rechargeReclaimTargetId = -1;
		rechargeReclaimTargetUuid = null;
		rechargeReliefUnitUuid = null;
		rechargeReclaimUntil = -1L;
		rechargeReclaimSlot = -1;
		reliefMissionInheritedFrom = "";
		entityData.set(COMBAT_SHOT_TICK, tickCount - 1000);
		entityData.set(COMBAT_AIM_X, (float)getX());
		entityData.set(COMBAT_AIM_Y, (float)getY());
		entityData.set(COMBAT_AIM_Z, (float)getZ());
		entityData.set(COMBAT_AIM_TARGET, -1);
	}

	void resetNavigationForVerification() {
		getNavigation().stop();
		activeFlightPath = null;
		pathGoal = null;
		pathRefreshTicks = 0;
		escapeTarget = null;
		escapeTicks = 0;
		progressSample = null;
		progressSampleTick = tickCount;
		stalledTicks = 0;
		recoveryAttempts = 0;
		entityData.set(RECOVERY_LEVEL, 0);
		entityData.set(RECOVERY_SINCE, -1L);
		clearStrategicWaypoint();
		routedFlight = false;
		strategicFlight = false;
	}

	void holdCombatVisualForVerification(CombatState state, CombatWeapon weapon,
		LivingEntity target, int ticks) {
		verificationVisualTicks = Math.max(1, ticks);
		verificationVisualTargetId = target.getId();
		entityData.set(COMBAT_TARGET, target.getId());
		entityData.set(COMBAT_WEAPON, weapon.id());
		entityData.set(COMBAT_CHARGE, state == CombatState.LASER_FIRE ? 1000 : 0);
		setCombatState(state);
		updateVerificationVisualAim(target);
	}

	private boolean tickVerificationVisual(ServerLevel level) {
		if (verificationVisualTicks <= 0) return false;
		verificationVisualTicks--;
		Entity target = level.getEntity(verificationVisualTargetId);
		if (!(target instanceof LivingEntity living) || !living.isAlive()) {
			verificationVisualTicks = 0;
			resetCombatForVerification();
			return false;
		}
		updateVerificationVisualAim(living);
		if (combatState() == CombatState.GUN_RUN) {
			entityData.set(COMBAT_SHOT_TICK, tickCount - 2);
		}
		setDeltaMovement(Vec3.ZERO);
		return true;
	}

	private void updateVerificationVisualAim(LivingEntity target) {
		Vec3 aim = target.position().add(0, target.getBbHeight() * 0.58, 0);
		entityData.set(COMBAT_AIM_X, (float)aim.x);
		entityData.set(COMBAT_AIM_Y, (float)aim.y);
		entityData.set(COMBAT_AIM_Z, (float)aim.z);
		entityData.set(COMBAT_AIM_TARGET, target.getId());
	}

	public boolean hasCargoSource() { return entityData.get(CARGO_SOURCE) != Long.MIN_VALUE; }
	public boolean hasCargoTarget() { return entityData.get(CARGO_TARGET) != Long.MIN_VALUE; }
	public BlockPos cargoSource() { return hasCargoSource() ? BlockPos.of(entityData.get(CARGO_SOURCE)) : BlockPos.ZERO; }
	public BlockPos cargoTarget() { return hasCargoTarget() ? BlockPos.of(entityData.get(CARGO_TARGET)) : BlockPos.ZERO; }
	public CargoState cargoState() { return CargoState.byId(entityData.get(CARGO_STATE)); }
	public int cargoItemCount() { return entityData.get(CARGO_COUNT); }
	public int cargoQueuePosition() { return Math.max(0, entityData.get(CARGO_QUEUE_POSITION)); }
	public boolean cargoPaused() { return entityData.get(CARGO_PAUSED); }
	public String cargoStatusLabel() {
		return cargoState().label() + (cargoState().queued() ? " #" + cargoQueuePosition() : "");
	}

	public EngineerState engineerState() { return EngineerState.byId(entityData.get(ENGINEER_STATE)); }
	public String engineerTargetLabel() { return entityData.get(ENGINEER_TARGET); }
	public String engineerStatusLabel() {
		return engineerState().label() + (engineerTargetLabel().isBlank() ? "" : " / " + engineerTargetLabel());
	}

	public boolean hasFieldOperation() { return !entityData.get(FIELD_ORDER).isBlank(); }
	public boolean hasActiveFieldOperation() {
		return hasFieldOperation() && fieldOperationState() != FieldOperationState.COMPLETE;
	}
	public String fieldOrderId() { return entityData.get(FIELD_ORDER); }
	public FieldOperationType fieldOperationType() { return FieldOperationType.byId(entityData.get(FIELD_TYPE)); }
	public BlockPos fieldAnchor() { return entityData.get(FIELD_ANCHOR) == Long.MIN_VALUE ? BlockPos.ZERO : BlockPos.of(entityData.get(FIELD_ANCHOR)); }
	public int fieldRadius() { return entityData.get(FIELD_RADIUS); }
	public FieldOperationState fieldOperationState() { return FieldOperationState.byId(entityData.get(FIELD_STATE)); }
	public int fieldProgress() { return entityData.get(FIELD_PROGRESS); }
	public int fieldFound() { return entityData.get(FIELD_FOUND); }
	public int fieldStockItems() { return entityData.get(FIELD_STOCK); }
	public String fieldStatusLabel() {
		return fieldOperationType().label() + " / " + fieldOperationState().label()
			+ " / " + fieldProgress() + "% / FOUND " + fieldFound();
	}

	public boolean hasSecurityPatrol() {
		return role() == DroneRole.SECURITY && !entityData.get(SECURITY_ORDER).isBlank();
	}

	public BlockPos securityAnchor() {
		return entityData.get(SECURITY_ANCHOR) == Long.MIN_VALUE
			? BlockPos.ZERO : BlockPos.of(entityData.get(SECURITY_ANCHOR));
	}

	public int securityRadius() {
		return Math.max(6, entityData.get(SECURITY_RADIUS));
	}

	public int securityContacts() {
		return Math.max(0, entityData.get(SECURITY_CONTACTS));
	}

	String securityOrderId() {
		return entityData.get(SECURITY_ORDER);
	}

	String reliefMissionInheritedFrom() {
		return reliefMissionInheritedFrom;
	}

	public String securityStatusLabel() {
		if (combatActive()) return "戦闘 / " + combatWeapon().label();
		if (emergencyInterceptActive()) return "緊急迎撃 / CONTACT " + securityContacts();
		return securityContacts() > 0 ? "接触追跡 / CONTACT " + securityContacts()
			: "地点警戒 / R" + securityRadius();
	}

	public List<BlockPos> patrolRoutePoints() {
		return PatrolRoutePolicy.decode(entityData.get(PATROL_ROUTE));
	}

	public int patrolRouteSize() {
		return patrolRoutePoints().size();
	}

	public int patrolRouteIndex() {
		int size = patrolRouteSize();
		return size == 0 ? 0 : Math.floorMod(entityData.get(PATROL_ROUTE_INDEX), size);
	}

	public boolean hasPatrolRoute() {
		return patrolRouteSize() > 0 && mode() == DroneMode.WAYPOINT;
	}

	public boolean emergencyInterceptActive() {
		return emergencyInterceptUntil >= 0L && level().getGameTime() <= emergencyInterceptUntil;
	}
	int emergencyTargetIdForVerification() { return emergencyTargetId; }
	public boolean serviceReturnActive() { return serviceReturn; }
	boolean weaponRechargeActive() { return serviceReturn; }

	public CombatState combatState() { return CombatState.byId(entityData.get(COMBAT_STATE)); }
	public CombatWeapon combatWeapon() { return CombatWeapon.byId(entityData.get(COMBAT_WEAPON)); }
	public SecurityLoadout securityLoadout() { return SecurityLoadout.byId(entityData.get(SECURITY_LOADOUT)); }
	public void assignSecurityLoadout(SecurityLoadout loadout) {
		if (role() != DroneRole.SECURITY || loadout == null) return;
		entityData.set(SECURITY_LOADOUT, loadout.id());
		clearCombatState();
	}
	public int combatTargetId() { return entityData.get(COMBAT_TARGET); }
	public int combatCharge() { return Math.max(0, Math.min(1000, entityData.get(COMBAT_CHARGE))); }
	public int combatSlot() { return Math.max(0, entityData.get(COMBAT_SLOT)); }
	public int combatCount() { return Math.max(1, entityData.get(COMBAT_COUNT)); }
	public long combatStateTick() { return entityData.get(COMBAT_STATE_TICK); }
	public int combatShotAge() { return tickCount - entityData.get(COMBAT_SHOT_TICK); }
	public int gunAmmo() { return Math.max(0, entityData.get(GUN_AMMO)); }
	public int missiles() { return Math.max(0, entityData.get(MISSILES)); }
	public int laserHeat() { return Math.max(0, Math.min(1000, entityData.get(LASER_HEAT))); }
	public boolean combatActive() { return combatState().active(); }
	public DroneOperationalState operationalState() {
		return DroneOperationalState.resolve(entityData.get(BATTERY), isDocked(), serviceReturn,
			combatState(), emergencyInterceptActive(), hasActiveFieldOperation(), hasSecurityPatrol(),
			engineerState() != EngineerState.IDLE, cargoState() != CargoState.UNASSIGNED, mode());
	}
	public Vec3 autocannonVisualAim(Entity target) {
		return new Vec3(entityData.get(COMBAT_AIM_X), entityData.get(COMBAT_AIM_Y),
			entityData.get(COMBAT_AIM_Z)).subtract(position());
	}
	public Vec3 combatVisualAim() {
		return new Vec3(entityData.get(COMBAT_AIM_X), entityData.get(COMBAT_AIM_Y),
			entityData.get(COMBAT_AIM_Z)).subtract(position());
	}

	public boolean hasValidCombatVisualAim() {
		return combatTargetId() >= 0 && entityData.get(COMBAT_AIM_TARGET) == combatTargetId();
	}
	public String combatStatusLabel() {
		if (combatState().rejoining()) return combatState().label() + " / " + groupId();
		if (!combatActive()) return "COMBAT READY / " + securityLoadout().displayName();
		return combatState().label() + " / " + combatWeapon().label()
			+ (combatWeapon() == CombatWeapon.LASER ? " " + combatCharge() / 10 + "%" : "");
	}

	public TargetDisposition trackingTargetDisposition() {
		if (!(level() instanceof ServerLevel level)) return TargetDisposition.INVALID;
		LivingEntity target = trackingTarget(level);
		if (target == null) return TargetDisposition.INVALID;
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId());
		if (owner == null) return TargetDisposition.INVALID;
		LivingEntity recentAttacker = owner.tickCount - owner.getLastHurtByMobTimestamp() <= 200
			? owner.getLastHurtByMob() : null;
		return ThreatAssessment.targetDisposition(target, owner, recentAttacker);
	}

	public String trackingStatusLabel() {
		return hasTrackingTarget() ? "TARGET TRACK / " + trackingTargetDisposition().label()
			: "TARGET TRACK / NONE";
	}

	public void assignPatrolRoute(List<BlockPos> points, String missionId, int expected, int index,
		BlockPos origin, long assignedTick) {
		assignPatrolRoute(points, missionId, expected, index, origin, assignedTick, 0);
	}

	public void assignPatrolRoute(List<BlockPos> points, String missionId, int expected, int index,
		BlockPos origin, long assignedTick, int startIndex) {
		if (points == null || points.isEmpty() || points.size() > PatrolRoutePolicy.MAX_POINTS) return;
		clearFieldOperation();
		clearSecurityPatrol();
		List<BlockPos> horizontal = points.stream().map(point -> new BlockPos(point.getX(), 0, point.getZ())).toList();
		entityData.set(PATROL_ROUTE, PatrolRoutePolicy.encode(horizontal));
		int firstIndex = Math.floorMod(startIndex, horizontal.size());
		entityData.set(PATROL_ROUTE_INDEX, firstIndex);
		entityData.set(PATROL_ROUTE_FIRST_LEG, true);
		routeGuidanceTarget = null;
		BlockPos first = patrolSurfacePoint(horizontal.get(firstIndex));
		entityData.set(WAYPOINT_POS, first.asLong());
		entityData.set(TRACK_TARGET, "");
		assignMission(missionId, expected, index, origin, assignedTick);
	}

	private void clearPatrolRoute() {
		entityData.set(PATROL_ROUTE, "");
		entityData.set(PATROL_ROUTE_INDEX, 0);
		entityData.set(PATROL_ROUTE_FIRST_LEG, false);
		routeGuidanceTarget = null;
	}

	public void assignSecurityPatrol(BlockPos anchor, int radius, String orderId) {
		if (!MissionAssignmentPolicy.allows(role(), MissionAssignmentPolicy.MissionKind.SECURITY_PATROL)
			|| anchor == null || orderId == null || orderId.isBlank()) return;
		clearFieldOperation();
		launchStationaryMission();
		setMode(DroneMode.STANDBY);
		entityData.set(SECURITY_ORDER, orderId);
		entityData.set(SECURITY_ANCHOR, anchor.asLong());
		entityData.set(SECURITY_RADIUS, Math.max(6, Math.min(32, radius)));
		entityData.set(SECURITY_CONTACT, -1);
		entityData.set(SECURITY_CONTACTS, 0);
		if (serviceReturn) taskStack.queue(currentTaskSnapshot(DroneMode.STANDBY));
		refreshOperationTicket();
	}

	public void clearSecurityPatrol() {
		entityData.set(SECURITY_ORDER, "");
		entityData.set(SECURITY_ANCHOR, Long.MIN_VALUE);
		entityData.set(SECURITY_RADIUS, 0);
		entityData.set(SECURITY_CONTACT, -1);
		entityData.set(SECURITY_CONTACTS, 0);
	}

	public void assignFieldOperation(FieldOperationType type, BlockPos anchor, int radius, String orderId) {
		if (!MissionAssignmentPolicy.allows(role(), MissionAssignmentPolicy.MissionKind.FIELD_OPERATION)
			|| type == null || type == FieldOperationType.NONE || anchor == null
			|| orderId == null || orderId.isBlank()) return;
		clearFieldOperation();
		clearSecurityPatrol();
		entityData.set(FIELD_ORDER, orderId);
		entityData.set(FIELD_TYPE, type.id());
		entityData.set(FIELD_ANCHOR, anchor.asLong());
		entityData.set(FIELD_RADIUS, DroneStatePolicy.fieldRadius(type, radius));
		entityData.set(FIELD_STATE, type == FieldOperationType.EXCAVATE
			? FieldOperationState.TRANSIT.id() : FieldOperationState.SCANNING.id());
		entityData.set(FIELD_PROGRESS, 0);
		entityData.set(FIELD_FOUND, 0);
		entityData.set(FIELD_STOCK, 0);
		fieldWorkTarget = null;
		fieldWorkApproach = null;
		fieldReplantTarget = null;
		fieldPickupTarget = null;
		fieldLocalSurveyed = false;
		fieldCargoQuietSinceTick = -1L;
		fieldCargoLoadedSinceTick = -1L;
		fieldCompletionDispatched = false;
		launchStationaryMission();
		setMode(DroneMode.STANDBY);
		refreshOperationTicket();
	}

	private void refreshOperationTicket() {
		if (level() instanceof ServerLevel serverLevel) {
			serverLevel.getChunkSource().addTicketWithRadius(MorrowgearDrone.DRONE_OPERATION_TICKET,
				chunkPosition(), RemoteOperationPolicy.ENTITY_TICKING_TICKET_RADIUS);
		}
	}

	private void launchStationaryMission() {
		entityData.set(DOCKED, false);
		entityData.set(DOCK_STAGE, 0);
		dockApproachDirection = null;
	}

	public void clearFieldOperation() {
		if (fieldWorkTarget != null && level() instanceof ServerLevel serverLevel) {
			FieldOperationRegistry.Operation operation = FieldOperationRegistry.find(
				serverLevel.dimension().toString(), ownerId(), fieldOrderId(), serverLevel.getGameTime());
			if (operation != null) operation.retryTarget(fieldWorkTarget, serverLevel.getGameTime());
		}
		entityData.set(FIELD_ORDER, "");
		entityData.set(FIELD_TYPE, FieldOperationType.NONE.id());
		entityData.set(FIELD_ANCHOR, Long.MIN_VALUE);
		entityData.set(FIELD_RADIUS, 0);
		entityData.set(FIELD_STATE, FieldOperationState.IDLE.id());
		entityData.set(FIELD_PROGRESS, 0);
		entityData.set(FIELD_FOUND, 0);
		entityData.set(FIELD_STOCK, 0);
		fieldWorkTarget = null;
		fieldWorkApproach = null;
		fieldReplantTarget = null;
		fieldPickupTarget = null;
		fieldLocalSurveyed = false;
		fieldCargoQuietSinceTick = -1L;
		fieldCargoLoadedSinceTick = -1L;
		fieldCompletionDispatched = false;
	}

	public void assignCargoSource(BlockPos pos) {
		if (!MissionAssignmentPolicy.allows(role(), MissionAssignmentPolicy.MissionKind.CARGO_ROUTE) || pos == null) return;
		releaseCargoAccess();
		entityData.set(CARGO_PAUSED, false);
		entityData.set(CARGO_SOURCE, pos.asLong());
		startCargoRouteIfReady();
	}

	public void assignCargoTarget(BlockPos pos) {
		if (!MissionAssignmentPolicy.allows(role(), MissionAssignmentPolicy.MissionKind.CARGO_ROUTE) || pos == null) return;
		releaseCargoAccess();
		entityData.set(CARGO_PAUSED, false);
		entityData.set(CARGO_TARGET, pos.asLong());
		startCargoRouteIfReady();
	}

	public List<ItemStack> removeAllCargo() {
		releaseCargoAccess();
		List<ItemStack> removed = cargo.stream().map(ItemStack::copy).toList();
		cargo.clear();
		updateCargoCount();
		return removed;
	}

	private void startCargoRouteIfReady() {
		if (role() != DroneRole.CARGO || cargoPaused() || !hasCargoSource() || !hasCargoTarget()
			|| cargoSource().equals(cargoTarget())) return;
		entityData.set(CARGO_STATE, CargoState.TO_SOURCE.id());
		entityData.set(CARGO_QUEUE_POSITION, 0);
		cargoRetryTick = -1L;
		assignWaypoint(cargoFlightWaypoint(cargoSource()), unitId() + "-cargo", 1, 0, blockPosition(), level().getGameTime());
	}

	public void pauseCargoRoute() {
		if (role() != DroneRole.CARGO) return;
		releaseCargoAccess();
		entityData.set(CARGO_PAUSED, true);
		entityData.set(CARGO_STATE, CargoState.UNASSIGNED.id());
		entityData.set(CARGO_QUEUE_POSITION, 0);
	}

	private static BlockPos cargoFlightWaypoint(BlockPos container) {
		// Normal waypoint missions add six blocks of cruise clearance. This offset
		// places the effective Cargo destination two blocks above the container.
		return container.below(4);
	}

	private static BlockPos cargoHoldingWaypoint(BlockPos container, int queuePosition) {
		int index = Math.max(0, queuePosition - 1);
		int layer = index / 8;
		double angle = (index % 8) * Math.PI / 4.0;
		double radius = 5.0 + layer * 2.0;
		return new BlockPos(
			container.getX() + (int)Math.round(Math.cos(angle) * radius),
			container.getY() - 4 + layer * 2,
			container.getZ() + (int)Math.round(Math.sin(angle) * radius));
	}

	public boolean hasDock() {
		return entityData.get(DOCK_POS) != Long.MIN_VALUE;
	}

	public BlockPos dockPos() {
		return hasDock() ? BlockPos.of(entityData.get(DOCK_POS)) : BlockPos.ZERO;
	}

	public boolean isDocked() {
		return entityData.get(DOCKED);
	}

	public boolean hasWaypoint() {
		return entityData.get(HAS_WAYPOINT);
	}

	public BlockPos waypointPos() {
		return BlockPos.of(entityData.get(WAYPOINT_POS));
	}

	public int missionStage() {
		return entityData.get(MISSION_STAGE);
	}

	public boolean formationCatchUp() {
		return entityData.get(FORMATION_CATCH_UP);
	}

	public int missionIndex() {
		return entityData.get(MISSION_INDEX);
	}

	public int missionExpected() {
		return entityData.get(MISSION_EXPECTED);
	}

	public int wingIndex() {
		return SwarmFormation.wingIndex(missionIndex());
	}

	public int wingCount() {
		return SwarmFormation.wingCount(missionExpected());
	}

	public double orbitLayerRadius() {
		int count = SwarmFormation.hierarchical(missionExpected())
			? SwarmFormation.wingSize(missionExpected(), missionIndex())
			: missionExpected();
		return SwarmFormation.hierarchical(missionExpected())
			? SwarmFormation.layeredOrbitRadius(count, wingIndex(), wingCount())
			: SwarmFormation.orbitRadius(count);
	}

	public double orbitLayerHeight() {
		return SwarmFormation.hierarchical(missionExpected()) ? wingIndex() * 3.8 : 0.0;
	}

	public String missionId() {
		return entityData.get(MISSION_ID);
	}

	public boolean hasTrackingTarget() {
		return !entityData.get(TRACK_TARGET).isBlank();
	}

	public UUID trackingTargetId() {
		try {
			return UUID.fromString(entityData.get(TRACK_TARGET));
		} catch (IllegalArgumentException ignored) {
			return new UUID(0, 0);
		}
	}

	public int threatScore() {
		return entityData.get(THREAT_SCORE);
	}

	public int defenseSlot() {
		return entityData.get(DEFENSE_SLOT);
	}

	public int recoveryLevel() {
		return entityData.get(RECOVERY_LEVEL);
	}

	public long recoverySinceTick() {
		return entityData.get(RECOVERY_SINCE);
	}

	public String cohortId() {
		return entityData.get(COHORT_ID);
	}

	public String cohortLeaderId() {
		return entityData.get(COHORT_LEADER);
	}

	public int cohortRank() {
		return entityData.get(COHORT_RANK);
	}

	public boolean hasRendezvous() {
		return entityData.get(RENDEZVOUS_POS) != Long.MIN_VALUE;
	}

	public BlockPos rendezvousPos() {
		return hasRendezvous() ? BlockPos.of(entityData.get(RENDEZVOUS_POS)) : BlockPos.ZERO;
	}

	public void assignDock(BlockPos pos) {
		entityData.set(DOCK_POS, pos.asLong());
		entityData.set(DOCKED, false);
		entityData.set(DOCK_STAGE, 0);
	}

	public void assignGroup(String group) {
		if (group != null && group.matches("[A-Z0-9_-]{1,24}")) entityData.set(GROUP, group);
	}

	public void clearDock() {
		entityData.set(DOCK_POS, Long.MIN_VALUE);
		entityData.set(DOCKED, false);
		entityData.set(DOCK_STAGE, 0);
		if (serviceReturn) {
			serviceReturn = false;
			serviceReason = DroneServicePolicy.Need.NONE;
			entityData.set(DATA_LINK_STATUS, "DOCK LINK LOST / HOLD");
		}
		if (mode() == DroneMode.DOCK) setMode(DroneMode.STANDBY);
	}

	public void setMode(DroneMode mode) {
		if (mode == DroneMode.DOCK && !hasDock()) return;
		if (mode == DroneMode.WAYPOINT && !entityData.get(HAS_WAYPOINT)) return;
		if (mode != DroneMode.WAYPOINT) clearMissionAssignment();
		if (mode != DroneMode.WAYPOINT) entityData.set(FORMATION_CATCH_UP, false);
		if (serviceReturn) {
			serviceResumeMode = mode.id();
			taskStack.queue(currentTaskSnapshot(mode));
			entityData.set(MODE, DroneMode.DOCK.id());
			entityData.set(DATA_LINK_STATUS, serviceReason.status() + " / NEXT " + mode.name());
			return;
		}
		if (combatState().controlsFlight()) combatResumeMode = mode.id();
		entityData.set(MODE, mode.id());
		if (mode == DroneMode.DOCK) {
			entityData.set(DOCK_STAGE, 0);
			dockApproachDirection = null;
		}
		if (mode != DroneMode.STANDBY) entityData.set(DOCKED, false);
		if (mode == DroneMode.STANDBY) getNavigation().stop();
	}

	public void assignFollowFormation(String missionId, int expected, int index, String leaderUnitId,
		BlockPos origin, long assignedTick) {
		clearFieldOperation();
		clearSecurityPatrol();
		clearPatrolRoute();
		setMode(DroneMode.FOLLOW);
		entityData.set(MISSION_ID, missionId);
		int safeExpected = DroneStatePolicy.missionExpected(expected);
		entityData.set(MISSION_EXPECTED, safeExpected);
		entityData.set(MISSION_INDEX, DroneStatePolicy.missionIndex(index, safeExpected));
		entityData.set(MISSION_STAGE, MISSION_ORBIT_ENTRY);
		entityData.set(MISSION_ORIGIN, origin.asLong());
		entityData.set(MISSION_ASSIGNED_TICK, assignedTick);
		entityData.set(ORBIT_ENTRY_TICK, assignedTick);
		entityData.set(ORBIT_PHASE_OFFSET, Float.NaN);
		entityData.set(COHORT_ID, missionId + "#" + leaderUnitId);
		entityData.set(COHORT_LEADER, leaderUnitId);
		entityData.set(COHORT_RANK, Math.max(0, index));
		if (serviceReturn) taskStack.queue(currentTaskSnapshot(DroneMode.FOLLOW));
	}

	public void assignWaypoint(BlockPos pos) {
		assignWaypoint(pos, getUUID().toString(), 1, 0, blockPosition().above(3), level().getGameTime());
	}

	public void assignWaypoint(BlockPos pos, String missionId, int expected, int index, BlockPos origin, long assignedTick) {
		clearFieldOperation();
		clearSecurityPatrol();
		clearPatrolRoute();
		entityData.set(WAYPOINT_POS, pos.asLong());
		entityData.set(TRACK_TARGET, "");
		assignMission(missionId, expected, index, origin, assignedTick);
	}

	public void assignTrackingTarget(LivingEntity target, String missionId, int expected, int index, BlockPos origin, long assignedTick) {
		clearFieldOperation();
		clearSecurityPatrol();
		clearPatrolRoute();
		entityData.set(WAYPOINT_POS, target.blockPosition().asLong());
		entityData.set(TRACK_TARGET, target.getUUID().toString());
		assignMission(missionId, expected, index, origin, assignedTick);
	}

	private void assignMission(String missionId, int expected, int index, BlockPos origin, long assignedTick) {
		resetNavigationPlan();
		entityData.set(HAS_WAYPOINT, true);
		entityData.set(MISSION_ID, missionId);
		int safeExpected = DroneStatePolicy.missionExpected(expected);
		entityData.set(MISSION_EXPECTED, safeExpected);
		entityData.set(MISSION_INDEX, DroneStatePolicy.missionIndex(index, safeExpected));
		// Dock launches join the moving rendezvous. A delayed aircraft must not hold
		// an ALL command in a static orbit above the player.
		entityData.set(MISSION_STAGE, MISSION_MOVING);
		entityData.set(MISSION_ORIGIN, origin.asLong());
		entityData.set(MISSION_ASSIGNED_TICK, assignedTick);
		entityData.set(ORBIT_ENTRY_TICK, -1L);
		entityData.set(ORBIT_PHASE_OFFSET, Float.NaN);
		clearCohort();
		setMode(DroneMode.WAYPOINT);
		refreshOperationTicket();
	}

	private void clearCohort() {
		entityData.set(COHORT_ID, "");
		entityData.set(COHORT_LEADER, "");
		entityData.set(COHORT_RANK, -1);
		entityData.set(RENDEZVOUS_POS, Long.MIN_VALUE);
	}

	private void clearMissionAssignment() {
		entityData.set(HAS_WAYPOINT, false);
		entityData.set(TRACK_TARGET, "");
		entityData.set(MISSION_ID, "");
		entityData.set(MISSION_EXPECTED, 1);
		entityData.set(MISSION_INDEX, 0);
		entityData.set(ORBIT_ENTRY_TICK, -1L);
		entityData.set(ORBIT_PHASE_OFFSET, Float.NaN);
		clearPatrolRoute();
		clearCohort();
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		setNoGravity(true);
		fallDistance = 0;
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId());
		if (owner == null && level.getServer().isSingleplayer()
			&& level.getServer().getPlayerList().getPlayers().size() == 1) {
			ServerPlayer solePlayer = level.getServer().getPlayerList().getPlayers().getFirst();
			if (isOwnedBy(solePlayer)) owner = solePlayer;
		}
		boolean ownerInDimension = owner != null && owner.level() == level;
		if (tickCount % 20 == 0 && (isPowerLost() || RemoteOperationPolicy.keepsChunkActive(ownerInDimension,
			isDocked(), serviceReturn, combatState().controlsFlight(), hasActiveFieldOperation(),
			hasSecurityPatrol(), hasPatrolRoute(), mode()))) {
			level.getChunkSource().addTicketWithRadius(MorrowgearDrone.DRONE_OPERATION_TICKET,
				chunkPosition(), RemoteOperationPolicy.ENTITY_TICKING_TICKET_RADIUS);
		}
		if (owner != null && isPowerLost() && (!powerLostBeaconReported
			|| tickCount % PowerLostBeaconLeasePolicy.HEARTBEAT_TICKS == 0)) {
			MorrowgearDrone.sendPowerLostBeacon(owner, this, true);
			powerLostBeaconReported = true;
		}
		if (isPowerLost()) {
			tickPowerLoss(level, owner);
			return;
		}
		if (owner == null || owner.level() != level) return;
		updateDroneLight(level);
		if (tickVerificationVisual(level)) return;
		if (hasDock() && level.getBlockEntity(dockPos()) instanceof DockBlockEntity dock) dock.adoptOwner(owner);
		if (!tickFlightPower(level, owner)) return;
		tickFlightSound(level);
		if (tickCount % 20 == 0) updateSolarService(level, owner);
		if (!isDocked() && hasDock()) {
			DroneServicePolicy.Need need = DroneServicePolicy.serviceNeed(getHealth() / getMaxHealth(),
				batteryPercent(), position().distanceTo(Vec3.atCenterOf(dockPos())));
			need = DroneServicePolicy.merge(need, DroneServicePolicy.subsystemNeed(
				propulsionCondition(), sensorCondition(), payloadCondition()));
			if (role() == DroneRole.SECURITY) {
				need = DroneServicePolicy.merge(need, CombatPolicy.weaponServiceNeed(securityLoadout(),
					weaponPowerPercent(), gunAmmo(), missiles(), laserHeat()));
			}
			if (!serviceReturn && SolarMissionPolicy.baseReturnRequired(need, solarServiceAssigned())) beginServiceReturn(need);
			else if (serviceReturn) {
				DroneServicePolicy.Need merged = DroneServicePolicy.merge(serviceReason, need);
				if (merged != serviceReason) {
					serviceReason = merged;
					entityData.set(DATA_LINK_STATUS, serviceReason.status());
				}
			}
		}

		if (mode() == DroneMode.DOCK && hasDock() && level.hasChunkAt(dockPos())
			&& !level.getBlockState(dockPos()).is(MorrowgearDrone.DOCK_CENTER)) {
			clearDock();
		}
		List<DroneEntity> formation = MorrowgearDrone.ownedDrones(level, owner, 512);
		if (role() == DroneRole.FIELD) tickFieldEmergencyRecovery(formation);
		if (role() == DroneRole.SALVAGE) tickSalvageMission(level, owner);
		if (!serviceReturn && !solarServiceAssigned()) {
			tickFieldOperation(level, owner, formation);
			tickCompletedFieldOperation(level, formation);
			// A completed field order still owns the aircraft until its autonomous
			// recovery has been dispatched. Do not let a configured cargo route or
			// engineer task overwrite RETURN/DOCK with a fresh waypoint in this tick.
			if (!hasFieldOperation()) tickCargoMission(level);
			if (!hasFieldOperation()) tickEngineerMission(level, owner, formation);
			tickSecurityPatrol(level);
			maintainFollowLeadership(level, owner, formation);
		}
		int index = Math.max(0, formation.indexOf(this));
		NavigationEnvironment.Snapshot navigationEnvironment = NavigationEnvironment.assess(level, owner);
		ThreatAssessment.Snapshot threat = ThreatAssessment.assess(level, owner);
		if (tickCount % 10 == 0) {
			entityData.set(THREAT_SCORE, threat.score());
			if (index == 0) publishOwnerThreat(level, owner, threat);
		}
		if (!serviceReturn) {
			updateMissionDataLink(level, owner, threat);
			publishTrackedTargetThreat(level, owner);
			publishPatrolThreat(level, owner);
			publishLocalSecurityThreat(level, owner);
			updateEmergencyInterception(level, owner, formation);
		}
		updateCombat(level, owner, formation);
		if (solarServiceAssigned() && (combatActive() || emergencyInterceptActive())) clearSolarService(false);
		logOperationalTransition(level);
		maintainMissionLeadership(level);
		entityData.set(DEFENSE_SLOT, -1);
		clearProtectionHold();
		defenseLaunchedFromDock = false;
		boolean engineerMovement = engineerState() == EngineerState.APPROACH;
		boolean fieldMovement = hasActiveFieldOperation()
			&& fieldOperationState() != FieldOperationState.BLOCKED;
		boolean fieldGuardMovement = hasActiveFieldOperation() && role() == DroneRole.SECURITY;
		boolean emergencyMovement = emergencyInterceptActive() || combatActive();
		boolean securityMovement = hasSecurityPatrol() || fieldGuardMovement || emergencyMovement;
		if (mode() == DroneMode.STANDBY
			&& !engineerMovement && !fieldMovement && !securityMovement
			&& !solarServiceAssigned()) {
			if (isDocked()) setDeltaMovement(Vec3.ZERO);
			else setDeltaMovement(FlightDynamics.brake(getDeltaMovement()));
			stabilizeHeading(owner);
			return;
		}

		if (mode() == DroneMode.WAYPOINT && !SolarMissionPolicy.freezeMissionProgress(solarServiceAssigned())) {
			updateMissionStage(level);
		}
		DockTarget dockTarget = mode() == DroneMode.DOCK && !solarServiceAssigned() ? dockTarget(level) : null;
		if (dockTarget != null && emergencyMovement) {
			dockTarget = null;
		}
		Vec3 target = dockTarget == null
			? targetPosition(level, owner, index, formation.size(), navigationEnvironment)
			: dockTarget.position();
		boolean solarServiceFlight = solarServiceAssigned();
		// Salvage interception is a precision hover, not a waypoint arrival orbit.
		// Keep driving directly above the disabled airframe even if the generic
		// mission-stage controller has already classified the waypoint as reached.
		DroneEntity salvageLoad = salvageTarget(level);
		boolean salvageIntercept = salvageState == SalvageState.INTERCEPT && salvageLoad != null;
		boolean salvageDeparture = salvageIntercept && hasDock()
			&& SalvageLaunchPolicy.requiresDepartureLane(position(), dockPos());
		if (salvageDeparture) target = SalvageLaunchPolicy.departureTarget(dockPos());
		else if (salvageIntercept) target = salvageLoad.position().add(0, 3.0, 0);
		boolean laserOrbit = combatState() == CombatState.LASER_CHARGE
			|| combatState() == CombatState.LASER_FIRE;
		boolean casFlight = combatWeapon() == CombatWeapon.AUTOCANNON
			&& (combatState() == CombatState.FLARE_ENTRY || combatState() == CombatState.GUN_RUN);
		boolean dedicatedCombatFlight = laserOrbit || casFlight;
		if (dedicatedCombatFlight) target = ensureClear(level, target);
		double remainingDistance = position().distanceTo(target);
		boolean salvageDirect = salvageDeparture || salvageIntercept
			&& DroneNavigator.corridorClear(level, this, position(), target);
		if (!dedicatedCombatFlight && !salvageDirect && !solarOrbitEstablished
			&& (dockTarget == null || !dockTarget.finalApproach()))
			target = navigationTarget(level, target, navigationEnvironment);
		else if (dedicatedCombatFlight) {
			routedFlight = false;
			strategicFlight = false;
		}
		if (salvageState == SalvageState.RETURN && salvageTarget(level) != null) {
			target = ensureSalvageLoadClearance(level, target, salvageTarget(level));
		}
		double distance = position().distanceTo(target);
		if (combatState().rejoining()
			&& CombatRejoinPolicy.complete(distance, combatElapsed(level))) {
			clearCombatState();
			entityData.set(DATA_LINK_STATUS, "MISSION REJOINED / " + groupId());
		}

		boolean docking = dockTarget != null;
		boolean finalApproach = docking && dockTarget.finalApproach();
		boolean tightFormation = mode() == DroneMode.WAYPOINT && missionStage() == MISSION_MOVING
			&& !cohortId().isBlank();
		Vec3 separation = docking || strategicFlight || dedicatedCombatFlight || salvageDirect
			? Vec3.ZERO : separationVector(level, tightFormation);
		double speedLimit = FlightDynamics.speedLimit(remainingDistance, docking, finalApproach);
		Vec3 convergenceLeaderVelocity = Vec3.ZERO;
		boolean catchUp = false;
		if (laserOrbit) speedLimit = 0.46;
		else if (solarServiceFlight) speedLimit = solarOrbitEstablished ? 0.42 : Math.max(speedLimit, 0.58);
		else if (casFlight) speedLimit = casBreakawayActive ? 1.14 : 0.92;
		else if (!docking && emergencyMovement) speedLimit = Math.max(speedLimit, 1.05);
		else if (!docking && mode() == DroneMode.WAYPOINT && !cohortId().isBlank()) {
			List<DroneEntity> mission = missionMembers(level);
			DroneEntity cohortLeader = mission.stream()
				.filter(member -> member.cohortId().equals(cohortId())
					&& member.unitId().equals(cohortLeaderId())).findFirst().orElse(null);
			boolean follower = cohortLeader != null && cohortLeader != this;
			catchUp = updateFormationCatchUp(missionStage() == MISSION_MOVING && follower, remainingDistance);
			int available = (int) mission.stream().filter(member -> member.cohortId().equals(cohortId())
				&& member.recoveryLevel() <= 0
				&& member.missionStage() != MISSION_ORBIT
				&& member.missionStage() != MISSION_ORBIT_ENTRY).count();
			int joined = (int) mission.stream().filter(member -> member.missionStage() == MISSION_MOVING
				&& member.recoveryLevel() <= 0 && member.cohortId().equals(cohortId())).count();
			if (missionStage() == MISSION_CONVERGING || catchUp) {
				convergenceLeaderVelocity = cohortLeader == null ? Vec3.ZERO : cohortLeader.getDeltaMovement();
				speedLimit = CohortControl.convergenceSpeedLimit(speedLimit, remainingDistance,
					convergenceLeaderVelocity.length());
			} else {
				speedLimit = CohortControl.assemblySpeedLimit(speedLimit, joined, available,
					position().distanceTo(missionDestination(level)));
			}
			if (cohortLeader == this && !catchUp) {
				speedLimit = Math.min(speedLimit, scoutRouteDecision.leaderSpeedCap());
			}
		}
		boolean converging = !docking && (missionStage() == MISSION_CONVERGING || catchUp);
		boolean predictivePatrol = !docking && !routedFlight && mode() == DroneMode.WAYPOINT
			&& patrolRouteSize() > 1 && missionStage() == MISSION_MOVING && !converging;
		if (laserOrbit) {
			LivingEntity laserTarget = combatTarget(level);
			Vec3 center = laserTarget == null ? target
				: laserTarget.position().add(0, laserTarget.getBbHeight() * 0.5, 0);
			LaserElement element = laserElement(level);
			Vec3 targetVelocity = laserTarget == null ? Vec3.ZERO : laserTarget.getDeltaMovement();
			Vec3 orbitNow = laserFormationSlot(level, center, element.index(), element.count(),
				level.getGameTime(), element.sharedCharge(), element.airspace());
			Vec3 orbitNext = laserFormationSlot(level, center, element.index(), element.count(),
				level.getGameTime() + 1, element.sharedCharge(), element.airspace());
			Vec3 orbitVelocity = orbitNext.subtract(orbitNow).add(targetVelocity);
			setDeltaMovement(FlightDynamics.steerLaserFormation(getDeltaMovement(), position(), target,
				orbitVelocity, speedLimit));
		} else if (casFlight) {
			if (casBreakawayActive) {
				setDeltaMovement(FlightDynamics.steerCombatBreakaway(getDeltaMovement(), position(),
					target, speedLimit));
			} else {
			LivingEntity casTarget = combatTarget(level);
			Vec3 center = casTarget == null ? target : autocannonManeuverCenter(level, casTarget);
			CasElement element = autocannonElement(level);
			Vec3 attackAxis = combatAttackAxis(owner, casTarget, center);
			Vec3 formationVelocity = CombatPolicy.casFormationVelocity(center, attackAxis,
				element.index(), element.count(), element.elapsed(), element.airspace());
			setDeltaMovement(FlightDynamics.steerMovingOrbit(getDeltaMovement(), position(), target,
				formationVelocity, speedLimit));
			}
		} else if (solarServiceFlight && solarOrbitEstablished) {
			SolarOrbitGuidance.Sample solar = solarOrbitSample(level);
			if (solar != null) setDeltaMovement(FlightDynamics.steerMovingOrbit(getDeltaMovement(),
				position(), solar.target(), solar.velocity(), speedLimit));
			else setDeltaMovement(FlightDynamics.brake(getDeltaMovement()));
		} else setDeltaMovement(converging && !routedFlight
				? FlightDynamics.steerConverging(getDeltaMovement(), position(), target, separation,
					speedLimit, convergenceLeaderVelocity)
			: routedFlight && (!docking || !finalApproach)
				? FlightDynamics.steerRoute(getDeltaMovement(), position(), target, separation, speedLimit, remainingDistance)
				: predictivePatrol
					? FlightDynamics.steerPredictiveRoute(getDeltaMovement(), position(), target,
						separation, speedLimit, remainingDistance)
				: FlightDynamics.steer(getDeltaMovement(), position(), target, separation, speedLimit, finalApproach));
		if (mode() == DroneMode.RETURN && remainingDistance < 1.0) setMode(DroneMode.FOLLOW);
		if (docking && !finalApproach && remainingDistance <= dockTarget.arrivalDistance()) {
			entityData.set(DOCK_STAGE, dockTarget.nextStage());
			setDeltaMovement(getDeltaMovement().scale(0.4));
			return;
		}
		if (docking && finalApproach && distance < 0.22) {
			setPos(target.x, target.y, target.z);
			setDeltaMovement(Vec3.ZERO);
			entityData.set(DOCKED, true);
			alignWithDock(dockTarget.dock());
			if (serviceReturn) entityData.set(MODE, DroneMode.STANDBY.id());
			else setMode(DroneMode.STANDBY);
		}
		if (!isDocked()) stabilizeHeading(owner);
	}

	private void logOperationalTransition(ServerLevel level) {
		DroneOperationalState current = operationalState();
		if (current == loggedOperationalState) return;
		MorrowgearDrone.LOGGER.info("Morrowgear state tick={} unit={} {} -> {} mode={} combat={} "
			+ "field={} cargo={} engineer={} battery={}% weapon={}% heat={} ammo={} missiles={} recovery={}",
			level.getGameTime(), unitId(), loggedOperationalState == null ? "SPAWN" : loggedOperationalState,
			current, mode(), combatState(), fieldOperationState(), cargoState(), engineerState(),
			batteryPercent(), weaponPowerPercent(), laserHeat(), gunAmmo(), missiles(), recoveryLevel());
		loggedOperationalState = current;
	}

	private boolean tickFlightPower(ServerLevel level, ServerPlayer owner) {
		if (isDocked()) {
			if (entityData.get(BATTERY) < batteryTier().capacity()
				&& level().getBlockEntity(dockPos()) instanceof DockBlockEntity dock
				&& dock.provideCharge(DockServicePolicy.FLIGHT_CHARGE_PER_TICK)) {
				entityData.set(BATTERY, Math.min(batteryTier().capacity(), entityData.get(BATTERY)
					+ DockServicePolicy.FLIGHT_CHARGE_PER_TICK));
			}
			if (powerLostBeaconReported && entityData.get(BATTERY) > 0) {
				MorrowgearDrone.sendPowerLostBeacon(owner, this, false);
				powerLostBeaconReported = false;
			}
			return true;
		}
		if (tickCount % 20 == 0 && entityData.get(BATTERY) > 0) {
			double speed = getDeltaMovement().length();
			boolean efficientReturnCruise = serviceReturn && mode() == DroneMode.DOCK && hasDock()
				&& position().distanceTo(Vec3.atCenterOf(dockPos())) > FlightDynamics.DOCK_CRUISE_HANDOFF_DISTANCE;
			int drain = FlightPowerPolicy.drain(speed, efficientReturnCruise,
				salvageState == SalvageState.RETURN)
				+ DroneSubsystemPolicy.powerPenalty(propulsionCondition());
			int previousPower = entityData.get(BATTERY);
			int nextPower = Math.max(0, previousPower - drain);
			if (previousPower > 0 && nextPower == 0) capturePowerLossTask();
			entityData.set(BATTERY, nextPower);
		}
		if (entityData.get(BATTERY) > 0) {
			if (powerLossTaskCaptured) resumeAfterPowerRestored(level, owner);
			if (powerLostBeaconReported) {
				MorrowgearDrone.sendPowerLostBeacon(owner, this, false);
				powerLostBeaconReported = false;
			}
			return true;
		}
		tickPowerLoss(level, owner);
		return false;
	}

	private void tickPowerLoss(ServerLevel level, ServerPlayer owner) {
		if (owner != null && (!powerLostBeaconReported
			|| tickCount % PowerLostBeaconLeasePolicy.HEARTBEAT_TICKS == 0)) {
			MorrowgearDrone.sendPowerLostBeacon(owner, this, true);
			powerLostBeaconReported = true;
		}
		clearCombatState();
		clearEmergencyInterception();
		if (role() == DroneRole.SALVAGE && salvageTargetId != null) cancelSalvageMission();
		clearDroneLight(level);
		getNavigation().stop();
		setTarget(null);
		entityData.set(MODE, DroneMode.STANDBY.id());
		if (SalvageMissionRegistry.isSuspended(getUUID())) {
			setNoGravity(true);
			return;
		}
		setNoGravity(false);
		setDeltaMovement(PowerLossPolicy.fallVelocity(getDeltaMovement(), onGround()));
	}

	private void capturePowerLossTask() {
		if (powerLossTaskCaptured) return;
		powerLossResumeMode = mode().id();
		taskStack.suspend(currentTaskSnapshot(mode(), true));
		powerLossTaskCaptured = true;
	}

	private void resumeAfterPowerRestored(ServerLevel level, ServerPlayer owner) {
		powerLossTaskCaptured = false;
		DroneTaskStack.Task task = taskStack.resume(candidate -> resolveTaskForResume(level, candidate))
			.orElse(new DroneTaskStack.Task(DroneTaskStack.Kind.IDLE,
				DroneMode.byId(powerLossResumeMode), SalvageState.IDLE, null, ""));
		// Power loss releases the suspended salvage reservation so another available
		// carrier can take over. If this aircraft is restored before that happens,
		// immediately reacquire the nearest eligible load instead of waiting in an
		// orphaned IDLE task after the old reservation fails validation.
		if (role() == DroneRole.SALVAGE && salvageTargetId == null && owner != null) {
			acquireSalvageTarget(level, owner);
			if (salvageTargetId != null) return;
		}
		if (task.kind() == DroneTaskStack.Kind.COMBAT && resumeCombatTask(level, task)) return;
		entityData.set(MODE, task.mode().id());
		entityData.set(DATA_LINK_STATUS, "POWER RESTORED / " + task.kind().name() + " RESUME");
	}

	@Override
	public void remove(RemovalReason reason) {
		if (!level().isClientSide() && salvageTargetId != null) cancelSalvageMission();
		if (!level().isClientSide() && reason.shouldDestroy() && (powerLostBeaconReported || isPowerLost())) {
			MorrowgearDrone.clearPowerLostBeacon(level().getServer(), ownerId(), unitId());
			powerLostBeaconReported = false;
		}
		super.remove(reason);
	}

	private void tickSalvageMission(ServerLevel level, ServerPlayer owner) {
		if (role() != DroneRole.SALVAGE) {
			cancelSalvageMission();
			return;
		}
		if (serviceReturn) return;
		DroneEntity target = salvageTarget(level);
		if (target != null && entityData.get(SALVAGE_TARGET) != target.getId()) {
			entityData.set(SALVAGE_TARGET, target.getId());
		}
		if (isDocked()) {
			if (target != null && salvageState == SalvageState.RETURN) {
				setSalvageState(SalvageState.DELIVER);
				deliverSalvage(level, target);
			} else if (target == null && tickCount % 40 == 0) {
				acquireSalvageTarget(level, owner);
			}
			return;
		}
		if (target == null && tickCount % 40 == 0) {
			acquireSalvageTarget(level, owner);
			return;
		}
		if (target == null) {
			cancelSalvageMission();
			return;
		}
		salvageStateTicks++;
		Vec3 hook = position().add(0, -SalvageTowPolicy.HOOK_DROP, 0);
		switch (salvageState) {
			case IDLE -> setSalvageState(SalvageState.INTERCEPT);
			case INTERCEPT -> {
				entityData.set(WAYPOINT_POS, target.blockPosition().above(3).asLong());
				if (SalvageInterceptPolicy.readyToHook(position(), target.position())) {
					setSalvageState(SalvageState.HOOK);
				}
			}
			case HOOK -> {
				setMode(DroneMode.STANDBY);
				setDeltaMovement(FlightDynamics.brake(getDeltaMovement()));
				SalvageMissionRegistry.setSuspended(target.getUUID(), getUUID(), true);
				target.setNoGravity(true);
				updateSuspendedLoad(target, hook, 0.16, false);
				if (salvageStateTicks >= 20) setSalvageState(SalvageState.HOIST);
			}
			case HOIST -> {
				SalvageMissionRegistry.setSuspended(target.getUUID(), getUUID(), true);
				target.setNoGravity(true);
				updateSuspendedLoad(target, hook, 0.28, false);
				if (target.position().add(0, target.getBbHeight(), 0).distanceTo(hook) <= 0.85
					|| salvageStateTicks >= 80) {
					setSalvageState(SalvageState.RETURN);
					if (hasDock()) setMode(DroneMode.DOCK);
				}
			}
			case RETURN -> {
				SalvageMissionRegistry.setSuspended(target.getUUID(), getUUID(), true);
				target.setNoGravity(true);
				updateSuspendedLoad(target, hook, 0.42, true);
				if (hasDock() && SalvageTowPolicy.readyForDockTransfer(
					position(), Vec3.atCenterOf(dockPos()))) {
					setSalvageState(SalvageState.DELIVER);
					deliverSalvage(level, target);
				}
			}
			case DELIVER -> deliverSalvage(level, target);
		}
	}

	private void acquireSalvageTarget(ServerLevel level, ServerPlayer owner) {
		if (!hasDock()) {
			entityData.set(DATA_LINK_STATUS, "SALVAGE BLOCKED / DOCK REQUIRED");
			return;
		}
		DroneEntity nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof DroneEntity candidate) || candidate == this
				|| !candidate.isOwnedBy(owner) || !candidate.isPowerLost()
				|| !PowerLossPolicy.salvageEligible(true, true,
					SalvageMissionRegistry.isReserved(candidate.getUUID()))) continue;
			double distance = distanceToSqr(candidate);
			if (distance >= nearestDistance) continue;
			nearestDistance = distance;
			nearest = candidate;
		}
		if (nearest == null || !SalvageMissionRegistry.reserve(nearest.getUUID(), getUUID())) return;
		salvageTargetId = nearest.getUUID();
		entityData.set(SALVAGE_TARGET, nearest.getId());
		setSalvageState(SalvageState.INTERCEPT);
		assignWaypoint(nearest.blockPosition().above(3), "salvage-" + nearest.unitId(), 1, 0,
			blockPosition(), level.getGameTime());
		entityData.set(DATA_LINK_STATUS, "SALVAGE INTERCEPT / " + nearest.unitId());
	}

	private DroneEntity salvageTarget(ServerLevel level) {
		if (salvageTargetId == null) return null;
		Entity entity = level.getEntity(salvageTargetId);
		if (!(entity instanceof DroneEntity target) || !target.isPowerLost()
			|| !SalvageMissionRegistry.heldBy(target.getUUID(), getUUID())) return null;
		return target;
	}

	private void updateSuspendedLoad(DroneEntity target, Vec3 hook, double response, boolean taut) {
		Vec3 attachment = target.position().add(0, target.getBbHeight(), 0);
		SalvageTowPolicy.Step step = SalvageTowPolicy.step(attachment, target.getDeltaMovement(),
			hook, getDeltaMovement(), salvageTowVelocity, response, taut);
		target.setDeltaMovement(step.velocity());
		salvageTowVelocity = getDeltaMovement();
	}

	private Vec3 ensureSalvageLoadClearance(ServerLevel level, Vec3 flightTarget, DroneEntity load) {
		int surface = Integer.MIN_VALUE;
		int radius = Mth.ceil(SalvageTowPolicy.ROPE_LENGTH + load.getBbWidth() * 0.5);
		for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
			surface = Math.max(surface, level.getHeight(
				net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				Mth.floor(flightTarget.x) + dx, Mth.floor(flightTarget.z) + dz));
		}
		double required = SalvageTowPolicy.requiredFlightY(surface, load.getBbHeight());
		return flightTarget.y >= required ? flightTarget : new Vec3(flightTarget.x, required, flightTarget.z);
	}

	private void deliverSalvage(ServerLevel level, DroneEntity target) {
		ItemStack recovered = target.createStoredUnit();
		DockBlockEntity recoveryDock = hasDock() && level.getBlockEntity(dockPos()) instanceof DockBlockEntity dock
			? dock : null;
		if (recoveryDock == null || !recoveryDock.hasRecoveryCapacity()) {
			entityData.set(DATA_LINK_STATUS, "SALVAGE HOLD / RECOVERY BUFFER FULL");
			return;
		}
		ServerPlayer targetOwner = level.getServer().getPlayerList().getPlayer(target.ownerId());
		if (targetOwner != null) MorrowgearDrone.sendPowerLostBeacon(targetOwner, target, false);
		target.clearDroneLight(level);
		SalvageMissionRegistry.release(target.getUUID(), getUUID());
		target.discard();
		recoveryDock.placeRecoveryOutput(recovered);
		salvageTargetId = null;
		entityData.set(SALVAGE_TARGET, -1);
		setSalvageState(SalvageState.IDLE);
		clearMissionAssignment();
		setMode(hasDock() ? DroneMode.DOCK : DroneMode.STANDBY);
		entityData.set(DATA_LINK_STATUS, "SALVAGE COMPLETE / DOCK OUTPUT");
		beginQueuedService();
	}

	private void cancelSalvageMission() {
		if (salvageTargetId != null) SalvageMissionRegistry.release(salvageTargetId, getUUID());
		salvageTargetId = null;
		entityData.set(SALVAGE_TARGET, -1);
		salvageTowVelocity = Vec3.ZERO;
		setSalvageState(SalvageState.IDLE);
		beginQueuedService();
	}

	private void setSalvageState(SalvageState state) {
		if (salvageState == state) return;
		salvageState = state;
		salvageStateTicks = 0;
	}

	private void tickFlightSound(ServerLevel level) {
		if (isDocked() || Math.floorMod(tickCount + getId(), 30) != 0) return;
		double speed = getDeltaMovement().length();
		boolean cruise = speed >= 0.32;
		float volume = (float)Mth.clamp(0.42 + speed * 0.16, 0.42, 0.65);
		float pitch = (float)Mth.clamp(0.82 + speed * 0.30, 0.82, 1.18);
		level.playSound(null, getX(), getY(), getZ(), cruise
			? MorrowgearDrone.FLIGHT_CRUISE_SOUND : MorrowgearDrone.FLIGHT_IDLE_SOUND,
			SoundSource.PLAYERS, volume, pitch);
	}

	private void tickFieldOperation(ServerLevel level, ServerPlayer owner, List<DroneEntity> formation) {
		if (!hasActiveFieldOperation()) return;
		FieldOperationRegistry.Operation operation = FieldOperationRegistry.acquire(
			level.dimension().toString(), owner.getUUID(), fieldOrderId(), fieldOperationType(),
			fieldAnchor(), fieldRadius(), level.getGameTime());
		List<DroneEntity> members = fieldMissionMembers(formation);
		boolean linkedScout = members.stream().anyMatch(member -> member.role() == DroneRole.SCOUT);
		if (role() == DroneRole.FIELD) tickFieldSupport(level, operation, members);
		else if (role() == DroneRole.SCOUT) tickFieldScout(level, operation, members);
		else if (role() == DroneRole.ENGINEER) tickFieldEngineer(level, owner, operation, linkedScout, members);
		else if (role() == DroneRole.CARGO) tickFieldCargo(level, operation, members);
		else if (role() == DroneRole.SECURITY) tickFieldGuard(level, members);
		else entityData.set(FIELD_STATE, FieldOperationState.BLOCKED.id());
		FieldOperationRegistry.Snapshot snapshot = operation.snapshot(level.getGameTime());
		entityData.set(FIELD_PROGRESS, role() == DroneRole.ENGINEER && !linkedScout && fieldLocalSurveyed
			? 100 : snapshot.scanProgress());
		entityData.set(FIELD_FOUND, snapshot.found());
		if (role() != DroneRole.CARGO) entityData.set(FIELD_STOCK, snapshot.stockItems());
	}

	private void tickFieldSupport(ServerLevel level, FieldOperationRegistry.Operation operation,
		List<DroneEntity> members) {
		boolean scoutPresent = members.stream().anyMatch(member -> member.role() == DroneRole.SCOUT);
		boolean engineerPresent = members.stream().anyMatch(member -> member.role() == DroneRole.ENGINEER);
		boolean engineersComplete = engineeringWorkComplete(members);
		FieldOperationRegistry.Snapshot snapshot = operation.snapshot(level.getGameTime());
		if (!snapshot.scanComplete() && !scoutPresent && !engineerPresent && tickCount % 4 == 0) {
			scanFieldTargets(level, operation, 64);
			snapshot = operation.snapshot(level.getGameTime());
		}
		boolean surveyReady = FieldSupportPolicy.surveyReady(snapshot.scanComplete(), scoutPresent,
			engineerPresent, engineersComplete);
		boolean cargoComplete = roleComplete(members, DroneRole.CARGO);
		entityData.set(FIELD_STATE, FieldSupportPolicy.state(surveyReady,
			engineersComplete, cargoComplete).id());
		entityData.set(DATA_LINK_STATUS, surveyReady
			? (snapshot.scanComplete() ? "FIELD RELAY / MISSION SUPPORT" : "FIELD RELAY / LOCAL COMPLETE")
			: "FIELD RELAY / LOCAL SURVEY");
		if (tickCount % 20 != 0) return;
		DroneEntity receiver = members.stream().filter(member -> member != this && !member.isDocked()
			&& !member.isPowerLost())
			.min(Comparator.comparingInt(DroneEntity::batteryPercent)).orElse(null);
		if (receiver == null || !FieldSupportPolicy.mayTransfer(batteryPercent(), receiver.batteryPercent())) return;
		int transfer = Math.min(10, entityData.get(BATTERY));
		entityData.set(BATTERY, entityData.get(BATTERY) - transfer);
		receiver.entityData.set(BATTERY, receiver.batteryTier().clamp(receiver.entityData.get(BATTERY) + transfer));
		entityData.set(DATA_LINK_STATUS, "FIELD RELAY / EMERGENCY POWER " + receiver.unitId());
	}

	private void tickFieldEmergencyRecovery(List<DroneEntity> formation) {
		if (isDocked() || tickCount % 20 != 0
			|| batteryPercent() <= PowerLossPolicy.FIELD_TRANSFER_MINIMUM_PERCENT) return;
		DroneEntity receiver = formation.stream()
			.filter(drone -> drone != this && !drone.isPowerLost() && !drone.isDocked()
				&& drone.batteryPercent() <= PowerLossPolicy.FIELD_TRANSFER_RECEIVER_MAXIMUM_PERCENT)
			.filter(drone -> distanceToSqr(drone) <= 48.0 * 48.0)
			.min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
		if (receiver == null
			|| !PowerLossPolicy.fieldTransferAllowed(batteryPercent(), receiver.batteryPercent())) return;
		int transfer = Math.min(10, entityData.get(BATTERY));
		entityData.set(BATTERY, entityData.get(BATTERY) - transfer);
		receiver.entityData.set(BATTERY,
			receiver.batteryTier().clamp(receiver.entityData.get(BATTERY) + transfer));
		entityData.set(DATA_LINK_STATUS, "FIELD RECOVERY LINK / " + receiver.unitId());
		receiver.entityData.set(DATA_LINK_STATUS, "EMERGENCY POWER RECEIVED / " + unitId());
	}

	private List<DroneEntity> fieldMissionMembers(List<DroneEntity> formation) {
		return formation.stream().filter(member -> !member.serviceReturn && member.hasFieldOperation()
			&& member.fieldOrderId().equals(fieldOrderId())
			&& member.fieldOperationType() == fieldOperationType()
			&& member.fieldAnchor().equals(fieldAnchor()))
			.sorted(Comparator.comparing(DroneEntity::unitId)).toList();
	}

	private void tickFieldScout(ServerLevel level, FieldOperationRegistry.Operation operation,
		List<DroneEntity> members) {
		double nominalSurveyRange = Math.max(14.0, Math.min(22.0, fieldRadius() * 0.58 + 4.0));
		double surveyRange = nominalSurveyRange * DroneSubsystemPolicy.sensorScale(sensorCondition());
		if (position().distanceToSqr(Vec3.atCenterOf(fieldAnchor()).add(0, 6, 0)) > surveyRange * surveyRange) {
			entityData.set(FIELD_STATE, FieldOperationState.TRANSIT.id());
			return;
		}
		FieldOperationRegistry.Snapshot snapshot = operation.snapshot(level.getGameTime());
		if (!snapshot.scanComplete()) {
			entityData.set(FIELD_STATE, FieldOperationState.SCOUT_SURVEY.id());
			scanFieldTargets(level, operation, 384);
			return;
		}
		boolean engineersComplete = engineeringWorkComplete(members);
		boolean engineerPresent = members.stream().anyMatch(member -> member.role() == DroneRole.ENGINEER);
		boolean cargoPresent = members.stream().anyMatch(member -> member.role() == DroneRole.CARGO);
		boolean cargoComplete = roleComplete(members, DroneRole.CARGO);
		entityData.set(FIELD_STATE, FieldMissionStatusPolicy.scout(true, engineerPresent,
			engineersComplete, cargoPresent, cargoComplete).id());
	}

	private void tickFieldEngineer(ServerLevel level, ServerPlayer owner,
		FieldOperationRegistry.Operation operation, boolean linkedScout, List<DroneEntity> members) {
		FieldOperationRegistry.Snapshot snapshot = operation.snapshot(level.getGameTime());
		if (linkedScout && !snapshot.scanComplete()) {
			if (position().distanceToSqr(Vec3.atCenterOf(fieldAnchor()).add(0, 3, 0)) > 12.0 * 12.0) {
				entityData.set(FIELD_STATE, FieldOperationState.TRANSIT.id());
				return;
			}
			entityData.set(FIELD_STATE, FieldOperationState.ENGINEER_WAITING_INTEL.id());
			return;
		}
		if (!linkedScout && !fieldLocalSurveyed) {
			entityData.set(FIELD_STATE, FieldOperationState.ENGINEER_LOCAL_SURVEY.id());
			surveyLocalField(level, operation);
			fieldLocalSurveyed = true;
		}
		if (fieldOperationType() == FieldOperationType.FORESTRY
			&& tickFieldReplant(level, owner, operation)) return;
		if (fieldWorkTarget == null) {
			fieldWorkTarget = operation.claimTarget(level.getGameTime());
			fieldWorkApproach = fieldWorkTarget == null ? null : selectFieldWorkPosition(level, fieldWorkTarget);
		}
		if (fieldWorkTarget != null) {
			if (!level.hasChunk(fieldWorkTarget.getX() >> 4, fieldWorkTarget.getZ() >> 4)) {
				entityData.set(FIELD_STATE, FieldOperationState.WAITING_DATA.id());
				return;
			}
			BlockState state = level.getBlockState(fieldWorkTarget);
			if (state.isAir() || state.getDestroySpeed(level, fieldWorkTarget) < 0
				|| level.getBlockEntity(fieldWorkTarget) != null) {
				operation.completeTarget(fieldWorkTarget, level.getGameTime());
				fieldWorkTarget = null;
				fieldWorkApproach = null;
				return;
			}
			Vec3 workPosition = fieldWorkApproach == null
				? selectFieldWorkPosition(level, fieldWorkTarget) : fieldWorkApproach;
			if (position().distanceToSqr(workPosition) > 4.0 * 4.0) {
				entityData.set(FIELD_STATE, FieldOperationState.TRANSIT.id());
				return;
			}
			entityData.set(FIELD_STATE, FieldOperationState.WORKING.id());
			int workInterval = (int)Math.ceil(4.0 / DroneSubsystemPolicy.payloadScale(payloadCondition()));
			if (tickCount % workInterval != 0) return;
			ItemStack tool = fieldToolFor(state);
			BlockEntity blockEntity = level.getBlockEntity(fieldWorkTarget);
			List<ItemStack> drops = Block.getDrops(state, level, fieldWorkTarget, blockEntity, this, tool);
			if (fieldOperationType() == FieldOperationType.FORESTRY && state.is(BlockTags.LOGS))
				promoteConnectedTree(level, fieldWorkTarget, operation);
			if (fieldOperationType() == FieldOperationType.FORESTRY && state.is(BlockTags.LOGS)
				&& !level.getBlockState(fieldWorkTarget.below()).is(BlockTags.LOGS)) {
				ItemStack sapling = saplingFor(state);
				if (!sapling.isEmpty()) operation.addReplant(fieldWorkTarget, sapling, level.getGameTime());
			}
			level.setBlock(fieldWorkTarget, Blocks.AIR.defaultBlockState(), 3);
			for (ItemStack drop : drops) if (!drop.isEmpty()) spawnFieldDrop(level, operation, fieldWorkTarget, drop);
			operation.completeTarget(fieldWorkTarget, level.getGameTime());
			fieldWorkTarget = null;
			fieldWorkApproach = null;
			return;
		}
		if (linkedScout && !operation.snapshot(level.getGameTime()).scanComplete())
			entityData.set(FIELD_STATE, FieldOperationState.ENGINEER_WAITING_INTEL.id());
		else {
			boolean cargoPending = members.stream().anyMatch(member -> member.role() == DroneRole.CARGO)
				&& !roleComplete(members, DroneRole.CARGO);
			entityData.set(FIELD_STATE, cargoPending
				? FieldOperationState.ENGINEER_RECOVERY_OVERWATCH.id()
				: FieldOperationState.COMPLETE.id());
		}
	}

	private boolean tickFieldReplant(ServerLevel level, ServerPlayer owner,
		FieldOperationRegistry.Operation operation) {
		if (fieldReplantTarget == null) fieldReplantTarget = operation.claimReplant(level.getGameTime());
		if (fieldReplantTarget == null) return false;
		BlockPos pos = fieldReplantTarget.pos();
		if (position().distanceToSqr(Vec3.atCenterOf(pos).add(0, 2, 0)) > 3.4 * 3.4) {
			entityData.set(FIELD_STATE, FieldOperationState.TRANSIT.id());
			return true;
		}
		entityData.set(FIELD_STATE, FieldOperationState.PLANTING.id());
		if (level.getBlockState(pos).isAir()
			&& consumeFieldSapling(level, owner, operation, fieldReplantTarget.sapling())) {
			BlockState saplingState = Block.byItem(fieldReplantTarget.sapling().getItem()).defaultBlockState();
			if (saplingState.canSurvive(level, pos)) level.setBlock(pos, saplingState, 3);
			else Block.popResource(level, pos, fieldReplantTarget.sapling().copy());
		} else if (!owner.isCreative()) {
			entityData.set(FIELD_STATE, FieldOperationState.BLOCKED.id());
			return true;
		}
		operation.completeReplant(level.getGameTime());
		fieldReplantTarget = null;
		return true;
	}

	private void promoteConnectedTree(ServerLevel level, BlockPos root,
		FieldOperationRegistry.Operation operation) {
		ArrayDeque<BlockPos> pending = new ArrayDeque<>();
		HashSet<Long> visited = new HashSet<>();
		pending.add(root.immutable());
		while (!pending.isEmpty() && visited.size() < 384) {
			BlockPos current = pending.removeFirst();
			if (!visited.add(current.asLong())) continue;
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) continue;
					BlockPos neighbor = current.offset(dx, dy, dz);
					BlockState neighborState = level.getBlockState(neighbor);
					if (neighborState.is(BlockTags.LOGS)) {
						operation.promoteTarget(neighbor, -20, level.getGameTime());
						if (!visited.contains(neighbor.asLong())) pending.addLast(neighbor.immutable());
					} else if (neighborState.is(BlockTags.LEAVES)) {
						operation.promoteTarget(neighbor, -10, level.getGameTime());
					}
				}
			}
		}
	}

	private boolean scanFieldTargets(ServerLevel level, FieldOperationRegistry.Operation operation, int budget) {
		for (int i = 0; i < budget; i++) {
			BlockPos pos = operation.nextScanPos(level.getGameTime());
			if (pos == null) return true;
			if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) continue;
			if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
				operation.retryScanPos(pos, level.getGameTime());
				continue;
			}
			BlockState state = level.getBlockState(pos);
			operation.recordSurveySample(state.isAir(), !state.getFluidState().isEmpty(), level.getGameTime());
			if (fieldOperationType() == FieldOperationType.ORE && isOre(state)) operation.addTarget(pos);
			else if (fieldOperationType() == FieldOperationType.FORESTRY && state.is(BlockTags.LOGS))
				operation.addTarget(pos, 0);
			else if (fieldOperationType() == FieldOperationType.FORESTRY && state.is(BlockTags.LEAVES))
				operation.addTarget(pos, 1);
			else if (fieldOperationType() == FieldOperationType.EXCAVATE && !state.isAir()
				&& state.getDestroySpeed(level, pos) >= 0 && level.getBlockEntity(pos) == null)
				operation.addTarget(pos, fieldAnchor().getY() - pos.getY());
		}
		return operation.snapshot(level.getGameTime()).scanComplete();
	}

	private void surveyLocalField(ServerLevel level, FieldOperationRegistry.Operation operation) {
		int horizontal = Math.min(fieldOperationType() == FieldOperationType.EXCAVATE ? 3 : 4, fieldRadius());
		BlockPos anchor = fieldAnchor();
		int minOffsetY = fieldOperationType() == FieldOperationType.EXCAVATE ? -5 : -16;
		int maxOffsetY = fieldOperationType() == FieldOperationType.FORESTRY ? 32 : 16;
		if (fieldOperationType() == FieldOperationType.EXCAVATE) maxOffsetY = 0;
		for (int x = -horizontal; x <= horizontal; x++) {
			for (int z = -horizontal; z <= horizontal; z++) {
				for (int y = minOffsetY; y <= maxOffsetY; y++) {
					BlockPos pos = anchor.offset(x, y, z);
					if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()
						|| !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
					BlockState state = level.getBlockState(pos);
					if (fieldOperationType() == FieldOperationType.ORE && isOre(state))
						operation.addTarget(pos, -4);
					else if (fieldOperationType() == FieldOperationType.FORESTRY && state.is(BlockTags.LOGS))
						operation.addTarget(pos, -4);
					else if (fieldOperationType() == FieldOperationType.FORESTRY && state.is(BlockTags.LEAVES))
						operation.addTarget(pos, -2);
					else if (fieldOperationType() == FieldOperationType.EXCAVATE && !state.isAir()
						&& state.getDestroySpeed(level, pos) >= 0 && level.getBlockEntity(pos) == null)
						operation.addTarget(pos, anchor.getY() - pos.getY());
				}
			}
		}
	}

	private void tickFieldCargo(ServerLevel level, FieldOperationRegistry.Operation operation,
		List<DroneEntity> members) {
		if (tickCount % 10 != 0) return;
		FieldOperationRegistry.Snapshot snapshot = operation.snapshot(level.getGameTime());
		List<ItemEntity> groundItems = fieldGroundItems(level, operation);
		entityData.set(FIELD_STOCK, groundItems.stream().mapToInt(item -> item.getItem().getCount()).sum());
		boolean engineersComplete = engineeringWorkComplete(members);
		boolean workSettled = engineersComplete && snapshot.workComplete();
		if (!workSettled || !groundItems.isEmpty() || !cargo.isEmpty()) fieldCargoQuietSinceTick = -1L;
		else if (fieldCargoQuietSinceTick < 0L) fieldCargoQuietSinceTick = level.getGameTime();
		BlockPos deliveryTarget = resolveFieldCargoTarget(level);
		if (deliveryTarget == null) {
			entityData.set(FIELD_STATE, FieldOperationState.BLOCKED.id());
			return;
		}
		if (!cargo.isEmpty()) {
			if (fieldCargoLoadedSinceTick < 0L) fieldCargoLoadedSinceTick = level.getGameTime();
			long loadedTicks = level.getGameTime() - fieldCargoLoadedSinceTick;
			boolean deliveryDue = cargoItemCount() >= 64 || cargo.size() >= 9
				|| loadedTicks >= 100L || workSettled;
			if (deliveryDue) {
				if (!(level.getBlockEntity(deliveryTarget) instanceof Container container)) {
					entityData.set(FIELD_STATE, FieldOperationState.BLOCKED.id());
					return;
				}
				if (position().distanceToSqr(Vec3.atCenterOf(deliveryTarget).add(0, 2, 0)) > 3.0 * 3.0) {
					entityData.set(FIELD_STATE, FieldOperationState.DELIVERING.id());
					return;
				}
				unloadCargo(container);
				if (cargo.isEmpty()) fieldCargoLoadedSinceTick = -1L;
				entityData.set(FIELD_STATE, cargo.isEmpty()
					? FieldOperationState.COLLECTING.id() : FieldOperationState.BLOCKED.id());
				return;
			}
		}
		if (cargo.size() < 9 && !groundItems.isEmpty()) {
			ItemEntity nearest = groundItems.stream().min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
			fieldPickupTarget = nearest == null ? fieldAnchor() : nearest.blockPosition();
			if (nearest == null || distanceToSqr(nearest) > 4.5 * 4.5) {
				entityData.set(FIELD_STATE, FieldOperationState.TRANSIT.id());
				return;
			}
			boolean wasEmpty = cargo.isEmpty();
			for (ItemEntity item : groundItems) {
				if (cargo.size() >= 9 || distanceToSqr(item) > 5.5 * 5.5) continue;
				ItemStack remaining = storeFieldCargo(item.getItem());
				if (remaining.isEmpty()) item.discard(); else item.setItem(remaining);
			}
			updateCargoCount();
			if (wasEmpty && !cargo.isEmpty()) fieldCargoLoadedSinceTick = level.getGameTime();
			entityData.set(FIELD_STATE, FieldOperationState.COLLECTING.id());
			return;
		}
		if (!cargo.isEmpty()) {
			fieldPickupTarget = null;
			entityData.set(FIELD_STATE, FieldOperationState.CARGO_HOLD.id());
			return;
		}
		fieldPickupTarget = null;
		long quietTicks = fieldCargoQuietSinceTick < 0L ? 0L : level.getGameTime() - fieldCargoQuietSinceTick;
		entityData.set(FIELD_STATE, FieldMissionStatusPolicy.cargo(engineersComplete,
			snapshot.workComplete(), !groundItems.isEmpty(), !cargo.isEmpty(), quietTicks).id());
	}

	private BlockPos resolveFieldCargoTarget(ServerLevel level) {
		if (hasCargoTarget() && level.getBlockEntity(cargoTarget()) instanceof Container) return cargoTarget();
		BlockPos anchor = fieldAnchor();
		int chunkRadius = 2;
		BlockPos best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		int centerChunkX = anchor.getX() >> 4;
		int centerChunkZ = anchor.getZ() >> 4;
		for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
			for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
				if (!level.hasChunk(chunkX, chunkZ)) continue;
				for (BlockEntity blockEntity : level.getChunk(chunkX, chunkZ).getBlockEntities().values()) {
					if (!(blockEntity instanceof Container container)
						|| hasCargoSource() && blockEntity.getBlockPos().equals(cargoSource())
						|| !containerCanAccept(container)) continue;
					double distance = blockEntity.getBlockPos().distSqr(anchor);
					if (distance >= bestDistance) continue;
					best = blockEntity.getBlockPos().immutable();
					bestDistance = distance;
				}
			}
		}
		if (best != null) entityData.set(CARGO_TARGET, best.asLong());
		return best;
	}

	private boolean containerCanAccept(Container container) {
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack present = container.getItem(slot);
			if (present.isEmpty()) return true;
			for (ItemStack carried : cargo) {
				if (ItemStack.isSameItemSameComponents(present, carried)
					&& present.getCount() < container.getMaxStackSize(present)) return true;
			}
		}
		return false;
	}

	private void tickCompletedFieldOperation(ServerLevel level, List<DroneEntity> formation) {
		if (fieldCompletionDispatched || !hasFieldOperation()
			|| fieldOperationState() != FieldOperationState.COMPLETE) return;
		dispatchCompletedFieldAction();
	}

	private void dispatchCompletedFieldAction() {
		if (fieldCompletionDispatched || fieldOperationState() != FieldOperationState.COMPLETE) return;
		clearMissionAssignment();
		fieldCompletionDispatched = true;
		setMode(hasDock() ? DroneMode.DOCK : DroneMode.RETURN);
	}

	private Vec3 selectFieldWorkPosition(ServerLevel level, BlockPos target) {
		Vec3 center = Vec3.atCenterOf(target);
		Vec3 best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		AABB body = getBoundingBox();
		for (int y = 1; y <= 4; y++) {
			for (int x = -3; x <= 3; x++) {
				for (int z = -3; z <= 3; z++) {
					if (x * x + z * z > 10) continue;
					Vec3 candidate = center.add(x, y + 0.35, z);
					AABB moved = body.move(candidate.subtract(position())).deflate(0.06);
					if (!level.noCollision(this, moved)) continue;
					double score = candidate.distanceToSqr(position()) * 0.2
						+ candidate.distanceToSqr(center);
					if (score >= bestScore) continue;
					best = candidate;
					bestScore = score;
				}
			}
		}
		return best == null ? center.add(0, 3.35, 0) : best;
	}

	private void tickFieldGuard(ServerLevel level, List<DroneEntity> members) {
		boolean engineersComplete = engineeringWorkComplete(members);
		boolean engineerPresent = members.stream().anyMatch(member -> member.role() == DroneRole.ENGINEER);
		boolean cargoPresent = members.stream().anyMatch(member -> member.role() == DroneRole.CARGO);
		boolean cargoComplete = roleComplete(members, DroneRole.CARGO);
		entityData.set(FIELD_STATE, FieldMissionStatusPolicy.guard(engineerPresent,
			engineersComplete, cargoPresent, cargoComplete).id());
		if (tickCount % 5 != 0) return;
		Vec3 center = fieldEscortCenter(members);
		List<LivingEntity> contacts = level.getEntitiesOfClass(LivingEntity.class,
			new AABB(center, center).inflate(Math.max(8, fieldRadius()), 12.0, Math.max(8, fieldRadius())),
			entity -> entity instanceof Enemy && entity.isAlive());
		LivingEntity primary = contacts.stream()
			.min(Comparator.comparingDouble(entity -> entity.position().distanceToSqr(center))).orElse(null);
		entityData.set(SECURITY_CONTACT, primary == null ? -1 : primary.getId());
		entityData.set(SECURITY_CONTACTS, contacts.size());
	}

	private static boolean roleComplete(List<DroneEntity> members, DroneRole role) {
		List<DroneEntity> assigned = members.stream().filter(member -> member.role() == role).toList();
		return assigned.isEmpty() || assigned.stream()
			.allMatch(member -> member.fieldOperationState() == FieldOperationState.COMPLETE);
	}

	private static boolean engineeringWorkComplete(List<DroneEntity> members) {
		List<DroneEntity> engineers = members.stream()
			.filter(member -> member.role() == DroneRole.ENGINEER).toList();
		return engineers.isEmpty() || engineers.stream().allMatch(member ->
			member.fieldOperationState() == FieldOperationState.COMPLETE
				|| member.fieldOperationState() == FieldOperationState.ENGINEER_RECOVERY_OVERWATCH);
	}

	private List<ItemEntity> fieldGroundItems(ServerLevel level, FieldOperationRegistry.Operation operation) {
		double radius = Math.max(4, fieldRadius() + 2);
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(fieldAnchor()).inflate(radius, 40, radius),
			item -> item.isAlive() && !item.getItem().isEmpty()
				&& operation.ownsDrop(item.getUUID(), level.getGameTime()));
	}

	private void spawnFieldDrop(ServerLevel level, FieldOperationRegistry.Operation operation,
		BlockPos pos, ItemStack stack) {
		double x = pos.getX() + 0.35 + level.getRandom().nextDouble() * 0.3;
		double y = pos.getY() + 0.35 + level.getRandom().nextDouble() * 0.25;
		double z = pos.getZ() + 0.35 + level.getRandom().nextDouble() * 0.3;
		ItemEntity item = new ItemEntity(level, x, y, z, stack.copy());
		item.setDefaultPickUpDelay();
		item.setDeltaMovement((level.getRandom().nextDouble() - 0.5) * 0.08, 0.12,
			(level.getRandom().nextDouble() - 0.5) * 0.08);
		operation.registerDrop(item.getUUID(), level.getGameTime());
		level.addFreshEntity(item);
	}

	private ItemStack storeFieldCargo(ItemStack incoming) {
		ItemStack remaining = incoming.copy();
		for (ItemStack stored : cargo) {
			if (!ItemStack.isSameItemSameComponents(stored, remaining)) continue;
			int moved = Math.min(remaining.getCount(), stored.getMaxStackSize() - stored.getCount());
			stored.grow(moved);
			remaining.shrink(moved);
			if (remaining.isEmpty()) return ItemStack.EMPTY;
		}
		if (cargo.size() < 9) {
			cargo.add(remaining.copy());
			return ItemStack.EMPTY;
		}
		return remaining;
	}

	private boolean consumeFieldSapling(ServerLevel level, ServerPlayer owner,
		FieldOperationRegistry.Operation operation, ItemStack sapling) {
		if (owner.isCreative()) return true;
		for (ItemEntity item : fieldGroundItems(level, operation)) {
			if (!ItemStack.isSameItemSameComponents(item.getItem(), sapling)) continue;
			ItemStack stack = item.getItem();
			stack.shrink(1);
			if (stack.isEmpty()) item.discard(); else item.setItem(stack);
			return true;
		}
		for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
			ItemStack stack = owner.getInventory().getItem(slot);
			if (!ItemStack.isSameItemSameComponents(stack, sapling)) continue;
			stack.shrink(1);
			return true;
		}
		return false;
	}

	private static boolean isOre(BlockState state) {
		return state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)
			|| state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)
			|| state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE)
			|| state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE)
			|| state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)
			|| state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)
			|| state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
			|| state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
			|| state.is(Blocks.NETHER_GOLD_ORE) || state.is(Blocks.NETHER_QUARTZ_ORE)
			|| state.is(Blocks.ANCIENT_DEBRIS);
	}

	private static ItemStack fieldToolFor(BlockState state) {
		if (state.is(BlockTags.LEAVES)) return ItemStack.EMPTY;
		if (state.is(BlockTags.LOGS)) return new ItemStack(Items.DIAMOND_AXE);
		return new ItemStack(Items.DIAMOND_PICKAXE);
	}

	private static ItemStack saplingFor(BlockState state) {
		if (state.is(Blocks.SPRUCE_LOG)) return new ItemStack(Blocks.SPRUCE_SAPLING);
		if (state.is(Blocks.BIRCH_LOG)) return new ItemStack(Blocks.BIRCH_SAPLING);
		if (state.is(Blocks.JUNGLE_LOG)) return new ItemStack(Blocks.JUNGLE_SAPLING);
		if (state.is(Blocks.ACACIA_LOG)) return new ItemStack(Blocks.ACACIA_SAPLING);
		if (state.is(Blocks.CHERRY_LOG)) return new ItemStack(Blocks.CHERRY_SAPLING);
		if (state.is(Blocks.DARK_OAK_LOG)) return new ItemStack(Blocks.DARK_OAK_SAPLING);
		if (state.is(Blocks.PALE_OAK_LOG)) return new ItemStack(Blocks.PALE_OAK_SAPLING);
		if (state.is(Blocks.MANGROVE_LOG)) return new ItemStack(Blocks.MANGROVE_PROPAGULE);
		if (state.is(Blocks.OAK_LOG)) return new ItemStack(Blocks.OAK_SAPLING);
		return ItemStack.EMPTY;
	}

	private void tickCargoMission(ServerLevel level) {
		if (role() != DroneRole.CARGO || cargoPaused() || !hasCargoSource() || !hasCargoTarget()
			|| cargoSource().equals(cargoTarget()) || tickCount % 10 != 0) return;
		CargoState state = cargoState();
		if (state == CargoState.UNASSIGNED) {
			startCargoRouteIfReady();
			return;
		}
		if ((state == CargoState.WAIT_SOURCE || state == CargoState.WAIT_TARGET)
			&& level.getGameTime() < cargoRetryTick) return;
		BlockPos endpoint = state.usesSource() ? cargoSource() : cargoTarget();
		BlockPos expectedWaypoint = state.queued()
			? cargoHoldingWaypoint(endpoint, cargoQueuePosition())
			: cargoFlightWaypoint(endpoint);
		if (!hasWaypoint() || !waypointPos().equals(expectedWaypoint)) {
			assignWaypoint(expectedWaypoint, unitId() + "-cargo", 1, 0, blockPosition(), level.getGameTime());
		}
		double endpointDistance = position().distanceTo(Vec3.atCenterOf(endpoint).add(0, 2.0, 0));
		if (!state.queued() && !state.servicing() && endpointDistance > 3.0) return;

		ContainerAccessKey accessKey = new ContainerAccessKey(level, endpoint.asLong());
		ContainerAccessCoordinator.Grant grant = CARGO_ACCESS.request(accessKey, getUUID(), level.getGameTime());
		if (!grant.granted()) {
			CargoState queuedState = state.usesSource() ? CargoState.QUEUE_SOURCE : CargoState.QUEUE_TARGET;
			entityData.set(CARGO_STATE, queuedState.id());
			entityData.set(CARGO_QUEUE_POSITION, grant.queuePosition());
			cargoServiceReadyTick = -1L;
			cargoAccessAcquiredTick = -1L;
			BlockPos holding = cargoHoldingWaypoint(endpoint, grant.queuePosition());
			if (!hasWaypoint() || !waypointPos().equals(holding)) {
				assignWaypoint(holding, unitId() + "-cargo", 1, 0, blockPosition(), level.getGameTime());
			}
			return;
		}

		CargoState serviceState = state.usesSource() ? CargoState.LOADING_SOURCE : CargoState.UNLOADING_TARGET;
		if (!state.servicing()) {
			entityData.set(CARGO_STATE, serviceState.id());
			entityData.set(CARGO_QUEUE_POSITION, 0);
			cargoServiceReadyTick = level.getGameTime() + CARGO_SERVICE_TICKS;
			cargoAccessAcquiredTick = level.getGameTime();
			assignWaypoint(cargoFlightWaypoint(endpoint), unitId() + "-cargo", 1, 0, blockPosition(), level.getGameTime());
			return;
		}
		if (cargoServiceReadyTick < 0L) {
			cargoServiceReadyTick = level.getGameTime() + CARGO_SERVICE_TICKS;
			cargoAccessAcquiredTick = level.getGameTime();
			return;
		}
		if (endpointDistance > 3.0) {
			if (level.getGameTime() - cargoAccessAcquiredTick > CARGO_ACCESS_TIMEOUT_TICKS) {
				CARGO_ACCESS.release(accessKey, getUUID());
				entityData.set(CARGO_STATE, state.usesSource() ? CargoState.QUEUE_SOURCE.id() : CargoState.QUEUE_TARGET.id());
				cargoServiceReadyTick = -1L;
				cargoAccessAcquiredTick = -1L;
			}
			return;
		}
		if (level.getGameTime() < cargoServiceReadyTick) return;
		BlockEntity blockEntity = level.getBlockEntity(endpoint);
		if (!(blockEntity instanceof Container container)) {
			CARGO_ACCESS.release(accessKey, getUUID());
			return;
		}
		boolean sourceAccess = state.usesSource();
		if (sourceAccess) {
			loadCargo(container);
			CARGO_ACCESS.release(accessKey, getUUID());
			resetCargoAccessTiming();
			CargoState next = CargoMissionPolicy.afterSource(!cargo.isEmpty());
			if (next == CargoState.WAIT_SOURCE) {
				entityData.set(CARGO_STATE, next.id());
				cargoRetryTick = level.getGameTime() + CARGO_RETRY_TICKS;
				return;
			}
			entityData.set(CARGO_STATE, next.id());
			assignWaypoint(cargoFlightWaypoint(cargoTarget()), unitId() + "-cargo", 1, 0, blockPosition(), level.getGameTime());
			return;
		}
		unloadCargo(container);
		CARGO_ACCESS.release(accessKey, getUUID());
		resetCargoAccessTiming();
		CargoState next = CargoMissionPolicy.afterTarget(!cargo.isEmpty());
		if (next == CargoState.WAIT_TARGET) {
			entityData.set(CARGO_STATE, next.id());
			cargoRetryTick = level.getGameTime() + CARGO_RETRY_TICKS;
			return;
		}
		entityData.set(CARGO_STATE, next.id());
		assignWaypoint(cargoFlightWaypoint(cargoSource()), unitId() + "-cargo", 1, 0, blockPosition(), level.getGameTime());
	}

	private void resetCargoAccessTiming() {
		entityData.set(CARGO_QUEUE_POSITION, 0);
		cargoServiceReadyTick = -1L;
		cargoAccessAcquiredTick = -1L;
	}

	private void tickEngineerMission(ServerLevel level, ServerPlayer owner, List<DroneEntity> formation) {
		if (role() != DroneRole.ENGINEER || isDocked() || mode() != DroneMode.STANDBY) {
			clearEngineerAssignment();
			return;
		}
		if (tickCount % 10 != 0) return;
		List<DroneEntity> engineers = formation.stream()
			.filter(drone -> drone.role() == DroneRole.ENGINEER && !drone.isDocked()
				&& drone.mode() == DroneMode.STANDBY)
			.sorted(Comparator.comparing(DroneEntity::unitId)).toList();
		List<DroneEntity> damaged = formation.stream()
			.filter(drone -> drone != this && drone.isAlive() && drone.getHealth() < drone.getMaxHealth())
			.filter(drone -> drone.distanceToSqr(owner) <= ENGINEER_SEARCH_RADIUS * ENGINEER_SEARCH_RADIUS)
			.sorted(Comparator.comparingDouble((DroneEntity drone) -> drone.getHealth() / drone.getMaxHealth())
				.thenComparing(DroneEntity::unitId)).toList();
		int targetIndex = EngineerAssignmentPolicy.targetIndex(engineers.indexOf(this), damaged.size());
		if (targetIndex < 0) {
			clearEngineerAssignment();
			return;
		}
		DroneEntity target = damaged.get(targetIndex);
		engineerTargetId = target.getUUID();
		entityData.set(ENGINEER_TARGET, target.unitId());
		if (distanceToSqr(target) > ENGINEER_SERVICE_DISTANCE * ENGINEER_SERVICE_DISTANCE) {
			entityData.set(ENGINEER_STATE, EngineerState.APPROACH.id());
			return;
		}
		if (tickCount % ENGINEER_REPAIR_INTERVAL != 0) {
			entityData.set(ENGINEER_STATE, EngineerState.REPAIRING.id());
			return;
		}
		if (!owner.isCreative() && !consumeEngineerMaterial(owner)) {
			entityData.set(ENGINEER_STATE, EngineerState.MATERIAL_LOW.id());
			return;
		}
		target.heal(Math.min(ENGINEER_REPAIR_AMOUNT, target.getMaxHealth() - target.getHealth()));
		entityData.set(ENGINEER_STATE, EngineerState.REPAIRING.id());
	}

	private boolean consumeEngineerMaterial(ServerPlayer owner) {
		for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
			ItemStack stack = owner.getInventory().getItem(slot);
			if (!stack.is(Items.COPPER_INGOT)) continue;
			stack.shrink(1);
			return true;
		}
		return false;
	}

	private void clearEngineerAssignment() {
		engineerTargetId = null;
		entityData.set(ENGINEER_STATE, EngineerState.IDLE.id());
		entityData.set(ENGINEER_TARGET, "");
	}

	private DroneEntity engineerTarget(ServerLevel level) {
		if (engineerTargetId == null) return null;
		Entity entity = level.getEntity(engineerTargetId);
		return entity instanceof DroneEntity drone && drone.isAlive() ? drone : null;
	}

	private void releaseCargoAccess() {
		CARGO_ACCESS.releaseEverywhere(getUUID());
		resetCargoAccessTiming();
	}

	private void loadCargo(Container source) {
		for (int slot = 0; slot < source.getContainerSize() && cargo.size() < 9; slot++) {
			ItemStack available = source.getItem(slot);
			if (available.isEmpty() || !source.canTakeItem(source, slot, available)) continue;
			ItemStack removed = source.removeItem(slot, Math.min(available.getCount(), available.getMaxStackSize()));
			if (!removed.isEmpty()) cargo.add(removed);
		}
		source.setChanged();
		updateCargoCount();
	}

	private void unloadCargo(Container target) {
		Iterator<ItemStack> carried = cargo.iterator();
		while (carried.hasNext()) {
			ItemStack stack = carried.next();
			for (int slot = 0; slot < target.getContainerSize() && !stack.isEmpty(); slot++) {
				ItemStack present = target.getItem(slot);
				if (!present.isEmpty() && ItemStack.isSameItemSameComponents(present, stack)) {
					int moved = Math.min(stack.getCount(), target.getMaxStackSize(present) - present.getCount());
					if (moved > 0) { present.grow(moved); stack.shrink(moved); }
				}
			}
			for (int slot = 0; slot < target.getContainerSize() && !stack.isEmpty(); slot++) {
				if (!target.getItem(slot).isEmpty() || !target.canPlaceItem(slot, stack)) continue;
				int moved = Math.min(stack.getCount(), target.getMaxStackSize(stack));
				target.setItem(slot, stack.copyWithCount(moved));
				stack.shrink(moved);
			}
			if (stack.isEmpty()) carried.remove();
		}
		target.setChanged();
		updateCargoCount();
	}

	private void updateCargoCount() {
		entityData.set(CARGO_COUNT, cargo.stream().mapToInt(ItemStack::getCount).sum());
	}

	private boolean updateFormationCatchUp(boolean eligible, double slotDistance) {
		boolean active = FormationCatchUpPolicy.update(entityData.get(FORMATION_CATCH_UP), eligible, slotDistance);
		entityData.set(FORMATION_CATCH_UP, active);
		return active;
	}

	private void updateMissionDataLink(ServerLevel level, ServerPlayer owner,
		ThreatAssessment.Snapshot threat) {
		if (tickCount % 10 != 0) return;
		if (missionId().isBlank() || mode() != DroneMode.WAYPOINT) {
			missionIntel = MissionDataLink.Snapshot.empty();
			scoutRouteDecision = ScoutRoutePolicy.Decision.local();
			entityData.set(DATA_LINK_STATUS, "LOCAL");
			return;
		}
		String dimension = level.dimension().toString();
		long now = level.getGameTime();
		if (role() == DroneRole.SCOUT) {
			NavigationEnvironment.Snapshot scoutEnvironment = NavigationEnvironment.assessAt(level, blockPosition());
			double targetDistance = position().distanceTo(Vec3.atCenterOf(waypointPos()));
			boolean destinationReady = targetDistance <= 24.0 && threat.score() < 14;
			MissionDataLink.RouteStatus routeStatus;
			if (recoveryLevel() > 0) routeStatus = MissionDataLink.RouteStatus.BLOCKED;
			else if (threat.score() >= 14 || scoutEnvironment.openness() < 0.3
				|| scoutEnvironment.ceilingClearance() <= 2) routeStatus = MissionDataLink.RouteStatus.HAZARDOUS;
			else if (destinationReady) routeStatus = MissionDataLink.RouteStatus.CLEAR;
			else routeStatus = MissionDataLink.RouteStatus.PROBING;
			double confidence = Math.min(1.0, 0.35 + scoutEnvironment.openness() * 0.45
				+ (scoutEnvironment.skyVisible() ? 0.2 : 0.0));
			MissionDataLink.publish(dimension, owner.getUUID(), missionId(), unitId(),
				new MissionDataLink.ScoutReport(routeStatus, position(), threat.score(),
					threat.threatPosition(), destinationReady, confidence, now, groupId()));
		}
		missionIntel = MissionDataLink.read(dimension, owner.getUUID(), missionId(), now);
		scoutRouteDecision = ScoutRoutePolicy.decide(missionIntel, now);
		entityData.set(DATA_LINK_STATUS, missionIntel.available()
			? scoutRouteDecision.status() + " " + missionIntel.sourceCount()
			: "NO INTEL");
	}

	private void updateProtectionHold(ThreatAssessment.Snapshot threat, boolean rawProtection) {
		if (rawProtection) {
			protectionHoldTicks = 24;
			heldThreatPosition = smoothTarget(heldThreatPosition, threat.threatPosition(), 0.28);
			heldInterceptPosition = threat.interceptPosition();
			return;
		}
		if (protectionHoldTicks > 0) protectionHoldTicks--;
		else {
			heldThreatPosition = null;
			heldInterceptPosition = null;
		}
	}

	private void clearProtectionHold() {
		protectionHoldTicks = 0;
		heldThreatPosition = null;
		heldInterceptPosition = null;
	}

	private static Vec3 smoothTarget(Vec3 previous, Vec3 current, double response) {
		return previous == null || previous.distanceToSqr(current) > 256.0
			? current
			: previous.scale(1.0 - response).add(current.scale(response));
	}

	private void interceptProjectiles(ServerLevel level, ServerPlayer owner) {
		Vec3 shieldCenter = position().add(0, getBbHeight() * 0.5, 0);
		for (Projectile projectile : level.getEntitiesOfClass(Projectile.class, getBoundingBox().inflate(3.0),
			candidate -> candidate.getOwner() != owner && !(candidate.getOwner() instanceof DroneEntity))) {
			if (!ProjectileDefense.crossesShield(projectile.position(), projectile.getDeltaMovement(), shieldCenter, 1.05)) continue;
			projectile.discard();
			hurtServer(level, level.damageSources().generic(), 2.0f);
			break;
		}
	}

	private void repelMeleeThreats(ServerLevel level, ServerPlayer owner) {
		if (tickCount % 3 != 0) return;
		LivingEntity recentAttacker = owner.tickCount - owner.getLastHurtByMobTimestamp() <= 200
			? owner.getLastHurtByMob() : null;
		for (LivingEntity hostile : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(1.7),
			entity -> entity instanceof Enemy && entity.isAlive() && entity.distanceTo(owner) <= 3.4)) {
			if (hostile != recentAttacker && (!(hostile instanceof Mob mob) || mob.getTarget() != owner)) continue;
			Vec3 impulse = MeleeDefense.outwardImpulse(owner.position(), hostile.position());
			hostile.push(impulse.x, impulse.y, impulse.z);
		}
	}

	private void tickSecurityPatrol(ServerLevel level) {
		if (!hasSecurityPatrol() || tickCount % 5 != 0) return;
		Vec3 anchor = Vec3.atCenterOf(securityAnchor());
		double radius = securityRadius();
		List<LivingEntity> contacts = level.getEntitiesOfClass(LivingEntity.class,
			new AABB(securityAnchor()).inflate(radius, 12.0, radius),
			entity -> entity instanceof Enemy && entity.isAlive());
		LivingEntity primary = contacts.stream()
			.min(Comparator.comparingDouble(entity -> entity.position().distanceToSqr(anchor))).orElse(null);
		entityData.set(SECURITY_CONTACT, primary == null ? -1 : primary.getId());
		entityData.set(SECURITY_CONTACTS, contacts.size());
	}

	private void publishOwnerThreat(ServerLevel level, ServerPlayer owner,
		ThreatAssessment.Snapshot threat) {
		LivingEntity recentAttacker = owner.tickCount - owner.getLastHurtByMobTimestamp() <= 200
			? owner.getLastHurtByMob() : null;
		List<LivingEntity> contacts = level.getEntitiesOfClass(LivingEntity.class,
			owner.getBoundingBox().inflate(24.0, 12.0, 24.0),
			entity -> ThreatAssessment.isThreat(entity, owner, recentAttacker));
		for (LivingEntity contact : contacts) {
			int enemyThreat = ThreatAssessment.entityThreat(contact);
			boolean attackingOwner = contact instanceof Mob mob && mob.getTarget() == owner;
			int playerDanger = EnemyThreatPolicy.playerDanger(enemyThreat, contact.distanceTo(owner),
				attackingOwner, contact == recentAttacker, false);
			int score = Mth.clamp((int)Math.ceil(enemyThreat * 0.72 + playerDanger * 0.58), 1, 100);
			if (contact.getId() == threat.primaryEntityId()) {
				score = Math.max(score, threat.score());
				playerDanger = Math.max(playerDanger, threat.playerDanger());
			}
			FleetThreatNetwork.publish(level.dimension().toString(), ownerId(),
				"PLAYER-" + owner.getStringUUID() + '-' + contact.getId(),
				new FleetThreatNetwork.Report(score, enemyThreat, playerDanger, contact.position(),
					contact.getId(), "PLAYER-GUARD", "", level.getGameTime()));
		}
	}

	private void publishPatrolThreat(ServerLevel level, ServerPlayer owner) {
		boolean deployedScout = hasPatrolRoute() || hasActiveFieldOperation()
			|| mode() == DroneMode.WAYPOINT || hasTrackingTarget();
		if (role() != DroneRole.SCOUT || !deployedScout || isDocked() || tickCount % 10 != 0) return;
		LivingEntity recentAttacker = owner.tickCount - owner.getLastHurtByMobTimestamp() <= 200
			? owner.getLastHurtByMob() : null;
		List<LivingEntity> enemies = level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(28.0, 14.0, 28.0),
			entity -> ThreatAssessment.isThreat(entity, owner, recentAttacker));
		LivingEntity nearest = enemies.stream().min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
		int enemyThreat = enemies.stream().mapToInt(ThreatAssessment::entityThreat).max().orElse(0);
		if (enemies.size() > 1) enemyThreat = Mth.clamp(enemyThreat + (enemies.size() - 1) * 3, 0, 100);
		int score = Math.max(FleetThreatNetwork.score(enemies.size(), nearest == null ? 28.0 : distanceTo(nearest)),
			enemyThreat);
		for (LivingEntity enemy : enemies) {
			int individualThreat = ThreatAssessment.entityThreat(enemy);
			int individualScore = enemy == nearest ? score : Math.max(1, individualThreat);
			FleetThreatNetwork.publish(level.dimension().toString(), ownerId(), unitId() + '-' + enemy.getId(),
				new FleetThreatNetwork.Report(individualScore, individualThreat, 0, enemy.position(),
					enemy.getId(), groupId(), missionId(), level.getGameTime()));
		}
		if (score > 0) entityData.set(DATA_LINK_STATUS, "THREAT " + score + " / " + groupId());
	}

	private void publishTrackedTargetThreat(ServerLevel level, ServerPlayer owner) {
		if (!hasTrackingTarget() || tickCount % 10 != 0 || isDocked()
			|| role() != DroneRole.SCOUT && role() != DroneRole.SECURITY) return;
		LivingEntity target = trackingTarget(level);
		if (target == null) {
			entityData.set(DATA_LINK_STATUS, "TARGET TRACK / LOST");
			return;
		}
		LivingEntity recentAttacker = owner.tickCount - owner.getLastHurtByMobTimestamp() <= 200
			? owner.getLastHurtByMob() : null;
		TargetDisposition disposition = ThreatAssessment.targetDisposition(target, owner, recentAttacker);
		entityData.set(DATA_LINK_STATUS, "TARGET TRACK / " + disposition.label());
		if (!disposition.engageable()) return;
		int enemyThreat = ThreatAssessment.entityThreat(target);
		boolean direct = disposition == TargetDisposition.DIRECT_THREAT;
		int playerDanger = EnemyThreatPolicy.playerDanger(enemyThreat, target.distanceTo(owner),
			direct, target == recentAttacker, false);
		int score = Mth.clamp((int)Math.ceil(enemyThreat * 0.72 + playerDanger * 0.58), 1, 100);
		FleetThreatNetwork.publish(level.dimension().toString(), ownerId(), "TRACK-" + unitId(),
			new FleetThreatNetwork.Report(score, enemyThreat, playerDanger, target.position(),
				target.getId(), groupId(), missionId(), level.getGameTime()));
	}

	private void publishLocalSecurityThreat(ServerLevel level, ServerPlayer owner) {
		if (role() != DroneRole.SECURITY || isDocked() || serviceReturn || tickCount % 10 != 0
			|| !CombatPolicy.hasUsableWeapon(securityLoadout(), gunAmmo(), missiles(), laserHeat())) return;
		LivingEntity recentAttacker = owner.tickCount - owner.getLastHurtByMobTimestamp() <= 200
			? owner.getLastHurtByMob() : null;
		double radius = hasSecurityPatrol() ? Math.max(24.0, securityRadius()) : 24.0;
		List<LivingEntity> contacts = level.getEntitiesOfClass(LivingEntity.class,
			getBoundingBox().inflate(radius, 12.0, radius),
			entity -> ThreatAssessment.isThreat(entity, owner, recentAttacker));
		for (LivingEntity contact : contacts) {
			int enemyThreat = ThreatAssessment.entityThreat(contact);
			boolean attackingOwner = contact instanceof Mob mob && mob.getTarget() == owner;
			int playerDanger = EnemyThreatPolicy.playerDanger(enemyThreat, contact.distanceTo(owner),
				attackingOwner, contact == recentAttacker, false);
			int score = Mth.clamp((int)Math.ceil(enemyThreat * 0.72 + playerDanger * 0.58), 1, 100);
			FleetThreatNetwork.publish(level.dimension().toString(), ownerId(),
				"SECURITY-" + unitId() + '-' + contact.getId(),
				new FleetThreatNetwork.Report(score, enemyThreat, playerDanger, contact.position(),
					contact.getId(), groupId(), missionId(), level.getGameTime()));
		}
	}

	private void updateEmergencyInterception(ServerLevel level, ServerPlayer owner,
		List<DroneEntity> fleet) {
		if (role() != DroneRole.SECURITY || tickCount % 10 != 0) return;
		if (verificationCombatTargetId >= 0) {
			Entity verificationTarget = level.getEntity(verificationCombatTargetId);
			if (verificationTarget instanceof LivingEntity living && living.isAlive()) {
				emergencyTargetId = living.getId();
				emergencyTargetPosition = living.position();
				emergencyInterceptUntil = level.getGameTime() + 30L;
				return;
			}
			verificationCombatTargetId = -1;
		}
		CombatTheaterCoordinator.Snapshot theater = CombatTheaterCoordinator.coordinate(level, owner, fleet);
		BaseDefenseOperation.Snapshot baseDefense = BaseDefenseOperation.snapshot(ownerId(),
			level.dimension().toString());
		for (DroneEntity drone : fleet) {
			drone.entityData.set(OPERATION_SUMMARY, baseDefense.active()
				? baseDefense.summary() : theater.summary());
			drone.entityData.set(OPERATION_FRONTS, baseDefense.active()
				? baseDefense.fronts() + (theater.fronts().isBlank() ? "" : " / " + theater.fronts())
				: theater.fronts());
		}
		CombatTheaterPolicy.Assignment assignment = theater.assignment(unitId());
		if (assignment == null) {
			// The theater plan preserves committed passes. An unassigned aircraft is either
			// reserve strength or has been released after its target disappeared.
			if (combatState() != CombatState.IDLE && !combatState().rejoining()) {
				beginCombatRejoin(level, "THEATER RELEASED");
			} else if (emergencyInterceptActive()) clearEmergencyInterception();
			return;
		}
		CombatTheaterPolicy.Contact contact = assignment.contact();
		boolean playerEmergency = "PLAYER-GUARD".equals(contact.sourceWing());
		if (combatState() == CombatState.REJOIN && !playerEmergency) return;
		Entity assignedTarget = emergencyTargetId < 0 ? null : level.getEntity(emergencyTargetId);
		if (emergencyInterceptActive() && assignedTarget instanceof LivingEntity assigned
			&& assigned.isAlive() && assigned.getId() != contact.entityId() && !playerEmergency) {
			// An intercept order is committed before the weapon state starts. Scout data may
			// update the fleet picture, but it cannot redirect an aircraft mid-sortie.
			emergencyTargetPosition = assigned.position();
			emergencyInterceptUntil = level.getGameTime() + 30L;
			return;
		}
		Entity reportedTarget = level.getEntity(contact.entityId());
		if (!(reportedTarget instanceof LivingEntity living) || !living.isAlive()) {
			if (combatState() != CombatState.IDLE) beginCombatRejoin(level, "TARGET LOST");
			else clearEmergencyInterception();
			return;
		}
		LivingEntity committed = committedCombatTarget(level, owner, owner.getLastHurtByMob());
		boolean playerEmergencyOverride = playerEmergency
			&& committed != null && committed.getId() != contact.entityId();
		if (committed != null && committed.getId() != contact.entityId() && !playerEmergencyOverride) {
			// Scout reports are fleet intelligence, not per-tick retarget commands. Finish the
			// current engagement before accepting another remote contact.
			if (emergencyInterceptActive()) {
				emergencyTargetId = committed.getId();
				emergencyTargetPosition = committed.position();
				emergencyInterceptUntil = level.getGameTime() + 30L;
			}
			return;
		}
		emergencyLaunchedFromDock |= isDocked() || mode() == DroneMode.DOCK;
		entityData.set(DOCKED, false);
		emergencyTargetId = contact.entityId();
		emergencyTargetPosition = contact.position();
		emergencyInterceptSlot = assignment.slot();
		emergencyInterceptCount = assignment.count();
		emergencyInterceptUntil = level.getGameTime() + 30L;
		if (rechargeReclaimTargetId == contact.entityId()) rechargeReclaimUntil = level.getGameTime() + 100L;
		entityData.set(DATA_LINK_STATUS, assignment.clusterId() + " / INTERCEPT " + contact.score());
	}

	private void clearEmergencyInterception() {
		emergencyTargetId = -1;
		emergencyTargetPosition = null;
		emergencyInterceptUntil = -1L;
		emergencyInterceptSlot = 0;
		emergencyInterceptCount = 0;
		if (emergencyLaunchedFromDock && hasDock()) {
			emergencyLaunchedFromDock = false;
			setMode(DroneMode.DOCK);
		}
	}

	private void updateCombat(ServerLevel level, ServerPlayer owner, List<DroneEntity> fleet) {
		if (isDocked()) {
			DockBlockEntity serviceDock = level.getBlockEntity(dockPos()) instanceof DockBlockEntity dock ? dock : null;
			if (serviceDock != null && DockServicePolicy.needsRepair(getHealth(), getMaxHealth())
				&& tickCount % DockServicePolicy.REPAIR_INTERVAL == 0) {
				float repaired = serviceDock.provideRepair(getMaxHealth() - getHealth());
				if (repaired > 0.0f) heal(repaired);
			}
			if (serviceDock != null && tickCount % (DockServicePolicy.REPAIR_INTERVAL * 2) == 0
				&& lowestSubsystemCondition() < DroneSubsystemPolicy.MAX) {
				repairLowestSubsystem(serviceDock);
			}
			if (serviceDock != null && tickCount % DockServicePolicy.AUTOCANNON_REARM_INTERVAL == 0) {
				int supplied = serviceDock.provideAutocannonRounds(CombatPolicy.GUN_CAPACITY - gunAmmo());
				if (supplied > 0) entityData.set(GUN_AMMO, Math.min(CombatPolicy.GUN_CAPACITY, gunAmmo() + supplied));
			}
			if (serviceDock != null && tickCount % DockServicePolicy.MISSILE_REARM_INTERVAL == 0) {
				int supplied = serviceDock.provideMissiles(CombatPolicy.MISSILE_CAPACITY - missiles());
				if (supplied > 0) entityData.set(MISSILES, Math.min(CombatPolicy.MISSILE_CAPACITY, missiles() + supplied));
			}
			entityData.set(LASER_HEAT, Math.max(0, laserHeat() - 12));
			if (serviceDock != null && entityData.get(WEAPON_POWER) < 1000) {
				int supplied = serviceDock.provideWeaponCharge(Math.min(
					DockServicePolicy.WEAPON_CHARGE_PER_TICK, 1000 - entityData.get(WEAPON_POWER)));
				if (supplied > 0) entityData.set(WEAPON_POWER, entityData.get(WEAPON_POWER) + supplied);
			}
			clearCombatState();
			Entity reclaimTarget = resolveReclaimTarget(level);
			boolean liveEngagement = reclaimTarget instanceof LivingEntity living && living.isAlive();
			CombatTheaterCoordinator.Snapshot theater = role() == DroneRole.SECURITY
				? CombatTheaterCoordinator.coordinate(level, owner, fleet) : null;
			CombatTheaterPolicy.Assignment theaterAssignment = theater == null
				? null : theater.assignment(unitId());
			Entity assignedEntity = theaterAssignment == null ? null
				: level.getEntity(theaterAssignment.targetId());
			boolean theaterEmergency = theaterAssignment != null
				&& assignedEntity instanceof LivingEntity assigned && assigned.isAlive()
				&& CombatTheaterPolicy.operationalEmergency(theaterAssignment.contact());
			if (theaterEmergency) {
				reclaimTarget = assignedEntity;
				rechargeReclaimTargetId = theaterAssignment.targetId();
				rechargeReclaimTargetUuid = assignedEntity.getUUID();
				rechargeReclaimSlot = theaterAssignment.slot();
				liveEngagement = true;
			}
			DroneEntity activeRelief = liveEngagement ? activeReliefFor(fleet,
				rechargeReclaimTargetId, rechargeReclaimSlot) : null;
			if (activeRelief != null) rechargeReliefUnitUuid = activeRelief.getUUID();
			else activeRelief = rememberedReliefFor(fleet);
			boolean reliefHolding = liveEngagement && activeRelief != null
				&& engagementStrengthSatisfied(fleet, rechargeReclaimTargetId);
			boolean urgentSortie = theaterEmergency || liveEngagement && !reliefHolding
				&& ThreatAssessment.entityThreat((LivingEntity)reclaimTarget) >= 14;
			boolean ready = role() == DroneRole.SECURITY
				? CombatPolicy.sortieReady(securityLoadout(), getHealth() / getMaxHealth(),
					batteryPercent(), weaponPowerPercent(), gunAmmo(), missiles(), laserHeat(), urgentSortie)
				: DroneServicePolicy.nonCombatSortieReady(getHealth() / getMaxHealth(), batteryPercent());
			boolean powerAvailable = serviceDock != null && serviceDock.hasPowerSupply();
			boolean ammunitionAvailable = serviceDock != null && switch (securityLoadout()) {
				case AUTOCANNON -> serviceDock.hasAutocannonAmmunition();
				case MISSILE -> serviceDock.hasMissileAmmunition();
				case AUTO -> serviceDock.hasAutocannonAmmunition() || serviceDock.hasMissileAmmunition();
				default -> true;
			};
			boolean normalPowerIncomplete = batteryPercent() < DroneServicePolicy.NORMAL_SORTIE_POWER
				|| weaponPowerPercent() < DroneServicePolicy.NORMAL_SORTIE_POWER;
			boolean normalPayloadIncomplete = role() == DroneRole.SECURITY
				&& !CombatPolicy.normalPayloadReady(securityLoadout(), gunAmmo(), missiles(), laserHeat());
			boolean dockResourceExhausted = serviceDock != null
				&& DockServicePolicy.completionResourceExhausted(normalPowerIncomplete, powerAvailable,
					normalPayloadIncomplete, ammunitionAvailable);
			boolean resourceLimitedSortie = role() == DroneRole.SECURITY
				&& CombatPolicy.resourceLimitedSortieReady(securityLoadout(), getHealth() / getMaxHealth(),
					batteryPercent(), weaponPowerPercent(), gunAmmo(), missiles(), laserHeat(),
					liveEngagement, dockResourceExhausted);
			if (serviceReturn && (ready || resourceLimitedSortie)) {
				serviceReturn = false;
				serviceReason = DroneServicePolicy.Need.NONE;
				rechargeReclaimUntil = reliefHolding ? -1L : level.getGameTime() + 200L;
				if (reliefHolding) {
					rechargeReclaimTargetId = -1;
					rechargeReclaimTargetUuid = null;
				}
				entityData.set(DOCKED, false);
				resumeServiceTask(level);
				if (activeRelief != null) inheritReliefMission(activeRelief);
				if (theaterEmergency) {
					emergencyTargetId = theaterAssignment.targetId();
					emergencyTargetPosition = theaterAssignment.contact().position();
					emergencyInterceptSlot = theaterAssignment.slot();
					emergencyInterceptCount = theaterAssignment.count();
					emergencyInterceptUntil = level.getGameTime() + 100L;
				}
				entityData.set(DATA_LINK_STATUS, role() != DroneRole.SECURITY
					? "SERVICE COMPLETE / MISSION RESUME"
					: resourceLimitedSortie ? "RESOURCE LIMITED SORTIE / SUPPLY EXHAUSTED"
					: theaterEmergency ? "EMERGENCY SORTIE / " + theaterAssignment.clusterId()
					: reliefHolding ? "REARMED / RELIEF HOLDS / MISSION RESUME"
					: "REARMED / THREAT REASSESS");
			} else if (serviceReturn && serviceDock != null) {
				boolean ammunition = switch (securityLoadout()) {
					case AUTOCANNON -> serviceDock.hasAutocannonAmmunition() || gunAmmo() >= CombatPolicy.GUN_CAPACITY;
					case MISSILE -> serviceDock.hasMissileAmmunition() || missiles() >= CombatPolicy.MISSILE_CAPACITY;
					default -> true;
				};
				String waiting = DockServicePolicy.waitingStatus(serviceReason,
					powerAvailable, serviceDock.hasRepairMaterial()
						|| !DockServicePolicy.needsRepair(getHealth(), getMaxHealth()), ammunition);
				int requiredPower = urgentSortie ? CombatPolicy.URGENT_SORTIE_POWER
					: DroneServicePolicy.NORMAL_SORTIE_POWER;
				int requiredAmmunition = switch (securityLoadout()) {
					case AUTOCANNON -> urgentSortie ? 1
						: (int)Math.ceil(CombatPolicy.GUN_CAPACITY * 0.6);
					case MISSILE -> urgentSortie ? 1 : 2;
					case AUTO -> urgentSortie ? 1
						: (int)Math.ceil(CombatPolicy.GUN_CAPACITY * 0.6);
					default -> 0;
				};
				int ammunitionCount = securityLoadout() == SecurityLoadout.MISSILE
					? missiles() : gunAmmo();
				entityData.set(DATA_LINK_STATUS, DockServicePolicy.readinessStatus(waiting,
					batteryPercent(), weaponPowerPercent(), requiredPower,
					ammunitionCount, requiredAmmunition));
			}
			return;
		}
		if (serviceReturn) {
			clearCombatState();
			return;
		}
		if (role() != DroneRole.SECURITY) {
			clearCombatState();
			return;
		}
		DroneServicePolicy.Need weaponService = CombatPolicy.weaponServiceNeed(securityLoadout(),
			weaponPowerPercent(), gunAmmo(), missiles(), laserHeat());
		if (hasDock() && weaponService != DroneServicePolicy.Need.NONE) {
			beginWeaponRecharge(weaponService);
			return;
		}
		if (laserHeat() > 0 && combatState() != CombatState.LASER_FIRE) {
			entityData.set(LASER_HEAT, Math.max(0, laserHeat() - (combatActive() ? 2 : 5)));
		}
		// Finish the physical return to the Wing before accepting another ordinary
		// Scout contact. PLAYER-GUARD can still interrupt through the dispatcher above.
		if (combatState() == CombatState.REJOIN) return;

		LivingEntity recentAttacker = owner.tickCount - owner.getLastHurtByMobTimestamp() <= 200
			? owner.getLastHurtByMob() : null;
		LivingEntity target = committedCombatTarget(level, owner, recentAttacker);
		if (target == null) target = sharedCombatTarget(level, fleet);
		if (target == null || !target.isAlive()
			|| !ThreatAssessment.targetDisposition(target, owner, recentAttacker).engageable()) {
			if (combatState() != CombatState.IDLE && combatState() != CombatState.REJOIN) {
				beginCombatRejoin(level, "TARGET NEUTRALIZED");
			}
			return;
		}
		int targetId = target.getId();
		Vec3 targetPosition = target.position();
		List<DroneEntity> candidates = fleet.stream()
			.filter(drone -> drone.role() == DroneRole.SECURITY && !drone.isDocked()
				&& drone.recoveryLevel() <= 0 && sharesCombatChannel(drone)
				&& (!drone.laserTargetLocked() || drone.combatTargetId() == targetId)
				&& (drone.laserTargetLocked() || CombatPolicy.hasUsableWeapon(
					drone.securityLoadout(), drone.gunAmmo(), drone.missiles(), drone.laserHeat())))
			.sorted(Comparator.comparingInt((DroneEntity drone) ->
					drone.combatActive() && drone.combatTargetId() == targetId ? 0
						: drone.rechargeReclaimTargetId == targetId
							&& level.getGameTime() <= drone.rechargeReclaimUntil ? 1 : 2)
				.thenComparingInt(drone -> drone.combatActive()
					&& drone.combatTargetId() == targetId ? drone.combatSlot() : Integer.MAX_VALUE)
				.thenComparingDouble((DroneEntity drone) -> drone.position().distanceToSqr(targetPosition))
				.thenComparing(DroneEntity::unitId)).toList();
		int contacts = candidates.stream().mapToInt(drone -> drone.emergencyInterceptActive()
			? Math.max(1, drone.emergencyInterceptCount) : drone.securityContacts()).max().orElse(1);
		CombatTelemetry.Snapshot telemetry = CombatTelemetry.observe(level, ownerId(), target.getId(), fleet);
		int enemyThreat = ThreatAssessment.entityThreat(target);
		boolean attackingOwner = target instanceof Mob mob && mob.getTarget() == owner;
		int playerDanger = EnemyThreatPolicy.playerDanger(enemyThreat, target.distanceTo(owner),
			attackingOwner, target == owner.getLastHurtByMob(), false);
		int requested = Math.max(
			CombatPolicy.requiredAttackers(contacts, target.getHealth(), candidates.size()),
			GuardDispatchPolicy.responderCount(enemyThreat, playerDanger, telemetry.pressure(), candidates.size()));
		int committedCount = (int)candidates.stream().filter(drone ->
			drone.combatActive() && drone.combatTargetId() == targetId).count();
		requested = Math.max(requested, committedCount);
		requested = Math.min(candidates.size(), requested);
		List<DroneEntity> selected = candidates.subList(0, requested);
		int slot = stableCombatSlot(selected, target.getId(), requested);
		if (slot < 0) {
			if (combatActive()) beginCombatRejoin(level, "ELEMENT RELEASED");
			return;
		}

		entityData.set(COMBAT_TARGET, target.getId());
		entityData.set(COMBAT_SLOT, slot);
		entityData.set(COMBAT_COUNT, Math.max(1, requested));
		if (combatState() == CombatState.IDLE || combatState() == CombatState.REJOIN) {
			if (combatState() == CombatState.IDLE) {
				taskStack.suspend(currentTaskSnapshot(mode(), false));
			}
			if (combatResumeMode < 0) combatResumeMode = mode().id();
			entityData.set(COMBAT_WEAPON, CombatPolicy.weaponFor(securityLoadout(), slot, requested,
				owner.getHealth() / owner.getMaxHealth(), target.getHealth(), gunAmmo(), missiles(), laserHeat()).id());
			entityData.set(COMBAT_CHARGE, 0);
			setCombatState(CombatState.FLARE_ENTRY);
		}

		switch (combatState()) {
			case FLARE_ENTRY -> {
				if (combatElapsed(level) >= CombatPolicy.FLARE_ENTRY_TICKS) enterWeaponState(level);
			}
			case GUN_RUN -> tickAutocannon(level, target);
			case LASER_CHARGE -> tickLaserCharge(level);
			case LASER_FIRE -> tickLaserFire(level, target);
			case MISSILE_APPROACH -> tickMissileAttack(level, target);
			case MISSILE_EGRESS -> {
				if (combatElapsed(level) >= 34) selectNextCombatPass(level, owner, target);
			}
			case RAM_APPROACH -> tickRam(level, target);
			default -> {}
		}
	}

	private void repairLowestSubsystem(DockBlockEntity dock) {
		int lowest = lowestSubsystemCondition();
		int supplied = dock.provideSubsystemRepair(DroneSubsystemPolicy.MAX - lowest);
		if (supplied <= 0) return;
		if (propulsionCondition() <= sensorCondition() && propulsionCondition() <= payloadCondition()) {
			entityData.set(PROPULSION_CONDITION,
				DroneSubsystemPolicy.repair(propulsionCondition(), supplied));
			entityData.set(DATA_LINK_STATUS, "DOCK SERVICE / PROPULSION");
		} else if (sensorCondition() <= payloadCondition()) {
			entityData.set(SENSOR_CONDITION, DroneSubsystemPolicy.repair(sensorCondition(), supplied));
			entityData.set(DATA_LINK_STATUS, "DOCK SERVICE / SENSOR");
		} else {
			entityData.set(PAYLOAD_CONDITION, DroneSubsystemPolicy.repair(payloadCondition(), supplied));
			entityData.set(DATA_LINK_STATUS, "DOCK SERVICE / PAYLOAD");
		}
	}

	private boolean engagementStrengthSatisfied(List<DroneEntity> fleet, int targetId) {
		if (targetId < 0) return false;
		List<DroneEntity> active = fleet.stream().filter(drone -> drone != this
			&& drone.combatActive() && drone.combatTargetId() == targetId
			&& !drone.isDocked() && !drone.serviceReturn).toList();
		int required = active.stream().mapToInt(DroneEntity::combatCount).max().orElse(0);
		return required > 0 && active.size() >= required;
	}

	private Entity resolveReclaimTarget(ServerLevel level) {
		Entity target = rechargeReclaimTargetId < 0 ? null : level.getEntity(rechargeReclaimTargetId);
		if (target == null && rechargeReclaimTargetUuid != null) {
			target = level.getEntity(rechargeReclaimTargetUuid);
			if (target != null) rechargeReclaimTargetId = target.getId();
		}
		return target;
	}

	private DroneEntity activeReliefFor(List<DroneEntity> fleet, int targetId, int preferredSlot) {
		return fleet.stream().filter(drone -> drone != this && drone.combatActive()
			&& drone.combatTargetId() == targetId && !drone.isDocked() && !drone.serviceReturn)
			.sorted(Comparator.comparingInt((DroneEntity drone) ->
				drone.combatSlot() == preferredSlot ? 0 : 1)
				.thenComparingInt(DroneEntity::combatSlot).thenComparing(DroneEntity::unitId))
			.findFirst().orElse(null);
	}

	private DroneEntity rememberedReliefFor(List<DroneEntity> fleet) {
		if (rechargeReliefUnitUuid == null) return null;
		return fleet.stream().filter(drone -> drone != this
			&& drone.getUUID().equals(rechargeReliefUnitUuid)
			&& !drone.isDocked() && !drone.serviceReturn
			&& drone.role() == DroneRole.SECURITY && drone.hasSecurityPatrol())
			.findFirst().orElse(null);
	}

	private void inheritReliefMission(DroneEntity relief) {
		reliefMissionInheritedFrom = relief.securityOrderId();
		rechargeReliefUnitUuid = null;
		entityData.set(HAS_WAYPOINT, relief.entityData.get(HAS_WAYPOINT));
		entityData.set(WAYPOINT_POS, relief.entityData.get(WAYPOINT_POS));
		entityData.set(MISSION_ID, relief.entityData.get(MISSION_ID));
		entityData.set(MISSION_EXPECTED, relief.entityData.get(MISSION_EXPECTED));
		entityData.set(MISSION_INDEX, relief.entityData.get(MISSION_INDEX));
		entityData.set(MISSION_STAGE, relief.entityData.get(MISSION_STAGE));
		entityData.set(MISSION_ORIGIN, relief.entityData.get(MISSION_ORIGIN));
		entityData.set(MISSION_ASSIGNED_TICK, relief.entityData.get(MISSION_ASSIGNED_TICK));
		entityData.set(ORBIT_ENTRY_TICK, relief.entityData.get(ORBIT_ENTRY_TICK));
		entityData.set(ORBIT_PHASE_OFFSET, relief.entityData.get(ORBIT_PHASE_OFFSET));
		entityData.set(TRACK_TARGET, relief.entityData.get(TRACK_TARGET));
		entityData.set(PATROL_ROUTE, relief.entityData.get(PATROL_ROUTE));
		entityData.set(PATROL_ROUTE_INDEX, relief.entityData.get(PATROL_ROUTE_INDEX));
		entityData.set(PATROL_ROUTE_FIRST_LEG, relief.entityData.get(PATROL_ROUTE_FIRST_LEG));
		entityData.set(SECURITY_ORDER, relief.entityData.get(SECURITY_ORDER));
		entityData.set(SECURITY_ANCHOR, relief.entityData.get(SECURITY_ANCHOR));
		entityData.set(SECURITY_RADIUS, relief.entityData.get(SECURITY_RADIUS));
		entityData.set(FIELD_ORDER, relief.entityData.get(FIELD_ORDER));
		entityData.set(FIELD_TYPE, relief.entityData.get(FIELD_TYPE));
		entityData.set(FIELD_ANCHOR, relief.entityData.get(FIELD_ANCHOR));
		entityData.set(FIELD_RADIUS, relief.entityData.get(FIELD_RADIUS));
		entityData.set(FIELD_STATE, relief.entityData.get(FIELD_STATE));
		entityData.set(FIELD_PROGRESS, relief.entityData.get(FIELD_PROGRESS));
		entityData.set(FIELD_FOUND, relief.entityData.get(FIELD_FOUND));
		entityData.set(FIELD_STOCK, relief.entityData.get(FIELD_STOCK));
		entityData.set(MODE, relief.mode().id());
		serviceResumeMode = relief.mode().id();
		rechargeReclaimSlot = -1;
	}

	private boolean laserTargetLocked() {
		return combatWeapon() == CombatWeapon.LASER
			&& (combatState() == CombatState.LASER_CHARGE || combatState() == CombatState.LASER_FIRE);
	}

	private LivingEntity lockedLaserTarget(ServerLevel level, ServerPlayer owner,
		LivingEntity recentAttacker) {
		if (!laserTargetLocked() || combatTargetId() < 0) return null;
		Entity locked = level.getEntity(combatTargetId());
		if (!(locked instanceof LivingEntity living) || !living.isAlive()) return null;
		return ThreatAssessment.targetDisposition(living, owner, recentAttacker).engageable()
			? living : null;
	}

	private LivingEntity committedCombatTarget(ServerLevel level, ServerPlayer owner,
		LivingEntity recentAttacker) {
		if (!combatState().controlsFlight() || combatState() == CombatState.REJOIN
			|| combatTargetId() < 0) return null;
		Entity current = level.getEntity(combatTargetId());
		if (!(current instanceof LivingEntity living) || !living.isAlive()) return null;
		return ThreatAssessment.targetDisposition(living, owner, recentAttacker).engageable()
			? living : null;
	}

	private int stableCombatSlot(List<DroneEntity> selected, int targetId, int requested) {
		if (!selected.contains(this) || requested <= 0) return -1;
		Map<DroneEntity, Integer> assignments = new HashMap<>();
		boolean[] used = new boolean[requested];
		selected.stream()
			.filter(drone -> drone.combatState().controlsFlight() && drone.combatTargetId() == targetId)
			.sorted(Comparator.comparingInt(DroneEntity::combatSlot).thenComparing(DroneEntity::unitId))
			.forEach(drone -> {
				int existing = drone.combatSlot();
				if (existing < requested && !used[existing]) {
					assignments.put(drone, existing);
					used[existing] = true;
				}
			});
		for (DroneEntity drone : selected) {
			if (assignments.containsKey(drone)) continue;
			int available = 0;
			while (available < requested && used[available]) available++;
			if (available >= requested) break;
			assignments.put(drone, available);
			used[available] = true;
		}
		return assignments.getOrDefault(this, -1);
	}

	private int candidateCombatTargetId() {
		if (emergencyInterceptActive()) return emergencyTargetId;
		return hasSecurityPatrol() || hasActiveFieldOperation()
			? entityData.get(SECURITY_CONTACT) : -1;
	}

	private boolean sharesCombatChannel(DroneEntity other) {
		if (!ownerId().equals(other.ownerId())) return false;
		if (emergencyInterceptActive()) {
			return other.emergencyInterceptActive() && other.emergencyTargetId == emergencyTargetId;
		}
		if (hasActiveFieldOperation()) {
			return other.hasActiveFieldOperation() && fieldOrderId().equals(other.fieldOrderId());
		}
		return hasSecurityPatrol() && other.hasSecurityPatrol()
			&& entityData.get(SECURITY_ORDER).equals(other.entityData.get(SECURITY_ORDER));
	}

	private LivingEntity sharedCombatTarget(ServerLevel level, List<DroneEntity> fleet) {
		Vec3 reference = hasSecurityPatrol() ? Vec3.atCenterOf(securityAnchor()) : position();
		return fleet.stream().filter(this::sharesCombatChannel)
			.map(DroneEntity::candidateCombatTargetId).filter(id -> id >= 0).distinct()
			.map(level::getEntity).filter(entity -> entity instanceof LivingEntity living && living.isAlive())
			.map(entity -> (LivingEntity)entity)
			.min(Comparator.comparingDouble((LivingEntity entity) -> entity.position().distanceToSqr(reference))
				.thenComparingInt(Entity::getId)).orElse(null);
	}

	private LivingEntity combatTarget(ServerLevel level) {
		int id = combatActive() ? combatTargetId() : candidateCombatTargetId();
		Entity entity = id < 0 ? null : level.getEntity(id);
		return entity instanceof LivingEntity living && living.isAlive() ? living : null;
	}

	private void setCombatState(CombatState state) {
		CombatState previous = combatState();
		entityData.set(COMBAT_STATE, state.id());
		entityData.set(COMBAT_STATE_TICK, level().getGameTime());
		if (level() instanceof ServerLevel serverLevel && previous != state) {
			if (state == CombatState.LASER_CHARGE) serverLevel.playSound(null, getX(), getY(), getZ(),
				MorrowgearDrone.LASER_CHARGE_SOUND, SoundSource.PLAYERS, laserEmitterVolume(0.72f),
				0.96f + combatSlot() * 0.004f);
			else if (state == CombatState.LASER_FIRE) serverLevel.playSound(null, getX(), getY(), getZ(),
				MorrowgearDrone.LASER_FIRE_SOUND, SoundSource.PLAYERS, laserEmitterVolume(0.62f),
				1.0f);
			else if (previous == CombatState.LASER_FIRE) serverLevel.playSound(null, getX(), getY(), getZ(),
				MorrowgearDrone.LASER_SHUTDOWN_SOUND, SoundSource.PLAYERS, laserEmitterVolume(0.64f),
				0.98f + combatSlot() * 0.006f);
		}
		if (state == CombatState.LASER_CHARGE && previous != CombatState.LASER_CHARGE) {
			laserReadySinceTick = -1L;
		}
		if (state == CombatState.IDLE) {
			entityData.set(COMBAT_WEAPON, CombatWeapon.NONE.id());
			entityData.set(COMBAT_TARGET, -1);
			entityData.set(COMBAT_CHARGE, 0);
			casTrackedTargetId = -1;
			casManeuverCenter = null;
			casTrackTick = -1L;
			casBreakawayActive = false;
			casBreakawayPoint = null;
			casBreakawayDirection = null;
			laserClearanceLift = 0.0;
			laserClearanceLiftTick = -1L;
			laserReadySinceTick = -1L;
			entityData.set(COMBAT_AIM_TARGET, -1);
		}
	}

	private float laserEmitterVolume(float singleEmitterVolume) {
		int simultaneousEmitters = Math.min(8, Math.max(1, combatCount()));
		return (float)(singleEmitterVolume / Math.sqrt(simultaneousEmitters));
	}

	private void clearCombatState() {
		if (combatState() != CombatState.IDLE || combatTargetId() >= 0) setCombatState(CombatState.IDLE);
		combatResumeMode = -1;
	}

	private void beginCombatRejoin(ServerLevel level, String reason) {
		if (combatResumeMode < 0) combatResumeMode = mode().id();
		boolean launchedFromDock = emergencyLaunchedFromDock;
		boolean entering = combatState() != CombatState.REJOIN;
		if (entering) setCombatState(CombatState.REJOIN);
		clearEmergencyInterception();
		DroneMode fallback = CombatRejoinPolicy.resumeMode(combatResumeMode, hasWaypoint(),
			!missionId().isBlank(), launchedFromDock, hasDock());
		DroneTaskStack.Task interrupted = entering ? taskStack.resume(task ->
			task.kind() == DroneTaskStack.Kind.COMBAT
				? java.util.Optional.empty() : resolveTaskForResume(level, task)).orElse(null) : null;
		DroneMode resume = interrupted == null ? fallback : interrupted.mode();
		entityData.set(MODE, resume.id());
		entityData.set(DATA_LINK_STATUS, reason + " / REJOIN " + groupId());
	}

	private long combatElapsed(ServerLevel level) {
		return Math.max(0L, level.getGameTime() - combatStateTick());
	}

	private void enterWeaponState(ServerLevel level) {
		if (weaponPowerPercent() <= 0) {
			beginWeaponRecharge();
			return;
		}
		CombatState next = switch (combatWeapon()) {
			case AUTOCANNON -> CombatState.GUN_RUN;
			case LASER -> CombatState.LASER_CHARGE;
			case MISSILE -> CombatState.MISSILE_APPROACH;
			case RAM -> CombatState.RAM_APPROACH;
			default -> CombatState.REJOIN;
		};
		if (next == CombatState.REJOIN) beginCombatRejoin(level, "WEAPONS DEPLETED");
		else setCombatState(next);
	}

	private void selectNextCombatPass(ServerLevel level, ServerPlayer owner, LivingEntity target) {
		CombatWeapon next = CombatPolicy.weaponFor(securityLoadout(), combatSlot(), combatCount(),
			owner.getHealth() / owner.getMaxHealth(), target.getHealth(), gunAmmo(), missiles(), laserHeat());
		if (next == CombatWeapon.NONE) {
			DroneServicePolicy.Need service = CombatPolicy.weaponServiceNeed(securityLoadout(),
				weaponPowerPercent(), gunAmmo(), missiles(), laserHeat());
			if (service != DroneServicePolicy.Need.NONE && hasDock()) beginWeaponRecharge(service);
			else beginCombatRejoin(level, laserHeat() >= CombatPolicy.LASER_SWITCH_HEAT
				? "LASER COOLING" : "WEAPONS DEPLETED");
			return;
		}
		entityData.set(COMBAT_WEAPON, next.id());
		entityData.set(COMBAT_CHARGE, 0);
		enterWeaponState(level);
	}

	private void tickAutocannon(ServerLevel level, LivingEntity target) {
		if (weaponPowerPercent() <= 0) {
			beginWeaponRecharge(DroneServicePolicy.Need.WEAPON_POWER);
			return;
		}
		if (gunAmmo() <= 0) {
			CombatWeapon fallback = CombatPolicy.weaponFor(securityLoadout(), combatSlot(), combatCount(),
				1.0f, target.getHealth(), gunAmmo(), missiles(), laserHeat());
			if (fallback == CombatWeapon.NONE && hasDock()) {
				beginWeaponRecharge(CombatPolicy.weaponServiceNeed(securityLoadout(),
					weaponPowerPercent(), gunAmmo(), missiles(), laserHeat()));
				return;
			}
			entityData.set(COMBAT_WEAPON, fallback.id());
			entityData.set(COMBAT_CHARGE, 0);
			enterWeaponState(level);
			return;
		}
		CasElement element = autocannonElement(level);
		if (!CombatPolicy.autocannonFireTick(tickCount)
			|| distanceToSqr(target) > 42.0 * 42.0) return;
		Vec3 from = combatMuzzlePosition();
		Vec3 observedCenter = target.position().add(0, target.getBbHeight() * 0.55, 0);
		Vec3 center = autocannonManeuverCenter(level, target);
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId());
		Vec3 attackAxis = combatAttackAxis(owner, target, center);
		Vec3 flightSlot = CombatPolicy.casFormation(center, attackAxis, element.index(),
			element.count(), element.elapsed(), element.airspace());
		double trackingError = center.distanceTo(observedCenter);
		double slotTolerance = CombatPolicy.casSlotTolerance(target.getDeltaMovement(), trackingError);
		CombatPolicy.FiringSolution firingSolution = CombatPolicy.firingSolution(
			from.distanceTo(observedCenter), 28.0, position().distanceTo(flightSlot),
			slotTolerance, 42.0);
		if (!firingSolution.permitted()) return;
		boolean mobileTarget = CombatPolicy.mobileCasTarget(target.getDeltaMovement(), trackingError);
		Vec3 aim;
		if (mobileTarget) {
			aim = CombatPolicy.ballisticAim(observedCenter, target.getDeltaMovement(),
				tickCount + combatSlot() * 101, from.distanceTo(observedCenter), firingSolution.accuracy());
		} else {
			Vec3 strike = CombatPolicy.casStrikePoint(center, attackAxis,
				element.index(), element.elapsed(), element.airspace());
			Vec3 roughAim = CombatPolicy.ballisticAim(strike, Vec3.ZERO,
				tickCount + combatSlot() * 101, from.distanceTo(strike), firingSolution.accuracy());
			BlockHitResult ground = (BlockHitResult)level.clip(new ClipContext(roughAim.add(0, 14, 0),
				roughAim.add(0, -18, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
			aim = ground.getType() == HitResult.Type.BLOCK ? ground.getLocation() : roughAim;
		}
		Vec3 plannedVelocity = CombatPolicy.casFormationVelocity(center, attackAxis,
			element.index(), element.count(), element.elapsed(), element.airspace());
		if (!CombatPolicy.forwardFiringSolution(from, plannedVelocity, aim)
			&& !CombatPolicy.forwardFiringSolution(from, getDeltaMovement(), aim)) return;
		entityData.set(GUN_AMMO, gunAmmo() - 1);
		entityData.set(WEAPON_POWER, Math.max(0, entityData.get(WEAPON_POWER) - 5));
		HitResult obstruction = level.clip(new ClipContext(from, aim, ClipContext.Block.COLLIDER,
			ClipContext.Fluid.NONE, this));
		Vec3 blockImpact = obstruction.getType() == HitResult.Type.BLOCK ? obstruction.getLocation() : aim;
		if (!CombatPolicy.autocannonImpactAcceptable(aim, blockImpact, observedCenter)) return;
		EntityHitResult entityImpact = ProjectileUtil.getEntityHitResult(level, this, from, blockImpact,
			new AABB(from, blockImpact).inflate(0.4), entity -> entity instanceof LivingEntity living
				&& living.isAlive() && !(living instanceof DroneEntity) && !living.getUUID().equals(ownerId()), 0.2f);
		LivingEntity directVictim = entityImpact != null && entityImpact.getEntity() instanceof LivingEntity living
			? living : null;
		Vec3 impact = entityImpact == null ? blockImpact : entityImpact.getLocation();
		entityData.set(COMBAT_SHOT_TICK, tickCount);
		entityData.set(COMBAT_AIM_X, (float)impact.x);
		entityData.set(COMBAT_AIM_Y, (float)impact.y);
		entityData.set(COMBAT_AIM_Z, (float)impact.z);
		entityData.set(COMBAT_AIM_TARGET, target.getId());
		if (Math.floorMod(tickCount + combatSlot() * 2, 8) == 0)
			level.playSound(null, getX(), getY(), getZ(),
			MorrowgearDrone.AUTOCANNON_BURST_SOUND, SoundSource.PLAYERS, 0.88f,
			0.98f + level.getRandom().nextFloat() * 0.04f);
		applyAutocannonImpact(level, impact, directVictim);
	}

	private void applyAutocannonImpact(ServerLevel level, Vec3 impact, LivingEntity directVictim) {
		level.sendParticles(ParticleTypes.EXPLOSION, impact.x, impact.y + 0.08, impact.z,
			1, 0.08, 0.05, 0.08, 0.0);
		level.sendParticles(ParticleTypes.SMOKE, impact.x, impact.y + 0.12, impact.z,
			4, 0.18, 0.12, 0.18, 0.015);
		level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.GENERIC_EXPLODE,
			SoundSource.HOSTILE, 0.22f, 1.18f + level.getRandom().nextFloat() * 0.10f);
		if (directVictim != null && directVictim.isAlive()) {
			MorrowgearCombatDamage.apply(level, directVictim,
				level.damageSources().mobProjectile(this, this),
				AutocannonImpactPolicy.DIRECT_DAMAGE);
		}
		AABB blast = new AABB(impact, impact).inflate(AutocannonImpactPolicy.BLAST_RADIUS);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, blast,
			entity -> entity.isAlive() && !(entity instanceof DroneEntity) && !entity.getUUID().equals(ownerId()))) {
			Vec3 body = victim.position().add(0, victim.getBbHeight() * 0.5, 0);
			double distance = body.distanceTo(impact);
			Vec3 exposureOrigin = body.subtract(impact).lengthSqr() < 0.0001 ? impact
				: impact.add(body.subtract(impact).normalize().scale(0.08));
			float damage = AutocannonImpactPolicy.blastDamage(distance,
				lineClear(level, exposureOrigin, body));
			if (damage > 0) MorrowgearCombatDamage.apply(level, victim,
				level.damageSources().explosion(this, this), damage);
		}
		BlockPos block = BlockPos.containing(impact.add(0, -0.08, 0));
		BlockState state = level.getBlockState(block);
		for (int depth = 0; state.isAir() && depth < 3; depth++) {
			block = block.below();
			state = level.getBlockState(block);
		}
		float hardness = state.getDestroySpeed(level, block);
		if (state.isAir() || hardness < 0 || level.getBlockEntity(block) != null) return;
		int interval = hardness <= 0.5f ? 1 : hardness <= 1.5f ? 3 : hardness <= 3.0f ? 8 : Integer.MAX_VALUE;
		if (interval != Integer.MAX_VALUE && Math.floorMod(tickCount + combatSlot(), interval) == 0) {
			level.destroyBlock(block, true, this);
		}
	}

	private void tickLaserCharge(ServerLevel level) {
		if (weaponPowerPercent() <= 0) {
			beginWeaponRecharge(DroneServicePolicy.Need.WEAPON_POWER);
			return;
		}
		int previousCharge = combatCharge();
		int charge = Math.min(1000, previousCharge
			+ CombatPolicy.laserChargePerTick(combatSlot(), batteryPercent()));
		entityData.set(COMBAT_CHARGE, charge);
		if (previousCharge < 1000) {
			entityData.set(WEAPON_POWER, Math.max(0, entityData.get(WEAPON_POWER) - 2));
		}
		if (charge < 1000) return;
		if (laserReadySinceTick < 0L) laserReadySinceTick = level.getGameTime();
		LivingEntity target = combatTarget(level);
		if (target == null) return;
		if (!laserFormationReady(level)) return;
		Vec3 center = target.position().add(0, target.getBbHeight() * 0.5, 0);
		LaserElement element = laserElement(level);
		Vec3 slot = laserFormationSlot(level, center, element.index(), element.count(),
			level.getGameTime(), charge, element.airspace());
		CombatPolicy.FiringSolution solution = CombatPolicy.firingSolution(
			position().distanceTo(center), 8.5, position().distanceTo(slot), 1.65, 24.0);
		if (solution.permitted() && lineClear(level, laserOutletPosition(), center)) {
			entityData.set(COMBAT_AIM_X, (float)center.x);
			entityData.set(COMBAT_AIM_Y, (float)center.y);
			entityData.set(COMBAT_AIM_Z, (float)center.z);
			entityData.set(COMBAT_AIM_TARGET, target.getId());
			setCombatState(CombatState.LASER_FIRE);
		}
	}

	private void tickLaserFire(ServerLevel level, LivingEntity target) {
		if (weaponPowerPercent() <= 0) {
			beginWeaponRecharge(DroneServicePolicy.Need.WEAPON_POWER);
			return;
		}
		if (laserHeat() >= CombatPolicy.LASER_SWITCH_HEAT) {
			if (securityLoadout() == SecurityLoadout.LASER) {
				entityData.set(COMBAT_CHARGE, 0);
				setCombatState(CombatState.LASER_CHARGE);
				return;
			}
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId());
			if (owner != null) selectNextCombatPass(level, owner, target);
			else beginWeaponRecharge();
			return;
		}
		entityData.set(LASER_HEAT, Math.min(1000,
			laserHeat() + CombatPolicy.LASER_FIRE_HEAT_PER_TICK));
		entityData.set(WEAPON_POWER, Math.max(0,
			entityData.get(WEAPON_POWER) - CombatPolicy.LASER_FIRE_POWER_PER_TICK));
		if (Math.floorMod(tickCount + combatSlot() * 3, 30) == 0) level.playSound(null,
			getX(), getY(), getZ(), MorrowgearDrone.LASER_FIRE_SOUND,
			SoundSource.PLAYERS, laserEmitterVolume(0.62f), 1.0f);
		if (tickCount % 4 == 0) {
			Vec3 from = laserOutletPosition();
			Vec3 center = target.position().add(0, target.getBbHeight() * 0.6, 0);
			LaserElement element = laserElement(level);
			Vec3 slot = laserFormationSlot(level, center, element.index(), element.count(),
				level.getGameTime(), 1000, element.airspace());
			CombatPolicy.FiringSolution solution = CombatPolicy.firingSolution(
				from.distanceTo(center), 8.5, position().distanceTo(slot), 1.65, 24.0);
			Vec3 aim = CombatPolicy.ballisticAim(center, target.getDeltaMovement(),
				tickCount + combatSlot() * 131, from.distanceTo(center), solution.accuracy());
			HitResult obstruction = level.clip(new ClipContext(from, aim, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, this));
			Vec3 visualAim = obstruction.getType() == HitResult.Type.MISS ? aim : obstruction.getLocation();
			entityData.set(COMBAT_AIM_X, (float)visualAim.x);
			entityData.set(COMBAT_AIM_Y, (float)visualAim.y);
			entityData.set(COMBAT_AIM_Z, (float)visualAim.z);
			entityData.set(COMBAT_AIM_TARGET, target.getId());
			entityData.set(COMBAT_SHOT_TICK, tickCount);
			if (solution.permitted() && obstruction.getType() == HitResult.Type.MISS
				&& CombatPolicy.accuracyHit(tickCount + combatSlot() * 131, solution.accuracy())) {
				int synchronizedUnits = laserSynchronizedCount(level, target);
				MorrowgearCombatDamage.apply(level, target, level.damageSources().mobAttack(this),
					CombatPolicy.laserPulseDamage(synchronizedUnits));
				if (element.index() == 0 && Math.floorMod(tickCount + target.getId(), 28) == 0)
					level.playSound(null,
						center.x, center.y, center.z, MorrowgearDrone.LASER_HIT_SOUND,
						SoundSource.PLAYERS, 0.36f, 0.92f + level.getRandom().nextFloat() * 0.08f);
			}
		}
	}

	private void tickMissileAttack(ServerLevel level, LivingEntity target) {
		if (weaponPowerPercent() <= 0) {
			beginWeaponRecharge(DroneServicePolicy.Need.WEAPON_POWER);
			return;
		}
		Vec3 approach = CombatPolicy.missileApproach(target.position(), position(), combatSlot(), combatCount(),
			combatAirspaceSlot(level));
		CombatPolicy.FiringSolution missileSolution = CombatPolicy.firingSolution(
			distanceTo(target), 11.0, position().distanceTo(approach), 3.0, 28.0);
		if (!missileSolution.permitted() || missiles() <= 0) {
			if (missiles() <= 0) {
				CombatWeapon fallback = CombatPolicy.weaponFor(securityLoadout(), combatSlot(), combatCount(),
					1.0f, target.getHealth(), gunAmmo(), missiles(), laserHeat());
				if (fallback == CombatWeapon.NONE && hasDock()) {
					beginWeaponRecharge(CombatPolicy.weaponServiceNeed(securityLoadout(),
						weaponPowerPercent(), gunAmmo(), missiles(), laserHeat()));
					return;
				}
				entityData.set(COMBAT_WEAPON, fallback.id());
				enterWeaponState(level);
			}
			return;
		}
		MorrowgearMissileEntity.launch(level, this, target);
		entityData.set(MISSILES, missiles() - 1);
		entityData.set(WEAPON_POWER, Math.max(0, entityData.get(WEAPON_POWER) - 80));
		entityData.set(COMBAT_SHOT_TICK, tickCount);
		setCombatState(CombatState.MISSILE_EGRESS);
	}

	private void tickRam(ServerLevel level, LivingEntity target) {
		if (distanceTo(target) > 1.35) return;
		float impact = (float)Mth.clamp(8.0 + getDeltaMovement().length() * 8.0, 8.0, 16.0);
		MorrowgearCombatDamage.apply(level, target, level.damageSources().mobAttack(this), impact);
		hurtServer(level, level.damageSources().generic(), impact * 0.62f);
		beginCombatRejoin(level, "RAM COMPLETE");
	}

	private boolean lineClear(ServerLevel level, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
			ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
	}

	private Vec3 combatMuzzlePosition() {
		double yaw = Math.toRadians(getYRot());
		Vec3 forward = new Vec3(Math.sin(yaw), 0, -Math.cos(yaw));
		return position().add(forward.scale(0.72)).add(0, 0.22, 0);
	}

	private Vec3 laserOutletPosition() {
		double yaw = Math.toRadians(getYRot());
		Vec3 forward = new Vec3(Math.sin(yaw), 0, -Math.cos(yaw));
		return position().add(forward.scale(0.08)).add(0, 0.08, 0);
	}

	private Vec3 combatFlightTarget(ServerLevel level, ServerPlayer owner) {
		LivingEntity target = combatTarget(level);
		if (target == null) return position();
		Vec3 center = target.position().add(0, target.getBbHeight() * 0.5, 0);
		return switch (combatState()) {
			case FLARE_ENTRY -> {
				if (combatWeapon() == CombatWeapon.AUTOCANNON) {
					CasElement element = autocannonElement(level);
					Vec3 maneuverCenter = autocannonManeuverCenter(level, target);
					Vec3 attackAxis = combatAttackAxis(owner, target, maneuverCenter);
					Vec3 breakaway = autocannonBreakawayTarget(target, attackAxis, element);
					if (breakaway != null) yield breakaway;
					yield CombatPolicy.casFormation(maneuverCenter, attackAxis,
						element.index(), element.count(), element.elapsed(), element.airspace());
				}
				yield CombatPolicy.entryApproach(center, position(), combatAirspaceSlot(level));
			}
			case GUN_RUN -> {
				CasElement element = autocannonElement(level);
				Vec3 maneuverCenter = autocannonManeuverCenter(level, target);
				Vec3 attackAxis = combatAttackAxis(owner, target, maneuverCenter);
				Vec3 breakaway = autocannonBreakawayTarget(target, attackAxis, element);
				if (breakaway != null) yield breakaway;
				yield CombatPolicy.casFormation(maneuverCenter, attackAxis,
					element.index(), element.count(), element.elapsed(), element.airspace());
			}
			case LASER_CHARGE, LASER_FIRE -> {
				LaserElement element = laserElement(level);
				yield laserFormationSlot(level, center, element.index(), element.count(),
					level.getGameTime(), element.sharedCharge(), element.airspace());
			}
			case MISSILE_APPROACH -> CombatPolicy.missileApproach(center, position(),
				combatSlot(), combatCount(), combatAirspaceSlot(level));
			case MISSILE_EGRESS, REJOIN -> CombatPolicy.egress(center, position(), combatAirspaceSlot(level));
			case RAM_APPROACH -> center;
			default -> position();
		};
	}

	private Vec3 autocannonManeuverCenter(ServerLevel level, LivingEntity target) {
		Vec3 observed = target.position().add(0, target.getBbHeight() * 0.55, 0);
		if (casTrackedTargetId != target.getId()) {
			casTrackedTargetId = target.getId();
			casManeuverCenter = observed;
			casTrackTick = level.getGameTime();
			casBreakawayActive = false;
			casBreakawayPoint = null;
			casBreakawayDirection = null;
			return observed;
		}
		if (casTrackTick != level.getGameTime()) {
			casManeuverCenter = CombatPolicy.stabilizeCasCenter(casManeuverCenter,
				observed, target.getDeltaMovement());
			casTrackTick = level.getGameTime();
		}
		return casManeuverCenter == null ? observed : casManeuverCenter;
	}

	private Vec3 autocannonBreakawayTarget(LivingEntity target, Vec3 attackAxis, CasElement element) {
		Vec3 targetCenter = target.position().add(0, target.getBbHeight() * 0.5, 0);
		double bodyDistance = position().distanceTo(targetCenter);
		if (casBreakawayActive && bodyDistance >= CombatPolicy.CAS_BREAKAWAY_EXIT_DISTANCE) {
			casBreakawayActive = false;
			casBreakawayPoint = null;
			casBreakawayDirection = null;
		}
		if (!casBreakawayActive && bodyDistance <= 3.0) {
			Vec3 away = position().subtract(target.position()).multiply(1, 0, 1);
			if (away.lengthSqr() < 0.01) away = getDeltaMovement().multiply(1, 0, 1);
			if (away.lengthSqr() < 0.01) away = attackAxis.scale(-1).multiply(1, 0, 1);
			casBreakawayDirection = away.normalize();
			casBreakawayPoint = CombatPolicy.casBreakawayPoint(position(), casBreakawayDirection,
				element.index(), element.count());
			casBreakawayActive = true;
		}
		if (!casBreakawayActive) return null;
		if (casBreakawayPoint == null || position().distanceTo(casBreakawayPoint) < 2.5) {
			casBreakawayPoint = CombatPolicy.casBreakawayPoint(position(), casBreakawayDirection,
				element.index(), element.count());
		}
		return casBreakawayPoint;
	}

	private CasElement autocannonElement(ServerLevel level) {
		int count = Math.max(1, combatCount());
		int index = Mth.clamp(combatSlot(), 0, count - 1);
		long epoch = CombatManeuverRegistry.epoch(level.dimension().toString(), ownerId(),
			combatTargetId(), combatManeuverKey(), level.getGameTime());
		return new CasElement(index, count,
			Math.max(0L, level.getGameTime() - epoch), combatAirspaceSlot(level));
	}

	private List<DroneEntity> laserFormationMembers(ServerLevel level) {
		return MorrowgearDrone.ownedDrones(level,
			level.getServer().getPlayerList().getPlayer(ownerId()), 512).stream()
			.filter(drone -> drone.combatActive()
				&& drone.combatTargetId() == combatTargetId()
				&& drone.combatWeapon() == CombatWeapon.LASER
				&& sharesCombatChannel(drone))
			.sorted(Comparator.comparingInt(DroneEntity::combatSlot)
				.thenComparing(DroneEntity::unitId)).toList();
	}

	private boolean laserFormationReady(ServerLevel level) {
		List<DroneEntity> members = laserFormationMembers(level);
		if (members.isEmpty()) return false;
		LivingEntity target = combatTarget(level);
		if (target == null) return false;
		Vec3 center = target.position().add(0, target.getBbHeight() * 0.5, 0);
		int readyCount = 0;
		boolean formationStarted = false;
		long earliestReady = Long.MAX_VALUE;
		for (int index = 0; index < members.size(); index++) {
			DroneEntity member = members.get(index);
			formationStarted |= member.combatState() == CombatState.LASER_FIRE;
			if (member.laserReadySinceTick >= 0L) earliestReady = Math.min(earliestReady,
				member.laserReadySinceTick);
			if (member.combatState() != CombatState.LASER_FIRE && member.combatCharge() < 1000) continue;
			CombatPolicy.AirspaceSlot airspace = member.combatAirspaceSlot(level);
			Vec3 slot = member.laserFormationSlot(level, center, index, members.size(),
				level.getGameTime(), 1000, airspace);
			if (member.position().distanceTo(slot) <= 1.65
				&& member.lineClear(level, member.laserOutletPosition(), center)) readyCount++;
		}
		int ownIndex = members.indexOf(this);
		if (ownIndex < 0) return false;
		Vec3 ownSlot = laserFormationSlot(level, center, ownIndex, members.size(),
			level.getGameTime(), 1000, combatAirspaceSlot(level));
		long waitTicks = earliestReady == Long.MAX_VALUE ? 0L : level.getGameTime() - earliestReady;
		double ownSlotError = position().distanceTo(ownSlot);
		boolean ownLineClear = lineClear(level, laserOutletPosition(), center);
		if (ownSlotError > 1.65 || !ownLineClear) {
			return CombatPolicy.laserFallbackReleaseReady(waitTicks,
				position().distanceTo(center), ownSlotError, ownLineClear);
		}
		return CombatPolicy.laserReleaseReady(members.size(), readyCount, formationStarted, waitTicks);
	}

	private int laserSynchronizedCount(ServerLevel level, LivingEntity target) {
		List<DroneEntity> members = laserFormationMembers(level);
		if (members.isEmpty()) return 1;
		Vec3 center = target.position().add(0, target.getBbHeight() * 0.6, 0);
		int synchronizedUnits = 0;
		for (int index = 0; index < members.size(); index++) {
			DroneEntity member = members.get(index);
			if (member.combatState() != CombatState.LASER_FIRE) continue;
			CombatPolicy.AirspaceSlot airspace = member.combatAirspaceSlot(level);
			Vec3 slot = member.laserFormationSlot(level, center, index, members.size(),
				level.getGameTime(), 1000, airspace);
			if (member.position().distanceTo(slot) <= 2.25
				&& member.lineClear(level, member.laserOutletPosition(), center)) synchronizedUnits++;
		}
		return Math.max(1, synchronizedUnits);
	}

	private LaserElement laserElement(ServerLevel level) {
		List<DroneEntity> members = laserFormationMembers(level);
		int index = members.indexOf(this);
		int orbitCharge = smoothedLaserOrbitCharge(level);
		if (index < 0) return new LaserElement(0, 1, orbitCharge, combatAirspaceSlot(level));
		return new LaserElement(index, Math.max(1, members.size()), orbitCharge, combatAirspaceSlot(level));
	}

	private int smoothedLaserOrbitCharge(ServerLevel level) {
		long now = level.getGameTime();
		long elapsed = laserOrbitChargeTick < 0L ? 1L : Math.max(1L, now - laserOrbitChargeTick);
		int desired = combatState() == CombatState.LASER_FIRE ? 1000 : combatCharge();
		laserOrbitCharge = CombatPolicy.smoothLaserOrbitCharge(laserOrbitCharge, desired, elapsed);
		laserOrbitChargeTick = now;
		return laserOrbitCharge;
	}

	private Vec3 laserFormationSlot(ServerLevel level, Vec3 center, int index, int count,
		long tick, int charge, CombatPolicy.AirspaceSlot airspace) {
		List<DroneEntity> members = laserFormationMembers(level);
		Vec3 ownBase = CombatPolicy.laserOrbit(center, index, count, tick, charge, airspace);
		for (int lift = 0; lift <= 16; lift++) {
			boolean clear = true;
			for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
				DroneEntity member = members.get(memberIndex);
				Vec3 candidate = CombatPolicy.laserOrbit(center, memberIndex, members.size(),
					tick, charge, airspace).add(0, lift, 0);
				AABB moved = member.getBoundingBox().move(candidate.subtract(member.position())).deflate(0.06);
				if (level.getBlockCollisions(member, moved).iterator().hasNext()) {
					clear = false;
					break;
				}
			}
			if (clear) return ownBase.add(0, smoothLaserClearanceLift(level, lift), 0);
		}
		return ownBase.add(0, smoothLaserClearanceLift(level, 16), 0);
	}

	private double smoothLaserClearanceLift(ServerLevel level, double desired) {
		long now = level.getGameTime();
		if (laserClearanceLiftTick == now) return laserClearanceLift;
		long elapsed = laserClearanceLiftTick < 0L ? 1L : Math.max(1L, now - laserClearanceLiftTick);
		double maximumStep = 0.12 * elapsed;
		laserClearanceLift += Mth.clamp(desired - laserClearanceLift, -maximumStep, maximumStep);
		laserClearanceLiftTick = now;
		return laserClearanceLift;
	}

	Vec3 laserOrbitSlotForVerification(ServerLevel level) {
		LivingEntity target = combatTarget(level);
		if (target == null || !laserTargetLocked()) return position();
		LaserElement element = laserElement(level);
		Vec3 center = target.position().add(0, target.getBbHeight() * 0.5, 0);
		return laserFormationSlot(level, center, element.index(), element.count(),
			level.getGameTime(), element.sharedCharge(), element.airspace());
	}

	double laserOrbitRelativeHeightForVerification(ServerLevel level) {
		LivingEntity target = combatTarget(level);
		if (target == null || !laserTargetLocked()) return 0.0;
		Vec3 center = target.position().add(0, target.getBbHeight() * 0.5, 0);
		return laserOrbitSlotForVerification(level).y - center.y;
	}

	String laserDiagnosticForVerification(ServerLevel level) {
		LivingEntity target = combatTarget(level);
		if (target == null) {
			return "state=" + combatState() + " target=none charge=" + combatCharge();
		}
		Vec3 center = target.position().add(0, target.getBbHeight() * 0.5, 0);
		LaserElement element = laserElement(level);
		Vec3 slot = laserFormationSlot(level, center, element.index(), element.count(),
			level.getGameTime(), element.sharedCharge(), element.airspace());
		return "state=" + combatState()
			+ " target=" + target.getId()
			+ " charge=" + combatCharge()
			+ " power=" + weaponPowerPercent()
			+ " range=" + String.format(java.util.Locale.ROOT, "%.2f", position().distanceTo(center))
			+ " slotError=" + String.format(java.util.Locale.ROOT, "%.2f", position().distanceTo(slot))
			+ " relY=" + String.format(java.util.Locale.ROOT, "%.2f", slot.y - center.y)
			+ " lane=" + element.airspace().index()
			+ " line=" + lineClear(level, laserOutletPosition(), center);
	}

	private CombatPolicy.AirspaceSlot combatAirspaceSlot(ServerLevel level) {
		if (combatTargetId() < 0) return CombatPolicy.AirspaceSlot.single();
		return CombatAirspaceRegistry.reserve(level.dimension().toString(), ownerId(), combatTargetId(),
			combatManeuverKey(), level.getGameTime());
	}

	private void beginWeaponRecharge() {
		beginWeaponRecharge(CombatPolicy.weaponServiceNeed(securityLoadout(),
			weaponPowerPercent(), gunAmmo(), missiles(), laserHeat()));
	}

	private void beginWeaponRecharge(DroneServicePolicy.Need reason) {
		beginServiceReturn(reason == null || reason == DroneServicePolicy.Need.NONE
			? DroneServicePolicy.Need.WEAPON_POWER : reason);
	}

	private void beginServiceReturn(DroneServicePolicy.Need reason) {
		if (!hasDock()) {
			if (combatActive() && level() instanceof ServerLevel serverLevel)
				beginCombatRejoin(serverLevel, "SERVICE UNAVAILABLE");
			return;
		}
		DroneServicePolicy.Need requested = reason == null
			? DroneServicePolicy.Need.FLIGHT_POWER : reason;
		if (solarServiceAssigned()) clearSolarService(false);
		if (salvageTowCommitted()) {
			queuedServiceReason = DroneServicePolicy.merge(queuedServiceReason, requested);
			entityData.set(DATA_LINK_STATUS, "SALVAGE TOW / SERVICE QUEUED / "
				+ queuedServiceReason.name());
			return;
		}
		if (!serviceReturn) {
			taskStack.suspend(currentTaskSnapshot());
			serviceResumeMode = mode().id();
			rechargeReclaimTargetId = combatTargetId() >= 0 ? combatTargetId() : emergencyTargetId;
			Entity reclaimTarget = rechargeReclaimTargetId < 0 ? null : level().getEntity(rechargeReclaimTargetId);
			rechargeReclaimTargetUuid = reclaimTarget == null ? null : reclaimTarget.getUUID();
			rechargeReclaimSlot = combatActive() ? combatSlot() : emergencyInterceptSlot;
			rechargeReliefUnitUuid = null;
			reliefMissionInheritedFrom = "";
		}
		serviceReturn = true;
		serviceReason = requested;
		releaseCargoAccess();
		suspendFieldWorkForService();
		clearCombatState();
		emergencyTargetId = -1;
		emergencyTargetPosition = null;
		emergencyInterceptUntil = -1L;
		entityData.set(MODE, DroneMode.DOCK.id());
		entityData.set(DOCKED, false);
		entityData.set(DOCK_STAGE, 0);
		dockApproachDirection = null;
		entityData.set(DATA_LINK_STATUS, serviceReason.status());
	}

	private boolean salvageTowCommitted() {
		return salvageTargetId != null && switch (salvageState) {
			case HOOK, HOIST, RETURN, DELIVER -> true;
			default -> false;
		};
	}

	private void beginQueuedService() {
		if (queuedServiceReason == DroneServicePolicy.Need.NONE) return;
		DroneServicePolicy.Need queued = queuedServiceReason;
		queuedServiceReason = DroneServicePolicy.Need.NONE;
		beginServiceReturn(queued);
	}

	private DroneTaskStack.Task currentTaskSnapshot() {
		return currentTaskSnapshot(serviceReturn ? DroneMode.byId(serviceResumeMode) : mode());
	}

	private DroneTaskStack.Task currentTaskSnapshot(DroneMode snapshotMode) {
		return currentTaskSnapshot(snapshotMode, true);
	}

	private DroneTaskStack.Task currentTaskSnapshot(DroneMode snapshotMode, boolean includeCombat) {
		return currentTaskSnapshot(snapshotMode, includeCombat, true);
	}

	private DroneTaskStack.Task currentTaskSnapshot(DroneMode snapshotMode, boolean includeCombat,
		boolean includePowerLoss) {
		if (includePowerLoss && isPowerLost()) return new DroneTaskStack.Task(DroneTaskStack.Kind.POWER_LOSS,
			DroneMode.STANDBY, salvageState, salvageTargetId, missionId());
		if (salvageTargetId != null && salvageState != SalvageState.IDLE) {
			return new DroneTaskStack.Task(salvageTowCommitted()
				? DroneTaskStack.Kind.SALVAGE_TOW : DroneTaskStack.Kind.SALVAGE,
				snapshotMode, salvageState, salvageTargetId, missionId());
		}
		if (includeCombat && (combatActive() || emergencyInterceptActive())) {
			int targetId = combatTargetId() >= 0 ? combatTargetId() : emergencyTargetId;
			Entity target = targetId < 0 ? null : level().getEntity(targetId);
			return new DroneTaskStack.Task(DroneTaskStack.Kind.COMBAT, snapshotMode, salvageState,
				target == null ? null : target.getUUID(), activeAssignmentKey());
		}
		if (hasActiveFieldOperation()) return new DroneTaskStack.Task(
			DroneTaskStack.Kind.FIELD, snapshotMode, salvageState, null, fieldOrderId());
		if (role() == DroneRole.CARGO && cargoState() != CargoState.UNASSIGNED
			&& hasCargoSource() && hasCargoTarget()) return new DroneTaskStack.Task(
			DroneTaskStack.Kind.CARGO, snapshotMode, salvageState, null, cargoAssignmentKey());
		if (role() == DroneRole.ENGINEER && engineerState() != EngineerState.IDLE) return new DroneTaskStack.Task(
			DroneTaskStack.Kind.ENGINEER, snapshotMode, salvageState, engineerTargetId,
			engineerTargetLabel());
		if (hasSecurityPatrol()) return new DroneTaskStack.Task(DroneTaskStack.Kind.SECURITY_PATROL,
			snapshotMode, salvageState, null, securityOrderId());
		if ((snapshotMode == DroneMode.WAYPOINT || hasPatrolRoute()) && hasTrackingTarget()) {
			return new DroneTaskStack.Task(DroneTaskStack.Kind.TRACKING, snapshotMode,
				salvageState, trackingTargetId(), missionId());
		}
		if (snapshotMode == DroneMode.WAYPOINT || hasPatrolRoute()) return new DroneTaskStack.Task(
			DroneTaskStack.Kind.ROUTE, snapshotMode, salvageState, null, missionId());
		if (snapshotMode == DroneMode.FOLLOW || snapshotMode == DroneMode.ORBIT) return new DroneTaskStack.Task(
			DroneTaskStack.Kind.FOLLOW, snapshotMode, salvageState, null, missionId());
		return new DroneTaskStack.Task(DroneTaskStack.Kind.IDLE, snapshotMode, salvageState, null, missionId());
	}

	private void resumeServiceTask(ServerLevel level) {
		DroneTaskStack.Task task = taskStack.resume(candidate -> resolveTaskForResume(level, candidate))
			.orElse(new DroneTaskStack.Task(
			DroneTaskStack.Kind.IDLE, DroneMode.byId(serviceResumeMode), salvageState,
			salvageTargetId, missionId()));
		if ((task.kind() == DroneTaskStack.Kind.SALVAGE
			|| task.kind() == DroneTaskStack.Kind.SALVAGE_TOW) && role() == DroneRole.SALVAGE) {
			DroneEntity target = salvageTarget(level);
			if (target != null) {
				salvageState = task.salvageState();
				if (salvageState == SalvageState.RETURN || salvageState == SalvageState.DELIVER) {
					entityData.set(MODE, hasDock() ? DroneMode.DOCK.id() : DroneMode.STANDBY.id());
				} else {
					entityData.set(WAYPOINT_POS, target.blockPosition().above(3).asLong());
					entityData.set(HAS_WAYPOINT, true);
					entityData.set(MODE, DroneMode.WAYPOINT.id());
				}
				entityData.set(DATA_LINK_STATUS, "SERVICE COMPLETE / SALVAGE RESUME / "
					+ salvageState.name());
				return;
			}
			cancelSalvageMission();
		}
		if (task.kind() == DroneTaskStack.Kind.COMBAT && resumeCombatTask(level, task)) return;
		entityData.set(MODE, task.mode().id());
		entityData.set(DATA_LINK_STATUS, "SERVICE COMPLETE / " + task.kind().name() + " RESUME");
	}

	private java.util.Optional<DroneTaskStack.Task> resolveTaskForResume(ServerLevel level,
		DroneTaskStack.Task task) {
		return switch (task.kind()) {
			case COMBAT -> resolveCombatTask(level, task);
			case TRACKING -> resolveTrackingTask(level, task);
			case ROUTE -> hasWaypoint() && task.missionId().equals(missionId())
				? java.util.Optional.of(task) : java.util.Optional.empty();
			case FOLLOW -> task.missionId().isBlank() || task.missionId().equals(missionId())
				? java.util.Optional.of(task) : java.util.Optional.empty();
			case FIELD -> hasActiveFieldOperation() && task.missionId().equals(fieldOrderId())
				? java.util.Optional.of(task) : java.util.Optional.empty();
			case CARGO -> resolveCargoTask(level, task);
			case ENGINEER -> role() == DroneRole.ENGINEER
				? java.util.Optional.of(task) : java.util.Optional.empty();
			case SECURITY_PATROL -> hasSecurityPatrol() && task.missionId().equals(securityOrderId())
				? java.util.Optional.of(task) : java.util.Optional.empty();
			case SALVAGE, SALVAGE_TOW -> salvageTarget(level) != null
				&& java.util.Objects.equals(task.targetId(), salvageTargetId)
				? java.util.Optional.of(task) : java.util.Optional.empty();
			case POWER_LOSS -> isPowerLost() ? java.util.Optional.of(task) : java.util.Optional.empty();
			default -> java.util.Optional.of(task);
		};
	}

	private String activeAssignmentKey() {
		if (hasActiveFieldOperation()) return "FIELD:" + fieldOrderId();
		if (hasSecurityPatrol()) return "SECURITY:" + securityOrderId();
		return "MISSION:" + missionId();
	}

	private java.util.Optional<DroneTaskStack.Task> resolveCombatTask(ServerLevel level,
		DroneTaskStack.Task task) {
		if (role() != DroneRole.SECURITY || task.targetId() == null
			|| rechargeReclaimTargetId < 0) return java.util.Optional.empty();
		Entity entity = level.getEntityInAnyDimension(task.targetId());
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId());
		if (!(entity instanceof LivingEntity target) || !target.isAlive() || owner == null) {
			return java.util.Optional.empty();
		}
		LivingEntity recentAttacker = owner.tickCount - owner.getLastHurtByMobTimestamp() <= 200
			? owner.getLastHurtByMob() : null;
		return ThreatAssessment.targetDisposition(target, owner, recentAttacker).engageable()
			? java.util.Optional.of(task) : java.util.Optional.empty();
	}

	private boolean resumeCombatTask(ServerLevel level, DroneTaskStack.Task task) {
		Entity entity = task.targetId() == null ? null : level.getEntityInAnyDimension(task.targetId());
		if (!(entity instanceof LivingEntity target) || !target.isAlive()) return false;
		entityData.set(MODE, task.mode().id());
		entityData.set(COMBAT_TARGET, target.getId());
		entityData.set(COMBAT_CHARGE, 0);
		setCombatState(CombatState.FLARE_ENTRY);
		entityData.set(DATA_LINK_STATUS, "SERVICE COMPLETE / COMBAT RESUME / " + target.getName().getString());
		return true;
	}

	private String cargoAssignmentKey() {
		return hasCargoSource() && hasCargoTarget()
			? cargoSource().asLong() + ">" + cargoTarget().asLong() : "";
	}

	private java.util.Optional<DroneTaskStack.Task> resolveCargoTask(ServerLevel level,
		DroneTaskStack.Task task) {
		if (role() != DroneRole.CARGO || cargoPaused() || !hasCargoSource() || !hasCargoTarget()
			|| !task.missionId().equals(cargoAssignmentKey())) return java.util.Optional.empty();
		boolean sourceLoaded = level.hasChunkAt(cargoSource());
		boolean targetLoaded = level.hasChunkAt(cargoTarget());
		boolean sourceMissing = sourceLoaded && !(level.getBlockEntity(cargoSource()) instanceof Container);
		boolean targetMissing = targetLoaded && !(level.getBlockEntity(cargoTarget()) instanceof Container);
		if ((!cargo.isEmpty() && !targetMissing) || (!sourceMissing && !targetMissing))
			return java.util.Optional.of(task);
		releaseCargoAccess();
		entityData.set(CARGO_PAUSED, true);
		entityData.set(CARGO_STATE, CargoState.UNASSIGNED.id());
		entityData.set(CARGO_QUEUE_POSITION, 0);
		entityData.set(DATA_LINK_STATUS, targetMissing
			? "CARGO TARGET LOST / LOAD SECURED" : "CARGO SOURCE LOST / ROUTE SUSPENDED");
		return java.util.Optional.of(new DroneTaskStack.Task(DroneTaskStack.Kind.IDLE,
			hasDock() ? DroneMode.DOCK : DroneMode.STANDBY, SalvageState.IDLE, null, ""));
	}

	private java.util.Optional<DroneTaskStack.Task> resolveTrackingTask(ServerLevel level,
		DroneTaskStack.Task task) {
		Entity target = task.targetId() == null ? null : level.getEntityInAnyDimension(task.targetId());
		if (target instanceof LivingEntity living && living.isAlive() && living.level() == level
			&& hasTrackingTarget() && task.targetId().equals(trackingTargetId())
			&& task.missionId().equals(missionId())) return java.util.Optional.of(task);
		if (!hasWaypoint() || !task.missionId().equals(missionId())) return java.util.Optional.empty();
		entityData.set(TRACK_TARGET, "");
		return java.util.Optional.of(new DroneTaskStack.Task(DroneTaskStack.Kind.ROUTE,
			DroneMode.WAYPOINT, SalvageState.IDLE, null, task.missionId()));
	}

	private void suspendFieldWorkForService() {
		if (fieldWorkTarget != null && level() instanceof ServerLevel serverLevel) {
			FieldOperationRegistry.Operation operation = FieldOperationRegistry.find(
				serverLevel.dimension().toString(), ownerId(), fieldOrderId(), serverLevel.getGameTime());
			if (operation != null) operation.retryTarget(fieldWorkTarget, serverLevel.getGameTime());
		}
		fieldWorkTarget = null;
		fieldWorkApproach = null;
		fieldReplantTarget = null;
		fieldPickupTarget = null;
	}

	private String combatManeuverKey() {
		String channel = emergencyInterceptActive()
			? "INTERCEPT:" + emergencyTargetId
			: hasActiveFieldOperation() ? "FIELD:" + fieldOrderId()
			: "PATROL:" + entityData.get(SECURITY_ORDER);
		return groupId() + '|' + channel + '|' + combatWeapon().name();
	}

	private Vec3 combatAttackAxis(ServerPlayer owner, LivingEntity target, Vec3 center) {
		Vec3 reference = hasSecurityPatrol()
			? Vec3.atCenterOf(securityAnchor()) : owner == null ? position() : owner.position();
		Vec3 axis = center.subtract(reference).multiply(1, 0, 1);
		if (axis.lengthSqr() < 1.0) {
			int seed = target == null ? combatTargetId() : target.getId();
			double angle = Math.floorMod(seed * 47, 360) * Mth.DEG_TO_RAD;
			axis = new Vec3(Math.sin(angle), 0, Math.cos(angle));
		}
		if (combatWeapon() != CombatWeapon.AUTOCANNON || target == null) return axis;
		return CombatManeuverRegistry.axis(level().dimension().toString(), ownerId(), target.getId(),
			combatManeuverKey(), axis, level().getGameTime());
	}

	private record CasElement(int index, int count, long elapsed, CombatPolicy.AirspaceSlot airspace) {
		private CasElement(int index, int count, long elapsed) {
			this(index, count, elapsed, CombatPolicy.AirspaceSlot.single());
		}
	}
	private record LaserElement(int index, int count, int sharedCharge,
		CombatPolicy.AirspaceSlot airspace) {}

	private Vec3 emergencyInterceptTarget(ServerLevel level) {
		Entity target = emergencyTargetId < 0 ? null : level.getEntity(emergencyTargetId);
		Vec3 threat = target instanceof LivingEntity living && living.isAlive()
			? living.position() : emergencyTargetPosition;
		if (threat == null) return position();
		Vec3 approach = threat.subtract(position()).multiply(1, 0, 1);
		if (approach.lengthSqr() < 0.001) approach = new Vec3(0, 0, 1);
		Vec3 right = new Vec3(-approach.z, 0, approach.x).normalize();
		double slotOffset = (emergencyInterceptSlot - (Math.max(1, emergencyInterceptCount) - 1) / 2.0) * 1.8;
		return threat.subtract(approach.normalize().scale(2.8)).add(right.scale(slotOffset)).add(0, 2.2, 0);
	}

	private void repelEmergencyThreats(ServerLevel level) {
		if (tickCount % 3 != 0) return;
		for (LivingEntity hostile : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(1.9),
			entity -> entity instanceof Enemy && entity.isAlive())) {
			Vec3 outward = hostile.position().subtract(position()).multiply(1, 0, 1);
			if (outward.lengthSqr() < 0.001) outward = new Vec3(0, 0, 1);
			outward = outward.normalize().scale(0.4);
			hostile.push(outward.x, 0.16, outward.z);
		}
	}

	private void repelSecurityThreats(ServerLevel level) {
		if (tickCount % 3 != 0) return;
		Vec3 anchor = Vec3.atCenterOf(securityAnchor());
		for (LivingEntity hostile : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(1.7),
			entity -> entity instanceof Enemy && entity.isAlive()
				&& entity.position().distanceToSqr(anchor) <= securityRadius() * securityRadius())) {
			Vec3 outward = hostile.position().subtract(anchor).multiply(1, 0, 1);
			if (outward.lengthSqr() < 0.001) outward = new Vec3(0, 0, 1);
			outward = outward.normalize().scale(0.32);
			hostile.push(outward.x, 0.12, outward.z);
		}
	}

	private void repelFieldThreats(ServerLevel level) {
		if (tickCount % 3 != 0) return;
		Vec3 center = Vec3.atCenterOf(fieldAnchor());
		for (LivingEntity hostile : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(1.9),
			entity -> entity instanceof Enemy && entity.isAlive()
				&& entity.position().distanceToSqr(center) <= Math.max(8, fieldRadius()) * Math.max(8, fieldRadius()))) {
			Vec3 outward = hostile.position().subtract(center).multiply(1, 0, 1);
			if (outward.lengthSqr() < 0.001) outward = new Vec3(0, 0, 1);
			outward = outward.normalize().scale(0.36);
			hostile.push(outward.x, 0.14, outward.z);
		}
	}

	private Vec3 targetPosition(ServerLevel level, ServerPlayer owner, int index, int total,
		NavigationEnvironment.Snapshot environment) {
		if (combatActive()) return combatFlightTarget(level, owner);
		if (emergencyInterceptActive()) return emergencyInterceptTarget(level);
		Vec3 solarTarget = solarServiceTarget(level);
		if (solarTarget != null) return solarTarget;
		if (hasSecurityPatrol()) return securityTargetPosition(level);
		if (hasActiveFieldOperation()) {
			List<DroneEntity> fieldMembers = fieldMissionMembers(
				MorrowgearDrone.ownedDrones(level, owner, 512));
			if (role() == DroneRole.ENGINEER && fieldWorkTarget != null)
				return fieldWorkApproach == null
					? selectFieldWorkPosition(level, fieldWorkTarget) : fieldWorkApproach;
			if (role() == DroneRole.ENGINEER && fieldReplantTarget != null)
				return Vec3.atCenterOf(fieldReplantTarget.pos()).add(0, 2.0, 0);
			if (role() == DroneRole.CARGO && fieldOperationState() == FieldOperationState.DELIVERING
				&& !cargo.isEmpty() && hasCargoTarget())
				return Vec3.atCenterOf(cargoTarget()).add(0, 2.0, 0);
			if (role() == DroneRole.CARGO && fieldPickupTarget != null)
				return Vec3.atCenterOf(fieldPickupTarget).add(0, 1.5, 0);
			return fieldRoleTarget(level, fieldMembers);
		}
		DroneEntity engineerTarget = engineerTarget(level);
		if (engineerState() == EngineerState.APPROACH && engineerTarget != null) {
			double angle = (getUUID().hashCode() & 0xffff) / 65535.0 * Math.PI * 2.0;
			return engineerTarget.position().add(Math.cos(angle) * 2.2, 1.1, Math.sin(angle) * 2.2);
		}
		FollowFormationLayout.Slot followSlot = null;
		List<DroneEntity> followFormation = List.of();
		if (mode() == DroneMode.FOLLOW && !cohortId().isBlank()) {
			followFormation = MorrowgearDrone.ownedDrones(level, owner, 512).stream()
				.filter(drone -> drone.mode() == DroneMode.FOLLOW && drone.cohortId().equals(cohortId()))
				.sorted(Comparator.comparingInt(DroneEntity::cohortRank).thenComparing(DroneEntity::unitId))
				.toList();
			int followIndex = followFormation.indexOf(this);
			if (followIndex >= 0) {
				followSlot = FollowFormationLayout.slotFor(followIndex,
					followFormation.stream().map(drone -> drone.recoveryLevel() > 0).toList());
				index = followSlot.index();
				total = followSlot.count();
			}
		}
		double phase = (getUUID().hashCode() & 0xffff) / 65535.0 * Math.PI * 2.0;
		AdaptiveFollowFormation.Profile profile = AdaptiveFollowFormation.profile(total,
			environment.openness(), environment.ceilingClearance(), environment.skyVisible(),
			environment.fluidNearby(), environment.dark(), environment.ownerSpeed());
		Vec3 heading = environment.travelHeading();
		double orbitPhase = ensureFollowOrbitPhase(followFormation, owner, profile, heading,
			level.getGameTime());
		if (mode() == DroneMode.FOLLOW && missionStage() == MISSION_ORBIT_ENTRY) {
			double progress = orbitEntryProgress(level.getGameTime());
			Vec3 offset = AdaptiveFollowFormation.orbitEntryOffset(profile, index, total,
				level.getGameTime(), heading, progress, orbitPhase);
			if (progress >= 1.0) entityData.set(MISSION_STAGE, MISSION_MOVING);
			return owner.position().add(offset);
		}
		if (mode() == DroneMode.ORBIT) {
			return owner.position().add(AdaptiveFollowFormation.offset(profile, index, total,
				level.getGameTime() + (long) (phase * 20.0), heading));
		}
		if (mode() == DroneMode.RETURN) {
			Vec3 returnOffset = heading.lengthSqr() < 0.001 ? new Vec3(Math.cos(phase), 0, Math.sin(phase)) : heading.normalize();
			return owner.position().add(returnOffset.scale(-1.35)).add(0, Math.max(1.4, profile.baseHeight()), 0);
		}
		if (mode() == DroneMode.WAYPOINT && entityData.get(HAS_WAYPOINT)) {
			return waypointTarget(level);
		}
		return owner.position().add(AdaptiveFollowFormation.offset(profile, index, total,
			level.getGameTime(), heading, orbitPhase));
	}

	private boolean solarServiceActive() {
		return solarServiceStationId != null && solarServiceSlot >= 0;
	}

	private boolean solarServiceAssigned() {
		return solarServiceStationId != null;
	}

	private void updateSolarService(ServerLevel level, ServerPlayer owner) {
		if (isDocked() || serviceReturn || combatActive() || emergencyInterceptActive()) {
			if (solarServiceAssigned()) clearSolarService(false);
			return;
		}
		if (solarServiceAssigned()) {
			Entity station = level.getEntity(solarServiceStationId);
			if (!(station instanceof SolarServiceStationEntity solar) || !solar.isAlive()
				|| (solarServiceActive()
					&& SolarServicePolicy.chargeComplete(batteryPercent(), solarChargeTargetPercent()))) clearSolarService();
			else {
				int renewed = SolarServiceRegistry.request(solar.getUUID(), getUUID(), batteryPercent(),
					level.getGameTime());
				if (renewed != solarServiceSlot) resetSolarOrbit(level.getGameTime());
				solarServiceSlot = renewed;
				if (renewed >= 0) {
					entityData.set(DATA_LINK_STATUS, "SOLAR DIVERT / SLOT " + (renewed + 1)
						+ " / " + batteryPercent() + "%");
				} else {
					int queue = SolarServiceRegistry.waitingPosition(solar.getUUID(), getUUID(), level.getGameTime());
					entityData.set(DATA_LINK_STATUS, "SOLAR HOLD / QUEUE " + Math.max(1, queue)
						+ " / " + batteryPercent() + "%");
				}
			}
			return;
		}
		double missionDistance = solarMissionDistance(level, owner);
		int diversionThreshold = SolarMissionPolicy.diversionThreshold(hasPatrolRoute(),
			salvageState == SalvageState.INTERCEPT, salvageState == SalvageState.RETURN, missionDistance);
		if (batteryPercent() > diversionThreshold) return;
		DroneServicePolicy.Need need = DroneServicePolicy.serviceNeed(getHealth() / getMaxHealth(),
			batteryPercent(), hasDock() ? position().distanceTo(Vec3.atCenterOf(dockPos())) : Double.MAX_VALUE);
		if (role() == DroneRole.SECURITY) need = DroneServicePolicy.merge(need,
			CombatPolicy.weaponServiceNeed(securityLoadout(), weaponPowerPercent(), gunAmmo(), missiles(), laserHeat()));
		if (need == DroneServicePolicy.Need.NONE) need = DroneServicePolicy.Need.FLIGHT_POWER;
		Vec3 mission = hasWaypoint() ? Vec3.atCenterOf(waypointPos()) : owner.position();
		double baseDistance = hasDock() ? position().distanceTo(Vec3.atCenterOf(dockPos())) : Double.MAX_VALUE;
		List<SolarServicePolicy.Candidate> candidates = SolarServiceRegistry.candidates(ownerId(),
			level.dimension().toString(), getX(), getY(), getZ(), mission.x, mission.y, mission.z,
			level.getGameTime());
		SolarServicePolicy.ServicePlan plan = SolarServicePolicy.plan(need, batteryPercent(), hasDock(),
			baseDistance, missionDistance, diversionThreshold, candidates);
		UUID stationId = plan.destination() == SolarServicePolicy.Destination.SOLAR_DOCK
			? plan.solarDockId() : SolarServicePolicy.selectQueueCandidate(batteryPercent(), baseDistance,
				missionDistance, candidates).map(SolarServicePolicy.Candidate::id).orElse(null);
		if (stationId == null) return;
		if (!solarTaskSuspended) {
			taskStack.suspend(currentTaskSnapshot());
			solarTaskSuspended = true;
		}
		int slot = SolarServiceRegistry.request(stationId, getUUID(), batteryPercent(), level.getGameTime());
		solarServiceStationId = plan.solarDockId();
		if (solarServiceStationId == null) solarServiceStationId = stationId;
		solarServiceSlot = slot;
		resetSolarOrbit(level.getGameTime());
		entityData.set(DATA_LINK_STATUS, slot >= 0 ? "SOLAR DIVERT / SLOT " + (slot + 1)
			: "SOLAR HOLD / QUEUE " + Math.max(1,
				SolarServiceRegistry.waitingPosition(stationId, getUUID(), level.getGameTime())));
	}

	int solarChargeTargetPercent() {
		return SolarMissionPolicy.chargeTarget(hasPatrolRoute(), salvageState == SalvageState.INTERCEPT,
			salvageState == SalvageState.RETURN);
	}

	private double solarMissionDistance(ServerLevel level, ServerPlayer owner) {
		DroneEntity load = salvageTarget(level);
		if (load != null) {
			if (salvageState == SalvageState.RETURN) {
				return hasDock() ? position().distanceTo(Vec3.atCenterOf(dockPos())) : 0.0;
			}
			double outbound = position().distanceTo(load.position());
			return hasDock() ? outbound + load.position().distanceTo(Vec3.atCenterOf(dockPos())) : outbound;
		}
		if (hasWaypoint()) {
			Vec3 current = Vec3.atCenterOf(waypointPos());
			double distance = position().distanceTo(current);
			if (patrolRouteSize() > 1) {
				List<BlockPos> route = patrolRoutePoints();
				BlockPos next = route.get(PatrolRoutePolicy.nextIndex(patrolRouteIndex(), route.size()));
				distance += current.distanceTo(Vec3.atCenterOf(next));
			}
			return distance;
		}
		return position().distanceTo(owner.position());
	}

	private Vec3 solarServiceTarget(ServerLevel level) {
		if (!solarServiceAssigned()) return null;
		Entity entity = level.getEntity(solarServiceStationId);
		if (!(entity instanceof SolarServiceStationEntity station) || !station.isAlive()) {
			clearSolarService();
			return null;
		}
		int queuePosition = solarServiceSlot >= 0 ? 0
			: Math.max(1, SolarServiceRegistry.waitingPosition(station.getUUID(), getUUID(), level.getGameTime()));
		SolarOrbitGuidance.Profile profile = SolarOrbitGuidance.profile(solarServiceSlot, queuePosition);
		initializeSolarOrbit(station.position(), level.getGameTime());
		Vec3 insertion = SolarOrbitGuidance.insertionTarget(station.position(), solarOrbitPhase, profile);
		if (!solarOrbitEstablished && SolarOrbitGuidance.captured(position(), insertion)
			&& DroneNavigator.corridorClear(level, this, position(), insertion)) {
			solarOrbitEstablished = true;
			solarOrbitTick = level.getGameTime();
		}
		if (!solarOrbitEstablished) return insertion;
		advanceSolarOrbit(level.getGameTime(), profile);
		return SolarOrbitGuidance.orbitSample(station.position(), solarOrbitPhase, profile).target();
	}

	private SolarOrbitGuidance.Sample solarOrbitSample(ServerLevel level) {
		if (!solarServiceAssigned()) return null;
		Entity entity = level.getEntity(solarServiceStationId);
		if (!(entity instanceof SolarServiceStationEntity station) || !station.isAlive()) return null;
		int queuePosition = solarServiceSlot >= 0 ? 0
			: Math.max(1, SolarServiceRegistry.waitingPosition(station.getUUID(), getUUID(), level.getGameTime()));
		SolarOrbitGuidance.Profile profile = SolarOrbitGuidance.profile(solarServiceSlot, queuePosition);
		initializeSolarOrbit(station.position(), level.getGameTime());
		return SolarOrbitGuidance.orbitSample(station.position(), solarOrbitPhase, profile);
	}

	private void initializeSolarOrbit(Vec3 station, long gameTime) {
		if (!Double.isFinite(solarOrbitPhase)) {
			solarOrbitPhase = SolarOrbitGuidance.initialPhase(station, position(), getUUID().hashCode());
		}
		if (solarOrbitTick < 0L) solarOrbitTick = gameTime;
	}

	private void advanceSolarOrbit(long gameTime, SolarOrbitGuidance.Profile profile) {
		solarOrbitPhase = SolarOrbitGuidance.advancePhase(solarOrbitPhase,
			Math.max(0L, gameTime - solarOrbitTick), profile);
		solarOrbitTick = gameTime;
	}

	private void resetSolarOrbit(long gameTime) {
		solarOrbitPhase = Double.NaN;
		solarOrbitTick = gameTime;
		solarOrbitEstablished = false;
	}

	private void clearSolarService() {
		clearSolarService(true);
	}

	private void clearSolarService(boolean resumeMission) {
		if (solarServiceStationId != null) SolarServiceRegistry.release(getUUID());
		solarServiceStationId = null;
		solarServiceSlot = -1;
		resetSolarOrbit(-1L);
		if (solarTaskSuspended && resumeMission && level() instanceof ServerLevel serverLevel) {
			DroneTaskStack.Task task = taskStack.resume(candidate -> resolveTaskForResume(serverLevel, candidate))
				.orElse(null);
			if (task != null) entityData.set(MODE, task.mode().id());
		}
		solarTaskSuspended = false;
		entityData.set(DATA_LINK_STATUS, resumeMission
			? "SOLAR SERVICE COMPLETE / MISSION RESUME" : "SOLAR SERVICE PREEMPTED");
	}

	private Vec3 fieldRoleTarget(ServerLevel level, List<DroneEntity> members) {
		List<DroneEntity> peers = members.stream().filter(member -> member.role() == role())
			.sorted(Comparator.comparing(DroneEntity::unitId)).toList();
		int slot = Math.max(0, peers.indexOf(this));
		int count = Math.max(1, peers.size());
		double phaseBias = switch (role()) {
			case SCOUT -> 0.0;
			case CARGO -> Math.PI * 0.55;
			case SECURITY -> Math.PI;
			default -> Math.PI * 1.45;
		};
		if (fieldOperationState() == FieldOperationState.SCOUT_CARGO_ESCORT
			|| fieldOperationState() == FieldOperationState.GUARD_CARGO_ESCORT) {
			return FieldFormationPolicy.escort(fieldEscortCenter(members), slot, count,
				role() == DroneRole.SECURITY ? 3.8 : 3.0,
				role() == DroneRole.SECURITY ? 2.2 : 2.8, level.getGameTime(), phaseBias);
		}
		if (fieldOperationState() == FieldOperationState.GUARD_WORK_ESCORT) {
			return FieldFormationPolicy.escort(fieldEscortCenter(members), slot, count,
				4.2, 2.4, level.getGameTime(), phaseBias);
		}
		double radius = Math.max(6.0, Math.min(15.0, fieldRadius() * 0.58));
		double height = switch (role()) {
			case SCOUT -> 7.0;
			case CARGO -> 4.5;
			case SECURITY -> 6.0;
			default -> 3.5;
		};
		if (role() == DroneRole.SCOUT) radius += 2.5;
		else if (role() == DroneRole.SECURITY) radius += 4.0;
		return FieldFormationPolicy.orbit(Vec3.atCenterOf(fieldAnchor()), slot, count,
			radius, height, level.getGameTime(), role() == DroneRole.SCOUT ? 0.032 : 0.025, phaseBias);
	}

	private Vec3 fieldEscortCenter(List<DroneEntity> members) {
		DroneEntity cargoEscort = members.stream().filter(member -> member.role() == DroneRole.CARGO
			&& member.fieldOperationState() != FieldOperationState.COMPLETE)
			.min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
		if (cargoEscort != null && roleComplete(members, DroneRole.ENGINEER)) return cargoEscort.position();
		DroneEntity engineerEscort = members.stream().filter(member -> member.role() == DroneRole.ENGINEER
			&& member.fieldOperationState() != FieldOperationState.COMPLETE)
			.min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
		return engineerEscort == null ? Vec3.atCenterOf(fieldAnchor()).add(0, 2.0, 0)
			: engineerEscort.position();
	}

	private Vec3 securityTargetPosition(ServerLevel level) {
		List<DroneEntity> patrol = level.getEntitiesOfClass(DroneEntity.class,
			new AABB(securityAnchor()).inflate(512), drone -> drone.hasSecurityPatrol()
				&& drone.ownerId().equals(ownerId())
				&& drone.entityData.get(SECURITY_ORDER).equals(entityData.get(SECURITY_ORDER)))
			.stream().sorted(Comparator.comparing(DroneEntity::unitId)).toList();
		int slot = Math.max(0, patrol.indexOf(this));
		int count = Math.max(1, patrol.size());
		Vec3 anchor = Vec3.atCenterOf(securityAnchor());
		return SecurityPatrolPolicy.patrolPosition(anchor, slot, count, level.getGameTime(), securityRadius());
	}

	private double ensureFollowOrbitPhase(List<DroneEntity> members, ServerPlayer owner,
		AdaptiveFollowFormation.Profile profile, Vec3 heading, long gameTime) {
		if (mode() != DroneMode.FOLLOW || members.isEmpty() || profile.column()) return 0.0;
		for (DroneEntity member : members) {
			float saved = member.entityData.get(ORBIT_PHASE_OFFSET);
			if (!Float.isNaN(saved)) {
				if (Float.isNaN(entityData.get(ORBIT_PHASE_OFFSET))) entityData.set(ORBIT_PHASE_OFFSET, saved);
				return saved;
			}
		}
		DroneEntity leader = members.stream()
			.filter(member -> member.unitId().equals(cohortLeaderId())).findFirst().orElse(members.getFirst());
		Vec3 forward = heading.multiply(1, 0, 1);
		if (forward.lengthSqr() < 0.001) forward = new Vec3(0, 0, 1);
		forward = forward.normalize();
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		Vec3 relative = leader.position().subtract(owner.position())
			.subtract(forward.scale(profile.forwardBias()));
		float phase = (float) (Math.atan2(relative.dot(forward), relative.dot(right))
			- gameTime * profile.angularSpeed());
		members.forEach(member -> member.entityData.set(ORBIT_PHASE_OFFSET, phase));
		return phase;
	}

	private Vec3 navigationTarget(ServerLevel level, Vec3 requested,
		NavigationEnvironment.Snapshot environment) {
		routedFlight = false;
		strategicFlight = false;
		if (localDetourTicks > 0) localDetourTicks--;
		Vec3 safeTarget = DroneNavigator.liftOutOfFluid(level, requested);
		double targetDistance = position().distanceTo(safeTarget);
		if (strategicWaypointTicks > 0) strategicWaypointTicks--;
		updateNavigationRecovery(level, safeTarget, environment);
		if (escapeTicks > 0) {
			routedFlight = true;
			escapeTicks--;
			if (targetDistance >= STRATEGIC_NAVIGATION_DISTANCE && recoveryAttempts >= 2) {
				Vec3 strategic = strategicNavigationTarget(level, safeTarget);
				if (strategic != null) {
					strategicFlight = true;
					return strategic;
				}
			}
			if (escapeTarget == null || position().distanceTo(escapeTarget) < 0.55
				|| !DroneNavigator.corridorClear(level, this, position(), escapeTarget)) {
				escapeTarget = DroneNavigator.recoveryTarget(level, this, safeTarget, true, recoveryAttempts);
			}
			return escapeTarget;
		}
		if (DroneNavigator.corridorClear(level, this, position(), safeTarget)) {
			activeFlightPath = null;
			pathGoal = null;
			clearStrategicWaypoint();
			clearLocalDetour();
			return safeTarget;
		}
		Vec3 cachedStrategic = cachedStrategicNavigationTarget(level, safeTarget);
		if (cachedStrategic != null) {
			routedFlight = true;
			strategicFlight = true;
			clearLocalDetour();
			return cachedStrategic;
		}
		if (targetDistance < STRATEGIC_NAVIGATION_DISTANCE) clearStrategicWaypoint();
		if (targetDistance >= STRATEGIC_NAVIGATION_DISTANCE) {
			Vec3 strategic = strategicNavigationTarget(level, safeTarget);
			if (strategic != null) {
				routedFlight = true;
				strategicFlight = true;
				clearLocalDetour();
				return strategic;
			}
		}

		BlockPos goal = BlockPos.containing(safeTarget);
		pathRefreshTicks--;
		if (activeFlightPath == null || activeFlightPath.isDone() || pathRefreshTicks <= 0
			|| pathGoal == null || pathGoal.distManhattan(goal) > 4) {
			activeFlightPath = getNavigation().createPath(goal, 1);
			pathGoal = goal;
			pathRefreshTicks = 30;
		}
		Vec3 pathPoint = DroneNavigator.nextPathPoint(level, this, activeFlightPath);
		if (pathPoint != null && DroneNavigator.corridorClear(level, this, position(), pathPoint)) {
			routedFlight = true;
			clearLocalDetour();
			return DroneNavigator.liftOutOfFluid(level, pathPoint);
		}
		routedFlight = true;
		if (targetDistance >= STRATEGIC_NAVIGATION_DISTANCE) {
			Vec3 strategic = strategicNavigationTarget(level, safeTarget);
			if (strategic != null) {
				strategicFlight = true;
				clearLocalDetour();
				return strategic;
			}
		}
		boolean orbit = mode() == DroneMode.WAYPOINT
			&& (missionStage() == MISSION_ORBIT_ENTRY || missionStage() == MISSION_ORBIT);
		return stableLocalDetour(level, safeTarget, environment, orbit);
	}

	private Vec3 stableLocalDetour(ServerLevel level, Vec3 goal,
		NavigationEnvironment.Snapshot environment, boolean orbit) {
		boolean cachedCorridorClear = localDetourTarget != null
			&& DroneNavigator.corridorClear(level, this, position(), localDetourTarget);
		if (LocalDetourPolicy.reusable(position(), localDetourTarget, localDetourGoal,
			goal, localDetourTicks, cachedCorridorClear)) return localDetourTarget;

		localDetourTarget = orbit
			? DroneNavigator.localOrbitDetour(level, this, goal)
			: DroneNavigator.localDetour(level, this, goal,
				environment.fluidNearby() || goal.y >= getY());
		localDetourGoal = goal;
		localDetourTicks = LocalDetourPolicy.HOLD_TICKS;
		return localDetourTarget;
	}

	private void clearLocalDetour() {
		localDetourTarget = null;
		localDetourGoal = null;
		localDetourTicks = 0;
	}

	private void resetNavigationPlan() {
		activeFlightPath = null;
		pathGoal = null;
		pathRefreshTicks = 0;
		escapeTarget = null;
		escapeTicks = 0;
		clearStrategicWaypoint();
		clearLocalDetour();
		routeGuidanceTarget = null;
	}

	private Vec3 strategicNavigationTarget(ServerLevel level, Vec3 goal) {
		boolean goalStable = strategicGoal != null && strategicGoal.distanceTo(goal) <= 8.0;
		Vec3 cached = cachedStrategicNavigationTarget(level, goal);
		if (cached != null) return cached;

		DroneNavigator.StrategicDetour detour = DroneNavigator.strategicDetour(
			level, this, goal, goalStable ? strategicSide : 0);
		if (detour == null) {
			clearStrategicWaypoint();
			return null;
		}
		strategicWaypoint = detour.waypoint();
		strategicGoal = goal;
		strategicSide = detour.side();
		strategicWaypointTicks = detour.directExit() ? 160 : 100;
		return strategicWaypoint;
	}

	private Vec3 cachedStrategicNavigationTarget(ServerLevel level, Vec3 goal) {
		boolean goalStable = strategicGoal != null && strategicGoal.distanceTo(goal) <= 8.0;
		boolean waypointUsable = strategicWaypoint != null && goalStable && strategicWaypointTicks > 0
			&& position().distanceTo(strategicWaypoint) > 1.2
			&& DroneNavigator.corridorClear(level, this, position(), strategicWaypoint);
		return waypointUsable ? strategicWaypoint : null;
	}

	private void clearStrategicWaypoint() {
		strategicWaypoint = null;
		strategicGoal = null;
		strategicSide = 0;
		strategicWaypointTicks = 0;
	}

	private void updateNavigationRecovery(ServerLevel level, Vec3 target,
		NavigationEnvironment.Snapshot environment) {
		if (progressSample == null) {
			progressSample = position();
			progressSampleTick = tickCount;
		}
		if (tickCount - progressSampleTick >= 10) {
			boolean shouldMove = position().distanceTo(target) > 2.0;
			double travelled = position().distanceTo(progressSample);
			boolean progressed = travelled >= 0.28;
			stalledTicks = shouldMove && !progressed ? stalledTicks + 10 : 0;
			if (RecoveryPolicy.stableProgress(travelled)) {
				recoveryAttempts = 0;
				entityData.set(RECOVERY_LEVEL, 0);
				entityData.set(RECOVERY_SINCE, -1L);
			}
			progressSample = position();
			progressSampleTick = tickCount;
		}
		boolean fluidDanger = isInWater() || isInLava()
			|| DroneNavigator.isHazardousFluid(level, blockPosition());
		if (escapeTicks <= 0 && (horizontalCollision || verticalCollision || fluidDanger || stalledTicks >= 30)) {
			if (entityData.get(RECOVERY_LEVEL) <= 0 || entityData.get(RECOVERY_SINCE) < 0) {
				entityData.set(RECOVERY_SINCE, level.getGameTime());
			}
			recoveryAttempts = RecoveryPolicy.nextLevel(recoveryAttempts);
			entityData.set(RECOVERY_LEVEL, recoveryAttempts);
			escapeTicks = RecoveryPolicy.escapeDuration(recoveryAttempts, fluidDanger);
			escapeTarget = DroneNavigator.recoveryTarget(level, this, target,
				fluidDanger || environment.fluidNearby(), recoveryAttempts);
			activeFlightPath = null;
			pathGoal = null;
			clearLocalDetour();
			stalledTicks = 0;
		}
	}

	private void maintainFollowLeadership(ServerLevel level, ServerPlayer owner,
		List<DroneEntity> ownedDrones) {
		if (mode() != DroneMode.FOLLOW || cohortId().isBlank() || level.getGameTime() % 10 != 0) return;
		List<DroneEntity> allMembers = ownedDrones.stream()
			.filter(member -> member.cohortId().equals(cohortId())
				&& (member.mode() == DroneMode.FOLLOW || member.temporarilyDetachedFromWingFlight()))
			.sorted(Comparator.comparingInt(DroneEntity::cohortRank).thenComparing(DroneEntity::unitId))
			.toList();
		List<DroneEntity> members = allMembers.stream()
			.filter(member -> member.mode() == DroneMode.FOLLOW && !member.temporarilyDetachedFromWingFlight())
			.toList();
		List<DroneEntity> healthy = members.stream().filter(member -> member.recoveryLevel() <= 0)
			.sorted(Comparator.comparing(DroneEntity::unitId)).toList();
		if (healthy.isEmpty() || healthy.getFirst() != this) return;

		DroneEntity leader = members.stream().filter(member -> member.unitId().equals(cohortLeaderId()))
			.findFirst().orElse(null);
		boolean leaseExpired = leader == null;
		if (leader != null) {
			long since = leader.recoverySinceTick();
			long duration = since < 0 ? 0 : Math.max(0, level.getGameTime() - since);
			leaseExpired = FollowLeadershipPolicy.leaseExpired(leader.recoveryLevel(), duration);
		}
		if (!leaseExpired) return;

		List<FollowLeadershipPolicy.Candidate> candidates = members.stream().map(member ->
			new FollowLeadershipPolicy.Candidate(member.recoveryLevel(),
				member.distanceToSqr(owner), member.batteryPercent(), member.unitId())).toList();
		int successorIndex = FollowLeadershipPolicy.successorIndex(candidates);
		if (successorIndex < 0) return;
		DroneEntity successor = members.get(successorIndex);
		transferFollowLeadership(allMembers, successor);
	}

	public void transferLeadershipBeforeRemoval(ServerLevel level, ServerPlayer owner) {
		if (cohortId().isBlank()) return;
		List<DroneEntity> remaining = MorrowgearDrone.ownedDrones(level, owner, 512).stream()
			.filter(member -> member != this && member.cohortId().equals(cohortId()))
			.sorted(Comparator.comparingInt(DroneEntity::cohortRank).thenComparing(DroneEntity::unitId))
			.toList();
		if (remaining.isEmpty()) return;

		DroneEntity successor = remaining.stream()
			.filter(member -> member.unitId().equals(cohortLeaderId())).findFirst().orElse(null);
		if (successor == null) {
			List<FollowLeadershipPolicy.Candidate> candidates = remaining.stream().map(member ->
				new FollowLeadershipPolicy.Candidate(member.recoveryLevel(), member.distanceToSqr(owner),
					member.batteryPercent(), member.unitId())).toList();
			int successorIndex = FollowLeadershipPolicy.removalSuccessorIndex(candidates);
			if (successorIndex < 0) return;
			successor = remaining.get(successorIndex);
		}
		transferFollowLeadership(remaining, successor);
	}

	private void transferFollowLeadership(List<DroneEntity> members, DroneEntity successor) {
		String transferredCohort = missionId() + "#" + successor.unitId();
		List<DroneEntity> ordered = new ArrayList<>(members);
		ordered.sort(Comparator
			.comparingInt((DroneEntity member) -> member == successor ? 0
				: member.temporarilyDetachedFromWingFlight() ? 3 : member.recoveryLevel() <= 0 ? 1 : 2)
			.thenComparingInt(DroneEntity::cohortRank)
			.thenComparing(DroneEntity::unitId));
		for (int rank = 0; rank < ordered.size(); rank++) {
			DroneEntity member = ordered.get(rank);
			member.entityData.set(COHORT_ID, transferredCohort);
			member.entityData.set(COHORT_LEADER, successor.unitId());
			member.entityData.set(COHORT_RANK, rank);
		}
	}

	private boolean temporarilyDetachedFromWingFlight() {
		return WingLeadershipPolicy.temporarilyDetached(serviceReturn,
			emergencyInterceptActive(), combatState().controlsFlight());
	}

	private void maintainMissionLeadership(ServerLevel level) {
		if (!hasWaypoint() || missionId().isBlank() || cohortId().isBlank()
			|| temporarilyDetachedFromWingFlight() || level.getGameTime() % 10 != 0) return;
		List<DroneEntity> assigned = allAssignedMissionMembers(level).stream()
			.filter(member -> member.cohortId().equals(cohortId()))
			.sorted(Comparator.comparingInt(DroneEntity::cohortRank).thenComparing(DroneEntity::unitId))
			.toList();
		List<DroneEntity> active = assigned.stream()
			.filter(member -> !member.temporarilyDetachedFromWingFlight()).toList();
		if (active.isEmpty() || active.stream().min(Comparator.comparing(DroneEntity::unitId)).orElse(null) != this) return;
		Vec3 destination = missionDestination(level);
		List<WingLeadershipPolicy.Candidate> candidates = assigned.stream().map(member ->
			new WingLeadershipPolicy.Candidate(member.temporarilyDetachedFromWingFlight(),
				member.recoveryLevel(), member.position().distanceToSqr(destination),
				member.batteryPercent(), member.cohortRank(), member.unitId())).toList();
		if (WingLeadershipPolicy.leaderAvailable(candidates, cohortLeaderId())) return;
		int successorIndex = WingLeadershipPolicy.successorIndex(candidates);
		if (successorIndex < 0) return;
		transferMissionLeadership(assigned, assigned.get(successorIndex));
	}

	private void transferMissionLeadership(List<DroneEntity> assigned, DroneEntity successor) {
		List<DroneEntity> ordered = new ArrayList<>(assigned);
		ordered.sort(Comparator
			.comparingInt((DroneEntity member) -> member == successor ? 0
				: member.temporarilyDetachedFromWingFlight() ? 2 : 1)
			.thenComparingInt(DroneEntity::cohortRank)
			.thenComparing(DroneEntity::unitId));
		for (int rank = 0; rank < ordered.size(); rank++) {
			DroneEntity member = ordered.get(rank);
			member.entityData.set(COHORT_LEADER, successor.unitId());
			member.entityData.set(COHORT_RANK, rank);
		}
	}

	private Vec3 waypointTarget(ServerLevel level) {
		List<DroneEntity> allMembers = missionMembers(level);
		Vec3 destination = missionDestination(level);
		if (patrolRouteSize() > 1) {
			List<BlockPos> route = patrolRoutePoints();
			BlockPos nextPoint = patrolSurfacePoint(route.get(PatrolRoutePolicy.nextIndex(patrolRouteIndex(), route.size())));
			Vec3 next = Vec3.atCenterOf(nextPoint.above(6));
			destination = PatrolRoutePolicy.curvedTarget(position(), destination, next,
				route.size(), patrolRouteIndex());
			routeGuidanceTarget = PatrolRoutePolicy.smoothGuidance(routeGuidanceTarget, destination);
			destination = routeGuidanceTarget;
		} else routeGuidanceTarget = null;
		boolean underground = isUndergroundMission(level);
		int expected = Math.max(entityData.get(MISSION_EXPECTED), allMembers.size());
		if (missionStage() == MISSION_MUSTER) {
			Vec3 origin = Vec3.atCenterOf(BlockPos.of(entityData.get(MISSION_ORIGIN)));
			return SwarmFormation.orbitPosition(origin,
				Math.min(entityData.get(MISSION_INDEX), expected - 1), expected, level.getGameTime(), 0.045);
		}
		if ((missionStage() == MISSION_ORBIT_ENTRY || missionStage() == MISSION_ORBIT) && !underground) {
			destination = OrbitAltitudePlanner.stabilize(level, missionId(), destination, expected);
			boolean hierarchical = SwarmFormation.hierarchical(expected);
			int wingIndex = SwarmFormation.wingIndex(missionIndex());
			if (hierarchical) destination = SwarmFormation.wingOrbitCenter(destination, wingIndex);
			int orbitCount = hierarchical ? SwarmFormation.wingSize(expected, missionIndex())
				: SwarmFormation.plannedFormationSize(expected,
					allMembers.stream().map(DroneEntity::missionIndex).toList());
			int index = hierarchical ? SwarmFormation.wingLocalIndex(missionIndex())
				: Math.floorMod(missionIndex(), orbitCount);
			int wingCount = SwarmFormation.wingCount(expected);
			double orbitRadius = hierarchical
				? SwarmFormation.layeredOrbitRadius(orbitCount, wingIndex, wingCount)
				: SwarmFormation.orbitRadius(orbitCount);
			double angularSpeed = hierarchical
				? SwarmFormation.layeredAngularSpeed(PATROL_ANGULAR_SPEED, wingIndex, wingCount)
				: PATROL_ANGULAR_SPEED;
			double phaseOffset = hierarchical
				? SwarmFormation.layeredPhaseOffset(orbitPhaseOffset(), wingIndex)
				: orbitPhaseOffset();
			if (missionStage() == MISSION_ORBIT_ENTRY) {
				double progress = orbitEntryProgress(level.getGameTime());
				Vec3 target = SwarmFormation.orbitEntryPosition(destination, index,
					orbitCount, level.getGameTime(), angularSpeed, progress,
					phaseOffset, orbitRadius);
				if (progress >= 1.0) entityData.set(MISSION_STAGE, MISSION_ORBIT);
				return target;
			}
			return SwarmFormation.orbitPosition(destination, index, orbitCount,
				level.getGameTime(), angularSpeed, phaseOffset, orbitRadius);
		}
		if (missionStage() == MISSION_CONVERGING) {
			Vec3 rendezvous = rendezvousTarget(this, allMembers, destination, underground);
			entityData.set(RENDEZVOUS_POS, BlockPos.containing(rendezvous).asLong());
			return rendezvous;
		}
		entityData.set(RENDEZVOUS_POS, Long.MIN_VALUE);

		List<DroneEntity> cohort = allMembers.stream()
			.filter(member -> member.missionStage() == MISSION_MOVING
				&& !cohortId().isBlank() && member.cohortId().equals(cohortId()))
			.sorted(Comparator.comparingInt(DroneEntity::cohortRank).thenComparing(DroneEntity::unitId)).toList();
		if (cohort.isEmpty()) return MissionFlightPlan.transitTarget(level, position(), destination, underground,
			scoutRouteDecision.clearanceBoost());
		DroneEntity leader = cohort.stream().filter(member -> member.unitId().equals(cohortLeaderId()))
			.findFirst().orElse(cohort.getFirst());
		int formationSize = plannedFormationSize(allMembers);
		int formationIndex = Math.floorMod(cohortRank(), Math.max(1, formationSize));
		Vec3 leaderTarget = MissionFlightPlan.transitTarget(level, leader.position(), destination, underground,
			scoutRouteDecision.clearanceBoost());
		Vec3 forward = leader.getDeltaMovement();
		if (forward.lengthSqr() < 0.0025) forward = leaderTarget.subtract(leader.position());
		if (SwarmFormation.hierarchical(entityData.get(MISSION_EXPECTED))) {
			int wing = SwarmFormation.wingIndex(leader.missionIndex());
			int wingCount = SwarmFormation.wingCount(entityData.get(MISSION_EXPECTED));
			Vec3 missionOrigin = Vec3.atCenterOf(BlockPos.of(entityData.get(MISSION_ORIGIN)));
			Vec3 missionForward = destination.subtract(missionOrigin);
			if (missionForward.multiply(1, 0, 1).lengthSqr() < 1.0) {
				missionForward = destination.subtract(leader.position());
			}
			leaderTarget = leaderTarget.add(SwarmFormation.wingTravelOffset(
				wing, wingCount, missionForward, underground));
		}
		Vec3 formationForward = forward;
		if (this == leader) {
			return leaderTarget;
		}
		Vec3 formationSlot = leader.position().add(
			SwarmFormation.movingOffset(formationIndex, formationSize, formationForward, underground));
		return formationSlot;
	}

	private Vec3 rendezvousTarget(DroneEntity member, List<DroneEntity> allMembers,
		Vec3 destination, boolean underground) {
		DroneEntity leader = allMembers.stream()
			.filter(candidate -> candidate.unitId().equals(member.cohortLeaderId()))
			.findFirst().orElseGet(() -> allMembers.stream()
				.min(Comparator.comparingDouble((DroneEntity candidate) -> candidate.position().distanceTo(destination))
					.thenComparing(DroneEntity::unitId)).orElse(member));
		int formationSize = member.plannedFormationSize(allMembers);
		int provisionalRank = Math.floorMod(member.cohortRank(), Math.max(1, formationSize));
		Vec3 forward = leader.getDeltaMovement();
		if (forward.lengthSqr() < 0.0025) forward = destination.subtract(leader.position());
		return leader.position().add(SwarmFormation.movingOffset(
			provisionalRank, formationSize, forward, underground));
	}

	private int plannedFormationSize(List<DroneEntity> members) {
		int expected = entityData.get(MISSION_EXPECTED);
		if (SwarmFormation.hierarchical(expected)) {
			return SwarmFormation.wingSize(expected, missionIndex());
		}
		return SwarmFormation.plannedFormationSize(expected,
			members.stream().map(DroneEntity::missionIndex).toList());
	}

	private static int plannedFormationRank(DroneEntity member, DroneEntity leader, int formationSize) {
		return SwarmFormation.formationIndex(member.missionIndex(), leader.missionIndex(), formationSize);
	}

	private void updateMissionStage(ServerLevel level) {
		List<DroneEntity> assignedMembers = allAssignedMissionMembers(level);
		List<DroneEntity> members = assignedMembers.stream()
			.filter(drone -> !drone.temporarilyDetachedFromWingFlight()).toList();
		if (members.isEmpty()) return;
		if (role() == DroneRole.CARGO && cargoState() != CargoState.UNASSIGNED) {
			members.forEach(member -> member.entityData.set(MISSION_STAGE, MISSION_MOVING));
			return;
		}
		int expected = entityData.get(MISSION_EXPECTED);
		long elapsed = level.getGameTime() - entityData.get(MISSION_ASSIGNED_TICK);
		if (assignedMembers.size() < expected && elapsed >= 40L) {
			// A temporary combat/service detachment still belongs to the mission.
			// Shrink and reindex against the complete assigned roster so active-flight
			// membership changes cannot fragment the persisted Wing metadata.
			expected = assignedMembers.size();
			for (int memberIndex = 0; memberIndex < assignedMembers.size(); memberIndex++) {
				DroneEntity member = assignedMembers.get(memberIndex);
				member.entityData.set(MISSION_EXPECTED, expected);
				member.entityData.set(MISSION_INDEX, memberIndex);
			}
		}
		Vec3 destination = missionDestination(level);
		boolean underground = isUndergroundMission(level);
		DroneEntity coordinator = missionCoordinator(members);
		if (patrolRouteSize() > 1 && coordinator == this
			&& advancePatrolRouteIfReady(level, members, destination)) return;
		double arrivalRadius = SwarmFormation.hierarchical(expected)
			? SwarmFormation.wingArrivalRadius(expected)
			: SwarmFormation.orbitRadius(expected) + 7.0;
		if (coordinator != this) return;
		if (patrolRouteSize() <= 1 && !underground && releaseArrivedCohort(members, destination, arrivalRadius)) return;
		List<DroneEntity> musteringMembers = members.stream()
			.filter(member -> member.missionStage() == MISSION_MUSTER).toList();
		boolean anyMustering = !musteringMembers.isEmpty();
		if (!SwarmFormation.canProceedWithAvailableUnits(anyMustering, expected, members.size(), elapsed)) return;
		if (anyMustering) {
			Vec3 origin = Vec3.atCenterOf(BlockPos.of(entityData.get(MISSION_ORIGIN)));
			double readyRadius = SwarmFormation.orbitRadius(expected) + 4.0;
			boolean assembled = musteringMembers.stream()
				.allMatch(member -> member.position().distanceTo(origin) <= readyRadius);
			if (assembled && elapsed >= 40) musteringMembers.forEach(
				member -> member.entityData.set(MISSION_STAGE, MISSION_MOVING));
			return;
		}

		List<DroneEntity> activeMembers = members.stream()
			.filter(member -> member.missionStage() != MISSION_MUSTER
				&& member.missionStage() != MISSION_ORBIT
				&& member.missionStage() != MISSION_ORBIT_ENTRY).toList();
		if (activeMembers.isEmpty()) return;
		if (underground) {
			assignCohort(activeMembers, destination);
			activeMembers.forEach(member -> member.entityData.set(MISSION_STAGE, MISSION_MOVING));
			return;
		}

		if (!SwarmFormation.hierarchical(expected)) mergeNearbyCohorts(activeMembers, destination);
		joinRendezvousMembers(members, destination, underground);

		List<DroneEntity> uncohortedMoving = activeMembers.stream()
			.filter(member -> member.missionStage() == MISSION_MOVING && member.cohortId().isBlank()).toList();
		if (uncohortedMoving.isEmpty()) return;
		if (SwarmFormation.hierarchical(expected)) {
			initializeWingCohorts(uncohortedMoving, destination);
			return;
		}
		double spread = SwarmFormation.maxSpread(uncohortedMoving.stream().map(DroneEntity::position).toList());
		if (SwarmFormation.convergenceRequired(spread, uncohortedMoving.size())) {
			initializeRendezvousCohort(uncohortedMoving, destination);
		} else {
			assignCohort(uncohortedMoving, destination);
		}
	}

	private static DroneEntity missionCoordinator(List<DroneEntity> members) {
		return members.stream()
			.filter(member -> !member.cohortLeaderId().isBlank()
				&& member.unitId().equals(member.cohortLeaderId()))
			.min(Comparator.comparingInt(DroneEntity::missionIndex).thenComparing(DroneEntity::unitId))
			.orElseGet(() -> members.stream().min(Comparator.comparingInt(DroneEntity::missionIndex)
				.thenComparing(DroneEntity::unitId)).orElse(members.getFirst()));
	}

	private boolean releaseArrivedCohort(List<DroneEntity> members, Vec3 destination,
		double arrivalRadius) {
		long entryTick = level().getGameTime();
		Float sharedPhase = members.stream()
			.filter(member -> member.missionStage() == MISSION_ORBIT_ENTRY
				|| member.missionStage() == MISSION_ORBIT)
			.map(member -> member.entityData.get(ORBIT_PHASE_OFFSET))
			.filter(value -> !Float.isNaN(value)).findFirst().orElse(null);
		Map<String, List<DroneEntity>> wings = members.stream()
			.filter(member -> !member.cohortId().isBlank() && member.missionStage() != MISSION_MUSTER)
			.collect(java.util.stream.Collectors.groupingBy(DroneEntity::cohortId));
		boolean released = false;
		for (List<DroneEntity> wing : wings.values()) {
			List<DroneEntity> orbiting = wing.stream().filter(member ->
				member.missionStage() == MISSION_ORBIT || member.missionStage() == MISSION_ORBIT_ENTRY).toList();
			if (!orbiting.isEmpty()) {
				float phase = sharedPhase == null ? orbiting.getFirst().entityData.get(ORBIT_PHASE_OFFSET) : sharedPhase;
				List<DroneEntity> lateArrivals = wing.stream().filter(member ->
					(member.missionStage() == MISSION_MOVING || member.missionStage() == MISSION_CONVERGING)
						&& member.position().distanceTo(destination) <= arrivalRadius).toList();
				lateArrivals.forEach(member -> member.enterMissionOrbit(entryTick, phase));
				released |= !lateArrivals.isEmpty();
				continue;
			}

			List<DroneEntity> moving = wing.stream()
				.filter(member -> member.missionStage() == MISSION_MOVING).toList();
			boolean allArrived = !moving.isEmpty() && moving.stream()
				.allMatch(member -> member.position().distanceTo(destination) <= arrivalRadius);
			boolean healthyConverging = wing.stream().anyMatch(member ->
				member.missionStage() == MISSION_CONVERGING && member.recoveryLevel() <= 0);
			boolean recoveryStraggler = wing.stream().anyMatch(member ->
				member.missionStage() == MISSION_CONVERGING && member.recoveryLevel() > 0);
			if (!CohortControl.wingReadyForPatrol(moving.size(), wing.size(), allArrived,
				healthyConverging, recoveryStraggler)) continue;

			List<DroneEntity> ranked = moving.stream()
				.sorted(Comparator.comparingInt(DroneEntity::cohortRank).thenComparing(DroneEntity::unitId)).toList();
			DroneEntity leader = ranked.stream().filter(member -> member.unitId().equals(member.cohortLeaderId()))
				.findFirst().orElse(ranked.getFirst());
			boolean hierarchical = SwarmFormation.hierarchical(entityData.get(MISSION_EXPECTED));
			int orbitCount = hierarchical
				? SwarmFormation.wingSize(entityData.get(MISSION_EXPECTED), leader.missionIndex())
				: SwarmFormation.plannedFormationSize(entityData.get(MISSION_EXPECTED),
					members.stream().map(DroneEntity::missionIndex).toList());
			int leaderOrbitIndex = hierarchical ? SwarmFormation.wingLocalIndex(leader.missionIndex())
				: leader.missionIndex();
			float phase = sharedPhase == null
				? (float) (orbitPhaseFor(leader.position(), destination, entryTick, PATROL_ANGULAR_SPEED)
					+ leaderOrbitIndex * Math.PI * 2.0 / orbitCount)
				: sharedPhase;
			sharedPhase = phase;
			moving.forEach(member -> member.enterMissionOrbit(entryTick, phase));
			released = true;
		}
		return released;
	}

	private void enterMissionOrbit(long entryTick, float phaseOffset) {
		entityData.set(MISSION_STAGE, MISSION_ORBIT_ENTRY);
		entityData.set(ORBIT_ENTRY_TICK, entryTick);
		entityData.set(ORBIT_PHASE_OFFSET, phaseOffset);
		entityData.set(RENDEZVOUS_POS, Long.MIN_VALUE);
	}

	private double orbitEntryProgress(long gameTime) {
		long started = entityData.get(ORBIT_ENTRY_TICK);
		if (started < 0L) return 1.0;
		return Mth.clamp((gameTime - started) / (double) ORBIT_ENTRY_DURATION, 0.0, 1.0);
	}

	private double orbitPhaseOffset() {
		float phase = entityData.get(ORBIT_PHASE_OFFSET);
		return Float.isNaN(phase) ? 0.0 : phase;
	}

	private static float orbitPhaseFor(Vec3 entryPosition, Vec3 center, long gameTime,
		double angularSpeed) {
		Vec3 relative = entryPosition.subtract(center);
		return (float) (Math.atan2(relative.z, relative.x) - gameTime * angularSpeed);
	}

	private void initializeRendezvousCohort(List<DroneEntity> members, Vec3 destination) {
		if (members.isEmpty()) return;
		int leaderIndex = CohortControl.destinationLeaderIndex(
			members.stream().map(member -> member.position().distanceTo(destination)).toList(),
			members.stream().map(DroneEntity::unitId).toList());
		DroneEntity leader = members.get(Math.max(0, leaderIndex));
		String assignedId = missionId() + "#" + leader.unitId();
		int formationSize = leader.plannedFormationSize(members);
		for (DroneEntity member : members) {
			member.entityData.set(COHORT_ID, assignedId);
			member.entityData.set(COHORT_LEADER, leader.unitId());
			member.entityData.set(COHORT_RANK, plannedFormationRank(member, leader, formationSize));
			member.entityData.set(MISSION_STAGE, member == leader ? MISSION_MOVING : MISSION_CONVERGING);
		}
	}

	private void initializeWingCohorts(List<DroneEntity> members, Vec3 destination) {
		Map<Integer, List<DroneEntity>> wings = new HashMap<>();
		for (DroneEntity member : members) {
			wings.computeIfAbsent(SwarmFormation.wingIndex(member.missionIndex()),
				ignored -> new ArrayList<>()).add(member);
		}
		for (List<DroneEntity> wing : wings.values()) {
			wing.sort(Comparator.comparingInt(DroneEntity::missionIndex).thenComparing(DroneEntity::unitId));
			double spread = SwarmFormation.maxSpread(wing.stream().map(DroneEntity::position).toList());
			if (SwarmFormation.convergenceRequired(spread, wing.size())) {
				initializeRendezvousCohort(wing, destination);
			} else {
				assignCohort(wing, destination);
			}
		}
	}

	private void joinRendezvousMembers(List<DroneEntity> members, Vec3 destination, boolean underground) {
		Map<String, List<DroneEntity>> waitingByCohort = new HashMap<>();
		members.stream().filter(member -> member.missionStage() == MISSION_CONVERGING
			&& !member.cohortId().isBlank()).forEach(member ->
			waitingByCohort.computeIfAbsent(member.cohortId(), ignored -> new ArrayList<>()).add(member));
		for (Map.Entry<String, List<DroneEntity>> entry : waitingByCohort.entrySet()) {
			List<DroneEntity> waiting = entry.getValue();
			DroneEntity leader = members.stream().filter(member -> member.cohortId().equals(entry.getKey())
				&& member.unitId().equals(member.cohortLeaderId())).findFirst().orElse(null);
			if (leader == null) continue;
			List<DroneEntity> joinable = waiting.stream().filter(member -> member.recoveryLevel() <= 0)
				.sorted(Comparator.comparingInt(DroneEntity::cohortRank).thenComparing(DroneEntity::unitId)).toList();
			List<Vec3> slots = joinable.stream()
				.map(member -> rendezvousTarget(member, members, destination, underground)).toList();
			for (int index = 0; index < joinable.size(); index++) {
				joinable.get(index).entityData.set(RENDEZVOUS_POS, BlockPos.containing(slots.get(index)).asLong());
			}
			List<Double> distances = java.util.stream.IntStream.range(0, joinable.size())
				.mapToObj(index -> joinable.get(index).position().distanceTo(slots.get(index))).toList();
			if (!CohortControl.synchronizedJoinReady(distances)) continue;
			for (DroneEntity member : joinable) {
				member.entityData.set(COHORT_RANK,
					plannedFormationRank(member, leader, member.plannedFormationSize(members)));
				member.entityData.set(MISSION_STAGE, MISSION_MOVING);
				member.entityData.set(RENDEZVOUS_POS, Long.MIN_VALUE);
			}
		}
	}

	private void mergeNearbyCohorts(List<DroneEntity> members, Vec3 destination) {
		List<List<DroneEntity>> cohorts = new ArrayList<>(movingCohorts(members).values());
		cohorts.sort(Comparator.comparingInt(List<DroneEntity>::size).reversed()
			.thenComparing(cohort -> cohort.getFirst().cohortId()));
		for (int first = 0; first < cohorts.size(); first++) {
			for (int second = first + 1; second < cohorts.size(); second++) {
				List<DroneEntity> a = cohorts.get(first);
				List<DroneEntity> b = cohorts.get(second);
				double closest = a.stream().flatMap(one -> b.stream().map(two -> one.position().distanceTo(two.position())))
					.min(Double::compareTo).orElse(Double.MAX_VALUE);
				if (!CohortControl.shouldMerge(a.size(), b.size(), closest)) continue;
				List<DroneEntity> merged = new ArrayList<>(a);
				merged.addAll(b);
				String firstId = a.getFirst().cohortId();
				String secondId = b.getFirst().cohortId();
				assignCohort(merged, destination);
				String mergedId = merged.getFirst().cohortId();
				String mergedLeader = merged.getFirst().cohortLeaderId();
				members.stream().filter(member -> member.cohortId().equals(firstId)
					|| member.cohortId().equals(secondId)).forEach(member -> {
					member.entityData.set(COHORT_ID, mergedId);
					member.entityData.set(COHORT_LEADER, mergedLeader);
				});
				return;
			}
		}
	}

	private Map<String, List<DroneEntity>> movingCohorts(List<DroneEntity> members) {
		Map<String, List<DroneEntity>> cohorts = new HashMap<>();
		for (DroneEntity member : members) {
			if (member.missionStage() != MISSION_MOVING || member.cohortId().isBlank()) continue;
			cohorts.computeIfAbsent(member.cohortId(), ignored -> new ArrayList<>()).add(member);
		}
		return cohorts;
	}

	private void assignCohort(List<DroneEntity> members, Vec3 destination) {
		if (members.isEmpty()) return;
		String dominant = CohortControl.dominantCohort(members.stream().map(DroneEntity::cohortId).toList());
		DroneEntity retainedLeader = dominant.isBlank() ? null : members.stream()
			.filter(member -> member.cohortId().equals(dominant)
				&& member.unitId().equals(member.cohortLeaderId())).findFirst().orElse(null);
		DroneEntity leader = retainedLeader != null ? retainedLeader : members.stream()
			.min(Comparator.comparingDouble((DroneEntity member) -> member.position().distanceTo(destination))
				.thenComparing(DroneEntity::unitId)).orElse(members.getFirst());
		String assignedId = dominant.isBlank() ? missionId() + "#" + leader.unitId() : dominant;
		List<DroneEntity> ordered = new ArrayList<>(members);
		ordered.sort(Comparator
			.comparingInt((DroneEntity member) -> member == leader ? 0 : member.cohortId().equals(dominant) ? 1 : 2)
			.thenComparingInt(DroneEntity::cohortRank)
			.thenComparingInt(DroneEntity::missionIndex)
			.thenComparing(DroneEntity::unitId));
		for (int rank = 0; rank < ordered.size(); rank++) {
			DroneEntity member = ordered.get(rank);
			member.entityData.set(COHORT_ID, assignedId);
			member.entityData.set(COHORT_LEADER, leader.unitId());
			member.entityData.set(COHORT_RANK, rank);
			member.entityData.set(RENDEZVOUS_POS, Long.MIN_VALUE);
		}
	}

	private Vec3 missionDestination(ServerLevel level) {
		LivingEntity tracking = trackingTarget(level);
		if (tracking != null) {
			if (tickCount % 10 == 0) entityData.set(DATA_LINK_STATUS, trackingStatusLabel());
			entityData.set(WAYPOINT_POS, tracking.blockPosition().asLong());
			return MissionFlightPlan.trackingObservationTarget(level, tracking);
		}
		if (hasTrackingTarget() && tickCount % 10 == 0) {
			entityData.set(DATA_LINK_STATUS, "TARGET TRACK / LOST / LAST KNOWN");
		}
		return Vec3.atCenterOf(waypointPos().above(6));
	}

	private BlockPos patrolSurfacePoint(BlockPos horizontal) {
		if (!(level() instanceof ServerLevel level)) return horizontal;
		int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
			horizontal.getX(), horizontal.getZ());
		return new BlockPos(horizontal.getX(), y, horizontal.getZ());
	}

	private boolean advancePatrolRouteIfReady(ServerLevel level, List<DroneEntity> members, Vec3 destination) {
		List<DroneEntity> flightLeaders = members.stream()
			.filter(member -> member.recoveryLevel() <= 0
				&& !member.cohortLeaderId().isBlank()
				&& member.unitId().equals(member.cohortLeaderId()))
			.toList();
		if (flightLeaders.isEmpty()) {
			flightLeaders = members.stream()
				.filter(member -> member.recoveryLevel() <= 0)
				.min(Comparator.comparingDouble(member -> member.position().distanceToSqr(destination)))
				.stream().toList();
		}
		if (flightLeaders.isEmpty()) return false;
		int arrivedLeaders = (int) flightLeaders.stream()
			.filter(leader -> leader.reachedPatrolHandoff(destination)).count();
		if (!PatrolRoutePolicy.leaderQuorumReached(arrivedLeaders, flightLeaders.size())) return false;
		int next = PatrolRoutePolicy.nextIndex(patrolRouteIndex(), patrolRouteSize());
		for (DroneEntity member : allAssignedMissionMembers(level)) {
			List<BlockPos> route = member.patrolRoutePoints();
			if (route.size() <= 1) continue;
			int memberNext = Math.floorMod(next, route.size());
			BlockPos point = member.patrolSurfacePoint(route.get(memberNext));
			member.entityData.set(PATROL_ROUTE_INDEX, memberNext);
			member.entityData.set(PATROL_ROUTE_FIRST_LEG, false);
			member.entityData.set(WAYPOINT_POS, point.asLong());
			member.entityData.set(MISSION_STAGE, MISSION_MOVING);
			member.entityData.set(ORBIT_ENTRY_TICK, -1L);
			member.entityData.set(ORBIT_PHASE_OFFSET, Float.NaN);
			member.entityData.set(RENDEZVOUS_POS, Long.MIN_VALUE);
		}
		return true;
	}

	private boolean reachedPatrolHandoff(Vec3 currentDestination) {
		List<BlockPos> route = patrolRoutePoints();
		if (route.size() <= 1) return PatrolRoutePolicy.shouldAdvance(position(), currentDestination);
		int currentIndex = Math.floorMod(patrolRouteIndex(), route.size());
		Vec3 previous;
		if (entityData.get(PATROL_ROUTE_FIRST_LEG)) {
			previous = Vec3.atCenterOf(BlockPos.of(entityData.get(MISSION_ORIGIN)));
		} else {
			int previousIndex = Math.floorMod(currentIndex - 1, route.size());
			previous = Vec3.atCenterOf(patrolSurfacePoint(route.get(previousIndex)).above(6));
		}
		return PatrolRoutePolicy.shouldAdvance(position(), previous, currentDestination);
	}

	private boolean isUndergroundMission(ServerLevel level) {
		return MissionFlightPlan.isUnderground(level, missionDestination(level));
	}

	private LivingEntity trackingTarget(ServerLevel level) {
		if (!hasTrackingTarget()) return null;
		Entity entity = level.getEntityInAnyDimension(trackingTargetId());
		return entity instanceof LivingEntity living && living.isAlive() && living.level() == level ? living : null;
	}

	private List<DroneEntity> missionMembers(ServerLevel level) {
		return allAssignedMissionMembers(level).stream()
			.filter(drone -> !drone.temporarilyDetachedFromWingFlight()).toList();
	}

	private List<DroneEntity> allAssignedMissionMembers(ServerLevel level) {
		if (missionId().isBlank()) return List.of(this);
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId());
		if (owner == null) return List.of(this);
		return MorrowgearDrone.ownedDrones(level, owner, 512).stream()
			.filter(drone -> drone.hasWaypoint() && MissionWingPolicy.sameMissionWing(
				missionId(), groupId(), drone.missionId(), drone.groupId()))
			.sorted(Comparator.comparingInt(DroneEntity::missionIndex).thenComparing(DroneEntity::unitId)).toList();
	}

	int assignedMissionSizeForVerification(ServerLevel level) {
		return allAssignedMissionMembers(level).size();
	}

	boolean assignedMissionOnlyContainsGroupForVerification(ServerLevel level, String expectedGroup) {
		return allAssignedMissionMembers(level).stream()
			.allMatch(member -> MissionWingPolicy.normalizedGroup(member.groupId())
				.equals(MissionWingPolicy.normalizedGroup(expectedGroup)));
	}

	private void stabilizeHeading(ServerPlayer owner) {
		Vec3 horizontal = getDeltaMovement().multiply(1, 0, 1);
		float targetYaw = FlightAttitude.movementYaw(horizontal, getYRot());
		boolean autocannonFormation = combatWeapon() == CombatWeapon.AUTOCANNON
			&& (combatState() == CombatState.FLARE_ENTRY || combatState() == CombatState.GUN_RUN);
		if (combatActive() && !autocannonFormation && level() instanceof ServerLevel serverLevel) {
			LivingEntity combatTarget = combatTarget(serverLevel);
			if (combatTarget != null) {
				Vec3 aim = combatTarget.position().subtract(position()).multiply(1, 0, 1);
				targetYaw = FlightAttitude.movementYaw(aim, targetYaw);
			}
		}
		if (horizontal.lengthSqr() <= 0.0016 && mode() == DroneMode.FOLLOW) {
			Vec3 ownerMotion = owner.getDeltaMovement().multiply(1, 0, 1);
			targetYaw = FlightAttitude.movementYaw(ownerMotion, targetYaw);
		}
		float turnLimit = FlightAttitude.yawTurnLimit(horizontal);
		float yaw = getYRot() + Mth.clamp(Mth.wrapDegrees(targetYaw - getYRot()), -turnLimit, turnLimit);
		setYRot(yaw);
		setYHeadRot(yaw);
		setYBodyRot(yaw);
	}

	private void updateDroneLight(ServerLevel level) {
		if (isDocked()) {
			clearDroneLight(level);
			return;
		}
		if (tickCount % 3 != 0) return;
		BlockPos next = BlockPos.containing(getX(), getY() + 0.1, getZ());
		if (next.equals(lightPos)) return;
		clearDroneLight(level);
		if (!level.getBlockState(next).isAir()) return;
		level.setBlockAndUpdate(next, Blocks.LIGHT.defaultBlockState());
		lightPos = next;
		for (int depth = 1; depth <= 8; depth++) {
			BlockPos candidate = next.below(depth);
			if (!level.getBlockState(candidate).isAir() || level.getBlockState(candidate.below()).isAir()) continue;
			level.setBlockAndUpdate(candidate, Blocks.LIGHT.defaultBlockState());
			groundLightPos = candidate;
			break;
		}
	}

	public void clearDroneLight(ServerLevel level) {
		removeLight(level, lightPos);
		removeLight(level, groundLightPos);
		lightPos = null;
		groundLightPos = null;
	}

	private void removeLight(ServerLevel level, BlockPos pos) {
		if (pos == null) return;
		boolean shared = !level.getEntitiesOfClass(DroneEntity.class, new AABB(pos).inflate(10.0),
			drone -> drone != this && (pos.equals(drone.lightPos) || pos.equals(drone.groundLightPos))).isEmpty();
		if (!shared && level.getBlockState(pos).is(Blocks.LIGHT)) level.removeBlock(pos, false);
	}

	boolean usesManagedLight(BlockPos pos) {
		return pos != null && (pos.equals(lightPos) || pos.equals(groundLightPos));
	}

	private DockTarget dockTarget(ServerLevel level) {
		if (!hasDock()) return null;
		BlockPos center = dockPos();
		if (!(level.getBlockEntity(center) instanceof DockBlockEntity dock)) {
			Vec3 hold = Vec3.atCenterOf(center).add(0, DOCK_LANDING_Y + 3.0, 0);
			return new DockTarget(hold, false, null, 0, 1.0);
		}
		Vec3 landing = new Vec3(center.getX() + 0.5, center.getY() + DOCK_LANDING_Y, center.getZ() + 0.5);
		int stage = entityData.get(DOCK_STAGE);

		if (stage >= 2) {
			if (DroneNavigator.corridorClear(level, this, position(), landing))
				return new DockTarget(landing, true, dock, 2, 0.22);
			entityData.set(DOCK_STAGE, 0);
			dockApproachDirection = null;
			stage = 0;
		}

		if (stage == 1 && dockApproachDirection != null) {
			DockLane lane = sideDockLane(level, landing, dockApproachDirection);
			if (lane != null) return new DockTarget(lane.gate(), false, dock, 2, 0.55);
			entityData.set(DOCK_STAGE, 0);
			dockApproachDirection = null;
			stage = 0;
		}

		if (stage == 0) {
			Vec3 topHold = landing.add(0, 5.0, 0);
			boolean topClear = DroneNavigator.corridorClear(level, this, topHold, landing);
			List<DockLane> lanes = DockApproachPlan.sideDirections(dock.facing()).stream()
				.map(direction -> sideDockLane(level, landing, direction))
				.filter(java.util.Objects::nonNull)
				.sorted(Comparator.comparingDouble(DockLane::score)).toList();
			double topScore = position().distanceTo(topHold);
			if (topClear && (lanes.isEmpty() || topScore <= lanes.getFirst().score() + 2.0)) {
				dockApproachDirection = null;
				return new DockTarget(topHold, false, dock, 2, DOCK_ARRIVAL_DISTANCE);
			}
			if (!lanes.isEmpty()) {
				DockLane lane = lanes.getFirst();
				dockApproachDirection = lane.direction();
				return new DockTarget(lane.outer(), false, dock, 1, 0.9);
			}
		}

		Vec3 hold = DroneNavigator.localDetour(level, this, landing.add(0, 3.0, 0), false);
		return new DockTarget(hold, false, dock, 0, 0.5);
	}

	private DockLane sideDockLane(ServerLevel level, Vec3 landing, Direction direction) {
		Vec3 outer = landing.add(direction.getStepX() * 4.5, 0.5, direction.getStepZ() * 4.5);
		Vec3 gate = landing.add(direction.getStepX() * 1.55, 0.18, direction.getStepZ() * 1.55);
		if (!DroneNavigator.corridorClear(level, this, outer, gate)
			|| !DroneNavigator.corridorClear(level, this, gate, landing)) return null;
		double score = position().distanceTo(outer) - corridorScore(level, dockPos(), direction) * 0.03;
		return new DockLane(direction, outer, gate, score);
	}

	private int corridorScore(ServerLevel level, BlockPos center, Direction direction) {
		Direction lateral = direction.getClockWise();
		int score = 0;
		for (int distance = 2; distance <= 5; distance++) {
			for (int width = -1; width <= 1; width++) {
				for (int height = 0; height < 2; height++) {
					BlockPos check = center.relative(direction, distance).relative(lateral, width).above(height);
					if (level.getBlockState(check).isAir()) score++;
				}
			}
		}
		return score;
	}

	private void alignWithDock(DockBlockEntity dock) {
		float yaw = dock.facing().toYRot();
		setYRot(yaw);
		setYHeadRot(yaw);
		setYBodyRot(yaw);
	}

	private Vec3 ensureClear(ServerLevel level, Vec3 requested) {
		Vec3 result = requested;
		for (int lift = 0; lift <= 5; lift++) {
			BlockPos feet = BlockPos.containing(result.x, result.y, result.z);
			if (level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()) return result;
			result = result.add(0, 1, 0);
		}
		return result;
	}

	private Vec3 separationVector(ServerLevel level, boolean tightFormation) {
		double radius = tightFormation ? 1.35 : 2.1;
		double maximum = tightFormation ? 0.07 : 0.18;
		AABB area = getBoundingBox().inflate(radius);
		List<DroneEntity> nearby = level.getEntitiesOfClass(DroneEntity.class, area, drone -> drone != this && drone.isOwnedBy(ownerId()));
		Vec3 force = Vec3.ZERO;
		for (DroneEntity drone : nearby) {
			Vec3 delta = position().subtract(drone.position());
			double distance = delta.length();
			if (distance < 0.01 || distance >= radius) continue;
			double strength = (radius - distance) / radius * maximum;
			force = force.add(delta.scale(strength / distance));
		}
		return force.length() > maximum ? force.normalize().scale(maximum) : force;
	}

	private record DockTarget(Vec3 position, boolean finalApproach, DockBlockEntity dock,
		int nextStage, double arrivalDistance) {
	}

	private record DockLane(Direction direction, Vec3 outer, Vec3 gate, double score) {
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean canCollideWith(Entity other) {
		return !(other instanceof Player) && !(other instanceof DroneEntity) && super.canCollideWith(other);
	}

	@Override
	public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
		fallDistance = 0;
		return false;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		boolean damaged = super.hurtServer(level, source, amount);
		if (!damaged || amount <= 0.0f) return damaged;
		int primary = Math.floorMod(tickCount + getId(), 3);
		entityData.set(PROPULSION_CONDITION,
			DroneSubsystemPolicy.wear(propulsionCondition(), amount, primary == 0));
		entityData.set(SENSOR_CONDITION,
			DroneSubsystemPolicy.wear(sensorCondition(), amount, primary == 1));
		entityData.set(PAYLOAD_CONDITION,
			DroneSubsystemPolicy.wear(payloadCondition(), amount, primary == 2));
		return true;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("Owner", entityData.get(OWNER));
		output.putString("OwnerName", ownerName());
		output.putString("UnitId", unitId());
		output.putInt("Mode", mode().id());
		output.putString("Role", role().id());
		output.putInt("Battery", entityData.get(BATTERY));
		output.putString("BatteryTier", batteryTier().id());
		output.putInt("WeaponPower", entityData.get(WEAPON_POWER));
		output.putInt("PropulsionCondition", propulsionCondition());
		output.putInt("SensorCondition", sensorCondition());
		output.putInt("PayloadCondition", payloadCondition());
		output.putBoolean("PowerLostBeacon", powerLostBeaconReported || isPowerLost());
		output.putBoolean("PowerLossTaskCaptured", powerLossTaskCaptured);
		output.putInt("PowerLossResumeMode", powerLossResumeMode);
		if (solarServiceStationId != null) output.putString("SolarServiceStation", solarServiceStationId.toString());
		output.putInt("SolarServiceSlot", solarServiceSlot);
		output.putBoolean("SolarTaskSuspended", solarTaskSuspended);
		if (salvageTargetId != null) output.putString("SalvageTarget", salvageTargetId.toString());
		output.putInt("SalvageState", salvageState.ordinal());
		output.putLong("DockPos", entityData.get(DOCK_POS));
		output.putBoolean("Docked", entityData.get(DOCKED));
		output.putInt("DockStage", entityData.get(DOCK_STAGE));
		output.putString("Group", groupId());
		output.putLong("WaypointPos", entityData.get(WAYPOINT_POS));
		output.putBoolean("HasWaypoint", entityData.get(HAS_WAYPOINT));
		output.putString("MissionId", entityData.get(MISSION_ID));
		output.putInt("MissionExpected", entityData.get(MISSION_EXPECTED));
		output.putInt("MissionIndex", entityData.get(MISSION_INDEX));
		output.putInt("MissionStage", entityData.get(MISSION_STAGE));
		output.putLong("MissionOrigin", entityData.get(MISSION_ORIGIN));
		output.putLong("MissionAssignedTick", entityData.get(MISSION_ASSIGNED_TICK));
		output.putLong("OrbitEntryTick", entityData.get(ORBIT_ENTRY_TICK));
		output.putFloat("OrbitPhaseOffset", entityData.get(ORBIT_PHASE_OFFSET));
		output.putString("TrackTarget", entityData.get(TRACK_TARGET));
		output.putInt("ThreatScore", entityData.get(THREAT_SCORE));
		output.putLong("RecoverySince", recoverySinceTick());
		output.putString("CohortId", cohortId());
		output.putString("CohortLeader", cohortLeaderId());
		output.putInt("CohortRank", cohortRank());
		output.putLong("RendezvousPos", entityData.get(RENDEZVOUS_POS));
		output.putLong("CargoSource", entityData.get(CARGO_SOURCE));
		output.putLong("CargoTarget", entityData.get(CARGO_TARGET));
		output.putInt("CargoState", entityData.get(CARGO_STATE));
		output.putBoolean("CargoPaused", cargoPaused());
		output.putString("FieldOrder", fieldOrderId());
		output.putString("FieldType", fieldOperationType().id());
		output.putLong("FieldAnchor", entityData.get(FIELD_ANCHOR));
		output.putInt("FieldRadius", fieldRadius());
		output.putInt("FieldState", fieldOperationState().id());
		output.putInt("FieldProgress", fieldProgress());
		output.putInt("FieldFound", fieldFound());
		output.putString("SecurityOrder", entityData.get(SECURITY_ORDER));
		output.putLong("SecurityAnchor", entityData.get(SECURITY_ANCHOR));
		output.putInt("SecurityRadius", entityData.get(SECURITY_RADIUS));
		output.putString("PatrolRoute", entityData.get(PATROL_ROUTE));
		output.putInt("PatrolRouteIndex", entityData.get(PATROL_ROUTE_INDEX));
		output.putBoolean("PatrolRouteFirstLeg", entityData.get(PATROL_ROUTE_FIRST_LEG));
		output.putString("SecurityLoadout", securityLoadout().id());
		output.putInt("CombatState", combatState().id());
		output.putInt("CombatWeapon", combatWeapon().id());
		output.putInt("CombatTarget", combatTargetId());
		output.putInt("CombatCharge", combatCharge());
		output.putInt("CombatSlot", combatSlot());
		output.putInt("CombatCount", combatCount());
		output.putLong("CombatStateTick", combatStateTick());
		output.putInt("CombatResumeMode", combatResumeMode);
		output.putInt("GunAmmo", gunAmmo());
		output.putInt("Missiles", missiles());
		output.putInt("LaserHeat", laserHeat());
		output.putBoolean("ServiceReturn", serviceReturn);
		output.putInt("ServiceResumeMode", serviceResumeMode);
		output.putString("ServiceReason", serviceReason.name());
		output.putString("QueuedServiceReason", queuedServiceReason.name());
		if (rechargeReclaimTargetUuid != null)
			output.putString("RechargeReclaimTarget", rechargeReclaimTargetUuid.toString());
		if (rechargeReliefUnitUuid != null)
			output.putString("RechargeReliefUnit", rechargeReliefUnitUuid.toString());
		output.putInt("RechargeReclaimSlot", rechargeReclaimSlot);
		ValueOutput.TypedOutputList<ItemStack> cargoOutput = output.list("Cargo", ItemStack.CODEC);
		for (ItemStack stack : cargo) if (!stack.isEmpty()) cargoOutput.add(stack);
		if (lightPos != null) output.putLong("LightPos", lightPos.asLong());
		if (groundLightPos != null) output.putLong("GroundLightPos", groundLightPos.asLong());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		setPersistenceRequired();
		entityData.set(OWNER, input.getStringOr("Owner", ""));
		try {
			String station = input.getStringOr("SolarServiceStation", "");
			solarServiceStationId = station.isBlank() ? null : UUID.fromString(station);
		} catch (IllegalArgumentException ignored) { solarServiceStationId = null; }
		solarServiceSlot = solarServiceStationId == null ? -1
			: Mth.clamp(input.getIntOr("SolarServiceSlot", -1), -1, 2);
		entityData.set(OWNER_NAME, input.getStringOr("OwnerName", ""));
		entityData.set(UNIT_ID, input.getStringOr("UnitId", "MG-DRN-UNSET"));
		entityData.set(MODE, input.getIntOr("Mode", DroneMode.STANDBY.id()));
		entityData.set(ROLE, DroneRole.byId(input.getStringOr("Role", DroneRole.FIELD.id())).id());
		BatteryTier savedBatteryTier = BatteryTier.byId(input.getStringOr("BatteryTier", BatteryTier.STANDARD.id()));
		entityData.set(BATTERY_TIER, savedBatteryTier.id());
		entityData.set(BATTERY, savedBatteryTier.clamp(input.getIntOr("Battery", 1000)));
		entityData.set(WEAPON_POWER, DroneStatePolicy.battery(input.getIntOr("WeaponPower", 1000)));
		entityData.set(PROPULSION_CONDITION,
			Mth.clamp(input.getIntOr("PropulsionCondition", DroneSubsystemPolicy.MAX), 0, DroneSubsystemPolicy.MAX));
		entityData.set(SENSOR_CONDITION,
			Mth.clamp(input.getIntOr("SensorCondition", DroneSubsystemPolicy.MAX), 0, DroneSubsystemPolicy.MAX));
		entityData.set(PAYLOAD_CONDITION,
			Mth.clamp(input.getIntOr("PayloadCondition", DroneSubsystemPolicy.MAX), 0, DroneSubsystemPolicy.MAX));
		powerLostBeaconReported = input.getBooleanOr("PowerLostBeacon", false);
		powerLossTaskCaptured = input.getBooleanOr("PowerLossTaskCaptured", false);
		powerLossResumeMode = DroneMode.byId(input.getIntOr("PowerLossResumeMode",
			DroneMode.STANDBY.id())).id();
		try {
			String savedSalvageTarget = input.getStringOr("SalvageTarget", "");
			salvageTargetId = savedSalvageTarget.isBlank() ? null : UUID.fromString(savedSalvageTarget);
		} catch (IllegalArgumentException ignored) {
			salvageTargetId = null;
		}
		salvageState = SalvageState.byId(input.getIntOr("SalvageState", SalvageState.IDLE.ordinal()));
		entityData.set(DOCK_POS, input.getLongOr("DockPos", Long.MIN_VALUE));
		entityData.set(DOCKED, input.getBooleanOr("Docked", false));
		entityData.set(DOCK_STAGE, input.getIntOr("DockStage", 0));
		entityData.set(GROUP, DroneStatePolicy.group(input.getStringOr("Group", "ALPHA")));
		entityData.set(WAYPOINT_POS, input.getLongOr("WaypointPos", BlockPos.ZERO.asLong()));
		entityData.set(HAS_WAYPOINT, input.getBooleanOr("HasWaypoint", false));
		entityData.set(MISSION_ID, input.getStringOr("MissionId", ""));
		int savedExpected = DroneStatePolicy.missionExpected(input.getIntOr("MissionExpected", 1));
		entityData.set(MISSION_EXPECTED, savedExpected);
		entityData.set(MISSION_INDEX, DroneStatePolicy.missionIndex(input.getIntOr("MissionIndex", 0), savedExpected));
		entityData.set(MISSION_STAGE, input.getIntOr("MissionStage", MISSION_MUSTER));
		entityData.set(MISSION_ORIGIN, input.getLongOr("MissionOrigin", blockPosition().above(3).asLong()));
		entityData.set(MISSION_ASSIGNED_TICK, input.getLongOr("MissionAssignedTick", 0L));
		entityData.set(ORBIT_ENTRY_TICK, input.getLongOr("OrbitEntryTick", -1L));
		entityData.set(ORBIT_PHASE_OFFSET, input.getFloatOr("OrbitPhaseOffset", Float.NaN));
		entityData.set(TRACK_TARGET, input.getStringOr("TrackTarget", ""));
		entityData.set(THREAT_SCORE, input.getIntOr("ThreatScore", 0));
		entityData.set(RECOVERY_SINCE, input.getLongOr("RecoverySince", -1L));
		entityData.set(COHORT_ID, input.getStringOr("CohortId", ""));
		entityData.set(COHORT_LEADER, input.getStringOr("CohortLeader", ""));
		entityData.set(COHORT_RANK, input.getIntOr("CohortRank", -1));
		entityData.set(RENDEZVOUS_POS, input.getLongOr("RendezvousPos", Long.MIN_VALUE));
		entityData.set(CARGO_SOURCE, input.getLongOr("CargoSource", Long.MIN_VALUE));
		entityData.set(CARGO_TARGET, input.getLongOr("CargoTarget", Long.MIN_VALUE));
		entityData.set(CARGO_STATE, CargoState.byId(input.getIntOr("CargoState", CargoState.UNASSIGNED.id())).id());
		entityData.set(CARGO_PAUSED, input.getBooleanOr("CargoPaused", false));
		String savedFieldOrder = input.getStringOr("FieldOrder", "");
		// The shared scan ledger is session-scoped. Cancel saved field assignments on
		// reload so per-role states cannot resume against an empty ledger. The saved
		// orbit/waypoint mode belongs to that expired assignment as well.
		entityData.set(FIELD_ORDER, "");
		entityData.set(FIELD_TYPE, FieldOperationType.NONE.id());
		entityData.set(FIELD_ANCHOR, Long.MIN_VALUE);
		entityData.set(FIELD_RADIUS, 0);
		entityData.set(FIELD_STATE, FieldOperationState.IDLE.id());
		entityData.set(FIELD_PROGRESS, 0);
		entityData.set(FIELD_FOUND, 0);
		if (!savedFieldOrder.isBlank()) {
			entityData.set(MODE, DroneMode.STANDBY.id());
			entityData.set(HAS_WAYPOINT, false);
			entityData.set(MISSION_ID, "");
			entityData.set(MISSION_STAGE, MISSION_MUSTER);
		}
		entityData.set(SECURITY_ORDER, input.getStringOr("SecurityOrder", ""));
		entityData.set(SECURITY_ANCHOR, input.getLongOr("SecurityAnchor", Long.MIN_VALUE));
		int savedSecurityRadius = input.getIntOr("SecurityRadius", 0);
		entityData.set(SECURITY_RADIUS, entityData.get(SECURITY_ORDER).isBlank()
			? 0 : DroneStatePolicy.securityRadius(savedSecurityRadius));
		entityData.set(SECURITY_CONTACT, -1);
		entityData.set(SECURITY_CONTACTS, 0);
		entityData.set(SECURITY_LOADOUT, SecurityLoadout.byId(
			input.getStringOr("SecurityLoadout", SecurityLoadout.AUTO.id())).id());
		String savedPatrolRoute = input.getStringOr("PatrolRoute", "");
		List<BlockPos> savedPatrolPoints = PatrolRoutePolicy.decode(savedPatrolRoute);
		entityData.set(PATROL_ROUTE, savedPatrolPoints.isEmpty() ? "" : PatrolRoutePolicy.encode(savedPatrolPoints));
		entityData.set(PATROL_ROUTE_INDEX, savedPatrolPoints.isEmpty() ? 0
			: Math.floorMod(input.getIntOr("PatrolRouteIndex", 0), savedPatrolPoints.size()));
		entityData.set(PATROL_ROUTE_FIRST_LEG, !savedPatrolPoints.isEmpty()
			&& input.getBooleanOr("PatrolRouteFirstLeg", false));
		entityData.set(COMBAT_STATE, CombatState.byId(input.getIntOr("CombatState", CombatState.IDLE.id())).id());
		entityData.set(COMBAT_WEAPON, CombatWeapon.byId(input.getIntOr("CombatWeapon", CombatWeapon.NONE.id())).id());
		entityData.set(COMBAT_TARGET, input.getIntOr("CombatTarget", -1));
		entityData.set(COMBAT_CHARGE, Mth.clamp(input.getIntOr("CombatCharge", 0), 0, 1000));
		entityData.set(COMBAT_SLOT, Math.max(0, input.getIntOr("CombatSlot", 0)));
		entityData.set(COMBAT_COUNT, Math.max(1, input.getIntOr("CombatCount", 1)));
		entityData.set(COMBAT_STATE_TICK, input.getLongOr("CombatStateTick", -1L));
		combatResumeMode = input.getIntOr("CombatResumeMode",
			combatState() == CombatState.IDLE ? -1 : entityData.get(MODE));
		entityData.set(COMBAT_SHOT_TICK, -1000);
		entityData.set(GUN_AMMO, Mth.clamp(input.getIntOr("GunAmmo", CombatPolicy.GUN_CAPACITY),
			0, CombatPolicy.GUN_CAPACITY));
		entityData.set(MISSILES, Mth.clamp(input.getIntOr("Missiles", CombatPolicy.MISSILE_CAPACITY),
			0, CombatPolicy.MISSILE_CAPACITY));
		entityData.set(LASER_HEAT, Mth.clamp(input.getIntOr("LaserHeat", 0), 0, 1000));
		serviceReturn = input.getBooleanOr("ServiceReturn", false) && hasDock();
		serviceResumeMode = DroneMode.byId(
			input.getIntOr("ServiceResumeMode", DroneMode.STANDBY.id())).id();
		serviceReason = serviceReturn ? DroneServicePolicy.Need.byName(
			input.getStringOr("ServiceReason", DroneServicePolicy.Need.NONE.name()))
			: DroneServicePolicy.Need.NONE;
		queuedServiceReason = DroneServicePolicy.Need.byName(
			input.getStringOr("QueuedServiceReason", DroneServicePolicy.Need.NONE.name()));
		try {
			String savedReclaimTarget = input.getStringOr("RechargeReclaimTarget", "");
			rechargeReclaimTargetUuid = savedReclaimTarget.isBlank()
				? null : UUID.fromString(savedReclaimTarget);
		} catch (IllegalArgumentException ignored) {
			rechargeReclaimTargetUuid = null;
		}
		rechargeReclaimTargetId = -1;
		try {
			String savedReliefUnit = input.getStringOr("RechargeReliefUnit", "");
			rechargeReliefUnitUuid = savedReliefUnit.isBlank()
				? null : UUID.fromString(savedReliefUnit);
		} catch (IllegalArgumentException ignored) {
			rechargeReliefUnitUuid = null;
		}
		rechargeReclaimSlot = Math.max(-1, input.getIntOr("RechargeReclaimSlot", -1));
		cargo.clear();
		for (ItemStack stack : input.listOrEmpty("Cargo", ItemStack.CODEC)) {
			if (!stack.isEmpty() && cargo.size() < 9) cargo.add(stack);
		}
		updateCargoCount();
		long savedLight = input.getLongOr("LightPos", Long.MIN_VALUE);
		lightPos = savedLight == Long.MIN_VALUE ? null : BlockPos.of(savedLight);
		long savedGroundLight = input.getLongOr("GroundLightPos", Long.MIN_VALUE);
		groundLightPos = savedGroundLight == Long.MIN_VALUE ? null : BlockPos.of(savedGroundLight);
		solarTaskSuspended = solarServiceAssigned()
			&& input.getBooleanOr("SolarTaskSuspended", true);
		if (serviceReturn) {
			taskStack.suspend(currentTaskSnapshot(DroneMode.byId(serviceResumeMode), false, false));
			if (rechargeReclaimTargetUuid != null) taskStack.suspend(new DroneTaskStack.Task(
				DroneTaskStack.Kind.COMBAT, DroneMode.byId(serviceResumeMode), SalvageState.IDLE,
				rechargeReclaimTargetUuid, activeAssignmentKey()));
		}
		if (powerLossTaskCaptured) {
			taskStack.suspend(currentTaskSnapshot(DroneMode.byId(powerLossResumeMode), false, false));
		}
		if (solarTaskSuspended) {
			taskStack.suspend(currentTaskSnapshot(mode(), false, false));
		}
		setNoGravity(true);
	}

	private record ContainerAccessKey(ServerLevel level, long blockPos) {
	}
}
