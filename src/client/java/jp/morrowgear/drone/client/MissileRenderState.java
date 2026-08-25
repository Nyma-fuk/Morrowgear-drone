package jp.morrowgear.drone.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;

final class MissileRenderState extends EntityRenderState {
	Vec3 velocity = new Vec3(0, 0, 1);
}
