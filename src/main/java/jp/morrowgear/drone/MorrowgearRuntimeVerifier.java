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
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
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
	private enum VerificationScope { ALL, NAVIGATION, FIELD, COMBAT, ADAPTIVE_COMBAT, REGRESSION }

	private MorrowgearRuntimeVerifier() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("morrowgear_verify")
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
					return startVisualEffects(player);
				}))
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

	private static int startCombat(ServerPlayer player) {
		if (active != null && !active.done()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] already running: " + active.progress()));
			return 0;
		}
		active = new RuntimeSession(player, VerificationScope.COMBAT);
		active.say("START combat coverage in Minecraft / cases " + active.caseCount());
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

	private static int startVisualEffects(ServerPlayer player) {
		if (visualEffects != null) visualEffects.cleanup();
		visualEffects = new VisualEffectSession(player);
		player.sendSystemMessage(Component.literal(
			"[MORROWGEAR VERIFY] EFFECTS START / LASER left / TRACER right / 8 sec"));
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
		solarServiceVerification = new SolarServiceVerificationSession(player);
		return Command.SINGLE_SUCCESS;
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
	}

	private static String status() {
		String arena = arenaBuilder == null ? "arena not started" : arenaBuilder.status();
		String verification = active == null ? "idle" : active.progress();
		String salvage = salvageVerification == null ? "salvage not started" : salvageVerification.progress();
		String solar = solarServiceVerification == null ? "solar not started" : solarServiceVerification.progress();
		String dockExhaustion = dockExhaustionVerification == null ? "dock exhaustion not started"
			: dockExhaustionVerification.progress();
		return "[MORROWGEAR VERIFY] " + verification + " / " + arena + " / " + salvage
			+ " / " + solar + " / " + dockExhaustion;
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
			BlockPos base = player.blockPosition().below().offset(-14, 0, 0);
			dockPos = base.above();
			for (int x = -4; x <= 20; x++) for (int z = -6; z <= 6; z++) {
				level.setBlockAndUpdate(base.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
				for (int y = 1; y <= 8; y++) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
			}
			for (int index = 0; index < MorrowgearDrone.DOCK_PARTS.length; index++) {
				BlockPos part = dockPos.offset(index % 3 - 1, 0, index / 3 - 1);
				level.setBlockAndUpdate(part, MorrowgearDrone.DOCK_PARTS[index].defaultBlockState());
			}
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
		private static final long TIMEOUT_TICKS = 520L;
		private final ServerPlayer player;
		private final ServerLevel level;
		private final SolarServiceStationEntity station;
		private final DroneEntity drone;
		private final long startedTick;
		private final Vec3 initialDronePosition;
		private final int initialBattery;
		private double previousAngle = Double.NaN;
		private double angularTravel;
		private int stableOrbitSamples;
		private boolean solarAssignmentObserved;
		private boolean complete;
		private String result = "SOLAR SERVICE RUNNING";

		private SolarServiceVerificationSession(ServerPlayer player) {
			this.player = player;
			this.level = player.level();
			Vec3 forward = player.getLookAngle().multiply(1, 0, 1);
			if (forward.lengthSqr() < 0.01) forward = new Vec3(0, 0, 1);
			forward = forward.normalize();
			Vec3 right = new Vec3(-forward.z, 0, forward.x);
			Vec3 center = player.position().add(forward.scale(22.0)).add(0, 10.0, 0);
			station = MorrowgearDrone.SOLAR_SERVICE_STATION.create(level, EntitySpawnReason.TRIGGERED);
			drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
			if (station == null || drone == null) throw new IllegalStateException("failed to create solar probes");
			station.deploy(player, BlockPos.containing(center));
			level.addFreshEntity(station);
			Vec3 aircraft = center.add(right.scale(13.0)).add(0, 1.5, 0);
			drone.setPos(aircraft.x, aircraft.y, aircraft.z);
			drone.initializeOwner(player);
			drone.assignGroup("VERIFY-SOLAR-SERVICE");
			drone.assignRole(DroneRole.SCOUT);
			drone.setMode(DroneMode.STANDBY);
			drone.setPowerForVerification(200, 1000);
			level.addFreshEntity(drone);
			startedTick = level.getGameTime();
			initialDronePosition = drone.position();
			initialBattery = drone.batteryPercent();
			say("SOLAR SERVICE START / 1 drone / standby diversion and orbit");
		}

		private void tick() {
			if (!player.isAlive() || !station.isAlive() || !drone.isAlive()) {
				finish(false, "probe removed before completion");
				return;
			}
			long elapsed = level.getGameTime() - startedTick;
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
			if (moved && orbiting && charging) {
				finish(true, "STANDBY>SOLAR DIVERT>CAPTURE>ORBIT / charge gained / samples="
					+ stableOrbitSamples);
				return;
			}
			if (elapsed >= TIMEOUT_TICKS) finish(false, "timeout / assigned=" + solarAssignmentObserved
				+ " moved=" + moved + " samples=" + stableOrbitSamples + " travel="
				+ String.format("%.2f", angularTravel) + " charge=" + drone.batteryPercent() + "%");
		}

		private void finish(boolean pass, String detail) {
			complete = true;
			result = "SOLAR SERVICE " + (pass ? "PASS" : "FAIL") + " / " + detail;
			say(result);
			drone.discard();
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
		private boolean verticalEscapeObserved;
		private boolean serviceInjected;
		private boolean serviceReturnObserved;
		private boolean serviceDockedObserved;
		private boolean serviceResumeObserved;
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
			this.player = player;
			this.level = player.level();
			this.serviceScenario = serviceScenario;
			this.verticalScenario = verticalScenario;
			this.base = player.blockPosition().below().offset(14, 0, 0);
			this.dockPos = base.offset(0, verticalScenario ? 12 : 1, 0);
			for (DroneEntity existing : level.getEntitiesOfClass(DroneEntity.class,
				new AABB(base).inflate(64), drone -> drone.isOwnedBy(player)
					&& drone.groupId().startsWith("VERIFY-SALVAGE"))) existing.discard();
			prepareSite();
			placeDock();
			carrier = createDrone(dockPos.getX() + .5, dockPos.getY() + .29,
				dockPos.getZ() + .5, "VERIFY-SALVAGE-CARRIER", DroneRole.SALVAGE);
			carrier.assignDock(dockPos);
			carrier.setDockedForCommission();
			load = createDrone(base.getX() + (verticalScenario ? 2.5 : 18.5),
				base.getY() + (verticalScenario ? 1.15 : 7.15),
				base.getZ() + .5, "VERIFY-SALVAGE-LOAD", DroneRole.SCOUT);
			load.setPowerForVerification(0, 1000);
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
				for (int y = 1; y <= 9; y++) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
			}
		}

		private void placeDock() {
			for (int index = 0; index < MorrowgearDrone.DOCK_PARTS.length; index++) {
				BlockPos part = dockPos.offset(index % 3 - 1, 0, index / 3 - 1);
				level.setBlockAndUpdate(part, MorrowgearDrone.DOCK_PARTS[index].defaultBlockState());
			}
			if (!(level.getBlockEntity(dockPos) instanceof DockBlockEntity dock)) {
				throw new IllegalStateException("salvage verifier Dock was not created");
			}
			dock.initialize(player);
			dock.setItem(DockBlockEntity.SLOT_RECOVERY_OUTPUT, ItemStack.EMPTY);
			dock.setItem(DockBlockEntity.SLOT_POWER_INPUT, new ItemStack(Items.CHARCOAL, 8));
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
				double horizontalFromDock = carrier.position().multiply(1, 0, 1)
					.distanceTo(Vec3.atCenterOf(dockPos).multiply(1, 0, 1));
				verticalEscapeObserved |= horizontalFromDock >= 8.0
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
				returnCruiseObserved |= horizontal >= 8.0 && carrier.getDeltaMovement().length() >= 0.35;
			}
			if (!load.isAlive()) {
				ItemStack output = level.getBlockEntity(dockPos) instanceof DockBlockEntity dock
					? dock.getItem(DockBlockEntity.SLOT_RECOVERY_OUTPUT) : ItemStack.EMPTY;
				if (!observed.contains(SalvageState.DELIVER)) {
					observed.add(SalvageState.DELIVER);
					say("SALVAGE STATE DELIVER / Dock output committed");
				}
				boolean transitions = observed.containsAll(EnumSet.allOf(SalvageState.class));
				boolean outputReady = output.is(MorrowgearDrone.DRONE_UNIT)
					&& StoredDroneState.read(output) != null;
				boolean finalState = carrier.salvageState() == SalvageState.IDLE
					&& carrier.hasDock() && carrier.mode() == DroneMode.DOCK;
				boolean servicePass = !serviceScenario || serviceInjected && serviceReturnObserved
					&& serviceDockedObserved && serviceResumeObserved;
				boolean verticalPass = !verticalScenario || verticalEscapeObserved;
				if (transitions && carrierDeparted && inertialFallObserved && !suspendedBeforeHook
					&& suspendedLoadObserved && returnedNearDock && returnCruiseObserved
					&& outputReady && finalState && servicePass && verticalPass) {
					finish(true, "POWER LOSS FALL>IDLE>INTERCEPT"
						+ (serviceScenario ? ">SERVICE>RESUME" : "")
						+ ">HOOK>HOIST>RETURN>DELIVER / Dock output ready");
				} else {
					finish(false, "transition=" + transitions + " departed=" + carrierDeparted
						+ " fall=" + inertialFallObserved + " preHookSuspended=" + suspendedBeforeHook
						+ " suspended=" + suspendedLoadObserved + " returned=" + returnedNearDock
						+ " cruise=" + returnCruiseObserved
						+ " output=" + outputReady + " final=" + finalState
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
			if (pass && carrier.isAlive()) carrier.discard();
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
		private final LivingEntity target;
		private final long endTick;

		private VisualEffectSession(ServerPlayer player) {
			this.player = player;
			ServerLevel level = player.level();
			Vec3 forward = player.getLookAngle().multiply(1, 0, 1);
			if (forward.lengthSqr() < 0.01) forward = new Vec3(0, 0, 1);
			forward = forward.normalize();
			Vec3 right = new Vec3(-forward.z, 0, forward.x);
			Vec3 targetPos = player.position().add(forward.scale(22.0));
			Zombie zombie = new Zombie(level);
			zombie.setPos(targetPos.x, player.getY(), targetPos.z);
			zombie.setNoAi(true);
			zombie.setInvulnerable(true);
			level.addFreshEntity(zombie);
			target = zombie;
			laser = visualDrone(level, player, player.position().add(forward.scale(7.0))
				.add(right.scale(-3.0)).add(0, 4.0, 0), "VERIFY-EFFECT-LASER");
			gun = visualDrone(level, player, player.position().add(forward.scale(7.0))
				.add(right.scale(3.0)).add(0, 4.0, 0), "VERIFY-EFFECT-GUN");
			laser.assignSecurityLoadout(SecurityLoadout.LASER);
			gun.assignSecurityLoadout(SecurityLoadout.AUTOCANNON);
			laser.holdCombatVisualForVerification(CombatState.LASER_FIRE,
				CombatWeapon.LASER, target, 160);
			gun.holdCombatVisualForVerification(CombatState.GUN_RUN,
				CombatWeapon.AUTOCANNON, target, 160);
			endTick = level.getGameTime() + 160L;
		}

		private boolean tick() {
			if (!player.isAlive() || player.level().getGameTime() < endTick) return false;
			cleanup();
			player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] EFFECTS COMPLETE / probes removed"));
			return true;
		}

		private void cleanup() {
			if (laser != null) laser.discard();
			if (gun != null) gun.discard();
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
		private int airspaceSeparationTicks;
		private int maxAirspaceSeparationTicks;
		private double bestAirspaceMinimumDistance;
		private double bestAirspaceAltitudeGap;
		private final Set<String> observedAirspaceGroups = new java.util.HashSet<>();
		private boolean triggerInterceptObserved;
		private boolean triggerCombatObserved;
		private boolean triggerCorrectTargetObserved;
		private int maximumHighThreatResponders;
		private int casImpactBlocksBefore;
		private int casImpactBlocksAfter;
		private int casInitialFlightPower;
		private int casInitialWeaponPower;
		private int casMinimumFlightPower;
		private int casMinimumWeaponPower;
		private double casMaximumRange;
		private double casMinimumHeight;
		private double casMaximumHeight;
		private final Set<String> adaptiveLaserFiringGroups = new HashSet<>();
		private boolean adaptiveCrossingGunShotObserved;
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
			this.base = scope == VerificationScope.ADAPTIVE_COMBAT
				? player.blockPosition().below().offset(72, 0, 0)
				: player.blockPosition().below();
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
			if (scope == VerificationScope.ADAPTIVE_COMBAT) {
				if (combatProbe != null && combatProbe.isAlive()) combatProbe.discard();
				cleanupVerifierDrones();
			}
		}

		private void buildCases() {
			cases.add(new CaseStep("sandbox setup", 5, this::setupSandbox, () -> {
				int expectedDrones = scope == VerificationScope.ADAPTIVE_COMBAT ? 6 : 24;
				int expectedDocks = scope == VerificationScope.ADAPTIVE_COMBAT ? 1 : 4;
				require(drones.size() == expectedDrones,
					"expected " + expectedDrones + " verifier drones, got " + drones.size());
				require(docks.size() == expectedDocks,
					"expected " + expectedDocks + " verifier docks, got " + docks.size());
				require(level.getBlockEntity(cargoSource) instanceof Container, "cargo source chest missing");
				require(level.getBlockEntity(cargoTarget) instanceof Container, "cargo target chest missing");
			}).scopedTo(VerificationScope.NAVIGATION, VerificationScope.FIELD,
				VerificationScope.COMBAT, VerificationScope.ADAPTIVE_COMBAT, VerificationScope.REGRESSION));
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
			cases.add(new CaseStep("waypoint formation sizes", 180, this::assignWaypointMatrix,
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
			cases.add(new CaseStep("laser temporary formation stability", 220,
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
			cases.add(new CaseStep("high power enemy dynamic reinforcement", 260,
				this::assignHighThreatReinforcement, this::assertHighThreatReinforcement)
				.scopedTo(VerificationScope.COMBAT));
			cases.add(new CaseStep("linear autocannon strike and split power", 620,
				this::assignLinearAutocannonStrike, this::assertLinearAutocannonStrike)
				.scopedTo(VerificationScope.COMBAT));
			cases.add(new CaseStep("adaptive multi-group laser reservation", 360,
				this::assignAdaptiveLaserGroups, this::assertAdaptiveLaserGroups)
				.scopedTo(VerificationScope.ADAPTIVE_COMBAT));
			cases.add(new CaseStep("adaptive route intercept and resume", 360,
				this::assignAdaptiveRouteIntercept, this::assertAdaptiveRouteIntercept)
				.scopedTo(VerificationScope.ADAPTIVE_COMBAT));
			cases.add(new CaseStep("adaptive weapon recharge relief rotation", 1400,
				this::assignAdaptiveRechargeRotation, this::assertAdaptiveRechargeRotation,
				() -> adaptiveRechargeRtbObserved && adaptiveReplacementObserved
					&& adaptiveOriginalMissionResumed).scopedTo(VerificationScope.ADAPTIVE_COMBAT));
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
			cases.add(new CaseStep("engineer solo forestry real blocks", 600,
				this::assignEngineerSoloForestry, this::assertEngineerSoloForestry));
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
			int dockCount = scope == VerificationScope.ADAPTIVE_COMBAT ? 1 : 4;
			for (int i = 0; i < dockCount; i++) {
				BlockPos dock = base.offset(-12 + i * 5, 1, 8);
				placeDock(dock);
				docks.add(dock);
			}
			int droneCount = scope == VerificationScope.ADAPTIVE_COMBAT ? 6 : 24;
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
					laserOrbitSamples++;
					maxLaserOrbitSpeed = Math.max(maxLaserOrbitSpeed, drone.getDeltaMovement().length());
				}
			}
			observeLaserFormation();
			observeCombatAirspace();
			observeCombatTrigger();
			observeHighThreatReinforcement();
			observeLinearAutocannonStrike();
			observeAdaptiveCombat();
		}

		private void assignAdaptiveLaserGroups() {
			clearHostiles(96);
			FleetThreatNetwork.clear();
			CombatTelemetry.clear();
			CombatAirspaceRegistry.clear();
			adaptiveLaserFiringGroups.clear();
			adaptiveCrossingGunShotObserved = false;
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
				drone.assignSecurityPatrol(anchor, 18, "adapt-gun-cross");
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
			require(adaptiveCrossingGunShotObserved,
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
			LivingEntity target = spawnZombie(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
			if (target.getAttribute(Attributes.MAX_HEALTH) != null) {
				target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
				target.setHealth(20.0f);
			}
			target.setInvulnerable(true);
			if (target instanceof Mob mob) mob.setTarget(player);
			for (DroneEntity drone : drones) {
				drone.setEmergencyInterceptForVerification(target.getId(), target.position(), 3);
			}
			combatProbe = target;
		}

		private void assertAdaptiveRechargeRotation() {
			require(adaptiveRechargeRtbObserved, "empty weapon reserve did not trigger Dock RTB");
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
				}
				if (drone.groupId().equals("VERIFY-ADAPT-GUN-CROSS")
					&& drone.combatShotAge() <= 1) adaptiveCrossingGunShotObserved = true;
			}
			boolean bothLaserGroupsFiring = adaptiveLaserFiringGroups.contains("VERIFY-ADAPT-LASER-A")
				&& adaptiveLaserFiringGroups.contains("VERIFY-ADAPT-LASER-B");
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
				if (adaptiveCrossingGunShotObserved && lockedLasers.size() >= 4) {
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
				Set<UUID> activeNow = drones.stream().filter(drone -> drone.groupId().equals("VERIFY-ADAPT-ROTATION")
					&& drone.combatActive()).map(DroneEntity::getUUID).collect(java.util.stream.Collectors.toSet());
				if (adaptiveInitialResponders.isEmpty() && activeNow.size() >= 3) adaptiveInitialResponders.addAll(activeNow);
				if (!adaptivePowerDepleted && adaptiveInitialResponders.contains(adaptiveRechargeOriginal.getUUID())) {
					adaptiveRechargeOriginal.setPowerForVerification(
						adaptiveRechargeOriginal.batteryPercent() * 10, 0);
					adaptivePowerDepleted = true;
				}
				adaptiveRechargeRtbObserved |= adaptiveRechargeOriginal.weaponRechargeActive()
					|| adaptiveRechargeOriginal.isDocked();
				if (adaptiveRechargeRtbObserved) adaptiveReplacementObserved |= activeNow.stream()
					.anyMatch(id -> !adaptiveInitialResponders.contains(id));
				if (adaptiveRechargeRtbObserved && !adaptiveRechargeOriginal.weaponRechargeActive()
					&& !adaptiveRechargeOriginal.isDocked() && !adaptiveRechargeOriginal.combatActive()
					&& adaptiveRechargeOriginal.hasSecurityPatrol()
					&& adaptiveRechargeOriginal.batteryPercent() >= CombatPolicy.URGENT_SORTIE_POWER
					&& adaptiveRechargeOriginal.weaponPowerPercent() >= CombatPolicy.URGENT_SORTIE_POWER) {
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
					if (gun.combatState() != CombatState.GUN_RUN) {
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

		private void assignHighThreatReinforcement() {
			clearHostiles(128);
			FleetThreatNetwork.clear();
			CombatTelemetry.clear();
			maximumHighThreatResponders = 0;
			for (DroneEntity drone : drones) {
				reset(drone);
				drone.assignRole(DroneRole.FIELD);
				drone.setPowerForVerification(1000, 1000);
			}
			for (int i = 0; i < 8; i++) {
				DroneEntity guard = drones.get(i);
				guard.assignRole(DroneRole.SECURITY);
				guard.assignGroup("VERIFY-HIGH-THREAT");
				guard.setPos(player.getX() - 9.0 + i * 2.2, player.getY() + 4.0, player.getZ() - 7.0);
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
			int responders = (int)drones.stream().filter(drone -> drone.groupId().equals("VERIFY-HIGH-THREAT")
				&& drone.combatActive() && drone.combatTargetId() == combatProbe.getId()).count();
			maximumHighThreatResponders = Math.max(maximumHighThreatResponders, responders);
		}

		private void assertHighThreatReinforcement() {
			require(maximumHighThreatResponders >= 7,
				"high-power enemy received too few responders: " + maximumHighThreatResponders);
		}

		private void assignLinearAutocannonStrike() {
			clearHostiles(160);
			FleetThreatNetwork.clear();
			CombatTelemetry.clear();
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
			casMinimumFlightPower = casInitialFlightPower;
			casMinimumWeaponPower = casInitialWeaponPower;
			casMaximumRange = 0.0;
			casMinimumHeight = Double.POSITIVE_INFINITY;
			casMaximumHeight = Double.NEGATIVE_INFINITY;
		}

		private void observeLinearAutocannonStrike() {
			if (combatProbe == null || !combatProbe.isAlive()) return;
			DroneEntity gunner = drones.stream().filter(drone -> drone.groupId().equals("VERIFY-CAS-LINE"))
				.findFirst().orElse(null);
			if (gunner == null || gunner.combatWeapon() != CombatWeapon.AUTOCANNON) return;
			casMinimumFlightPower = Math.min(casMinimumFlightPower, gunner.batteryPercent());
			casMinimumWeaponPower = Math.min(casMinimumWeaponPower, gunner.weaponPowerPercent());
			casMaximumRange = Math.max(casMaximumRange, gunner.position().multiply(1, 0, 1)
				.distanceTo(combatProbe.position().multiply(1, 0, 1)));
			double relativeY = gunner.getY() - combatProbe.getY();
			casMinimumHeight = Math.min(casMinimumHeight, relativeY);
			casMaximumHeight = Math.max(casMaximumHeight, relativeY);
		}

		private void assertLinearAutocannonStrike() {
			BlockPos anchor = base.offset(72, 1, 0);
			casImpactBlocksAfter = countCasImpactBlocks(anchor);
			require(casMaximumRange >= 42.0, "CAS did not use the extended approach: " + casMaximumRange);
			require(casMaximumHeight - casMinimumHeight >= 8.0,
				"CAS did not descend and climb through the strike: " + casMinimumHeight + ".." + casMaximumHeight);
			require(casImpactBlocksAfter < casImpactBlocksBefore,
				"linear autocannon impacts did not produce bounded terrain damage");
			require(casMinimumWeaponPower < casInitialWeaponPower,
				"weapon power did not drain during autocannon fire");
			require(casMinimumFlightPower > 0,
				"flight reserve was exhausted while weapon power remained independently available");
			require(casMinimumFlightPower != casMinimumWeaponPower,
				"flight and weapon reserves did not evolve independently");
		}

		private void prepareIsolatedCombatFleet() {
			CombatTelemetry.clear();
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
			}
			List<DroneEntity> firing = formation.stream()
				.filter(drone -> drone.combatState() == CombatState.LASER_FIRE).toList();
			simultaneousLaserFire = Math.max(simultaneousLaserFire, firing.size());
			if (firing.size() != formation.size() || firing.size() < 2) return;
			List<Double> angles = firing.stream().map(drone -> Math.atan2(
				drone.getZ() - combatProbe.getZ(), drone.getX() - combatProbe.getX()))
				.sorted().toList();
			double ideal = Math.PI * 2.0 / angles.size();
			double error = 0.0;
			for (int index = 0; index < angles.size(); index++) {
				double current = angles.get(index);
				double next = index + 1 < angles.size() ? angles.get(index + 1)
					: angles.getFirst() + Math.PI * 2.0;
				error = Math.max(error, Math.abs((next - current) - ideal));
			}
			bestLaserGapError = Math.min(bestLaserGapError, error);
			laserSpacingStableTicks = error <= 0.38 ? laserSpacingStableTicks + 1 : 0;
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
			require(maxLaserOrbitSpeed <= 0.48,
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
		}

		private void assertLaserFormation() {
			require(simultaneousLaserFire == 4,
				"laser formation did not reach four-aircraft simultaneous fire: " + simultaneousLaserFire);
			require(bestLaserGapError <= 0.38,
				"laser formation angular spacing remained uneven: " + bestLaserGapError);
			require(maxLaserSpacingStableTicks >= 40,
				"laser formation did not sustain even spacing for 40 ticks: "
					+ maxLaserSpacingStableTicks);
			require(!laserCombatDropped,
				"laser combat lock dropped while the assigned target remained alive");
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
			require(level.getBlockState(anchor).is(Blocks.OAK_SAPLING),
				"engineer solo forestry did not replace the felled trunk with a sapling");
			for (int y = 1; y < 4; y++) require(!level.getBlockState(anchor.above(y)).is(BlockTags.LOGS),
				"engineer solo forestry left trunk at y+" + y);
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
			require(!scout.isDocked() && !engineer.isDocked() && !cargo.isDocked() && !guard.isDocked(),
				type + " field launch retained a stale docked state");
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
				boolean returningToDock = drone.hasDock()
					&& (drone.mode() == DroneMode.DOCK || drone.isDocked());
				require(returnedToOwner || returningToDock,
					label + " did not autonomously recover " + drone.unitId() + " / " + drone.mode());
			}
		}

		private boolean fieldTeamReady(int startIndex, int count) {
			if (drones.size() < startIndex + count) return false;
			for (DroneEntity drone : drones.subList(startIndex, startIndex + count)) {
				if (drone.fieldOperationState() != FieldOperationState.COMPLETE) return false;
				boolean recovering = drone.mode() == DroneMode.RETURN || drone.mode() == DroneMode.FOLLOW
					|| drone.hasDock() && (drone.mode() == DroneMode.DOCK || drone.isDocked());
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
			drone.assignDock(dock);
			drone.setPos(dock.getX() - 7.5, dock.getY() + 2.0, dock.getZ() + 0.5);
			drone.setMode(DroneMode.DOCK);
		}

		private void assertObstructedDockReturn() {
			BlockPos dock = base.offset(30, 1, 8);
			DroneEntity drone = drones.get(4);
			boolean docked = drone.isDocked();
			double distance = drone.position().distanceTo(Vec3.atCenterOf(dock));
			clearVolume(dock, 8, 0, 8);
			require(docked, "drone did not find the only open east approach; distance=" + String.format("%.2f", distance));
		}

		private void assignObstructedRegroup() {
			BlockPos wall = base.offset(26, 1, 0);
			clearVolume(wall, 8, 0, 8);
			for (int z = -6; z <= 6; z++) for (int y = 0; y <= 7; y++) {
				level.setBlockAndUpdate(wall.offset(0, y, z), Blocks.SMOOTH_STONE.defaultBlockState());
			}
			List<DroneEntity> wing = drones.subList(8, 12);
			for (int i = 0; i < wing.size(); i++) {
				DroneEntity drone = wing.get(i);
				reset(drone);
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
			BlockPos wall = base.offset(18, 1, 0);
			clearGiantWall(wall);
			for (int z = -40; z <= 40; z++) for (int y = 0; y < 52; y++) {
				level.setBlockAndUpdate(wall.offset(0, y, z), Blocks.SMOOTH_STONE.defaultBlockState());
			}
			List<DroneEntity> wing = drones.subList(13, 17);
			for (int i = 0; i < wing.size(); i++) {
				DroneEntity drone = wing.get(i);
				reset(drone);
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
			clearGiantWall(wall);
			require(crossed == wing.size(), "large obstacle trapped formation: " + crossed + "/" + wing.size());
			require(alive == wing.size(), "large obstacle destroyed formation members: " + alive + "/" + wing.size());
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
				BlockPos part = center.offset(i % 3 - 1, 0, i / 3 - 1);
				level.setBlockAndUpdate(part, MorrowgearDrone.DOCK_PARTS[i].defaultBlockState());
			}
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
