package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.morrowgear.drone.carrier.CarrierCargoContainerBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class CarrierCargoContainerRenderer implements BlockEntityRenderer<CarrierCargoContainerBlockEntity, CarrierCargoContainerRenderer.State> {
    private final RuntimeMesh full = RuntimeMesh.load("carrier_cargo_container");
    private final RuntimeMesh far = RuntimeMesh.load("carrier_cargo_container_far");
    static final class State extends BlockEntityRenderState { AABB bounds; }
    @Override public State createRenderState() { return new State(); }
    @Override public void extractRenderState(CarrierCargoContainerBlockEntity cargo, State state, float partial,
                                             Vec3 camera, ModelFeatureRenderer.CrumblingOverlay breaking) {
        BlockEntityRenderer.super.extractRenderState(cargo,state,partial,camera,breaking);
        double x=state.blockPos.getX()+.5,y=state.blockPos.getY(),z=state.blockPos.getZ()+.5;
        state.bounds=new AABB(x-2.5,y,z-1.5,x+2.5,y+2.4,z+1.5);
    }
    @Override public void submit(State state, PoseStack stack, SubmitNodeCollector collector, CameraRenderState camera) {
        if(camera.cullFrustum!=null&&!camera.cullFrustum.isVisible(state.bounds)) return;
        double dx=camera.pos.x-state.blockPos.getX()-.5,dy=camera.pos.y-state.blockPos.getY(),dz=camera.pos.z-state.blockPos.getZ()-.5;
        RuntimeMesh mesh=dx*dx+dy*dy+dz*dz>48*48?far:full;
        stack.pushPose(); stack.translate(.5,0,.5);
        mesh.submit(stack,collector,state.lightCoords,0,0,0,true,false,true,false);
        stack.popPose();
    }
    @Override public boolean shouldRenderOffScreen(){return true;}
    @Override public int getViewDistance(){return 128;}
}
