package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.SalvageState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

final class SalvageEffectRenderer {
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final Identifier WHITE_TEXTURE = Identifier.fromNamespaceAndPath(
		MorrowgearDrone.MOD_ID, "textures/entity/emissive_white.png");

	private SalvageEffectRenderer() {}

	static void render(DroneRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		if (state.salvageTargetOffset == null || state.salvageState.ordinal() < SalvageState.HOOK.ordinal()) return;
		Vec3 hook = new Vec3(0, -jp.morrowgear.drone.SalvageTowPolicy.HOOK_DROP, 0);
		Vec3 load = state.salvageTargetOffset;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE_TEXTURE),
			(pose, consumer) -> {
				CombatEffectRenderer.emitBeam(pose, consumer, hook, load, 0.045f,
					49, 58, 61, 255);
				CombatEffectRenderer.emitBeam(pose, consumer, hook.add(0.018, 0, 0), load,
					0.014f, 231, 145, 30, 255);
			});
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
			(pose, consumer) -> CombatEffectRenderer.emitBeam(pose, consumer,
				hook.add(0, 0.015, 0), hook.add(0, -0.20, 0), 0.075f,
				255, 166, 35, 210));
	}
}
