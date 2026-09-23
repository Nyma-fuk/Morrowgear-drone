package jp.morrowgear.drone;

import com.mojang.brigadier.Command;
import java.util.List;

import jp.morrowgear.drone.MorrowgearBaseLayout.DockStation;
import jp.morrowgear.drone.block.DockBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class MorrowgearBaseGenerator {
	private static final String BASE_GROUP_PREFIX = "BASE-WING-";
	private static final String[] WINGS = { "ALPHA", "BRAVO", "CHARLIE", "DELTA" };
	private static final DroneRole[] WING_ROLES = {
		DroneRole.SCOUT, DroneRole.SECURITY, DroneRole.SECURITY, DroneRole.ENGINEER,
		DroneRole.CARGO, DroneRole.SECURITY, DroneRole.FIELD, DroneRole.SCOUT
	};
	private static final SecurityLoadout[] SECURITY_LOADOUTS = {
		SecurityLoadout.UNARMED, SecurityLoadout.AUTOCANNON, SecurityLoadout.LASER,
		SecurityLoadout.UNARMED, SecurityLoadout.UNARMED, SecurityLoadout.MISSILE,
		SecurityLoadout.UNARMED, SecurityLoadout.UNARMED
	};

	private MorrowgearBaseGenerator() {}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("morrowgear_base")
				.then(Commands.literal("create").executes(context -> withPlayer(context.getSource().getEntity(),
					MorrowgearBaseGenerator::create)))
				.then(Commands.literal("wave")
					.then(Commands.literal("contact").executes(context -> launchWave(context.getSource(), Wave.CONTACT)))
					.then(Commands.literal("assault").executes(context -> launchWave(context.getSource(), Wave.ASSAULT)))
					.then(Commands.literal("siege").executes(context -> launchWave(context.getSource(), Wave.SIEGE))))
				.then(Commands.literal("defense").executes(context -> startDefense(context.getSource())))
			)
		);
		ServerTickEvents.END_SERVER_TICK.register(BaseDefenseOperation::tick);
	}

	private static int withPlayer(Entity entity, java.util.function.Consumer<ServerPlayer> action) {
		if (!(entity instanceof ServerPlayer player)) return 0;
		action.accept(player);
		return Command.SINGLE_SUCCESS;
	}

	private static void create(ServerPlayer player) {
		ServerLevel level = player.level();
		BlockPos center = player.blockPosition().below();
		removePreviousBaseDrones(level, player, center);
		clearInteriorHostiles(level, center);
		clearVolume(level, center);
		buildFloorAndWalls(level, center);
		buildDockBays(level, player, center);
		buildCommandConsole(level, center);
		spawnDockedFleet(level, player, center);
		player.sendSystemMessage(Component.literal("[MORROWGEAR BASE] ONLINE / 80x80 / 32 DOCKS / 4 WINGS"));
		player.sendSystemMessage(Component.literal("[MORROWGEAR BASE] 中央コンソール: DEFENSE / CONTACT / ASSAULT / SIEGE"));
	}

	private static void clearVolume(ServerLevel level, BlockPos center) {
		for (int x = -MorrowgearBaseLayout.HALF_SIZE; x < MorrowgearBaseLayout.HALF_SIZE; x++) {
			for (int z = -MorrowgearBaseLayout.HALF_SIZE; z < MorrowgearBaseLayout.HALF_SIZE; z++) {
				for (int y = 1; y <= 14; y++) level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
			}
		}
	}

	private static void buildFloorAndWalls(ServerLevel level, BlockPos center) {
		int half = MorrowgearBaseLayout.HALF_SIZE;
		for (int x = -half; x < half; x++) for (int z = -half; z < half; z++) {
			boolean route = Math.abs(x) <= 2 || Math.abs(z) <= 2;
			boolean light = Math.floorMod(x + half, 8) == 4 && Math.floorMod(z + half, 8) == 4;
			level.setBlock(center.offset(x, 0, z), light ? Blocks.SEA_LANTERN.defaultBlockState()
				: route ? Blocks.IRON_BLOCK.defaultBlockState() : Blocks.SMOOTH_STONE_SLAB.defaultBlockState(), 2);
			boolean perimeter = x <= -half + 1 || x >= half - 2 || z <= -half + 1 || z >= half - 2;
			if (!perimeter || MorrowgearBaseLayout.gateOpening(x, z)) continue;
			for (int y = 1; y <= 5; y++) {
				boolean accent = y == 3 && (Math.floorMod(x, 6) == 0 || Math.floorMod(z, 6) == 0);
				level.setBlock(center.offset(x, y, z), accent ? Blocks.IRON_BLOCK.defaultBlockState()
					: Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), 2);
			}
		}
		buildGateArch(level, center, Direction.NORTH);
		buildGateArch(level, center, Direction.SOUTH);
		buildGateArch(level, center, Direction.WEST);
		buildGateArch(level, center, Direction.EAST);
	}

	private static void buildGateArch(ServerLevel level, BlockPos center, Direction direction) {
		int half = MorrowgearBaseLayout.HALF_SIZE;
		for (int lateral = -5; lateral <= 5; lateral++) for (int height = 1; height <= 6; height++) {
			if (Math.abs(lateral) <= MorrowgearBaseLayout.GATE_HALF_WIDTH && height <= 4) continue;
			int x = direction.getAxis() == Direction.Axis.Z ? lateral : direction.getStepX() * (half - 1);
			int z = direction.getAxis() == Direction.Axis.X ? lateral : direction.getStepZ() * (half - 1);
			level.setBlock(center.offset(x, height, z), height == 5 ? Blocks.IRON_BLOCK.defaultBlockState()
				: Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), 2);
		}
	}

	private static void buildDockBays(ServerLevel level, ServerPlayer player, BlockPos center) {
		for (DockStation station : MorrowgearBaseLayout.dockStations()) {
			BlockPos dock = center.offset(station.x(), station.y() + 1, station.z());
			if (station.y() > 0) buildUpperSupport(level, dock);
			placeDock(level, player, dock);
		}
	}

	private static void buildUpperSupport(ServerLevel level, BlockPos dock) {
		for (int x : new int[] { -2, 2 }) for (int z : new int[] { -2, 2 }) {
			for (int y = -7; y <= -1; y++) level.setBlock(dock.offset(x, y, z),
				Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), 2);
		}
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
			if (Math.abs(x) == 2 || Math.abs(z) == 2) level.setBlock(dock.offset(x, -1, z),
				Blocks.IRON_BLOCK.defaultBlockState(), 2);
		}
	}

	private static void placeDock(ServerLevel level, ServerPlayer player, BlockPos center) {
		for (int index = 0; index < MorrowgearDrone.WIDE_DOCK_PARTS.length; index++) {
			BlockPos part = center.offset(index % 5 - 2, 0, index / 5 - 2);
			level.setBlockAndUpdate(part, MorrowgearDrone.WIDE_DOCK_PARTS[index].defaultBlockState());
		}
		if (level.getBlockEntity(center) instanceof DockBlockEntity dock) dock.initialize(player);
	}

	private static void buildCommandConsole(ServerLevel level, BlockPos center) {
		BlockPos console = center.offset(0, 0, 8);
		for (int x = -5; x <= 5; x++) for (int z = -2; z <= 2; z++) {
			level.setBlock(console.offset(x, 0, z), Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), 2);
		}
		placeWaveButton(level, console.offset(-3, 2, 0), "morrowgear_base wave contact");
		placeWaveButton(level, console.offset(0, 2, 0), "morrowgear_base wave assault");
		placeWaveButton(level, console.offset(3, 2, 0), "morrowgear_base wave siege");
		placeWaveButton(level, console.offset(0, 2, -2), "morrowgear_base defense");
		for (int x : new int[] { -5, 5 }) for (int z : new int[] { -3, 3 }) {
			level.setBlock(console.offset(x, 1, z), Blocks.SEA_LANTERN.defaultBlockState(), 2);
		}
	}

	private static void placeWaveButton(ServerLevel level, BlockPos commandPos, String command) {
		level.setBlockAndUpdate(commandPos, Blocks.COMMAND_BLOCK.defaultBlockState());
		if (level.getBlockEntity(commandPos) instanceof CommandBlockEntity commandBlock) {
			commandBlock.getCommandBlock().setCommand(command);
			commandBlock.getCommandBlock().setTrackOutput(false);
			commandBlock.setAutomatic(false);
			commandBlock.setChanged();
		}
		level.setBlockAndUpdate(commandPos.above(), Blocks.STONE_BUTTON.defaultBlockState()
			.setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
			.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
	}

	private static void spawnDockedFleet(ServerLevel level, ServerPlayer player, BlockPos center) {
		List<DockStation> stations = MorrowgearBaseLayout.dockStations();
		for (int index = 0; index < stations.size(); index++) {
			DockStation station = stations.get(index);
			BlockPos dock = center.offset(station.x(), station.y() + 1, station.z());
			DroneEntity drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
			if (drone == null) continue;
			int wing = index / 8;
			int slot = index % 8;
			drone.setPos(dock.getX() + 0.5, dock.getY() + 0.316, dock.getZ() + 0.5);
			drone.initializeOwner(player);
			drone.assignGroup(BASE_GROUP_PREFIX + WINGS[wing]);
			drone.assignRole(WING_ROLES[slot]);
			drone.assignSecurityLoadout(SECURITY_LOADOUTS[slot]);
			drone.assignDock(dock);
			drone.setMode(DroneMode.STANDBY);
			drone.setDockedForVerification(true);
			level.addFreshEntity(drone);
			if (level.getBlockEntity(dock) instanceof DockBlockEntity serviceDock) {
				serviceDock.setItem(DockBlockEntity.SLOT_POWER_INPUT, new ItemStack(MorrowgearDrone.POWER_CELL, 16));
				serviceDock.setItem(DockBlockEntity.SLOT_REPAIR, new ItemStack(Items.IRON_INGOT, 32));
				serviceDock.setItem(DockBlockEntity.SUPPLY_BUFFER_START, new ItemStack(SupplyItems.LASER_CELL, 16));
				if (SECURITY_LOADOUTS[slot] == SecurityLoadout.AUTOCANNON)
					serviceDock.setItem(DockBlockEntity.SLOT_AMMUNITION, new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 16));
				else if (SECURITY_LOADOUTS[slot] == SecurityLoadout.MISSILE)
					serviceDock.setItem(DockBlockEntity.SLOT_AMMUNITION, new ItemStack(SupplyItems.MICRO_MISSILE_PACK, 16));
			}
		}
	}

	private static void removePreviousBaseDrones(ServerLevel level, ServerPlayer player, BlockPos center) {
		for (DroneEntity drone : level.getEntitiesOfClass(DroneEntity.class,
			new net.minecraft.world.phys.AABB(center).inflate(64, 32, 64),
			value -> value.isOwnedBy(player) && value.groupId().startsWith(BASE_GROUP_PREFIX))) drone.discard();
	}

	private static void clearInteriorHostiles(ServerLevel level, BlockPos center) {
		for (Mob mob : level.getEntitiesOfClass(Mob.class,
			new net.minecraft.world.phys.AABB(center).inflate(MorrowgearBaseLayout.HALF_SIZE - 2, 24,
				MorrowgearBaseLayout.HALF_SIZE - 2), value -> value instanceof Enemy)) mob.discard();
	}

	private static int launchWave(CommandSourceStack source, Wave wave) {
		ServerPlayer player = source.getEntity() instanceof ServerPlayer direct ? direct
			: source.getLevel().players().stream()
				.min(java.util.Comparator.comparingDouble(candidate -> candidate.position().distanceToSqr(source.getPosition())))
				.orElse(null);
		if (player == null) return 0;
		BlockPos center = source.getEntity() instanceof ServerPlayer ? player.blockPosition().below()
			: BlockPos.containing(source.getPosition()).offset(-wave.consoleX, -2, -8);
		ServerLevel level = player.level();
		spawnWave(level, player, center, wave);
		player.sendSystemMessage(Component.literal("[MORROWGEAR BASE] " + wave.name()
			+ " WAVE / HOSTILES " + wave.total()));
		return Command.SINGLE_SUCCESS;
	}

	private static int startDefense(CommandSourceStack source) {
		ServerPlayer player = source.getEntity() instanceof ServerPlayer direct ? direct
			: source.getLevel().players().stream()
				.min(java.util.Comparator.comparingDouble(candidate -> candidate.position().distanceToSqr(source.getPosition())))
				.orElse(null);
		if (player == null) return 0;
		BlockPos center = source.getEntity() instanceof ServerPlayer ? player.blockPosition().below()
			: BlockPos.containing(source.getPosition()).offset(0, -2, -6);
		BaseDefenseOperation.start(player, center);
		player.sendSystemMessage(Component.literal("[MORROWGEAR BASE] DEFENSE OPERATION / ALERT"));
		return Command.SINGLE_SUCCESS;
	}

	static void launchOperationWave(ServerLevel level, ServerPlayer player, BlockPos center, int index) {
		Wave wave = Wave.values()[Math.max(0, Math.min(Wave.values().length - 1, index))];
		spawnWave(level, player, center, wave);
		player.sendSystemMessage(Component.literal("[MORROWGEAR BASE] " + wave.name()
			+ " WAVE / HOSTILES " + wave.total()));
	}

	private static void spawnWave(ServerLevel level, ServerPlayer player, BlockPos center, Wave wave) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos gate = center.relative(direction, MorrowgearBaseLayout.HALF_SIZE + 5);
			spawn(level, player, EntityTypes.ZOMBIE, gate, wave.zombiesPerGate);
			spawn(level, player, EntityTypes.SKELETON, gate.offset(direction.getClockWise().getStepX() * 2, 0,
				direction.getClockWise().getStepZ() * 2), wave.skeletonsPerGate);
			spawn(level, player, EntityTypes.PILLAGER, gate.offset(direction.getCounterClockWise().getStepX() * 2, 0,
				direction.getCounterClockWise().getStepZ() * 2), wave.pillagersPerGate);
			spawn(level, player, EntityTypes.RAVAGER, gate.relative(direction, 2), wave.ravagersPerGate);
		}
	}

	private static <T extends Mob> void spawn(ServerLevel level, ServerPlayer player,
		EntityType<T> type, BlockPos origin, int count) {
		for (int index = 0; index < count; index++) {
			T mob = type.create(level, EntitySpawnReason.COMMAND);
			if (mob == null) continue;
			int lateral = index % 5 - 2;
			int depth = index / 5;
			mob.setPos(origin.getX() + 0.5 + lateral * 1.4, origin.getY() + 1,
				origin.getZ() + 0.5 + depth * 1.4);
			mob.setPersistenceRequired();
			mob.setTarget(player);
			level.addFreshEntity(mob);
		}
	}

	private enum Wave {
		CONTACT(-3, 6, 0, 0, 0),
		ASSAULT(0, 8, 3, 1, 0),
		SIEGE(3, 12, 4, 2, 1);

		final int consoleX;
		final int zombiesPerGate;
		final int skeletonsPerGate;
		final int pillagersPerGate;
		final int ravagersPerGate;

		Wave(int consoleX, int zombiesPerGate, int skeletonsPerGate, int pillagersPerGate, int ravagersPerGate) {
			this.consoleX = consoleX;
			this.zombiesPerGate = zombiesPerGate;
			this.skeletonsPerGate = skeletonsPerGate;
			this.pillagersPerGate = pillagersPerGate;
			this.ravagersPerGate = ravagersPerGate;
		}

		int total() {
			return 4 * (zombiesPerGate + skeletonsPerGate + pillagersPerGate + ravagersPerGate);
		}
	}
}
