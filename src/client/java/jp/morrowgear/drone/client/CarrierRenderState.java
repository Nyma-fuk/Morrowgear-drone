package jp.morrowgear.drone.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import jp.morrowgear.drone.carrier.CarrierPolicy;

public final class CarrierRenderState extends EntityRenderState {
    public float heading;
    public double trailTime;
    public List<jp.morrowgear.drone.FormationTrailHistory.Point> trail = List.of();
    public Vec3 beamOrigin;
    public List<Vec3> beamEnds = List.of();
    public List<Vec3> scanEnds = List.of();
    public CarrierPolicy.WorkPhase phase = CarrierPolicy.WorkPhase.IDLE;
    public double phaseProgress;
    public CarrierEffectGeometry.Column column;
    public Vec3 capturePosition;
    public double captureProgress;
    public Vec3 boardingPad;
    public boolean combat;
}
