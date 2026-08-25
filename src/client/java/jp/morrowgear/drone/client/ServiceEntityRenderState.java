package jp.morrowgear.drone.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;

final class ServiceEntityRenderState extends EntityRenderState {
	float heading;
	float rotorAngle;
	Vec3 chargeTargetOffset;
}
