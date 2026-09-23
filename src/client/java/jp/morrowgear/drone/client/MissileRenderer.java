package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.MorrowgearMissileEntity;
import jp.morrowgear.drone.MissileTrailHistory;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

final class MissileRenderer extends EntityRenderer<MorrowgearMissileEntity, MissileRenderState> {
	private static final Identifier WHITE_TEXTURE = Identifier.fromNamespaceAndPath(
		MorrowgearDrone.MOD_ID, "textures/entity/emissive_white.png");

	MissileRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 0.0f;
	}

	@Override
	public MissileRenderState createRenderState() {
		return new MissileRenderState();
	}

	@Override
	protected AABB getBoundingBoxForCulling(MorrowgearMissileEntity entity) {
		AABB bounds = entity.getBoundingBox().inflate(1);
		var history = entity.visualTrail();
		for (int i = Math.max(0, history.size() - MissileTrailHistory.MAX_POINTS); i < history.size(); i++) {
			Vec3 point = history.get(i).position();
			bounds = bounds.minmax(new AABB(point, point).inflate(0.5));
		}
		return bounds;
	}

	@Override
	public void extractRenderState(MorrowgearMissileEntity entity, MissileRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.velocity = Vec3.directionFromRotation(Mth.lerp(partialTick, entity.xRotO, entity.getXRot()),
			Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot()));
		state.impacted = entity.impacted();
		state.motorIgnited = entity.motorIgnited();
		state.ejecting = entity.flightPhase() == MorrowgearMissileEntity.FlightPhase.EJECT;
		List<MissileTrailHistory.Point> history = entity.visualTrail();
		state.trail = List.copyOf(history.subList(Math.max(0, history.size() - MissileTrailHistory.MAX_POINTS), history.size()));
		state.trailTime = entity.level().getGameTime() + partialTick;
		if (state.impacted) {
			Vec3 anchor = entity.attachedRenderPosition(partialTick);
			if (anchor != null) { state.x = anchor.x; state.y = anchor.y; state.z = anchor.z; }
		}
	}

	@Override
	public void submit(MissileRenderState state, PoseStack poseStack,
		SubmitNodeCollector collector, CameraRenderState camera) {
		super.submit(state, poseStack, collector, camera);
		Vec3 direction = state.velocity.lengthSqr() < 0.001 ? new Vec3(0, 0, 1) : state.velocity.normalize();
		Vec3 side = direction.cross(new Vec3(0, 1, 0));
		if (side.lengthSqr() < 0.0001) side = new Vec3(1, 0, 0);
		Vec3 axisX = side.normalize(), axisY = direction.cross(axisX).normalize();
		float flash = (float)Math.pow(0.5 + 0.5 * Math.cos(state.ageInTicks * Math.PI / 3.2), 3);
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE_TEXTURE), (pose, out) -> {
			for (int i = 0; i + 1 < MissileCapsuleShape.HULL.size(); i++) {
				var a = MissileCapsuleShape.HULL.get(i);
				var b = MissileCapsuleShape.HULL.get(i + 1);
				tube(pose, out, direction, axisX, axisY, a.z(), b.z(), a.radius(), b.radius(), a.color(), 255, state.lightCoords);
			}
			cap(pose, out, direction, axisX, axisY, MissileCapsuleShape.REAR, 0.10, false, 0x20282D, state.lightCoords);
			cap(pose, out, direction, axisX, axisY, MissileCapsuleShape.FRONT, 0.11, true, 0xBAC5CB, state.lightCoords);
		});
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
			(pose, consumer) -> {
				tube(pose, consumer, direction, axisX, axisY, MissileCapsuleShape.GLOW_FROM, MissileCapsuleShape.GLOW_TO,
					MissileCapsuleShape.GLOW_RADIUS, MissileCapsuleShape.GLOW_RADIUS, 0xFF382A, 192 + Math.round(32 * flash), 0xF000F0);
				if (MissileCapsuleShape.exhaustVisible(state.motorIgnited, state.impacted)) {
					tube(pose, consumer, direction, axisX, axisY, MissileCapsuleShape.EXHAUST_END, MissileCapsuleShape.REAR,
						0.025, 0.09, 0xFF842E, 135, 0xF000F0);
					tube(pose, consumer, direction, axisX, axisY, MissileCapsuleShape.EXHAUST_CORE_END, MissileCapsuleShape.REAR,
						0.018, 0.047, 0xFFE9A6, 230, 0xF000F0);
				}
			});
		boolean cold = MissileCapsuleShape.ejectVisible(state.ejecting, state.motorIgnited, state.impacted, state.ageInTicks);
		if (!state.trail.isEmpty() || cold) collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(WHITE_TEXTURE),
			(pose, consumer) -> {
				smoke(pose, consumer, state, camera.pos);
				if (cold) coldEject(pose, consumer, state, direction, camera.pos);
			});
	}

	private static void smoke(PoseStack.Pose pose, VertexConsumer out, MissileRenderState state, Vec3 camera) {
		Vec3 origin = new Vec3(state.x, state.y, state.z);
		int count = MissileCapsuleShape.smokeCount(state.trail.size());
		for (int i = 0; i < count; i++) {
			var point = state.trail.get(MissileCapsuleShape.historyIndex(i, state.trail.size()));
			double age = MissileCapsuleShape.age(state.trailTime - point.tick(), MissileTrailHistory.LIFETIME_TICKS);
			int alpha = MissileCapsuleShape.smokeAlpha(age);
			if (alpha == 0) continue;
			Vec3 center = point.position().add(0, MissileCapsuleShape.smokeRise(age), 0).subtract(origin);
			double radius = MissileCapsuleShape.smokeRadius(age);
			puff(pose, out, center, camera.subtract(origin), radius, 0x949BA0, alpha, state.lightCoords);
		}
	}

	private static void coldEject(PoseStack.Pose pose, VertexConsumer out, MissileRenderState state, Vec3 direction, Vec3 camera) {
		double progress = state.ageInTicks / 3.0;
		Vec3 cameraOffset = camera.subtract(new Vec3(state.x, state.y, state.z));
		for (int i = 0; i < MissileCapsuleShape.COLD_EJECT_PUFFS; i++) {
			Vec3 center = direction.scale(MissileCapsuleShape.REAR - 0.08 - i * 0.10 - progress * 0.15);
			puff(pose, out, center, cameraOffset, 0.07 + progress * 0.08 + i * 0.018,
				0xD7DCDD, (int)Math.round(85 * (1 - progress) * (i == 0 ? 1 : 0.65)), state.lightCoords);
		}
	}

	private static void puff(PoseStack.Pose pose, VertexConsumer out, Vec3 center, Vec3 cameraOffset,
		double radius, int color, int alpha, int light) {
		Vec3 normal = cameraOffset.subtract(center);
		if (normal.lengthSqr() < 0.0001) return;
		normal = normal.normalize();
		Vec3 right = normal.cross(new Vec3(0, 1, 0));
		if (right.lengthSqr() < 0.0001) right = new Vec3(1, 0, 0);
		right = right.normalize();
		Vec3 up = normal.cross(right).normalize();
		// Radial alpha, normal lighting and ordinary depth: smoke is not a luminous ribbon.
		for (int side = 0; side < MissileCapsuleShape.SIDES; side++) {
			double a = MissileCapsuleShape.angle(side);
			double b = MissileCapsuleShape.angle(side + 1);
			Vec3 edgeA = center.add(right.scale(Math.cos(a) * radius)).add(up.scale(Math.sin(a) * radius));
			Vec3 edgeB = center.add(right.scale(Math.cos(b) * radius)).add(up.scale(Math.sin(b) * radius));
			vertex(pose, out, center, normal, color, alpha, light, 0.5f, 0.5f);
			vertex(pose, out, edgeA, normal, color, 0, light, 0, 0);
			vertex(pose, out, edgeB, normal, color, 0, light, 1, 1);
			vertex(pose, out, edgeB, normal, color, 0, light, 1, 1);
		}
	}

	private static void cap(PoseStack.Pose pose, VertexConsumer out, Vec3 forward, Vec3 x, Vec3 y,
		double z, double radius, boolean front, int color, int light) {
		Vec3 center = forward.scale(z);
		for (int side = 0; side < MissileCapsuleShape.SIDES; side++) {
			double a = MissileCapsuleShape.angle(side);
			double b = MissileCapsuleShape.angle(side + 1);
			Vec3 edgeA = center.add(x.scale(Math.cos(a) * radius)).add(y.scale(Math.sin(a) * radius));
			Vec3 edgeB = center.add(x.scale(Math.cos(b) * radius)).add(y.scale(Math.sin(b) * radius));
			quad(pose, out, center, front ? edgeA : edgeB, front ? edgeB : edgeA, front ? edgeB : edgeA, color, 255, light);
		}
	}

	private static void tube(PoseStack.Pose pose, VertexConsumer out, Vec3 forward, Vec3 x, Vec3 y,
		double from, double to, double radiusA, double radiusB, int color, int alpha, int light) {
		for (int i = 0; i < MissileCapsuleShape.SIDES; i++) {
			double a = MissileCapsuleShape.angle(i), b = MissileCapsuleShape.angle(i + 1);
			Vec3 radialA = x.scale(Math.cos(a)).add(y.scale(Math.sin(a)));
			Vec3 radialB = x.scale(Math.cos(b)).add(y.scale(Math.sin(b)));
			quad(pose, out, forward.scale(from).add(radialA.scale(radiusA)), forward.scale(from).add(radialB.scale(radiusA)),
				forward.scale(to).add(radialB.scale(radiusB)), forward.scale(to).add(radialA.scale(radiusB)), color, alpha, light);
		}
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer out, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
		int color, int alpha, int light) {
		Vec3 normal = b.subtract(a).cross(c.subtract(a)).normalize();
		vertex(pose, out, a, normal, color, alpha, light, 0, 0);
		vertex(pose, out, b, normal, color, alpha, light, 0, 1);
		vertex(pose, out, c, normal, color, alpha, light, 1, 1);
		vertex(pose, out, d, normal, color, alpha, light, 1, 0);
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer out, Vec3 point, Vec3 normal,
		int color, int alpha, int light, float u, float v) {
		out.addVertex(pose, (float)point.x, (float)point.y, (float)point.z)
			.setColor((color >> 16) & 255, (color >> 8) & 255, color & 255, alpha).setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
			.setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
	}
}
