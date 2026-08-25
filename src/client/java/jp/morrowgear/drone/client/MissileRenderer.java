package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.MorrowgearMissileEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

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
	public void extractRenderState(MorrowgearMissileEntity entity, MissileRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.velocity = entity.getDeltaMovement();
	}

	@Override
	public void submit(MissileRenderState state, PoseStack poseStack,
		SubmitNodeCollector collector, CameraRenderState camera) {
		super.submit(state, poseStack, collector, camera);
		Vec3 direction = state.velocity.lengthSqr() < 0.001 ? new Vec3(0, 0, 1) : state.velocity.normalize();
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
			(pose, consumer) -> {
				CombatEffectRenderer.emitBeam(pose, consumer, direction.scale(-0.28),
					direction.scale(0.34), 0.24f, 232, 238, 242, 255);
				CombatEffectRenderer.emitBeam(pose, consumer, direction.scale(-0.22),
					direction.scale(0.38), 0.08f, 255, 255, 255, 255);
				CombatEffectRenderer.emitBeam(pose, consumer, direction.scale(-0.22),
					direction.scale(-1.45), 0.22f, 255, 82, 8, 92);
				CombatEffectRenderer.emitBeam(pose, consumer, direction.scale(-0.28),
					direction.scale(-0.92), 0.09f, 255, 246, 214, 245);
			});
	}
}
