package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

final class DefensiveFormation {
	private DefensiveFormation() {
	}

	static Vec3 shieldAnchor(Vec3 owner, Vec3 threat) {
		Vec3 direction = horizontalDirection(owner, threat);
		return owner.add(direction.scale(1.65)).add(0, 1.0, 0);
	}

	static Vec3 wallOffset(int index, int count, Vec3 owner, Vec3 threat) {
		Vec3 direction = horizontalDirection(owner, threat);
		Vec3 right = new Vec3(-direction.z, 0, direction.x);
		if (count == 2) return right.scale(index == 0 ? -0.72 : 0.72)
			.add(0, index == 0 ? -0.28 : 0.28, 0);
		if (count == 3) {
			return switch (index) {
				case 0 -> new Vec3(0, 0.72, 0);
				case 1 -> right.scale(-0.86).add(0, -0.42, 0);
				default -> right.scale(0.86).add(0, -0.42, 0);
			};
		}
		int columns = Math.min(5, Math.max(1, count));
		int row = index / columns;
		int column = index % columns;
		double lateral = (column - (Math.min(columns, count - row * columns) - 1) / 2.0) * 1.28;
		double vertical = row * 0.9 - 0.35;
		return right.scale(lateral).add(0, vertical, 0);
	}

	static Vec3 guardArc(Vec3 owner, Vec3 threat, int index, int count) {
		Vec3 direction = horizontalDirection(owner, threat);
		Vec3 right = new Vec3(-direction.z, 0, direction.x);
		double ratio = count <= 1 ? 0.5 : index / (double) (count - 1);
		double angle = (ratio - 0.5) * Math.PI * 0.9;
		double radius = 3.4 + (index % 2) * 0.45;
		return owner.add(direction.scale(Math.cos(angle) * radius))
			.add(right.scale(Math.sin(angle) * radius))
			.add(0, 1.8 + (index % 3) * 0.65, 0);
	}

	static Vec3 escortOffset(int index, int count, Vec3 owner, Vec3 threat) {
		Vec3 direction = horizontalDirection(owner, threat);
		Vec3 right = new Vec3(-direction.z, 0, direction.x);
		double ratio = count <= 1 ? 0.5 : index / (double) (count - 1);
		double lateral = (ratio - 0.5) * Math.min(3.0, count * 0.65);
		return right.scale(lateral).add(direction.scale(0.35)).add(0, (index % 2) * 0.25, 0);
	}

	static Vec3 evasionOffset(Vec3 owner, Vec3 threat, int index, long tick) {
		Vec3 direction = horizontalDirection(owner, threat);
		Vec3 right = new Vec3(-direction.z, 0, direction.x);
		double sign = ((tick / 12 + index) & 1) == 0 ? 1.0 : -1.0;
		return right.scale(sign * 1.25).add(0, 0.7 + (index % 2) * 0.45, 0);
	}

	private static Vec3 horizontalDirection(Vec3 origin, Vec3 target) {
		Vec3 direction = target.subtract(origin).multiply(1, 0, 1);
		return direction.lengthSqr() < 0.001 ? new Vec3(0, 0, 1) : direction.normalize();
	}
}
