package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.DroneHardpoints;
import jp.morrowgear.drone.DroneRole;
import jp.morrowgear.drone.ScoutScanVisualPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.MorrowgearRenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A narrow fan sweeps 360 degrees over time; never a simultaneous full-circle wall. */
final class ScoutScanRenderer {
	// Belly emitter; all orientation uses the same model transform as the airframe.
	private static final Vec3 SCAN_ORIGIN = new Vec3(0, 0.18, 0.32);
	private static final Map<UUID, CachedScan> SCANS = new HashMap<>();
	private static final ScoutScanVisualPolicy.TickBudget BUDGET = new ScoutScanVisualPolicy.TickBudget();
	private static WeakReference<ClientLevel> cachedLevel = new WeakReference<>(null);
	private static long lastTick = Long.MIN_VALUE;

	private ScoutScanRenderer() {}

	/** Optional lifecycle hook; render also calls it, so no extra client event registration is required. */
	static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (cachedLevel.get() != level || level == null) {
			SCANS.clear();
			BUDGET.reset();
			cachedLevel = new WeakReference<>(level);
			lastTick = Long.MIN_VALUE;
		}
		if (level == null) return;
		long now = level.getGameTime();
		if (now < lastTick) SCANS.clear();
		if (now == lastTick) return;
		lastTick = now;
		SCANS.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > ScoutScanVisualPolicy.CACHE_RETENTION_TICKS
			|| !(level.getEntity(entry.getKey()) instanceof DroneEntity drone) || !eligible(drone));
	}

	static void render(DroneRenderState state, PoseStack stack, SubmitNodeCollector collector, Vec3 cameraOffset) {
		Minecraft client = Minecraft.getInstance();
		tick(client);
		if (client.level == null || state.entityId == null) return;
		if (state.role != DroneRole.SCOUT || state.docked || state.powerLost
			|| !(client.level.getEntity(state.entityId) instanceof DroneEntity drone) || !eligible(drone)) {
			SCANS.remove(state.entityId);
			return;
		}
		double distance = cameraOffset.length();
		int rayCount = ScoutScanVisualPolicy.rayCount(distance);
		if (rayCount == 0) return;
		CachedScan cached = SCANS.get(state.entityId);
		if (cached == null) {
			if (SCANS.size() >= ScoutScanVisualPolicy.MAX_CACHED_SCOUTS) return;
			cached = new CachedScan();
			SCANS.put(state.entityId, cached);
		}
		long now = client.level.getGameTime();
		cached.lastSeen = now;
		Vec3 position = new Vec3(state.x, state.y, state.z);
		Vec3 origin = position.add(DroneHardpoints.rotate(SCAN_ORIGIN, state.heading, state.flightPitch, state.flightRoll));
		if (cached.frame != null && origin.distanceToSqr(cached.frame.origin) > 64) cached.frame = null;
		if (cached.attemptedAt != now && (cached.frame == null
			|| ScoutScanVisualPolicy.shouldRefresh(now, cached.frame.tick, distance, origin.distanceTo(cached.frame.origin)))) {
			cached.attemptedAt = now;
			if (BUDGET.reserve(now, rayCount)) cached.frame = sample(client.level, drone, origin, now,
				state.ageInTicks, rayCount, state.lightCoords);
		}
		ScanFrame frame = cached.frame;
		if (frame == null) return;
		float alpha = ScoutScanVisualPolicy.distanceAlpha(distance);
		if (now - frame.tick > ScoutScanVisualPolicy.refreshInterval(distance)) return;
		Vec3 camera = position.add(cameraOffset);
		stack.pushPose();
		stack.translate(-position.x, -position.y, -position.z);
		// The beam shader ignores world light but retains depth testing and fog; low alpha stays soft at night.
		collector.submitCustomGeometry(stack, MorrowgearRenderTypes.energyBeam(),
			(pose, out) -> draw(pose, out, frame, origin, camera, alpha));
		stack.popPose();
	}

	private static boolean eligible(DroneEntity drone) {
		return ScoutScanVisualPolicy.eligible(drone.role(), drone.isAlive(), drone.isDocked(), drone.isPowerLost(),
			!drone.onGround() && !drone.isInWater() && !drone.isInLava());
	}

	private static ScanFrame sample(ClientLevel level, DroneEntity drone, Vec3 origin, long now,
		float age, int count, int light) {
		List<LivingEntity> mobs = new ArrayList<>();
		level.getEntities(EntityTypeTest.forClass(LivingEntity.class),
			AABB.ofSize(origin, 96, 64, 96), entity -> entity.isAlive() && !entity.isSpectator()
				&& !(entity instanceof DroneEntity), mobs, ScoutScanVisualPolicy.MAX_MOB_BOUNDS);
		List<AABB> bounds = mobs.stream().map(LivingEntity::getBoundingBox).toList();
		List<Ray> rays = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			double angle = ScoutScanVisualPolicy.rayAngle(age, index, count);
			Vec3 direction = new Vec3(Math.cos(angle), -0.32, Math.sin(angle)).normalize();
			Vec3 destination = origin.add(direction.scale(ScoutScanVisualPolicy.RAY_LENGTH));
			Vec3 terrainEnd = level.clip(new ClipContext(origin, destination, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.ANY, drone)).getLocation();
			double terrainLength = origin.distanceTo(terrainEnd), nearestMob = Double.POSITIVE_INFINITY;
			for (AABB bound : bounds) {
				if (bound.contains(origin)) { nearestMob = 0; break; }
				var hit = bound.clip(origin, terrainEnd);
				if (hit.isPresent()) nearestMob = Math.min(nearestMob, origin.distanceTo(hit.get()));
			}
			double length = ScoutScanVisualPolicy.clippedLength(terrainLength, nearestMob);
			// Keep endpoints just in front of the surface to avoid coplanar flicker.
			Vec3 end = origin.add(direction.scale(Math.max(0, length - .025)));
			rays.add(new Ray(end, length, nearestMob < terrainLength));
		}
		return new ScanFrame(origin, now, List.copyOf(rays), light);
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer out, ScanFrame frame, Vec3 origin, Vec3 camera, float opacity) {
		List<Ray> rays = frame.rays;
		for (int index = 0; index < rays.size(); index++) {
			Ray ray = rays.get(index);
			float edge = (float) index / (rays.size() - 1);
			if (index > 0) {
				Ray previous = rays.get(index - 1);
				if (ScoutScanVisualPolicy.joinCurtain(previous.length, ray.length, previous.mob, ray.mob)) {
					int alpha = Math.round(ScoutScanVisualPolicy.CURTAIN_ALPHA * opacity * edge);
					quad(pose, out, origin, origin, ray.end, previous.end, alpha / 3, alpha, frame.light);
					line(pose, out, previous.end, ray.end, camera, .045f,
						Math.round(112 * opacity * edge), frame.light);
				}
			}
			if (ray.length < .4) continue;
			int alpha = Math.round(opacity * (index == rays.size() - 1 ? ScoutScanVisualPolicy.EDGE_ALPHA : 46 * edge));
			line(pose, out, origin, ray.end, camera, index == rays.size() - 1 ? .09f : .028f, alpha, frame.light);
		}
	}

	private static void line(PoseStack.Pose pose, VertexConsumer out, Vec3 start, Vec3 end, Vec3 camera,
		float width, int alpha, int light) {
		Vec3 side = end.subtract(start).cross(camera.subtract(start));
		if (side.lengthSqr() < .000001 || alpha <= 0) return;
		side = side.normalize().scale(width * .5);
		quad(pose, out, start.subtract(side), start.add(side), end.add(side), end.subtract(side), alpha / 2, alpha, light);
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer out, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
		int startAlpha, int endAlpha, int light) {
		vertex(pose, out, a, startAlpha, light, 0, 0);
		vertex(pose, out, b, startAlpha, light, 0, 1);
		vertex(pose, out, c, endAlpha, light, 1, 1);
		vertex(pose, out, d, endAlpha, light, 1, 0);
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer out, Vec3 point, int alpha, int light, float u, float v) {
		out.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
			.setColor(32, 215, 223, alpha).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light).setNormal(pose, 0, 1, 0);
	}

	private static final class CachedScan {
		private long lastSeen;
		private long attemptedAt = Long.MIN_VALUE;
		private ScanFrame frame;
	}

	private record Ray(Vec3 end, double length, boolean mob) {}
	private record ScanFrame(Vec3 origin, long tick, List<Ray> rays, int light) {}
}
