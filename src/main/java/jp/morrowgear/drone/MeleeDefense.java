package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

final class MeleeDefense {
	private MeleeDefense() {
	}

	static Vec3 outwardImpulse(Vec3 owner, Vec3 hostile) {
		Vec3 direction = hostile.subtract(owner).multiply(1, 0, 1);
		if (direction.lengthSqr() < 0.001) direction = new Vec3(0, 0, 1);
		return direction.normalize().scale(0.36).add(0, 0.08, 0);
	}
}
