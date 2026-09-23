package jp.morrowgear.drone.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

final class EffectProjection {
	private EffectProjection() {}

	static double pixelsPerBlock(Vec3 origin) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return 0;
		var camera = client.gameRenderer.mainCamera();
		var f = camera.forwardVector();
		Vec3 forward = new Vec3(f.x(), f.y(), f.z());
		if (origin.subtract(camera.position()).dot(forward) <= 0.1) return 0;
		Vec3 right = forward.cross(new Vec3(0, 1, 0));
		if (right.lengthSqr() < 0.0001) right = new Vec3(1, 0, 0);
		Vec3 a = client.gameRenderer.projectPointToScreen(origin);
		Vec3 b = client.gameRenderer.projectPointToScreen(origin.add(right.normalize()));
		return Math.hypot((b.x - a.x) * client.getWindow().getWidth() * 0.5,
			(b.y - a.y) * client.getWindow().getHeight() * 0.5);
	}
}
