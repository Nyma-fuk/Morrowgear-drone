package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.StrikeBeaconEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Compact mechanical bacteriophage: folded dart in flight, four-point clamp after impact. */
final class StrikeBeaconRenderer extends EntityRenderer<StrikeBeaconEntity, StrikeBeaconRenderState> {
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath(
		MorrowgearDrone.MOD_ID, "textures/entity/emissive_white.png");
	private static final int SIDES = 8;

	StrikeBeaconRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 0;
	}

	@Override public StrikeBeaconRenderState createRenderState() { return new StrikeBeaconRenderState(); }

	@Override protected AABB getBoundingBoxForCulling(StrikeBeaconEntity entity) {
		return entity.getBoundingBox().inflate(1.2);
	}

	@Override public void extractRenderState(StrikeBeaconEntity entity, StrikeBeaconRenderState state,
		float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.attached = entity.attached();
		state.phase = entity.operationPhase();
		if (state.attached) state.direction = entity.surfaceNormal().scale(-1);
		else state.direction = Vec3.directionFromRotation(
			Mth.lerp(partialTick, entity.xRotO, entity.getXRot()),
			Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot()));
	}

	@Override public void submit(StrikeBeaconRenderState state, PoseStack stack,
		SubmitNodeCollector collector, CameraRenderState camera) {
		super.submit(state, stack, collector, camera);
		Vec3 forward = state.direction.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : state.direction.normalize();
		Vec3 x = forward.cross(Math.abs(forward.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
		Vec3 y = forward.cross(x).normalize();
		collector.submitCustomGeometry(stack, RenderTypes.entityCutout(WHITE), (pose, out) -> {
			// Graphite armored head, neck, base plate and penetrator sheath.
			tube(pose, out, forward, x, y, -.38, -.20, .19, .22, 0x20282D, 255, state.lightCoords);
			tube(pose, out, forward, x, y, -.20, -.08, .22, .13, 0x4D5A61, 255, state.lightCoords);
			tube(pose, out, forward, x, y, -.08, .06, .085, .085, 0x151B20, 255, state.lightCoords);
			tube(pose, out, forward, x, y, .04, .16, .18, .18, 0x59666C, 255, state.lightCoords);
			tube(pose, out, forward, x, y, .12, .56, .078, .055, 0x303B42, 255, state.lightCoords);
			tube(pose, out, forward, x, y, .56, .76, .055, .008, 0xA6B2B7, 255, state.lightCoords);
			for (int leg = 0; leg < 4; leg++) {
				double angle = Math.PI * .25 + leg * Math.PI * .5;
				Vec3 radial = x.scale(Math.cos(angle)).add(y.scale(Math.sin(angle)));
				Vec3 tangent = x.scale(-Math.sin(angle)).add(y.scale(Math.cos(angle)));
				Vec3 root = forward.scale(.08).add(radial.scale(.14));
				Vec3 knee = state.attached
					? forward.scale(.30).add(radial.scale(.36)).add(tangent.scale((leg & 1) == 0 ? .025 : -.025))
					: forward.scale(.34).add(radial.scale(.115));
				Vec3 foot = state.attached
					? forward.scale(.57).add(radial.scale(.43))
					: forward.scale(.59).add(radial.scale(.075));
				rod(pose, out, root, knee, .035, 0x3D484E, 255, state.lightCoords);
				rod(pose, out, knee, foot, .028, 0x78868C, 255, state.lightCoords);
				rod(pose, out, knee.subtract(radial.scale(.03)), knee.add(radial.scale(.03)),
					.052, 0x00BCD4, 255, state.lightCoords);
			}
		});
		collector.submitCustomGeometry(stack, RenderTypes.entityTranslucentEmissive(WHITE), (pose, out) -> {
			double pulse = .5 + .5 * Math.sin(state.ageInTicks * (state.phase == 2 ? .95 : .42));
			int alpha = 120 + (int)Math.round(125 * pulse);
			// Red beacon crown, with a thin cyan machine-status collar.
			tube(pose, out, forward, x, y, -.405, -.345, .14, .19, 0xFF2C2C, alpha, 0xF000F0);
			tube(pose, out, forward, x, y, -.10, -.065, .115, .115, 0x29E4F2, 205, 0xF000F0);
		});
	}

	private static void rod(PoseStack.Pose pose, VertexConsumer out, Vec3 from, Vec3 to,
		double radius, int color, int alpha, int light) {
		Vec3 direction = to.subtract(from);
		if (direction.lengthSqr() < 1.0e-8) return;
		direction = direction.normalize();
		Vec3 x = direction.cross(Math.abs(direction.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
		Vec3 y = direction.cross(x).normalize();
		for (int side = 0; side < 6; side++) {
			double a = side * Math.PI / 3, b = (side + 1) * Math.PI / 3;
			Vec3 ra = x.scale(Math.cos(a) * radius).add(y.scale(Math.sin(a) * radius));
			Vec3 rb = x.scale(Math.cos(b) * radius).add(y.scale(Math.sin(b) * radius));
			quad(pose, out, from.add(ra), from.add(rb), to.add(rb), to.add(ra), color, alpha, light);
		}
	}

	private static void tube(PoseStack.Pose pose, VertexConsumer out, Vec3 forward, Vec3 x, Vec3 y,
		double from, double to, double radiusA, double radiusB, int color, int alpha, int light) {
		for (int side = 0; side < SIDES; side++) {
			double a = side * Math.PI * 2 / SIDES, b = (side + 1) * Math.PI * 2 / SIDES;
			Vec3 ra = x.scale(Math.cos(a)).add(y.scale(Math.sin(a)));
			Vec3 rb = x.scale(Math.cos(b)).add(y.scale(Math.sin(b)));
			quad(pose, out, forward.scale(from).add(ra.scale(radiusA)),
				forward.scale(from).add(rb.scale(radiusA)), forward.scale(to).add(rb.scale(radiusB)),
				forward.scale(to).add(ra.scale(radiusB)), color, alpha, light);
		}
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer out, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
		int color, int alpha, int light) {
		Vec3 normal = b.subtract(a).cross(c.subtract(a));
		if (normal.lengthSqr() < 1.0e-9) return;
		normal = normal.normalize();
		vertex(pose, out, a, normal, color, alpha, light, 0, 0);
		vertex(pose, out, b, normal, color, alpha, light, 0, 1);
		vertex(pose, out, c, normal, color, alpha, light, 1, 1);
		vertex(pose, out, d, normal, color, alpha, light, 1, 0);
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer out, Vec3 point, Vec3 normal,
		int color, int alpha, int light, float u, float v) {
		out.addVertex(pose, (float)point.x, (float)point.y, (float)point.z)
			.setColor((color >> 16) & 255, (color >> 8) & 255, color & 255, alpha)
			.setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
			.setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
	}
}
