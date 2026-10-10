package jp.morrowgear.drone.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import jp.morrowgear.drone.carrier.CarrierPolicy;
import jp.morrowgear.drone.carrier.CarrierMiningEffectPolicy;
import jp.morrowgear.drone.carrier.CarrierProtection;
import jp.morrowgear.drone.carrier.CarrierContainerTransferPolicy;
import jp.morrowgear.drone.carrier.CarrierExpansionPolicy;

public final class CarrierRenderState extends EntityRenderState {
    public float heading;
    public double trailTime;
    public List<jp.morrowgear.drone.FormationTrailHistory.Point> trail = List.of();
    public Vec3 beamOrigin;
    public List<Vec3> beamEnds = List.of();
    public List<Vec3> scanEnds = List.of();
    public CarrierPolicy.WorkPhase phase = CarrierPolicy.WorkPhase.IDLE;
    public int phaseTick;
    public double phaseProgress;
    public CarrierEffectGeometry.Column column;
    public double outlineY;
    public CarrierMiningEffectPolicy.ChunkOffset scanChunk;
    public CarrierMiningEffectPolicy.Visual miningVisual;
    public Vec3 miningTarget;
    public Vec3 capturePosition;
    public double captureProgress;
    public CarrierProtection.PlasmaResponse plasmaResponse = CarrierProtection.PlasmaResponse.VAPORIZE;
    public int cargoModules;
    public int cargoHardpoints;
    public int missileHardpoints;
    public int missileLaunchTicks;
    public int missileSalvoSize;
    public int missilesFired;
    public int missileSourceMask;
    public boolean transferActive;
    public CarrierContainerTransferPolicy.Phase transferPhase = CarrierContainerTransferPolicy.Phase.COMPLETE;
    public CarrierContainerTransferPolicy.Direction transferDirection = CarrierContainerTransferPolicy.Direction.LOAD;
    public CarrierExpansionPolicy.Type transferType = CarrierExpansionPolicy.Type.CARGO;
    public int transferHardpoint = -1;
    public double transferProgress;
    public Vec3 transferGround;
    public Vec3 boardingPad;
    public boolean combat;
    public boolean mining;
}
