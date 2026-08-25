package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.FormationLightTrailProfile;
import jp.morrowgear.drone.FormationTrailPolicy;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class FormationTrailController {
	private static final double TRACKING_RADIUS = 96.0;
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final Identifier WHITE_TEXTURE = Identifier.fromNamespaceAndPath(
		MorrowgearDrone.MOD_ID, "textures/entity/emissive_white.png");
	private static final Map<UUID, TrailState> TRAILS = new HashMap<>();

	private FormationTrailController() {}

	static void tick(Minecraft client) {
		if (client.level == null || client.player == null || client.isPaused()) {
			TRAILS.clear();
			return;
		}
		long gameTick = client.level.getGameTime();
		Set<UUID> visible = new HashSet<>();
		AABB area = client.player.getBoundingBox().inflate(TRACKING_RADIUS);
		for (DroneEntity drone : client.level.getEntitiesOfClass(DroneEntity.class, area, entity -> entity.isAlive())) {
			UUID id = drone.getUUID();
			visible.add(id);
			if (drone.isPowerLost()) {
				TRAILS.remove(id);
				continue;
			}
			TrailState state = TRAILS.computeIfAbsent(id, ignored -> new TrailState());
			FormationTrailPolicy.TrailStyle style = FormationTrailPolicy.style(drone.mode(), drone.combatState(),
				drone.missionExpected(), drone.missionStage(), drone.isDocked(), drone.serviceReturnActive(),
				drone.formationCatchUp(), drone.patrolRouteSize(), drone.cohortRank(),
				!drone.cohortLeaderId().isBlank(), drone.hasActiveFieldOperation(), drone.hasSecurityPatrol());
			if (style != state.style) {
				state.points.clear();
				state.activeTicks = 0;
			}
			boolean active = style != FormationTrailPolicy.TrailStyle.NONE;
			if (!active) state.points.clear();
			state.activeThisTick = active;
			state.style = style;
			if (active) state.activeTicks++; else state.activeTicks = 0;
			boolean immediate = style == FormationTrailPolicy.TrailStyle.COMBAT_ENTRY
				|| style == FormationTrailPolicy.TrailStyle.SERVICE_RETURN
				|| style == FormationTrailPolicy.TrailStyle.REJOIN;
			boolean activated = active && (immediate
				|| state.activeTicks > FormationTrailPolicy.activationDelay(drone.cohortRank()));
			if (activated && drone.getDeltaMovement().lengthSqr() >= 0.0025)
				sample(state, trailOrigin(drone), gameTick);
			prune(state, gameTick);
		}
		TRAILS.entrySet().removeIf(entry -> !FormationLightTrailProfile.retainState(
			visible.contains(entry.getKey()), entry.getValue().activeThisTick, !entry.getValue().points.isEmpty()));
	}

	static void render(UUID entityId, Vec3 entityPosition, PoseStack poseStack, SubmitNodeCollector collector) {
		TrailState state = entityId == null ? null : TRAILS.get(entityId);
		Minecraft client = Minecraft.getInstance();
		if (state == null || state.points.size() < 2 || client.level == null) return;
		long now = client.level.getGameTime();
		List<TrailPoint> points = smoothed(state.points);

		poseStack.pushPose();
		poseStack.translate(-entityPosition.x, -entityPosition.y, -entityPosition.z);
		if (state.style == FormationTrailPolicy.TrailStyle.COMBAT_ENTRY) {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.28f, 255, 42, 8, 84));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.15f, 255, 118, 18, 228));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.04f, 255, 238, 205, 255));
		} else if (state.style == FormationTrailPolicy.TrailStyle.SERVICE_RETURN) {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.24f, 255, 132, 18, 74));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.11f, 255, 202, 82, 230));
		} else if (state.style == FormationTrailPolicy.TrailStyle.REJOIN) {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.24f, 36, 224, 142, 76));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.10f, 178, 255, 218, 232));
		} else if (state.style == FormationTrailPolicy.TrailStyle.CATCH_UP) {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.30f, 12, 176, 246, 82));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.13f, 128, 244, 255, 238));
		} else {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.24f, 18, 164, 232, 72));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.14f, 8, 222, 255, 218));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> renderLayer(pose, consumer, points, now, 0.035f, 238, 252, 255, 255));
		}
		poseStack.popPose();
	}

	private static void sample(TrailState state, Vec3 position, long tick) {
		TrailPoint newest = state.points.peekLast();
		if (newest != null && !FormationLightTrailProfile.shouldSample(position.distanceTo(newest.position), tick - newest.tick)) return;
		state.points.addLast(new TrailPoint(position, tick));
		while (state.points.size() > FormationLightTrailProfile.MAX_CONTROL_POINTS) state.points.removeFirst();
	}

	private static void prune(TrailState state, long now) {
		while (!state.points.isEmpty()
			&& now - state.points.peekFirst().tick >= FormationLightTrailProfile.LIFETIME_TICKS) {
			state.points.removeFirst();
		}
	}

	private static Vec3 trailOrigin(DroneEntity drone) {
		Vec3 velocity = drone.getDeltaMovement();
		Vec3 direction = velocity.lengthSqr() > 0.0001 ? velocity.normalize()
			: new Vec3(0.0, 0.0, 1.0).yRot((float) Math.toRadians(-drone.getYRot()));
		return drone.position().add(0.0, drone.getBbHeight() * 0.42, 0.0).subtract(direction.scale(0.82));
	}

	private static List<TrailPoint> smoothed(Deque<TrailPoint> source) {
		List<TrailPoint> raw = new ArrayList<>(source);
		if (raw.size() < 3) return raw;
		List<TrailPoint> result = new ArrayList<>(raw.size());
		result.add(raw.getFirst());
		for (int index = 1; index < raw.size() - 1; index++) {
			Vec3 position = raw.get(index - 1).position.scale(0.2)
				.add(raw.get(index).position.scale(0.6))
				.add(raw.get(index + 1).position.scale(0.2));
			result.add(new TrailPoint(position, raw.get(index).tick));
		}
		result.add(raw.getLast());
		return result;
	}

	private static void renderLayer(PoseStack.Pose pose, VertexConsumer consumer, List<TrailPoint> points,
		long now, float width, int red, int green, int blue, int baseAlpha) {
		for (int index = 0; index < points.size() - 1; index++) {
			TrailPoint start = points.get(index);
			TrailPoint end = points.get(index + 1);
			Vec3 delta = end.position.subtract(start.position);
			if (delta.lengthSqr() < 0.000001) continue;
			int alphaStart = Math.round(baseAlpha * FormationLightTrailProfile.alpha((int) (now - start.tick)));
			int alphaEnd = Math.round(baseAlpha * FormationLightTrailProfile.alpha((int) (now - end.tick)));
			if (alphaStart <= 0 && alphaEnd <= 0) continue;
			Vec3 horizontal = new Vec3(-delta.z, 0.0, delta.x);
			if (horizontal.lengthSqr() < 0.000001) horizontal = new Vec3(1.0, 0.0, 0.0);
			horizontal = horizontal.normalize().scale(width * 0.5);
			Vec3 vertical = new Vec3(0.0, width * 0.5, 0.0);
			emitQuad(pose, consumer, start.position.subtract(vertical), start.position.add(vertical),
				end.position.add(vertical), end.position.subtract(vertical), red, green, blue, alphaStart, alphaEnd);
			emitQuad(pose, consumer, start.position.subtract(horizontal), start.position.add(horizontal),
				end.position.add(horizontal), end.position.subtract(horizontal), red, green, blue, alphaStart, alphaEnd);
		}
	}

	private static void emitQuad(PoseStack.Pose pose, VertexConsumer consumer, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
		int red, int green, int blue, int alphaStart, int alphaEnd) {
		emitVertex(pose, consumer, a, red, green, blue, alphaStart, 0.0f, 0.0f);
		emitVertex(pose, consumer, b, red, green, blue, alphaStart, 0.0f, 1.0f);
		emitVertex(pose, consumer, c, red, green, blue, alphaEnd, 1.0f, 1.0f);
		emitVertex(pose, consumer, d, red, green, blue, alphaEnd, 1.0f, 0.0f);
	}

	private static void emitVertex(PoseStack.Pose pose, VertexConsumer consumer, Vec3 point,
		int red, int green, int blue, int alpha, float u, float v) {
		consumer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
			.setColor(red, green, blue, alpha).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(FULL_BRIGHT).setNormal(pose, 0.0f, 1.0f, 0.0f);
	}

	private static final class TrailState {
		private final Deque<TrailPoint> points = new ArrayDeque<>();
		private int activeTicks;
		private boolean activeThisTick;
		private FormationTrailPolicy.TrailStyle style = FormationTrailPolicy.TrailStyle.NONE;
	}

	private record TrailPoint(Vec3 position, long tick) {}
}
