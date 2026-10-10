package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import jp.morrowgear.drone.DroneHardpoints;
import jp.morrowgear.drone.FieldOperationState;
import jp.morrowgear.drone.FieldOperationType;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.MorrowgearRenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;

/** Forestry scan and cutting beams share the aircraft nose emitter but have distinct cadence. */
final class FieldWorkEffectRenderer {
	private FieldWorkEffectRenderer() {}

	static void render(DroneRenderState state, PoseStack stack, SubmitNodeCollector collector, Vec3 cameraOffset) {
		if (state.fieldType != FieldOperationType.FORESTRY || state.docked || state.powerLost) return;
		boolean scanning = state.fieldState == FieldOperationState.SCOUT_SURVEY
			|| state.fieldState == FieldOperationState.ENGINEER_LOCAL_SURVEY
			|| state.fieldState == FieldOperationState.SCANNING;
		boolean cutting = state.fieldState == FieldOperationState.WORKING && state.fieldWorkTargetOffset != null;
		if (!scanning && !cutting) return;
		Vec3 aircraft = new Vec3(state.x, state.y, state.z);
		Vec3 origin = aircraft.add(DroneHardpoints.rotate(DroneHardpoints.FIELD_TOOL,
			state.heading, state.flightPitch, state.flightRoll));
		Vec3 camera = aircraft.add(cameraOffset);
		stack.pushPose();
		stack.translate(-aircraft.x, -aircraft.y, -aircraft.z);
		collector.submitCustomGeometry(stack, MorrowgearRenderTypes.energyBeam(), (pose, out) -> {
			if (scanning && state.fieldAnchorOffset != null) drawScan(pose, out, state, aircraft, origin, camera);
			if (cutting) drawCut(pose, out, origin, aircraft.add(state.fieldWorkTargetOffset), camera, state.lightCoords);
		});
		stack.popPose();
	}

	private static void drawScan(PoseStack.Pose pose, VertexConsumer out, DroneRenderState state,
		Vec3 aircraft, Vec3 origin, Vec3 camera) {
		Vec3 center = aircraft.add(state.fieldAnchorOffset);
		double radius = Math.max(8.0, state.fieldRadius);
		for (int lane = 0; lane < 3; lane++) {
			double phase = state.ageInTicks * 0.24 + lane * Math.PI * 2.0 / 3.0;
			double across = Math.sin(phase) * radius;
			double along = Math.cos(phase * 0.73 + lane) * radius * 0.92;
			Vec3 end = new Vec3(center.x + across, center.y + 0.25, center.z + along);
			beam(pose, out, origin, end, camera, lane == 0 ? .055f : .035f,
				32, 228, 220, lane == 0 ? 146 : 92, state.lightCoords);
			cross(pose, out, end, camera, .34f, 32, 228, 220, 104, state.lightCoords);
		}
	}

	private static void drawCut(PoseStack.Pose pose, VertexConsumer out, Vec3 origin, Vec3 end,
		Vec3 camera, int light) {
		beam(pose, out, origin, end, camera, .16f, 255, 128, 36, 54, light);
		beam(pose, out, origin, end, camera, .065f, 255, 222, 138, 210, light);
		beam(pose, out, origin, end, camera, .018f, 255, 255, 244, 255, light);
		cross(pose, out, end, camera, .42f, 255, 174, 66, 180, light);
	}

	private static void cross(PoseStack.Pose pose, VertexConsumer out, Vec3 center, Vec3 camera,
		float radius, int red, int green, int blue, int alpha, int light) {
		Vec3 view = camera.subtract(center).normalize();
		Vec3 right = view.cross(new Vec3(0, 1, 0));
		if (right.lengthSqr() < .0001) right = new Vec3(1, 0, 0);
		right = right.normalize().scale(radius);
		Vec3 up = view.cross(right).normalize().scale(radius);
		beam(pose, out, center.subtract(right), center.add(right), camera, .035f, red, green, blue, alpha, light);
		beam(pose, out, center.subtract(up), center.add(up), camera, .035f, red, green, blue, alpha, light);
	}

	private static void beam(PoseStack.Pose pose, VertexConsumer out, Vec3 start, Vec3 end, Vec3 camera,
		float width, int red, int green, int blue, int alpha, int light) {
		Vec3 side = end.subtract(start).cross(camera.subtract(start));
		if (side.lengthSqr() < .000001) side = end.subtract(start).cross(new Vec3(0, 1, 0));
		if (side.lengthSqr() < .000001) return;
		side = side.normalize().scale(width * .5);
		vertex(pose, out, start.subtract(side), red, green, blue, alpha / 2, light, 0, 0);
		vertex(pose, out, start.add(side), red, green, blue, alpha / 2, light, 0, 1);
		vertex(pose, out, end.add(side), red, green, blue, alpha, light, 1, 1);
		vertex(pose, out, end.subtract(side), red, green, blue, alpha, light, 1, 0);
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer out, Vec3 point,
		int red, int green, int blue, int alpha, int light, float u, float v) {
		out.addVertex(pose, (float)point.x, (float)point.y, (float)point.z)
			.setColor(red, green, blue, alpha).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light).setNormal(pose, 0, 1, 0);
	}
}
