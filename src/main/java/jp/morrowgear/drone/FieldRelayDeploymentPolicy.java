package jp.morrowgear.drone;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Resolves a stable FIELD mesh origin without coupling it to later player movement. */
final class FieldRelayDeploymentPolicy {
	private FieldRelayDeploymentPolicy() {}

	static Vec3 resolve(List<Vec3> persisted, List<Vec3> activePositions, Vec3 commandPosition) {
		if (persisted != null && !persisted.isEmpty()) return persisted.getFirst();
		if (activePositions == null || activePositions.isEmpty()) return commandPosition;
		double x = 0.0, y = 0.0, z = 0.0;
		for (Vec3 position : activePositions) {
			x += position.x;
			y += position.y;
			z += position.z;
		}
		double count = activePositions.size();
		return new Vec3(x / count, y / count, z / count);
	}
}
