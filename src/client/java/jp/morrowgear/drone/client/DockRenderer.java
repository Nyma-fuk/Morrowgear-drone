package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.DockGeometryPolicy;
import jp.morrowgear.drone.block.DockBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

final class DockRenderer implements BlockEntityRenderer<DockBlockEntity, DockRenderer.State> {
	private final RuntimeMesh mesh = RuntimeMesh.load("dock");
	private final RuntimeMesh medium = RuntimeMesh.load("dock_lod", "dock");
	private final RuntimeMesh far = RuntimeMesh.load("dock_far", "dock");
	static final class State extends BlockEntityRenderState { boolean wide; float yaw; AABB bounds; }
	@Override public State createRenderState() { return new State(); }
	@Override public void extractRenderState(DockBlockEntity dock, State state, float partial, Vec3 camera,
		ModelFeatureRenderer.CrumblingOverlay breaking) {
		BlockEntityRenderer.super.extractRenderState(dock, state, partial, camera, breaking);
		state.wide = dock.getBlockState().is(MorrowgearDrone.WIDE_DOCK_CENTER);
		state.yaw = dock.facing().toYRot();
		double x = state.blockPos.getX() + .5, y = state.blockPos.getY(), z = state.blockPos.getZ() + .5;
		state.bounds = new AABB(x - DockGeometryPolicy.HALF_WIDTH, y + DockGeometryPolicy.MIN_Y,
			z - DockGeometryPolicy.HALF_WIDTH, x + DockGeometryPolicy.HALF_WIDTH,
			y + DockGeometryPolicy.MAX_Y, z + DockGeometryPolicy.HALF_WIDTH);
	}
	@Override public void submit(State state, PoseStack stack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (!state.wide) return;
		if (camera.cullFrustum != null && !camera.cullFrustum.isVisible(state.bounds)) return;
		var detail = DockGeometryPolicy.detail(distanceSquared(state.blockPos.getX(), state.blockPos.getY(),
			state.blockPos.getZ(), camera.pos));
		RuntimeMesh selected = switch (detail) { case FULL -> mesh; case MEDIUM -> medium; case FAR -> far; };
		stack.pushPose();
		stack.translate(.5, 0, .5);
		stack.mulPose(Axis.YP.rotationDegrees(-state.yaw));
		selected.submit(stack, collector, state.lightCoords, 0, 0, 0, true, false,
			detail == DockGeometryPolicy.Detail.FULL, false);
		stack.popPose();
	}
	// Keep extraction independent of the center chunk; cull the whole platform in submit.
	@Override public boolean shouldRenderOffScreen() { return true; }
	@Override public boolean shouldRender(DockBlockEntity dock, Vec3 camera) {
		var pos = dock.getBlockPos();
		return dock.getBlockState().is(MorrowgearDrone.WIDE_DOCK_CENTER)
			&& distanceSquared(pos.getX(), pos.getY(), pos.getZ(), camera) <= (double)getViewDistance() * getViewDistance();
	}
	private static double distanceSquared(int x, int y, int z, Vec3 camera) {
		return DockGeometryPolicy.distanceSquared(camera.x - x - .5, camera.y - y, camera.z - z - .5);
	}
}
