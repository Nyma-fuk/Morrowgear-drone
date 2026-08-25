package jp.morrowgear.drone;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class VerificationArenaBuilder {
	static final int WIDTH = 500;
	static final int DEPTH = 500;
	static final int HEIGHT = 300;
	private static final int TORCH_SPACING = 5;
	private static final int BLOCKS_PER_TICK = 4096;
	private static final int UPDATE_FLAGS = 18;

	private final ServerLevel level;
	private final UUID playerId;
	private final int minX;
	private final int minZ;
	private final int floorY;
	private final long shellBlocks;
	private final long torchBlocks;
	private final long totalBlocks;
	private Phase phase = Phase.FLOOR;
	private int cursor;
	private long completed;
	private int announcedPercent = -5;

	VerificationArenaBuilder(ServerPlayer player) {
		this.level = player.level();
		this.playerId = player.getUUID();
		this.minX = player.blockPosition().getX() - WIDTH / 2;
		this.minZ = player.blockPosition().getZ() - DEPTH / 2;
		this.floorY = Math.max(level.getMinY() + 1,
			Math.min(player.blockPosition().getY() - 1, level.getMaxY() - HEIGHT));
		long floorAndCeiling = (long)WIDTH * DEPTH * 2L;
		long northAndSouth = (long)WIDTH * (HEIGHT - 2) * 2L;
		long westAndEast = (long)(DEPTH - 2) * (HEIGHT - 2) * 2L;
		this.shellBlocks = floorAndCeiling + northAndSouth + westAndEast;
		this.torchBlocks = (long)gridCount(WIDTH) * gridCount(DEPTH);
		this.totalBlocks = shellBlocks + torchBlocks;
	}

	void tick(MinecraftServer server) {
		if (done()) return;
		int budget = BLOCKS_PER_TICK;
		while (budget-- > 0 && !done()) placeNext();
		int percent = (int)Math.min(100L, completed * 100L / Math.max(1L, totalBlocks));
		if (percent >= announcedPercent + 5 || done()) {
			announcedPercent = percent - percent % 5;
			announce(server, done() ? "ARENA COMPLETE 500x500x300 / spawn lighting online"
				: "ARENA BUILD " + percent + "% / " + phase.label);
		}
	}

	boolean done() {
		return phase == Phase.DONE;
	}

	String status() {
		if (done()) return "arena 500x500x300 ready";
		int percent = (int)Math.min(99L, completed * 100L / Math.max(1L, totalBlocks));
		return "arena " + percent + "% / " + phase.label;
	}

	BlockPos centerFloor() {
		return new BlockPos(minX + WIDTH / 2, floorY, minZ + DEPTH / 2);
	}

	private void placeNext() {
		switch (phase) {
			case FLOOR -> placePlane(floorY, Blocks.SMOOTH_STONE.defaultBlockState(), Phase.CEILING);
			case CEILING -> placePlane(floorY + HEIGHT - 1,
				Blocks.SMOOTH_STONE.defaultBlockState(), Phase.NORTH_WALL);
			case NORTH_WALL -> placeNorthSouthWall(minZ,
				Blocks.SMOOTH_STONE.defaultBlockState(), Phase.SOUTH_WALL);
			case SOUTH_WALL -> placeNorthSouthWall(minZ + DEPTH - 1,
				Blocks.SMOOTH_STONE.defaultBlockState(), Phase.WEST_WALL);
			case WEST_WALL -> placeWestEastWall(minX,
				Blocks.SMOOTH_STONE.defaultBlockState(), Phase.EAST_WALL);
			case EAST_WALL -> placeWestEastWall(minX + WIDTH - 1,
				Blocks.SMOOTH_STONE.defaultBlockState(), Phase.TORCH_GRID);
			case TORCH_GRID -> placeTorch();
			case DONE -> { }
		}
	}

	private void placePlane(int y, BlockState state, Phase next) {
		int x = cursor % WIDTH;
		int z = cursor / WIDTH;
		set(minX + x, y, minZ + z, state);
		advance(WIDTH * DEPTH, next);
	}

	private void placeNorthSouthWall(int z, BlockState state, Phase next) {
		int x = cursor % WIDTH;
		int y = cursor / WIDTH;
		set(minX + x, floorY + 1 + y, z, state);
		advance(WIDTH * (HEIGHT - 2), next);
	}

	private void placeWestEastWall(int x, BlockState state, Phase next) {
		int zSpan = DEPTH - 2;
		int z = cursor % zSpan;
		int y = cursor / zSpan;
		set(x, floorY + 1 + y, minZ + 1 + z, state);
		advance(zSpan * (HEIGHT - 2), next);
	}

	private void placeTorch() {
		int countX = gridCount(WIDTH);
		int gx = cursor % countX;
		int gz = cursor / countX;
		int x = Math.min(WIDTH - 2, 2 + gx * TORCH_SPACING);
		int z = Math.min(DEPTH - 2, 2 + gz * TORCH_SPACING);
		set(minX + x, floorY + 1, minZ + z, Blocks.TORCH.defaultBlockState());
		advance((int)torchBlocks, Phase.DONE);
	}

	private void set(int x, int y, int z, BlockState state) {
		level.setBlock(new BlockPos(x, y, z), state, UPDATE_FLAGS);
	}

	private void advance(int phaseSize, Phase next) {
		cursor++;
		completed++;
		if (cursor >= phaseSize) {
			cursor = 0;
			phase = next;
		}
	}

	private void announce(MinecraftServer server, String message) {
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player != null) player.sendSystemMessage(Component.literal("[MORROWGEAR VERIFY] " + message));
		MorrowgearDrone.LOGGER.info("[MORROWGEAR VERIFY] {}", message);
	}

	private static int gridCount(int span) {
		return (span - 3) / TORCH_SPACING + 1;
	}

	private enum Phase {
		FLOOR("floor"), CEILING("ceiling"), NORTH_WALL("north wall"),
		SOUTH_WALL("south wall"), WEST_WALL("west wall"), EAST_WALL("east wall"),
		TORCH_GRID("torch grid"), DONE("ready");

		private final String label;

		Phase(String label) {
			this.label = label;
		}
	}
}
