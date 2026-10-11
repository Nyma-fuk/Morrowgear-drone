package jp.morrowgear.drone.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;

final class StrikeBeaconRenderState extends EntityRenderState {
	Vec3 direction = new Vec3(0, 0, 1);
	boolean attached;
	int phase;
}
