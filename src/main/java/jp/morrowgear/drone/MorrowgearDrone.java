package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.function.Function;

import jp.morrowgear.drone.block.DockBlockEntity;
import jp.morrowgear.drone.block.DockCenterBlock;
import jp.morrowgear.drone.block.DockPartBlock;
import jp.morrowgear.drone.item.DockKitItem;
import jp.morrowgear.drone.item.DroneUnitItem;
import jp.morrowgear.drone.item.RecoveryToolItem;
import jp.morrowgear.drone.item.SolarServiceStationItem;
import jp.morrowgear.drone.network.DroneCommandPayload;
import jp.morrowgear.drone.network.FleetOperationPayload;
import jp.morrowgear.drone.network.PowerLostBeaconPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.TagKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MorrowgearDrone implements ModInitializer {
	public static final String MOD_ID = "morrowgear_drone";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final TagKey<EntityType<?>> COMPAT_HOSTILE_ENTITIES = TagKey.create(Registries.ENTITY_TYPE,
		Identifier.fromNamespaceAndPath(MOD_ID, "compat_hostile"));
	public static final TagKey<EntityType<?>> COMPAT_FRIENDLY_ENTITIES = TagKey.create(Registries.ENTITY_TYPE,
		Identifier.fromNamespaceAndPath(MOD_ID, "compat_friendly"));
	public static final TagKey<EntityType<?>> COMPAT_HIGH_VALUE_ENTITIES = TagKey.create(Registries.ENTITY_TYPE,
		Identifier.fromNamespaceAndPath(MOD_ID, "compat_high_value"));
	static final TicketType DRONE_OPERATION_TICKET = new TicketType(100L,
		TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
			| TicketType.FLAG_KEEP_DIMENSION_ACTIVE | TicketType.FLAG_CAN_EXPIRE_IF_UNLOADED);
	private static final Map<UUID, CachedDroneRoster> DRONE_ROSTERS = new HashMap<>();

	public static final EntityType<DroneEntity> DRONE = registerEntity(
		"field_drone",
		EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC)
			.sized(1.45f, 0.7f)
			.clientTrackingRange(12)
			.updateInterval(2)
	);
	public static final EntityType<MorrowgearMissileEntity> MISSILE = registerEntity(
		"security_missile",
		EntityType.Builder.<MorrowgearMissileEntity>of(MorrowgearMissileEntity::new, MobCategory.MISC)
			.sized(0.28f, 0.28f)
			.clientTrackingRange(12)
			.updateInterval(1)
	);
	public static final EntityType<SolarServiceStationEntity> SOLAR_SERVICE_STATION = registerEntity(
		"solar_service_station",
		EntityType.Builder.<SolarServiceStationEntity>of(SolarServiceStationEntity::new, MobCategory.MISC)
			.sized(3.6f, 1.05f).clientTrackingRange(16).updateInterval(2)
	);
	public static final EntityType<ChargingRelayEntity> CHARGING_RELAY = registerEntity(
		"charging_relay",
		EntityType.Builder.<ChargingRelayEntity>of(ChargingRelayEntity::new, MobCategory.MISC)
			.sized(0.9f, 0.38f).clientTrackingRange(16).updateInterval(1)
	);

	public static final Item CONTROLLER = registerItem("controller", Item::new, new Item.Properties().stacksTo(1));
	public static final Item SCOUT_MODULE = registerItem("scout_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item CARGO_MODULE = registerItem("cargo_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item ENGINEER_MODULE = registerItem("engineer_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item SECURITY_MODULE = registerItem("security_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item SALVAGE_MODULE = registerItem("salvage_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item RECOVERY_TOOL = registerItem("recovery_tool", RecoveryToolItem::new,
		new Item.Properties().stacksTo(1).durability(192));
	public static final Item POWER_CELL = registerItem("power_cell", Item::new, new Item.Properties().stacksTo(16));
	public static final Item RAW_MORROW_COMPOSITE = registerItem("raw_morrow_composite", Item::new, new Item.Properties());
	public static final Item MORROW_ALLOY = registerItem("morrow_alloy", Item::new, new Item.Properties());
	public static final Item LIGHTWEIGHT_FRAME = registerItem("lightweight_frame", Item::new, new Item.Properties());
	public static final Item BASIC_CONTROL_BOARD = registerItem("basic_control_board", Item::new, new Item.Properties());
	public static final Item FLIGHT_ACTUATOR = registerItem("flight_actuator", Item::new, new Item.Properties());
	public static final Item STANDARD_BATTERY_PACK = registerItem("standard_battery_pack", Item::new, new Item.Properties().stacksTo(16));
	public static final Item REINFORCED_BATTERY_PACK = registerItem("reinforced_battery_pack", Item::new, new Item.Properties().stacksTo(16));
	public static final Item HIGH_DENSITY_BATTERY_PACK = registerItem("high_density_battery_pack", Item::new, new Item.Properties().stacksTo(16));
	public static final Item AUTOCANNON_MODULE = registerItem("autocannon_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item LASER_MODULE = registerItem("laser_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item MISSILE_MODULE = registerItem("missile_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item TACTICAL_VISOR = registerItem("tactical_visor", Item::new,
		new Item.Properties().stacksTo(1).fireResistant().equippable(EquipmentSlot.HEAD));
	public static final Item SOLAR_SERVICE_STATION_ITEM = registerItem("solar_service_station",
		SolarServiceStationItem::new, new Item.Properties().stacksTo(4));
	public static final SoundEvent FLIGHT_IDLE_SOUND = registerSound("flight_idle", 48.0f);
	public static final SoundEvent FLIGHT_CRUISE_SOUND = registerSound("flight_cruise", 64.0f);
	public static final SoundEvent AUTOCANNON_BURST_SOUND = registerSound("autocannon_burst", 64.0f);
	public static final SoundEvent LASER_CHARGE_SOUND = registerSound("laser_charge", 36.0f);
	public static final SoundEvent LASER_FIRE_SOUND = registerSound("laser_fire", 48.0f);
	public static final SoundEvent LASER_HIT_SOUND = registerSound("laser_hit", 40.0f);
	public static final SoundEvent LASER_SHUTDOWN_SOUND = registerSound("laser_shutdown", 40.0f);
	public static final Block[] DOCK_PARTS = new Block[9];
	public static final DockCenterBlock DOCK_CENTER;
	public static final Item DRONE_UNIT;
	public static final Item DOCK_ITEM;
	public static final BlockEntityType<DockBlockEntity> DOCK_BLOCK_ENTITY;

	static {
		for (int i = 0; i < DOCK_PARTS.length; i++) {
			if (i == 4) continue;
			int x = i % 3 - 1;
			int z = i / 3 - 1;
			DOCK_PARTS[i] = registerBlock("dock_part_" + i,
				properties -> new DockPartBlock(properties, x, z), dockProperties());
		}
		DOCK_CENTER = registerBlock("dock_part_4", DockCenterBlock::new, dockProperties());
		DOCK_PARTS[4] = DOCK_CENTER;
		DRONE_UNIT = registerItem("field_drone_unit", DroneUnitItem::new, new Item.Properties().stacksTo(1));
		DOCK_ITEM = registerItem("dock_item", DockKitItem::new, new Item.Properties().stacksTo(16));
		DOCK_BLOCK_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			Identifier.fromNamespaceAndPath(MOD_ID, "dock"),
			FabricBlockEntityTypeBuilder.create(DockBlockEntity::new, DOCK_CENTER).build()
		);
	}

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(DRONE, DroneEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(SOLAR_SERVICE_STATION, SolarServiceStationEntity.createAttributes());
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
			entries.accept(CONTROLLER);
			entries.accept(DRONE_UNIT);
			entries.accept(DOCK_ITEM);
			entries.accept(SCOUT_MODULE);
			entries.accept(CARGO_MODULE);
			entries.accept(ENGINEER_MODULE);
			entries.accept(SECURITY_MODULE);
			entries.accept(SALVAGE_MODULE);
			entries.accept(RECOVERY_TOOL);
			entries.accept(POWER_CELL);
			entries.accept(RAW_MORROW_COMPOSITE);
			entries.accept(MORROW_ALLOY);
			entries.accept(LIGHTWEIGHT_FRAME);
			entries.accept(BASIC_CONTROL_BOARD);
			entries.accept(FLIGHT_ACTUATOR);
			entries.accept(STANDARD_BATTERY_PACK);
			entries.accept(REINFORCED_BATTERY_PACK);
			entries.accept(HIGH_DENSITY_BATTERY_PACK);
			entries.accept(AUTOCANNON_MODULE);
			entries.accept(LASER_MODULE);
			entries.accept(MISSILE_MODULE);
			entries.accept(TACTICAL_VISOR);
			entries.accept(SOLAR_SERVICE_STATION_ITEM);
		});

		PayloadTypeRegistry.serverboundPlay().register(DroneCommandPayload.TYPE, DroneCommandPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(FleetOperationPayload.TYPE, FleetOperationPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PowerLostBeaconPayload.TYPE, PowerLostBeaconPayload.CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			for (PowerLostBeaconData.Beacon beacon : PowerLostBeaconData.get(server).forOwner(player.getUUID())) {
				ServerPlayNetworking.send(player, new PowerLostBeaconPayload(beacon.unitId(), beacon.dimension(),
					beacon.x(), beacon.y(), beacon.z(), true, beacon.tick()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(FleetOperationPayload.TYPE, (payload, context) ->
			dispatchFleetOperation(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(DroneCommandPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!DroneCommandPolicy.acceptablePayload(payload.action())) return;
			Entity entity = player.level().getEntity(payload.entityId());
			if (!(entity instanceof DroneEntity drone) || !drone.isOwnedBy(player)) return;
			if (payload.action().startsWith("weapon_module:")) {
				String loadoutId = payload.action().substring("weapon_module:".length());
				if (SecurityLoadout.isValidId(loadoutId)) {
					changeSecurityLoadout(player, drone, SecurityLoadout.byId(loadoutId));
				}
				return;
			}
			if (payload.action().startsWith("module:")) {
				changeRoleModule(player, drone, DroneRole.byId(payload.action().substring("module:".length())));
				return;
			}
			if (payload.action().startsWith("wing_join:") || payload.action().startsWith("wing_create:")) {
				String target = payload.action().substring(payload.action().indexOf(':') + 1);
				moveToWing(player, drone, target);
				return;
			}
			if (payload.action().equals("wing_leave")) {
				leaveWing(player, drone);
				return;
			}
			if (payload.action().startsWith("group:")) {
				moveToWing(player, drone, payload.action().substring("group:".length()));
				return;
			}
			if (payload.action().startsWith("assign_dock:")) {
				try {
					assignDock(player, drone, BlockPos.of(Long.parseLong(payload.action().substring("assign_dock:".length()))));
				} catch (NumberFormatException ignored) {
					LOGGER.warn("Rejected malformed dock assignment from {}", player.getScoreboardName());
				}
				return;
			}
			if (payload.action().startsWith("cargo_source:") || payload.action().startsWith("cargo_target:")) {
				assignCargoEndpoint(player, drone, payload.action());
				return;
			}
			if (payload.action().startsWith("guard:")) {
				String[] data = payload.action().split(":");
				try {
					if (data.length != 6) return;
					BlockPos anchor = new BlockPos(Integer.parseInt(data[1]), Integer.parseInt(data[2]), Integer.parseInt(data[3]));
					int radius = Integer.parseInt(data[4]);
					String orderId = data[5];
					if (!MissionAssignmentPolicy.allows(drone.role(), MissionAssignmentPolicy.MissionKind.SECURITY_PATROL)
						|| anchor.distSqr(player.blockPosition()) > 512 * 512
						|| !DroneCommandPolicy.validSecurityRadius(radius)
						|| !DroneCommandPolicy.validMission(orderId, 1, 0)) return;
					drone.assignSecurityPatrol(anchor, radius, orderId);
					player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId()
						+ " / SECURITY PATROL / R" + radius + " / " + anchor.toShortString()));
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
					LOGGER.warn("Rejected malformed security patrol from {}", player.getScoreboardName());
				}
				return;
			}
			if (payload.action().startsWith("work:")) {
				String[] data = payload.action().split(":");
				try {
					if (data.length != 7) return;
					FieldOperationType type = FieldOperationType.byId(data[1]);
					BlockPos requestedAnchor = new BlockPos(Integer.parseInt(data[2]), Integer.parseInt(data[3]), Integer.parseInt(data[4]));
					int radius = Integer.parseInt(data[5]);
					String orderId = data[6];
					if (type == FieldOperationType.NONE
						|| !MissionAssignmentPolicy.allows(drone.role(), MissionAssignmentPolicy.MissionKind.FIELD_OPERATION)
						|| requestedAnchor.distSqr(player.blockPosition()) > 512 * 512
						|| !DroneCommandPolicy.validFieldRadius(type, radius)
						|| !DroneCommandPolicy.validMission(orderId, 1, 0)) return;
					ServerLevel level = player.level();
					int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						requestedAnchor.getX(), requestedAnchor.getZ());
					int anchorY = switch (type) {
						case ORE -> surfaceY - 16;
						case EXCAVATE, FORESTRY -> surfaceY - 1;
						default -> requestedAnchor.getY();
					};
					BlockPos anchor = new BlockPos(requestedAnchor.getX(), anchorY, requestedAnchor.getZ());
					drone.clearSecurityPatrol();
					if (MissionCommandPolicy.preemptsCargoRoute(payload.action())) drone.pauseCargoRoute();
					drone.assignFieldOperation(type, anchor, radius, orderId);
					player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " / "
						+ type.label() + " / R" + radius + " / " + anchor.toShortString()));
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
					LOGGER.warn("Rejected malformed field operation from {}", player.getScoreboardName());
				}
				return;
			}
			if (payload.action().startsWith("move:")) {
				String[] coordinates = payload.action().substring("move:".length()).split(":");
				try {
					int x = Integer.parseInt(coordinates[0]);
					int z = Integer.parseInt(coordinates[1]);
					if (Math.abs(x - player.getBlockX()) > 512 || Math.abs(z - player.getBlockZ()) > 512) return;
					ServerLevel level = player.level();
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					String missionId = coordinates.length > 2 ? coordinates[2] : drone.getUUID().toString();
					int expected = coordinates.length > 3 ? Integer.parseInt(coordinates[3]) : 1;
					int index = coordinates.length > 4 ? Integer.parseInt(coordinates[4]) : 0;
					if (coordinates.length > 5 || !DroneCommandPolicy.validMission(missionId, expected, index)) return;
					drone.clearSecurityPatrol();
					if (MissionCommandPolicy.preemptsCargoRoute(payload.action())) drone.pauseCargoRoute();
					drone.assignWaypoint(new BlockPos(x, y, z), missionId, expected, index,
						player.blockPosition().above(6), level.getGameTime());
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
					LOGGER.warn("Rejected malformed waypoint from {}", player.getScoreboardName());
				}
				return;
			}
			if (payload.action().startsWith("track:")) {
				String[] targetData = payload.action().substring("track:".length()).split(":");
				try {
					int targetId = Integer.parseInt(targetData[0]);
					Entity target = player.level().getEntity(targetId);
					if (!(target instanceof LivingEntity living) || !living.isAlive() || living == player
						|| living.distanceTo(player) > 512.0) return;
					String missionId = targetData.length > 1 ? targetData[1] : drone.getUUID().toString();
					int expected = targetData.length > 2 ? Integer.parseInt(targetData[2]) : 1;
					int index = targetData.length > 3 ? Integer.parseInt(targetData[3]) : 0;
					if (targetData.length > 4 || !DroneCommandPolicy.validMission(missionId, expected, index)) return;
					drone.clearSecurityPatrol();
					if (MissionCommandPolicy.preemptsCargoRoute(payload.action())) drone.pauseCargoRoute();
					drone.assignTrackingTarget(living, missionId, expected, index,
						player.blockPosition().above(6), player.level().getGameTime());
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
					LOGGER.warn("Rejected malformed tracking assignment from {}", player.getScoreboardName());
				}
				return;
			}
			if (payload.action().startsWith("patrol:")) {
				String[] data = payload.action().split(":", 8);
				try {
					if (data.length != 5 && data.length != 8) return;
					String missionId = data[1];
					int expected = Integer.parseInt(data[2]);
					int index = Integer.parseInt(data[3]);
					int startIndex = data.length == 8 ? Integer.parseInt(data[4]) : 0;
					int originX = data.length == 8 ? Integer.parseInt(data[5]) : player.getBlockX();
					int originZ = data.length == 8 ? Integer.parseInt(data[6]) : player.getBlockZ();
					List<BlockPos> route = PatrolRoutePolicy.decode(data[data.length - 1]);
					if (!DroneCommandPolicy.validMission(missionId, expected, index)
						|| route.isEmpty() || route.size() > PatrolRoutePolicy.MAX_POINTS
						|| startIndex < 0 || startIndex >= route.size()
						|| Math.abs(originX - player.getBlockX()) > 512
						|| Math.abs(originZ - player.getBlockZ()) > 512
						|| route.stream().anyMatch(point -> Math.abs(point.getX() - player.getBlockX()) > 512
							|| Math.abs(point.getZ() - player.getBlockZ()) > 512)) return;
					drone.clearSecurityPatrol();
					if (MissionCommandPolicy.preemptsCargoRoute(payload.action())) drone.pauseCargoRoute();
					drone.assignPatrolRoute(route, missionId, expected, index,
						new BlockPos(originX, player.getBlockY() + 6, originZ), player.level().getGameTime(), startIndex);
				} catch (NumberFormatException ignored) {
					LOGGER.warn("Rejected malformed patrol route from {}", player.getScoreboardName());
				}
				return;
			}
			if (payload.action().startsWith("follow:")) {
				String[] formationData = payload.action().substring("follow:".length()).split(":");
				try {
					String missionId = formationData[0];
					int expected = Integer.parseInt(formationData[1]);
					int index = Integer.parseInt(formationData[2]);
					if (formationData.length != 4 || !DroneCommandPolicy.validMission(missionId, expected, index)) return;
					Entity leaderEntity = player.level().getEntity(Integer.parseInt(formationData[3]));
					if (!(leaderEntity instanceof DroneEntity leader) || !leader.isOwnedBy(player)) return;
					drone.clearSecurityPatrol();
					if (MissionCommandPolicy.preemptsCargoRoute(payload.action())) drone.pauseCargoRoute();
					drone.assignFollowFormation(missionId, expected, index, leader.unitId(),
						player.blockPosition().above(4), player.level().getGameTime());
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
					LOGGER.warn("Rejected malformed follow formation from {}", player.getScoreboardName());
				}
				return;
			}
			if (payload.action().equals("decommission")) {
				storeDrone(player, drone);
				return;
			}
			if (!DroneCommandPolicy.isSimpleAction(payload.action())) return;
			if (payload.action().equals("dock") && !drone.hasDock()) {
				player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " / Dock未割当"));
				return;
			}
			if (payload.action().equals("standby") || payload.action().equals("return")
				|| payload.action().equals("dock") || payload.action().equals("orbit")) {
				drone.clearFieldOperation();
				drone.clearSecurityPatrol();
				if (MissionCommandPolicy.preemptsCargoRoute(payload.action())) drone.pauseCargoRoute();
			}
			drone.setMode(DroneMode.fromAction(payload.action()));
		});
		MorrowgearRuntimeVerifier.register();
		MorrowgearBaseGenerator.register();

		LOGGER.info("Morrowgear Drone Command initialized for Minecraft 26.2");
	}

	private static void dispatchFleetOperation(ServerPlayer player, FleetOperationPayload payload) {
		FieldOperationType type = FieldOperationType.byId(payload.operationType());
		if (type == FieldOperationType.NONE || !DroneCommandPolicy.validFieldRadius(type, payload.radius())
			|| Math.abs(payload.x() - player.getBlockX()) > 512
			|| Math.abs(payload.z() - player.getBlockZ()) > 512) return;
		ServerLevel level = player.level();
		int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, payload.x(), payload.z());
		int anchorY = switch (type) {
			case ORE -> surfaceY - 16;
			case EXCAVATE, FORESTRY -> surfaceY - 1;
			case NONE -> surfaceY;
		};
		BlockPos anchor = new BlockPos(payload.x(), anchorY, payload.z());
		List<DroneEntity> fleet = ownedDrones(level, player, 512);
		List<AutonomousTaskForcePolicy.Candidate> candidates = fleet.stream()
			.map(drone -> automaticCandidate(drone, anchor)).toList();
		AutonomousTaskForcePolicy.Plan plan = AutonomousTaskForcePolicy.plan(type, payload.radius(), candidates);
		if (!plan.executable()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] AUTO OPS BLOCKED / " + plan.status()));
			return;
		}
		Map<Integer, DroneEntity> byId = new HashMap<>();
		fleet.forEach(drone -> byId.put(drone.getId(), drone));
		String orderId = "AUTO-" + Long.toString(level.getGameTime(), 36).toUpperCase()
			+ "-" + type.id().toUpperCase() + "-" + payload.x() + "-" + payload.z();
		for (AutonomousTaskForcePolicy.Candidate selected : plan.selected()) {
			DroneEntity drone = byId.get(selected.entityId());
			if (drone == null) continue;
			drone.clearSecurityPatrol();
			if (drone.role() == DroneRole.CARGO) drone.pauseCargoRoute();
			drone.assignFieldOperation(type, anchor, payload.radius(), orderId);
		}
		player.sendSystemMessage(Component.literal("[MORROWGEAR] AUTO OPS / " + type.label()
			+ " / " + plan.selected().size() + " UNITS / " + plan.rosterLabel()));
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + plan.missingLabel()));
	}

	private static AutonomousTaskForcePolicy.Candidate automaticCandidate(DroneEntity drone, BlockPos anchor) {
		boolean cargoRouteActive = drone.role() == DroneRole.CARGO && drone.hasCargoSource()
			&& drone.hasCargoTarget() && !drone.cargoPaused();
		boolean idle = AutonomousTaskForcePolicy.operationallyIdle(drone.isPowerLost(),
			drone.serviceReturnActive(), drone.combatActive(), drone.emergencyInterceptActive(),
			drone.recoveryLevel(), drone.hasActiveFieldOperation(), drone.hasSecurityPatrol(),
			drone.salvageTargetEntityId() >= 0, cargoRouteActive, drone.mode(), drone.isDocked());
		int health = Math.round(drone.getHealth() * 100.0f / Math.max(1.0f, drone.getMaxHealth()));
		return new AutonomousTaskForcePolicy.Candidate(drone.getId(), drone.unitId(), drone.role(), idle,
			drone.isDocked(), drone.batteryPercent(), health, drone.distanceToSqr(Vec3.atCenterOf(anchor)));
	}

	private static void moveToWing(ServerPlayer player, DroneEntity drone, String targetGroup) {
		ServerLevel level = player.level();
		List<DroneEntity> fleet = ownedDrones(level, player, 512);
		int targetSize = (int) fleet.stream().filter(member -> member.groupId().equals(targetGroup)).count();
		WingMembershipPolicy.Result result = WingMembershipPolicy.evaluate(drone.groupId(), targetGroup, targetSize);
		if (result == WingMembershipPolicy.Result.UNCHANGED) return;
		if (result != WingMembershipPolicy.Result.ACCEPTED) {
			String reason = result == WingMembershipPolicy.Result.WING_FULL ? "WING FULL 8/8" : "INVALID WING";
			player.sendSystemMessage(Component.literal("[MORROWGEAR] " + reason));
			return;
		}

		String sourceGroup = drone.groupId();
		drone.assignGroup(targetGroup);
		List<DroneEntity> sourceMembers = fleet.stream()
			.filter(member -> member != drone && member.groupId().equals(sourceGroup)).toList();
		List<DroneEntity> targetMembers = fleet.stream()
			.filter(member -> member == drone || member.groupId().equals(targetGroup)).toList();
		if (sourceGroup.startsWith("WING-")) reslotWingMission(level, player, sourceMembers, null);
		reslotWingMission(level, player, targetMembers, drone);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " JOIN "
			+ targetGroup + " " + targetMembers.size() + "/" + WingMembershipPolicy.MAX_MEMBERS));
	}

	private static void leaveWing(ServerPlayer player, DroneEntity drone) {
		if (!drone.groupId().startsWith("WING-")) return;
		ServerLevel level = player.level();
		String sourceGroup = drone.groupId();
		List<DroneEntity> remaining = ownedDrones(level, player, 512).stream()
			.filter(member -> member != drone && member.groupId().equals(sourceGroup)).toList();
		drone.assignGroup("ALPHA");
		drone.clearFieldOperation();
		drone.setMode(DroneMode.STANDBY);
		reslotWingMission(level, player, remaining, null);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " LEFT " + sourceGroup));
	}

	private static void reslotWingMission(ServerLevel level, ServerPlayer player, List<DroneEntity> members,
		DroneEntity joining) {
		if (members.isEmpty()) return;
		List<DroneEntity> ordered = new ArrayList<>(members);
		DroneEntity template = ordered.stream()
			.filter(member -> member != joining && (member.hasActiveFieldOperation()
				|| member.mode() == DroneMode.WAYPOINT || member.mode() == DroneMode.FOLLOW))
			.findFirst().orElse(null);
		if (template == null) {
			if (joining != null) joining.setMode(DroneMode.STANDBY);
			return;
		}
		ordered.sort(Comparator
			.comparingInt((DroneEntity member) -> member == template ? 0 : 1)
			.thenComparingDouble(member -> member.distanceToSqr(template))
			.thenComparing(DroneEntity::unitId));
		long assignedTick = level.getGameTime();
		String missionId = template.missionId().isBlank()
			? "wing-" + Long.toString(assignedTick, 36) : template.missionId();

		if (template.hasActiveFieldOperation()) {
			if (joining != null && joining.role() != DroneRole.FIELD && joining.role() != DroneRole.SECURITY) {
				joining.assignFieldOperation(template.fieldOperationType(), template.fieldAnchor(),
					template.fieldRadius(), template.fieldOrderId());
			}
			return;
		}
		if (template.mode() == DroneMode.FOLLOW) {
			for (int index = 0; index < ordered.size(); index++) {
				ordered.get(index).assignFollowFormation(missionId, ordered.size(), index,
					template.unitId(), player.blockPosition().above(4), assignedTick);
			}
			return;
		}
		if (template.mode() != DroneMode.WAYPOINT || !template.hasWaypoint()) return;
		Entity tracked = template.hasTrackingTarget() ? level.getEntityInAnyDimension(template.trackingTargetId()) : null;
		for (int index = 0; index < ordered.size(); index++) {
			DroneEntity member = ordered.get(index);
			if (tracked instanceof LivingEntity living && living.isAlive()) {
				member.assignTrackingTarget(living, missionId, ordered.size(), index,
					template.blockPosition(), assignedTick);
			} else {
				member.assignWaypoint(template.waypointPos(), missionId, ordered.size(), index,
					template.blockPosition(), assignedTick);
			}
		}
	}

	public static boolean deploy(ServerPlayer player) {
		return deploy(player, ItemStack.EMPTY);
	}

	public static boolean deploy(ServerPlayer player, ItemStack sourceStack) {
		if (!(player.level() instanceof ServerLevel level)) return false;
		DroneEntity drone = DRONE.create(level, EntitySpawnReason.TRIGGERED);
		if (drone == null) return false;
		List<DroneEntity> drones = ownedDrones(level, player, 512);
		int index = drones.size();
		Vec3 forward = player.getLookAngle().multiply(1, 0, 1);
		if (forward.lengthSqr() < 0.01) forward = new Vec3(0, 0, 1);
		forward = forward.normalize();
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		int layer = index / 6;
		double angle = index * 2.399963;
		double radius = 2.8 + layer * 1.2;
		Vec3 spawn = player.position()
			.add(forward.scale(3.0))
			.add(right.scale(Math.cos(angle) * radius))
			.add(0, 2.7 + (index % 3) * 1.15, Math.sin(angle) * radius);
		drone.setPos(spawn.x, spawn.y, spawn.z);
		drone.initializeOwner(player);
		StoredDroneState stored = sourceStack == null ? null : StoredDroneState.read(sourceStack);
		if (stored != null) {
			drone.restoreStoredUnit(stored);
			drone.restoreStoredCargo(sourceStack);
		}

		Set<Long> occupied = new HashSet<>();
		for (DroneEntity existing : drones) if (existing.hasDock()) occupied.add(existing.dockPos().asLong());
		findDocks(level, player, 64).stream()
			.filter(dock -> !occupied.contains(dock.getBlockPos().asLong()))
			.findFirst()
			.ifPresent(dock -> drone.assignDock(dock.getBlockPos()));
		level.addFreshEntity(drone);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId()
			+ (stored == null ? " を展開しました。" : " を再展開しました。")));
		return true;
	}

	public static boolean deployAtDock(ServerPlayer player, ItemStack sourceStack, BlockPos dockPos,
		ItemStack battery, ItemStack roleModule, ItemStack weaponModule) {
		if (!(player.level() instanceof ServerLevel level)) return false;
		if (!(level.getBlockEntity(dockPos) instanceof DockBlockEntity dock) || !dock.isOwnedBy(player)) return false;
		for (DroneEntity existing : ownedDrones(level, player, 512)) {
			if (existing.hasDock() && existing.dockPos().equals(dockPos)) return false;
		}
		DroneEntity drone = DRONE.create(level, EntitySpawnReason.TRIGGERED);
		if (drone == null) return false;
		drone.setPos(dockPos.getX() + 0.5, dockPos.getY() + 0.45, dockPos.getZ() + 0.5);
		drone.initializeOwner(player);
		StoredDroneState stored = StoredDroneState.read(sourceStack);
		if (stored != null) {
			drone.restoreStoredUnit(stored);
			drone.restoreStoredCargo(sourceStack);
		}
		BatteryTier requestedBattery = batteryTierForItem(battery.getItem());
		if (requestedBattery != null) drone.installBatteryForCommission(requestedBattery);
		DroneRole requestedRole = roleForModule(roleModule.getItem());
		if (requestedRole != null) drone.assignRole(requestedRole);
		SecurityLoadout loadout = loadoutForModule(weaponModule.getItem());
		if (requestedRole == DroneRole.SECURITY && loadout != null) drone.assignSecurityLoadout(loadout);
		drone.assignDock(dockPos);
		drone.setDockedForCommission();
		level.addFreshEntity(drone);
		return true;
	}

	public static void sendPowerLostBeacon(ServerPlayer player, DroneEntity drone, boolean active) {
		if (player == null || drone == null) return;
		if (!active) {
			clearPowerLostBeacon(player.level().getServer(), player.getUUID(), drone.unitId());
			return;
		}
		PowerLostBeaconData data = PowerLostBeaconData.get(player.level().getServer());
		data.update(player.getUUID(), drone.unitId(), drone.level().dimension().toString(),
			drone.getX(), drone.getY(), drone.getZ(), drone.level().getGameTime());
		ServerPlayNetworking.send(player, new PowerLostBeaconPayload(drone.unitId(),
			drone.level().dimension().toString(), drone.getX(), drone.getY(), drone.getZ(), true,
			drone.level().getGameTime()));
	}

	public static void clearPowerLostBeacon(MinecraftServer server, UUID ownerId, String unitId) {
		if (server == null || ownerId == null || unitId == null || unitId.isBlank()) return;
		PowerLostBeaconData.get(server).remove(ownerId, unitId);
		ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
		if (owner != null) ServerPlayNetworking.send(owner, new PowerLostBeaconPayload(unitId, "", 0, 0, 0,
			false, server.overworld().getGameTime()));
	}

	public static void assignDock(ServerPlayer player, DroneEntity drone, BlockPos pos) {
		if (!(player.level() instanceof ServerLevel level)) return;
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof DockBlockEntity dock) || !dock.isOwnedBy(player)) return;
		for (DroneEntity other : ownedDrones(level, player, 512)) {
			if (other != drone && other.hasDock() && other.dockPos().equals(pos)) other.clearDock();
		}
		drone.assignDock(pos);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " に " + dock.dockId() + " を割り当てました。"));
	}

	public static void changeRoleModule(ServerPlayer player, DroneEntity drone, DroneRole requestedRole) {
		boolean validDock = drone.isDocked() && drone.hasDock()
			&& player.level().getBlockEntity(drone.dockPos()) instanceof DockBlockEntity dock
			&& dock.isOwnedBy(player);
		Item requestedModule = moduleForRole(requestedRole);
		boolean moduleAvailable = requestedModule == null || hasOne(player, requestedModule);
		RoleModulePolicy.Result result = RoleModulePolicy.evaluate(validDock, player.isCreative(), moduleAvailable,
			drone.role(), requestedRole);
		if (result == RoleModulePolicy.Result.NOT_DOCKED) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Module Bay is available only while docked."));
			return;
		}
		if (result == RoleModulePolicy.Result.UNCHANGED) return;
		if (result == RoleModulePolicy.Result.MODULE_MISSING) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Required " + requestedRole.displayName() + " module was not found."));
			return;
		}
		DroneRole currentRole = drone.role();
		if (currentRole == DroneRole.CARGO && requestedRole != DroneRole.CARGO) returnCargo(player, drone);
		if (currentRole == DroneRole.SECURITY && requestedRole != DroneRole.SECURITY && !player.isCreative()) {
			giveModule(player, moduleForLoadout(drone.securityLoadout()));
			drone.assignSecurityLoadout(SecurityLoadout.UNARMED);
		}
		if (!player.isCreative() && requestedModule != null) consumeOne(player, requestedModule);
		if (!player.isCreative()) giveModule(player, moduleForRole(currentRole));
		drone.assignRole(requestedRole);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " / MODULE " + requestedRole.displayName()));
	}

	public static void changeSecurityLoadout(ServerPlayer player, DroneEntity drone,
		SecurityLoadout requestedLoadout) {
		boolean validDock = drone.isDocked() && drone.hasDock()
			&& player.level().getBlockEntity(drone.dockPos()) instanceof DockBlockEntity dock
			&& dock.isOwnedBy(player);
		Item requestedModule = moduleForLoadout(requestedLoadout);
		WeaponModulePolicy.Result result = WeaponModulePolicy.evaluate(drone.role() == DroneRole.SECURITY,
			validDock, player.isCreative(), requestedModule != null && hasOne(player, requestedModule),
			drone.securityLoadout(), requestedLoadout);
		if (result == WeaponModulePolicy.Result.NOT_SECURITY) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Weapon module requires a Security unit."));
			return;
		}
		if (result == WeaponModulePolicy.Result.NOT_DOCKED) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Weapon Bay is available only while docked."));
			return;
		}
		if (result == WeaponModulePolicy.Result.MODULE_MISSING) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Required weapon module was not found."));
			return;
		}
		if (result == WeaponModulePolicy.Result.PHYSICAL_MODULE_REQUIRED) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Select an installed physical weapon module."));
			return;
		}
		if (result == WeaponModulePolicy.Result.UNCHANGED) return;
		if (!player.isCreative()) {
			consumeOne(player, requestedModule);
			giveModule(player, moduleForLoadout(drone.securityLoadout()));
		}
		drone.assignSecurityLoadout(requestedLoadout);
		CombatTheaterCoordinator.clear();
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId()
			+ " / WEAPON " + requestedLoadout.displayName()));
	}

	private static void returnInstalledModule(ServerPlayer player, DroneEntity drone) {
		returnCargo(player, drone);
		if (!player.isCreative()) {
			giveModule(player, moduleForRole(drone.role()));
			giveModule(player, moduleForLoadout(drone.securityLoadout()));
		}
		drone.assignSecurityLoadout(SecurityLoadout.UNARMED);
		drone.assignRole(DroneRole.FIELD);
	}

	static void storeDrone(ServerPlayer player, DroneEntity drone) {
		ItemStack installedModule = moduleForRole(drone.role()) == null
			? ItemStack.EMPTY : new ItemStack(moduleForRole(drone.role()));
		List<ItemStack> returned = DroneStoragePolicy.returnedItems(new ItemStack(DRONE_UNIT), installedModule,
			drone.removeAllCargo(), player.isCreative());
		if (!player.isCreative()) {
			giveModule(player, moduleForLoadout(drone.securityLoadout()));
			if (drone.batteryTier() != BatteryTier.STANDARD)
				giveModule(player, itemForBatteryTier(drone.batteryTier()));
		}
		drone.assignSecurityLoadout(SecurityLoadout.UNARMED);
		drone.assignRole(DroneRole.FIELD);
		if (player.level() instanceof ServerLevel level) {
			drone.transferLeadershipBeforeRemoval(level, player);
			drone.clearDroneLight(level);
		}
		drone.discard();
		for (ItemStack stack : returned) giveStack(player, stack);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " を格納しました。"));
	}

	public static boolean recoverPowerLost(ServerPlayer player, DroneEntity drone) {
		if (player == null || drone == null || !PowerLossPolicy.manuallyRecoverable(
			drone.isOwnedBy(player), drone.isPowerLost(), player.distanceTo(drone))) return false;
		ItemStack recovered = drone.createStoredUnit();
		if (player.level() instanceof ServerLevel level) {
			drone.transferLeadershipBeforeRemoval(level, player);
			drone.clearDroneLight(level);
		}
		sendPowerLostBeacon(player, drone, false);
		drone.discard();
		giveStack(player, recovered);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId()
			+ " / AIRFRAME RECOVERED"));
		return true;
	}

	private static void assignCargoEndpoint(ServerPlayer player, DroneEntity drone, String action) {
		if (!MissionAssignmentPolicy.allows(drone.role(), MissionAssignmentPolicy.MissionKind.CARGO_ROUTE)) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Cargo module is required."));
			return;
		}
		try {
			boolean source = action.startsWith("cargo_source:");
			BlockPos pos = BlockPos.of(Long.parseLong(action.substring(action.indexOf(':') + 1)));
			if (pos.distSqr(player.blockPosition()) > 32 * 32
				|| !(player.level().getBlockEntity(pos) instanceof net.minecraft.world.Container)) return;
			if (source) drone.assignCargoSource(pos); else drone.assignCargoTarget(pos);
			player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " / "
				+ (source ? "CARGO SOURCE" : "CARGO TARGET") + " " + pos.toShortString()));
		} catch (NumberFormatException ignored) {
			LOGGER.warn("Rejected malformed cargo assignment from {}", player.getScoreboardName());
		}
	}

	private static void returnCargo(ServerPlayer player, DroneEntity drone) {
		for (ItemStack stack : drone.removeAllCargo()) {
			if (!player.getInventory().add(stack)) player.drop(stack, false);
		}
	}

	private static boolean consumeOne(ServerPlayer player, Item item) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.is(item)) continue;
			stack.shrink(1);
			return true;
		}
		return false;
	}

	private static boolean hasOne(ServerPlayer player, Item item) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (player.getInventory().getItem(slot).is(item)) return true;
		}
		return false;
	}

	private static void giveModule(ServerPlayer player, Item item) {
		giveItem(player, item);
	}

	private static void giveItem(ServerPlayer player, Item item) {
		if (item == null) return;
		giveStack(player, new ItemStack(item));
	}

	private static void giveStack(ServerPlayer player, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		if (!player.getInventory().add(stack)) player.drop(stack, false);
	}

	public static Item moduleForRole(DroneRole role) {
		return switch (role) {
			case FIELD -> null;
			case SCOUT -> SCOUT_MODULE;
			case CARGO -> CARGO_MODULE;
			case ENGINEER -> ENGINEER_MODULE;
			case SECURITY -> SECURITY_MODULE;
			case SALVAGE -> SALVAGE_MODULE;
		};
	}

	public static Item moduleForLoadout(SecurityLoadout loadout) {
		if (loadout == null) return null;
		return switch (loadout) {
			case AUTOCANNON -> AUTOCANNON_MODULE;
			case LASER -> LASER_MODULE;
			case MISSILE -> MISSILE_MODULE;
			case UNARMED, AUTO -> null;
		};
	}

	public static Item itemForBatteryTier(BatteryTier tier) {
		return switch (tier == null ? BatteryTier.STANDARD : tier) {
			case STANDARD -> STANDARD_BATTERY_PACK;
			case REINFORCED -> REINFORCED_BATTERY_PACK;
			case HIGH_DENSITY -> HIGH_DENSITY_BATTERY_PACK;
		};
	}

	public static BatteryTier batteryTierForItem(Item item) {
		if (item == REINFORCED_BATTERY_PACK) return BatteryTier.REINFORCED;
		if (item == HIGH_DENSITY_BATTERY_PACK) return BatteryTier.HIGH_DENSITY;
		return item == STANDARD_BATTERY_PACK ? BatteryTier.STANDARD : null;
	}

	public static int batteryEnergyForItem(Item item) {
		if (item == POWER_CELL) return 1200;
		BatteryTier tier = batteryTierForItem(item);
		return tier == null ? 0 : tier.capacity();
	}

	public static DroneRole roleForModule(Item item) {
		if (item == SCOUT_MODULE) return DroneRole.SCOUT;
		if (item == CARGO_MODULE) return DroneRole.CARGO;
		if (item == ENGINEER_MODULE) return DroneRole.ENGINEER;
		if (item == SECURITY_MODULE) return DroneRole.SECURITY;
		if (item == SALVAGE_MODULE) return DroneRole.SALVAGE;
		return null;
	}

	public static SecurityLoadout loadoutForModule(Item item) {
		if (item == AUTOCANNON_MODULE) return SecurityLoadout.AUTOCANNON;
		if (item == LASER_MODULE) return SecurityLoadout.LASER;
		if (item == MISSILE_MODULE) return SecurityLoadout.MISSILE;
		return null;
	}

	public static List<DroneEntity> ownedDrones(ServerLevel level, ServerPlayer player, double range) {
		long tick = level.getGameTime();
		if (range >= 512.0) {
			CachedDroneRoster cached = DRONE_ROSTERS.get(player.getUUID());
			if (cached != null && cached.level() == level && cached.tick() == tick) return cached.drones();
			List<DroneEntity> drones = queryOwnedDrones(level, player, range);
			DRONE_ROSTERS.put(player.getUUID(), new CachedDroneRoster(level, tick, drones));
			return drones;
		}
		return queryOwnedDrones(level, player, range);
	}

	private static List<DroneEntity> queryOwnedDrones(ServerLevel level, ServerPlayer player, double range) {
		List<DroneEntity> drones = new ArrayList<>();
		if (range >= 512.0) {
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof DroneEntity drone && drone.isOwnedBy(player)) drones.add(drone);
			}
			drones.sort(Comparator.comparing(DroneEntity::unitId));
		} else {
			drones.addAll(level.getEntitiesOfClass(DroneEntity.class,
				player.getBoundingBox().inflate(range), drone -> drone.isOwnedBy(player)).stream()
				.sorted(Comparator.comparing(DroneEntity::unitId)).toList());
		}
		normalizeManualWingCapacity(drones);
		return List.copyOf(drones);
	}

	private static void normalizeManualWingCapacity(List<DroneEntity> drones) {
		Map<String, Integer> counts = new HashMap<>();
		for (DroneEntity drone : drones) {
			String group = drone.groupId();
			if (!group.startsWith("WING-")) continue;
			int index = counts.getOrDefault(group, 0);
			counts.put(group, index + 1);
			int partition = index / WingMembershipPolicy.MAX_MEMBERS;
			if (partition == 0) continue;
			String suffix = "-" + (partition + 1);
			String base = group.substring(0, Math.min(group.length(), 24 - suffix.length()));
			drone.assignGroup(base + suffix);
		}
	}

	private record CachedDroneRoster(ServerLevel level, long tick, List<DroneEntity> drones) {
	}

	public static List<DockBlockEntity> findDocks(ServerLevel level, ServerPlayer player, int range) {
		List<DockBlockEntity> docks = new ArrayList<>();
		int chunkRadius = Math.max(1, (range + 15) / 16);
		int centerX = player.chunkPosition().x();
		int centerZ = player.chunkPosition().z();
		for (int x = centerX - chunkRadius; x <= centerX + chunkRadius; x++) {
			for (int z = centerZ - chunkRadius; z <= centerZ + chunkRadius; z++) {
				if (!level.hasChunk(x, z)) continue;
				for (BlockEntity blockEntity : level.getChunk(x, z).getBlockEntities().values()) {
					if (blockEntity instanceof DockBlockEntity dock && dock.isOwnedBy(player)
						&& dock.getBlockPos().distSqr(player.blockPosition()) <= range * range) docks.add(dock);
				}
			}
		}
		docks.sort(Comparator.comparing(DockBlockEntity::dockId));
		return docks;
	}

	public static void removeDock(ServerLevel level, BlockPos center, boolean drop) {
		if (level.getBlockEntity(center) instanceof DockBlockEntity dock) {
			for (int slot = 0; slot < dock.getContainerSize(); slot++) {
				ItemStack stack = dock.removeItemNoUpdate(slot);
				if (!stack.isEmpty()) Block.popResource(level, center.above(), stack);
			}
		}
		boolean found = false;
		for (int i = 0; i < DOCK_PARTS.length; i++) {
			BlockPos part = center.offset(i % 3 - 1, 0, i / 3 - 1);
			Block stateBlock = level.getBlockState(part).getBlock();
			for (Block dockPart : DOCK_PARTS) {
				if (stateBlock != dockPart) continue;
				found = true;
				level.removeBlock(part, false);
				break;
			}
		}
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof DroneEntity drone && drone.hasDock() && drone.dockPos().equals(center)) drone.clearDock();
		}
		if (drop && found) Block.popResource(level, center, new ItemStack(DOCK_ITEM));
	}

	private static <T extends Entity> EntityType<T> registerEntity(String name, EntityType.Builder<T> builder) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, name));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
	}

	private static SoundEvent registerSound(String name, float range) {
		Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createFixedRangeEvent(id, range));
	}

	private static <T extends Item> T registerItem(String name, Function<Item.Properties, T> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, name));
		T item = factory.apply(properties.setId(key));
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	private static <T extends Block> T registerBlock(String name, Function<BlockBehaviour.Properties, T> factory, BlockBehaviour.Properties properties) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, name));
		T block = factory.apply(properties.setId(key));
		return Registry.register(BuiltInRegistries.BLOCK, key, block);
	}

	private static BlockBehaviour.Properties dockProperties() {
		return BlockBehaviour.Properties.of().strength(2.5f, 12.0f).noOcclusion();
	}
}
