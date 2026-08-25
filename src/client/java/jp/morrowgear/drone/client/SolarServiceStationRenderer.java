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
	private final DroneMesh mesh;
	private final DroneMesh lightSatelliteMesh;

	SolarServiceStationRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 1.8f;
		try {
			mesh = DroneMesh.read(context.getResourceManager().getResourceOrThrow(Identifier.fromNamespaceAndPath(
				MorrowgearDrone.MOD_ID, "models/entity/solar_service_station.mgm")).open());
			lightSatelliteMesh = DroneMesh.read(context.getResourceManager().getResourceOrThrow(Identifier.fromNamespaceAndPath(
				MorrowgearDrone.MOD_ID, "models/entity/charging_relay.mgm")).open());
		} catch (IOException exception) { throw new IllegalStateException(exception); }
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
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE),
			(pose, consumer) -> mesh.render(pose, consumer, state.lightCoords, state.rotorAngle));
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE),
			(pose, consumer) -> mesh.renderEmissive(pose, consumer, state.rotorAngle, 1.0f, 255));
		for (int slot = 0; slot < SolarStationLightingPolicy.SATELLITE_COUNT; slot++) {
			double angle = Math.toRadians(slot * 90.0);
			poseStack.pushPose();
			poseStack.translate(Math.cos(angle) * SolarStationLightingPolicy.ORBIT_RADIUS,
				SolarStationLightingPolicy.HEIGHT_OFFSET + Math.sin(state.ageInTicks * .08f + slot) * .08,
				Math.sin(angle) * SolarStationLightingPolicy.ORBIT_RADIUS);
			poseStack.mulPose(Axis.YP.rotationDegrees(slot * 90.0f + state.ageInTicks * 1.2f));
			poseStack.scale(.46f, .46f, .46f);
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE),
				(pose, consumer) -> lightSatelliteMesh.render(pose, consumer, state.lightCoords,
					state.rotorAngle * 1.6f));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE),
				(pose, consumer) -> lightSatelliteMesh.renderEmissive(pose, consumer,
					state.rotorAngle * 1.6f, 1.08f, 255));
			poseStack.popPose();
		}
		poseStack.popPose();
	}
}
