package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.DroneHardpoints;
import jp.morrowgear.drone.EffectReadabilityPolicy;
import jp.morrowgear.drone.FormationLightTrailProfile;
import jp.morrowgear.drone.FormationTrailHistory;
import jp.morrowgear.drone.FormationTrailHistory.Point;
import jp.morrowgear.drone.FormationTrailPolicy;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class FormationTrailController {
	private static final Vec3 LEFT_EXHAUST = DroneHardpoints.EXHAUST_LEFT;
	private static final Vec3 RIGHT_EXHAUST = DroneHardpoints.EXHAUST_RIGHT;
	private static final Identifier WHITE_TEXTURE = Identifier.fromNamespaceAndPath(
		MorrowgearDrone.MOD_ID, "textures/entity/emissive_white.png");
	private static final Map<UUID, TrailState> TRAILS = new HashMap<>();
	private static ClientLevel trackedLevel;
	private static long lastTick = Long.MIN_VALUE;

	private FormationTrailController() {}

	static void tick(Minecraft client) {
		if (client.level != trackedLevel || client.level == null || client.player == null) {
			TRAILS.clear();
			trackedLevel = client.level;
			lastTick = Long.MIN_VALUE;
		}
		if (client.level == null || client.player == null || client.isPaused()) return;
		long gameTick = client.level.getGameTime();
		if (gameTick < lastTick) TRAILS.clear();
		if (gameTick == lastTick) return;
		lastTick = gameTick;
		Set<UUID> visible = new HashSet<>();
		AABB area = client.player.getBoundingBox().inflate(FormationLightTrailProfile.MAX_RENDER_DISTANCE);
		List<DroneEntity> drones = client.level.getEntitiesOfClass(DroneEntity.class, area, entity -> entity.isAlive());
		drones.sort(Comparator.comparingDouble((DroneEntity drone) -> client.player.distanceToSqr(drone))
			.thenComparing(DroneEntity::getUUID));
		for (DroneEntity drone : drones) {
			UUID id = drone.getUUID();
			if (drone.isPowerLost()) { TRAILS.remove(id); continue; }
			if (drone.distanceToSqr(client.player) > FormationLightTrailProfile.MAX_RENDER_DISTANCE
				* FormationLightTrailProfile.MAX_RENDER_DISTANCE) continue;
			FormationTrailPolicy.TrailStyle style = FormationTrailPolicy.style(drone.mode(), drone.combatState(),
				drone.missionExpected(), drone.missionStage(), drone.isDocked(), drone.serviceReturnActive(),
				drone.formationCatchUp(), drone.patrolRouteSize(), drone.cohortRank(),
				!drone.cohortLeaderId().isBlank(), drone.hasActiveFieldOperation(), drone.hasSecurityPatrol());
			if (style == FormationTrailPolicy.TrailStyle.NONE && !TRAILS.containsKey(id)) continue;
			if (visible.size() >= FormationLightTrailProfile.MAX_TRACKED_DRONES) break;
			visible.add(id);
			TrailState state = TRAILS.computeIfAbsent(id, ignored -> new TrailState());
			state.history.tick(style, drone.position(), DroneHardpoints.worldPosition(drone, LEFT_EXHAUST),
				DroneHardpoints.worldPosition(drone, RIGHT_EXHAUST), gameTick,
				drone.getDeltaMovement().lengthSqr() >= 0.0025, FormationTrailPolicy.activationDelay(drone.cohortRank()));
			state.smoothed = smoothed(state.history.points());
		}
		TRAILS.entrySet().removeIf(entry -> !FormationLightTrailProfile.retainState(visible.contains(entry.getKey()),
			entry.getValue().history.active(), entry.getValue().history.hasPoints()));
	}

	static void render(DroneRenderState state, PoseStack stack, SubmitNodeCollector collector, Vec3 cameraOffset) {
		Vec3 position = new Vec3(state.x, state.y, state.z);
		render(state.entityId, position, stack, collector, position.add(cameraOffset),
			position.add(DroneHardpoints.rotate(LEFT_EXHAUST, state.heading, state.flightPitch, state.flightRoll)),
			position.add(DroneHardpoints.rotate(RIGHT_EXHAUST, state.heading, state.flightPitch, state.flightRoll)),
			state.ageInTicks - Math.floor(state.ageInTicks));
	}

	// Compatibility for callers that have not yet switched to the interpolated hardpoint overload.
	static void render(UUID id, Vec3 position, PoseStack stack, SubmitNodeCollector collector) {
		render(id, position, stack, collector, Minecraft.getInstance().gameRenderer.mainCamera().position(), null, null, 0);
	}

	private static void render(UUID id, Vec3 position, PoseStack stack, SubmitNodeCollector collector,
		Vec3 camera, Vec3 left, Vec3 right, double partialTick) {
		TrailState state = id == null ? null : TRAILS.get(id);
		Minecraft client = Minecraft.getInstance();
		if (state == null || state.smoothed.size() < 2 || client.level == null || client.level != trackedLevel) return;
		double distance = camera.distanceTo(position);
		float opacity = FormationLightTrailProfile.distanceAlpha(distance);
		if (opacity <= 0) return;
		double now = client.level.getGameTime() + partialTick;
		List<Point> points = new ArrayList<>(state.smoothed);
		if (left != null && right != null && state.history.emitting()) {
			Point newest = points.getLast();
			if (now - newest.tick() <= FormationLightTrailProfile.MAX_SAMPLE_INTERVAL + 1)
				points.set(points.size() - 1, newest.at(left, right));
		}
		int stride = FormationLightTrailProfile.sampleStride(distance);
		float coreWidth = EffectReadabilityPolicy.width(FormationLightTrailProfile.CORE_WIDTH,
			EffectProjection.pixelsPerBlock(position), 0.9f, 1.7f);
		stack.pushPose();
		stack.translate(-position.x, -position.y, -position.z);
		collector.submitCustomGeometry(stack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE), (pose, out) -> {
			renderLayer(pose, out, points, camera, now, stride, FormationLightTrailProfile.HALO_WIDTH, 50 * opacity, false);
			renderLayer(pose, out, points, camera, now, stride, coreWidth, 172 * opacity, true);
		});
		stack.popPose();
	}

	private static List<Point> smoothed(List<Point> raw) {
		if (raw.size() < 3) return raw;
		List<Point> result = new ArrayList<>(raw);
		for (int index = 1; index < raw.size() - 1; index++) {
			Point a = raw.get(index - 1), b = raw.get(index), c = raw.get(index + 1);
			if (a.strip() != b.strip() || b.strip() != c.strip()) continue;
			result.set(index, b.at(a.left().scale(.2).add(b.left().scale(.6)).add(c.left().scale(.2)),
				a.right().scale(.2).add(b.right().scale(.6)).add(c.right().scale(.2))));
		}
		return List.copyOf(result);
	}

	private static void renderLayer(PoseStack.Pose pose, VertexConsumer out, List<Point> points, Vec3 camera,
		double now, int stride, float width, float opacity, boolean core) {
		for (int index = 0; index < points.size() - 1; index += stride) {
			int next = Math.min(points.size() - 1, index + stride);
			Point start = points.get(index), end = points.get(next);
			if (start.strip() != end.strip()) continue;
			float fadeStart = start.alpha(now) * Math.min(1, index / 3.0f);
			float fadeEnd = end.alpha(now) * Math.min(1, next / 3.0f);
			for (int side = 0; side < 2; side++) {
				Vec3 a = side == 0 ? start.left() : start.right(), b = side == 0 ? end.left() : end.right();
				Vec3 axis = b.subtract(a).cross(camera.subtract(a));
				if (axis.lengthSqr() < 0.000001) continue;
				axis = axis.normalize().scale(width * .5);
				Vec3 startAxis = axis.scale(.35 + .65 * fadeStart), endAxis = axis.scale(.35 + .65 * fadeEnd);
				int colorA = core ? FormationLightTrailProfile.blendColor(start.color(), 0xFFFFFF, .58f) : start.color();
				int colorB = core ? FormationLightTrailProfile.blendColor(end.color(), 0xFFFFFF, .58f) : end.color();
				vertex(pose, out, a.subtract(startAxis), colorA, Math.round(opacity * fadeStart), 0, 0);
				vertex(pose, out, a.add(startAxis), colorA, Math.round(opacity * fadeStart), 0, 1);
				vertex(pose, out, b.add(endAxis), colorB, Math.round(opacity * fadeEnd), 1, 1);
				vertex(pose, out, b.subtract(endAxis), colorB, Math.round(opacity * fadeEnd), 1, 0);
			}
		}
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer out, Vec3 point, int rgb, int alpha, float u, float v) {
		out.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
			.setColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, alpha).setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(0x00F000F0).setNormal(pose, 0, 1, 0);
	}

	private static final class TrailState {
		private final FormationTrailHistory history = new FormationTrailHistory();
		private List<Point> smoothed = List.of();
	}
}
