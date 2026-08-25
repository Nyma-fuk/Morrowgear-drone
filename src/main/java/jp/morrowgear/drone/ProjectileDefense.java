package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

final class ProjectileDefense {
	private ProjectileDefense() {
	}

	static boolean crossesShield(Vec3 start, Vec3 velocity, Vec3 shieldCenter, double radius) {
		if (velocity.lengthSqr() < 0.0004) return false;
		Vec3 end = start.add(velocity);
		Vec3 segment = end.subtract(start);
		double progress = shieldCenter.subtract(start).dot(segment) / segment.lengthSqr();
		progress = Math.max(0.0, Math.min(1.0, progress));
		Vec3 closest = start.add(segment.scale(progress));
		return closest.distanceToSqr(shieldCenter) <= radius * radius;
	}

	static TrajectoryPrediction predictClosestToVerticalTarget(Vec3 start, Vec3 velocity,
		double targetX, double targetZ, double minY, double maxY,
		double gravity, double drag, int maxTicks) {
		Vec3 position = start;
		Vec3 motion = velocity;
		Vec3 closest = start;
		double closestDistance = Double.MAX_VALUE;
		double closestTick = 0.0;
		for (int tick = 0; tick < maxTicks; tick++) {
			Vec3 next = position.add(motion);
			for (int sample = 1; sample <= 4; sample++) {
				double fraction = sample / 4.0;
				Vec3 point = position.add(next.subtract(position).scale(fraction));
				double targetY = Math.max(minY, Math.min(maxY, point.y));
				double distance = point.distanceTo(new Vec3(targetX, targetY, targetZ));
				if (distance < closestDistance) {
					closestDistance = distance;
					closest = point;
					closestTick = tick + fraction;
				}
			}
			position = next;
			motion = new Vec3(motion.x * drag, (motion.y - gravity) * drag, motion.z * drag);
		}
		return new TrajectoryPrediction(closest, closestTick, closestDistance);
	}

	record TrajectoryPrediction(Vec3 closestPoint, double ticks, double missDistance) {
	}
}
