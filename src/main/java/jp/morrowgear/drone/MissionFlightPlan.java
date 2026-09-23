package jp.morrowgear.drone;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

final class MissionFlightPlan {
	private static final double ARRIVAL_APPROACH_DISTANCE = 15.0;

	private MissionFlightPlan() {
	}

	static Vec3 transitTarget(ServerLevel level, Vec3 current, Vec3 destination, boolean underground) {
		return transitTarget(level, current, destination, underground, 0.0);
	}

	static Vec3 transitTarget(ServerLevel level, Vec3 current, Vec3 destination, boolean underground,
		double clearanceBoost) {
		if (underground || horizontalDistance(current, destination) <= ARRIVAL_APPROACH_DISTANCE) return destination;
		Corridor corridor = corridor(level, current, destination);
		double altitude = corridor.waterRatio() >= 0.7
			? corridor.maximumSurfaceY() + 5.0
			: corridor.maximumSurfaceY() + 10.0;
		altitude += Math.max(0.0, clearanceBoost);
		altitude = Math.max(altitude, destination.y + 3.0);
		Vec3 horizontal = destination.subtract(current).multiply(1, 0, 1);
		double horizontalDistance = horizontal.length();
		if (horizontalDistance < 0.001) return new Vec3(current.x, altitude, current.z);
		double lookahead = Math.max(8.0, Math.min(30.0, horizontalDistance * 0.4));
		Vec3 forward = horizontal.scale(Math.min(horizontalDistance, lookahead) / horizontalDistance);
		double altitudeStep = Math.max(-7.0, Math.min(7.0, altitude - current.y));
		return current.add(forward.x, altitudeStep, forward.z);
	}

	static boolean isUnderground(ServerLevel level, Vec3 target) {
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
			(int) Math.floor(target.x), (int) Math.floor(target.z));
		return target.y + 3.0 < surface;
	}

	static Vec3 trackingObservationTarget(ServerLevel level, LivingEntity target) {
		Vec3 targetTop = target.position().add(0, target.getBbHeight(), 0);
		BlockPos targetBlock = target.blockPosition();
		boolean submerged = level.getFluidState(targetBlock).is(FluidTags.WATER)
			|| level.getFluidState(targetBlock).is(FluidTags.LAVA);
		if (!submerged && !isUnderground(level, targetTop)) return targetTop.add(0, AirframeEnvelope.ORBIT_HEIGHT, 0);

		int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE,
			targetBlock.getX(), targetBlock.getZ());
		return new Vec3(target.getX(), observationAltitude(surfaceY), target.getZ());
	}

	static double observationAltitude(int worldSurfaceY) {
		return worldSurfaceY + AirframeEnvelope.ORBIT_HEIGHT;
	}

	static double requiredOrbitCenterAltitude(ServerLevel level, Vec3 center, int count) {
		double radius = SwarmFormation.orbitRadius(count);
		int maximumSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING,
			(int) Math.floor(center.x), (int) Math.floor(center.z));
		int samples = Math.max(16, Math.min(32, count * 4));
		for (int index = 0; index < samples; index++) {
			double angle = index * Math.PI * 2.0 / samples;
			for (double radialOffset : new double[] {-1.6, 0.0, 1.6}) {
				double sampleRadius = Math.max(0.0, radius + radialOffset);
				int x = (int) Math.floor(center.x + Math.cos(angle) * sampleRadius);
				int z = (int) Math.floor(center.z + Math.sin(angle) * sampleRadius);
				maximumSurface = Math.max(maximumSurface,
					level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
			}
		}
		// The lowest orbit slot is 0.45 blocks below the center plane.
		return Math.max(center.y, maximumSurface + AirframeEnvelope.ORBIT_HEIGHT);
	}

	private static Corridor corridor(ServerLevel level, Vec3 current, Vec3 destination) {
		int samples = Math.max(4, Math.min(20, (int) Math.ceil(horizontalDistance(current, destination) / 8.0)));
		int maximumSurface = Integer.MIN_VALUE;
		int waterSamples = 0;
		for (int index = 0; index <= samples; index++) {
			double progress = index / (double) samples;
			int x = (int) Math.floor(current.x + (destination.x - current.x) * progress);
			int z = (int) Math.floor(current.z + (destination.z - current.z) * progress);
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			maximumSurface = Math.max(maximumSurface, surface);
			BlockPos surfaceBlock = new BlockPos(x, Math.max(level.getMinY(), surface - 1), z);
			if (level.getFluidState(surfaceBlock).is(FluidTags.WATER)) waterSamples++;
		}
		return new Corridor(maximumSurface, waterSamples / (double) (samples + 1));
	}

	private static double horizontalDistance(Vec3 first, Vec3 second) {
		return Math.hypot(first.x - second.x, first.z - second.z);
	}

	private record Corridor(int maximumSurfaceY, double waterRatio) {
	}
}
