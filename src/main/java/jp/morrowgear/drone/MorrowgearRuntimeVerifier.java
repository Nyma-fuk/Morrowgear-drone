package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import jp.morrowgear.drone.block.DockBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class MorrowgearRuntimeVerifier {
	private static RuntimeSession active;
	private static VerificationArenaBuilder arenaBuilder;
	private static VisualEffectSession visualEffects;
	private static SalvageVerificationSession salvageVerification;
	private static SolarServiceVerificationSession solarServiceVerification;
	private static DockExhaustionVerificationSession dockExhaustionVerification;
	private static DynamicDockVerificationSession dynamicDockVerification;
	private static CargoLiftVerificationSession cargoLiftVerification;
	private enum VerificationScope { ALL, NAVIGATION, FIELD, SNOWFIELD, COMBAT, COMBAT_CAS, ADAPTIVE_COMBAT, BASE_LASER, FIRING_ROUTES, REGRESSION }

	private MorrowgearRuntimeVerifier() {
	}

	static int startAutomation(ServerPlayer player, String scenario) {
		return switch (scenario) {
			case "all" -> start(player);
			case "navigation" -> startNavigation(player);
			case "field" -> startField(player);
			case "snowfield_mining" -> startSnowfieldMining(player);
			case "combat" -> startCombat(player);
			case "combat_cas" -> startCombatCas(player);
			case "adaptive_combat" -> startAdaptiveCombat(player);
			case "base_laser" -> startBaseLaser(player);
			case "firing_routes" -> startFiringRoutes(player);
			case "regression" -> startRegression(player);
			case "ui" -> prepareUi(player);
			case "salvage" -> startSalvage(player);
			case "salvage_service" -> startSalvageService(player);
			case "salvage_vertical" -> startSalvageVertical(player);
			case "salvage_clearance" -> startSalvageClearance(player, false, false);
			case "salvage_protected" -> startSalvageClearance(player, true, false);
			case "salvage_late_engineer" -> startSalvageClearance(player, false, true);
			case "solar_service" -> startSolarService(player);
			case "dock_exhaustion" -> startDockExhaustion(player);
			case "dock_allocation" -> startDynamicDock(player);
			case "cargo_lift" -> startCargoLift(player);
			default -> 0;
		};
	}

	static MorrowgearAutomation.Result automationStatus(String scenario) {
		if (Set.of("all", "navigation", "field", "snowfield_mining", "combat", "combat_cas", "adaptive_combat", "base_laser", "firing_routes", "regression").contains(scenario)) {
			if (active == null) return MorrowgearAutomation.Result.running("runtime session has not started");
			if (!active.done()) return MorrowgearAutomation.Result.running(active.progress());
			return active.failures.isEmpty()
				? MorrowgearAutomation.Result.pass("COMPLETE PASS " + active.caseCount() + "/" + active.caseCount())
				: MorrowgearAutomation.Result.fail("COMPLETE FAIL " + active.failures.size() + " / " + active.failures.getFirst());
		}
		String result = switch (scenario) {
			case "salvage", "salvage_service", "salvage_vertical", "salvage_clearance",
				"salvage_protected", "salvage_late_engineer" -> salvageVerification == null ? null : salvageVerification.result;
			case "solar_service" -> solarServiceVerification == null ? null : solarServiceVerification.result;
			case "dock_exhaustion" -> dockExhaustionVerification == null ? null : dockExhaustionVerification.result;
			case "dock_allocation" -> dynamicDockVerification == null ? null : dynamicDockVerification.result;
			case "cargo_lift" -> cargoLiftVerification == null ? null : cargoLiftVerification.result;
			default -> "UNKNOWN SCENARIO";
		};
		if (result == null || result.contains("RUNNING")) return MorrowgearAutomation.Result.running(result == null ? "session has not started" : result);
		return result.contains("PASS") ? MorrowgearAutomation.Result.pass(result) : MorrowgearAutomation.Result.fail(result);
	}

	private static int startSalvageClearance(ServerPlayer player, boolean protectedBlock, boolean lateEngineer) {
		if (salvageVerification != null && !salvageVerification.done()) return 0;
		salvageVerification = new SalvageVerificationSession(player, false, false, true, protectedBlock, lateEngineer);
		return Command.SINGLE_SUCCESS;
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("morrowgear_verify")
				.requires(source -> source.getEntity() instanceof ServerPlayer player
					&& player.isCreative() && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				.then(Commands.literal("all").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return start(player);
				}))
				.then(Commands.literal("navigation").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startNavigation(player);
				}))
				.then(Commands.literal("field").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startField(player);
				}))
			.then(Commands.literal("snowfield_mining").executes(context -> {
				Entity entity = context.getSource().getEntity();
				if (!(entity instanceof ServerPlayer player)) {
					context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
					return 0;
				}
				return startSnowfieldMining(player);
			}))
				.then(Commands.literal("combat").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startCombat(player);
				}))
				.then(Commands.literal("adaptive_combat").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startAdaptiveCombat(player);
				}))
				.then(Commands.literal("firing_routes").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) return 0;
					return startFiringRoutes(player);
				}))
				.then(Commands.literal("regression").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startRegression(player);
				}))
				.then(Commands.literal("arena").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startArena(player);
				}))
				.then(Commands.literal("ui").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return prepareUi(player);
				}))
				.then(Commands.literal("effects").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startVisualEffects(player, CombatState.LASER_FIRE, 8);
				})
					.then(effectPhaseCommand("charge", CombatState.LASER_CHARGE))
					.then(effectPhaseCommand("fire", CombatState.LASER_FIRE))
					.then(Commands.literal("stop").executes(context -> {
						if (visualEffects != null) visualEffects.cleanup();
						visualEffects = null;
						return Command.SINGLE_SUCCESS;
					})))
				.then(Commands.literal("persistence_prepare").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return preparePersistence(player);
				}))
				.then(Commands.literal("persistence_check").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return checkPersistence(player);
				}))
				.then(Commands.literal("salvage").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startSalvage(player);
				}))
				.then(Commands.literal("salvage_service").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startSalvageService(player);
				}))
				.then(Commands.literal("salvage_vertical").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startSalvageVertical(player);
				}))
				.then(Commands.literal("salvage_clearance").executes(context -> {
					if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return 0;
					if (salvageVerification != null && !salvageVerification.done()) return 0;
					salvageVerification = new SalvageVerificationSession(player, false, false, true);
					return 1;
				}))
				.then(Commands.literal("salvage_protected").executes(context -> {
					if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return 0;
					if (salvageVerification != null && !salvageVerification.done()) return 0;
					salvageVerification = new SalvageVerificationSession(player, false, false, true, true);
					return 1;
				}))
				.then(Commands.literal("salvage_late_engineer").executes(context -> {
					if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return 0;
					if (salvageVerification != null && !salvageVerification.done()) return 0;
					salvageVerification = new SalvageVerificationSession(player, false, false, true, false, true);
					return 1;
				}))
				.then(Commands.literal("salvage_canopy").executes(context -> {
					if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return 0;
					if (salvageVerification != null && !salvageVerification.done()) return 0;
					salvageVerification = new SalvageVerificationSession(player, false, false, true);
					for (int x = 16; x <= 20; x++) for (int z = -2; z <= 2; z++) {
						BlockState canopy = x == 18 && z == 0 ? Blocks.OAK_LOG.defaultBlockState()
							: Blocks.OAK_LEAVES.defaultBlockState().setValue(
								net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true);
						player.level().setBlockAndUpdate(salvageVerification.base.offset(x, 10, z), canopy);
					}
					salvageVerification.say("SALVAGE CANOPY / persistent leaves and trunk / Engineer resume required");
					return 1;
				}))
				.then(Commands.literal("solar_service").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startSolarService(player);
				}))
				.then(Commands.literal("dock_exhaustion").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startDockExhaustion(player);
				}))
				.then(Commands.literal("dock_allocation").executes(context -> {
					Entity entity = context.getSource().getEntity();
					if (!(entity instanceof ServerPlayer player)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR VERIFY] player only"));
						return 0;
					}
					return startDynamicDock(player);
				}))
				.then(Commands.literal("status").executes(context -> {
					context.getSource().sendSuccess(() -> Component.literal(status()), false);
					return Command.SINGLE_SUCCESS;
				}))
			)
		);
		ServerTickEvents.END_SERVER_TICK.register(MorrowgearRuntimeVerifier::tick);
	}

	private static int start(ServerPlayer player) {
		if (active != null && !active.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: " + active.progress()));
			return 0;
		}
		active = new RuntimeSession(player);
		active.say("START runtime coverage in Minecraft / cases " + active.caseCount());
		MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] START runtime coverage for {}", player.getScoreboardName());
		return Command.SINGLE_SUCCESS;
	}

	private static int startNavigation(ServerPlayer player) {
		if (active != null && !active.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: " + active.progress()));
			return 0;
		}
		active = new RuntimeSession(player, true);
		active.say("START navigation coverage in Minecraft / cases " + active.caseCount());
		return Command.SINGLE_SUCCESS;
	}

	private static int startField(ServerPlayer player) {
		if (active != null && !active.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: " + active.progress()));
			return 0;
		}
		active = new RuntimeSession(player, VerificationScope.FIELD);
		active.say("START field coverage in Minecraft / cases " + active.caseCount());
		return Command.SINGLE_SUCCESS;
	}

	private static int startSnowfieldMining(ServerPlayer player) {
		if (active != null && !active.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: " + active.progress()));
			return 0;
		}
		active = new RuntimeSession(player, VerificationScope.SNOWFIELD);
		active.say("START focused snowfield mining coverage / cases " + active.caseCount());
		return Command.SINGLE_SUCCESS;
	}

	private static int startCombat(ServerPlayer player) {
		if (active != null && !active.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: " + active.progress()));
			return 0;
		}
		active = new RuntimeSession(player, VerificationScope.COMBAT);
		active.say("START combat coverage in Minecraft / cases " + active.caseCount());
		return Command.SINGLE_SUCCESS;
	}

	private static int startCombatCas(ServerPlayer player) {
		if (active != null && !active.done()) return 0;
		active = new RuntimeSession(player, VerificationScope.COMBAT_CAS);
		active.say("START focused CAS route coverage / cases " + active.caseCount());
		return Command.SINGLE_SUCCESS;
	}

	private static int startAdaptiveCombat(ServerPlayer player) {
		if (active != null && !active.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: " + active.progress()));
			return 0;
		}
		active = new RuntimeSession(player, VerificationScope.ADAPTIVE_COMBAT);
		active.say("START adaptive combat in Minecraft / 6 drones / 1 dock / cases " + active.caseCount());
		return Command.SINGLE_SUCCESS;
	}

	private static int startBaseLaser(ServerPlayer player) {
		if (active != null && !active.done()) return 0;
		active = new RuntimeSession(player, VerificationScope.BASE_LASER);
		active.say("START base laser Warden regression / 4 Wings / 8 laser aircraft");
		return Command.SINGLE_SUCCESS;
	}

	private static int startFiringRoutes(ServerPlayer player) {
		if (active != null && !active.done()) return 0;
		active = new RuntimeSession(player, VerificationScope.FIRING_ROUTES);
		active.say("START weapon firing-route coverage / caves, roofs, walls and shafts");
		return Command.SINGLE_SUCCESS;
	}

	private static int startRegression(ServerPlayer player) {
		if (active != null && !active.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: " + active.progress()));
			return 0;
		}
		active = new RuntimeSession(player, VerificationScope.REGRESSION);
		active.say("START targeted regression in Minecraft / cases " + active.caseCount());
		return Command.SINGLE_SUCCESS;
	}

	private static int startArena(ServerPlayer player) {
		if (arenaBuilder != null && !arenaBuilder.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] " + arenaBuilder.status()));
			return 0;
		}
		arenaBuilder = new VerificationArenaBuilder(player);
		player.sendSystemMessage(Component.literal(
			"[MORROWGEAR VERIFY] ARENA START 500x500x300 / batched construction"));
		return Command.SINGLE_SUCCESS;
	}

	private static int prepareUi(ServerPlayer player) {
		if (active != null && !active.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: " + active.progress()));
			return 0;
		}
		RuntimeSession session = new RuntimeSession(player);
		session.prepareUiScenario();
		session.say("UI scenario ready / open MORROWGEAR C2 with K");
		return Command.SINGLE_SUCCESS;
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>
		effectPhaseCommand(String name, CombatState state) {
		return Commands.literal(name)
			.executes(context -> startVisualEffects(context.getSource().getPlayerOrException(), state, 8))
			.then(Commands.argument("distance", IntegerArgumentType.integer(4, 128))
				.executes(context -> startVisualEffects(context.getSource().getPlayerOrException(), state,
					IntegerArgumentType.getInteger(context, "distance")))
				.then(Commands.argument("count", IntegerArgumentType.integer(1, 8))
					.executes(context -> startVisualEffects(context.getSource().getPlayerOrException(), state,
						IntegerArgumentType.getInteger(context, "distance"),
						IntegerArgumentType.getInteger(context, "count")))));
	}

	private static int startVisualEffects(ServerPlayer player, CombatState state, int distance) {
		return startVisualEffects(player, state, distance, 1);
	}

	private static int startVisualEffects(ServerPlayer player, CombatState state, int distance, int count) {
		if (visualEffects != null) visualEffects.cleanup();
		visualEffects = new VisualEffectSession(player, state, distance, count);
		player.sendSystemMessage(Component.literal(
			"[MORROWGEAR VERIFY] EFFECTS START / " + state + " / distance " + distance
				+ " / lasers " + count + " / 60 sec / visual inspection required (not PASS)"));
		return Command.SINGLE_SUCCESS;
	}

	private static int preparePersistence(ServerPlayer player) {
		ServerLevel level = player.level();
		BlockPos base = player.blockPosition().below();
		for (DroneEntity existing : level.getEntitiesOfClass(DroneEntity.class,
			player.getBoundingBox().inflate(128), drone -> drone.isOwnedBy(player)
				&& drone.groupId().startsWith("VERIFY-PERSIST-"))) existing.discard();

		BlockPos source = base.offset(-8, 1, -8);
		BlockPos target = base.offset(-8, 1, -2);
		if (!(level.getBlockEntity(source) instanceof Container)) level.setBlockAndUpdate(source, Blocks.CHEST.defaultBlockState());
		if (!(level.getBlockEntity(target) instanceof Container)) level.setBlockAndUpdate(target, Blocks.CHEST.defaultBlockState());

		DroneEntity move = persistenceDrone(level, player, base.offset(0, 4, 0), "VERIFY-PERSIST-MOVE", DroneRole.SCOUT);
		move.assignWaypoint(base.offset(20, 1, 20), "persist-move", 100, 99, base, level.getGameTime());
		DroneEntity cargo = persistenceDrone(level, player, base.offset(2, 4, 0), "VERIFY-PERSIST-CARGO", DroneRole.CARGO);
		cargo.assignCargoSource(source);
		cargo.assignCargoTarget(target);
		DroneEntity security = persistenceDrone(level, player, base.offset(4, 4, 0), "VERIFY-PERSIST-SEC", DroneRole.SECURITY);
		security.assignSecurityPatrol(base.offset(4, 1, 4), 16, "persist-security");
		DroneEntity field = persistenceDrone(level, player, base.offset(6, 4, 0), "VERIFY-PERSIST-FIELD", DroneRole.ENGINEER);
		field.assignFieldOperation(FieldOperationType.EXCAVATE, base.offset(8, 1, 8), 4, "persist-field");
		player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] PERSISTENCE PREPARED 4/4 / save and restart world"));
		return Command.SINGLE_SUCCESS;
	}

	private static DroneEntity persistenceDrone(ServerLevel level, ServerPlayer player, BlockPos pos,
		String group, DroneRole role) {
		DroneEntity drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
		if (drone == null) throw new IllegalStateException("failed to create persistence probe");
		drone.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		drone.initializeOwner(player);
		drone.assignGroup(group);
		drone.assignRole(role);
		level.addFreshEntity(drone);
		return drone;
	}

	private static BlockPos verificationBase(ServerLevel level, BlockPos requested, int overhead) {
		int minimum = level.getMinY() + 4;
		int maximum = level.getMaxY() - Math.max(8, overhead) - 1;
		return new BlockPos(requested.getX(), Mth.clamp(requested.getY(), minimum, maximum), requested.getZ());
	}

	private static int checkPersistence(ServerPlayer player) {
		ServerLevel level = player.level();
		List<DroneEntity> probes = level.getEntitiesOfClass(DroneEntity.class,
			player.getBoundingBox().inflate(128), drone -> drone.isOwnedBy(player)
				&& drone.groupId().startsWith("VERIFY-PERSIST-"));
		List<String> failures = new ArrayList<>();
		if (probes.size() != 4) failures.add("probe count " + probes.size() + "/4");
		DroneEntity move = probe(probes, "VERIFY-PERSIST-MOVE");
		if (move == null || move.mode() != DroneMode.WAYPOINT || !move.hasWaypoint()
			|| !move.missionId().equals("persist-move") || move.missionExpected() < 1
			|| move.missionIndex() < 0 || move.missionIndex() >= move.missionExpected())
			failures.add("waypoint state not restored");
		DroneEntity cargo = probe(probes, "VERIFY-PERSIST-CARGO");
		if (cargo == null || cargo.role() != DroneRole.CARGO || !cargo.hasCargoSource()
			|| !cargo.hasCargoTarget()) failures.add("cargo route not restored");
		DroneEntity security = probe(probes, "VERIFY-PERSIST-SEC");
		if (security == null || !security.hasSecurityPatrol()) failures.add("security patrol not restored");
		DroneEntity field = probe(probes, "VERIFY-PERSIST-FIELD");
		if (field == null || field.hasFieldOperation() || field.mode() != DroneMode.STANDBY)
			failures.add("session field operation did not cancel safely");
		String result = failures.isEmpty() ? "PERSISTENCE PASS 4/4" : "PERSISTENCE FAIL " + String.join("; ", failures);
		player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] " + result));
		MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] {}", result);
		for (DroneEntity probe : probes) probe.discard();
		return failures.isEmpty() ? Command.SINGLE_SUCCESS : 0;
	}

	private static int startSalvage(ServerPlayer player) {
		if (active != null && !active.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: " + active.progress()));
			return 0;
		}
		if (salvageVerification != null && !salvageVerification.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: "
				+ salvageVerification.progress()));
			return 0;
		}
		salvageVerification = new SalvageVerificationSession(player);
		return Command.SINGLE_SUCCESS;
	}

	private static int startSalvageService(ServerPlayer player) {
		if (salvageVerification != null && !salvageVerification.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: "
				+ salvageVerification.progress()));
			return 0;
		}
		salvageVerification = new SalvageVerificationSession(player, true);
		return Command.SINGLE_SUCCESS;
	}

	private static int startSalvageVertical(ServerPlayer player) {
		if (salvageVerification != null && !salvageVerification.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: "
				+ salvageVerification.progress()));
			return 0;
		}
		salvageVerification = new SalvageVerificationSession(player, false, true);
		return Command.SINGLE_SUCCESS;
	}

	private static int startSolarService(ServerPlayer player) {
		if (solarServiceVerification != null && !solarServiceVerification.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: "
				+ solarServiceVerification.progress()));
			return 0;
		}
		if (MorrowgearAutomation.requested()) isolateSolarAutomation(player);
		solarServiceVerification = new SolarServiceVerificationSession(player);
		return Command.SINGLE_SUCCESS;
	}

	private static void isolateSolarAutomation(ServerPlayer player) {
		ServerLevel level = player.level();
		List<Entity> stale = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) if (entity instanceof DroneEntity
			|| entity instanceof SolarServiceStationEntity || entity instanceof ChargingRelayEntity) stale.add(entity);
		stale.forEach(Entity::discard);
		SolarServiceRegistry.clearForTests();
		FieldRelayRuntime.invalidate(player);
	}

	private static int startDockExhaustion(ServerPlayer player) {
		if (dockExhaustionVerification != null && !dockExhaustionVerification.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: "
				+ dockExhaustionVerification.progress()));
			return 0;
		}
		dockExhaustionVerification = new DockExhaustionVerificationSession(player);
		return Command.SINGLE_SUCCESS;
	}

	private static int startDynamicDock(ServerPlayer player) {
		if (dynamicDockVerification != null && !dynamicDockVerification.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: "
				+ dynamicDockVerification.progress()));
			return 0;
		}
		dynamicDockVerification = new DynamicDockVerificationSession(player);
		return Command.SINGLE_SUCCESS;
	}

	private static int startCargoLift(ServerPlayer player) {
		if (cargoLiftVerification != null && !cargoLiftVerification.done()) return 0;
		cargoLiftVerification = new CargoLiftVerificationSession(player);
		return Command.SINGLE_SUCCESS;
	}

	private static DroneEntity probe(List<DroneEntity> probes, String group) {
		return probes.stream().filter(drone -> drone.groupId().equals(group)).findFirst().orElse(null);
	}

	private static void tick(MinecraftServer server) {
		if (arenaBuilder != null && !arenaBuilder.done()) arenaBuilder.tick(server);
		if (active != null && !active.done()) active.tick();
		if (visualEffects != null && visualEffects.tick()) visualEffects = null;
		if (salvageVerification != null && !salvageVerification.done()) salvageVerification.tick();
		if (solarServiceVerification != null && !solarServiceVerification.done()) solarServiceVerification.tick();
		if (dockExhaustionVerification != null && !dockExhaustionVerification.done())
			dockExhaustionVerification.tick();
		if (dynamicDockVerification != null && !dynamicDockVerification.done())
			dynamicDockVerification.tick();
		if (cargoLiftVerification != null && !cargoLiftVerification.done()) cargoLiftVerification.tick();
	}

	private static String status() {
		String arena = arenaBuilder == null ? "arena not started" : arenaBuilder.status();
		String verification = active == null ? "idle" : active.progress();
		String salvage = salvageVerification == null ? "salvage not started" : salvageVerification.progress();
		String solar = solarServiceVerification == null ? "solar not started" : solarServiceVerification.progress();
		String dockExhaustion = dockExhaustionVerification == null ? "dock exhaustion not started"
			: dockExhaustionVerification.progress();
		String dynamicDock = dynamicDockVerification == null ? "dock allocation not started"
			: dynamicDockVerification.progress();
		return "[MORROWGEAR VERIFY] " + verification + " / " + arena + " / " + salvage
			+ " / " + solar + " / " + dockExhaustion + " / " + dynamicDock;
	}

	private static final class CargoLiftVerificationSession {
		private static final long TIMEOUT_TICKS = 2400;
		private final ServerPlayer player;
		private final ServerLevel level;
		private final BlockPos source;
		private final BlockPos destination;
		private final BlockPos platformBase;
		private final UUID containerId;
		private final List<DroneEntity> aircraft = new ArrayList<>();
		private final long startedTick;
		private final EnumSet<CargoLiftPolicy.Phase> phases = EnumSet.noneOf(CargoLiftPolicy.Phase.class);
		private CargoLiftLoadEntity mission;
		private boolean fourCornersObserved;
		private boolean carriedObserved;
		private boolean transitClearanceObserved;
		private boolean transitFormationObserved;
		private boolean sourceInteractionProxyObserved;
		private boolean blockedIngressReplanned;
		private int maximumRouteReplansObserved;
		private double minimumAircraftSeparation=Double.POSITIVE_INFINITY;
		private double maximumLoadLead=Double.NEGATIVE_INFINITY;
		private boolean commandIssued;
		private boolean complete;
		private String result = "CARGO LIFT RUNNING";

		private CargoLiftVerificationSession(ServerPlayer player) {
			this.player=player;
			this.level=player.level();
			BlockPos base=verificationBase(level, player.blockPosition().below().offset(42,0,34), 32);
			platformBase=base;
			source=base.above();
			destination=base.offset(32,1,0);
			for(DroneEntity existing:level.getEntitiesOfClass(DroneEntity.class,
				new AABB(base).inflate(96),drone->drone.isOwnedBy(player)
					&&drone.groupId().startsWith("VERIFY-CARGO-LIFT")))existing.discard();
			for(CargoLiftLoadEntity lift:level.getEntitiesOfClass(CargoLiftLoadEntity.class,
				new AABB(base).inflate(128),entity->entity.ownerId().equals(player.getUUID())))lift.discard();
			cleanupVerificationPlatform(base);
			prepareSite(base);
			var ledger=jp.morrowgear.drone.carrier.CarrierExpansionLedger.get(level.getServer());
			for(BlockPos pos:List.of(source,destination)){
				jp.morrowgear.drone.carrier.CarrierContainerFootprint.removeContainer(level,pos);
				ledger.releaseMissingPlaced(level.dimension().identifier().toString(),pos.asLong(),
					"cargo-lift-verification-reset",level.getGameTime());
			}
			containerId=UUID.randomUUID();
			ItemStack module=new ItemStack(jp.morrowgear.drone.carrier.CarrierModule.CARGO_MODULE_ITEM);
			jp.morrowgear.drone.carrier.CarrierExpansionItemData.write(module,
				new jp.morrowgear.drone.carrier.CarrierExpansionItemData.Identity(containerId,player.getUUID(),
					jp.morrowgear.drone.carrier.CarrierExpansionPolicy.Type.CARGO));
			var contents=net.minecraft.core.NonNullList.withSize(
				jp.morrowgear.drone.carrier.CarrierPolicy.STORAGE_SLOTS_PER_MODULE,ItemStack.EMPTY);
			contents.set(0,new ItemStack(Items.DIAMOND,7));
			module.set(net.minecraft.core.component.DataComponents.CONTAINER,
				net.minecraft.world.item.component.ItemContainerContents.fromItems(contents));
			if(!level.setBlockAndUpdate(source,
				jp.morrowgear.drone.carrier.CarrierModule.CARGO_CONTAINER_BLOCK.defaultBlockState())
				||!(level.getBlockEntity(source) instanceof jp.morrowgear.drone.carrier.CarrierCargoContainerBlockEntity container))
				throw new IllegalStateException("cargo lift verification container creation failed");
			container.initialize(player,module);
			jp.morrowgear.drone.carrier.CarrierContainerFootprint.ensure(level,source);
			BlockPos sourceInteraction=source.offset(2,1,1);
			sourceInteractionProxyObserved=level.getBlockState(sourceInteraction).is(
				jp.morrowgear.drone.carrier.CarrierModule.CONTAINER_PART_BLOCK)
				&&jp.morrowgear.drone.carrier.CarrierContainerFootprint.open(player,sourceInteraction);
			if(sourceInteractionProxyObserved)player.closeContainer();
			for(int index=0;index<CargoLiftPolicy.REQUIRED_AIRCRAFT;index++){
				DroneEntity drone=MorrowgearDrone.DRONE.create(level,EntitySpawnReason.TRIGGERED);
				if(drone==null)throw new IllegalStateException("cargo lift verification aircraft creation failed");
				drone.setPos(source.getX()+.5+(index==0?-18:index<2?-6:6),source.getY()+8,
					source.getZ()+.5+(index%2==0?-4:4));
				drone.initializeOwner(player);drone.assignGroup("VERIFY-CARGO-LIFT-"+index);
				drone.assignRole(DroneRole.SALVAGE);drone.setPowerForVerification(1000,1000);
				level.addFreshEntity(drone);aircraft.add(drone);
			}
			// One aircraft starts behind a finite wall. The mission must route around it
			// without probing the same blocked corridor on every server tick.
			for(int z=-6;z<=0;z++)for(int y=5;y<=12;y++)
				level.setBlockAndUpdate(source.offset(-11,y,z),Blocks.SMOOTH_STONE.defaultBlockState());
			startedTick=level.getGameTime();
		}

		private void prepareSite(BlockPos base){
			int clearHeight=Math.max(1,Math.min(48,level.getMaxY()-base.getY()-1));
			for(int x=-24;x<=42;x++)for(int z=-10;z<=10;z++){
				level.setBlockAndUpdate(base.offset(x,0,z),Blocks.SMOOTH_STONE.defaultBlockState());
				// A reused verification world may place the player beneath a mountain or
				// an older fixture. Cargo lift clearance uses the heightmap, so leaving a
				// single block above this arena can create an impossible world-height
				// target and strand the mission in LIFT.
				for(int y=1;y<=clearHeight;y++)level.setBlockAndUpdate(base.offset(x,y,z),Blocks.AIR.defaultBlockState());
			}
		}

		private void cleanupVerificationPlatform(BlockPos base){
			for(int x=-24;x<=42;x++)for(int z=-10;z<=10;z++){
				BlockPos floor=base.offset(x,0,z);
				if(level.getBlockState(floor).is(Blocks.SMOOTH_STONE))level.removeBlock(floor,false);
				for(int y=1;y<=14;y++){
					BlockPos obstacle=base.offset(x,y,z);
					if(level.getBlockState(obstacle).is(Blocks.SMOOTH_STONE))level.removeBlock(obstacle,false);
				}
			}
		}

		private CargoLiftLoadEntity findMission(){
			for(Entity entity:level.getAllEntities())if(entity instanceof CargoLiftLoadEntity lift
				&&lift.ownerId().equals(player.getUUID())&&lift.sourcePos().equals(source))return lift;
			return null;
		}

		private void tick(){
			if(complete)return;
			long elapsed=level.getGameTime()-startedTick;
			if(elapsed>=TIMEOUT_TICKS){finish(false,"timeout / phases="+phases+" / reason="
				+(mission==null?"mission missing":mission.lastReason()));return;}
			if(aircraft.stream().anyMatch(drone->!drone.isAlive())){finish(false,"aircraft removed");return;}
			// Entity registration and the owned-drone roster cache settle at the next
			// server tick in ordinary play. Issue the command after that boundary so
			// this verifier exercises the same path as a real controller command.
			if(!commandIssued&&elapsed>=2){
				MorrowgearDrone.clearDroneRosterCache(player.getUUID());
				CargoLiftNetwork.apply(player,jp.morrowgear.drone.network.CargoLiftCommandPayload.start(
					source.asLong(),destination.asLong()));
				commandIssued=true;
				return;
			}
			if(!commandIssued)return;
			if(commandIssued&&mission==null){
				mission=findMission();
				if(mission==null&&elapsed<40)return;
				if(mission==null){finish(false,"mission was not created / "+candidateDiagnostics());return;}
			}
			if(mission!=null&&!mission.isRemoved()){
				blockedIngressReplanned|=aircraft.stream().anyMatch(drone->drone.cargoLiftRouteReplansForVerification()>0);
				maximumRouteReplansObserved=Math.max(maximumRouteReplansObserved,
					aircraft.stream().mapToInt(DroneEntity::cargoLiftRouteReplansForVerification).max().orElse(0));
				phases.add(mission.phase());
				fourCornersObserved|=mission.aircraft().size()==4
					&&mission.aircraft().keySet().containsAll(EnumSet.allOf(CargoLiftPolicy.Corner.class));
				carriedObserved|=mission.carried()&&!level.getBlockState(source).is(
					jp.morrowgear.drone.carrier.CarrierModule.CARGO_CONTAINER_BLOCK);
				if(mission.phase()==CargoLiftPolicy.Phase.TRANSIT){
					for(int a=0;a<aircraft.size();a++)for(int b=a+1;b<aircraft.size();b++){
						double separationX=aircraft.get(a).getX()-aircraft.get(b).getX();
						double separationZ=aircraft.get(a).getZ()-aircraft.get(b).getZ();
						minimumAircraftSeparation=Math.min(minimumAircraftSeparation,
							Math.sqrt(separationX*separationX+separationZ*separationZ));
					}
					double centreX=aircraft.stream().mapToDouble(DroneEntity::getX).average().orElse(mission.getX());
					double centreZ=aircraft.stream().mapToDouble(DroneEntity::getZ).average().orElse(mission.getZ());
					double routeX=destination.getX()-source.getX(),routeZ=destination.getZ()-source.getZ();
					double routeLength=Math.sqrt(routeX*routeX+routeZ*routeZ);
					if(routeLength>.001)maximumLoadLead=Math.max(maximumLoadLead,
						(mission.getX()-centreX)*routeX/routeLength+(mission.getZ()-centreZ)*routeZ/routeLength);
					transitFormationObserved=true;
					int terrain=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						mission.getBlockX(),mission.getBlockZ());
					if(mission.getY()+.01<terrain+CargoLiftPolicy.MIN_LOAD_BOTTOM_CLEARANCE){
						finish(false,"suspended load violated terrain clearance / y="+mission.getY()
							+" / required="+(terrain+CargoLiftPolicy.MIN_LOAD_BOTTOM_CLEARANCE)
							+" / phaseTicks="+elapsed);return;
					}
					transitClearanceObserved=true;
				}
				return;
			}
			CargoLiftLoadEntity terminalMission=mission;
			mission=findMission();
			if(mission!=null)return;
			if(!(level.getBlockEntity(destination) instanceof jp.morrowgear.drone.carrier.CarrierCargoContainerBlockEntity placed)){
				finish(false,"delivered container missing / phases="+phases+" / finalPhase="
					+(terminalMission==null?"missing":terminalMission.phase())+" / reason="
					+(terminalMission==null?"missing":terminalMission.lastReason())+" / carried="
					+(terminalMission!=null&&terminalMission.carried())+" / source="+level.getBlockState(source)
					+" / destination="+level.getBlockState(destination)+" / nearby="+nearbyContainerDiagnostics());return;
			}
			boolean contents=placed.cargoId().equals(containerId)&&placed.indexedCount("minecraft:diamond")==7;
			BlockPos deliveredInteraction=destination.offset(2,1,1);
			boolean deliveredInteractionProxy=level.getBlockState(deliveredInteraction).is(
				jp.morrowgear.drone.carrier.CarrierModule.CONTAINER_PART_BLOCK)
				&&jp.morrowgear.drone.carrier.CarrierContainerFootprint.open(player,deliveredInteraction);
			if(deliveredInteractionProxy)player.closeContainer();
			boolean released=aircraft.stream().noneMatch(DroneEntity::cargoLiftAssigned);
			boolean phasesComplete=phases.containsAll(EnumSet.of(CargoLiftPolicy.Phase.CONNECTING,
				CargoLiftPolicy.Phase.TENSIONING,CargoLiftPolicy.Phase.LIFT,CargoLiftPolicy.Phase.TRANSIT,
				CargoLiftPolicy.Phase.DESCENT,CargoLiftPolicy.Phase.RELEASE));
			boolean formationSpacing=transitFormationObserved&&minimumAircraftSeparation>=5.25;
			boolean loadDidNotLead=transitFormationObserved&&maximumLoadLead<=.35;
			finish(fourCornersObserved&&carriedObserved&&transitClearanceObserved&&formationSpacing
				&&loadDidNotLead&&contents&&sourceInteractionProxyObserved&&deliveredInteractionProxy
				&&released&&phasesComplete&&blockedIngressReplanned&&maximumRouteReplansObserved<=20,
				"four corners="+fourCornersObserved+" / carried="+carriedObserved+" / clearance="
				+transitClearanceObserved+" / min aircraft separation="+minimumAircraftSeparation
				+" / max load lead="+maximumLoadLead+" / identity+contents="+contents
				+" / source interaction="+sourceInteractionProxyObserved
				+" / delivered interaction="+deliveredInteractionProxy+" / released="+released
				+" / blocked ingress replanned="+blockedIngressReplanned+" / max replans="+maximumRouteReplansObserved
				+" / phases="+phases);
		}

		private String candidateDiagnostics(){
			long candidates=aircraft.stream().filter(DroneEntity::cargoLiftCandidate).count();
			return "candidates="+candidates+"/"+aircraft.size()+" / "+aircraft.stream()
				.map(drone->drone.unitId()+":"+drone.role()+":"+drone.batteryPercent()+"%:payload="
					+drone.payloadCondition()+":mode="+drone.mode())
				.collect(java.util.stream.Collectors.joining(","));
		}

		private String nearbyContainerDiagnostics(){
			List<String> matches=new ArrayList<>();
			for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=-8;y<=8;y++){
				BlockPos pos=destination.offset(x,y,z);
				if(level.getBlockEntity(pos) instanceof jp.morrowgear.drone.carrier.CarrierCargoContainerBlockEntity container)
					matches.add(pos.toShortString()+":"+container.cargoId());
			}
			return matches.isEmpty()?"none":String.join(",",matches);
		}

		private void finish(boolean pass,String detail){
			complete=true;result="CARGO LIFT "+(pass?"PASS":"FAIL")+" / "+detail;
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] "+result));
			if(pass)cleanupCompletedFixture();
		}

		private void cleanupCompletedFixture(){
			var ledger=jp.morrowgear.drone.carrier.CarrierExpansionLedger.get(level.getServer());
			for(BlockPos pos:List.of(source,destination)){
				jp.morrowgear.drone.carrier.CarrierContainerFootprint.removeContainer(level,pos);
				ledger.releaseMissingPlaced(level.dimension().identifier().toString(),pos.asLong(),
					"cargo-lift-verification-complete",level.getGameTime());
			}
			for(DroneEntity drone:aircraft)if(drone.isAlive())drone.discard();
			cleanupVerificationPlatform(platformBase);
		}
		private boolean done(){return complete;}
	}

	private static final class DynamicDockVerificationSession {
		private static final long TIMEOUT_TICKS = 2400L;
		private final ServerPlayer player;
		private final ServerLevel level;
		private final BlockPos dockPos;
		private final DroneEntity parked;
		private final DroneEntity ordinary;
		private final DroneEntity service;
		private final long startedTick;
		private boolean queueObserved;
		private boolean holdingOrbitObserved;
		private boolean priorityReservationObserved;
		private boolean parkedYieldObserved;
		private boolean noDockHoldingObserved;
		private boolean serviceLandingObserved;
		private boolean dockCreated;
		private boolean lateDockCreated;
		private double ordinaryTravel;
		private Vec3 previousOrdinary;
		private boolean complete;
		private String result = "DOCK ALLOCATION RUNNING";

		private DynamicDockVerificationSession(ServerPlayer player) {
			this.player = player;
			this.level = player.level();
			BlockPos base = verificationBase(level, player.blockPosition().below().offset(0, 0, 18), 24);
			// This dedicated verification world is reused between command-only runs.
			// Remove stale probes and Docks so they cannot satisfy a reservation before
			// this scenario has observed its required no-Dock and queue phases.
			for (DroneEntity existing : level.getEntitiesOfClass(DroneEntity.class,
				new AABB(base).inflate(640.0), drone -> drone.isOwnedBy(player))) existing.discard();
			for (DockBlockEntity existing : List.copyOf(MorrowgearDrone.findDocks(level, player, base, 320)))
				MorrowgearDrone.removeDock(level, existing.getBlockPos(), false);
			dockPos = base.above();
			for (int x = -22; x <= 22; x++) for (int z = -22; z <= 22; z++) {
				level.setBlockAndUpdate(base.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
				for (int y = 1; y <= 18; y++) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
			}
			parked = createDrone(dockPos.getX() + .5, dockPos.getY() + .29, dockPos.getZ() + .5,
				"VERIFY-DOCK-PARKED", 1000);
			parked.setMode(DroneMode.STANDBY);
			ordinary = createDrone(dockPos.getX() + 12.5, dockPos.getY() + 8.0, dockPos.getZ() + .5,
				"VERIFY-DOCK-ORDINARY", 900);
			ordinary.requestManualDockReturn();
			service = createDrone(dockPos.getX() - 12.5, dockPos.getY() + 8.0, dockPos.getZ() + .5,
				"VERIFY-DOCK-SERVICE", 240);
			service.beginAirborneServiceReturnForVerification(DroneServicePolicy.Need.FLIGHT_POWER);
			previousOrdinary = ordinary.position();
			startedTick = level.getGameTime();
			say("DOCK ALLOCATION START / drones before Docks / use an area without other owned Docks");
		}

		private void createDock(BlockPos pos) {
			for (int index = 0; index < MorrowgearDrone.DOCK_PARTS.length; index++) {
				if (index == 4) continue;
				BlockPos part = pos.offset(index % 3 - 1, 0, index / 3 - 1);
				level.setBlockAndUpdate(part, MorrowgearDrone.DOCK_PARTS[index].defaultBlockState());
			}
			level.setBlockAndUpdate(pos, MorrowgearDrone.DOCK_PARTS[4].defaultBlockState());
			if (!(level.getBlockEntity(pos) instanceof DockBlockEntity dock))
				throw new IllegalStateException("dynamic Dock verifier Dock was not created");
			dock.initialize(player);
			dock.setStoredPowerForVerification(6000);
			dock.setItem(DockBlockEntity.SLOT_POWER_INPUT, new ItemStack(Items.CHARCOAL, 16));
			DockAllocationRuntime.invalidate(level, player.getUUID());
		}

		private DroneEntity createDrone(double x, double y, double z, String group, int power) {
			DroneEntity drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
			if (drone == null) throw new IllegalStateException("failed to create dynamic Dock probe");
			drone.setPos(x, y, z);
			drone.initializeOwner(player);
			drone.assignGroup(group);
			drone.assignRole(DroneRole.FIELD);
			drone.setPowerForVerification(power, 1000);
			level.addFreshEntity(drone);
			return drone;
		}

		private void tick() {
			if (complete) return;
			if (!parked.isAlive() || !ordinary.isAlive() || !service.isAlive()) {
				finish(false, "probe removed before completion");
				return;
			}
			long elapsed = level.getGameTime() - startedTick;
			if (!dockCreated) {
				noDockHoldingObserved |= ordinary.dockHolding() && !ordinary.hasDock() && !ordinary.isDocked()
					&& service.dockHolding() && !service.hasDock() && !service.isDocked();
				if (elapsed < 80) return;
				createDock(dockPos);
				parked.assignDock(dockPos);
				parked.setPos(dockPos.getX() + .5, dockPos.getY() + .344, dockPos.getZ() + .5);
				parked.setDockedForCommission();
				dockCreated = true;
			}
			queueObserved |= service.dockHolding() && service.dockQueuePosition() == 1
				&& ordinary.dockHolding() && ordinary.dockQueuePosition() >= 2;
			ordinaryTravel += ordinary.position().distanceTo(previousOrdinary);
			previousOrdinary = ordinary.position();
			holdingOrbitObserved |= ordinary.dockHolding() && ordinary.getY() >= dockPos.getY() + 6.0
				&& ordinaryTravel >= 8.0;
			priorityReservationObserved |= service.hasDock() && service.dockPos().equals(dockPos);
			parkedYieldObserved |= parked.dockHolding() && !parked.hasDock();
			serviceLandingObserved |= service.isDocked() && service.hasDock()
				&& DockServicePolicy.serviceEnvelope(service.position(), Vec3.atCenterOf(service.dockPos()));
			if (!lateDockCreated && queueObserved && holdingOrbitObserved
				&& priorityReservationObserved && parkedYieldObserved) {
				createDock(dockPos.offset(10, 0, 0));
				lateDockCreated = true;
			}
			if (noDockHoldingObserved && lateDockCreated && serviceLandingObserved && ordinary.isDocked()
				&& ordinary.dockPos().equals(dockPos.offset(10, 0, 0))
				&& DockServicePolicy.serviceEnvelope(ordinary.position(), Vec3.atCenterOf(ordinary.dockPos()))) {
				finish(true, "airborne no Dock hold>priority queue>parked yield>late Dock>both actual landings");
				return;
			}
			if (elapsed >= TIMEOUT_TICKS) finish(false, "timeout / queue=" + queueObserved
				+ " orbit=" + holdingOrbitObserved + " priority=" + priorityReservationObserved
				+ " yield=" + parkedYieldObserved + " noDock=" + noDockHoldingObserved
				+ " serviceLanded=" + serviceLandingObserved + " ordinaryLanded=" + ordinary.isDocked()
				+ " q=" + ordinary.dockQueuePosition());
		}

		private void finish(boolean pass, String detail) {
			complete = true;
			result = "DOCK ALLOCATION " + (pass ? "PASS" : "FAIL") + " / " + detail;
			say(result);
			if (parked.isAlive()) parked.discard();
			if (ordinary.isAlive()) ordinary.discard();
			if (service.isAlive()) service.discard();
			MorrowgearDrone.removeDock(level, dockPos, false);
			if (lateDockCreated) MorrowgearDrone.removeDock(level, dockPos.offset(10, 0, 0), false);
		}

		private void say(String message) {
			String text = "[MORROWGEAR VERIFY] " + message;
			player.sendSystemMessage(Component.literal(text));
			MorrowgearDrone.LOGGER.info(text);
		}

		private boolean done() { return complete; }
		private String progress() { return complete ? result : "DOCK ALLOCATION t="
			+ (level.getGameTime() - startedTick); }
	}

	private static final class DockExhaustionVerificationSession {
		private static final long TIMEOUT_TICKS = 240L;
		private final ServerPlayer player;
		private final ServerLevel level;
		private final BlockPos dockPos;
		private final DroneEntity drone;
		private final Zombie target;
		private final long startedTick;
		private boolean launchObserved;
		private boolean limitedStatusObserved;
		private boolean combatObserved;
		private boolean complete;
		private String result = "DOCK EXHAUSTION RUNNING";

		private DockExhaustionVerificationSession(ServerPlayer player) {
			this.player = player;
			this.level = player.level();
			BlockPos base = verificationBase(level, player.blockPosition().below().offset(-14, 0, 0), 16);
			dockPos = base.above();
			for (int x = -4; x <= 20; x++) for (int z = -6; z <= 6; z++) {
				level.setBlockAndUpdate(base.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
				for (int y = 1; y <= 8; y++) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
			}
			for (int index = 0; index < MorrowgearDrone.DOCK_PARTS.length; index++) {
				if (index == 4) continue;
				BlockPos part = dockPos.offset(index % 3 - 1, 0, index / 3 - 1);
				level.setBlockAndUpdate(part, MorrowgearDrone.DOCK_PARTS[index].defaultBlockState());
			}
			level.setBlockAndUpdate(dockPos, MorrowgearDrone.DOCK_PARTS[4].defaultBlockState());
			if (!(level.getBlockEntity(dockPos) instanceof DockBlockEntity dock))
				throw new IllegalStateException("dock exhaustion verifier Dock was not created");
			dock.initialize(player);
			dock.setStoredPowerForVerification(0);
			dock.setItem(DockBlockEntity.SLOT_POWER_INPUT, ItemStack.EMPTY);
			dock.setItem(DockBlockEntity.SLOT_AMMUNITION, ItemStack.EMPTY);

			drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
			target = new Zombie(level);
			if (drone == null || target == null) throw new IllegalStateException("failed to create dock exhaustion probes");
			drone.setPos(dockPos.getX() + .5, dockPos.getY() + .29, dockPos.getZ() + .5);
			drone.initializeOwner(player);
			drone.assignGroup("VERIFY-DOCK-EXHAUSTION");
			drone.assignRole(DroneRole.SECURITY);
			drone.assignSecurityLoadout(SecurityLoadout.AUTOCANNON);
			drone.assignDock(dockPos);
			drone.setDockedForCommission();
			drone.setPowerForVerification(700, 700);
			drone.setCombatResourcesForVerification(12, 0, 0);
			level.addFreshEntity(drone);

			target.setPos(base.getX() + 15.5, base.getY() + 1.0, base.getZ() + .5);
			target.setNoAi(true);
			level.addFreshEntity(target);
			drone.beginServiceReturnForVerification(DroneServicePolicy.Need.FLIGHT_POWER, target);
			startedTick = level.getGameTime();
			say("DOCK EXHAUSTION START / FLT 70 WPN 70 AMMO 12 / Dock fuel and ammunition empty");
		}

		private void tick() {
			if (complete) return;
			long elapsed = level.getGameTime() - startedTick;
			launchObserved |= !drone.isDocked() && !drone.serviceReturnActive();
			limitedStatusObserved |= drone.dataLinkStatus().startsWith("RESOURCE LIMITED SORTIE");
			combatObserved |= drone.combatActive() && drone.combatTargetId() == target.getId();
			if (launchObserved && limitedStatusObserved && combatObserved) {
				finish(true, "Dock resource exhaustion>limited sortie>combat resume");
				return;
			}
			if (elapsed >= TIMEOUT_TICKS) finish(false, "timeout / docked=" + drone.isDocked()
				+ " service=" + drone.serviceReturnActive() + " status=" + drone.dataLinkStatus()
				+ " combat=" + drone.combatState().name());
		}

		private void finish(boolean pass, String detail) {
			complete = true;
			result = "DOCK EXHAUSTION " + (pass ? "PASS" : "FAIL") + " / " + detail;
			say(result);
			if (drone.isAlive()) drone.discard();
			if (target.isAlive()) target.discard();
			MorrowgearDrone.removeDock(level, dockPos, false);
		}

		private void say(String message) {
			String text = "[MORROWGEAR VERIFY] " + message;
			player.sendSystemMessage(Component.literal(text));
			MorrowgearDrone.LOGGER.info(text);
		}

		private boolean done() { return complete; }
		private String progress() { return complete ? result : "DOCK EXHAUSTION t="
			+ (level.getGameTime() - startedTick); }
	}

	private static final class SolarServiceVerificationSession {
		private static final long TIMEOUT_TICKS = 2400L;
		private static final String RESUME_MISSION = "VERIFY-SOLAR-RESUME";
		private final ServerPlayer player;
		private final ServerLevel level;
		private final SolarServiceStationEntity station;
		private final DroneEntity drone;
		private final DroneEntity relayDrone;
		private final long startedTick;
		private final Vec3 playerAnchor;
		private final Vec3 initialDronePosition;
		private final int initialBattery;
		private Vec3 resumePosition;
		private double previousAngle = Double.NaN;
		private double angularTravel;
		private int stableOrbitSamples;
		private boolean solarAssignmentObserved;
		private boolean linkCompletionObserved;
		private boolean missionResumeObserved;
		private boolean relayExtensionObserved;
		private boolean relayAircraftObserved;
		private boolean relayCommandIssued;
		private boolean relayCommandObserved;
		private String relayLoiterSlot = "";
		private double relayPreviousAngle = Double.NaN;
		private double relayAngularTravel;
		private int relayLoiterSamples;
		private boolean relayServiceInjected;
		private boolean relaySolarObserved;
		private boolean relaySolarCompletionObserved;
		private boolean relayMeshRejoinObserved;
		private int relayPostServiceLoiterSamples;
		private final Vec3 relayStart;
		private final BlockPos relayDestination;
		private boolean complete;
		private String result = "SOLAR SERVICE RUNNING";

		private SolarServiceVerificationSession(ServerPlayer player) {
			this.player = player;
			this.level = player.level();
			playerAnchor = player.position();
			player.setDeltaMovement(Vec3.ZERO);
			Vec3 forward = player.getLookAngle().multiply(1, 0, 1);
			if (forward.lengthSqr() < 0.01) forward = new Vec3(0, 0, 1);
			forward = forward.normalize();
			Vec3 right = new Vec3(-forward.z, 0, forward.x);
			Vec3 center = player.position().add(forward.scale(22.0)).add(0, 10.0, 0);
			// This scenario reuses the automation world. Previous obstacle, carrier, or
			// excavation fixtures may occupy the high-altitude test cell, so isolate the
			// station and both initial aircraft positions before entities are created.
			BlockPos airspace = BlockPos.containing(center);
			for (int x = -18; x <= 18; x++) for (int z = -18; z <= 18; z++)
				for (int y = -4; y <= 12; y++)
					level.setBlockAndUpdate(airspace.offset(x, y, z), Blocks.AIR.defaultBlockState());
			station = MorrowgearDrone.SOLAR_SERVICE_STATION.create(level, EntitySpawnReason.TRIGGERED);
			drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
			relayDrone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
			if (station == null || drone == null || relayDrone == null)
				throw new IllegalStateException("failed to create solar probes");
			station.deploy(player, BlockPos.containing(center));
			level.addFreshEntity(station);
			Vec3 aircraft = center.add(right.scale(13.0)).add(0, 1.5, 0);
			drone.setPos(aircraft.x, aircraft.y, aircraft.z);
			drone.initializeOwner(player);
			drone.assignGroup("VERIFY-SOLAR-SERVICE");
			drone.assignRole(DroneRole.SCOUT);
			drone.assignWaypoint(BlockPos.containing(center.add(forward.scale(36.0))), RESUME_MISSION,
				1, 0, drone.blockPosition(), level.getGameTime());
			// Allow the station to publish its first heartbeat before forcing diversion.
			drone.setPowerForVerification(400, 1000);
			level.addFreshEntity(drone);
			// Deploy from the loaded base area. Teleporting the relay directly into an
			// unloaded remote chunk only tests chunk resurrection, not mesh formation.
			relayStart = center.add(right.scale(-10.0));
			relayDestination = BlockPos.containing(center.add(forward.scale(240.0))
				.add(right.scale(20.0)));
			BlockPos relayBlock = BlockPos.containing(relayStart);
			OperationChunkLeaseRuntime.refreshDataLink(level, new net.minecraft.world.level.ChunkPos(
				relayBlock.getX() >> 4, relayBlock.getZ() >> 4));
			relayDrone.setPos(relayStart.x, relayStart.y, relayStart.z);
			relayDrone.initializeOwner(player);
			relayDrone.assignGroup("VERIFY-SOLAR-RELAY");
			relayDrone.assignRole(DroneRole.FIELD);
			relayDrone.setFieldMeshAuthorized(true);
			relayDrone.setPowerForVerification(1000, 1000);
			level.addFreshEntity(relayDrone);
			startedTick = level.getGameTime();
			initialDronePosition = drone.position();
			initialBattery = drone.batteryPercent();
			say("SOLAR SERVICE START / 1 drone / waypoint diversion, orbit and mission resume");
		}

		private void tick() {
			if (!player.isAlive() || !station.isAlive() || !drone.isAlive() || !relayDrone.isAlive()) {
				finish(false, "probe removed before completion");
				return;
			}
			long elapsed = level.getGameTime() - startedTick;
			player.setDeltaMovement(Vec3.ZERO);
			if (player.position().distanceToSqr(playerAnchor) > 0.01)
				player.teleportTo(playerAnchor.x, playerAnchor.y, playerAnchor.z);
			if (elapsed == 5) drone.setPowerForVerification(200, 1000);
			DataLinkRelayPolicy.Coverage relayCoverage = DataLinkRelayNetwork.coverage(player);
			relayAircraftObserved |= relayDrone.isAlive() && relayCoverage.relays().stream()
				.anyMatch(node -> node.node().id().equals(relayDrone.getUUID()));
				if (relayAircraftObserved) {
					Vec3 outward = relayDrone.position().subtract(player.position()).multiply(1, 0, 1);
					if (outward.lengthSqr() > 0.01) {
						Vec3 remotePoint = relayDrone.position().add(outward.normalize().scale(80.0));
						DataLinkRelayPolicy.Link link = DataLinkRelayNetwork.link(player, remotePoint);
						relayExtensionObserved |= link.connected() && link.hops() == 1;
					}
			}
				if (!relayCommandIssued && elapsed >= 80) {
					relayCommandIssued = true;
					DataLinkRelayNetwork.canReach(player, Vec3.atCenterOf(relayDestination));
				}
			FieldRelayRuntime.Assignment currentRelayAssignment = FieldRelayRuntime.assignment(player, relayDrone);
				relayCommandObserved |= relayCommandIssued
					&& relayDrone.dataLinkStatus().startsWith("FIELD MESH /")
					&& currentRelayAssignment != null;
			if (currentRelayAssignment != null) {
				if (!currentRelayAssignment.slotId().equals(relayLoiterSlot)) {
					relayLoiterSlot = currentRelayAssignment.slotId();
					relayPreviousAngle = Double.NaN;
					relayAngularTravel = 0.0;
					relayLoiterSamples = 0;
				}
				Vec3 relayRadial = relayDrone.position().subtract(currentRelayAssignment.target()).multiply(1, 0, 1);
				if (FieldRelayFlightPolicy.established(relayDrone.position(), currentRelayAssignment.target())
					&& relayRadial.length() >= 4.0 && relayRadial.length() <= 10.5) {
					double relayAngle = Math.atan2(relayRadial.z, relayRadial.x);
					if (Double.isFinite(relayPreviousAngle)) relayAngularTravel += Math.abs(Mth.wrapDegrees(
						(float)Math.toDegrees(relayAngle - relayPreviousAngle))) * Math.PI / 180.0;
					relayPreviousAngle = relayAngle;
					relayLoiterSamples++;
					if (relaySolarCompletionObserved) {
						relayPostServiceLoiterSamples++;
						relayMeshRejoinObserved |= relayPostServiceLoiterSamples >= 20
							&& relayDrone.dataLinkStatus().startsWith("FIELD MESH /");
					}
				}
			}
			if (!relayServiceInjected && relayLoiterSamples >= 40) {
				relayServiceInjected = true;
				relayDrone.setPowerForVerification(340, 1000);
			}
			relaySolarObserved |= relayServiceInjected && relayDrone.solarServiceAssignedForVerification();
			relaySolarCompletionObserved |= relaySolarObserved
				&& !relayDrone.solarServiceAssignedForVerification();
			if (drone.dataLinkStatus().startsWith("SOLAR ")) solarAssignmentObserved = true;
			Vec3 radial = drone.position().subtract(station.position()).multiply(1, 0, 1);
			double radius = radial.length();
			double relativeHeight = drone.getY() - station.getY();
			if (solarAssignmentObserved && radius >= 4.0 && radius <= 6.8
				&& relativeHeight >= 0.7 && relativeHeight <= 2.8) {
				double angle = Math.atan2(radial.z, radial.x);
				if (Double.isFinite(previousAngle)) angularTravel += Math.abs(Mth.wrapDegrees(
					(float)Math.toDegrees(angle - previousAngle))) * Math.PI / 180.0;
				previousAngle = angle;
				stableOrbitSamples++;
			}
			boolean moved = drone.position().distanceTo(initialDronePosition) > 4.0;
			boolean orbiting = stableOrbitSamples >= 80 && angularTravel >= 0.75;
			boolean charging = drone.batteryPercent() > initialBattery;
			if (solarAssignmentObserved && !drone.solarServiceAssignedForVerification()
				&& drone.batteryPercent() >= drone.solarChargeTargetPercent()) {
				linkCompletionObserved = true;
				if (resumePosition == null) resumePosition = drone.position();
			}
			missionResumeObserved |= linkCompletionObserved && drone.mode() == DroneMode.WAYPOINT
				&& RESUME_MISSION.equals(drone.missionId()) && resumePosition != null
				&& drone.position().distanceTo(resumePosition) >= 2.0;
			boolean relayLoitering = relayLoiterSamples >= 30 && relayAngularTravel >= 0.25;
			if (moved && orbiting && charging && missionResumeObserved && relayExtensionObserved
				&& relayAircraftObserved && relayCommandObserved && relayLoitering
				&& relaySolarObserved && relayMeshRejoinObserved) {
				finish(true, "WAYPOINT>SOLAR DIVERT>CAPTURE>ORBIT>WAYPOINT RESUME / charge gained / samples="
						+ stableOrbitSamples + " / FIELD MESH RELAY EXTENDED / TERRAIN-PLANE LOITER "
						+ relayLoiterSamples + " samples / FIELD SOLAR>REJOIN "
						+ relayPostServiceLoiterSamples + " samples");
				return;
			}
			if (elapsed >= TIMEOUT_TICKS) {
				FieldRelayRuntime.Snapshot relaySnapshot = FieldRelayRuntime.snapshot(player);
				FieldRelayRuntime.Assignment relayAssignment = FieldRelayRuntime.assignment(player, relayDrone);
				finish(false, "timeout / assigned=" + solarAssignmentObserved
				+ " moved=" + moved + " samples=" + stableOrbitSamples + " travel="
				+ String.format("%.2f", angularTravel) + " charge=" + drone.batteryPercent()
				+ "% linkEnd=" + linkCompletionObserved + " resumed=" + missionResumeObserved
				+ " relay=" + relayExtensionObserved + " aircraft=" + relayAircraftObserved
				+ " command=" + relayCommandObserved
					+ " relayLoiter=" + relayLoiterSamples + "/" + String.format("%.2f", relayAngularTravel)
					+ " relaySolar=" + relaySolarObserved + "/" + relaySolarCompletionObserved
					+ " relayRejoin=" + relayMeshRejoinObserved
					+ " relayPostService=" + relayPostServiceLoiterSamples
				+ " eligible=" + relayDrone.fieldRelayAvailableForMesh()
				+ " relayAssigned=" + (relayAssignment != null)
				+ " playerPos=" + player.position()
				+ " meshOrigin=" + relaySnapshot.origin()
				+ " relayTarget=" + (relayAssignment == null ? "none" : relayAssignment.target())
				+ " relayDemand=" + (relayAssignment == null ? "none" : relayAssignment.demandId())
				+ " relayPos=" + relayDrone.position()
				+ " relayMotion=" + relayDrone.getDeltaMovement()
				+ " relayStatus=" + relayDrone.dataLinkStatus()
				+ " mode=" + drone.mode() + " mission=" + drone.missionId());
			}
		}

		private void finish(boolean pass, String detail) {
			complete = true;
			result = "SOLAR SERVICE " + (pass ? "PASS" : "FAIL") + " / " + detail;
			say(result);
			drone.discard();
			relayDrone.discard();
			station.discard();
		}

		private void say(String message) {
			String text = "[MORROWGEAR VERIFY] " + message;
			player.sendSystemMessage(Component.literal(text));
			MorrowgearDrone.LOGGER.info(text);
		}

		private boolean done() { return complete; }
		private String progress() { return complete ? result : "SOLAR SERVICE t="
			+ (level.getGameTime() - startedTick) + " / samples=" + stableOrbitSamples; }
	}

	private static final class SalvageVerificationSession {
		private static final long TIMEOUT_TICKS = 2200;
		private final ServerPlayer player;
		private final ServerLevel level;
		private final BlockPos base;
		private final BlockPos dockPos;
		private final DroneEntity carrier;
		private final DroneEntity load;
		private final double initialLoadY;
		private final long startedTick;
		private final Set<SalvageState> observed = EnumSet.noneOf(SalvageState.class);
		private SalvageState lastState;
		private boolean carrierDeparted;
		private boolean suspendedLoadObserved;
		private boolean inertialFallObserved;
		private boolean suspendedBeforeHook;
		private boolean returnedNearDock;
		private boolean returnCruiseObserved;
		private final boolean serviceScenario;
		private final boolean verticalScenario;
		private final boolean clearanceScenario;
		private final boolean protectedScenario;
		private final boolean lateEngineerScenario;
		private DroneEntity clearanceEngineer;
		private boolean clearanceObserved;
		private boolean verticalEscapeObserved;
		private boolean serviceInjected;
		private boolean serviceReturnObserved;
		private boolean serviceDockedObserved;
		private boolean serviceDockDiagnosticReported;
		private boolean serviceResumeObserved;
		private boolean loadRestoredObserved;
		private boolean loadMissionResumedObserved;
		private boolean complete;
		private String result = "SALVAGE RUNNING";

		private SalvageVerificationSession(ServerPlayer player) {
			this(player, false, false);
		}

		private SalvageVerificationSession(ServerPlayer player, boolean serviceScenario) {
			this(player, serviceScenario, false);
		}

		private SalvageVerificationSession(ServerPlayer player, boolean serviceScenario,
			boolean verticalScenario) {
			this(player, serviceScenario, verticalScenario, false);
		}

		private SalvageVerificationSession(ServerPlayer player, boolean serviceScenario,
			boolean verticalScenario, boolean clearanceScenario) {
			this(player, serviceScenario, verticalScenario, clearanceScenario, false);
		}

		private SalvageVerificationSession(ServerPlayer player, boolean serviceScenario,
			boolean verticalScenario, boolean clearanceScenario, boolean protectedScenario) {
			this(player, serviceScenario, verticalScenario, clearanceScenario, protectedScenario, false);
		}

		private SalvageVerificationSession(ServerPlayer player, boolean serviceScenario,
			boolean verticalScenario, boolean clearanceScenario, boolean protectedScenario, boolean lateEngineerScenario) {
			this.player = player;
			this.level = player.level();
			this.serviceScenario = serviceScenario;
			this.verticalScenario = verticalScenario;
			this.clearanceScenario = clearanceScenario;
			this.protectedScenario = protectedScenario;
			this.lateEngineerScenario = lateEngineerScenario;
			this.base = verificationBase(level, player.blockPosition().below().offset(14, 0, 0), 32);
			this.dockPos = base.offset(0, verticalScenario ? 12 : 1, 0);
			for (DroneEntity existing : level.getEntitiesOfClass(DroneEntity.class,
				new AABB(base).inflate(64), drone -> drone.isOwnedBy(player)
					&& (MorrowgearAutomation.requested()
						|| drone.groupId().startsWith("VERIFY-SALVAGE")))) existing.discard();
			prepareSite();
			placeDock();
			carrier = createDrone(dockPos.getX() + .5, dockPos.getY() + .29,
				dockPos.getZ() + .5, "VERIFY-SALVAGE-CARRIER", DroneRole.SALVAGE);
			carrier.assignDock(dockPos);
			carrier.setDockedForCommission();
			load = createDrone(base.getX() + (verticalScenario ? 7.5 : 18.5),
				base.getY() + (verticalScenario ? 5.15 : 7.15),
				base.getZ() + .5, "VERIFY-SALVAGE-LOAD", DroneRole.SCOUT);
			load.assignWaypoint(base.offset(24, 0, 0), "VERIFY-SALVAGE-RESUME", 1, 0,
				load.blockPosition(), level.getGameTime());
			load.capturePowerLossTaskForVerification();
			load.setPowerForVerification(0, 1000);
			if (clearanceScenario) {
				for (int x = 16; x <= 20; x++) for (int z = -2; z <= 2; z++)
					level.setBlockAndUpdate(base.offset(x, 10, z),
						(protectedScenario ? Blocks.BEDROCK : Blocks.STONE).defaultBlockState());
			}
			if ((clearanceScenario || verticalScenario) && !lateEngineerScenario) {
				clearanceEngineer = createDrone(base.getX() + 9.5, base.getY() + 14,
					base.getZ() + 5.5, "VERIFY-SALVAGE-ENGINEER", DroneRole.ENGINEER);
				clearanceEngineer.setMode(DroneMode.FOLLOW);
			}
			initialLoadY = load.getY();
			startedTick = level.getGameTime();
			lastState = carrier.salvageState();
			observed.add(lastState);
			say("SALVAGE START / 1 carrier / 1 POWER LOST load / "
				+ (verticalScenario ? "load below occluding platform" : "distance 18m")
				+ (serviceScenario ? " / service interruption" : ""));
			say("SALVAGE STATE " + lastState.name() + " / carrier docked");
			carrier.assignSalvageTargetForVerification(load);
		}

		private void prepareSite() {
			if (verticalScenario) {
				for (int x = -9; x <= 25; x++) for (int z = -11; z <= 11; z++) {
					level.setBlockAndUpdate(base.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
					for (int y = 1; y <= 17; y++) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
				}
				for (int x = -7; x <= 9; x++) for (int z = -8; z <= 8; z++) {
					level.setBlockAndUpdate(base.offset(x, 11, z), Blocks.SMOOTH_STONE.defaultBlockState());
				}
				return;
			}
			for (int x = -4; x <= 22; x++) for (int z = -6; z <= 6; z++) {
				level.setBlockAndUpdate(base.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
				// The automated world is intentionally reused between runs. Clear the full
				// extraction corridor so an old roof or carrier fixture cannot silently turn
				// the standard open-air case into an Engineer-clearance scenario.
				for (int y = 1; y <= 40; y++) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
			}
		}

		private void placeDock() {
			for (int index = 0; index < MorrowgearDrone.DOCK_PARTS.length; index++) {
				if (index == 4) continue;
				BlockPos part = dockPos.offset(index % 3 - 1, 0, index / 3 - 1);
				level.setBlockAndUpdate(part, MorrowgearDrone.DOCK_PARTS[index].defaultBlockState());
			}
			level.setBlockAndUpdate(dockPos, MorrowgearDrone.DOCK_PARTS[4].defaultBlockState());
			if (!(level.getBlockEntity(dockPos) instanceof DockBlockEntity dock)) {
				throw new IllegalStateException("salvage verifier Dock was not created");
			}
			dock.initialize(player);
			dock.setItem(DockBlockEntity.SLOT_RECOVERY_OUTPUT, ItemStack.EMPTY);
			dock.setItem(DockBlockEntity.SLOT_POWER_INPUT, new ItemStack(Items.CHARCOAL, 8));
			dock.setItem(DockBlockEntity.SLOT_REPAIR, new ItemStack(Items.IRON_INGOT, 8));
		}

		private DroneEntity createDrone(double x, double y, double z, String group, DroneRole role) {
			DroneEntity drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
			if (drone == null) throw new IllegalStateException("failed to create salvage verifier drone");
			drone.setPos(x, y, z);
			drone.initializeOwner(player);
			drone.assignGroup(group);
			drone.assignRole(role);
			drone.setPowerForVerification(1000, 1000);
			level.addFreshEntity(drone);
			return drone;
		}

		private void tick() {
			if (complete) return;
			if (!player.isAlive() || player.level() != level) {
				finish(false, "player left salvage verification dimension");
				return;
			}
			long elapsed = level.getGameTime() - startedTick;
			if (lateEngineerScenario && clearanceEngineer == null) {
				boolean intact = true;
				for (int x = 16; x <= 20; x++) for (int z = -2; z <= 2; z++)
					intact &= level.getBlockState(base.offset(x, 10, z)).is(Blocks.STONE);
				if (!intact || !load.isAlive() || !load.isPowerLost() || load.isNoGravity()
					|| carrier.salvageState() == SalvageState.HOOK) {
					finish(false, "extraction began before Engineer reinforcement");
					return;
				}
				if (elapsed >= 400) {
					clearanceEngineer = createDrone(base.getX() + 9.5, base.getY() + 14,
						base.getZ() + 5.5, "VERIFY-SALVAGE-ENGINEER", DroneRole.ENGINEER);
					clearanceEngineer.setMode(DroneMode.FOLLOW);
					say("SALVAGE ENGINEER REINFORCEMENT / corridor and load preserved for 400 ticks");
				}
			}
			if (protectedScenario) {
				boolean intact = true;
				for (int x = 16; x <= 20; x++) for (int z = -2; z <= 2; z++)
					intact &= level.getBlockState(base.offset(x, 10, z)).is(Blocks.BEDROCK);
				if (!intact || !load.isAlive() || !load.isPowerLost()
					|| carrier.salvageState() == SalvageState.HOOK || load.isNoGravity()) {
					finish(false, "protected extraction corridor was bypassed or load changed");
					return;
				}
				if (elapsed >= 400) {
					finish(carrier.isAlive() && clearanceEngineer != null && clearanceEngineer.isAlive(),
						"PROTECTED CORRIDOR / 25 bedrock intact / load preserved / no hook after 400 ticks");
					return;
				}
			}
			if (clearanceScenario && elapsed > 20) {
				boolean roofRemoved = true;
				for (int x = 16; x <= 20; x++) for (int z = -2; z <= 2; z++)
					roofRemoved &= level.getBlockState(base.offset(x, 10, z)).isAir();
				clearanceObserved |= roofRemoved && clearanceEngineer != null && clearanceEngineer.isAlive();
			}
			SalvageState state = carrier.salvageState();
			observed.add(state);
			if (state != lastState) {
				lastState = state;
				double distance = load.isAlive() ? carrier.distanceTo(load) : 0;
				say("SALVAGE STATE " + state.name() + " / t=" + elapsed
					+ " / separation=" + String.format(java.util.Locale.ROOT, "%.1f", distance) + "m");
			}
			carrierDeparted |= !carrier.isDocked() && carrier.position().distanceTo(Vec3.atCenterOf(dockPos)) > 3.0;
			if (verticalScenario && state == SalvageState.INTERCEPT) {
				// The rescue contract now opens the roof before descending, rather
				// than forcing the carrier to squeeze underneath the platform.
				verticalEscapeObserved |= SalvageClearance.obstruction(level, load) == null
					&& clearanceEngineer != null && clearanceEngineer.isAlive()
					&& carrier.getY() <= dockPos.getY() + 2.5;
			}
			if (serviceScenario && !serviceInjected && carrierDeparted
				&& state == SalvageState.INTERCEPT) {
				carrier.setPowerForVerification(50, 1000);
				serviceInjected = true;
				say("SALVAGE SERVICE INJECT / flight power 5%");
			}
			serviceReturnObserved |= carrier.serviceReturnActive();
			serviceDockedObserved |= serviceReturnObserved && carrier.isDocked();
			if (serviceScenario && serviceDockedObserved && !serviceDockDiagnosticReported) {
				serviceDockDiagnosticReported = true;
				DockBlockEntity dock = level.getBlockEntity(dockPos) instanceof DockBlockEntity found ? found : null;
				Vec3 center = Vec3.atCenterOf(dockPos);
				say("SALVAGE SERVICE DIAGNOSTIC / delta="
					+ String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f",
						carrier.getX() - center.x, carrier.getY() - center.y, carrier.getZ() - center.z)
					+ " / envelope=" + DockServicePolicy.serviceEnvelope(carrier.position(), center)
					+ " / dock=" + (dock != null)
					+ " / owner=" + (dock != null && dock.isOwnedBy(carrier.ownerId()))
					+ " / fuel=" + (dock == null ? -1 : dock.supplyCount(DockSupplyPolicy.SupplyKind.FUEL))
					+ " / stored=" + (dock == null ? -1 : dock.storedFlightPower()));
			}
			serviceResumeObserved |= serviceDockedObserved && !carrier.serviceReturnActive()
				&& !carrier.isDocked() && carrier.salvageState() == SalvageState.INTERCEPT;
			if (load.isAlive() && state == SalvageState.INTERCEPT) {
				inertialFallObserved |= load.getY() < initialLoadY - 0.45 && !load.isNoGravity();
				suspendedBeforeHook |= load.isNoGravity();
			}
			if (load.isAlive() && (state == SalvageState.HOIST || state == SalvageState.RETURN)) {
				double hookDistance = load.position().add(0, load.getBbHeight(), 0)
					.distanceTo(carrier.position().add(0, -SalvageTowPolicy.HOOK_DROP, 0));
				suspendedLoadObserved |= load.isNoGravity()
					&& hookDistance <= SalvageTowPolicy.ROPE_LENGTH + 0.9;
			}
			returnedNearDock |= state == SalvageState.RETURN
				&& SalvageTowPolicy.readyForDockTransfer(carrier.position(), Vec3.atCenterOf(dockPos));
			if (state == SalvageState.RETURN) {
				double horizontal = carrier.position().multiply(1, 0, 1)
					.distanceTo(Vec3.atCenterOf(dockPos).multiply(1, 0, 1));
				// The standard fixture has only an 18 m return leg. Verify purposeful powered
				// return here; long-distance cruise efficiency is covered by the vertical fixture.
				returnCruiseObserved |= horizontal >= 6.0 && carrier.getDeltaMovement().length() >= 0.20;
			}
			loadRestoredObserved |= load.isAlive() && !load.isPowerLost();
			loadMissionResumedObserved |= loadRestoredObserved && load.mode() == DroneMode.WAYPOINT
				&& "VERIFY-SALVAGE-RESUME".equals(load.missionId());
			if (loadRestoredObserved && carrier.salvageState() == SalvageState.IDLE) {
				boolean transitions = observed.containsAll(EnumSet.allOf(SalvageState.class));
				boolean finalState = carrier.salvageState() == SalvageState.IDLE
					&& carrier.mode() == DroneMode.DOCK
					&& (carrier.hasDock() || carrier.dockHolding());
				boolean servicePass = !serviceScenario || serviceInjected && serviceReturnObserved
					&& serviceDockedObserved && serviceResumeObserved;
				boolean verticalPass = !verticalScenario || verticalEscapeObserved;
				boolean engineerResumed = clearanceEngineer == null
					|| clearanceEngineer.isAlive() && clearanceEngineer.mode() == DroneMode.FOLLOW;
				if (transitions && carrierDeparted && inertialFallObserved && !suspendedBeforeHook
					&& suspendedLoadObserved && returnedNearDock && returnCruiseObserved
					&& load.isAlive() && loadRestoredObserved && loadMissionResumedObserved
					&& finalState && servicePass && verticalPass && engineerResumed
					&& (!clearanceScenario || clearanceObserved)) {
					finish(true, "POWER LOSS FALL>IDLE>INTERCEPT"
						+ (serviceScenario ? ">SERVICE>RESUME" : "")
						+ ">HOOK>HOIST>RETURN>DELIVER>SERVICE / entity mission resumed"
						+ (clearanceScenario ? " / ENGINEER CLEARANCE VERIFIED" : "")
						+ (clearanceEngineer == null ? "" : " / ENGINEER FOLLOW RESUMED"));
				} else {
					finish(false, "transition=" + transitions + " departed=" + carrierDeparted
						+ " fall=" + inertialFallObserved + " preHookSuspended=" + suspendedBeforeHook
						+ " suspended=" + suspendedLoadObserved + " returned=" + returnedNearDock
						+ " cruise=" + returnCruiseObserved
						+ " entity=" + load.isAlive() + "/" + loadRestoredObserved
						+ " resumed=" + loadMissionResumedObserved + " final=" + finalState
						+ "/mode=" + carrier.mode() + "/dock=" + carrier.hasDock()
						+ "/queue=" + carrier.dockHolding()
						+ " engineerResumed=" + engineerResumed
						+ " vertical=" + verticalPass + "/" + verticalEscapeObserved
						+ " service=" + servicePass + "/" + serviceReturnObserved
						+ "/" + serviceDockedObserved + "/" + serviceResumeObserved);
				}
				return;
			}
			if (elapsed >= TIMEOUT_TICKS) finish(false, "timeout / state=" + state.name()
				+ " / carrier=" + carrier.blockPosition().toShortString()
				+ " / load=" + load.blockPosition().toShortString());
		}

		private void finish(boolean pass, String detail) {
			complete = true;
			result = "SALVAGE " + (pass ? "PASS" : "FAIL") + " / " + detail;
			say(result);
			if (carrier.isAlive()) carrier.discard();
			if (load.isAlive()) load.discard();
			if (clearanceEngineer != null && clearanceEngineer.isAlive()) clearanceEngineer.discard();
			MorrowgearDrone.removeDock(level, dockPos, false);
		}

		private void say(String message) {
			String text = "[MORROWGEAR VERIFY] " + message;
			player.sendSystemMessage(Component.literal(text));
			MorrowgearDrone.LOGGER.info(text);
		}

		private boolean done() {
			return complete;
		}

		private String progress() {
			return complete ? result : "SALVAGE " + carrier.salvageState().name()
				+ " / t=" + (level.getGameTime() - startedTick);
		}
	}

	private static final class VisualEffectSession {
		private final ServerPlayer player;
		private final DroneEntity laser;
		private final DroneEntity gun;
		private final List<DroneEntity> additionalLasers = new ArrayList<>();
		private final LivingEntity target;
		private final long endTick;

		private VisualEffectSession(ServerPlayer player, CombatState state, int distance, int count) {
			this.player = player;
			ServerLevel level = player.level();
			Vec3 forward = player.getLookAngle().multiply(1, 0, 1);
			if (forward.lengthSqr() < 0.01) forward = new Vec3(0, 0, 1);
			forward = forward.normalize();
			Vec3 right = new Vec3(-forward.z, 0, forward.x);
			Vec3 targetPos = player.position().add(forward.scale(distance + 15.0));
			// A render probe must survive Peaceful mode and stay at the sampled aim point.
			ArmorStand stand = new ArmorStand(level, targetPos.x, player.getY(), targetPos.z);
			stand.setNoGravity(true);
			stand.setInvulnerable(true);
			level.addFreshEntity(stand);
			target = stand;
			laser = visualDrone(level, player, player.position().add(forward.scale(distance))
				.add(right.scale(-3.0)).add(0, 4.0, 0), "VERIFY-EFFECT-LASER");
			gun = visualDrone(level, player, player.position().add(forward.scale(distance))
				.add(right.scale(3.0)).add(0, 4.0, 0), "VERIFY-EFFECT-GUN");
			laser.assignSecurityLoadout(SecurityLoadout.LASER);
			gun.assignSecurityLoadout(SecurityLoadout.AUTOCANNON);
			laser.holdCombatVisualForVerification(state,
				CombatWeapon.LASER, target, 1200);
			for (int i = 1; i < count; i++) {
				DroneEntity extra = visualDrone(level, player,
					player.position().add(forward.scale(distance + (i / 4) * 3.0))
						.add(right.scale(-3.0 + (i % 4) * 2.0)).add(0, 4.0 + (i / 4) * 2.0, 0),
					"VERIFY-EFFECT-LASER-" + i);
				extra.assignSecurityLoadout(SecurityLoadout.LASER);
				extra.holdCombatVisualForVerification(state, CombatWeapon.LASER, target, 1200);
				additionalLasers.add(extra);
			}
			if (count > 1) gun.setPos(gun.getX(), gun.getY() + 6.0, gun.getZ());
			gun.holdCombatVisualForVerification(CombatState.GUN_RUN,
				CombatWeapon.AUTOCANNON, target, 1200);
			endTick = level.getGameTime() + 1200L;
		}

		private boolean tick() {
			if (player.isAlive() && player.level() == laser.level() && !player.hasDisconnected()
				&& target.isAlive() && player.level().getGameTime() < endTick) return false;
			cleanup();
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] EFFECTS COMPLETE / probes removed"));
			return true;
		}

		private void cleanup() {
			if (laser != null) laser.discard();
			if (gun != null) gun.discard();
			additionalLasers.forEach(DroneEntity::discard);
			if (target != null) target.discard();
		}

		private static DroneEntity visualDrone(ServerLevel level, ServerPlayer player,
			Vec3 position, String group) {
			DroneEntity drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
			if (drone == null) throw new IllegalStateException("failed to create visual probe");
			drone.setPos(position.x, position.y, position.z);
			drone.initializeOwner(player);
			drone.assignGroup(group);
			drone.assignRole(DroneRole.SECURITY);
			drone.setPowerForVerification(1000, 1000);
			level.addFreshEntity(drone);
			return drone;
		}
	}

	private static final class RuntimeSession {
		private final VerificationScope scope;
		private final ServerPlayer player;
		private final ServerLevel level;
		private final BlockPos base;
		private final List<DroneEntity> drones = new ArrayList<>();
		private final List<BlockPos> docks = new ArrayList<>();
		private final List<String> failures = new ArrayList<>();
		private final List<CaseStep> cases = new ArrayList<>();
		private int index;
		private long startedTick;
		private boolean startedCase;
		private int caseFailureStart;
		private boolean complete;
		private BlockPos cargoSource = BlockPos.ZERO;
		private BlockPos cargoTarget = BlockPos.ZERO;
		private LivingEntity trackingTarget;
		private int cargoConservationTotal;
		private BlockPos atomicDock = BlockPos.ZERO;
		private int atomicDockDropsBefore;
		private DroneEntity atomicDockProbe;
		private DroneEntity storageProbe;
		private int storedUnitCountBefore;
		private DroneEntity dockGuardLaunchProbe;
		private Vec3 dockGuardLaunchStart = Vec3.ZERO;
		private float dockGuardLaunchYaw;
		private final List<DroneEntity> rosterProbes = new ArrayList<>();
		private final List<BlockPos> specialContainers = new ArrayList<>();
		private int specialContainerTotal;
		private LivingEntity combatProbe;
		private final Set<CombatState> observedCombatStates = EnumSet.noneOf(CombatState.class);
		private final Set<CombatWeapon> observedCombatWeapons = EnumSet.noneOf(CombatWeapon.class);
		private boolean observedCombatShot;
		private int laserOrbitSamples;
		private double maxLaserOrbitSpeed;
		private int simultaneousLaserFire;
		private double bestLaserGapError = Double.POSITIVE_INFINITY;
		private int laserSpacingStableTicks;
		private int maxLaserSpacingStableTicks;
		private boolean laserCombatDropped;
		private final Set<String> laserFormationEngaged = new java.util.HashSet<>();
		private final Map<String, Double> laserFormationLastAngle = new HashMap<>();
		private final Map<String, Double> laserFormationOrbitTravel = new HashMap<>();
		private int airspaceSeparationTicks;
		private int maxAirspaceSeparationTicks;
		private double bestAirspaceMinimumDistance;
		private double bestAirspaceAltitudeGap;
		private final Set<String> observedAirspaceGroups = new java.util.HashSet<>();
		private boolean triggerInterceptObserved;
		private boolean triggerCombatObserved;
		private boolean triggerCorrectTargetObserved;
		private int maximumHighThreatResponders;
		private final Set<UUID> highThreatExpectedLaserUnits = new HashSet<>();
		private final Set<UUID> highThreatFiringLaserUnits = new HashSet<>();
		private int casImpactBlocksBefore;
		private int casImpactBlocksAfter;
		private int casInitialFlightPower;
		private int casInitialWeaponPower;
		private int casInitialAuxiliaryPower;
		private int casMinimumFlightPower;
		private int casMinimumWeaponPower;
		private int casMinimumAuxiliaryPower;
		private double casMaximumRange;
		private double casMinimumHeight;
		private double casMaximumHeight;
		private long casLastObservedShotTick;
		private int casObservedShots;
		private boolean casRearwardShotObserved;
		private final Set<String> adaptiveLaserFiringGroups = new HashSet<>();
		private final Set<UUID> adaptiveExpectedLaserUnits = new HashSet<>();
		private final Set<UUID> adaptiveLaserFiringUnits = new HashSet<>();
		private boolean adaptiveCrossingGunShotObserved;
		private boolean adaptiveCrossingTrafficObserved;
		private LivingEntity adaptiveLaserDecoy;
		private int adaptiveLaserLockedTargetId = -1;
		private boolean adaptiveLaserTargetStable;
		private int adaptiveLaserStableTicks;
		private int adaptiveLaserMaximumStableTicks;
		private final Map<UUID, Double> adaptiveLaserRelativeHeights = new HashMap<>();
		private double adaptiveLaserMaximumHeightStep;
		private int adaptiveLaserEmissionStableTicks;
		private int adaptiveLaserMaximumEmissionStableTicks;
		private final Map<String, Integer> adaptiveLaserGroupEmissionTicks = new HashMap<>();
		private final Map<String, Integer> adaptiveLaserGroupMaximumEmissionTicks = new HashMap<>();
		private boolean adaptiveRouteInterceptObserved;
		private boolean adaptiveRouteResumeObserved;
		private boolean adaptiveRouteTargetRemoved;
		private DroneEntity adaptiveRechargeOriginal;
		private final Set<UUID> adaptiveInitialResponders = new HashSet<>();
		private boolean adaptiveRechargeRtbObserved;
		private boolean adaptiveReplacementObserved;
		private boolean adaptiveOriginalMissionResumed;
		private boolean adaptiveReliefChurnObserved;
		private boolean adaptivePowerDepleted;
		private boolean adaptiveGunShotObserved;
		private double adaptiveGunMaximumAimOffset;
		private double adaptiveGunMaximumSpacing;
		private double adaptiveGunMinimumForwardDot;
		private final Map<UUID, Double> adaptiveGunPreviousSpeed = new HashMap<>();
		private double adaptiveGunMaximumSpeedStep;
		private int adaptiveGunSpeedSamples;
		private DroneEntity adaptiveHeatAircraft;
		private boolean adaptiveHeatCooledObserved;
		private boolean adaptiveHeatPatrolMaintained;
		private boolean adaptiveHeatCombatObserved;
		private final Map<UUID, Vec3> remoteOperationOrigins = new HashMap<>();
		private final Map<UUID, Double> waypointInitialDistances = new HashMap<>();
		private Vec3 waypointVerificationDestination;
		private final List<DroneEntity> remoteOperationDrones = new ArrayList<>();
		private boolean remoteOperationMotionObserved;
		private boolean remoteOperationCombatObserved;
		private boolean forestryCutBeforeScanComplete;
		private boolean forestryLowerBlockRemovedBeforeUpper;
		private final Set<UUID> firingRouteShotUnits = new HashSet<>();
		private final Set<UUID> firingRouteAlternativeUnits = new HashSet<>();
		private final Set<CombatWeapon> firingRouteObservedWeapons = EnumSet.noneOf(CombatWeapon.class);
		private boolean firingRouteSuppressionObserved;
		private float firingRouteInitialTargetHealth;
		private DroneEntity supplyContinuityProbe;
		private boolean supplyImmediateRedispatchObserved;
		private boolean supplyIdleReturnObserved;
		private boolean supplyLoadedCommandAttempted;
		private boolean supplyLoadedCommandRejectedObserved;
		private LivingEntity operatorAttackProbe;
		private DroneEntity fieldRelayObstacleProbe;
		private String fieldRelayObstacleSlot = "";
		private Vec3 fieldRelayObstacleTarget;
		private final List<BlockPos> fieldRelayObstacleBlocks = new ArrayList<>();

		RuntimeSession(ServerPlayer player) {
			this(player, VerificationScope.ALL);
		}

		RuntimeSession(ServerPlayer player, boolean navigationOnly) {
			this(player, navigationOnly ? VerificationScope.NAVIGATION : VerificationScope.ALL);
		}

		RuntimeSession(ServerPlayer player, VerificationScope scope) {
			this.scope = scope;
			this.player = player;
			this.level = player.level();
			BlockPos requested = scope == VerificationScope.ADAPTIVE_COMBAT || scope == VerificationScope.FIRING_ROUTES
				? player.blockPosition().below().offset(72, 0, 0)
				: player.blockPosition().below();
			this.base = verificationBase(level, requested, 48);
			buildCases();
			if (scope != VerificationScope.ALL) cases.removeIf(step -> !step.includedIn(scope));
		}

		boolean done() {
			return complete;
		}

		int caseCount() {
			return cases.size();
		}

		String progress() {
			return index + "/" + cases.size() + " failures=" + failures.size();
		}

		void tick() {
			observeCombat();
			observeSupplyContinuity();
			observeForestryOrder();
			if (complete || index >= cases.size()) {
				finish();
				return;
			}
			CaseStep step = cases.get(index);
			if (!startedCase) {
				startedCase = true;
				caseFailureStart = failures.size();
				startedTick = level.getGameTime();
				say(String.format("CASE %02d/%02d %s", index + 1, cases.size(), step.name));
				try {
					step.start.run();
				} catch (RuntimeException error) {
					fail(step.name + " threw on start: " + error.getMessage());
					if (step.name.equals("sandbox setup")) {
						finish();
						return;
					}
					next();
					return;
				}
			}
			long elapsed = level.getGameTime() - startedTick;
			if (elapsed % 100L == 0L) logNavigationDiagnostics(step.name, elapsed);
			if (step.ready == null && elapsed < step.waitTicks) return;
			if (step.ready != null && !step.ready.getAsBoolean() && elapsed < step.waitTicks) return;
			try {
				step.assertion.run();
			} catch (RuntimeException error) {
				fail(step.name + " threw on assert: " + error.getMessage());
			}
			if (failures.size() == caseFailureStart) say("PASS " + step.name);
			else say("FAIL " + failures.getLast());
			if (step.name.equals("sandbox setup") && failures.size() > caseFailureStart) {
				finish();
				return;
			}
			next();
		}

		private void observeForestryOrder() {
			if (index < 0 || index >= cases.size()
				|| !cases.get(index).name().startsWith("engineer solo forestry")) return;
			DroneEntity engineer = drones.size() > 7 ? drones.get(7) : null;
			if (engineer == null) return;
			if (engineer.fieldOperationState() == FieldOperationState.WORKING
				&& engineer.fieldProgress() < 100) forestryCutBeforeScanComplete = true;
			BlockPos anchor = fieldAnchor(FieldOperationType.FORESTRY);
			boolean upperRemains = false;
			for (int y = 1; y <= 4; y++) if (level.getBlockState(anchor.above(y)).is(BlockTags.LOGS)
				|| level.getBlockState(anchor.above(y)).is(BlockTags.LEAVES)) upperRemains = true;
			if (upperRemains && !level.getBlockState(anchor).is(BlockTags.LOGS))
				forestryLowerBlockRemovedBeforeUpper = true;
		}

		private void next() {
			index++;
			startedCase = false;
			if (index >= cases.size()) finish();
		}

		private void finish() {
			if (complete) return;
			complete = true;
			String result = failures.isEmpty()
				? "COMPLETE PASS " + cases.size() + "/" + cases.size()
				: "COMPLETE FAIL " + failures.size() + " issue(s) / cases " + cases.size();
			say(result);
			MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] {}", result);
			for (String failure : failures) {
				say("ISSUE " + failure);
				MorrowgearDrone.LOGGER.warn("[MORROWGEAR VERIFY] {}", failure);
			}
			if (scope == VerificationScope.ADAPTIVE_COMBAT || scope == VerificationScope.BASE_LASER
				|| scope == VerificationScope.FIRING_ROUTES) {
				if (combatProbe != null && combatProbe.isAlive()) combatProbe.discard();
				cleanupVerifierDrones();
			}
		}

		private void buildCases() {
			cases.add(new CaseStep("sandbox setup", 5, this::setupSandbox, () -> {
				int expectedDrones = scope == VerificationScope.ADAPTIVE_COMBAT
					|| scope == VerificationScope.FIRING_ROUTES ? 6
					: scope == VerificationScope.BASE_LASER || scope == VerificationScope.SNOWFIELD ? 8 : 24;
				int expectedDocks = scope == VerificationScope.ADAPTIVE_COMBAT
					|| scope == VerificationScope.BASE_LASER
					|| scope == VerificationScope.FIRING_ROUTES
					|| scope == VerificationScope.SNOWFIELD ? 1 : 4;
				require(drones.size() == expectedDrones,
					"expected " + expectedDrones + " verifier drones, got " + drones.size());
				require(docks.size() == expectedDocks,
					"expected " + expectedDocks + " verifier docks, got " + docks.size());
				require(level.getBlockEntity(cargoSource) instanceof Container, "cargo source chest missing");
				require(level.getBlockEntity(cargoTarget) instanceof Container, "cargo target chest missing");
			}).scopedTo(VerificationScope.NAVIGATION, VerificationScope.FIELD,
				VerificationScope.SNOWFIELD,
				VerificationScope.COMBAT, VerificationScope.COMBAT_CAS,
				VerificationScope.ADAPTIVE_COMBAT, VerificationScope.BASE_LASER,
				VerificationScope.FIRING_ROUTES,
				VerificationScope.REGRESSION));
			cases.add(new CaseStep("remote supply source and continuous cargo dispatch", 1000,
				this::assignContinuousSupply, this::assertContinuousSupply,
				this::continuousSupplyComplete).scopedTo(VerificationScope.REGRESSION));
			cases.add(new CaseStep("operator target lock and forced combat override", 500,
				this::assignOperatorTarget, this::assertOperatorTargetOverride,
				() -> drones.getFirst().combatState() == CombatState.LASER_CHARGE
					|| drones.getFirst().combatState() == CombatState.LASER_FIRE)
				.scopedTo(VerificationScope.REGRESSION));
			cases.add(new CaseStep("identity and ownership", 1, () -> {}, this::assertIdentity));
			cases.add(new CaseStep("dock subsystem maintenance", 140,
				this::assignDockSubsystemMaintenance, this::assertDockSubsystemMaintenance));
			cases.add(new CaseStep("role mission compatibility matrix", 1, () -> {}, this::assertCompatibilityMatrix));
			cases.add(new CaseStep("state boundary normalization", 1, this::assignBoundaryStates,
				this::assertBoundaryStates));
			cases.add(new CaseStep("manual wing capacity normalization", 1, this::assignOversizedWing,
				this::assertWingCapacity));
			cases.add(new CaseStep("follow formation sizes", 80, this::assignFollowMatrix,
				() -> assertMission("follow", DroneMode.FOLLOW)));
			cases.add(new CaseStep("waypoint formation sizes", 240, this::assignWaypointMatrix,
				this::assertWaypointMission).scopedTo(VerificationScope.NAVIGATION));
			cases.add(new CaseStep("mission roster shrink and stable reindex", 140, this::assignRosterShrink,
				this::assertRosterShrink));
			cases.add(new CaseStep("tracking target assignment", 100, this::assignTrackingMission,
				this::assertTrackingMission));
			cases.add(new CaseStep("tracking target loss fallback", 100, this::removeTrackingTarget,
				this::assertTrackingFallback));
			cases.add(new CaseStep("security patrol and threat contacts", 140, this::assignSecurityPatrol,
				this::assertSecurityPatrol));
			cases.add(new CaseStep("mixed security weapon engagement", 600,
				this::assignMixedCombat, this::assertMixedCombat).scopedTo(VerificationScope.COMBAT));
			cases.add(new CaseStep("laser temporary formation stability", 360,
				this::assignLaserFormation, this::assertLaserFormation).scopedTo(VerificationScope.COMBAT));
			cases.add(new CaseStep("multi element combat airspace separation", 440,
				this::assignCombatAirspace, this::assertCombatAirspace).scopedTo(VerificationScope.COMBAT));
			cases.add(new CaseStep("player danger interception trigger", 180,
				this::assignPlayerDangerTrigger, () -> assertTriggerCase("player danger", true))
				.scopedTo(VerificationScope.COMBAT));
			cases.add(new CaseStep("scout data link interception trigger", 260,
				this::assignScoutThreatTrigger, () -> assertTriggerCase("scout data link", true))
				.scopedTo(VerificationScope.COMBAT));
			cases.add(new CaseStep("field guard local combat trigger", 200,
				this::assignFieldGuardTrigger, () -> assertTriggerCase("field guard", false))
				.scopedTo(VerificationScope.COMBAT, VerificationScope.REGRESSION));
			cases.add(new CaseStep("high power enemy dynamic reinforcement", 520,
				this::assignHighThreatReinforcement, this::assertHighThreatReinforcement)
				.scopedTo(VerificationScope.COMBAT, VerificationScope.BASE_LASER));
			cases.add(new CaseStep("linear autocannon strike and split power", 620,
				this::assignLinearAutocannonStrike, this::assertLinearAutocannonStrike)
				.scopedTo(VerificationScope.COMBAT, VerificationScope.COMBAT_CAS));
			cases.add(new CaseStep("cave laser acquires an individual firing aperture", 900,
				this::assignCaveLaserRoute, this::assertCaveLaserRoute,
				() -> firingRouteAlternativeUnits.size() >= 2 && firingRouteShotUnits.size() >= 2 && combatProbe != null
					&& combatProbe.getHealth() < firingRouteInitialTargetHealth)
				.scopedTo(VerificationScope.FIRING_ROUTES));
			cases.add(new CaseStep("cave autocannon uses a forward alternate ingress", 1100,
				this::assignCaveGunRoute, this::assertCaveGunRoute,
				() -> !firingRouteAlternativeUnits.isEmpty() && !firingRouteShotUnits.isEmpty()
					&& combatProbe != null && combatProbe.getHealth() < firingRouteInitialTargetHealth)
				.scopedTo(VerificationScope.FIRING_ROUTES));
			cases.add(new CaseStep("shaft laser engages vertically without clipping", 900,
				this::assignShaftLaserRoute, this::assertShaftLaserRoute,
				() -> !firingRouteShotUnits.isEmpty() && combatProbe != null
					&& combatProbe.getHealth() < firingRouteInitialTargetHealth)
				.scopedTo(VerificationScope.FIRING_ROUTES));
			cases.add(new CaseStep("sealed cave terminates missile search and replans", 500,
				this::assignSealedMissileRoute, this::assertSealedMissileRoute,
				() -> firingRouteSuppressionObserved).scopedTo(VerificationScope.FIRING_ROUTES));
			cases.add(new CaseStep("multi-Wing wall routes remain independent", 1200,
				this::assignMultiWingWallRoutes, this::assertMultiWingWallRoutes,
				() -> firingRouteAlternativeUnits.size() >= 4 && firingRouteShotUnits.size() >= 4)
				.scopedTo(VerificationScope.FIRING_ROUTES));
			cases.add(new CaseStep("adaptive multi-group laser reservation", 360,
				this::assignAdaptiveLaserGroups, this::assertAdaptiveLaserGroups)
				.scopedTo(VerificationScope.ADAPTIVE_COMBAT));
			cases.add(new CaseStep("adaptive route intercept and resume", 360,
				this::assignAdaptiveRouteIntercept, this::assertAdaptiveRouteIntercept)
				.scopedTo(VerificationScope.ADAPTIVE_COMBAT));
			cases.add(new CaseStep("adaptive weapon recharge relief rotation", 1400,
				this::assignAdaptiveRechargeRotation, this::assertAdaptiveRechargeRotation,
				() -> adaptiveRechargeRtbObserved && adaptiveReplacementObserved
					&& adaptiveOriginalMissionResumed
					&& !adaptiveRechargeOriginal.reliefMissionInheritedFrom().isBlank())
				.scopedTo(VerificationScope.ADAPTIVE_COMBAT));
			cases.add(new CaseStep("adaptive laser field cooling and reengagement", 700,
				this::assignAdaptiveHeatService, this::assertAdaptiveHeatService,
					() -> adaptiveHeatCooledObserved && adaptiveHeatCombatObserved)
				.scopedTo(VerificationScope.ADAPTIVE_COMBAT));
			cases.add(new CaseStep("adaptive autocannon lane and aim", 760,
				this::assignAdaptiveAutocannon, this::assertAdaptiveAutocannon,
				() -> adaptiveGunShotObserved && adaptiveGunMaximumSpacing >= 2.0
					&& adaptiveGunSpeedSamples >= 20).scopedTo(VerificationScope.ADAPTIVE_COMBAT));
			cases.add(new CaseStep("remote owner-distance mission continuity", 420,
				this::assignRemoteOperation, this::assertRemoteOperation,
					() -> remoteOperationMotionObserved && remoteOperationCombatObserved)
				.scopedTo(VerificationScope.ADAPTIVE_COMBAT, VerificationScope.REGRESSION));
			cases.add(new CaseStep("scout solo ore scan", 180, () -> assignScoutSolo(FieldOperationType.ORE, 6),
				() -> assertScoutSolo(FieldOperationType.ORE, 6)));
			cases.add(new CaseStep("scout solo forestry scan", 180, () -> assignScoutSolo(FieldOperationType.FORESTRY, 7),
				() -> assertScoutSolo(FieldOperationType.FORESTRY, 7)));
			cases.add(new CaseStep("engineer solo forestry overhead scan and top-down cut", 900,
				this::assignEngineerSoloForestry, this::assertEngineerSoloForestry,
				this::engineerSoloForestryComplete).scopedTo(VerificationScope.FIELD));
			cases.add(new CaseStep("engineer sparse snowfield ore", 900,
				this::assignEngineerSnowfieldOre, this::assertEngineerSnowfieldOre,
				this::engineerSnowfieldOreComplete).scopedTo(VerificationScope.FIELD, VerificationScope.SNOWFIELD));
			cases.add(new CaseStep("cargo route and container arbitration", 520, this::assignCargoRoute,
				this::assertCargoRoute));
			cases.add(new CaseStep("cargo full target item conservation", 620, this::assignCargoFullTarget,
				this::assertCargoConservation));
			cases.add(new CaseStep("cargo special container interoperability", 620, this::assignSpecialContainerCargo,
				this::assertSpecialContainerCargo));
			cases.add(new CaseStep("mission interruption and reassignment", 20, this::assignMissionInterruptions,
				this::assertMissionInterruptions));
			cases.add(new CaseStep("rapid command supersession", 20, this::assignRapidCommandSupersession,
				this::assertRapidCommandSupersession));
			cases.add(new CaseStep("manual wing route cohort isolation", 40,
				this::assignWingRouteIsolation, this::assertWingRouteIsolation)
				.scopedTo(VerificationScope.NAVIGATION));
			cases.add(new CaseStep("field ore scout engineer cargo", 1200,
				() -> assignFieldOperation(FieldOperationType.ORE),
				() -> assertFieldOperation(FieldOperationType.ORE), () -> fieldTeamReady(0, 4))
				.scopedTo(VerificationScope.FIELD));
			cases.add(new CaseStep("field excavation", 3000,
				() -> assignFieldOperation(FieldOperationType.EXCAVATE),
				() -> assertFieldOperation(FieldOperationType.EXCAVATE), () -> fieldTeamReady(0, 4))
				.scopedTo(VerificationScope.FIELD));
			cases.add(new CaseStep("field forestry and replant", 1400,
				() -> assignFieldOperation(FieldOperationType.FORESTRY),
				() -> assertFieldOperation(FieldOperationType.FORESTRY), () -> fieldTeamReady(0, 4))
				.scopedTo(VerificationScope.FIELD));
			cases.add(new CaseStep("engineer repair support", 180, this::assignEngineerRepair,
				this::assertEngineerRepair));
			cases.add(new CaseStep("parallel composite scout engineer cargo operations", 2400,
				this::assignParallelCompositeOperations, this::assertParallelCompositeOperations,
				() -> fieldTeamReady(0, 9)).scopedTo(VerificationScope.FIELD));
			cases.add(new CaseStep("ui terminal data snapshot", 180,
				this::assignUiTerminalSnapshot, this::assertUiTerminalSnapshot));
			cases.add(new CaseStep("dock return and landing", 600, this::assignDockReturn,
				this::assertDockReturn));
			cases.add(new CaseStep("guard direct launch from dock", 120,
				this::assignDockGuardLaunch, this::assertDockGuardLaunch));
			cases.add(new CaseStep("dock return with roof and blocked approaches", 900,
				this::assignObstructedDockReturn, this::assertObstructedDockReturn)
				.scopedTo(VerificationScope.NAVIGATION));
			cases.add(new CaseStep("regroup and formation obstacle reroute", 1200,
				this::assignObstructedRegroup, this::assertObstructedRegroup, this::obstructedRegroupReady)
				.scopedTo(VerificationScope.NAVIGATION, VerificationScope.REGRESSION));
			cases.add(new CaseStep("large obstacle strategic reroute", 1200,
				this::assignLargeObstacleRoute, this::assertLargeObstacleRoute)
				.scopedTo(VerificationScope.NAVIGATION));
			cases.add(new CaseStep("field relay obstacle reroute and slot retention", 1200,
				this::assignFieldRelayObstacleRoute, this::assertFieldRelayObstacleRoute,
				this::fieldRelayObstacleRouteReady).scopedTo(VerificationScope.NAVIGATION));
			cases.add(new CaseStep("field work obstacle reroute", 700,
				this::assignObstructedFieldWork, this::assertObstructedFieldWork)
				.scopedTo(VerificationScope.NAVIGATION));
			cases.add(new CaseStep("dock atomic removal", 5, this::removeAtomicDock,
				this::assertAtomicDockRemoval));
			cases.add(new CaseStep("storage returns drone unit", 5, this::storeTemporaryDrone,
				this::assertStoredDroneReturn));
			cases.add(new CaseStep("final fleet invariants", 1, () -> {}, this::assertFleetInvariants));
		}

		private void assignBoundaryStates() {
			DroneEntity drone = drones.getFirst();
			reset(drone);
			drone.assignFollowFormation("verify-boundary", 1000, 999, drone.unitId(), base, level.getGameTime());
			DroneEntity field = drones.get(1);
			reset(field);
			field.assignRole(DroneRole.ENGINEER);
			field.assignFieldOperation(FieldOperationType.EXCAVATE, base, 999, "verify-boundary-field");
		}

		private void assertBoundaryStates() {
			DroneEntity drone = drones.getFirst();
			require(drone.missionExpected() == DroneCommandPolicy.MAX_MISSION_SIZE,
				"mission expected not normalized: " + drone.missionExpected());
			require(drone.missionIndex() == DroneCommandPolicy.MAX_MISSION_SIZE - 1,
				"mission index not normalized: " + drone.missionIndex());
			require(drones.get(1).fieldRadius() == 8, "field radius not normalized: " + drones.get(1).fieldRadius());
		}

		private void setupSandbox() {
			cleanupVerifierDrones();
			List<DroneEntity> foreignDrones = level.getEntitiesOfClass(DroneEntity.class,
				new AABB(base).inflate(160.0), drone -> drone.isOwnedBy(player)
					&& !isVerifierGroup(drone.groupId()));
			if (scope == VerificationScope.BASE_LASER) {
				foreignDrones.forEach(DroneEntity::discard);
				foreignDrones = List.of();
			}
			if (MorrowgearAutomation.requested()) {
				// The automation world is disposable and may retain an aircraft if a previous JVM
				// was interrupted. Remove only this test player's nearby units before isolation checks.
				foreignDrones.forEach(DroneEntity::discard);
				foreignDrones = List.of();
			}
			if (!foreignDrones.isEmpty()) {
				throw new IllegalStateException("test airspace is not isolated: "
					+ foreignDrones.size() + " non-verifier owned drone(s) within 160 blocks");
			}
			FieldOperationRegistry.clear();
			CombatManeuverRegistry.clear();
			clearHostiles(48);
			preparePlatform(base);
			cargoSource = base.offset(-8, 1, -8);
			cargoTarget = base.offset(-8, 1, -2);
			placeChest(cargoSource, new ItemStack(Items.COBBLESTONE, 64));
			placeChest(cargoTarget, ItemStack.EMPTY);
			int dockCount = scope == VerificationScope.ADAPTIVE_COMBAT
				|| scope == VerificationScope.BASE_LASER
				|| scope == VerificationScope.FIRING_ROUTES
				|| scope == VerificationScope.SNOWFIELD ? 1 : 4;
			for (int i = 0; i < dockCount; i++) {
				BlockPos dock = base.offset(-12 + i * 5, 1, 8);
				placeDock(dock);
				docks.add(dock);
			}
			int droneCount = scope == VerificationScope.ADAPTIVE_COMBAT
				|| scope == VerificationScope.FIRING_ROUTES ? 6
				: scope == VerificationScope.BASE_LASER || scope == VerificationScope.SNOWFIELD ? 8 : 24;
			for (int i = 0; i < droneCount; i++) {
				DroneEntity drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
				if (drone == null) continue;
				double x = base.getX() + 2 + (i % 8) * 2.2;
				double y = base.getY() + 4 + (i / 8) * 1.3;
				double z = base.getZ() - 4 - (i / 8) * 2.4;
				drone.setPos(x, y, z);
				drone.initializeOwner(player);
				drone.assignGroup("VERIFY");
				drone.assignRole(DroneRole.values()[i % DroneRole.values().length]);
				level.addFreshEntity(drone);
				drones.add(drone);
			}
			player.getInventory().add(new ItemStack(Items.COPPER_INGOT, 64));
			player.getInventory().add(new ItemStack(Items.OAK_SAPLING, 16));
			prepareFieldBlocks();
		}

		private DroneEntity spawnVerifierDrone(int index) {
			DroneEntity drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
			if (drone == null) throw new IllegalStateException("failed to create verifier drone");
			double x = base.getX() + 2 + (index % 8) * 2.2;
			double y = base.getY() + 4 + (index / 8) * 1.3;
			double z = base.getZ() - 4 - (index / 8) * 2.4;
			drone.setPos(x, y, z);
			drone.initializeOwner(player);
			drone.assignGroup("VERIFY");
			drone.assignRole(DroneRole.values()[index % DroneRole.values().length]);
			level.addFreshEntity(drone);
			drones.add(drone);
			return drone;
		}

		private void assertIdentity() {
			Set<String> unitIds = new HashSet<>();
			for (DroneEntity drone : aliveDrones()) {
				require(drone.isOwnedBy(player), drone.unitId() + " owner mismatch");
				require(unitIds.add(drone.unitId()), "duplicate unit id " + drone.unitId());
				require(drone.batteryPercent() == 100, drone.unitId() + " battery not full");
			}
		}

		private void assignContinuousSupply() {
			BlockPos remoteSource = base.offset(52, 1, -10);
			preparePlatform(remoteSource.offset(-2, -1, 0));
			placeChest(remoteSource, new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 2));
			List<SupplyNetworkPolicy.Rule> rules = java.util.Arrays.stream(DockSupplyPolicy.SupplyKind.values())
				.map(kind -> new SupplyNetworkPolicy.Rule(kind,
					kind == DockSupplyPolicy.SupplyKind.GUN ? 1 : 0,
					kind == DockSupplyPolicy.SupplyKind.GUN ? 100 : 0)).toList();
			SupplyNetworkRuntime.configure(player, remoteSource, docks.get(0), rules, true);
			SupplyNetworkRuntime.configure(player, remoteSource, docks.get(1), rules, true);
			supplyContinuityProbe = drones.get(2);
			reset(supplyContinuityProbe);
			supplyContinuityProbe.assignRole(DroneRole.CARGO);
			// The generic verifier reset places aircraft in a protected test group.
			// ALPHA is the unreserved default fleet pool used by autonomous logistics.
			supplyContinuityProbe.assignGroup("ALPHA");
			supplyContinuityProbe.setPos(Vec3.atCenterOf(remoteSource).add(0, 4, 0));
			supplyImmediateRedispatchObserved = false;
			supplyIdleReturnObserved = false;
			supplyLoadedCommandAttempted = false;
			supplyLoadedCommandRejectedObserved = false;
		}

		private void observeSupplyContinuity() {
			if (supplyContinuityProbe == null || !supplyContinuityProbe.isAlive() || docks.size() < 2) return;
			if (!supplyLoadedCommandAttempted && supplyContinuityProbe.supplyNetworkAssignedForVerification()
				&& supplyContinuityProbe.cargoItemCount() > 0) {
				supplyLoadedCommandAttempted = true;
				String mission = supplyContinuityProbe.missionId();
				BlockPos waypoint = supplyContinuityProbe.waypointPos();
				DroneMode mode = supplyContinuityProbe.mode();
				int cargo = supplyContinuityProbe.cargoItemCount();
				supplyContinuityProbe.assignWaypoint(base.offset(-24, 1, -24),
					"verify-illegal-loaded-cargo-preemption", 1, 0, base, level.getGameTime());
				supplyLoadedCommandRejectedObserved = mission.equals(supplyContinuityProbe.missionId())
					&& waypoint.equals(supplyContinuityProbe.waypointPos())
					&& mode == supplyContinuityProbe.mode()
					&& cargo == supplyContinuityProbe.cargoItemCount()
					&& supplyContinuityProbe.dataLinkStatus().startsWith("COMMAND REJECTED / SUPPLY LOAD SAFETY");
			}
			int supplied = 0;
			for (int i = 0; i < 2; i++) if (level.getBlockEntity(docks.get(i)) instanceof DockBlockEntity dock
				&& dock.supplyCount(DockSupplyPolicy.SupplyKind.GUN) > 0) supplied++;
			if (supplied == 1 && supplyContinuityProbe.supplyNetworkAssignedForVerification())
				supplyImmediateRedispatchObserved = true;
			if (supplied == 2 && !supplyContinuityProbe.supplyNetworkAssignedForVerification()
				&& (supplyContinuityProbe.mode() == DroneMode.DOCK || supplyContinuityProbe.isDocked()
					|| supplyContinuityProbe.dockHolding())) supplyIdleReturnObserved = true;
		}

		private boolean continuousSupplyComplete() {
			if (docks.size() < 2) return false;
			return level.getBlockEntity(docks.get(0)) instanceof DockBlockEntity first
				&& level.getBlockEntity(docks.get(1)) instanceof DockBlockEntity second
				&& first.supplyCount(DockSupplyPolicy.SupplyKind.GUN) > 0
				&& second.supplyCount(DockSupplyPolicy.SupplyKind.GUN) > 0;
		}

		private void assertContinuousSupply() {
			int first = level.getBlockEntity(docks.get(0)) instanceof DockBlockEntity dock
				? dock.supplyCount(DockSupplyPolicy.SupplyKind.GUN) : -1;
			int second = level.getBlockEntity(docks.get(1)) instanceof DockBlockEntity dock
				? dock.supplyCount(DockSupplyPolicy.SupplyKind.GUN) : -1;
			require(continuousSupplyComplete(), "remote source did not supply both Docks"
				+ " / first=" + first + " second=" + second
				+ " cargo=" + (supplyContinuityProbe == null ? "missing"
					: supplyContinuityProbe.cargoState() + "/" + supplyContinuityProbe.mode()
						+ "/assigned=" + supplyContinuityProbe.supplyNetworkAssignedForVerification()));
			require(supplyImmediateRedispatchObserved,
				"cargo aircraft entered idle between consecutive network deliveries");
			require(supplyLoadedCommandAttempted,
				"loaded supply cargo was not observed during continuous dispatch");
			require(supplyLoadedCommandRejectedObserved,
				"loaded supply cargo accepted a conflicting flight command or lost custody state");
			require(supplyIdleReturnObserved,
				"cargo aircraft did not enter dynamic Dock return after final delivery");
		}

		private void assignOperatorTarget() {
			clearHostiles(80);
			for (DroneEntity drone : drones) {
				reset(drone);
				drone.assignRole(DroneRole.FIELD);
			}
			DroneEntity commanded = drones.getFirst();
			commanded.assignRole(DroneRole.SECURITY);
			commanded.assignSecurityLoadout(SecurityLoadout.LASER);
			commanded.setPowerForVerification(1000, 1000);
			commanded.setPos(base.getX() - 8.0, base.getY() + 8.0, base.getZ());
			operatorAttackProbe = durableZombie(base.getX() + 10.5, base.getY() + 1.0, base.getZ() + 0.5);
			require(commanded.assignOperatorAttack(player, operatorAttackProbe,
				"verify-operator-target", 1, 0), "operator attack assignment was rejected");
		}

		private void assertOperatorTargetOverride() {
			DroneEntity commanded = drones.getFirst();
			require(commanded.combatTargetId() == operatorAttackProbe.getId(),
				"operator-selected target was replaced");
			require(commanded.forceOperatorOverride(), "forced combat override was rejected");
			commanded.setMode(DroneMode.STANDBY);
			require(commanded.combatState() == CombatState.IDLE && !commanded.emergencyInterceptActive(),
				"forced command did not terminate charge/fire state");
			if (operatorAttackProbe != null && operatorAttackProbe.isAlive()) operatorAttackProbe.discard();
		}

		private void assignDockSubsystemMaintenance() {
			DroneEntity drone = drones.getFirst();
			reset(drone);
			BlockPos dockPos = docks.getFirst();
			drone.assignDock(dockPos);
			drone.setPos(Vec3.atCenterOf(dockPos).add(0, 0.56, 0));
			drone.setDockedForVerification(true);
			drone.setMode(DroneMode.STANDBY);
			drone.setSubsystemConditionForVerification(200, 200, 200);
			if (!(level.getBlockEntity(dockPos) instanceof DockBlockEntity dock))
				throw new IllegalStateException("maintenance Dock missing");
			dock.setItem(DockBlockEntity.SLOT_REPAIR, new ItemStack(MorrowgearDrone.MORROW_ALLOY, 6));
			dock.setItem(DockBlockEntity.SLOT_POWER_INPUT, new ItemStack(Items.CHARCOAL, 2));
		}

		private void assertDockSubsystemMaintenance() {
			DroneEntity drone = drones.getFirst();
			require(drone.propulsionCondition() > 200, "propulsion subsystem was not repaired");
			require(drone.sensorCondition() > 200, "sensor subsystem was not repaired");
			require(drone.payloadCondition() > 200, "payload subsystem was not repaired");
			require(level.getBlockEntity(docks.getFirst()) instanceof DockBlockEntity dock
				&& dock.getContainerSize() == 27, "Dock logistics buffer is not 27 slots");
			drone.setSubsystemConditionForVerification(DroneSubsystemPolicy.MAX,
				DroneSubsystemPolicy.MAX, DroneSubsystemPolicy.MAX);
			reset(drone);
		}

		private void assertCompatibilityMatrix() {
			for (DroneRole role : DroneRole.values()) {
				DroneEntity drone = drones.get(role.ordinal());
				reset(drone);
				drone.assignRole(role);

				drone.assignSecurityPatrol(base, 16, "verify-sec-" + role.id());
				require(drone.hasSecurityPatrol() == MissionAssignmentPolicy.allows(role,
					MissionAssignmentPolicy.MissionKind.SECURITY_PATROL), role + " security compatibility mismatch");
				drone.clearSecurityPatrol();

				drone.assignFieldOperation(FieldOperationType.ORE, base, 12, "verify-field-" + role.id());
				require(drone.hasFieldOperation() == MissionAssignmentPolicy.allows(role,
					MissionAssignmentPolicy.MissionKind.FIELD_OPERATION), role + " field compatibility mismatch");
				drone.clearFieldOperation();

				drone.assignCargoSource(cargoSource);
				drone.assignCargoTarget(cargoTarget);
				require(drone.hasCargoSource() == MissionAssignmentPolicy.allows(role,
					MissionAssignmentPolicy.MissionKind.CARGO_ROUTE), role + " cargo compatibility mismatch");
				drone.pauseCargoRoute();

				drone.assignFollowFormation("verify-follow-" + role.id(), 1, 0, drone.unitId(), base, level.getGameTime());
				require(drone.mode() == DroneMode.FOLLOW, role + " follow rejected");
				drone.assignWaypoint(base.offset(4, 0, 4), "verify-waypoint-" + role.id(), 1, 0, base, level.getGameTime());
				require(drone.mode() == DroneMode.WAYPOINT, role + " waypoint rejected");
			}
		}

		private void assignOversizedWing() {
			for (DroneEntity drone : aliveDrones()) {
				reset(drone);
				drone.assignGroup("WING-VERIFY");
			}
			MorrowgearDrone.ownedDrones(level, player, 512);
		}

		private void assertWingCapacity() {
			Map<String, Integer> counts = new HashMap<>();
			for (DroneEntity drone : aliveDrones()) {
				counts.merge(drone.groupId(), 1, Integer::sum);
			}
			for (Map.Entry<String, Integer> entry : counts.entrySet()) {
				if (entry.getKey().startsWith("WING-VERIFY")) {
					require(entry.getValue() <= WingMembershipPolicy.MAX_MEMBERS,
						entry.getKey() + " exceeds 8 members: " + entry.getValue());
				}
			}
		}

		private void assignFollowMatrix() {
			List<DroneEntity> fleet = aliveDrones();
			assignGroups(fleet);
			assignFollow(fleet.subList(0, Math.min(24, fleet.size())), "verify-follow-24");
		}

		private void assignWaypointMatrix() {
			clearHostiles(128.0);
			List<DroneEntity> fleet = aliveDrones();
			assignGroups(fleet);
			List<DroneEntity> assigned = fleet.subList(0, Math.min(24, fleet.size()));
			waypointVerificationDestination = Vec3.atCenterOf(base.offset(34, 0, 10).above(6));
			waypointInitialDistances.clear();
			// The preceding follow case can leave an aircraft inside the destination's
			// eventual orbit radius. Start this transit check from a known distant grid so
			// center-distance measures navigation progress instead of orbit deployment.
			for (int index = 0; index < assigned.size(); index++) {
				DroneEntity drone = assigned.get(index);
				drone.resetNavigationForVerification();
				drone.setDeltaMovement(Vec3.ZERO);
				drone.setPos(base.getX() - 24.0 + index % 8 * 1.8,
					base.getY() + 12.0 + index / 8 * 1.5,
					base.getZ() - 10.0 + index / 8 * 3.0);
			}
			assigned.forEach(drone -> waypointInitialDistances.put(drone.getUUID(),
				drone.position().distanceTo(waypointVerificationDestination)));
			assignWaypoint(assigned, base.offset(34, 0, 10), "verify-waypoint-24");
		}

		private void assertWaypointMission() {
			assertMission("waypoint", DroneMode.WAYPOINT);
			for (DroneEntity drone : aliveDrones()) {
				Double initial = waypointInitialDistances.get(drone.getUUID());
				if (initial == null || waypointVerificationDestination == null) continue;
				double current = drone.position().distanceTo(waypointVerificationDestination);
				require(current <= initial - 4.0,
					"waypoint local sensor prevented progress " + drone.unitId()
						+ " / " + String.format(java.util.Locale.ROOT, "%.1f -> %.1f", initial, current));
			}
		}

		private void assignRosterShrink() {
			rosterProbes.clear();
			String mission = "verify-roster-" + level.getGameTime();
			BlockPos destination = base.offset(30, 0, -10);
			for (int i = 0; i < WingMembershipPolicy.MAX_MEMBERS; i++) {
				DroneEntity probe = spawnVerifierDrone(drones.size());
				probe.assignGroup("WING-VERIFY-ROSTER");
				probe.assignWaypoint(destination, mission, WingMembershipPolicy.MAX_MEMBERS,
					i, base, level.getGameTime());
				rosterProbes.add(probe);
			}
			rosterProbes.get(4).discard();
		}

		private void assertRosterShrink() {
			List<DroneEntity> survivors = rosterProbes.stream().filter(DroneEntity::isAlive)
				.sorted(Comparator.comparingInt(DroneEntity::missionIndex)).toList();
			int expected = WingMembershipPolicy.MAX_MEMBERS - 1;
			require(survivors.size() == expected, "roster survivor count " + survivors.size());
			for (int index = 0; index < survivors.size(); index++) {
				DroneEntity member = survivors.get(index);
				require(member.missionExpected() == expected,
					member.unitId() + " retained stale expected " + member.missionExpected());
				require(member.missionIndex() == index, member.unitId() + " non-contiguous index " + member.missionIndex());
			}
			rosterProbes.forEach(DroneEntity::discard);
			drones.removeAll(rosterProbes);
			rosterProbes.clear();
		}

		private void assignTrackingMission() {
			if (trackingTarget == null || !trackingTarget.isAlive()) {
				trackingTarget = spawnZombie(base.getX() + 24.5, base.getY() + 2, base.getZ() + 2.5);
			}
			List<DroneEntity> fleet = aliveDrones().subList(0, Math.min(8, aliveDrones().size()));
			String missionId = "verify-track-" + level.getGameTime();
			for (int i = 0; i < fleet.size(); i++) {
				fleet.get(i).assignTrackingTarget(trackingTarget, missionId, fleet.size(), i, base, level.getGameTime());
			}
		}

		private void assignWingRouteIsolation() {
			String sharedMission = "verify-wing-isolation-" + level.getGameTime();
			BlockPos destination = base.offset(22, 0, 22);
			for (int index = 0; index < 4; index++) {
				DroneEntity drone = drones.get(index);
				reset(drone);
				drone.setPos(base.getX() + 2.0 + index * 2.0, base.getY() + 4.0,
					base.getZ() - 4.0);
				String group = index < 2 ? "WING-VERIFY-A" : "WING-VERIFY-B";
				drone.assignGroup(group);
					drone.assignWaypoint(destination, sharedMission, 2, index % 2,
					base, level.getGameTime());
			}
			// A docked member may retain mission metadata while its sibling launches.
			drones.get(1).assignDock(docks.getFirst());
			drones.get(1).setDockedForVerification(true);
		}

		private void assertWingRouteIsolation() {
			for (int index = 0; index < 4; index++) {
				DroneEntity drone = drones.get(index);
				String expectedGroup = index < 2 ? "WING-VERIFY-A" : "WING-VERIFY-B";
				require(drone.assignedMissionSizeForVerification(level) == 2,
					drone.unitId() + " mission cohort crossed wing boundary: "
						+ drone.assignedMissionSizeForVerification(level));
				require(drone.assignedMissionOnlyContainsGroupForVerification(level, expectedGroup),
					drone.unitId() + " mission cohort contains another wing");
			}
		}

		private void assertTrackingMission() {
			List<DroneEntity> tracked = aliveDrones().subList(0, Math.min(8, aliveDrones().size()));
			for (DroneEntity drone : tracked) {
				require(drone.mode() == DroneMode.WAYPOINT, drone.unitId() + " not in tracking waypoint mode");
				require(drone.hasTrackingTarget(), drone.unitId() + " missing tracking target");
			}
		}

		private void removeTrackingTarget() {
			if (trackingTarget != null) trackingTarget.discard();
		}

		private void assertTrackingFallback() {
			for (DroneEntity drone : aliveDrones().subList(0, Math.min(8, aliveDrones().size()))) {
				require(drone.mode() == DroneMode.WAYPOINT, drone.unitId() + " left tracking fallback mission");
				require(Double.isFinite(drone.getX()) && Double.isFinite(drone.getY()) && Double.isFinite(drone.getZ()),
					drone.unitId() + " acquired invalid fallback position");
			}
		}

		private void assignSecurityPatrol() {
			if (trackingTarget == null || !trackingTarget.isAlive()) {
				trackingTarget = spawnZombie(base.getX() + 5.5, base.getY() + 2, base.getZ() + 5.5);
			}
			for (int i = 0; i < Math.min(4, drones.size()); i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.assignSecurityPatrol(base.offset(5, 1, 5), 16, "verify-security");
			}
		}

		private void assertSecurityPatrol() {
			int contacts = 0;
			int threat = 0;
			for (int i = 0; i < Math.min(4, drones.size()); i++) {
				DroneEntity drone = drones.get(i);
				require(drone.hasSecurityPatrol(), drone.unitId() + " security patrol missing");
				contacts += drone.securityContacts();
				threat = Math.max(threat, drone.threatScore());
			}
			require(contacts > 0 || threat > 0, "security patrol did not observe contacts/threats");
		}

		private void assignMixedCombat() {
			clearHostiles(64);
			FleetThreatNetwork.clear();
			prepareIsolatedCombatFleet();
			observedCombatStates.clear();
			observedCombatWeapons.clear();
			observedCombatShot = false;
			laserOrbitSamples = 0;
			maxLaserOrbitSpeed = 0.0;
			BlockPos anchor = base.offset(12, 1, 4);
			for (int i = 0; i < Math.min(4, drones.size()); i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.assignSecurityLoadout(switch (i) {
					case 0 -> SecurityLoadout.AUTOCANNON;
					case 1 -> SecurityLoadout.LASER;
					case 2 -> SecurityLoadout.MISSILE;
					default -> SecurityLoadout.AUTO;
				});
				drone.setCombatResourcesForVerification(CombatPolicy.GUN_CAPACITY,
					CombatPolicy.MISSILE_CAPACITY, 0);
				drone.setPowerForVerification(1000, 1000);
				drone.setPos(anchor.getX() - 8.0 + i * 1.6, anchor.getY() + 4.0, anchor.getZ() - 5.0);
				drone.assignSecurityPatrol(anchor, 16, "verify-mixed-combat");
			}
			LivingEntity target = spawnZombie(anchor.getX() + 0.5, anchor.getY() + 1.0, anchor.getZ() + 0.5);
			target.setInvulnerable(false);
			if (target.getAttribute(Attributes.MAX_HEALTH) != null) {
				target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1200.0);
				target.setHealth(1200.0f);
			}
			combatProbe = target;
		}

		private void observeCombat() {
			for (DroneEntity drone : drones) {
				if (drone.combatActive()) {
					observedCombatStates.add(drone.combatState());
					observedCombatWeapons.add(drone.combatWeapon());
				}
				if (drone.combatShotAge() <= 1) observedCombatShot = true;
				if (drone.combatState() == CombatState.LASER_CHARGE
					|| drone.combatState() == CombatState.LASER_FIRE) {
					if (!drone.laserIngressForVerification(level)) {
						laserOrbitSamples++;
						maxLaserOrbitSpeed = Math.max(maxLaserOrbitSpeed, drone.getDeltaMovement().length());
					}
				}
			}
			observeLaserFormation();
			observeCombatAirspace();
			observeCombatTrigger();
			observeHighThreatReinforcement();
			observeLinearAutocannonStrike();
			observeAdaptiveCombat();
			observeFiringRoutes();
		}

		private void assignAdaptiveLaserGroups() {
			clearHostiles(96);
			FleetThreatNetwork.clear();
			CombatTelemetry.clear();
			CombatAirspaceRegistry.clear();
			adaptiveLaserFiringGroups.clear();
			adaptiveExpectedLaserUnits.clear();
			adaptiveLaserFiringUnits.clear();
			adaptiveCrossingGunShotObserved = false;
			adaptiveCrossingTrafficObserved = false;
			adaptiveLaserDecoy = null;
			adaptiveLaserLockedTargetId = -1;
			adaptiveLaserTargetStable = true;
			adaptiveLaserStableTicks = 0;
			adaptiveLaserMaximumStableTicks = 0;
			adaptiveLaserRelativeHeights.clear();
			adaptiveLaserMaximumHeightStep = 0.0;
			adaptiveLaserEmissionStableTicks = 0;
			adaptiveLaserMaximumEmissionStableTicks = 0;
			adaptiveLaserGroupEmissionTicks.clear();
			adaptiveLaserGroupMaximumEmissionTicks.clear();
			BlockPos anchor = base.offset(14, 1, 0);
			for (int i = 0; i < 4; i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.assignSecurityLoadout(SecurityLoadout.LASER);
				drone.assignGroup(i < 2 ? "VERIFY-ADAPT-LASER-A" : "VERIFY-ADAPT-LASER-B");
				drone.setPowerForVerification(1000, 1000);
				drone.setPos(anchor.getX() - 10.0 + i * 2.0, anchor.getY() + 5.0, anchor.getZ() - 7.0);
					drone.assignSecurityPatrol(anchor, 18, i < 2 ? "adapt-laser-a" : "adapt-laser-b");
				adaptiveExpectedLaserUnits.add(drone.getUUID());
			}
			for (int i = 4; i < 6; i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.assignSecurityLoadout(SecurityLoadout.AUTOCANNON);
				drone.assignGroup("VERIFY-ADAPT-GUN-CROSS");
				drone.setPowerForVerification(1000, 1000);
				drone.setPos(anchor.getX() - 30.0, anchor.getY() + 10.0,
					anchor.getZ() - 2.0 + (i - 4) * 4.0);
				// Use a fixed ingress axis for this crossing fixture. A target-centered
				// patrol randomizes the CAS axis from the transient entity id.
				drone.assignSecurityPatrol(anchor.offset(-60, 0, 0), 18, "adapt-gun-cross");
			}
			combatProbe = durableZombie(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
			for (DroneEntity drone : drones) {
				drone.setEmergencyInterceptForVerification(combatProbe.getId(), combatProbe.position(), 6);
			}
			adaptiveLaserLockedTargetId = combatProbe.getId();
		}

		private void assertAdaptiveLaserGroups() {
			require(adaptiveLaserFiringGroups.contains("VERIFY-ADAPT-LASER-A"),
				"laser group A stayed in constrained charge orbit");
			require(adaptiveLaserFiringGroups.contains("VERIFY-ADAPT-LASER-B"),
				"laser group B stayed in constrained charge orbit");
			require(adaptiveLaserFiringUnits.containsAll(adaptiveExpectedLaserUnits),
				"individual laser aircraft stayed in charge orbit: expected="
					+ adaptiveExpectedLaserUnits.size() + " fired=" + adaptiveLaserFiringUnits.size());
			require(adaptiveCrossingTrafficObserved,
				"autocannon element did not cross the active laser engagement");
			require(adaptiveLaserDecoy != null, "competing laser target was not introduced");
			require(adaptiveLaserTargetStable,
				"laser aircraft changed target during charge or sustained fire");
			require(adaptiveLaserMaximumStableTicks >= 30,
				"laser formation did not remain stable through crossing traffic: "
					+ adaptiveLaserMaximumStableTicks);
			require(adaptiveLaserMaximumEmissionStableTicks >= 20,
				"laser emission was interrupted by crossing traffic: "
					+ adaptiveLaserMaximumEmissionStableTicks);
			require(adaptiveLaserMaximumHeightStep <= 0.16,
				"laser orbit altitude lane jumped during crossing traffic: "
					+ adaptiveLaserMaximumHeightStep);
		}

		private void assignAdaptiveRouteIntercept() {
			clearHostiles(96);
			FleetThreatNetwork.clear();
			adaptiveRouteInterceptObserved = false;
			adaptiveRouteResumeObserved = false;
			adaptiveRouteTargetRemoved = false;
			BlockPos first = base.offset(8, 1, -10);
			List<BlockPos> route = List.of(first, first.offset(14, 0, 0), first.offset(14, 0, 14));
			for (int i = 0; i < 4; i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(i == 0 ? DroneRole.SCOUT : DroneRole.SECURITY);
				drone.assignGroup("VERIFY-ADAPT-ROUTE");
				drone.setPowerForVerification(1000, 1000);
				drone.setPos(first.getX() - 4.0 + i * 1.5, first.getY() + 5.0, first.getZ() - 4.0);
				drone.assignPatrolRoute(route, "adapt-route", 4, i, first, level.getGameTime());
			}
			combatProbe = durableZombie(first.getX() + 5.5, first.getY(), first.getZ() + 3.5);
		}

		private void assertAdaptiveRouteIntercept() {
			require(adaptiveRouteInterceptObserved, "route wing did not detach a guard for interception");
			require(adaptiveRouteResumeObserved, "temporary interceptor did not resume the route wing");
		}

		private void assignAdaptiveRechargeRotation() {
			clearHostiles(96);
			FleetThreatNetwork.clear();
			prepareIsolatedCombatFleet();
			CombatTelemetry.clear();
			CombatManeuverRegistry.clear();
			CombatAirspaceRegistry.clear();
			adaptiveInitialResponders.clear();
			adaptiveRechargeRtbObserved = false;
			adaptiveReplacementObserved = false;
			adaptiveOriginalMissionResumed = false;
			adaptiveReliefChurnObserved = false;
			adaptivePowerDepleted = false;
			BlockPos anchor = base.offset(14, 1, 0);
			for (int i = 0; i < 6; i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.assignGroup("VERIFY-ADAPT-ROTATION");
				drone.assignSecurityLoadout(i == 0 ? SecurityLoadout.LASER : SecurityLoadout.AUTOCANNON);
				drone.setPowerForVerification(1000, 1000);
				double stagingX = i < 3 ? anchor.getX() - 5.0 - i * 1.5
					: anchor.getX() - 38.0 - (i - 3) * 2.0;
				drone.setPos(stagingX, anchor.getY() + 5.0, anchor.getZ() - 4.0);
				drone.assignSecurityPatrol(anchor, 20, "adapt-rotation-" + i);
			}
			adaptiveRechargeOriginal = drones.getFirst();
			adaptiveRechargeOriginal.assignDock(docks.getFirst());
			if (!(level.getBlockEntity(docks.getFirst()) instanceof DockBlockEntity rechargeDock))
				throw new IllegalStateException("adaptive recharge Dock missing");
			// Case 04 verifies rotation, not inventory ingestion. Seed the persisted remainder
			// of one opened cell so service timing is deterministic across an already-used Dock.
			rechargeDock.setSupplyCreditsForVerification(DockSupplyPolicy.LASER_CELL_ENERGY - 1, 0, 0);
			LivingEntity target = spawnZombie(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
			if (target.getAttribute(Attributes.MAX_HEALTH) != null) {
				target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
				target.setHealth(20.0f);
			}
			target.setInvulnerable(true);
			if (target instanceof Mob mob) mob.setTarget(player);
			for (DroneEntity drone : drones) {
				// This case verifies one-for-one relief inheritance. Multi-aircraft demand
				// scaling is covered by the dedicated reinforcement cases.
				drone.setEmergencyInterceptForVerification(target.getId(), target.position(), 1);
			}
			combatProbe = target;
		}

		private void assertAdaptiveRechargeRotation() {
			require(adaptiveRechargeRtbObserved, "all weapon stores depleted but Dock RTB did not start");
			require(adaptiveReplacementObserved, "no available guard relieved the charging aircraft");
			require(adaptiveOriginalMissionResumed,
				"rearmed aircraft did not resume mission while the relief held combat strength");
			require(!adaptiveReliefChurnObserved,
				"rearmed aircraft displaced its active relief and caused responder churn");
			String inherited = adaptiveRechargeOriginal.reliefMissionInheritedFrom();
			boolean inheritedFromReplacement = drones.stream().anyMatch(drone ->
				!drone.getUUID().equals(adaptiveRechargeOriginal.getUUID())
					&& drone.combatActive()
					&& drone.securityOrderId().equals(inherited));
			require(!inherited.isBlank() && inheritedFromReplacement
				&& adaptiveRechargeOriginal.securityOrderId().equals(inherited),
				"rearmed aircraft did not inherit an actual relief mission: inherited "
					+ inherited + " actual " + adaptiveRechargeOriginal.securityOrderId());
		}

		private void assignAdaptiveHeatService() {
			clearHostiles(96);
			FleetThreatNetwork.clear();
			CombatTelemetry.clear();
			CombatManeuverRegistry.clear();
			adaptiveHeatCooledObserved = false;
			adaptiveHeatPatrolMaintained = true;
			adaptiveHeatCombatObserved = false;
			BlockPos anchor = base.offset(14, 1, 0);
			for (int i = 0; i < 3; i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.assignSecurityLoadout(i == 0 ? SecurityLoadout.LASER : SecurityLoadout.AUTOCANNON);
				drone.assignGroup("VERIFY-ADAPT-HEAT");
				drone.setPowerForVerification(1000, 1000);
				drone.setPos(anchor.getX() - 8.0 - i * 2.0, anchor.getY() + 5.0, anchor.getZ() - 4.0);
				drone.assignSecurityPatrol(anchor, 20, "adapt-heat");
			}
			adaptiveHeatAircraft = drones.getFirst();
			adaptiveHeatAircraft.assignDock(docks.getFirst());
			adaptiveHeatAircraft.setCombatResourcesForVerification(CombatPolicy.GUN_CAPACITY,
				CombatPolicy.MISSILE_CAPACITY, 1000);
			LivingEntity target = durableZombie(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
			target.setInvulnerable(true);
			if (target instanceof Mob mob) mob.setTarget(player);
			combatProbe = target;
		}

		private void assertAdaptiveHeatService() {
			require(adaptiveHeatPatrolMaintained,
				"overheated laser aircraft abandoned its patrol for Dock service");
			require(adaptiveHeatCooledObserved, "laser aircraft did not cool below the firing threshold in flight");
			require(adaptiveHeatCombatObserved, "cooled laser aircraft did not reassess and rejoin the live threat");
		}

		private void assignAdaptiveAutocannon() {
			clearHostiles(96);
			FleetThreatNetwork.clear();
			adaptiveGunShotObserved = false;
			adaptiveGunMaximumAimOffset = 0.0;
			adaptiveGunMaximumSpacing = 0.0;
			adaptiveGunMinimumForwardDot = 1.0;
			adaptiveGunPreviousSpeed.clear();
			adaptiveGunMaximumSpeedStep = 0.0;
			adaptiveGunSpeedSamples = 0;
			CombatManeuverRegistry.clear();
			BlockPos anchor = base.offset(14, 1, 0);
			for (int i = 0; i < 3; i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.assignSecurityLoadout(SecurityLoadout.AUTOCANNON);
				drone.assignGroup("VERIFY-ADAPT-GUN");
				drone.setPowerForVerification(1000, 1000);
				drone.setPos(anchor.getX() - 48.0, anchor.getY() + 14.0, anchor.getZ() - 3.0 + i * 3.0);
				drone.assignSecurityPatrol(anchor, 20, "adapt-gun");
			}
			combatProbe = durableZombie(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
			for (DroneEntity drone : drones.subList(0, 3)) {
				drone.setEmergencyInterceptForVerification(combatProbe.getId(), combatProbe.position(), 3);
			}
		}

		private void assertAdaptiveAutocannon() {
			require(adaptiveGunShotObserved, "autocannon element did not fire");
			require(adaptiveGunMaximumSpacing >= 2.0,
				"autocannon aircraft remained in an exact trail column: " + adaptiveGunMaximumSpacing);
			require(adaptiveGunMaximumAimOffset <= 10.0,
				"airspace shifted autocannon aim away from the target: " + adaptiveGunMaximumAimOffset);
			require(adaptiveGunMinimumForwardDot >= 0.18,
				"autocannon fired behind its flight vector: " + adaptiveGunMinimumForwardDot);
			require(adaptiveGunSpeedSamples >= 20,
				"autocannon run produced too few speed samples: " + adaptiveGunSpeedSamples);
			require(adaptiveGunMaximumSpeedStep <= 0.35,
				"autocannon run changed speed abruptly: " + adaptiveGunMaximumSpeedStep);
		}

		private void assignRemoteOperation() {
			clearHostiles(96);
			FleetThreatNetwork.clear();
			prepareIsolatedCombatFleet();
			remoteOperationDrones.forEach(DroneEntity::discard);
			remoteOperationDrones.clear();
			remoteOperationOrigins.clear();
			remoteOperationMotionObserved = false;
			remoteOperationCombatObserved = false;
			BlockPos anchor = base.offset(560, 1, 0);
			int anchorChunkX = anchor.getX() >> 4;
			int anchorChunkZ = anchor.getZ() >> 4;
			// Bootstrap the isolated remote fixture synchronously. Expiring operation tickets
			// are intentionally only responsible for keeping an already active mission ticking.
			for (int x = anchorChunkX - 2; x <= anchorChunkX + 2; x++) {
				for (int z = anchorChunkZ - 2; z <= anchorChunkZ + 2; z++) level.getChunk(x, z);
			}
			clearHostilesAt(anchor, 96);
			List<BlockPos> route = List.of(anchor, anchor.offset(18, 0, 0), anchor.offset(18, 0, 18));
			for (int i = 0; i < 4; i++) {
				DroneEntity drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
				if (drone == null) throw new IllegalStateException("failed to create remote verifier drone");
				drone.setPos(anchor.getX() - 5.0 + i * 1.6, anchor.getY() + 5.0, anchor.getZ() - 5.0);
				drone.initializeOwner(player);
				drone.assignRole(i == 0 ? DroneRole.SCOUT : DroneRole.SECURITY);
				drone.assignSecurityLoadout(i == 0 ? SecurityLoadout.UNARMED : SecurityLoadout.AUTO);
				drone.setCombatResourcesForVerification(CombatPolicy.GUN_CAPACITY,
					CombatPolicy.MISSILE_CAPACITY, 0);
				drone.assignGroup("VERIFY-REMOTE-WING");
				drone.setPowerForVerification(1000, 1000);
				level.addFreshEntity(drone);
				// Match the real lifecycle: the aircraft exists in the level before a
				// mission assignment starts and refreshes its operation ticket.
				drone.assignPatrolRoute(route, "remote-route", 4, i, anchor, level.getGameTime());
				level.getChunk(drone.chunkPosition().x(), drone.chunkPosition().z());
				remoteOperationDrones.add(drone);
				remoteOperationOrigins.put(drone.getUUID(), drone.position());
			}
			combatProbe = durableZombie(anchor.getX() + 7.5, anchor.getY(), anchor.getZ() + 5.5);
		}

		private void assertRemoteOperation() {
			if (!remoteOperationMotionObserved || !remoteOperationCombatObserved) {
				logCombatDiagnostics("REMOTE", remoteOperationDrones);
			}
			require(remoteOperationDrones.stream().allMatch(drone ->
				drone.distanceToSqr(player) > 512.0 * 512.0),
				"remote probes were not outside the former owner-centered roster radius");
			List<DroneEntity> roster = MorrowgearDrone.ownedDrones(level, player, 512);
			require(remoteOperationDrones.stream().allMatch(roster::contains),
				"loaded remote Wing was absent from the owner roster");
			require(remoteOperationMotionObserved,
				"remote Wing stopped navigating when the owner was outside 512 blocks");
			require(remoteOperationCombatObserved,
				"remote Scout report did not detach Security while the owner was outside 512 blocks");
			remoteOperationDrones.forEach(DroneEntity::discard);
			remoteOperationDrones.clear();
			remoteOperationOrigins.clear();
			if (combatProbe != null) combatProbe.discard();
			combatProbe = null;
		}

		private void observeAdaptiveCombat() {
			// Keep endurance/service depletion outside the crossing-traffic case. Recharge
			// rotation and heat recovery have dedicated cases below.
			for (DroneEntity drone : drones) {
				if (drone.groupId().startsWith("VERIFY-ADAPT-LASER-")) {
					drone.setPowerForVerification(1000, 1000);
					drone.setCombatResourcesForVerification(drone.gunAmmo(), drone.missiles(), 0);
				}
			}
			boolean routeCaseActive = drones.stream().anyMatch(drone ->
				drone.groupId().equals("VERIFY-ADAPT-ROUTE"));
			if (routeCaseActive && combatProbe != null && combatProbe.isAlive()) {
				FleetThreatNetwork.publish(level.dimension().toString(), player.getUUID(),
					"VERIFY-SCOUT-ROUTE", new FleetThreatNetwork.Report(42, 28, 18,
						combatProbe.position(), combatProbe.getId(), "VERIFY-ADAPT-ROUTE",
						"adapt-route", level.getGameTime()));
			}
			boolean remoteCaseActive = !remoteOperationDrones.isEmpty()
				&& remoteOperationDrones.stream().anyMatch(DroneEntity::isAlive);
			if (remoteCaseActive) {
				// Natural spawns near the verifier player publish a higher-priority PLAYER-GUARD
				// contact. Keep this fixture isolated so it measures the remote Scout data link,
				// not the intentionally dominant owner-protection override.
				clearHostiles(96);
				if (combatProbe != null && combatProbe.isAlive())
					clearHostilesExcept(BlockPos.containing(combatProbe.position()), 96, combatProbe);
				for (DroneEntity drone : remoteOperationDrones) {
					Vec3 origin = remoteOperationOrigins.get(drone.getUUID());
					if (origin != null && drone.position().distanceTo(origin) >= 3.0) {
						remoteOperationMotionObserved = true;
					}
				}
				if (combatProbe != null && combatProbe.isAlive()) {
					FleetThreatNetwork.publish(level.dimension().toString(), player.getUUID(),
						"VERIFY-REMOTE-SCOUT", new FleetThreatNetwork.Report(48, 32, 0,
							combatProbe.position(), combatProbe.getId(), "VERIFY-REMOTE-WING",
							"remote-route", level.getGameTime()));
					remoteOperationCombatObserved |= remoteOperationDrones.stream().skip(1).anyMatch(drone ->
						(drone.emergencyInterceptActive()
							&& drone.emergencyTargetIdForVerification() == combatProbe.getId())
						|| (drone.combatActive() && drone.combatTargetId() == combatProbe.getId()));
				}
			}
			if (index >= 0 && index < cases.size()
				&& cases.get(index).name().equals("adaptive multi-group laser reservation")
				&& level.getGameTime() % 40 == 0) {
				for (DroneEntity drone : drones) {
					if (drone.groupId().startsWith("VERIFY-ADAPT-LASER-")) {
						MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] LASER DIAG {} {} {}",
							drone.groupId(), drone.unitId(), drone.laserDiagnosticForVerification(level));
					}
				}
			}
			for (DroneEntity drone : drones) {
				if (drone.groupId().startsWith("VERIFY-ADAPT-LASER-")
					&& drone.combatState() == CombatState.LASER_FIRE) {
					adaptiveLaserFiringGroups.add(drone.groupId());
					adaptiveLaserFiringUnits.add(drone.getUUID());
				}
				if (drone.groupId().equals("VERIFY-ADAPT-GUN-CROSS")
					&& drone.combatShotAge() <= 1) adaptiveCrossingGunShotObserved = true;
			}
			boolean bothLaserGroupsFiring = adaptiveLaserFiringGroups.contains("VERIFY-ADAPT-LASER-A")
				&& adaptiveLaserFiringGroups.contains("VERIFY-ADAPT-LASER-B");
			if (bothLaserGroupsFiring && combatProbe != null) {
				adaptiveCrossingTrafficObserved |= drones.stream().anyMatch(drone ->
					drone.groupId().equals("VERIFY-ADAPT-GUN-CROSS") && drone.combatActive()
						&& drone.position().multiply(1, 0, 1).distanceTo(
							combatProbe.position().multiply(1, 0, 1)) <= 28.0);
			}
			if (bothLaserGroupsFiring && adaptiveLaserDecoy == null && combatProbe != null && combatProbe.isAlive()) {
				adaptiveLaserDecoy = durableZombie(combatProbe.getX() + 2.0,
					combatProbe.getY(), combatProbe.getZ() + 1.0);
			}
			if (adaptiveLaserDecoy != null && adaptiveLaserDecoy.isAlive()) {
				List<DroneEntity> lockedLasers = drones.stream().filter(drone ->
					drone.groupId().startsWith("VERIFY-ADAPT-LASER-")
						&& (drone.combatState() == CombatState.LASER_CHARGE
							|| drone.combatState() == CombatState.LASER_FIRE)).toList();
				adaptiveLaserTargetStable &= lockedLasers.stream().allMatch(drone ->
					drone.combatTargetId() == adaptiveLaserLockedTargetId);
				adaptiveLaserStableTicks = lockedLasers.size() >= 2 ? adaptiveLaserStableTicks + 1 : 0;
				adaptiveLaserMaximumStableTicks = Math.max(adaptiveLaserMaximumStableTicks,
					adaptiveLaserStableTicks);
				if (adaptiveCrossingTrafficObserved && lockedLasers.size() >= 4) {
					for (String group : List.of("VERIFY-ADAPT-LASER-A", "VERIFY-ADAPT-LASER-B")) {
						List<DroneEntity> groupMembers = lockedLasers.stream()
							.filter(drone -> drone.groupId().equals(group)).toList();
						boolean groupEmitting = !groupMembers.isEmpty() && groupMembers.stream().allMatch(drone ->
							drone.combatState() == CombatState.LASER_FIRE && drone.combatShotAge() <= 4);
						int run = groupEmitting ? adaptiveLaserGroupEmissionTicks.getOrDefault(group, 0) + 1 : 0;
						adaptiveLaserGroupEmissionTicks.put(group, run);
						adaptiveLaserGroupMaximumEmissionTicks.merge(group, run, Math::max);
					}
					adaptiveLaserMaximumEmissionStableTicks = List.of(
						adaptiveLaserGroupMaximumEmissionTicks.getOrDefault("VERIFY-ADAPT-LASER-A", 0),
						adaptiveLaserGroupMaximumEmissionTicks.getOrDefault("VERIFY-ADAPT-LASER-B", 0))
						.stream().mapToInt(Integer::intValue).min().orElse(0);
					for (DroneEntity drone : lockedLasers) {
						if (drone.combatState() != CombatState.LASER_FIRE) continue;
						double height = drone.laserOrbitRelativeHeightForVerification(level);
						Double previous = adaptiveLaserRelativeHeights.put(drone.getUUID(), height);
						if (previous != null) adaptiveLaserMaximumHeightStep = Math.max(
							adaptiveLaserMaximumHeightStep, Math.abs(height - previous));
					}
				}
			}
			if (drones.stream().anyMatch(drone -> drone.groupId().equals("VERIFY-ADAPT-ROUTE")
				&& drone.role() == DroneRole.SECURITY && drone.emergencyInterceptActive() && drone.combatActive())) {
				adaptiveRouteInterceptObserved = true;
			}
			if (adaptiveRouteInterceptObserved && !adaptiveRouteTargetRemoved
				&& level.getGameTime() - startedTick > 120 && combatProbe != null) {
				combatProbe.discard();
				adaptiveRouteTargetRemoved = true;
			}
			if (adaptiveRouteTargetRemoved) adaptiveRouteResumeObserved |= drones.stream().anyMatch(drone ->
				drone.groupId().equals("VERIFY-ADAPT-ROUTE") && drone.role() == DroneRole.SECURITY
					&& drone.hasPatrolRoute() && !drone.emergencyInterceptActive() && !drone.combatActive());

			if (adaptiveRechargeOriginal != null && adaptiveRechargeOriginal.groupId().equals("VERIFY-ADAPT-ROTATION")) {
				if ((level.getGameTime() - startedTick) % 100L == 0L) {
					MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] RECHARGE DIAG {} {}",
						adaptiveRechargeOriginal.unitId(),
						adaptiveRechargeOriginal.rechargeDecisionDiagnosticForVerification());
				}
				Set<UUID> activeNow = drones.stream().filter(drone -> drone.groupId().equals("VERIFY-ADAPT-ROTATION")
					&& drone.combatActive()).map(DroneEntity::getUUID).collect(java.util.stream.Collectors.toSet());
				// This case verifies one-for-one replacement. Deplete the first committed
				// responder immediately instead of waiting for unrelated fleet scaling.
				if (adaptiveInitialResponders.isEmpty()
					&& activeNow.contains(adaptiveRechargeOriginal.getUUID())) {
					adaptiveInitialResponders.addAll(activeNow);
				}
				if (!adaptivePowerDepleted && adaptiveInitialResponders.contains(adaptiveRechargeOriginal.getUUID())) {
					adaptiveRechargeOriginal.setPowerForVerification(
						adaptiveRechargeOriginal.batteryPercent() * 10, 0);
					// Composite armament keeps fighting while any independent store remains.
					// Exhaust all three stores here so this case verifies actual service rotation.
					adaptiveRechargeOriginal.setCombatResourcesForVerification(0, 0, 0);
					adaptivePowerDepleted = true;
				}
				adaptiveRechargeRtbObserved |= adaptiveRechargeOriginal.weaponRechargeActive()
					|| adaptiveRechargeOriginal.isDocked();
				if (adaptiveRechargeRtbObserved) adaptiveReplacementObserved |= activeNow.stream()
					.anyMatch(id -> !adaptiveInitialResponders.contains(id));
				if (adaptiveRechargeRtbObserved && !adaptiveRechargeOriginal.weaponRechargeActive()
					&& !adaptiveRechargeOriginal.isDocked() && !adaptiveRechargeOriginal.combatActive()
					&& adaptiveRechargeOriginal.hasSecurityPatrol()
					&& adaptiveRechargeOriginal.batteryPercent() >= CombatPolicy.RESOURCE_LIMITED_SORTIE_POWER
					&& adaptiveRechargeOriginal.weaponPowerPercent() >= CombatPolicy.RESOURCE_LIMITED_SORTIE_POWER) {
					adaptiveOriginalMissionResumed = true;
				}
				if (adaptiveOriginalMissionResumed && activeNow.contains(adaptiveRechargeOriginal.getUUID()))
					adaptiveReliefChurnObserved = true;
			}

			if (adaptiveHeatAircraft != null && adaptiveHeatAircraft.groupId().equals("VERIFY-ADAPT-HEAT")) {
				adaptiveHeatPatrolMaintained &= !adaptiveHeatAircraft.serviceReturnActive()
					&& !adaptiveHeatAircraft.isDocked() && adaptiveHeatAircraft.hasSecurityPatrol();
				adaptiveHeatCooledObserved |= adaptiveHeatAircraft.laserHeat() < CombatPolicy.LASER_SWITCH_HEAT;
				if (adaptiveHeatCooledObserved && adaptiveHeatAircraft.combatActive()
					&& adaptiveHeatAircraft.combatTargetId() == (combatProbe == null ? -1 : combatProbe.getId())) {
					adaptiveHeatCombatObserved = true;
				}
			}

			if (combatProbe != null && combatProbe.isAlive()) {
				List<DroneEntity> guns = drones.stream().filter(drone -> drone.groupId().equals("VERIFY-ADAPT-GUN")
					&& drone.combatWeapon() == CombatWeapon.AUTOCANNON).toList();
				for (int i = 0; i < guns.size(); i++) for (int j = i + 1; j < guns.size(); j++) {
					adaptiveGunMaximumSpacing = Math.max(adaptiveGunMaximumSpacing,
						guns.get(i).position().distanceTo(guns.get(j).position()));
				}
				for (DroneEntity gun : guns) if (gun.combatShotAge() <= 1) {
					adaptiveGunShotObserved = true;
					Vec3 aim = gun.position().add(gun.autocannonVisualAim(combatProbe));
					Vec3 shot = aim.subtract(gun.position());
					if (gun.getDeltaMovement().lengthSqr() >= 0.01 && shot.lengthSqr() >= 0.01) {
						adaptiveGunMinimumForwardDot = Math.min(adaptiveGunMinimumForwardDot,
							gun.getDeltaMovement().normalize().dot(shot.normalize()));
					}
					adaptiveGunMaximumAimOffset = Math.max(adaptiveGunMaximumAimOffset,
						aim.multiply(1, 0, 1).distanceTo(combatProbe.position().multiply(1, 0, 1)));
				}
				for (DroneEntity gun : guns) {
					if (gun.combatState() != CombatState.GUN_RUN || gun.recoveryLevel() > 0) {
						adaptiveGunPreviousSpeed.remove(gun.getUUID());
						continue;
					}
					double speed = gun.getDeltaMovement().length();
					Double previous = adaptiveGunPreviousSpeed.put(gun.getUUID(), speed);
					if (previous != null) {
						adaptiveGunMaximumSpeedStep = Math.max(adaptiveGunMaximumSpeedStep,
							Math.abs(speed - previous));
						adaptiveGunSpeedSamples++;
					}
				}
			}
		}

		private void observeFiringRoutes() {
			if (scope != VerificationScope.FIRING_ROUTES) return;
			for (DroneEntity drone : drones) {
				if (!drone.groupId().startsWith("VERIFY-ROUTE-")) continue;
				if (drone.combatActive()) firingRouteObservedWeapons.add(drone.combatWeapon());
				if (drone.combatShotAge() <= 1) firingRouteShotUnits.add(drone.getUUID());
				if (drone.combatFiringRouteActiveForVerification())
					firingRouteAlternativeUnits.add(drone.getUUID());
				if (drone.combatRouteSuppressedWeaponForVerification() != CombatWeapon.NONE)
					firingRouteSuppressionObserved = true;
			}
		}

		private void resetFiringRouteFixture() {
			if (combatProbe != null && combatProbe.isAlive()) combatProbe.discard();
			combatProbe = null;
			firingRouteShotUnits.clear();
			firingRouteAlternativeUnits.clear();
			firingRouteObservedWeapons.clear();
			firingRouteSuppressionObserved = false;
			firingRouteInitialTargetHealth = 0;
			FleetThreatNetwork.clear();
			CombatTelemetry.clear();
			CombatManeuverRegistry.clear();
			CombatAirspaceRegistry.clear();
			clearHostilesAt(base, 128);
			for (DroneEntity drone : drones) {
				reset(drone);
				drone.assignRole(DroneRole.FIELD);
				drone.setPos(base.getX() - 16, base.getY() + 18, base.getZ() + 34);
			}
		}

		private void assignCaveLaserRoute() {
			resetFiringRouteFixture();
			BlockPos anchor = base.offset(24, 1, 0);
			prepareRouteChamber(anchor, 36, 24, 11, false);
			combatProbe = routeTarget(anchor.offset(10, 1, 0));
			armRouteDrones(2, SecurityLoadout.LASER, "VERIFY-ROUTE-CAVE-LASER",
				anchor.offset(-44, 4, 0), combatProbe);
		}

		private void assertCaveLaserRoute() {
			if (firingRouteShotUnits.size() < 2) logFiringRouteDiagnostics("CAVE");
			require(firingRouteAlternativeUnits.size() >= 2,
				"laser element did not leave the roof-blocked formation geometry");
			require(firingRouteShotUnits.size() >= 2,
				"not every cave laser aircraft acquired a firing aperture: " + firingRouteShotUnits.size());
			require(firingRouteObservedWeapons.contains(CombatWeapon.LASER),
				"laser cave fixture never entered laser combat");
			require(combatProbe != null && combatProbe.getHealth() < firingRouteInitialTargetHealth,
				"laser emission did not apply damage inside the cave");
		}

		private void assignCaveGunRoute() {
			resetFiringRouteFixture();
			BlockPos anchor = base.offset(24, 1, 0);
			prepareRouteChamber(anchor, 36, 24, 11, false);
			combatProbe = routeTarget(anchor.offset(9, 1, 0));
			armRouteDrones(2, SecurityLoadout.AUTOCANNON, "VERIFY-ROUTE-CAVE-GUN",
				anchor.offset(-44, 4, 0), combatProbe);
		}

		private void assertCaveGunRoute() {
			require(firingRouteAlternativeUnits.size() >= 1,
				"autocannon did not select an alternate cave ingress");
			require(firingRouteShotUnits.size() >= 1, "autocannon never fired through the cave aperture");
			require(firingRouteObservedWeapons.contains(CombatWeapon.AUTOCANNON),
				"cave fixture did not retain autocannon doctrine");
			require(combatProbe != null && combatProbe.getHealth() < firingRouteInitialTargetHealth,
				"autocannon route produced no entity damage");
		}

		private void assignShaftLaserRoute() {
			resetFiringRouteFixture();
			BlockPos anchor = base.offset(24, 1, 0);
			clearVolume(anchor, 40, -2, 32);
			for (int y = 0; y <= 24; y++) for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
				boolean wall = Math.abs(x) >= 3 || Math.abs(z) >= 3;
				level.setBlockAndUpdate(anchor.offset(x, y, z), wall
					? Blocks.DEEPSLATE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
			for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
				level.setBlockAndUpdate(anchor.offset(x, -1, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
			combatProbe = routeTarget(anchor.offset(0, 1, 0));
			armRouteDrones(1, SecurityLoadout.LASER, "VERIFY-ROUTE-SHAFT",
				anchor.offset(0, 28, 0), combatProbe);
		}

		private void assertShaftLaserRoute() {
			if (firingRouteShotUnits.size() != 1 || combatProbe == null
				|| combatProbe.getHealth() >= firingRouteInitialTargetHealth) {
				logFiringRouteDiagnostics("SHAFT");
			}
			require(firingRouteAlternativeUnits.size() == 1,
				"shaft aircraft did not select the vertical firing solution");
			require(firingRouteShotUnits.size() == 1, "shaft laser did not fire down the clear opening");
			require(combatProbe != null && combatProbe.getHealth() < firingRouteInitialTargetHealth,
				"shaft laser did not damage the target");
		}

		private void logFiringRouteDiagnostics(String label) {
			MorrowgearDrone.LOGGER.info(
				"[MORROWGEAR VERIFY] ROUTE-{} target={}:{} health={}/{} disposition={} shots={} alternatives={} weapons={}",
				label, combatProbe == null ? -1 : combatProbe.getId(),
				combatProbe != null && combatProbe.isAlive(),
				combatProbe == null ? -1.0f : combatProbe.getHealth(), firingRouteInitialTargetHealth,
				combatProbe == null ? null : ThreatAssessment.targetDisposition(combatProbe, player,
					player.getLastHurtByMob()), firingRouteShotUnits.size(),
				firingRouteAlternativeUnits.size(), firingRouteObservedWeapons);
			for (DroneEntity drone : drones.stream().filter(DroneEntity::isAlive)
				.filter(unit -> unit.groupId().startsWith("VERIFY-ROUTE-")).toList()) {
				MorrowgearDrone.LOGGER.info(
					"[MORROWGEAR VERIFY] ROUTE-{} unit={} pos={} role={} mode={} state={} weapon={} emergency={}:{} active={} target={} route={}:{} recovery={} service={} power={}/{} laser={}",
					label, drone.unitId(), drone.position(), drone.role(), drone.mode(), drone.combatState(),
					drone.combatWeapon(), drone.emergencyInterceptActive(),
					drone.emergencyTargetIdForVerification(), drone.combatActive(), drone.combatTargetId(),
					drone.combatFiringRouteActiveForVerification(),
					drone.combatRouteSuppressedWeaponForVerification(), drone.recoveryLevel(),
					drone.serviceReturnActive(), drone.batteryPercent(), drone.weaponPowerPercent(),
					drone.laserDiagnosticForVerification(level));
			}
		}

		private void assignSealedMissileRoute() {
			resetFiringRouteFixture();
			BlockPos anchor = base.offset(24, 1, 0);
			prepareRouteChamber(anchor, 18, 14, 10, true);
			combatProbe = routeTarget(anchor.offset(0, 1, 0));
			armRouteDrones(1, SecurityLoadout.MISSILE, "VERIFY-ROUTE-SEALED",
				anchor.offset(-28, 5, 0), combatProbe);
		}

		private void assertSealedMissileRoute() {
			DroneEntity drone = drones.getFirst();
			require(firingRouteSuppressionObserved,
				"sealed target did not terminate the bounded missile route search");
			require(drone.missiles() == CombatPolicy.MISSILE_CAPACITY,
				"missile ammunition was consumed through solid cover: " + drone.missiles());
			require(combatProbe != null && combatProbe.getHealth() == firingRouteInitialTargetHealth,
				"sealed target received damage through cover");
		}

		private void assignMultiWingWallRoutes() {
			resetFiringRouteFixture();
			BlockPos anchor = base.offset(24, 1, 0);
			clearVolume(anchor, 48, -2, 24);
			for (int z = -24; z <= 24; z++) for (int y = 0; y <= 18; y++) {
				boolean opening = (Math.abs(z - 14) <= 3 || Math.abs(z + 14) <= 3) && y >= 1 && y <= 8;
				level.setBlockAndUpdate(anchor.offset(0, y, z), opening
					? Blocks.AIR.defaultBlockState() : Blocks.DEEPSLATE_BRICKS.defaultBlockState());
			}
			combatProbe = routeTarget(anchor.offset(12, 1, 0));
			for (int i = 0; i < 4; i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.assignSecurityLoadout(SecurityLoadout.LASER);
				drone.assignGroup(i < 2 ? "VERIFY-ROUTE-WALL-A" : "VERIFY-ROUTE-WALL-B");
				drone.setPos(anchor.getX() - 28, anchor.getY() + 4,
					anchor.getZ() + (i < 2 ? -16 + i * 3 : 13 + (i - 2) * 3));
				drone.setEmergencyInterceptForVerification(combatProbe.getId(), combatProbe.position(), 4);
			}
		}

		private void assertMultiWingWallRoutes() {
			require(firingRouteShotUnits.size() >= 4,
				"wall routing blocked part of the multi-Wing laser force: " + firingRouteShotUnits.size());
			require(firingRouteAlternativeUnits.size() >= 4,
				"multi-Wing aircraft did not retain independent alternate routes");
			require(drones.subList(0, 2).stream().allMatch(drone -> drone.groupId().equals("VERIFY-ROUTE-WALL-A")),
				"Wing A identity changed during individual routing");
			require(drones.subList(2, 4).stream().allMatch(drone -> drone.groupId().equals("VERIFY-ROUTE-WALL-B")),
				"Wing B identity changed during individual routing");
		}

		private void armRouteDrones(int count, SecurityLoadout loadout, String group,
			BlockPos start, LivingEntity target) {
			for (int i = 0; i < count; i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.assignSecurityLoadout(loadout);
				drone.assignGroup(group);
				drone.setPowerForVerification(1000, 1000);
				drone.setCombatResourcesForVerification(CombatPolicy.GUN_CAPACITY,
					CombatPolicy.MISSILE_CAPACITY, 0);
				drone.setPos(start.getX() + .5, start.getY() + i * 2.2, start.getZ() + .5 + i * 2.2);
				drone.setEmergencyInterceptForVerification(target.getId(), target.position(), count);
			}
		}

		private LivingEntity routeTarget(BlockPos position) {
			LivingEntity target = durableZombie(position.getX() + .5, position.getY(), position.getZ() + .5);
			target.setInvulnerable(false);
			if (target instanceof Mob mob) mob.setTarget(player);
			firingRouteInitialTargetHealth = target.getHealth();
			return target;
		}

		private void prepareRouteChamber(BlockPos anchor, int halfX, int halfZ, int height,
			boolean sealed) {
			clearVolume(anchor, Math.max(halfX, halfZ) + 8, -2, height + 8);
			for (int x = -halfX; x <= halfX; x++) for (int z = -halfZ; z <= halfZ; z++) {
				level.setBlockAndUpdate(anchor.offset(x, -1, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
				level.setBlockAndUpdate(anchor.offset(x, height, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
			}
			for (int x = -halfX; x <= halfX; x++) for (int y = 0; y <= height; y++) {
				level.setBlockAndUpdate(anchor.offset(x, y, -halfZ), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
				level.setBlockAndUpdate(anchor.offset(x, y, halfZ), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
			}
			for (int z = -halfZ; z <= halfZ; z++) for (int y = 0; y <= height; y++) {
				boolean entrance = !sealed && Math.abs(z) <= 4 && y >= 1 && y <= 8;
				level.setBlockAndUpdate(anchor.offset(-halfX, y, z), entrance
					? Blocks.AIR.defaultBlockState() : Blocks.DEEPSLATE_BRICKS.defaultBlockState());
				level.setBlockAndUpdate(anchor.offset(halfX, y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
			}
		}

		private void assignHighThreatReinforcement() {
			clearHostiles(128);
			FleetThreatNetwork.clear();
			CombatTelemetry.clear();
			maximumHighThreatResponders = 0;
			highThreatExpectedLaserUnits.clear();
			highThreatFiringLaserUnits.clear();
			for (DroneEntity drone : drones) {
				reset(drone);
				drone.assignRole(DroneRole.FIELD);
				drone.setPowerForVerification(1000, 1000);
			}
			for (int i = 0; i < 8; i++) {
				DroneEntity guard = drones.get(i);
				guard.assignRole(DroneRole.SECURITY);
				guard.assignSecurityLoadout(SecurityLoadout.LASER);
				guard.assignGroup("VERIFY-BASE-WING-" + (i / 2));
				double angle = (i / 2) * Math.PI * 0.5 + (i % 2 == 0 ? -0.12 : 0.12);
				guard.setPos(player.getX() + Math.cos(angle) * 25.0, player.getY() + 5.0,
					player.getZ() + Math.sin(angle) * 25.0);
				highThreatExpectedLaserUnits.add(guard.getUUID());
			}
			EntityType<?> wardenType = BuiltInRegistries.ENTITY_TYPE.getValue(
				Identifier.fromNamespaceAndPath("minecraft", "warden"));
			Entity created = wardenType == null ? null
				: wardenType.create(level, EntitySpawnReason.COMMAND);
			if (!(created instanceof Warden warden)) {
				throw new IllegalStateException("could not create Warden probe");
			}
			warden.setPos(player.getX() + 10.0, player.getY(), player.getZ());
			warden.setNoAi(true);
			warden.setInvulnerable(true);
			warden.setTarget(player);
			level.addFreshEntity(warden);
			combatProbe = warden;
		}

		private void observeHighThreatReinforcement() {
			if (!(combatProbe instanceof Warden) || !combatProbe.isAlive()) return;
			int responders = (int)drones.stream().filter(drone -> highThreatExpectedLaserUnits.contains(drone.getUUID())
				&& drone.combatActive() && drone.combatTargetId() == combatProbe.getId()).count();
			maximumHighThreatResponders = Math.max(maximumHighThreatResponders, responders);
			drones.stream().filter(drone -> highThreatExpectedLaserUnits.contains(drone.getUUID())
				&& drone.combatTargetId() == combatProbe.getId()
				&& drone.combatState() == CombatState.LASER_FIRE)
				.map(DroneEntity::getUUID).forEach(highThreatFiringLaserUnits::add);
		}

		private void assertHighThreatReinforcement() {
			for (DroneEntity drone : drones.stream()
				.filter(drone -> highThreatExpectedLaserUnits.contains(drone.getUUID())).toList()) {
				MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] BASE LASER DIAG {} group={} fired={} {}",
					drone.unitId(), drone.groupId(), highThreatFiringLaserUnits.contains(drone.getUUID()),
					drone.laserDiagnosticForVerification(level));
			}
			require(maximumHighThreatResponders >= 8,
				"high-power enemy received too few responders: " + maximumHighThreatResponders);
			require(highThreatFiringLaserUnits.containsAll(highThreatExpectedLaserUnits),
				"base laser aircraft remained floating without firing: expected="
					+ highThreatExpectedLaserUnits.size() + " fired=" + highThreatFiringLaserUnits.size());
		}

		private void assignLinearAutocannonStrike() {
			clearHostiles(160);
			FleetThreatNetwork.clear();
			CombatTelemetry.clear();
			prepareIsolatedCombatFleet();
			BlockPos anchor = base.offset(72, 1, 0);
			for (int x = -28; x <= 28; x++) for (int z = -28; z <= 28; z++) {
				level.setBlockAndUpdate(anchor.offset(x, -1, z), (Math.floorMod(x + z, 3) == 0
					? Blocks.STONE : Blocks.DIRT).defaultBlockState());
				for (int y = 0; y <= 18; y++) level.setBlockAndUpdate(anchor.offset(x, y, z), Blocks.AIR.defaultBlockState());
			}
			casImpactBlocksBefore = countCasImpactBlocks(anchor);
			DroneEntity gunner = drones.get(0);
			reset(gunner);
			gunner.assignRole(DroneRole.SECURITY);
			gunner.assignSecurityLoadout(SecurityLoadout.AUTOCANNON);
			gunner.assignGroup("VERIFY-CAS-LINE");
			gunner.setPowerForVerification(1000, 1000);
			gunner.setPos(anchor.getX() - 54.0, anchor.getY() + 16.0, anchor.getZ());
			gunner.assignSecurityPatrol(anchor, 20, "verify-cas-line");
			LivingEntity target = spawnZombie(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
			target.setInvulnerable(false);
			if (target.getAttribute(Attributes.MAX_HEALTH) != null) {
				target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(3000.0);
				target.setHealth(3000.0f);
			}
			// Threat acquisition is covered separately. Keep this target committed so
			// this case measures the full CAS route, firing, power draw, and impacts.
			gunner.setEmergencyInterceptForVerification(target.getId(), target.position(), 1);
			combatProbe = target;
			casInitialFlightPower = gunner.batteryPercent();
			casInitialWeaponPower = gunner.weaponPowerPercent();
			casInitialAuxiliaryPower = gunner.auxiliaryPowerPercent();
			casMinimumFlightPower = casInitialFlightPower;
			casMinimumWeaponPower = casInitialWeaponPower;
			casMinimumAuxiliaryPower = casInitialAuxiliaryPower;
			casMaximumRange = 0.0;
			casMinimumHeight = Double.POSITIVE_INFINITY;
			casMaximumHeight = Double.NEGATIVE_INFINITY;
			casLastObservedShotTick = Long.MIN_VALUE;
			casObservedShots = 0;
			casRearwardShotObserved = false;
			gunner.resetCasDiagnosticsForVerification();
		}

		private void observeLinearAutocannonStrike() {
			if (combatProbe == null || !combatProbe.isAlive()) return;
			DroneEntity gunner = drones.stream().filter(drone -> drone.groupId().equals("VERIFY-CAS-LINE"))
				.findFirst().orElse(null);
			if (gunner == null || gunner.combatWeapon() != CombatWeapon.AUTOCANNON) return;
			casMinimumFlightPower = Math.min(casMinimumFlightPower, gunner.batteryPercent());
			casMinimumWeaponPower = Math.min(casMinimumWeaponPower, gunner.weaponPowerPercent());
			casMinimumAuxiliaryPower = Math.min(casMinimumAuxiliaryPower, gunner.auxiliaryPowerPercent());
			casMaximumRange = Math.max(casMaximumRange, gunner.position().multiply(1, 0, 1)
				.distanceTo(combatProbe.position().multiply(1, 0, 1)));
			double relativeY = gunner.getY() - combatProbe.getY();
			casMinimumHeight = Math.min(casMinimumHeight, relativeY);
			casMaximumHeight = Math.max(casMaximumHeight, relativeY);
			if (gunner.combatShotTick() != casLastObservedShotTick && gunner.combatShotAge() <= 1) {
				casLastObservedShotTick = gunner.combatShotTick();
				casObservedShots++;
				Vec3 muzzle = gunner.position();
				Vec3 aim = muzzle.add(gunner.combatVisualAim());
				if (!CombatPolicy.actualMotionFacesShot(muzzle, gunner.getDeltaMovement(), aim)) {
					casRearwardShotObserved = true;
				}
			}
		}

		private void assertLinearAutocannonStrike() {
			BlockPos anchor = base.offset(72, 1, 0);
			casImpactBlocksAfter = countCasImpactBlocks(anchor);
			DroneEntity gunner = drones.stream().filter(drone -> drone.groupId().equals("VERIFY-CAS-LINE"))
				.findFirst().orElse(null);
			if (gunner != null) MorrowgearDrone.LOGGER.info(
				"[MORROWGEAR VERIFY] CAS GATE DIAG {}", gunner.casDiagnosticForVerification());
			require(casMaximumRange >= 42.0, "CAS did not use the extended approach: " + casMaximumRange);
			require(casMaximumHeight - casMinimumHeight >= 8.0,
				"CAS did not descend and climb through the strike: " + casMinimumHeight + ".." + casMaximumHeight);
			require(casImpactBlocksAfter < casImpactBlocksBefore,
				"linear autocannon impacts did not produce bounded terrain damage");
			require(casMinimumAuxiliaryPower < casInitialAuxiliaryPower,
				"auxiliary firing bus did not drain during autocannon fire");
			require(casMinimumWeaponPower == casInitialWeaponPower,
				"autocannon incorrectly consumed the independent laser energy store");
			require(casObservedShots > 0, "CAS produced no observable forward gun burst");
			require(!casRearwardShotObserved, "autocannon fired outside its forward CAS cone");
			require(casMinimumFlightPower > 0,
				"flight reserve was exhausted while weapon power remained independently available");
			require(casMinimumFlightPower != casMinimumAuxiliaryPower,
				"flight and auxiliary firing reserves did not evolve independently");
		}

		private void prepareIsolatedCombatFleet() {
			CombatTelemetry.clear();
			CombatManeuverRegistry.clear();
			CombatAirspaceRegistry.clear();
			for (DroneEntity drone : aliveDrones()) {
				reset(drone);
				drone.assignRole(DroneRole.FIELD);
				drone.setPowerForVerification(1000, 1000);
			}
		}

		private int countCasImpactBlocks(BlockPos anchor) {
			int count = 0;
			for (int x = -28; x <= 28; x++) for (int z = -28; z <= 28; z++) {
				if (!level.getBlockState(anchor.offset(x, -1, z)).isAir()) count++;
			}
			return count;
		}

		private void observeLaserFormation() {
			List<DroneEntity> formation = drones.stream()
				.filter(drone -> drone.groupId().equals("VERIFY-LASER-FORMATION")).toList();
			if (formation.isEmpty() || combatProbe == null || !combatProbe.isAlive()) return;
			for (DroneEntity drone : formation) {
				if (drone.combatActive()) laserFormationEngaged.add(drone.unitId());
				else if (laserFormationEngaged.contains(drone.unitId())) laserCombatDropped = true;
				if (drone.combatState() == CombatState.LASER_FIRE
					&& !drone.laserIngressForVerification(level)) {
					double angle = Math.atan2(drone.getZ() - combatProbe.getZ(),
						drone.getX() - combatProbe.getX());
					Double previous = laserFormationLastAngle.put(drone.unitId(), angle);
					if (previous != null) {
						double delta = Math.atan2(Math.sin(angle - previous), Math.cos(angle - previous));
						if (delta > 0.0) laserFormationOrbitTravel.merge(drone.unitId(), delta, Double::sum);
					}
				}
			}
			List<DroneEntity> firing = formation.stream()
				.filter(drone -> drone.combatState() == CombatState.LASER_FIRE).toList();
			simultaneousLaserFire = Math.max(simultaneousLaserFire, firing.size());
			if (firing.size() != formation.size() || firing.size() < 2) return;
			double error = CombatPolicy.laserFormationGapError(
				firing.stream().map(DroneEntity::position).toList(), combatProbe.position());
			bestLaserGapError = Math.min(bestLaserGapError, error);
			laserSpacingStableTicks = error <= CombatPolicy.LASER_FORMATION_MAX_GAP_ERROR
				? laserSpacingStableTicks + 1 : 0;
			maxLaserSpacingStableTicks = Math.max(maxLaserSpacingStableTicks,
				laserSpacingStableTicks);
		}

		private void assertMixedCombat() {
			if (!observedCombatStates.contains(CombatState.LASER_FIRE)) {
				for (DroneEntity drone : drones.stream().filter(candidate ->
					candidate.securityLoadout() == SecurityLoadout.LASER
					|| candidate.combatWeapon() == CombatWeapon.LASER).toList()) {
					MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] MIXED LASER DIAG {} {}",
						drone.unitId(), drone.laserDiagnosticForVerification(level));
				}
			}
			require(observedCombatStates.contains(CombatState.FLARE_ENTRY),
				"combat entry flare state was not observed");
			require(observedCombatStates.contains(CombatState.GUN_RUN),
				"autocannon run was not observed");
			require(observedCombatStates.contains(CombatState.LASER_CHARGE),
				"laser charge was not observed");
			require(observedCombatStates.contains(CombatState.LASER_FIRE),
				"laser fire was not observed");
			require(observedCombatStates.contains(CombatState.MISSILE_APPROACH),
				"missile approach was not observed");
			require(observedCombatWeapons.containsAll(List.of(
				CombatWeapon.AUTOCANNON, CombatWeapon.LASER, CombatWeapon.MISSILE)),
				"mixed weapon allocation missing: " + observedCombatWeapons);
			require(observedCombatShot, "no synchronized combat shot pulse was observed");
			require(laserOrbitSamples >= 20, "laser orbit did not produce enough stability samples");
			require(maxLaserOrbitSpeed <= CombatPolicy.laserFlightSpeed(6.0, false) + 0.02,
				"laser orbit exceeded dedicated speed limit: " + maxLaserOrbitSpeed);
			require(combatProbe == null || !combatProbe.isAlive() || combatProbe.getHealth() < 1200.0f,
				"combat weapons did not damage the target");
		}

		private void assignLaserFormation() {
			clearHostiles(64);
			FleetThreatNetwork.clear();
			prepareIsolatedCombatFleet();
			simultaneousLaserFire = 0;
			bestLaserGapError = Double.POSITIVE_INFINITY;
			laserSpacingStableTicks = 0;
			maxLaserSpacingStableTicks = 0;
			laserCombatDropped = false;
			laserFormationEngaged.clear();
			laserFormationLastAngle.clear();
			laserFormationOrbitTravel.clear();
			BlockPos anchor = base.offset(12, 1, 4);
			for (int i = 0; i < 4; i++) {
				DroneEntity drone = drones.get(4 + i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.setPowerForVerification(1000, 1000);
				drone.assignSecurityLoadout(SecurityLoadout.LASER);
				drone.assignGroup("VERIFY-LASER-FORMATION");
				drone.setPos(anchor.getX() - 10.0 + i * 1.4, anchor.getY() + 3.0,
					anchor.getZ() - 7.0);
				drone.assignSecurityPatrol(anchor, 16, "verify-laser-formation");
			}
			LivingEntity target = spawnZombie(anchor.getX() + 0.5, anchor.getY() + 1.0,
				anchor.getZ() + 0.5);
			if (target.getAttribute(Attributes.MAX_HEALTH) != null) {
				target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(2400.0);
				target.setHealth(2400.0f);
			}
			combatProbe = target;
			// This case measures formation convergence, not autonomous contact latency.
			// Commit the four-aircraft element in the same setup tick so every member
			// receives the same target and requested element size from the outset.
			for (int i = 0; i < 4; i++) {
				drones.get(4 + i).setEmergencyInterceptForVerification(
					target.getId(), target.position(), 4);
			}
		}

		private void assertLaserFormation() {
			require(simultaneousLaserFire == 4,
				"laser formation did not reach four-aircraft simultaneous fire: " + simultaneousLaserFire);
			require(bestLaserGapError <= CombatPolicy.LASER_FORMATION_MAX_GAP_ERROR,
				"laser formation angular spacing remained uneven: " + bestLaserGapError);
			int requiredStableTicks = Math.max(1, CombatPolicy.LASER_SYNC_WINDOW_TICKS - 4);
			require(maxLaserSpacingStableTicks >= requiredStableTicks,
				"laser formation did not sustain even spacing through the synchronization window: "
					+ maxLaserSpacingStableTicks);
			require(!laserCombatDropped,
				"laser combat lock dropped while the assigned target remained alive");
			require(laserFormationOrbitTravel.size() == 4,
				"not every laser aircraft established a measurable firing orbit: "
					+ laserFormationOrbitTravel);
			double minimumTravel = laserFormationOrbitTravel.values().stream()
				.mapToDouble(Double::doubleValue).min().orElse(0.0);
			require(minimumTravel >= 1.5,
				"laser formation held a static slot instead of orbiting: minimum angular travel="
					+ minimumTravel);
		}

		private void assignCombatAirspace() {
			clearHostiles(64);
			FleetThreatNetwork.clear();
			prepareIsolatedCombatFleet();
			airspaceSeparationTicks = 0;
			maxAirspaceSeparationTicks = 0;
			bestAirspaceMinimumDistance = 0.0;
			bestAirspaceAltitudeGap = 0.0;
			observedAirspaceGroups.clear();
			BlockPos anchor = base.offset(12, 1, 4);
			for (int i = 0; i < 8; i++) {
				DroneEntity drone = drones.get(8 + i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.setPowerForVerification(1000, 1000);
				int element = i / 2;
				drone.assignSecurityLoadout(element < 2
					? SecurityLoadout.LASER : SecurityLoadout.AUTOCANNON);
				drone.assignGroup("VERIFY-AIRSPACE-" + element);
				drone.setPos(anchor.getX() - 13.0 + i * 1.7, anchor.getY() + 3.0,
					anchor.getZ() - 9.0);
				drone.assignSecurityPatrol(anchor, 18, "verify-airspace-" + element);
			}
			LivingEntity target = spawnZombie(anchor.getX() + 0.5, anchor.getY() + 1.0,
				anchor.getZ() + 0.5);
			if (target.getAttribute(Attributes.MAX_HEALTH) != null) {
				target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(6000.0);
				target.setHealth(6000.0f);
			}
			combatProbe = target;
		}

		private void observeCombatAirspace() {
			if (combatProbe == null || !combatProbe.isAlive()) return;
			List<DroneEntity> active = drones.stream()
				.filter(drone -> drone.groupId().startsWith("VERIFY-AIRSPACE-")
					&& drone.combatState().controlsFlight()
					&& drone.combatState() != CombatState.REJOIN).toList();
			active.forEach(drone -> observedAirspaceGroups.add(drone.groupId()));
			if (observedAirspaceGroups.size() < 4 || active.size() < 8) {
				airspaceSeparationTicks = 0;
				return;
			}
			double minimumDistance = Double.POSITIVE_INFINITY;
			double minimumAltitudeGap = Double.POSITIVE_INFINITY;
			for (int left = 0; left < active.size(); left++) {
				for (int right = left + 1; right < active.size(); right++) {
					DroneEntity a = active.get(left);
					DroneEntity b = active.get(right);
					if (a.groupId().equals(b.groupId())) continue;
					minimumDistance = Math.min(minimumDistance, a.position().distanceTo(b.position()));
				}
			}
			for (int left = 0; left < 4; left++) {
				double leftY = meanAirspaceAltitude(active, "VERIFY-AIRSPACE-" + left);
				for (int right = left + 1; right < 4; right++) {
					double rightY = meanAirspaceAltitude(active, "VERIFY-AIRSPACE-" + right);
					minimumAltitudeGap = Math.min(minimumAltitudeGap, Math.abs(leftY - rightY));
				}
			}
			bestAirspaceMinimumDistance = Math.max(bestAirspaceMinimumDistance, minimumDistance);
			bestAirspaceAltitudeGap = Math.max(bestAirspaceAltitudeGap, minimumAltitudeGap);
			boolean separated = minimumDistance >= 1.6;
			airspaceSeparationTicks = separated ? airspaceSeparationTicks + 1 : 0;
			maxAirspaceSeparationTicks = Math.max(maxAirspaceSeparationTicks, airspaceSeparationTicks);
		}

		private double meanAirspaceAltitude(List<DroneEntity> active, String group) {
			return active.stream().filter(drone -> drone.groupId().equals(group))
				.mapToDouble(DroneEntity::getY).average().orElse(Double.NaN);
		}

		private void assertCombatAirspace() {
			require(observedAirspaceGroups.size() == 4,
				"not all laser/CAS temporary groups entered combat: " + observedAirspaceGroups);
			require(maxAirspaceSeparationTicks >= 40,
				"temporary groups did not sustain separated airspace for 40 ticks: "
					+ maxAirspaceSeparationTicks + " distance=" + bestAirspaceMinimumDistance
					+ " altitude=" + bestAirspaceAltitudeGap);
		}

		private void resetTriggerObservation() {
			triggerInterceptObserved = false;
			triggerCombatObserved = false;
			triggerCorrectTargetObserved = false;
		}

		private void assignPlayerDangerTrigger() {
			clearHostiles(96);
			FleetThreatNetwork.clear();
			prepareIsolatedCombatFleet();
			resetTriggerObservation();
			for (int i = 0; i < 3; i++) {
				DroneEntity guard = drones.get(16 + i);
				reset(guard);
				guard.assignRole(DroneRole.SECURITY);
				guard.assignSecurityLoadout(SecurityLoadout.AUTO);
				guard.setCombatResourcesForVerification(CombatPolicy.GUN_CAPACITY,
					CombatPolicy.MISSILE_CAPACITY, 0);
				guard.assignGroup("VERIFY-TRIGGER-PLAYER");
				guard.setPos(player.getX() - 5.0 + i * 2.0, player.getY() + 4.0, player.getZ() - 5.0);
			}
			LivingEntity target = durableZombie(player.getX() + 8.0, player.getY(), player.getZ());
			if (target instanceof Mob mob) mob.setTarget(player);
			combatProbe = target;
		}

		private void assignScoutThreatTrigger() {
			clearHostiles(96);
			FleetThreatNetwork.clear();
			prepareIsolatedCombatFleet();
			resetTriggerObservation();
			BlockPos scoutAnchor = base.offset(38, 1, 0);
			DroneEntity scout = drones.get(19);
			reset(scout);
			scout.assignRole(DroneRole.SCOUT);
			scout.assignGroup("VERIFY-TRIGGER-SCOUT");
			scout.setPos(scoutAnchor.getX() - 3.0, scoutAnchor.getY() + 5.0, scoutAnchor.getZ());
			scout.assignPatrolRoute(List.of(scoutAnchor, scoutAnchor.offset(0, 0, 8)),
				"verify-trigger-scout", 1, 0, scoutAnchor, level.getGameTime());
			for (int i = 0; i < 3; i++) {
				DroneEntity guard = drones.get(20 + i);
				reset(guard);
				guard.assignRole(DroneRole.SECURITY);
				guard.assignSecurityLoadout(SecurityLoadout.AUTO);
				guard.setCombatResourcesForVerification(CombatPolicy.GUN_CAPACITY,
					CombatPolicy.MISSILE_CAPACITY, 0);
				guard.assignGroup("VERIFY-TRIGGER-SCOUT");
				guard.setPos(scoutAnchor.getX() - 10.0, scoutAnchor.getY() + 4.0,
					scoutAnchor.getZ() - 3.0 + i * 2.0);
			}
			combatProbe = durableZombie(scoutAnchor.getX() + 3.5, scoutAnchor.getY(), scoutAnchor.getZ() + 2.5);
		}

		private void assignFieldGuardTrigger() {
			clearHostiles(96);
			FleetThreatNetwork.clear();
			prepareIsolatedCombatFleet();
			resetTriggerObservation();
			// Reserve Security aircraft are deliberately preferred over a Guard already
			// committed to field work. Make this the fallback case by removing reserves.
			BlockPos anchor = base.offset(38, 1, -10);
			String order = "verify-trigger-field-" + level.getGameTime();
			DroneEntity guard = drones.get(23);
			reset(guard);
			guard.assignRole(DroneRole.SECURITY);
			guard.assignSecurityLoadout(SecurityLoadout.AUTO);
			guard.setCombatResourcesForVerification(CombatPolicy.GUN_CAPACITY,
				CombatPolicy.MISSILE_CAPACITY, 0);
			guard.assignGroup("VERIFY-TRIGGER-FIELD");
			guard.setPos(anchor.getX() - 4.0, anchor.getY() + 5.0, anchor.getZ());
			guard.assignFieldOperation(FieldOperationType.EXCAVATE, anchor, 6, order);
			DroneEntity engineer = drones.get(16);
			reset(engineer);
			engineer.assignRole(DroneRole.ENGINEER);
			engineer.assignGroup("VERIFY-TRIGGER-FIELD");
			engineer.setPos(anchor.getX() + 4.0, anchor.getY() + 4.0, anchor.getZ());
			engineer.assignFieldOperation(FieldOperationType.EXCAVATE, anchor, 6, order);
			for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
				level.setBlockAndUpdate(anchor.offset(x, 0, z), Blocks.STONE.defaultBlockState());
			}
			combatProbe = durableZombie(anchor.getX() + 2.5, anchor.getY() + 1.0, anchor.getZ() + 2.5);
		}

		private LivingEntity durableZombie(double x, double y, double z) {
			LivingEntity target = spawnZombie(x, y, z);
			if (target.getAttribute(Attributes.MAX_HEALTH) != null) {
				target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(2400.0);
				target.setHealth(2400.0f);
			}
			return target;
		}

		private void observeCombatTrigger() {
			if (combatProbe == null || !combatProbe.isAlive()) return;
			if (drones.stream().anyMatch(drone -> drone.groupId().startsWith("VERIFY-TRIGGER-")))
				clearHostilesExcept(player.blockPosition(), 96, combatProbe);
			for (DroneEntity drone : drones) {
				if (!drone.groupId().startsWith("VERIFY-TRIGGER-")) continue;
				triggerInterceptObserved |= drone.emergencyInterceptActive();
				triggerCombatObserved |= drone.combatActive();
				triggerCorrectTargetObserved |= drone.combatActive()
					&& drone.combatTargetId() == combatProbe.getId();
			}
		}

		private void assertTriggerCase(String label, boolean emergencyExpected) {
			if ((emergencyExpected && !triggerInterceptObserved) || !triggerCombatObserved
				|| !triggerCorrectTargetObserved) {
				logCombatDiagnostics("TRIGGER-" + label, aliveDrones());
			}
			if (emergencyExpected) require(triggerInterceptObserved,
				label + " did not dispatch an emergency interceptor");
			require(triggerCombatObserved, label + " did not enter COMBAT");
			require(triggerCorrectTargetObserved, label + " attacked a different or missing target");
		}

		private void logCombatDiagnostics(String label, List<DroneEntity> fleet) {
			List<FleetThreatNetwork.Snapshot> reports = FleetThreatNetwork.readAll(
				level.dimension().toString(), player.getUUID(), level.getGameTime());
			CombatTheaterCoordinator.Snapshot theater = CombatTheaterCoordinator.coordinate(
				level, player, MorrowgearDrone.ownedDrones(level, player, 512));
			MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] {} DIAG target={}:{} pos={} disposition={} reports={} assignments={}",
				label, combatProbe == null ? -1 : combatProbe.getId(),
				combatProbe != null && combatProbe.isAlive(),
				combatProbe == null ? null : combatProbe.position(),
				combatProbe == null ? null : ThreatAssessment.targetDisposition(combatProbe, player,
					player.getLastHurtByMob()),
				reports.stream().map(report -> report.entityId() + ":" + report.score()).toList(),
				theater.assignments().values().stream().map(assignment ->
					assignment.unitId() + "->" + assignment.targetId()).toList());
			for (DroneEntity drone : fleet.stream().filter(DroneEntity::isAlive)
				.filter(drone -> drone.role() == DroneRole.SECURITY).toList()) {
				MorrowgearDrone.LOGGER.info(
					"[MORROWGEAR VERIFY] {} UNIT {} group={} pos={} mode={} field={} patrol={} contacts={} loadout={} emergency={}:{} combat={}:{} state={} flight={} weapon={}",
					label, drone.unitId(), drone.groupId(), drone.position(), drone.mode(), drone.hasActiveFieldOperation(),
					drone.hasSecurityPatrol(), drone.securityContacts(), drone.securityLoadout(),
					drone.emergencyInterceptActive(),
					drone.emergencyTargetIdForVerification(), drone.combatActive(), drone.combatTargetId(),
					drone.combatState(), drone.batteryPercent(), drone.weaponPowerPercent());
			}
		}

		private void assignScoutSolo(FieldOperationType type, int droneIndex) {
			BlockPos anchor = fieldAnchor(type);
			clearFlightBubble(anchor, 7);
			prepareFieldBlocks();
			DroneEntity scout = drones.get(droneIndex);
			reset(scout);
			scout.assignRole(DroneRole.SCOUT);
			scout.assignGroup("WING-SCOUT-" + type.id().toUpperCase());
			scout.setPos(anchor.getX() + 0.5, anchor.getY() + 6.5, anchor.getZ() + 0.5);
			scout.assignFieldOperation(type, anchor, 12, "verify-scout-" + type.id() + "-" + level.getGameTime());
		}

		private void assertScoutSolo(FieldOperationType type, int droneIndex) {
			DroneEntity scout = drones.get(droneIndex);
			require(scout.role() == DroneRole.SCOUT, type + " scout role mismatch");
			require(scout.fieldOperationType() == type, type + " scout operation mismatch");
			require(scout.fieldOperationState() != FieldOperationState.BLOCKED,
				type + " scout blocked: " + scout.fieldStatusLabel());
			require(scout.fieldProgress() > 0, type + " scout did not scan");
			require(scout.fieldFound() > 0, type + " scout found no targets");
		}

		private void assignEngineerSoloForestry() {
			forestryCutBeforeScanComplete = false;
			forestryLowerBlockRemovedBeforeUpper = false;
			BlockPos anchor = fieldAnchor(FieldOperationType.FORESTRY);
			clearFlightBubble(anchor, 7);
			prepareFieldBlocks();
			player.getInventory().add(new ItemStack(Items.OAK_SAPLING, 1));
			DroneEntity engineer = drones.get(7);
			reset(engineer);
			engineer.assignRole(DroneRole.ENGINEER);
			engineer.assignGroup("WING-ENGINEER-SOLO");
			engineer.setPos(anchor.getX() + 2.5, anchor.getY() + 3.0, anchor.getZ() + 0.5);
			engineer.assignFieldOperation(FieldOperationType.FORESTRY, anchor, 12,
				"verify-engineer-forestry-" + level.getGameTime());
		}

		private void assertEngineerSoloForestry() {
			BlockPos anchor = fieldAnchor(FieldOperationType.FORESTRY);
			DroneEntity engineer = drones.get(7);
			require(engineer.fieldOperationState() != FieldOperationState.BLOCKED,
				"engineer solo forestry blocked: " + engineer.fieldStatusLabel());
			require(engineer.fieldProgress() > 0,
				"engineer solo forestry scan did not start: " + engineer.fieldStatusLabel());
			require(!forestryCutBeforeScanComplete,
				"engineer started cutting before the overhead survey completed");
			require(!forestryLowerBlockRemovedBeforeUpper,
				"engineer removed the lower trunk while upper tree blocks remained");
			require(level.getBlockState(anchor).is(Blocks.OAK_SAPLING),
				"engineer solo forestry did not replace the felled trunk with a sapling");
			for (int y = 1; y < 4; y++) require(!level.getBlockState(anchor.above(y)).is(BlockTags.LOGS),
				"engineer solo forestry left trunk at y+" + y);
			require(level.getBlockState(anchor.offset(3, 0, 0)).isAir(),
				"engineer solo forestry left scanned surface vegetation");
		}

		private boolean engineerSoloForestryComplete() {
			BlockPos anchor = fieldAnchor(FieldOperationType.FORESTRY);
			DroneEntity engineer = drones.get(7);
			return level.getBlockState(anchor).is(Blocks.OAK_SAPLING)
				&& !level.getBlockState(anchor.above(1)).is(BlockTags.LOGS)
				&& !level.getBlockState(anchor.above(2)).is(BlockTags.LOGS)
				&& !level.getBlockState(anchor.above(3)).is(BlockTags.LOGS)
				&& level.getBlockState(anchor.offset(3, 0, 0)).isAir()
				&& engineer.fieldOperationState() != FieldOperationState.BLOCKED;
		}

		private void assignEngineerSnowfieldOre() {
			BlockPos anchor = fieldAnchor(FieldOperationType.ORE);
			clearVolume(anchor, 12, -16, 8);
			for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
				level.setBlockAndUpdate(anchor.offset(x, -1, z), Blocks.STONE.defaultBlockState());
				level.setBlockAndUpdate(anchor.offset(x, 0, z), Blocks.SNOW.defaultBlockState());
			}
			level.setBlockAndUpdate(snowfieldOreTarget(), Blocks.DIAMOND_ORE.defaultBlockState());
			DroneEntity engineer = drones.get(7);
			reset(engineer);
			engineer.assignRole(DroneRole.ENGINEER);
			engineer.assignGroup("WING-ENGINEER-SNOWFIELD");
			engineer.setPos(anchor.getX() + 0.5, anchor.getY() + 4.0, anchor.getZ() + 0.5);
			engineer.assignFieldOperation(FieldOperationType.ORE, anchor, 12,
				"verify-engineer-snowfield-" + level.getGameTime());
		}

		private boolean engineerSnowfieldOreComplete() {
			DroneEntity engineer = drones.get(7);
			return level.getBlockState(snowfieldOreTarget()).isAir()
				&& !engineer.hasActiveFieldOperation()
				&& engineer.fieldOperationState() == FieldOperationState.COMPLETE;
		}

		private void assertEngineerSnowfieldOre() {
			DroneEntity engineer = drones.get(7);
			require(level.getBlockState(snowfieldOreTarget()).isAir(),
				"snowfield operation ended before reaching the sparse ore");
			require(engineer.fieldOperationState() != FieldOperationState.BLOCKED,
				"snowfield engineer blocked: " + engineer.fieldStatusLabel());
			require(!engineer.hasActiveFieldOperation()
					&& engineer.fieldOperationState() == FieldOperationState.COMPLETE,
				"snowfield operation did not complete after the full survey");
		}

		private BlockPos snowfieldOreTarget() {
			return fieldAnchor(FieldOperationType.ORE).offset(10, -1, 0);
		}

		private void assignCargoRoute() {
			for (int i = 0; i < Math.min(3, drones.size()); i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.CARGO);
				drone.assignCargoSource(cargoSource);
				drone.assignCargoTarget(cargoTarget);
			}
		}

		private void assertCargoRoute() {
			int targetItems = countContainer(cargoTarget);
			boolean activeCargo = false;
			for (int i = 0; i < Math.min(3, drones.size()); i++) {
				DroneEntity drone = drones.get(i);
				activeCargo |= drone.cargoState() != CargoState.UNASSIGNED || drone.cargoItemCount() > 0;
				require(drone.role() == DroneRole.CARGO, drone.unitId() + " lost cargo role");
				require(!drone.cargoPaused(), drone.unitId() + " cargo unexpectedly paused");
			}
			require(activeCargo || targetItems > 0, "cargo route never became active");
		}

		private void assignCargoFullTarget() {
			for (int i = 0; i < Math.min(3, drones.size()); i++) {
				DroneEntity previousCargo = drones.get(i);
				previousCargo.pauseCargoRoute();
				previousCargo.removeAllCargo();
			}
			placeChest(cargoSource, new ItemStack(Items.IRON_INGOT, 64));
			placeChest(cargoTarget, ItemStack.EMPTY);
			if (level.getBlockEntity(cargoTarget) instanceof Container target) {
				for (int slot = 0; slot < target.getContainerSize(); slot++) {
					target.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
				}
			}
			DroneEntity cargo = drones.getFirst();
			reset(cargo);
			cargo.assignRole(DroneRole.CARGO);
			cargo.setPos(cargoSource.getX() + 0.5, cargoSource.getY() + 2.5, cargoSource.getZ() + 0.5);
			cargo.assignCargoSource(cargoSource);
			cargo.assignCargoTarget(cargoTarget);
			cargoConservationTotal = countContainer(cargoSource) + countContainer(cargoTarget) + cargo.cargoItemCount();
		}

		private void assertCargoConservation() {
			DroneEntity cargo = drones.getFirst();
			int actual = countContainer(cargoSource) + countContainer(cargoTarget) + cargo.cargoItemCount();
			require(actual == cargoConservationTotal,
				"cargo item total changed " + cargoConservationTotal + " -> " + actual);
			require(cargo.cargoItemCount() > 0 || countContainer(cargoSource) > 0,
				"cargo items disappeared at full target");
		}

		private void assignSpecialContainerCargo() {
			specialContainers.clear();
			BlockPos barrelSource = base.offset(-14, 1, -10);
			BlockPos hopperTarget = base.offset(-14, 1, -4);
			BlockPos shulkerSource = base.offset(-18, 1, -10);
			BlockPos barrelTarget = base.offset(-18, 1, -4);
			placeContainer(barrelSource, Blocks.BARREL, new ItemStack(Items.GOLD_INGOT, 32));
			placeContainer(hopperTarget, Blocks.HOPPER, ItemStack.EMPTY);
			placeContainer(shulkerSource, Blocks.SHULKER_BOX, new ItemStack(Items.REDSTONE, 48));
			placeContainer(barrelTarget, Blocks.BARREL, ItemStack.EMPTY);
			specialContainers.addAll(List.of(barrelSource, hopperTarget, shulkerSource, barrelTarget));
			specialContainerTotal = specialContainers.stream().mapToInt(this::countContainer).sum();
			for (int i = 0; i < 2; i++) {
				DroneEntity cargo = drones.get(i);
				reset(cargo);
				cargo.removeAllCargo();
				cargo.assignRole(DroneRole.CARGO);
				BlockPos source = i == 0 ? barrelSource : shulkerSource;
				BlockPos target = i == 0 ? hopperTarget : barrelTarget;
				cargo.setPos(source.getX() + 0.5, source.getY() + 2.5, source.getZ() + 0.5);
				cargo.assignCargoSource(source);
				cargo.assignCargoTarget(target);
			}
		}

		private void assertSpecialContainerCargo() {
			int carried = drones.get(0).cargoItemCount() + drones.get(1).cargoItemCount();
			int stored = specialContainers.stream().mapToInt(this::countContainer).sum();
			require(stored + carried == specialContainerTotal,
				"special container item total changed " + specialContainerTotal + " -> " + (stored + carried));
			require(countContainer(specialContainers.get(1)) > 0 || countContainer(specialContainers.get(3)) > 0,
				"special container routes delivered no items");
		}

		private void assignMissionInterruptions() {
			DroneEntity cargo = drones.getFirst();
			reset(cargo);
			cargo.assignRole(DroneRole.CARGO);
			cargo.assignCargoSource(cargoSource);
			cargo.assignCargoTarget(cargoTarget);
			cargo.pauseCargoRoute();
			cargo.assignWaypoint(base.offset(12, 1, 0), "verify-interrupt-move", 1, 0, base, level.getGameTime());
			DroneEntity engineer = drones.get(1);
			reset(engineer);
			engineer.assignRole(DroneRole.ENGINEER);
			engineer.assignFieldOperation(FieldOperationType.EXCAVATE, base, 4, "verify-interrupt-field");
			engineer.assignFollowFormation("verify-interrupt-follow", 1, 0, engineer.unitId(), base, level.getGameTime());
		}

		private void assertMissionInterruptions() {
			DroneEntity cargo = drones.getFirst();
			require(cargo.cargoPaused() && cargo.cargoState() == CargoState.UNASSIGNED,
				"cargo route remained active after movement preemption");
			require(cargo.mode() == DroneMode.WAYPOINT, "cargo movement mission not assigned");
			DroneEntity engineer = drones.get(1);
			require(!engineer.hasFieldOperation(), "field operation remained after follow assignment");
			require(engineer.mode() == DroneMode.FOLLOW, "follow mission not assigned after field operation");
		}

		private void assignRapidCommandSupersession() {
			DroneEntity drone = drones.get(3);
			reset(drone);
			drone.assignRole(DroneRole.CARGO);
			drone.assignCargoSource(cargoSource);
			drone.assignCargoTarget(cargoTarget);
			drone.pauseCargoRoute();
			drone.assignFieldOperation(FieldOperationType.ORE, fieldAnchor(FieldOperationType.ORE), 12,
				"verify-rapid-field");
			drone.assignFollowFormation("verify-rapid-follow", 4, 3, drone.unitId(), base, level.getGameTime());
			drone.assignWaypoint(base.offset(20, 0, 0), "verify-rapid-final", 1, 0, base, level.getGameTime());
		}

		private void assertRapidCommandSupersession() {
			DroneEntity drone = drones.get(3);
			require(drone.mode() == DroneMode.WAYPOINT, "last valid command did not win");
			require(drone.missionId().equals("verify-rapid-final"), "stale mission remained " + drone.missionId());
			require(!drone.hasFieldOperation(), "stale field operation remained");
			require(!drone.hasSecurityPatrol(), "stale security operation remained");
			require(drone.cargoPaused() && drone.cargoState() == CargoState.UNASSIGNED,
				"preempted cargo route remained active");
		}

		private void assignFieldOperation(FieldOperationType type) {
			BlockPos anchor = fieldAnchor(type);
			clearFlightBubble(anchor, 7);
			prepareFieldBlocks();
			placeChest(cargoTarget, ItemStack.EMPTY);
			String order = "verify-field-" + type.id() + "-" + level.getGameTime();
			DroneEntity scout = drones.get(0);
			DroneEntity engineer = drones.get(1);
			DroneEntity cargo = drones.get(2);
			DroneEntity guard = drones.get(3);
			reset(scout);
			reset(engineer);
			reset(cargo);
			reset(guard);
			scout.assignRole(DroneRole.SCOUT);
			engineer.assignRole(DroneRole.ENGINEER);
			cargo.assignRole(DroneRole.CARGO);
			guard.assignRole(DroneRole.SECURITY);
			scout.setPos(anchor.getX() + 0.5, anchor.getY() + 6.5, anchor.getZ() + 0.5);
			engineer.setPos(anchor.getX() + 2.5, anchor.getY() + 3.0, anchor.getZ() + 0.5);
			cargo.setPos(anchor.getX() + 3.5, anchor.getY() + 3.0, anchor.getZ() + 0.5);
			guard.setPos(anchor.getX() - 3.5, anchor.getY() + 5.0, anchor.getZ() + 0.5);
			cargo.assignCargoTarget(cargoTarget);
			int radius = verifierFieldRadius(type);
			scout.assignFieldOperation(type, anchor, radius, order);
			engineer.assignFieldOperation(type, anchor, radius, order);
			cargo.assignFieldOperation(type, anchor, radius, order);
			guard.assignFieldOperation(type, anchor, radius, order);
			require(!scout.isDocked() && !engineer.isDocked() && !cargo.isDocked() && !guard.isDocked(),
				type + " field launch retained a stale docked state");
		}

		private void assertFieldOperation(FieldOperationType type) {
			DroneEntity scout = drones.get(0);
			DroneEntity engineer = drones.get(1);
			DroneEntity cargo = drones.get(2);
			DroneEntity guard = drones.get(3);
			require(scout.fieldProgress() > 0, type + " scout did not scan");
			require(engineer.fieldOperationType() == type, type + " engineer operation mismatch");
			require(engineer.fieldOperationState() != FieldOperationState.BLOCKED,
				type + " engineer blocked: " + engineer.fieldStatusLabel());
			require(cargo.fieldOperationState() != FieldOperationState.BLOCKED,
				type + " cargo blocked: " + cargo.fieldStatusLabel());
			require(guard.role() == DroneRole.SECURITY && guard.fieldOperationType() == type,
				type + " guard did not join the composite mission");
			require(guard.fieldOperationState() != FieldOperationState.BLOCKED,
				type + " guard blocked: " + guard.fieldStatusLabel());
			require(countContainer(cargoTarget) > 0,
				type + " cargo did not deliver field drops: " + fieldCargoDiagnostics(cargo, type));
			assertAutonomousFieldExit(List.of(scout, engineer, cargo, guard), type + " field team");
			BlockPos anchor = fieldAnchor(type);
			if (type == FieldOperationType.ORE) {
				require(level.getBlockState(anchor).isAir(), "ore operation left diamond ore in the world");
				require(level.getBlockState(anchor.offset(1, 0, 0)).isAir(), "ore operation left iron ore in the world");
			} else if (type == FieldOperationType.EXCAVATE) {
				require(level.getBlockState(anchor).isAir(), "excavation did not remove the center surface block");
				require(level.getBlockState(anchor.offset(2, 0, 2)).isAir(), "excavation did not remove an edge surface block");
			} else if (type == FieldOperationType.FORESTRY) {
				require(level.getBlockState(anchor).is(Blocks.OAK_SAPLING),
					"forestry did not replant the harvested tree");
				require(!level.getBlockState(anchor.above(3)).is(BlockTags.LOGS),
					"forestry left upper trunk blocks in the world");
			}
		}

		private void assignFieldTeam(FieldOperationType type, int startIndex, String groupId) {
			BlockPos anchor = fieldAnchor(type);
			clearFlightBubble(anchor, 7);
			prepareFieldBlocks();
			DroneEntity scout = drones.get(startIndex);
			DroneEntity engineer = drones.get(startIndex + 1);
			DroneEntity cargo = drones.get(startIndex + 2);
			reset(scout);
			reset(engineer);
			reset(cargo);
			scout.assignRole(DroneRole.SCOUT);
			engineer.assignRole(DroneRole.ENGINEER);
			cargo.assignRole(DroneRole.CARGO);
			scout.assignGroup(groupId);
			engineer.assignGroup(groupId);
			cargo.assignGroup(groupId);
			scout.setPos(anchor.getX() + 0.5, anchor.getY() + 6.5, anchor.getZ() + 0.5);
			engineer.setPos(anchor.getX() + 2.5, anchor.getY() + 3.0, anchor.getZ() + 0.5);
			cargo.setPos(anchor.getX() + 3.5, anchor.getY() + 3.0, anchor.getZ() + 0.5);
			cargo.assignCargoTarget(cargoTarget);
			String order = "verify-team-" + type.id() + "-" + startIndex + "-" + level.getGameTime();
			int radius = verifierFieldRadius(type);
			scout.assignFieldOperation(type, anchor, radius, order);
			engineer.assignFieldOperation(type, anchor, radius, order);
			cargo.assignFieldOperation(type, anchor, radius, order);
		}

		private int verifierFieldRadius(FieldOperationType type) {
			return type == FieldOperationType.EXCAVATE ? 4 : 12;
		}

		private void assertFieldTeam(FieldOperationType type, int startIndex, String groupId) {
			DroneEntity scout = drones.get(startIndex);
			DroneEntity engineer = drones.get(startIndex + 1);
			DroneEntity cargo = drones.get(startIndex + 2);
			require(scout.groupId().equals(groupId) && engineer.groupId().equals(groupId)
				&& cargo.groupId().equals(groupId), type + " composite group mismatch");
			require(scout.role() == DroneRole.SCOUT, type + " composite scout role mismatch");
			require(engineer.role() == DroneRole.ENGINEER, type + " composite engineer role mismatch");
			require(cargo.role() == DroneRole.CARGO, type + " composite cargo role mismatch");
			if (type != FieldOperationType.EXCAVATE) {
				require(scout.fieldProgress() > 0, type + " composite scout did not scan");
				require(scout.fieldFound() > 0, type + " composite scout found no targets");
			}
			require(engineer.fieldOperationType() == type, type + " composite engineer operation mismatch");
			require(engineer.fieldOperationState() != FieldOperationState.BLOCKED,
				type + " composite engineer blocked: " + engineer.fieldStatusLabel());
			require(cargo.fieldOperationState() != FieldOperationState.BLOCKED,
				type + " composite cargo blocked: " + cargo.fieldStatusLabel());
			require(countContainer(cargoTarget) > 0,
				type + " composite cargo did not deliver field drops: " + fieldCargoDiagnostics(cargo, type));
			assertAutonomousFieldExit(List.of(scout, engineer, cargo), type + " composite team");
		}

		private void assertAutonomousFieldExit(List<DroneEntity> team, String label) {
			for (DroneEntity drone : team) {
				require(drone.fieldOperationState() == FieldOperationState.COMPLETE,
					label + " did not complete " + drone.role() + " / " + drone.fieldOperationState());
			}
			for (DroneEntity drone : team) {
				boolean returnedToOwner = drone.mode() == DroneMode.RETURN
					|| drone.mode() == DroneMode.FOLLOW
					|| drone.mode() == DroneMode.STANDBY && drone.distanceToSqr(player) < 4.0;
				boolean returningToDock = drone.isDocked()
					|| drone.mode() == DroneMode.DOCK && (drone.hasDock() || drone.dockHolding());
				require(returnedToOwner || returningToDock,
					label + " did not autonomously recover " + drone.unitId() + " / " + drone.mode());
			}
		}

		private boolean fieldTeamReady(int startIndex, int count) {
			if (drones.size() < startIndex + count) return false;
			for (DroneEntity drone : drones.subList(startIndex, startIndex + count)) {
				if (drone.fieldOperationState() != FieldOperationState.COMPLETE) return false;
				boolean recovering = drone.mode() == DroneMode.RETURN || drone.mode() == DroneMode.FOLLOW
					|| drone.isDocked()
					|| drone.mode() == DroneMode.DOCK && (drone.hasDock() || drone.dockHolding());
				if (!recovering) return false;
			}
			return true;
		}

		private String fieldCargoDiagnostics(DroneEntity cargo, FieldOperationType type) {
			BlockPos anchor = fieldAnchor(type);
			int ground = level.getEntitiesOfClass(ItemEntity.class, new AABB(anchor).inflate(40),
				item -> item.isAlive() && !item.getItem().isEmpty()).stream()
				.mapToInt(item -> item.getItem().getCount()).sum();
			double targetDistance = cargo.hasCargoTarget()
				? cargo.position().distanceTo(Vec3.atCenterOf(cargo.cargoTarget()).add(0, 2, 0)) : -1.0;
			return cargo.fieldOperationState().label() + ", load=" + cargo.cargoItemCount()
				+ ", ground=" + ground + ", target=" + cargo.hasCargoTarget()
				+ ", distance=" + String.format(java.util.Locale.ROOT, "%.1f", targetDistance);
		}

		private void assignEngineerRepair() {
			DroneEntity damaged = drones.get(4);
			DroneEntity engineer = drones.get(5);
			clearHostiles(64);
			for (DroneEntity drone : aliveDrones()) {
				reset(drone);
				drone.clearDock();
				drone.assignRole(DroneRole.FIELD);
				drone.setHealth(drone.getMaxHealth());
			}
			reset(damaged);
			reset(engineer);
			damaged.assignRole(DroneRole.FIELD);
			engineer.assignRole(DroneRole.ENGINEER);
			damaged.assignGroup("WING-REPAIR");
			engineer.assignGroup("WING-REPAIR");
			damaged.setHealth(8.0f);
			damaged.setPos(base.getX() + 5.5, base.getY() + 2.5, base.getZ() + 6.5);
			engineer.setPos(base.getX() + 7.5, base.getY() + 2.5, base.getZ() + 6.5);
		}

		private void assertEngineerRepair() {
			DroneEntity damaged = drones.get(4);
			DroneEntity engineer = drones.get(5);
			require(engineer.engineerState() != EngineerState.MATERIAL_LOW, "engineer material low");
			require(damaged.getHealth() > 8.0f || !engineer.engineerTargetLabel().isBlank(),
				"engineer did not acquire or repair damaged drone");
		}

		private void assignParallelCompositeOperations() {
			clearHostiles(64);
			prepareFieldBlocks();
			placeChest(cargoTarget, ItemStack.EMPTY);
			assignFieldTeam(FieldOperationType.ORE, 0, "WING-COMP-ORE");
			assignFieldTeam(FieldOperationType.EXCAVATE, 3, "WING-COMP-EXC");
			assignFieldTeam(FieldOperationType.FORESTRY, 6, "WING-COMP-FOR");
		}

		private void assertParallelCompositeOperations() {
			assertFieldTeam(FieldOperationType.ORE, 0, "WING-COMP-ORE");
			assertFieldTeam(FieldOperationType.EXCAVATE, 3, "WING-COMP-EXC");
			assertFieldTeam(FieldOperationType.FORESTRY, 6, "WING-COMP-FOR");
		}

		private void assignUiTerminalSnapshot() {
			prepareUiScenario();
		}

		private void assertUiTerminalSnapshot() {
			require(aliveDrones().size() >= 24, "UI snapshot fleet too small");
			require(docks.size() >= 4, "UI snapshot docks missing");
			require(level.getBlockEntity(cargoSource) instanceof Container, "UI snapshot cargo source missing");
			require(level.getBlockEntity(cargoTarget) instanceof Container, "UI snapshot cargo target missing");
			require(aliveDrones().stream().anyMatch(drone -> drone.role() == DroneRole.SCOUT
				&& drone.hasFieldOperation() && drone.fieldProgress() > 0), "UI snapshot scout field line missing");
			require(aliveDrones().stream().anyMatch(drone -> drone.role() == DroneRole.ENGINEER
				&& (!drone.engineerStatusLabel().isBlank() || drone.hasFieldOperation())), "UI snapshot engineer line missing");
			require(aliveDrones().stream().anyMatch(drone -> drone.role() == DroneRole.CARGO
				&& (drone.cargoState() != CargoState.UNASSIGNED || drone.hasFieldOperation())), "UI snapshot cargo line missing");
			require(aliveDrones().stream().anyMatch(DroneEntity::hasSecurityPatrol), "UI snapshot security patrol line missing");
			require(aliveDrones().stream().filter(drone -> drone.groupId().startsWith("WING-")).count() >= 16,
				"UI snapshot wing roster missing");
		}

		private void prepareUiScenario() {
			drones.clear();
			docks.clear();
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
				new ItemStack(MorrowgearDrone.TACTICAL_VISOR));
			cleanupVerifierDrones();
			clearHostiles(64);
			preparePlatform(base);
			cargoSource = base.offset(-8, 1, -8);
			cargoTarget = base.offset(-8, 1, -2);
			placeChest(cargoSource, new ItemStack(Items.COBBLESTONE, 64));
			placeChest(cargoTarget, ItemStack.EMPTY);
			placeChest(base.offset(-5, 1, -8), new ItemStack(Items.OAK_LOG, 32));
			placeChest(base.offset(-5, 1, -2), ItemStack.EMPTY);
			for (int i = 0; i < 4; i++) {
				BlockPos dock = base.offset(-12 + i * 5, 1, 8);
				placeDock(dock);
				docks.add(dock);
			}
			for (int i = 0; i < 24; i++) spawnVerifierDrone(i);
			player.getInventory().add(new ItemStack(Items.COPPER_INGOT, 64));
			player.getInventory().add(new ItemStack(Items.OAK_SAPLING, 16));
			prepareFieldBlocks();

			assignFieldTeam(FieldOperationType.ORE, 0, "WING-ORE");
			assignFieldTeam(FieldOperationType.FORESTRY, 3, "WING-TIMBER");

			for (int i = 6; i < 9; i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.CARGO);
				drone.assignGroup("WING-CARGO");
				drone.assignCargoSource(cargoSource);
				drone.assignCargoTarget(cargoTarget);
			}

			for (int i = 9; i < 12; i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignRole(DroneRole.SECURITY);
				drone.assignGroup("WING-SEC");
				drone.assignSecurityPatrol(base.offset(5, 1, 5), 16, "verify-ui-guard");
			}
			trackingTarget = spawnZombie(base.getX() + 7.5, base.getY() + 2.0, base.getZ() + 5.5);

			DroneEntity damaged = drones.get(12);
			DroneEntity engineer = drones.get(13);
			reset(damaged);
			reset(engineer);
			damaged.assignRole(DroneRole.FIELD);
			engineer.assignRole(DroneRole.ENGINEER);
			damaged.assignGroup("WING-REPAIR");
			engineer.assignGroup("WING-REPAIR");
			damaged.setHealth(8.0f);
			damaged.setPos(base.getX() + 5.5, base.getY() + 2.5, base.getZ() + 6.5);
			engineer.setPos(base.getX() + 7.5, base.getY() + 2.5, base.getZ() + 6.5);

			List<DroneEntity> patrol = drones.subList(14, Math.min(22, drones.size()));
			for (DroneEntity drone : patrol) {
				reset(drone);
				drone.assignRole(DroneRole.FIELD);
				drone.assignGroup("WING-PATROL");
			}
			assignWaypoint(patrol, base.offset(34, 0, 10), "verify-ui-patrol-" + level.getGameTime());
			patrol.forEach(drone -> drone.setFieldMeshAuthorized(true));

			for (int i = 22; i < drones.size(); i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignGroup("UNIT-POOL");
				drone.setMode(DroneMode.STANDBY);
			}
		}

		private void assignDockReturn() {
			clearHostiles(64);
			if (trackingTarget != null) trackingTarget.discard();
			for (int i = 0; i < Math.min(4, docks.size()); i++) {
				DroneEntity drone = drones.get(i);
				reset(drone);
				drone.assignDock(docks.get(i));
				drone.setPos(docks.get(i).getX() + 0.5, docks.get(i).getY() + 5.5, docks.get(i).getZ() + 0.5);
				drone.setMode(DroneMode.DOCK);
			}
		}

		private void assertDockReturn() {
			int docked = 0;
			for (int i = 0; i < Math.min(4, docks.size()); i++) {
				DroneEntity drone = drones.get(i);
				if (drone.isDocked()) docked++;
			}
			require(docked >= Math.min(4, docks.size()) - 1, "dock return low success count " + docked + "/" + docks.size());
		}

		private void assignDockGuardLaunch() {
			dockGuardLaunchProbe = drones.subList(0, Math.min(4, drones.size())).stream()
				.filter(DroneEntity::isDocked).findFirst()
				.orElseThrow(() -> new IllegalStateException("no docked drone available for guard launch"));
			dockGuardLaunchProbe.assignRole(DroneRole.SECURITY);
			dockGuardLaunchProbe.setYRot(180.0f);
			dockGuardLaunchProbe.setYHeadRot(180.0f);
			dockGuardLaunchProbe.setYBodyRot(180.0f);
			dockGuardLaunchStart = dockGuardLaunchProbe.position();
			dockGuardLaunchYaw = dockGuardLaunchProbe.getYRot();
			dockGuardLaunchProbe.assignSecurityPatrol(base.offset(18, 1, 8), 16,
				"verify-dock-guard-launch-" + level.getGameTime());
		}

		private void assertDockGuardLaunch() {
			require(dockGuardLaunchProbe != null && dockGuardLaunchProbe.isAlive(),
				"dock guard launch probe missing");
			require(dockGuardLaunchProbe.hasSecurityPatrol(), "dock guard patrol was not assigned");
			require(!dockGuardLaunchProbe.isDocked(), "dock guard retained stale docked state");
			require(dockGuardLaunchProbe.position().distanceTo(dockGuardLaunchStart) > 1.0,
				"dock guard did not leave the bay");
			require(Math.abs(net.minecraft.util.Mth.wrapDegrees(
				dockGuardLaunchProbe.getYRot() - dockGuardLaunchYaw)) > 5.0f,
				"dock guard heading remained locked to the bay");
		}

		private void assignObstructedDockReturn() {
			BlockPos dock = base.offset(30, 1, 8);
			clearVolume(dock, 8, 0, 8);
			placeDock(dock);
			for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
				level.setBlockAndUpdate(dock.offset(x, 4, z), Blocks.SMOOTH_STONE.defaultBlockState());
			}
			for (net.minecraft.core.Direction direction : List.of(
				net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
				net.minecraft.core.Direction.WEST)) {
				net.minecraft.core.Direction lateral = direction.getClockWise();
				for (int distance = 2; distance <= 5; distance++) for (int width = -1; width <= 1; width++) {
					for (int height = 0; height <= 3; height++) {
						BlockPos obstacle = dock.relative(direction, distance).relative(lateral, width).above(height);
						level.setBlockAndUpdate(obstacle, Blocks.SMOOTH_STONE.defaultBlockState());
					}
				}
			}
			DroneEntity drone = drones.get(4);
			reset(drone);
			// Fleet setup cycles index 4 to SECURITY. This case owns navigation geometry,
			// so do not let ambient theater dispatch replace its Dock route mid-approach.
			drone.assignRole(DroneRole.FIELD);
			drone.assignDock(dock);
			drone.setPos(dock.getX() - 7.5, dock.getY() + 2.0, dock.getZ() + 0.5);
			drone.setMode(DroneMode.DOCK);
		}

		private void assertObstructedDockReturn() {
			BlockPos dock = base.offset(30, 1, 8);
			DroneEntity drone = drones.get(4);
			boolean docked = drone.isDocked();
			double distance = drone.position().distanceTo(Vec3.atCenterOf(dock));
			MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] NAV FINAL dock {} {}",
				drone.unitId(), drone.navigationDiagnosticForVerification());
			clearVolume(dock, 8, 0, 8);
			require(docked, "drone did not find the only open east approach; distance=" + String.format("%.2f", distance));
		}

		private void assignObstructedRegroup() {
			clearHostiles(128.0);
			BlockPos wall = base.offset(26, 1, 0);
			clearVolume(wall, 8, 0, 8);
			for (int z = -6; z <= 6; z++) for (int y = 0; y <= 7; y++) {
				level.setBlockAndUpdate(wall.offset(0, y, z), Blocks.SMOOTH_STONE.defaultBlockState());
			}
			List<DroneEntity> wing = drones.subList(8, 12);
			for (int i = 0; i < wing.size(); i++) {
				DroneEntity drone = wing.get(i);
				reset(drone);
				drone.assignRole(DroneRole.FIELD);
				drone.assignGroup("WING-OBSTACLE");
				drone.setPos(wall.getX() - 6.5, wall.getY() + 2.0, wall.getZ() - 3.0 + i * 2.0);
			}
			assignWaypoint(wing, base.offset(36, 1, 0), "verify-obstacle-regroup-" + level.getGameTime());
		}

		private void assertObstructedRegroup() {
			BlockPos wall = base.offset(26, 1, 0);
			List<DroneEntity> wing = drones.subList(8, 12);
			long crossed = wing.stream().filter(drone -> drone.getX() > wall.getX() + 1.5).count();
			long healthy = wing.stream().filter(drone -> drone.isAlive() && drone.recoveryLevel() < 3).count();
			clearVolume(wall, 8, 0, 8);
			require(crossed == wing.size(), "formation did not route every member around wall: " + crossed + "/" + wing.size());
			require(healthy == wing.size(), "formation left members in terminal recovery: " + healthy + "/" + wing.size());
		}

		private boolean obstructedRegroupReady() {
			BlockPos wall = base.offset(26, 1, 0);
			return drones.subList(8, 12).stream().allMatch(drone -> drone.getX() > wall.getX() + 1.5);
		}

		private void assignLargeObstacleRoute() {
			clearHostiles(128.0);
			BlockPos wall = base.offset(18, 1, 0);
			clearGiantWall(wall);
			for (int z = -40; z <= 40; z++) for (int y = 0; y < 52; y++) {
				level.setBlockAndUpdate(wall.offset(0, y, z), Blocks.SMOOTH_STONE.defaultBlockState());
			}
			List<DroneEntity> wing = drones.subList(13, 17);
			for (int i = 0; i < wing.size(); i++) {
				DroneEntity drone = wing.get(i);
				reset(drone);
				// This case measures formation navigation, not autonomous threat
				// interception.  A SECURITY role could legitimately leave the route if
				// a mob appears during the minute-long obstacle traversal.
				drone.assignRole(DroneRole.FIELD);
				drone.assignGroup("WING-LARGE-OBSTACLE");
				drone.setPos(wall.getX() - 10.5, wall.getY() + 2.0, wall.getZ() - 3.0 + i * 2.0);
			}
			assignWaypoint(wing, base.offset(36, 1, 0), "verify-large-obstacle-" + level.getGameTime());
		}

		private void assertLargeObstacleRoute() {
			BlockPos wall = base.offset(18, 1, 0);
			List<DroneEntity> wing = drones.subList(13, 17);
			long crossed = wing.stream().filter(drone -> drone.getX() > wall.getX() + 2.0).count();
			long alive = wing.stream().filter(DroneEntity::isAlive).count();
			for (DroneEntity drone : wing) {
				MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] NAV FINAL large {} {}",
					drone.unitId(), drone.navigationDiagnosticForVerification());
			}
			clearGiantWall(wall);
			require(crossed == wing.size(), "large obstacle trapped formation: " + crossed + "/" + wing.size());
			require(alive == wing.size(), "large obstacle destroyed formation members: " + alive + "/" + wing.size());
		}

		private void assignFieldRelayObstacleRoute() {
			clearHostiles(128.0);
			for (DroneEntity drone : drones) {
				reset(drone);
				drone.setFieldMeshAuthorized(false);
			}
			FieldRelayRuntime.clearOwner(player.getUUID());
			fieldRelayObstacleBlocks.clear();
			fieldRelayObstacleProbe = drones.get(17);
			fieldRelayObstacleProbe.assignRole(DroneRole.FIELD);
			fieldRelayObstacleProbe.assignGroup("FIELD-OBSTACLE-RELAY");
			fieldRelayObstacleProbe.setPos(base.getX() + 4.5, base.getY() + 29.0, base.getZ() + 0.5);
			fieldRelayObstacleProbe.setFieldMeshAuthorized(true);
			FieldRelayRuntime.Assignment assignment = FieldRelayRuntime.assignment(player, fieldRelayObstacleProbe);
			if (assignment == null) throw new IllegalStateException("FIELD relay received no mesh slot");
			fieldRelayObstacleSlot = assignment.slotId();
			fieldRelayObstacleTarget = assignment.target();
			buildFieldRelayObstacle(fieldRelayObstacleProbe.position(), fieldRelayObstacleTarget);
		}

		private void buildFieldRelayObstacle(Vec3 from, Vec3 target) {
			Vec3 horizontal = target.subtract(from).multiply(1, 0, 1);
			if (horizontal.lengthSqr() < 1.0) throw new IllegalStateException("FIELD relay slot is too close");
			Vec3 forward = horizontal.normalize();
			Vec3 right = new Vec3(-forward.z, 0, forward.x);
			Vec3 center = from.add(horizontal.scale(0.42));
			for (int thickness = -1; thickness <= 1; thickness++) {
				for (int lateral = -12; lateral <= 12; lateral++) {
					for (int vertical = -8; vertical <= 8; vertical++) {
						Vec3 point = center.add(forward.scale(thickness)).add(right.scale(lateral))
							.add(0, vertical, 0);
						BlockPos block = BlockPos.containing(point);
						if (!fieldRelayObstacleBlocks.contains(block)) fieldRelayObstacleBlocks.add(block);
						level.setBlockAndUpdate(block, Blocks.SMOOTH_STONE.defaultBlockState());
					}
				}
			}
		}

		private boolean fieldRelayObstacleRouteReady() {
			if (fieldRelayObstacleProbe == null || !fieldRelayObstacleProbe.isAlive()) return true;
			FieldRelayRuntime.Assignment assignment = FieldRelayRuntime.assignment(player, fieldRelayObstacleProbe);
			return assignment != null && assignment.slotId().equals(fieldRelayObstacleSlot)
				&& FieldRelayFlightPolicy.established(fieldRelayObstacleProbe.position(), assignment.target())
				&& fieldRelayObstacleProbe.recoveryLevel() == 0;
		}

		private void assertFieldRelayObstacleRoute() {
			DroneEntity probe = fieldRelayObstacleProbe;
			FieldRelayRuntime.Assignment assignment = probe == null ? null
				: FieldRelayRuntime.assignment(player, probe);
			boolean linked = probe != null && DataLinkRelayNetwork.coverage(player).relays().stream()
				.anyMatch(node -> node.node().id().equals(probe.getUUID()));
			for (BlockPos block : fieldRelayObstacleBlocks) level.removeBlock(block, false);
			fieldRelayObstacleBlocks.clear();
			require(probe != null && probe.isAlive(), "FIELD relay was removed during obstacle reroute");
			require(assignment != null && assignment.slotId().equals(fieldRelayObstacleSlot),
				"FIELD relay lost or changed its reserved mesh slot during obstacle recovery");
			require(fieldRelayObstacleTarget != null
				&& FieldRelayFlightPolicy.established(probe.position(), assignment.target()),
				"FIELD relay did not route around the wall to its mesh slot; "
					+ probe.navigationDiagnosticForVerification());
			require(probe.recoveryLevel() == 0, "FIELD relay retained recovery level " + probe.recoveryLevel());
			require(linked, "FIELD relay did not re-enter the data-link graph after arrival");
			probe.setFieldMeshAuthorized(false);
		}

		private void assignObstructedFieldWork() {
			FieldOperationRegistry.clear();
			BlockPos wall = base.offset(30, 1, -10);
			BlockPos ore = base.offset(36, 1, -10);
			clearVolume(wall, 8, 0, 8);
			for (int z = -5; z <= 5; z++) for (int y = 0; y <= 7; y++) {
				level.setBlockAndUpdate(wall.offset(0, y, z), Blocks.SMOOTH_STONE.defaultBlockState());
			}
			level.setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
			DroneEntity engineer = drones.get(12);
			reset(engineer);
			engineer.assignRole(DroneRole.ENGINEER);
			engineer.assignGroup("WING-OBSTACLE-FIELD");
			engineer.setPos(wall.getX() - 5.5, wall.getY() + 2.0, wall.getZ() + 0.5);
			engineer.assignFieldOperation(FieldOperationType.ORE, ore, 4,
				"verify-obstacle-field-" + level.getGameTime());
		}

		private void assertObstructedFieldWork() {
			BlockPos wall = base.offset(30, 1, -10);
			BlockPos ore = base.offset(36, 1, -10);
			DroneEntity engineer = drones.get(12);
			BlockState resultState = level.getBlockState(ore);
			boolean mined = resultState.isAir() || resultState.is(Blocks.LIGHT);
			boolean healthy = engineer.isAlive() && engineer.fieldOperationState() != FieldOperationState.BLOCKED;
			clearVolume(wall, 8, 0, 8);
			double distance = engineer.position().distanceTo(Vec3.atCenterOf(ore).add(0, 1.8, 0));
			require(mined, "engineer did not navigate around wall to mine target; state="
				+ engineer.fieldOperationState() + " pos=" + String.format("%.1f,%.1f,%.1f",
				engineer.getX(), engineer.getY(), engineer.getZ()) + " distance=" + String.format("%.2f", distance)
				+ " recovery=" + engineer.recoveryLevel());
			require(healthy, "engineer became blocked or was destroyed while rerouting");
		}

		private void removeAtomicDock() {
			atomicDock = base.offset(34, 1, 12);
			placeDock(atomicDock);
			atomicDockProbe = aliveDrones().stream().findFirst()
				.orElseGet(() -> spawnVerifierDrone(drones.size()));
			reset(atomicDockProbe);
			atomicDockProbe.assignDock(atomicDock);
			atomicDockDropsBefore = countNearbyItem(MorrowgearDrone.DOCK_ITEM, atomicDock);
			MorrowgearDrone.removeDock(level, atomicDock, true);
		}

		private void assertAtomicDockRemoval() {
			for (int i = 0; i < MorrowgearDrone.DOCK_PARTS.length; i++) {
				BlockPos part = atomicDock.offset(i % 3 - 1, 0, i / 3 - 1);
				require(level.getBlockState(part).isAir(), "dock part remained at " + part.toShortString());
			}
			require(atomicDockProbe != null && atomicDockProbe.isAlive(), "dock removal probe missing");
			require(!atomicDockProbe.hasDock(), "drone retained removed dock assignment");
			require(countNearbyItem(MorrowgearDrone.DOCK_ITEM, atomicDock) == atomicDockDropsBefore + 1,
				"dock removal did not produce exactly one kit");
		}

		private void logNavigationDiagnostics(String caseName, long elapsed) {
			if (caseName.equals("dock return with roof and blocked approaches")) {
				DroneEntity drone = drones.get(4);
				MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] NAV DIAG t={} dock {} {}",
					elapsed, drone.unitId(), drone.navigationDiagnosticForVerification());
			} else if (caseName.equals("large obstacle strategic reroute")) {
				for (DroneEntity drone : drones.subList(13, 17)) {
					MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] NAV DIAG t={} large {} {}",
						elapsed, drone.unitId(), drone.navigationDiagnosticForVerification());
				}
			} else if (caseName.equals("field relay obstacle reroute and slot retention")
				&& fieldRelayObstacleProbe != null) {
				MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] NAV DIAG t={} FIELD relay slot={} target={} {}",
					elapsed, fieldRelayObstacleSlot, fieldRelayObstacleTarget,
					fieldRelayObstacleProbe.navigationDiagnosticForVerification());
			}
		}

		private void storeTemporaryDrone() {
			storageProbe = spawnVerifierDrone(drones.size());
			storageProbe.assignGroup("VERIFY-STORE");
			storageProbe.assignRole(DroneRole.FIELD);
			storedUnitCountBefore = countPlayerAndNearbyItem(MorrowgearDrone.DRONE_UNIT);
			MorrowgearDrone.storeDrone(player, storageProbe);
		}

		private void assertStoredDroneReturn() {
			require(storageProbe != null && !storageProbe.isAlive(), "stored drone entity remained alive");
			int after = countPlayerAndNearbyItem(MorrowgearDrone.DRONE_UNIT);
			require(after == storedUnitCountBefore + (player.isCreative() ? 0 : 1),
				"stored unit return mismatch " + storedUnitCountBefore + " -> " + after);
			if (!player.isCreative()) removeOnePlayerOrNearbyItem(MorrowgearDrone.DRONE_UNIT);
		}

		private void assertMission(String label, DroneMode expectedMode) {
			List<DroneEntity> fleet = aliveDrones();
			for (DroneEntity drone : fleet) {
				List<DroneEntity> wing = fleet.stream()
					.filter(member -> MissionWingPolicy.normalizedGroup(member.groupId()).equals(
						MissionWingPolicy.normalizedGroup(drone.groupId()))).toList();
				require(drone.mode() == expectedMode, label + " mode mismatch " + drone.unitId() + " / " + drone.mode());
				require(drone.missionExpected() == wing.size(), label + " expected mismatch " + drone.unitId());
				require(drone.missionIndex() >= 0 && drone.missionIndex() < wing.size(),
					label + " index out of range " + drone.unitId() + " / " + drone.missionIndex());
				require(drone.wingIndex() == 0,
					label + " wing index mismatch " + drone.unitId());
			}
			if (expectedMode == DroneMode.FOLLOW) {
				for (List<DroneEntity> wing : MissionWingPolicy.partitionByWing(fleet, DroneEntity::groupId)) {
					require(wing.stream().map(DroneEntity::cohortLeaderId).filter(id -> !id.isBlank())
						.distinct().count() <= 1, label + " conflicting follow leaders in "
							+ wing.getFirst().groupId());
				}
			}
		}

		private void assertFleetInvariants() {
			List<DroneEntity> fleet = aliveDrones();
			Map<String, Integer> wingCounts = new HashMap<>();
			Set<String> unitIds = new HashSet<>();
			for (DroneEntity drone : fleet) {
				require(unitIds.add(drone.unitId()), "duplicate final unit " + drone.unitId());
				require(drone.getHealth() > 0, drone.unitId() + " dead");
				require(drone.batteryPercent() >= 0 && drone.batteryPercent() <= 100,
					drone.unitId() + " battery out of range " + drone.batteryPercent());
				if (drone.groupId().startsWith("WING-")) wingCounts.merge(drone.groupId(), 1, Integer::sum);
				if (drone.role() == DroneRole.CARGO && drone.cargoPaused()) {
					require(drone.cargoState() == CargoState.UNASSIGNED,
						drone.unitId() + " paused cargo still has active state");
				}
			}
			for (Map.Entry<String, Integer> entry : wingCounts.entrySet()) {
				require(entry.getValue() <= WingMembershipPolicy.MAX_MEMBERS,
					entry.getKey() + " final count exceeds capacity " + entry.getValue());
			}
		}

		private void assignGroups(List<DroneEntity> fleet) {
			for (int i = 0; i < fleet.size(); i++) {
				fleet.get(i).assignGroup("WING-R" + (i / WingMembershipPolicy.MAX_MEMBERS + 1));
			}
		}

		private void assignFollow(List<DroneEntity> fleet, String missionId) {
			if (fleet.isEmpty()) return;
			List<List<DroneEntity>> wings = MissionWingPolicy.partitionByWing(fleet, DroneEntity::groupId);
			for (int wingIndex = 0; wingIndex < wings.size(); wingIndex++) {
				List<DroneEntity> wing = wings.get(wingIndex);
				DroneEntity leader = wing.stream().min(Comparator.comparing(DroneEntity::unitId))
					.orElse(wing.getFirst());
				String wingMission = wings.size() == 1 ? missionId : missionId + "-W" + (wingIndex + 1);
				for (int index = 0; index < wing.size(); index++) {
					DroneEntity drone = wing.get(index);
					resetMissionOnly(drone);
					drone.assignFollowFormation(wingMission, wing.size(), index,
						leader.unitId(), base, level.getGameTime());
				}
			}
		}

		private void assignWaypoint(List<DroneEntity> fleet, BlockPos waypoint, String missionId) {
			List<List<DroneEntity>> wings = MissionWingPolicy.partitionByWing(fleet, DroneEntity::groupId);
			for (int wingIndex = 0; wingIndex < wings.size(); wingIndex++) {
				List<DroneEntity> wing = wings.get(wingIndex);
				String wingMission = wings.size() == 1 ? missionId : missionId + "-W" + (wingIndex + 1);
				for (int index = 0; index < wing.size(); index++) {
					DroneEntity drone = wing.get(index);
					resetMissionOnly(drone);
					drone.assignWaypoint(waypoint, wingMission, wing.size(), index,
						base, level.getGameTime());
				}
			}
		}

		private List<DroneEntity> aliveDrones() {
			return drones.stream().filter(DroneEntity::isAlive).toList();
		}

		private void reset(DroneEntity drone) {
			drone.resetCombatForVerification();
			drone.resetNavigationForVerification();
			// Every runtime case owns its fixture. A Dock binding retained from an
			// earlier case can redirect autonomous recovery and contaminate Wing tests.
			drone.clearDock();
			drone.setDockedForVerification(false);
			drone.setPowerForVerification(1000, 1000);
			drone.setSubsystemConditionForVerification(DroneSubsystemPolicy.MAX,
				DroneSubsystemPolicy.MAX, DroneSubsystemPolicy.MAX);
			drone.setCombatResourcesForVerification(CombatPolicy.GUN_CAPACITY,
				CombatPolicy.MISSILE_CAPACITY, 0);
			drone.setHealth(drone.getMaxHealth());
			resetMissionOnly(drone);
			drone.clearSecurityPatrol();
			drone.clearFieldOperation();
			drone.pauseCargoRoute();
			drone.assignGroup("VERIFY");
			drone.assignSecurityLoadout(SecurityLoadout.AUTO);
			drone.setMode(DroneMode.STANDBY);
		}

		private void resetMissionOnly(DroneEntity drone) {
			drone.clearSecurityPatrol();
			drone.clearFieldOperation();
			if (drone.role() == DroneRole.CARGO) drone.pauseCargoRoute();
			drone.setMode(DroneMode.STANDBY);
		}

		private void preparePlatform(BlockPos origin) {
			for (int x = -18; x <= 42; x++) {
				for (int z = -18; z <= 18; z++) {
					level.setBlockAndUpdate(origin.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
					for (int y = 1; y <= 8; y++) level.setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
				}
			}
		}

		private void placeDock(BlockPos center) {
			for (int i = 0; i < MorrowgearDrone.DOCK_PARTS.length; i++) {
				if (i == 4) continue;
				BlockPos part = center.offset(i % 3 - 1, 0, i / 3 - 1);
				level.setBlockAndUpdate(part, MorrowgearDrone.DOCK_PARTS[i].defaultBlockState());
			}
			level.setBlockAndUpdate(center, MorrowgearDrone.DOCK_PARTS[4].defaultBlockState());
			if (level.getBlockEntity(center) instanceof DockBlockEntity dock) dock.initialize(player);
		}

		private void placeChest(BlockPos pos, ItemStack stack) {
			placeContainer(pos, Blocks.CHEST, stack);
		}

		private void placeContainer(BlockPos pos, net.minecraft.world.level.block.Block block, ItemStack stack) {
			level.setBlockAndUpdate(pos, block.defaultBlockState());
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (blockEntity instanceof Container container) {
				container.clearContent();
				if (!stack.isEmpty()) container.setItem(0, stack.copy());
			}
		}

		private void prepareFieldBlocks() {
			BlockPos ore = fieldAnchor(FieldOperationType.ORE);
			for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
				for (int y = -32; y <= 32; y++) {
					BlockPos pos = ore.offset(x, y, z);
					if (isVerifierOre(level.getBlockState(pos)))
						level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
				}
			}
			level.setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
			level.setBlockAndUpdate(ore.offset(1, 0, 0), Blocks.IRON_ORE.defaultBlockState());
			level.setBlockAndUpdate(ore.offset(0, -1, 0), Blocks.COAL_ORE.defaultBlockState());

			BlockPos excavation = fieldAnchor(FieldOperationType.EXCAVATE);
			for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
				level.setBlockAndUpdate(excavation.offset(x, 0, z), Blocks.STONE.defaultBlockState());
			}

			BlockPos tree = fieldAnchor(FieldOperationType.FORESTRY);
			level.setBlockAndUpdate(tree.below(), Blocks.DIRT.defaultBlockState());
			for (int y = 0; y < 4; y++) level.setBlockAndUpdate(tree.offset(0, y, 0), Blocks.OAK_LOG.defaultBlockState());
			for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
				if (Math.abs(x) + Math.abs(z) <= 3) level.setBlockAndUpdate(tree.offset(x, 4, z), Blocks.OAK_LEAVES.defaultBlockState());
			}
			level.setBlockAndUpdate(tree.offset(3, -1, 0), Blocks.DIRT.defaultBlockState());
			level.setBlockAndUpdate(tree.offset(3, 0, 0), Blocks.SHORT_GRASS.defaultBlockState());
		}

		private boolean isVerifierOre(BlockState state) {
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

		private void cleanupVerifierDrones() {
			for (DroneEntity drone : level.getEntitiesOfClass(DroneEntity.class,
				player.getBoundingBox().inflate(128.0), drone -> drone.isOwnedBy(player))) {
				if (isVerifierGroup(drone.groupId())) drone.discard();
			}
		}

		private boolean isVerifierGroup(String group) {
			return group.startsWith("VERIFY")
				|| group.startsWith("WING-R")
				|| group.startsWith("WING-VERIFY")
				|| group.startsWith("WING-SCOUT-")
				|| group.startsWith("WING-COMP-")
				|| group.startsWith("WING-OBSTACLE")
				|| group.startsWith("WING-LARGE-OBSTACLE")
				|| group.equals("WING-ORE")
				|| group.equals("WING-TIMBER")
				|| group.equals("WING-ENGINEER-SOLO")
				|| group.equals("WING-CARGO")
				|| group.equals("WING-SEC")
				|| group.equals("WING-REPAIR")
				|| group.equals("WING-PATROL")
				|| group.equals("UNIT-POOL");
		}

		private void clearHostiles(double range) {
			clearHostilesAt(player.blockPosition(), range);
		}

		private void clearHostilesAt(BlockPos center, double range) {
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(center).inflate(range), entity -> entity instanceof Enemy)) {
				entity.discard();
			}
		}

		private void clearHostilesExcept(BlockPos center, double range, LivingEntity preserved) {
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(center).inflate(range), entity -> entity instanceof Enemy && entity != preserved)) {
				entity.discard();
			}
		}

		private void clearFlightBubble(BlockPos anchor, int radius) {
			for (int x = -radius; x <= radius; x++) {
				for (int y = 1; y <= 8; y++) {
					for (int z = -radius; z <= radius; z++) {
						BlockPos pos = anchor.offset(x, y, z);
						if (level.getBlockState(pos).is(Blocks.CHEST)) continue;
						level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
					}
				}
			}
		}

		private void clearVolume(BlockPos anchor, int radius, int minY, int maxY) {
			for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
				for (int y = minY; y <= maxY; y++) {
					level.setBlockAndUpdate(anchor.offset(x, y, z), Blocks.AIR.defaultBlockState());
				}
			}
		}

		private void clearGiantWall(BlockPos wall) {
			for (int z = -40; z <= 40; z++) for (int y = 0; y < 52; y++) {
				level.setBlockAndUpdate(wall.offset(0, y, z), Blocks.AIR.defaultBlockState());
			}
		}

		private LivingEntity spawnZombie(double x, double y, double z) {
			Zombie zombie = new Zombie(level);
			zombie.setPos(x, y, z);
			zombie.setNoAi(true);
			zombie.setInvulnerable(true);
			zombie.setPersistenceRequired();
			level.addFreshEntity(zombie);
			return zombie;
		}

		private BlockPos fieldAnchor(FieldOperationType type) {
			return switch (type) {
				case ORE -> base.offset(8, 1, -12);
				case EXCAVATE -> base.offset(16, 1, -12);
				case FORESTRY -> base.offset(24, 1, -12);
				default -> base;
			};
		}

		private int countContainer(BlockPos pos) {
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (!(blockEntity instanceof Container container)) return 0;
			int count = 0;
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				count += container.getItem(slot).getCount();
			}
			return count;
		}

		private int countNearbyItem(net.minecraft.world.item.Item item, BlockPos pos) {
			return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4),
				entity -> entity.getItem().is(item)).stream().mapToInt(entity -> entity.getItem().getCount()).sum();
		}

		private int countPlayerAndNearbyItem(net.minecraft.world.item.Item item) {
			int count = 0;
			for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
				ItemStack stack = player.getInventory().getItem(slot);
				if (stack.is(item)) count += stack.getCount();
			}
			return count + level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(8),
				entity -> entity.getItem().is(item)).stream().mapToInt(entity -> entity.getItem().getCount()).sum();
		}

		private void removeOnePlayerOrNearbyItem(net.minecraft.world.item.Item item) {
			for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
				ItemStack stack = player.getInventory().getItem(slot);
				if (!stack.is(item)) continue;
				stack.shrink(1);
				return;
			}
			for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(8),
				candidate -> candidate.getItem().is(item))) {
				ItemStack stack = entity.getItem();
				stack.shrink(1);
				if (stack.isEmpty()) entity.discard();
				return;
			}
		}

		private void require(boolean condition, String message) {
			if (!condition) fail(message);
		}

		private void fail(String message) {
			String prefixed = cases.get(Math.min(index, cases.size() - 1)).name + ": " + message;
			if (!failures.contains(prefixed)) failures.add(prefixed);
		}

		private void say(String message) {
			String text = "[MORROWGEAR VERIFY] " + message;
			player.sendSystemMessage(Component.literal(text));
			MorrowgearDrone.LOGGER.info(text);
		}
	}

	private record CaseStep(String name, long waitTicks, Runnable start, Runnable assertion,
		BooleanSupplier ready, Set<VerificationScope> scopes) {
		CaseStep(String name, long waitTicks, Runnable start, Runnable assertion) {
			this(name, waitTicks, start, assertion, null, Set.of());
		}

		CaseStep(String name, long waitTicks, Runnable start, Runnable assertion,
			BooleanSupplier ready) {
			this(name, waitTicks, start, assertion, ready, Set.of());
		}

		CaseStep scopedTo(VerificationScope... scopes) {
			return new CaseStep(name, waitTicks, start, assertion, ready, Set.of(scopes));
		}

		boolean includedIn(VerificationScope scope) {
			return name.equals("sandbox setup") || scopes.contains(scope);
		}
	}
}
