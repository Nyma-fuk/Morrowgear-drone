package jp.morrowgear.drone.client;

import jp.morrowgear.drone.CargoLiftPolicy;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

final class CargoLiftLoadRenderState extends EntityRenderState {
    boolean visible;
    CargoLiftPolicy.Phase phase = CargoLiftPolicy.Phase.RESERVING;
}
