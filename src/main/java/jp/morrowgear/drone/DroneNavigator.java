package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class DroneNavigator {
	private static final double[] STRATEGIC_RADII = {8.0, 14.0, 22.0, 34.0, 50.0, 72.0};
	private static final double[] STRATEGIC_HEIGHTS = {0.0, 6.0, 14.0, 26.0, 44.0, 68.0, -6.0, -12.0};
	private static final double[] STRATEGIC_FORWARD = {-3.0, 2.0, 8.0};

	private DroneNavigator() {
	}

	static Vec3 liftOutOfFluid(ServerLevel level, Vec3 requested) {
		BlockPos cursor = BlockPos.containing(requested);
		boolean startedInFluid = isHazardousFluid(level, cursor);
		for (int lift = 0; lift <= 14 && isHazardousFluid(level, cursor); lift++) cursor = cursor.above();
		return startedInFluid
			? new Vec3(requested.x, cursor.getY() + 1.15, requested.z)
			: requested;
	}

	static boolean corridorClear(ServerLevel level, DroneEntity drone, Vec3 from, Vec3 to) {
		Vec3 delta = to.subtract(from);
		int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.45));
		AABB box = drone.getBoundingBox();
		for (int step = 1; step <= samples; step++) {
			Vec3 point = from.add(delta.scale(step / (double) samples));
			AABB moved = box.move(point.subtract(drone.position())).deflate(0.08);
			// Wing separation owns aircraft-to-aircraft spacing. The local terrain
			// sensor must not reinterpret a formation member as a route obstruction.
			if (level.getBlockCollisions(drone, moved).iterator().hasNext()
				|| fluidInBox(level, moved)) return false;
		}
		return true;
	}

	static Vec3 nextPathPoint(ServerLevel level, DroneEntity drone, Path path) {
		if (path == null || path.isDone()) return null;
		while (!path.isDone() && drone.position().distanceTo(path.getNextEntityPos(drone)) < 1.6) path.advance();
		if (path.isDone()) return null;
		int next = path.getNextNodeIndex();
		int furthest = next;
		int limit = Math.min(path.getNodeCount() - 1, next + 6);
		for (int candidate = next + 1; candidate <= limit; candidate++) {
			Vec3 point = path.getEntityPosAtNode(drone, candidate);
			if (drone.position().distanceTo(point) > 9.0
				|| !corridorClear(level, drone, drone.position(), point)) break;
			furthest = candidate;
		}
		if (furthest > next) path.setNextNodeIndex(furthest);
		return path.getNextEntityPos(drone);
	}

	static Vec3 localDetour(ServerLevel level, DroneEntity drone, Vec3 target, boolean preserveAltitude) {
		Vec3 forward = target.subtract(drone.position()).multiply(1, 0, 1);
		if (forward.lengthSqr() < 0.001) forward = drone.getLookAngle().multiply(1, 0, 1);
		if (forward.lengthSqr() < 0.001) forward = new Vec3(0, 0, 1);
		forward = forward.normalize();
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		List<Vec3> candidates = detourCandidates(drone.position(), forward, right, 2.8, 4.6,
			preserveAltitude ? new double[] {2.4, 0.7, -1.8} : new double[] {2.4, 0.7, -1.8, -3.2});
		return candidates.stream()
			.filter(candidate -> corridorClear(level, drone, drone.position(), candidate))
			.min(Comparator.comparingDouble(candidate -> detourScore(level, drone, candidate, target,
				preserveAltitude)))
			.orElse(drone.position().add(0, 0.8, 0));
	}

	private static List<Vec3> detourCandidates(Vec3 origin, Vec3 forward, Vec3 right,
		double near, double far, double[] heights) {
		List<Vec3> candidates = new ArrayList<>();
		List<Vec3> directions = List.of(forward, forward.scale(-1), right, right.scale(-1),
			forward.add(right).normalize(), forward.subtract(right).normalize(),
			forward.scale(-1).add(right).normalize(), forward.scale(-1).subtract(right).normalize());
		for (double height : heights) {
			candidates.add(origin.add(0, height, 0));
			for (Vec3 direction : directions) {
				candidates.add(origin.add(direction.scale(near)).add(0, height, 0));
				candidates.add(origin.add(direction.scale(far)).add(0, height, 0));
			}
		}
		return candidates;
	}

	private static double detourScore(ServerLevel level, DroneEntity drone, Vec3 candidate,
		Vec3 target, boolean preserveAltitude) {
		double score = candidate.distanceTo(target) + drone.position().distanceTo(candidate) * 0.28;
		if (preserveAltitude && candidate.y < drone.getY()) score += (drone.getY() - candidate.y) * 0.7;
		if (corridorClear(level, drone, candidate, target)) score -= 8.0;
		return score;
	}

	static Vec3 localOrbitDetour(ServerLevel level, DroneEntity drone, Vec3 target) {
		Vec3 forward = target.subtract(drone.position()).multiply(1, 0, 1);
		if (forward.lengthSqr() < 0.001) forward = drone.getLookAngle().multiply(1, 0, 1);
		if (forward.lengthSqr() < 0.001) forward = new Vec3(0, 0, 1);
		forward = forward.normalize();
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		double planeY = target.y;
		List<Vec3> lateral = List.of(
			new Vec3(drone.getX(), planeY, drone.getZ()).add(right.scale(2.8)),
			new Vec3(drone.getX(), planeY, drone.getZ()).add(right.scale(-2.8)),
			new Vec3(drone.getX(), planeY, drone.getZ()).add(forward.scale(-2.0)));
		return lateral.stream()
			.filter(candidate -> corridorClear(level, drone, drone.position(), candidate))
			.min(Comparator.comparingDouble(candidate -> candidate.distanceTo(target)))
			.orElseGet(() -> drone.position().add(0, 1.2, 0));
	}

	static StrategicDetour strategicDetour(ServerLevel level, DroneEntity drone, Vec3 target,
		int preferredSide) {
		Vec3 forward = target.subtract(drone.position()).multiply(1, 0, 1);
		if (forward.lengthSqr() < 0.001) forward = new Vec3(0, 0, 1);
		forward = forward.normalize();
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		List<StrategicCandidate> candidates = new ArrayList<>();
		int[] sides = preferredSide == 0 ? new int[] {-1, 1} : new int[] {preferredSide, -preferredSide};
		for (int side : sides) {
			for (double radius : STRATEGIC_RADII) {
				for (double forwardOffset : STRATEGIC_FORWARD) {
					for (double height : STRATEGIC_HEIGHTS) {
						Vec3 candidate = drone.position().add(right.scale(side * radius))
							.add(forward.scale(forwardOffset)).add(0, height, 0);
						addStrategicCandidate(level, drone, target, candidates, candidate, side, preferredSide);
					}
				}
			}
		}
		// A disabled aircraft can be far below a roof or platform. Reaching it may
		// require flying away from the target first, clearing the edge, and only then
		// descending. The lateral fan above cannot represent that escape geometry.
		for (Vec3 direction : strategicEscapeDirections(forward, right)) {
			for (double radius : STRATEGIC_RADII) {
				for (double height : new double[] {0.0, 6.0, 14.0, -6.0, -12.0}) {
					Vec3 candidate = drone.position().add(direction.scale(radius)).add(0, height, 0);
					addStrategicCandidate(level, drone, target, candidates, candidate, 0, preferredSide);
				}
			}
		}
		for (double height : new double[] {8.0, 16.0, 28.0, 44.0, 68.0, -8.0, -16.0}) {
			Vec3 candidate = drone.position().add(forward.scale(4.0)).add(0, height, 0);
			addStrategicCandidate(level, drone, target, candidates, candidate, preferredSide, preferredSide);
		}
		return candidates.stream().min(Comparator.comparingDouble(StrategicCandidate::score))
			.map(candidate -> new StrategicDetour(candidate.position(), candidate.side(), candidate.directExit()))
			.orElse(null);
	}

	static List<Vec3> strategicEscapeDirections(Vec3 forward, Vec3 right) {
		Vec3 backward = forward.scale(-1);
		return List.of(backward, backward.add(right).normalize(), backward.subtract(right).normalize());
	}

	private static void addStrategicCandidate(ServerLevel level, DroneEntity drone, Vec3 target,
		List<StrategicCandidate> candidates, Vec3 candidate, int side, int preferredSide) {
		BlockPos pos = BlockPos.containing(candidate);
		if (pos.getY() <= level.getMinY() + 2 || pos.getY() >= level.getMaxY() - 2
			|| !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
			|| !corridorClear(level, drone, drone.position(), candidate)) return;
		boolean directExit = corridorClear(level, drone, candidate, target);
		double score = candidate.distanceTo(target) + drone.position().distanceTo(candidate) * 0.16;
		if (directExit) score -= 1000.0;
		if (preferredSide != 0 && side == preferredSide) score -= 12.0;
		score += Math.abs(candidate.y - drone.getY()) * 0.08;
		candidates.add(new StrategicCandidate(candidate, side, directExit, score));
	}

	static Vec3 recoveryTarget(ServerLevel level, DroneEntity drone, Vec3 target,
		boolean preserveAltitude, int recoveryLevel) {
		if (recoveryLevel <= 1) return localDetour(level, drone, target, preserveAltitude);

		Vec3 forward = target.subtract(drone.position()).multiply(1, 0, 1);
		if (forward.lengthSqr() < 0.001) forward = new Vec3(0, 0, 1);
		forward = forward.normalize();
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		List<Vec3> candidates = new ArrayList<>();
		if (recoveryLevel >= 3) {
			int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING,
				drone.getBlockX(), drone.getBlockZ());
			double escapeY = Math.min(drone.getY() + 12.0,
				Math.max(drone.getY() + 4.5, surfaceY + 2.2));
			candidates.add(new Vec3(drone.getX(), escapeY, drone.getZ()));
		}
		double lateral = recoveryLevel >= 3 ? 5.2 : 3.8;
		double lift = recoveryLevel >= 3 ? 2.8 : 1.6;
		candidates.add(drone.position().add(right.scale(lateral)).add(0, lift, 0));
		candidates.add(drone.position().add(right.scale(-lateral)).add(0, lift, 0));
		candidates.add(drone.position().add(forward.scale(-3.2)).add(0, lift + 0.8, 0));
		candidates.add(drone.position().add(forward.scale(2.8)).add(0, lift, 0));
		candidates.add(drone.position().add(right.scale(lateral)).add(0, -1.8, 0));
		candidates.add(drone.position().add(right.scale(-lateral)).add(0, -1.8, 0));
		candidates.add(drone.position().add(forward.scale(-3.2)).add(0, -2.4, 0));
		return candidates.stream()
			.filter(candidate -> corridorClear(level, drone, drone.position(), candidate))
			.min(Comparator.comparingDouble(candidate -> detourScore(level, drone, candidate, target,
				preserveAltitude) - Math.max(0, candidate.y - drone.getY()) * 0.35))
			.orElseGet(() -> localDetour(level, drone, target, true));
	}

	static boolean isHazardousFluid(ServerLevel level, BlockPos pos) {
		return level.getFluidState(pos).is(FluidTags.WATER) || level.getFluidState(pos).is(FluidTags.LAVA);
	}

	private static boolean fluidInBox(ServerLevel level, AABB box) {
		int minX = (int) Math.floor(box.minX);
		int maxX = (int) Math.floor(box.maxX);
		int minY = (int) Math.floor(box.minY);
		int maxY = (int) Math.floor(box.maxY);
		int minZ = (int) Math.floor(box.minZ);
		int maxZ = (int) Math.floor(box.maxZ);
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					if (isHazardousFluid(level, new BlockPos(x, y, z))) return true;
				}
			}
		}
		return false;
	}

	record StrategicDetour(Vec3 waypoint, int side, boolean directExit) {
	}

	private record StrategicCandidate(Vec3 position, int side, boolean directExit, double score) {
	}
}
