package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.IOException;
import jp.morrowgear.drone.ChargingRelayEntity;
import jp.morrowgear.drone.ChargingRelayPolicy;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.MorrowgearRenderTypes;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

final class ChargingRelayRenderer extends EntityRenderer<ChargingRelayEntity, ServiceEntityRenderState> {
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID,
		"textures/entity/emissive_white.png");
	private final RuntimeMesh mesh;

	ChargingRelayRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = .32f;
		mesh = RuntimeMesh.load("charging_relay");
	}

	@Override public ServiceEntityRenderState createRenderState() { return new ServiceEntityRenderState(); }
	@Override public void extractRenderState(ChargingRelayEntity entity, ServiceEntityRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.heading = entity.getYRot();
		state.rotorAngle = state.ageInTicks * .74f;
		net.minecraft.world.entity.Entity target = entity.level().getEntity(entity.targetId());
		Vec3 targetOffset = target == null ? null
			: target.position().add(0, target.getBbHeight(), 0).subtract(entity.position());
		state.chargeTargetOffset = targetOffset != null
			&& ChargingRelayPolicy.showBeam(true, targetOffset.length()) ? targetOffset : null;
	}
	@Override public void submit(ServiceEntityRenderState state, PoseStack poseStack,
		SubmitNodeCollector collector, CameraRenderState camera) {
		super.submit(state, poseStack, collector, camera);
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(-state.heading));
		mesh.submit(poseStack, collector, state.lightCoords, state.rotorAngle, 0, 0, true, false);
		poseStack.popPose();
		if (state.chargeTargetOffset != null) {
			Vec3 start = new Vec3(0, -.10, 0);
			Vec3 cameraOffset = camera.pos.subtract(new Vec3(state.x, state.y, state.z));
			collector.submitCustomGeometry(poseStack, MorrowgearRenderTypes.energyBeam(),
				(pose, consumer) -> CombatEffectRenderer.renderChargingBeam(pose, consumer,
					start, state.chargeTargetOffset, state.ageInTicks, cameraOffset, false));
			collector.submitCustomGeometry(poseStack, MorrowgearRenderTypes.visibleEnergyCore(),
				(pose, consumer) -> CombatEffectRenderer.renderChargingBeam(pose, consumer,
					start, state.chargeTargetOffset, state.ageInTicks, cameraOffset, true));
		}
	}
}
