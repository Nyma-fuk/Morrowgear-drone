package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class PatrolRoutePolicy {
	public static final int MAX_POINTS = 8;
	public static final double TURN_RADIUS = 18.0;
	public static final double ADVANCE_RADIUS = 6.5;

	private PatrolRoutePolicy() {}

	public static String encode(List<BlockPos> points) {
		if (points == null) return "";
		return points.stream().limit(MAX_POINTS)
			.map(point -> point.getX() + "," + point.getZ()).reduce((a, b) -> a + ";" + b).orElse("");
	}

	public static List<BlockPos> decode(String encoded) {
		if (encoded == null || encoded.isBlank()) return List.of();
		List<BlockPos> points = new ArrayList<>();
		for (String value : encoded.split(";")) {
			if (points.size() >= MAX_POINTS) break;
			String[] coordinates = value.split(",");
			if (coordinates.length != 2) return List.of();
			try {
				int x = Integer.parseInt(coordinates[0]);
				int z = Integer.parseInt(coordinates[1]);
				points.add(new BlockPos(x, 0, z));
			} catch (NumberFormatException ignored) {
				return List.of();
			}
		}
		return List.copyOf(points);
	}

	public static int nextIndex(int current, int size) {
		return size <= 0 ? 0 : Math.floorMod(current + 1, size);
	}

	public static Vec3 formationOrigin(List<Vec3> positions) {
		if (positions == null || positions.isEmpty()) return Vec3.ZERO;
		Vec3 sum = Vec3.ZERO;
		int count = 0;
		for (Vec3 position : positions) {
			if (position == null) continue;
			sum = sum.add(position.x, 0.0, position.z);
			count++;
		}
		return count == 0 ? Vec3.ZERO : sum.scale(1.0 / count);
	}

	public static int nearestIndex(List<BlockPos> points, Vec3 origin) {
		if (points == null || points.isEmpty()) return 0;
		Vec3 reference = origin == null ? Vec3.ZERO : origin;
		int nearest = 0;
		double nearestDistance = Double.MAX_VALUE;
		for (int index = 0; index < points.size(); index++) {
			BlockPos point = points.get(index);
			double dx = point.getX() + .5 - reference.x;
			double dz = point.getZ() + .5 - reference.z;
			double distance = dx * dx + dz * dz;
			if (distance < nearestDistance) {
				nearest = index;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	public static Vec3 curvedTarget(Vec3 position, Vec3 current, Vec3 next, int routeSize, int currentIndex) {
		double distance = position.distanceTo(current);
		if (distance >= TURN_RADIUS) return current;
		double blend = 1.0 - Math.max(0.0, distance) / TURN_RADIUS;
		blend = blend * blend * (3.0 - 2.0 * blend);
		Vec3 target = current.scale(1.0 - blend * 0.9).add(next.scale(blend * 0.9));
		if (routeSize != 2) return target;
		Vec3 outgoing = next.subtract(current).multiply(1, 0, 1);
		if (outgoing.lengthSqr() < 0.001) return target;
		Vec3 side = new Vec3(-outgoing.z, 0, outgoing.x).normalize();
		if ((currentIndex & 1) != 0) side = side.scale(-1);
		double arc = Math.sin(Math.PI * blend) * TURN_RADIUS * 0.58;
		return target.add(side.scale(arc));
	}

	public static Vec3 smoothGuidance(Vec3 previous, Vec3 requested) {
		if (previous == null) return requested;
		Vec3 delta = requested.subtract(previous);
		Vec3 horizontal = delta.multiply(1, 0, 1);
		if (horizontal.length() > 3.5) horizontal = horizontal.normalize().scale(3.5);
		double vertical = Math.max(-0.45, Math.min(0.45, delta.y));
		return previous.add(horizontal.x, vertical, horizontal.z);
	}

	public static boolean shouldAdvance(Vec3 leaderPosition, Vec3 current) {
		return Math.hypot(leaderPosition.x - current.x, leaderPosition.z - current.z) <= ADVANCE_RADIUS;
	}

	public static boolean shouldAdvance(Vec3 leaderPosition, Vec3 previous, Vec3 current) {
		if (shouldAdvance(leaderPosition, current)) return true;
		Vec3 leg = current.subtract(previous).multiply(1, 0, 1);
		if (leg.lengthSqr() < 4.0) return false;
		Vec3 fromPrevious = leaderPosition.subtract(previous).multiply(1, 0, 1);
		double progress = fromPrevious.dot(leg) / leg.lengthSqr();
		double clamped = Math.max(0.0, Math.min(1.0, progress));
		Vec3 closest = previous.add(leg.scale(clamped));
		double crossTrack = leaderPosition.multiply(1, 0, 1).distanceTo(closest.multiply(1, 0, 1));
		return progress >= 0.82 && crossTrack <= TURN_RADIUS * 1.25;
	}

	public static boolean leaderQuorumReached(int arrivedLeaders, int totalLeaders) {
		if (totalLeaders <= 0) return false;
		int required = Math.max(1, (int) Math.ceil(totalLeaders * 0.6));
		return arrivedLeaders >= required;
	}
}
