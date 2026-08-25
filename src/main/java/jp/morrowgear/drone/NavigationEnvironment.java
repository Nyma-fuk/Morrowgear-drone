package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

final class NavigationEnvironment {
	private static final Map<UUID, CachedSnapshot> CACHE = new HashMap<>();
	private static final Map<UUID, Vec3> TRAVEL_HEADINGS = new HashMap<>();
	private static final int[][] DIRECTIONS = {
		{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private NavigationEnvironment() {
	}

	static Snapshot assess(ServerLevel level, ServerPlayer owner) {
		long tick = level.getGameTime();
		CachedSnapshot cached = CACHE.get(owner.getUUID());
		if (cached != null && cached.tick == tick) return cached.snapshot;
		BlockPos origin = owner.blockPosition();
		int ceiling = ceilingClearance(level, origin.above());
		int open = 0;
		int samples = 0;
		boolean fluid = false;
		for (int[] direction : DIRECTIONS) {
			for (int radius : new int[] {2, 4}) {
				for (int height : new int[] {1, 2}) {
					BlockPos sample = origin.offset(direction[0] * radius, height, direction[1] * radius);
					samples++;
					if (level.getBlockState(sample).getCollisionShape(level, sample).isEmpty()
						&& level.getFluidState(sample).isEmpty()) open++;
					fluid |= level.getFluidState(sample).is(FluidTags.WATER)
						|| level.getFluidState(sample).is(FluidTags.LAVA);
				}
			}
		}
		for (int x = -2; x <= 2 && !fluid; x++) {
			for (int z = -2; z <= 2 && !fluid; z++) {
				for (int y = -1; y <= 1; y++) {
					BlockPos sample = origin.offset(x, y, z);
					if (level.getFluidState(sample).is(FluidTags.WATER)
						|| level.getFluidState(sample).is(FluidTags.LAVA)) {
						fluid = true;
						break;
					}
				}
			}
		}
		Vec3 ownerMotion = owner.getDeltaMovement().multiply(1, 0, 1);
		Vec3 heading = TRAVEL_HEADINGS.getOrDefault(owner.getUUID(), new Vec3(0, 0, 1));
		if (ownerMotion.lengthSqr() >= 0.0025) {
			heading = ownerMotion.normalize();
			TRAVEL_HEADINGS.put(owner.getUUID(), heading);
		}
		Snapshot snapshot = new Snapshot(open / (double) samples, ceiling,
			level.canSeeSky(origin.above()), fluid, level.isDarkOutside(),
			ownerMotion.length(), heading);
		CACHE.put(owner.getUUID(), new CachedSnapshot(tick, snapshot));
		return snapshot;
	}

	static Snapshot assessAt(ServerLevel level, BlockPos origin) {
		int ceiling = ceilingClearance(level, origin.above());
		int open = 0;
		int samples = 0;
		boolean fluid = false;
		for (int[] direction : DIRECTIONS) {
			for (int radius : new int[] {2, 4}) {
				for (int height : new int[] {1, 2}) {
					BlockPos sample = origin.offset(direction[0] * radius, height, direction[1] * radius);
					samples++;
					if (level.getBlockState(sample).getCollisionShape(level, sample).isEmpty()
						&& level.getFluidState(sample).isEmpty()) open++;
					fluid |= level.getFluidState(sample).is(FluidTags.WATER)
						|| level.getFluidState(sample).is(FluidTags.LAVA);
				}
			}
		}
		return new Snapshot(open / (double) samples, ceiling, level.canSeeSky(origin.above()),
			fluid, level.isDarkOutside(), 0.0, Vec3.ZERO);
	}

	private static int ceilingClearance(ServerLevel level, BlockPos start) {
		for (int height = 1; height <= 10; height++) {
			BlockPos sample = start.above(height);
			if (!level.getBlockState(sample).getCollisionShape(level, sample).isEmpty()) return height;
		}
		return 11;
	}

	record Snapshot(double openness, int ceilingClearance, boolean skyVisible,
		boolean fluidNearby, boolean dark, double ownerSpeed, Vec3 travelHeading) {
	}

	private record CachedSnapshot(long tick, Snapshot snapshot) {
	}
}
