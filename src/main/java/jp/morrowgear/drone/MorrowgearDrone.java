package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
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
import jp.morrowgear.drone.item.OperationMarkerItem;
import jp.morrowgear.drone.item.SolarServiceStationItem;
import jp.morrowgear.drone.item.StrikeBeaconLauncherItem;
import jp.morrowgear.drone.network.DroneCommandPayload;
import jp.morrowgear.drone.network.DroneCommandResultPayload;
import jp.morrowgear.drone.network.FleetOperationPayload;
import jp.morrowgear.drone.network.PowerLostBeaconPayload;
import jp.morrowgear.drone.network.SupplyNetworkConfigPayload;
import jp.morrowgear.drone.network.SupplyNodeConfigPayload;
import jp.morrowgear.drone.network.StrikeBeaconLaunchPayload;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
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
			// The rendered rotor envelope remains 3x3. Keep the physical hull slightly
			// inset so a centered aircraft can traverse a three-block service lane
			// without numerical edge contact against both walls.
			.sized(2.8f, 0.75f)
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
	public static final EntityType<CargoLiftLoadEntity> CARGO_LIFT_LOAD = registerEntity(
		"cargo_lift_load",
		EntityType.Builder.<CargoLiftLoadEntity>of(CargoLiftLoadEntity::new, MobCategory.MISC)
			.sized(5.0f, 2.4f).clientTrackingRange(20).updateInterval(1)
	);
	public static final EntityType<StrikeBeaconEntity> STRIKE_BEACON = registerEntity(
		"strike_beacon",
		EntityType.Builder.<StrikeBeaconEntity>of(StrikeBeaconEntity::new, MobCategory.MISC)
			.sized(0.32f, 0.32f).clientTrackingRange(16).updateInterval(1)
	);

	public static final Item CONTROLLER = registerItem("controller", Item::new, new Item.Properties().stacksTo(1));
	public static final Item SCOUT_MODULE = registerItem("scout_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item CARGO_MODULE = registerItem("cargo_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item ENGINEER_MODULE = registerItem("engineer_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item SECURITY_MODULE = registerItem("security_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item SALVAGE_MODULE = registerItem("salvage_module", Item::new, new Item.Properties().stacksTo(16));
	public static final Item RECOVERY_TOOL = registerItem("recovery_tool", RecoveryToolItem::new,
		new Item.Properties().stacksTo(1).durability(192));
	public static final Item OPERATION_MARKER = registerItem("operation_marker", OperationMarkerItem::new,
		new Item.Properties().stacksTo(1));
	public static final Item STRIKE_BEACON_CARTRIDGE = registerItem("strike_beacon_cartridge", Item::new,
		new Item.Properties().stacksTo(16));
	public static final Item STRIKE_BEACON_LAUNCHER = registerItem("strike_beacon_launcher", StrikeBeaconLauncherItem::new,
		new Item.Properties().stacksTo(1).durability(96));
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
	public static final SoundEvent AUTOCANNON_START_SOUND = registerSound("autocannon_start", 64.0f);
	public static final SoundEvent AUTOCANNON_FIRE_SOUND = registerSound("autocannon_fire", 64.0f);
	public static final SoundEvent AUTOCANNON_STOP_SOUND = registerSound("autocannon_stop", 64.0f);
	public static final SoundEvent AUTOCANNON_IMPACT_SOUND = registerSound("autocannon_impact", 40.0f);
	public static final SoundEvent LASER_CHARGE_SOUND = registerSound("laser_charge", 36.0f);
	public static final SoundEvent LASER_FIRE_SOUND = registerSound("laser_fire", 48.0f);
	public static final SoundEvent LASER_HIT_SOUND = registerSound("laser_hit", 40.0f);
	public static final SoundEvent LASER_SHUTDOWN_SOUND = registerSound("laser_shutdown", 40.0f);
	public static final SoundEvent CARRIER_FLIGHT_IDLE_SOUND = registerSound("carrier_flight_idle", 192.0f);
	public static final SoundEvent CARRIER_FLIGHT_CRUISE_SOUND = registerSound("carrier_flight_cruise", 192.0f);
	public static final SoundEvent CARRIER_LASER_CHARGE_SOUND = registerSound("carrier_laser_charge", 160.0f);
	public static final SoundEvent CARRIER_LASER_FIRE_SOUND = registerSound("carrier_laser_fire", 192.0f);
	public static final SoundEvent CARRIER_LASER_HIT_SOUND = registerSound("carrier_laser_hit", 128.0f);
	public static final SoundEvent CARRIER_LASER_COOLDOWN_SOUND = registerSound("carrier_laser_cooldown", 128.0f);
	public static final SoundEvent CARRIER_MINING_EMITTER_SOUND = registerSound("carrier_mining_emitter", 160.0f);
	public static final SoundEvent CARRIER_MINING_CORE_SOUND = registerSound("carrier_mining_core", 192.0f);
	public static final SoundEvent CARRIER_MINING_RELEASE_SOUND = registerSound("carrier_mining_release", 192.0f);
	public static final SoundEvent CARRIER_MINING_IMPACT_SOUND = registerSound("carrier_mining_impact", 224.0f);
	public static final SoundEvent CARRIER_CARGO_HATCH_UNLOCK_SOUND = registerSound("carrier_cargo_hatch_unlock", 144.0f);
	public static final SoundEvent CARRIER_CARGO_HATCH_MOVE_SOUND = registerSound("carrier_cargo_hatch_move", 128.0f);
	public static final SoundEvent CARRIER_WINCH_START_SOUND = registerSound("carrier_winch_start", 128.0f);
	public static final SoundEvent CARRIER_WINCH_HOIST_SOUND = registerSound("carrier_winch_hoist", 128.0f);
	public static final SoundEvent CARRIER_WINCH_LOWER_SOUND = registerSound("carrier_winch_lower", 128.0f);
	public static final SoundEvent CARRIER_WINCH_BRAKE_SOUND = registerSound("carrier_winch_brake", 144.0f);
	public static final SoundEvent CARRIER_CABLE_TENSION_SOUND = registerSound("carrier_cable_tension", 144.0f);
	public static final SoundEvent CARRIER_CONTAINER_RAIL_SOUND = registerSound("carrier_container_rail", 144.0f);
	public static final SoundEvent CARRIER_CONTAINER_LATCH_SOUND = registerSound("carrier_container_latch", 160.0f);
	public static final SoundEvent CARRIER_CARGO_HATCH_SEAL_SOUND = registerSound("carrier_cargo_hatch_seal", 144.0f);
	public static final SoundEvent CARRIER_CONTAINER_GROUND_SOUND = registerSound("carrier_container_ground", 160.0f);
	public static final SoundEvent CARRIER_CONTAINER_HYDRAULIC_SOUND = registerSound("carrier_container_hydraulic", 128.0f);
	public static final SoundEvent CARRIER_MISSILE_HATCH_SOUND = registerSound("carrier_missile_hatch", 176.0f);
	public static final SoundEvent CARRIER_MISSILE_EJECT_SOUND = registerSound("carrier_missile_eject", 192.0f);
	public static final SoundEvent CARRIER_MISSILE_IGNITION_SOUND = registerSound("carrier_missile_ignition", 224.0f);
	public static final SoundEvent CARRIER_MISSILE_MOTOR_SOUND = registerSound("carrier_missile_motor", 224.0f);
	public static final SoundEvent CARRIER_MISSILE_PENETRATION_SOUND = registerSound("carrier_missile_penetration", 192.0f);
	public static final SoundEvent CARRIER_MISSILE_EXPLOSION_SOUND = registerSound("carrier_missile_explosion", 256.0f);
	public static final SoundEvent MISSILE_LAUNCH_SOUND = registerSound("missile_launch", 64.0f);
	public static final SoundEvent MISSILE_IGNITION_SOUND = registerSound("missile_ignition", 64.0f);
	public static final SoundEvent MISSILE_MOTOR_SOUND = registerSound("missile_motor", 64.0f);
	public static final SoundEvent MISSILE_IMPACT_SOUND = registerSound("missile_impact", 48.0f);
	public static final SoundEvent MISSILE_EXPLOSION_SOUND = registerSound("missile_explosion", 80.0f);
	public static final SoundEvent LASER_DISCHARGE_SOUND = Registry.register(BuiltInRegistries.SOUND_EVENT,
		Identifier.fromNamespaceAndPath(MOD_ID, "laser_discharge"), DroneAudioSounds.LASER_DISCHARGE);
	public static final SoundEvent MISSILE_DEBRIS_SOUND = Registry.register(BuiltInRegistries.SOUND_EVENT,
		Identifier.fromNamespaceAndPath(MOD_ID, "missile_debris"), DroneAudioSounds.MISSILE_DEBRIS);
	public static final Block[] DOCK_PARTS = new Block[9];
	public static final Block[] WIDE_DOCK_PARTS = new Block[25];
	public static final DockCenterBlock DOCK_CENTER;
	public static final DockCenterBlock WIDE_DOCK_CENTER;
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
		for (int i = 0; i < WIDE_DOCK_PARTS.length; i++) {
			if (i == 12) continue;
			int x = i % 5 - 2, z = i / 5 - 2;
			WIDE_DOCK_PARTS[i] = registerBlock("dock_wide_part_" + i,
				properties -> new DockPartBlock(properties, x, z, 5), dockProperties());
		}
		WIDE_DOCK_CENTER = registerBlock("dock_wide_part_12", properties -> new DockCenterBlock(properties, 5), dockProperties());
		WIDE_DOCK_PARTS[12] = WIDE_DOCK_CENTER;
		DRONE_UNIT = registerItem("field_drone_unit", DroneUnitItem::new, new Item.Properties().stacksTo(1));
		DOCK_ITEM = registerItem("dock_item", DockKitItem::new, new Item.Properties().stacksTo(16));
		DOCK_BLOCK_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			Identifier.fromNamespaceAndPath(MOD_ID, "dock"),
			FabricBlockEntityTypeBuilder.create(DockBlockEntity::new, DOCK_CENTER, WIDE_DOCK_CENTER).build()
		);
	}

	@Override
	public void onInitialize() {
		SupplyItems.register();
		DockMenu.register();
		DockAllocationRuntime.register();
		OperationChunkLeaseRuntime.register();
		ManagedLightRuntime.register();
		MissileDetonationRuntime.register();
		RuntimeCacheLifecycle.register();
		FieldRelayRuntime.register();
		jp.morrowgear.drone.carrier.CarrierModule.register();
		CarrierRuntimeVerification.register();
		OperationCommunicationsServer.register();
		CargoLiftNetwork.register();
		SupplyNetworkRuntime.register();
		SupplyNetworkTransport.register();
		CarrierDroneServiceAdapter.install();
		MissileAudioController.register();
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
			entries.accept(OPERATION_MARKER);
			entries.accept(STRIKE_BEACON_CARTRIDGE);
			entries.accept(STRIKE_BEACON_LAUNCHER);
			entries.accept(POWER_CELL);
			entries.accept(SupplyItems.AUTOCANNON_MAGAZINE);
			entries.accept(SupplyItems.LASER_CELL);
			entries.accept(SupplyItems.MICRO_MISSILE_PACK);
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
		PayloadTypeRegistry.clientboundPlay().register(DroneCommandResultPayload.TYPE, DroneCommandResultPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(FleetOperationPayload.TYPE, FleetOperationPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PowerLostBeaconPayload.TYPE, PowerLostBeaconPayload.CODEC);
		RecoverySnapshotNetwork.register();
		TacticalContactNetwork.register();
		FleetLinkNetwork.register();
		PayloadTypeRegistry.serverboundPlay().register(SupplyNetworkConfigPayload.TYPE, SupplyNetworkConfigPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SupplyNodeConfigPayload.TYPE, SupplyNodeConfigPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(StrikeBeaconLaunchPayload.TYPE, StrikeBeaconLaunchPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SupplyNetworkConfigPayload.TYPE, (payload, context) ->
			context.server().execute(() -> payload.apply(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(SupplyNodeConfigPayload.TYPE, (payload, context) ->
			context.server().execute(() -> payload.apply(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(StrikeBeaconLaunchPayload.TYPE, (payload, context) ->
			context.server().execute(() -> {
				ServerPlayer player = context.player();
				net.minecraft.world.InteractionHand hand = payload.offhand()
					? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
				ItemStack stack = player.getItemInHand(hand);
				if (!stack.is(STRIKE_BEACON_LAUNCHER) || player.isSpectator()
					|| player.getCooldowns().isOnCooldown(stack)) return;
				if (StrikeBeaconLauncherItem.rounds(stack) <= 0) {
					player.sendSystemMessage(Component.literal("[MORROWGEAR] BEACON MAGAZINE EMPTY / USE TO RELOAD"));
					return;
				}
				if (player.level() instanceof ServerLevel level
					&& StrikeBeaconEntity.activeCount(level, player.getUUID()) >= StrikeBeaconPolicy.MAX_ACTIVE_BEACONS) {
					player.sendSystemMessage(Component.literal("[MORROWGEAR] ACTIVE BEACON LIMIT 3/3"));
					return;
				}
				if (player.level() instanceof ServerLevel level && StrikeBeaconEntity.launch(level, player)) {
					player.getCooldowns().addCooldown(stack, 12);
					int remaining = StrikeBeaconLauncherItem.rounds(stack);
					if (!player.isCreative()) {
						StrikeBeaconLauncherItem.consumeRound(stack);
						remaining = StrikeBeaconLauncherItem.rounds(stack);
						stack.hurtAndBreak(1, player, hand.asEquipmentSlot());
					}
					player.sendSystemMessage(Component.literal("[MORROWGEAR] BEACON AWAY / MAGAZINE "
						+ remaining + "/" + StrikeBeaconLauncherItem.CAPACITY));
				}
			}));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			for (PowerLostBeaconData.Beacon beacon : PowerLostBeaconData.get(server).forOwner(player.getUUID())) {
				ServerPlayNetworking.send(player, new PowerLostBeaconPayload(beacon.unitId(), beacon.dimension(),
					beacon.x(), beacon.y(), beacon.z(), true, beacon.tick()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(FleetOperationPayload.TYPE, (payload, context) ->
			dispatchFleetOperation(context.player(), payload));
		OperatorCommandTransactions.register();
		ServerPlayNetworking.registerGlobalReceiver(DroneCommandPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			try (var transaction = OperatorCommandTransactions.begin(player, payload)) {
			if (!DroneCommandPolicy.acceptablePayload(payload.action())) {
				transaction.rejected("命令データが空、または長すぎます"); return;
			}
			boolean forced = payload.action().startsWith("force:");
			String action = forced ? payload.action().substring("force:".length()) : payload.action();
			if (!DroneCommandPolicy.acceptablePayload(action)) { transaction.rejected("強制命令の形式が不正です"); return; }
			Entity entity = player.level().getEntity(payload.entityId());
			if (!(entity instanceof DroneEntity drone)) { transaction.rejected("機体が受信範囲に存在しません"); return; }
			transaction.identify(drone, action);
			if (!drone.isOwnedBy(player)) { transaction.rejected("他ユーザーの機体は操作できません"); return; }
			if (!DataLinkRelayNetwork.canReach(player, drone.position())) {
					transaction.rejected("データリンク圏外 / FIELDメッシュ展開中 / 接続後に再実行してください"); return;
			}
			if (!allowOperatorCommand(player, drone)) { transaction.rejected(drone.operatorFlightCommandRejection()); return; }
			if (forced && drone.strikeBeaconActive()) drone.cancelStrikeBeaconForOperator();
			if (action.startsWith("capacity_upgrade:")) {
				if (forced) { transaction.rejected("容量変更は強制介入の対象外です"); return; }
				try {
					if (upgradeCapacity(player, drone, Integer.parseInt(action.substring(17))))
						transaction.accepted(drone, action);
					else transaction.rejected("容量拡張の条件または必要素材を満たしていません");
				}
				catch (NumberFormatException ignored) { transaction.rejected("容量設定が不正です"); }
				return;
			}
			if (action.startsWith("weapon_module:")) {
				if (forced) { transaction.rejected("兵装変更は強制介入の対象外です"); return; }
				String loadoutId = action.substring("weapon_module:".length());
				if (SecurityLoadout.isValidId(loadoutId)) {
					if (changeSecurityLoadout(player, drone, SecurityLoadout.byId(loadoutId)))
						transaction.accepted(drone, action);
					else transaction.rejected("兵装変更の条件または必要モジュールを満たしていません");
				} else transaction.rejected("兵装IDが不正です");
				return;
			}
			if (action.startsWith("module:")) {
				if (forced) { transaction.rejected("役割変更は強制介入の対象外です"); return; }
				String roleId = action.substring("module:".length());
				if (!DroneRole.isValidId(roleId)) { transaction.rejected("役割IDが不正です"); return; }
				if (changeRoleModule(player, drone, DroneRole.byId(roleId))) transaction.accepted(drone, action);
				else transaction.rejected("役割変更の条件または必要モジュールを満たしていません");
				return;
			}
			if (action.startsWith("wing_join:") || action.startsWith("wing_create:")) {
				if (forced) { transaction.rejected("Wing変更は強制介入の対象外です"); return; }
				String target = action.substring(action.indexOf(':') + 1);
				if (moveToWing(player, drone, target)) transaction.accepted(drone, action);
				else transaction.rejected("Wingへ参加できません");
				return;
			}
			if (action.equals("wing_leave")) {
				if (forced) { transaction.rejected("Wing変更は強制介入の対象外です"); return; }
				if (leaveWing(player, drone)) transaction.accepted(drone, action);
				else transaction.rejected("現在Wingに所属していません、または離脱できません");
				return;
			}
			if (action.startsWith("group:")) {
				if (forced) { transaction.rejected("Wing変更は強制介入の対象外です"); return; }
				if (moveToWing(player, drone, action.substring("group:".length()))) transaction.accepted(drone, action);
				else transaction.rejected("Wing変更を適用できません");
				return;
			}
			if (action.startsWith("assign_dock:")) {
				if (forced) { transaction.rejected("Dock割当は強制介入の対象外です"); return; }
				try {
					if (assignDock(player, drone, BlockPos.of(Long.parseLong(action.substring("assign_dock:".length())))))
						transaction.accepted(drone, action);
					else transaction.rejected("指定Dockを割り当てられません");
				} catch (NumberFormatException ignored) {
					transaction.rejected("Dock座標が不正です");
					LOGGER.warn("Rejected malformed dock assignment from {}", player.getScoreboardName());
				}
				return;
			}
			if (action.startsWith("cargo_source:") || action.startsWith("cargo_target:")) {
				if (forced) { transaction.rejected("補給経路変更は強制介入の対象外です"); return; }
				if (assignCargoEndpoint(player, drone, action)) transaction.accepted(drone, action);
				else transaction.rejected("物流ノードを設定できません");
				return;
			}
			if (action.startsWith("guard:")) {
				String[] data = action.split(":");
				try {
					if (data.length != 6) return;
					BlockPos requestedAnchor = new BlockPos(Integer.parseInt(data[1]), Integer.parseInt(data[2]), Integer.parseInt(data[3]));
					int radius = Integer.parseInt(data[4]);
					String orderId = data[5];
					if (!MissionAssignmentPolicy.allows(drone.role(), MissionAssignmentPolicy.MissionKind.SECURITY_PATROL)
						|| !DataLinkRelayNetwork.canReach(player, Vec3.atCenterOf(requestedAnchor))
						|| !DroneCommandPolicy.validSecurityRadius(radius)
						|| !DroneCommandPolicy.validMission(orderId, 1, 0)) return;
					int surfaceY = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						requestedAnchor.getX(), requestedAnchor.getZ());
					BlockPos anchor = new BlockPos(requestedAnchor.getX(), surfaceY, requestedAnchor.getZ());
					if (forced && !applyForcedOperatorCommand(player, drone)) return;
					drone.assignSecurityPatrol(anchor, radius, orderId);
					transaction.accepted(drone, action);
					player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId()
						+ " / SECURITY PATROL / R" + radius + " / " + anchor.toShortString()));
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
					LOGGER.warn("Rejected malformed security patrol from {}", player.getScoreboardName());
				}
				return;
			}
			if (action.startsWith("work:")) {
				String[] data = action.split(":");
				try {
					if (data.length != 7) return;
					FieldOperationType type = FieldOperationType.byId(data[1]);
					BlockPos requestedAnchor = new BlockPos(Integer.parseInt(data[2]), Integer.parseInt(data[3]), Integer.parseInt(data[4]));
					int radius = Integer.parseInt(data[5]);
					String orderId = data[6];
					if (type == FieldOperationType.NONE
						|| !MissionAssignmentPolicy.allows(drone.role(), MissionAssignmentPolicy.MissionKind.FIELD_OPERATION)
						|| !DataLinkRelayNetwork.canReach(player, Vec3.atCenterOf(requestedAnchor))
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
					if (forced && !applyForcedOperatorCommand(player, drone)) return;
					drone.clearSecurityPatrol();
					if (MissionCommandPolicy.preemptsCargoRoute(action)) drone.pauseCargoRoute();
					drone.assignFieldOperation(type, anchor, radius, orderId);
					transaction.accepted(drone, action);
					player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " / "
						+ type.label() + " / R" + radius + " / " + anchor.toShortString()));
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
					LOGGER.warn("Rejected malformed field operation from {}", player.getScoreboardName());
				}
				return;
			}
			if (action.startsWith("move:")) {
				String[] coordinates = action.substring("move:".length()).split(":");
				try {
					int x = Integer.parseInt(coordinates[0]);
					int z = Integer.parseInt(coordinates[1]);
					if (!DataLinkRelayNetwork.canReach(player, new Vec3(x + 0.5, player.getY(), z + 0.5))) return;
					ServerLevel level = player.level();
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					String missionId = coordinates.length > 2 ? coordinates[2] : drone.getUUID().toString();
					int expected = coordinates.length > 3 ? Integer.parseInt(coordinates[3]) : 1;
					int index = coordinates.length > 4 ? Integer.parseInt(coordinates[4]) : 0;
					if (coordinates.length > 5 || !DroneCommandPolicy.validMission(missionId, expected, index)) return;
					if (forced && !applyForcedOperatorCommand(player, drone)) return;
					drone.clearSecurityPatrol();
					if (MissionCommandPolicy.preemptsCargoRoute(action)) drone.pauseCargoRoute();
					drone.assignWaypoint(new BlockPos(x, y, z), missionId, expected, index,
						player.blockPosition().above(6), level.getGameTime());
					transaction.accepted(drone, action);
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
					LOGGER.warn("Rejected malformed waypoint from {}", player.getScoreboardName());
				}
				return;
			}
			if (action.startsWith("track:")) {
				String[] targetData = action.substring("track:".length()).split(":");
				try {
					int targetId = Integer.parseInt(targetData[0]);
					Entity target = player.level().getEntity(targetId);
					boolean linked = FleetThreatNetwork.knownContact(player.level().dimension().toString(),
						player.getUUID(), targetId, player.level().getGameTime());
					if (!(target instanceof LivingEntity living) || !living.isAlive() || living == player
						|| living.distanceTo(player) > 512.0 && !linked) return;
					String missionId = targetData.length > 1 ? targetData[1] : drone.getUUID().toString();
					int expected = targetData.length > 2 ? Integer.parseInt(targetData[2]) : 1;
					int index = targetData.length > 3 ? Integer.parseInt(targetData[3]) : 0;
					if (targetData.length > 4 || !DroneCommandPolicy.validMission(missionId, expected, index)) return;
					if (forced && !applyForcedOperatorCommand(player, drone)) return;
					drone.clearSecurityPatrol();
					if (MissionCommandPolicy.preemptsCargoRoute(action)) drone.pauseCargoRoute();
					drone.assignTrackingTarget(living, missionId, expected, index,
						player.blockPosition().above(6), player.level().getGameTime());
					transaction.accepted(drone, action);
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
					LOGGER.warn("Rejected malformed tracking assignment from {}", player.getScoreboardName());
				}
				return;
			}
			if (action.startsWith("attack:")) {
				String[] targetData = action.substring("attack:".length()).split(":");
				try {
					int targetId = Integer.parseInt(targetData[0]);
					Entity target = player.level().getEntity(targetId);
					boolean linked = FleetThreatNetwork.knownContact(player.level().dimension().toString(),
						player.getUUID(), targetId, player.level().getGameTime());
					if (!(target instanceof LivingEntity living) || !living.isAlive() || living == player
						|| living.distanceTo(player) > 512.0 && !linked || drone.role() != DroneRole.SECURITY) return;
					String missionId = targetData.length > 1 ? targetData[1] : drone.getUUID().toString();
					int expected = targetData.length > 2 ? Integer.parseInt(targetData[2]) : 1;
					int index = targetData.length > 3 ? Integer.parseInt(targetData[3]) : 0;
					if (targetData.length > 4 || !DroneCommandPolicy.validMission(missionId, expected, index)) return;
					if (forced && !applyForcedOperatorCommand(player, drone)) return;
					if (drone.assignOperatorAttack(player, living, missionId, expected, index)) {
						transaction.accepted(drone, action);
						player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId()
							+ " / FORCED TARGET / " + living.getName().getString()));
					} else transaction.rejected("指定目標を攻撃できません");
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
					LOGGER.warn("Rejected malformed attack assignment from {}", player.getScoreboardName());
				}
				return;
			}
			if (action.startsWith("patrol:")) {
				String[] data = action.split(":", 8);
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
						|| !DataLinkRelayNetwork.canReach(player, new Vec3(originX + 0.5, player.getY(), originZ + 0.5))
						|| route.stream().anyMatch(point -> !DataLinkRelayNetwork.canReach(player,
							new Vec3(point.getX() + 0.5, player.getY(), point.getZ() + 0.5)))) return;
					if (forced && !applyForcedOperatorCommand(player, drone)) return;
					drone.clearSecurityPatrol();
					if (MissionCommandPolicy.preemptsCargoRoute(action)) drone.pauseCargoRoute();
					drone.assignPatrolRoute(route, missionId, expected, index,
						new BlockPos(originX, player.getBlockY() + 6, originZ), player.level().getGameTime(), startIndex);
					transaction.accepted(drone, action);
				} catch (NumberFormatException ignored) {
					LOGGER.warn("Rejected malformed patrol route from {}", player.getScoreboardName());
				}
				return;
			}
			if (action.startsWith("follow:")) {
				String[] formationData = action.substring("follow:".length()).split(":");
				try {
					String missionId = formationData[0];
					int expected = Integer.parseInt(formationData[1]);
					int index = Integer.parseInt(formationData[2]);
					if (formationData.length != 4 || !DroneCommandPolicy.validMission(missionId, expected, index)) return;
					Entity leaderEntity = player.level().getEntity(Integer.parseInt(formationData[3]));
					if (!(leaderEntity instanceof DroneEntity leader) || !leader.isOwnedBy(player)) return;
					if (forced && !applyForcedOperatorCommand(player, drone)) return;
					drone.clearSecurityPatrol();
					if (MissionCommandPolicy.preemptsCargoRoute(action)) drone.pauseCargoRoute();
					drone.assignFollowFormation(missionId, expected, index, leader.unitId(),
						player.blockPosition().above(4), player.level().getGameTime());
					transaction.accepted(drone, action);
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
					LOGGER.warn("Rejected malformed follow formation from {}", player.getScoreboardName());
				}
				return;
			}
			if (action.equals("decommission")) {
				if (forced) return;
				storeDrone(player, drone);
				transaction.accepted(drone, action);
				return;
			}
			if (!DroneCommandPolicy.isSimpleAction(action)) return;
			if (forced && !applyForcedOperatorCommand(player, drone)) return;
			if (action.equals("mesh_deploy") || action.equals("mesh_withdraw")) {
				if (drone.role() != DroneRole.FIELD) {
					transaction.rejected("通信メッシュ命令はFIELD機専用です");
					return;
				}
				boolean deployMesh = action.equals("mesh_deploy");
				Vec3 deploymentAnchor = deployMesh ? FieldRelayRuntime.deploymentAnchor(player) : null;
				if (deployMesh) drone.setFieldMeshAuthorized(true, deploymentAnchor);
				else drone.requestFieldMeshWithdrawal();
				transaction.accepted(drone, action);
				player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId()
					+ (deployMesh ? " / FIELD MESH DEPLOY AUTHORIZED" : " / FIELD MESH WITHDRAW / RTB")));
				return;
			}
			// Return is also a request for a free berth; an existing reservation is not required.
			if (action.equals("standby") || action.equals("return")
				|| action.equals("dock") || action.equals("orbit")) {
				drone.clearFieldOperation();
				drone.clearSecurityPatrol();
				if (MissionCommandPolicy.preemptsCargoRoute(action)) drone.pauseCargoRoute();
			}
			if (action.equals("dock")) drone.requestManualDockReturn();
			else drone.setMode(DroneMode.fromAction(action));
			transaction.accepted(drone, action);
			}
		});
		MorrowgearRuntimeVerifier.register();
		DockMenuRuntimeVerification.register();
		DesignVerificationSession.register();
		MorrowgearBaseGenerator.register();
		MorrowgearAutomation.register();

		LOGGER.info("Morrowgear Drone Command initialized for Minecraft 26.2");
	}

	private static boolean allowOperatorCommand(ServerPlayer player, DroneEntity drone) {
		return drone != null && drone.isOwnedBy(player) && allowOperatorCommands(player, List.of(drone));
	}

	private static boolean applyForcedOperatorCommand(ServerPlayer player, DroneEntity drone) {
		if (drone.forceOperatorOverride()) return true;
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId()
			+ " / 強制介入を拒否 / " + drone.forcedOperatorCommandRejection()));
		return false;
	}

	private static boolean allowOperatorCommands(ServerPlayer player, List<DroneEntity> drones) {
		if (drones.stream().anyMatch(drone -> !drone.isOwnedBy(player))) return false;
		var admission = OperatorCommandAdmission.select(drones, DroneEntity::acceptsOperatorFlightCommand);
		notifyOperatorRejections(player, admission.rejected());
		return admission.allAccepted();
	}

	private static void notifyOperatorRejections(ServerPlayer player, List<DroneEntity> rejected) {
		if (rejected.isEmpty()) return;
		String units = rejected.stream().limit(3).map(DroneEntity::unitId)
			.collect(java.util.stream.Collectors.joining(", "));
		if (rejected.size() > 3) units += " ほか" + (rejected.size() - 3) + "機";
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + units + ": "
			+ rejected.getFirst().operatorFlightCommandRejection()));
	}

	private static void dispatchFleetOperation(ServerPlayer player, FleetOperationPayload payload) {
		FieldOperationType type = FieldOperationType.byId(payload.operationType());
		if (type == FieldOperationType.NONE || !DroneCommandPolicy.validFieldRadius(type, payload.radius())
			|| !DataLinkRelayNetwork.canReach(player,
				new Vec3(payload.x() + 0.5, player.getY(), payload.z() + 0.5))) {
			OperatorCommandTransactions.rejectGenerated(player, payload.commandId(), null,
				"fleet:" + payload.operationType(), "作業種別、範囲、または地点が不正です");
			return;
		}
		ServerLevel level = player.level();
		int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, payload.x(), payload.z());
		int anchorY = switch (type) {
			case ORE -> surfaceY - 16;
			case EXCAVATE, FORESTRY -> surfaceY - 1;
			case NONE -> surfaceY;
		};
		BlockPos anchor = new BlockPos(payload.x(), anchorY, payload.z());
		var relayCoverage = DataLinkRelayNetwork.coverage(player);
		var admission = OperatorCommandAdmission.select(ownedDrones(level, player, 512).stream()
			.filter(drone -> relayCoverage.link(new DataLinkRelayPolicy.Point(
				drone.getX(), drone.getY(), drone.getZ())).connected()).toList(),
			DroneEntity::acceptsOperatorFlightCommand);
		notifyOperatorRejections(player, admission.rejected());
		List<DroneEntity> fleet = admission.accepted();
		List<AutonomousTaskForcePolicy.Candidate> candidates = fleet.stream()
			.map(drone -> automaticCandidate(drone, anchor)).toList();
		AutonomousTaskForcePolicy.Plan plan = AutonomousTaskForcePolicy.plan(type, payload.radius(), candidates);
		if (!plan.executable()) {
			OperatorCommandTransactions.rejectGenerated(player, payload.commandId(), null,
				"fleet:" + payload.operationType(), plan.status());
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
			String action = "work:" + type.id() + ":" + anchor.getX() + ":" + anchor.getY() + ":"
				+ anchor.getZ() + ":" + payload.radius() + ":" + orderId;
			OperatorCommandTransactions.acceptGenerated(player, payload.commandId(), drone, action);
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

	private static boolean moveToWing(ServerPlayer player, DroneEntity drone, String targetGroup) {
		if (!allowOperatorCommand(player, drone)) return false;
		ServerLevel level = player.level();
		List<DroneEntity> fleet = ownedDrones(level, player, 512);
		int targetSize = (int) fleet.stream().filter(member -> member.groupId().equals(targetGroup)).count();
		WingMembershipPolicy.Result result = WingMembershipPolicy.evaluate(drone.groupId(), targetGroup, targetSize);
		if (result == WingMembershipPolicy.Result.UNCHANGED) return true;
		if (result != WingMembershipPolicy.Result.ACCEPTED) {
			String reason = result == WingMembershipPolicy.Result.WING_FULL ? "WING FULL 8/8" : "INVALID WING";
			player.sendSystemMessage(Component.literal("[MORROWGEAR] " + reason));
			return false;
		}

		String sourceGroup = drone.groupId();
		List<DroneEntity> affected = fleet.stream().filter(member -> member == drone
			|| member.groupId().equals(targetGroup)
			|| sourceGroup.startsWith("WING-") && member.groupId().equals(sourceGroup)).toList();
		if (!allowOperatorCommands(player, affected)) return false;
		drone.assignGroup(targetGroup);
		List<DroneEntity> sourceMembers = fleet.stream()
			.filter(member -> member != drone && member.groupId().equals(sourceGroup)).toList();
		List<DroneEntity> targetMembers = fleet.stream()
			.filter(member -> member == drone || member.groupId().equals(targetGroup)).toList();
		if (sourceGroup.startsWith("WING-")) reslotWingMission(level, player, sourceMembers, null);
		reslotWingMission(level, player, targetMembers, drone);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " JOIN "
			+ targetGroup + " " + targetMembers.size() + "/" + WingMembershipPolicy.MAX_MEMBERS));
		return true;
	}

	private static boolean leaveWing(ServerPlayer player, DroneEntity drone) {
		if (!allowOperatorCommand(player, drone)) return false;
		if (!drone.groupId().startsWith("WING-")) return false;
		ServerLevel level = player.level();
		String sourceGroup = drone.groupId();
		List<DroneEntity> remaining = ownedDrones(level, player, 512).stream()
			.filter(member -> member != drone && member.groupId().equals(sourceGroup)).toList();
		if (!allowOperatorCommands(player, remaining)) return false;
		drone.assignGroup("ALPHA");
		drone.clearFieldOperation();
		drone.setMode(DroneMode.STANDBY);
		reslotWingMission(level, player, remaining, null);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " LEFT " + sourceGroup));
		return true;
	}

	private static void reslotWingMission(ServerLevel level, ServerPlayer player, List<DroneEntity> members,
		DroneEntity joining) {
		if (members.isEmpty()) return;
		if (!allowOperatorCommands(player, members)) return;
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
		drone.prepareForCommissioningReturn();
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
		double deckHeight = level.getBlockState(dockPos).is(WIDE_DOCK_CENTER) ? 0.316 : 0.344;
		drone.setPos(dockPos.getX() + 0.5, dockPos.getY() + deckHeight, dockPos.getZ() + 0.5);
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

	public static boolean assignDock(ServerPlayer player, DroneEntity drone, BlockPos pos) {
		if (!allowOperatorCommand(player, drone)) return false;
		if (!(player.level() instanceof ServerLevel level)) return false;
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof DockBlockEntity dock) || !dock.isOwnedBy(player)) return false;
		List<DroneEntity> displaced = ownedDrones(level, player, 512).stream()
			.filter(other -> other != drone && other.hasDock() && other.dockPos().equals(pos)).toList();
		if (!allowOperatorCommands(player, displaced)) return false;
		for (DroneEntity other : displaced) other.clearDock();
		drone.assignDock(pos);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " に " + dock.dockId() + " を割り当てました。"));
		return drone.hasDock() && drone.dockPos().equals(pos);
	}

	public static boolean changeRoleModule(ServerPlayer player, DroneEntity drone, DroneRole requestedRole) {
		if (!allowOperatorCommand(player, drone)) return false;
		boolean validDock = drone.isDocked() && drone.hasDock()
			&& player.level().getBlockEntity(drone.dockPos()) instanceof DockBlockEntity dock
			&& dock.isOwnedBy(player);
		Item requestedModule = moduleForRole(requestedRole);
		boolean moduleAvailable = requestedModule == null || hasOne(player, requestedModule);
		RoleModulePolicy.Result result = RoleModulePolicy.evaluate(validDock, player.isCreative(), moduleAvailable,
			drone.role(), requestedRole);
		if (result == RoleModulePolicy.Result.NOT_DOCKED) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Module Bay is available only while docked."));
			return false;
		}
		if (result == RoleModulePolicy.Result.UNCHANGED) return true;
		if (result == RoleModulePolicy.Result.MODULE_MISSING) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Required " + requestedRole.displayName() + " module was not found."));
			return false;
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
		return drone.role() == requestedRole;
	}

	public static boolean changeSecurityLoadout(ServerPlayer player, DroneEntity drone,
		SecurityLoadout requestedLoadout) {
		if (!allowOperatorCommand(player, drone)) return false;
		boolean validDock = drone.isDocked() && drone.hasDock()
			&& player.level().getBlockEntity(drone.dockPos()) instanceof DockBlockEntity dock
			&& dock.isOwnedBy(player);
		Item requestedModule = moduleForLoadout(requestedLoadout);
		WeaponModulePolicy.Result result = WeaponModulePolicy.evaluate(drone.role() == DroneRole.SECURITY,
			validDock, player.isCreative(), requestedModule != null && hasOne(player, requestedModule),
			drone.securityLoadout(), requestedLoadout);
		if (result == WeaponModulePolicy.Result.NOT_SECURITY) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Weapon module requires a Security unit."));
			return false;
		}
		if (result == WeaponModulePolicy.Result.NOT_DOCKED) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Weapon Bay is available only while docked."));
			return false;
		}
		if (result == WeaponModulePolicy.Result.MODULE_MISSING) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Required weapon module was not found."));
			return false;
		}
		if (result == WeaponModulePolicy.Result.PHYSICAL_MODULE_REQUIRED) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Select an installed physical weapon module."));
			return false;
		}
		if (result == WeaponModulePolicy.Result.UNCHANGED) return true;
		if (!player.isCreative()) {
			consumeOne(player, requestedModule);
			giveModule(player, moduleForLoadout(drone.securityLoadout()));
		}
		drone.assignSecurityLoadout(requestedLoadout);
		CombatTheaterCoordinator.clear();
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId()
			+ " / WEAPON " + requestedLoadout.displayName()));
		return drone.securityLoadout() == requestedLoadout;
	}

	public static boolean upgradeCapacity(ServerPlayer player, DroneEntity drone, int expectedTier) {
		if (!allowOperatorCommand(player, drone)) return false;
		if (!drone.isOwnedBy(player) || drone.role() != DroneRole.SECURITY || !drone.isDocked()
			|| !drone.hasDock() || drone.capacityTier() != expectedTier || expectedTier >= PayloadCapacity.MAX_TIER
			|| !(player.level().getBlockEntity(drone.dockPos()) instanceof DockBlockEntity dock)
			|| !dock.isOwnedBy(player)) return false;
		int alloy = expectedTier == 0 ? 8 : 16;
		int diamonds = expectedTier == 0 ? 1 : 4;
		int redstone = expectedTier == 0 ? 2 : 4;
		int scrap = expectedTier == 0 ? 0 : 2;
		java.util.Map<Item, Integer> cost = java.util.Map.of(MORROW_ALLOY, alloy, Items.DIAMOND, diamonds,
			Items.REDSTONE_BLOCK, redstone, Items.NETHERITE_SCRAP, scrap);
		if (!player.isCreative() && cost.entrySet().stream().anyMatch(entry -> {
			int found = 0;
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				ItemStack stack = player.getInventory().getItem(i);
				if (stack.is(entry.getKey())) found += stack.getCount();
			}
			return found < entry.getValue();
		})) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] CAPACITY / 合金 " + alloy
				+ " / ダイヤ " + diamonds + " / レッドストーンブロック " + redstone + " / ネザライトの欠片 " + scrap));
			return false;
		}
		if (!player.isCreative()) cost.forEach((item, count) -> {
			for (int i = 0; i < count; i++) consumeOne(player, item);
		});
		drone.upgradePayloadCapacity();
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " / CAPACITY "
			+ drone.capacityTier() + " / GUN " + drone.gunCapacity() + " / MSL " + drone.missileCapacity()
			+ " / ENERGY " + drone.weaponCapacity() + " / 残量は補給してください"));
		return drone.capacityTier() == expectedTier + 1;
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
		if (!allowOperatorCommand(player, drone)) return;
		ItemStack installedModule = moduleForRole(drone.role()) == null
			? ItemStack.EMPTY : new ItemStack(moduleForRole(drone.role()));
		List<ItemStack> returned = DroneStoragePolicy.returnedItems(drone.createStoredChassis(), installedModule,
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
		if (!allowOperatorCommand(player, drone)) return false;
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

	private static boolean assignCargoEndpoint(ServerPlayer player, DroneEntity drone, String action) {
		if (!allowOperatorCommand(player, drone)) return false;
		if (!MissionAssignmentPolicy.allows(drone.role(), MissionAssignmentPolicy.MissionKind.CARGO_ROUTE)) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR] Cargo module is required."));
			return false;
		}
		try {
			boolean source = action.startsWith("cargo_source:");
			BlockPos pos = BlockPos.of(Long.parseLong(action.substring(action.indexOf(':') + 1)));
			if (!SupplyNetworkRuntime.selectLogisticsNode(player, pos)) return false;
			if (source) drone.assignCargoSource(pos); else drone.assignCargoTarget(pos);
			player.sendSystemMessage(Component.literal("[MORROWGEAR] " + drone.unitId() + " / "
				+ (source ? "CARGO SOURCE" : "CARGO TARGET") + " " + pos.toShortString()));
			return true;
		} catch (NumberFormatException ignored) {
			LOGGER.warn("Rejected malformed cargo assignment from {}", player.getScoreboardName());
			return false;
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
			DRONE_ROSTERS.put(player.getUUID(), CachedDroneRoster.create(level, tick, drones));
			return drones;
		}
		return queryOwnedDrones(level, player, range);
	}

	static void clearDroneRosterCache() { DRONE_ROSTERS.clear(); }
	static void clearDroneRosterCache(UUID owner) { DRONE_ROSTERS.remove(owner); }

	static List<DroneEntity> ownedMissionDrones(ServerLevel level, ServerPlayer player,
		String missionId, String groupId) {
		ownedDrones(level, player, 512);
		CachedDroneRoster cached = DRONE_ROSTERS.get(player.getUUID());
		if (cached == null || cached.level() != level || cached.tick() != level.getGameTime()) return List.of();
		return cached.missions().getOrDefault(new MissionWingKey(missionId,
			MissionWingPolicy.normalizedGroup(groupId)), List.of());
	}

	static List<DroneEntity> ownedSecurityPatrolDrones(ServerLevel level, ServerPlayer player,
		String securityOrder) {
		ownedDrones(level, player, 512);
		CachedDroneRoster cached = DRONE_ROSTERS.get(player.getUUID());
		if (cached == null || cached.level() != level || cached.tick() != level.getGameTime()) return List.of();
		return cached.securityPatrols().getOrDefault(securityOrder == null ? "" : securityOrder, List.of());
	}

	static List<DroneEntity> ownedNearbyDrones(ServerLevel level, UUID ownerId, AABB area) {
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
		if (owner == null || owner.level() != level) {
			return level.getEntitiesOfClass(DroneEntity.class, area, drone -> drone.isOwnedBy(ownerId));
		}
		ownedDrones(level, owner, 512.0);
		CachedDroneRoster cached = DRONE_ROSTERS.get(ownerId);
		if (cached == null || cached.level() != level || cached.tick() != level.getGameTime()) return List.of();
		Set<DroneEntity> candidates = new LinkedHashSet<>();
		for (int x = SpatialCell.coordinate(area.minX); x <= SpatialCell.coordinate(area.maxX); x++) {
			for (int y = SpatialCell.coordinate(area.minY); y <= SpatialCell.coordinate(area.maxY); y++) {
				for (int z = SpatialCell.coordinate(area.minZ); z <= SpatialCell.coordinate(area.maxZ); z++) {
					candidates.addAll(cached.spatial().getOrDefault(new SpatialCell(x, y, z), List.of()));
				}
			}
		}
		return candidates.stream().filter(drone -> area.intersects(drone.getBoundingBox())).toList();
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

	private record MissionWingKey(String missionId, String groupId) {
	}

	private record SpatialCell(int x, int y, int z) {
		private static final int SIZE = 8;
		static int coordinate(double value) { return (int)Math.floor(value / SIZE); }
	}

	private record CachedDroneRoster(ServerLevel level, long tick, List<DroneEntity> drones,
		Map<MissionWingKey, List<DroneEntity>> missions, Map<String, List<DroneEntity>> securityPatrols,
		Map<SpatialCell, List<DroneEntity>> spatial) {
		static CachedDroneRoster create(ServerLevel level, long tick, List<DroneEntity> drones) {
			Map<MissionWingKey, List<DroneEntity>> missions = new HashMap<>();
			Map<String, List<DroneEntity>> patrols = new HashMap<>();
			Map<SpatialCell, List<DroneEntity>> spatial = new HashMap<>();
			for (DroneEntity drone : drones) {
				if (drone.hasWaypoint() && !drone.missionId().isBlank())
					missions.computeIfAbsent(new MissionWingKey(drone.missionId(),
						MissionWingPolicy.normalizedGroup(drone.groupId())), ignored -> new ArrayList<>()).add(drone);
				if (drone.hasSecurityPatrol()) patrols.computeIfAbsent(drone.securityOrderKey(),
					ignored -> new ArrayList<>()).add(drone);
				AABB bounds = drone.getBoundingBox();
				for (int x = SpatialCell.coordinate(bounds.minX); x <= SpatialCell.coordinate(bounds.maxX); x++)
					for (int y = SpatialCell.coordinate(bounds.minY); y <= SpatialCell.coordinate(bounds.maxY); y++)
						for (int z = SpatialCell.coordinate(bounds.minZ); z <= SpatialCell.coordinate(bounds.maxZ); z++)
							spatial.computeIfAbsent(new SpatialCell(x, y, z), ignored -> new ArrayList<>()).add(drone);
			}
			missions.replaceAll((key, members) -> members.stream().sorted(
				Comparator.comparingInt(DroneEntity::missionIndex).thenComparing(DroneEntity::unitId)).toList());
			patrols.replaceAll((key, members) -> members.stream().sorted(
				Comparator.comparing(DroneEntity::unitId)).toList());
			spatial.replaceAll((key, members) -> List.copyOf(members));
			return new CachedDroneRoster(level, tick, drones, Map.copyOf(missions), Map.copyOf(patrols),
				Map.copyOf(spatial));
		}
	}

	public static List<DockBlockEntity> findDocks(ServerLevel level, ServerPlayer player, int range) {
		return findDocks(level, player, player.blockPosition(), range);
	}

	public static void dockTopologyChanged(ServerLevel level, UUID owner) {
		DockAllocationRuntime.invalidate(level, owner);
	}

	public static List<DockBlockEntity> findDocks(ServerLevel level, ServerPlayer player,
		BlockPos origin, int range) {
		List<DockBlockEntity> docks = new ArrayList<>();
		int chunkRadius = Math.max(1, (range + 15) / 16);
		int centerX = origin.getX() >> 4;
		int centerZ = origin.getZ() >> 4;
		for (int x = centerX - chunkRadius; x <= centerX + chunkRadius; x++) {
			for (int z = centerZ - chunkRadius; z <= centerZ + chunkRadius; z++) {
				if (!level.hasChunk(x, z)) continue;
				for (BlockEntity blockEntity : level.getChunk(x, z).getBlockEntities().values()) {
					if (blockEntity instanceof DockBlockEntity dock && dock.isOwnedBy(player)
						&& dock.getBlockPos().distSqr(origin) <= range * range) docks.add(dock);
				}
			}
		}
		docks.sort(Comparator.comparing(DockBlockEntity::dockId));
		return docks;
	}

	public static void removeDock(ServerLevel level, BlockPos center, boolean drop) {
		boolean wide = level.getBlockState(center).is(WIDE_DOCK_CENTER);
		Block[] parts = wide ? WIDE_DOCK_PARTS : DOCK_PARTS;
		int side = wide ? 5 : 3;
		if (level.getBlockEntity(center) instanceof DockBlockEntity dock) {
			SupplyNetworkRuntime.removeDock(level, dock.ownerId(), center);
			dockTopologyChanged(level, dock.ownerId());
			for (int slot = 0; slot < dock.getContainerSize(); slot++) {
				ItemStack stack = dock.removeItemNoUpdate(slot);
				if (!stack.isEmpty()) Block.popResource(level, center.above(), stack);
			}
		}
		boolean found = false;
		for (int i = 0; i < parts.length; i++) {
			BlockPos part = center.offset(i % side - side / 2, 0, i / side - side / 2);
			Block stateBlock = level.getBlockState(part).getBlock();
			if (stateBlock == parts[i]) {
				found = true;
				level.removeBlock(part, false);
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
