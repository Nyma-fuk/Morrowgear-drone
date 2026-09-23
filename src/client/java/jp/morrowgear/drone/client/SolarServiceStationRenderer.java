package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.IOException;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.SolarStationLightingPolicy;
import jp.morrowgear.drone.SolarServiceStationEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;

final class SolarServiceStationRenderer extends EntityRenderer<SolarServiceStationEntity, ServiceEntityRenderState> {
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID,
		"textures/entity/emissive_white.png");
	private final RuntimeMesh mesh;
	private final RuntimeMesh lightSatelliteMesh;

	SolarServiceStationRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 1.8f;
		mesh = RuntimeMesh.load("solar_service_station");
		lightSatelliteMesh = RuntimeMesh.load("service_light");
	}

	@Override public ServiceEntityRenderState createRenderState() { return new ServiceEntityRenderState(); }
	@Override public void extractRenderState(SolarServiceStationEntity entity, ServiceEntityRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.heading = entity.getYRot();
		state.rotorAngle = state.ageInTicks * .34f;
	}
	@Override public void submit(ServiceEntityRenderState state, PoseStack poseStack,
		SubmitNodeCollector collector, CameraRenderState camera) {
		super.submit(state, poseStack, collector, camera);
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(-state.heading));
		mesh.submit(poseStack, collector, state.lightCoords, state.rotorAngle, 0, 0, true, false);
		for (int slot = 0; slot < SolarStationLightingPolicy.SATELLITE_COUNT; slot++) {
			double angle = Math.toRadians(slot * 90.0);
			poseStack.pushPose();
			poseStack.translate(Math.cos(angle) * SolarStationLightingPolicy.ORBIT_RADIUS,
				SolarStationLightingPolicy.HEIGHT_OFFSET + Math.sin(state.ageInTicks * .08f + slot) * .08,
				Math.sin(angle) * SolarStationLightingPolicy.ORBIT_RADIUS);
			poseStack.mulPose(Axis.YP.rotationDegrees(slot * 90.0f + state.ageInTicks * 1.2f));
			lightSatelliteMesh.submit(poseStack, collector, state.lightCoords, state.rotorAngle * 1.6f,
				0, 0, true, false);
			poseStack.popPose();
		}
		poseStack.popPose();
	}
}
