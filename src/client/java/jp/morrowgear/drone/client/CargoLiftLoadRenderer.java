package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.morrowgear.drone.CargoLiftLoadEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;

final class CargoLiftLoadRenderer extends EntityRenderer<CargoLiftLoadEntity, CargoLiftLoadRenderState> {
    private final RuntimeMesh full = RuntimeMesh.load("carrier_cargo_container");
    private final RuntimeMesh far = RuntimeMesh.load("carrier_cargo_container_far");

    CargoLiftLoadRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 2.5f;
    }

    @Override public CargoLiftLoadRenderState createRenderState() { return new CargoLiftLoadRenderState(); }

    @Override public void extractRenderState(CargoLiftLoadEntity entity, CargoLiftLoadRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.visible = entity.carried();
        state.phase = entity.phase();
    }

    @Override public void submit(CargoLiftLoadRenderState state, PoseStack stack,
            SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, stack, collector, camera);
        if (!state.visible) return;
        double dx = camera.pos.x - state.x, dy = camera.pos.y - state.y, dz = camera.pos.z - state.z;
        RuntimeMesh mesh = dx * dx + dy * dy + dz * dz > 48 * 48 ? far : full;
        mesh.submit(stack, collector, state.lightCoords, 0, 0, 0, true, false, true, false);
    }
}
