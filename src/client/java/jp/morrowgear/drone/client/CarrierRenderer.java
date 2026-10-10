package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;
import jp.morrowgear.drone.carrier.CarrierTrailPolicy;
import jp.morrowgear.drone.carrier.CarrierEntity;
import jp.morrowgear.drone.carrier.CarrierPolicy;
import jp.morrowgear.drone.carrier.CarrierMiningEffectPolicy;
import jp.morrowgear.drone.carrier.CarrierCargoVisualPolicy;
import jp.morrowgear.drone.carrier.CarrierContainerTransferPolicy;
import jp.morrowgear.drone.carrier.CarrierExpansionPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.MorrowgearRenderTypes;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.renderer.texture.OverlayTexture;
import java.util.List;
import jp.morrowgear.drone.client.CarrierEffectGeometry.Point;
import jp.morrowgear.drone.client.CarrierEffectGeometry.Quad;

/** Uses the same block-space model as the bay and boarding contracts. */
public final class CarrierRenderer extends EntityRenderer<CarrierEntity, CarrierRenderState> {
    private static final Identifier TRAIL_TEXTURE = Identifier.fromNamespaceAndPath("morrowgear_drone", "textures/entity/emissive_white.png");
    private final RuntimeMesh mesh = RuntimeMesh.load("carrier");
    private final RuntimeMesh distantMesh = RuntimeMesh.load("carrier_lod");
    private final RuntimeMesh farMesh = RuntimeMesh.load("carrier_far", "carrier_lod");
    private final RuntimeMesh cargoContainer = RuntimeMesh.load("carrier_cargo_container");
    private final RuntimeMesh cargoContainerFar = RuntimeMesh.load("carrier_cargo_container_far");
    private final RuntimeMesh missileContainer = RuntimeMesh.load("carrier_missile_container");
    private final RuntimeMesh missileContainerFar = RuntimeMesh.load("carrier_missile_container_far");
    private final CarrierEffectTimeline timeline = new CarrierEffectTimeline();

    public CarrierRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 10;
    }

    @Override public CarrierRenderState createRenderState() { return new CarrierRenderState(); }

    @Override protected AABB getBoundingBoxForCulling(CarrierEntity entity) {
        AABB hull = CarrierTrailController.bounds(entity);
        if (entity.beamActive()) {
            for (var path : entity.beamPaths().stream().limit(CarrierEffectGeometry.MAX_BEAMS).toList())
                hull = hull.minmax(new AABB(path.origin(), path.target()).inflate(entity.workMode() == CarrierPolicy.Mode.COMBAT
                    ? CarrierPolicy.COMBAT_IMPACT_RADIUS : CarrierEffectGeometry.APERTURE_RADIUS));
        }
        if (entity.workMode() == CarrierPolicy.Mode.MINING && entity.workPhase() != CarrierPolicy.WorkPhase.IDLE) {
            AABB work = new AABB(Vec3.atLowerCornerOf(entity.operationMin()),
                Vec3.atLowerCornerOf(entity.operationMax().offset(1,1,1)));
            if (entity.workPhase() == CarrierPolicy.WorkPhase.SCAN) work = work.inflate(16, 0, 16);
            hull = hull.minmax(work);
        }
        if (entity.workMode() == CarrierPolicy.Mode.MINING && entity.captureSequence() > 0)
            hull = hull.minmax(new AABB(entity.beamOrigin(), Vec3.atCenterOf(entity.lastCapturePosition())).inflate(1.15));
        var pad = entity.visualBoardingPad();
        if (pad.isPresent()) hull = hull.minmax(new AABB(pad.get()).inflate(1.2));
        return hull;
    }

    @Override public void extractRenderState(CarrierEntity entity, CarrierRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.heading = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        state.trailTime = entity.level().getGameTime() + partialTick;
        var trail = new java.util.ArrayList<>(CarrierTrailController.points(entity));
        if (!trail.isEmpty() && CarrierTrailController.emitting(entity)) {
            Vec3 interpolated = new Vec3(state.x, state.y, state.z);
            var newest = trail.getLast();
            trail.set(trail.size() - 1, newest.at(
                interpolated.add(CarrierTrailPolicy.rotate(CarrierTrailPolicy.LEFT, state.heading)),
                interpolated.add(CarrierTrailPolicy.rotate(CarrierTrailPolicy.RIGHT, state.heading))));
        }
        state.trail = List.copyOf(trail);
        state.combat = entity.workMode() == CarrierPolicy.Mode.COMBAT;
        state.mining = entity.workMode() == CarrierPolicy.Mode.MINING;
        Vec3 origin = new Vec3(state.x, state.y, state.z);
        state.beamOrigin = entity.beamOrigin().subtract(origin);
        var timing = timeline.observe(entity.getUUID(), entity.sampleTick(), entity.captureSequence(), entity.capturedItems(),
            entity.tickCount + partialTick, point(Vec3.atCenterOf(entity.lastCapturePosition())));
        state.phase = timing.fresh() ? entity.workPhase() : CarrierPolicy.WorkPhase.IDLE;
        state.phaseTick = entity.phaseTick();
        state.phaseProgress = CarrierEffectGeometry.progress(entity.phaseTick(), entity.phaseDuration(), timing.interpolation());
        var min = entity.operationMin(); var max = entity.operationMax();
        state.column = state.combat ? null : new CarrierEffectGeometry.Column(min.getX() - origin.x, max.getX() + 1 - origin.x,
            min.getZ() - origin.z, max.getZ() + 1 - origin.z);
        state.outlineY = entity.miningSurfaceY() + .055 - origin.y;
        state.scanChunk = CarrierMiningEffectPolicy.scanChunk(entity.phaseTick());
        state.miningVisual = CarrierMiningEffectPolicy.visual(state.phase, entity.phaseTick(), entity.phaseDuration());
        List<Vec3> fireTargets = state.phase == CarrierPolicy.WorkPhase.FIRE && entity.beamActive()
            ? entity.beamPaths().stream().limit(1)
                .map(path -> path.toRenderLocal(origin)).filter(path -> CarrierEffectGeometry.validRay(
                    point(path.origin()), point(path.target()), state.column)).map(jp.morrowgear.drone.carrier.CarrierBeamPath.Local::target).toList()
            : List.of();
        state.beamEnds = state.combat ? fireTargets : List.of();
        state.scanEnds = (state.phase == CarrierPolicy.WorkPhase.SCAN || state.combat && state.phase == CarrierPolicy.WorkPhase.CHARGE)
            && entity.beamActive() ? entity.beamPaths().stream().limit(CarrierEffectGeometry.MAX_BEAMS)
                .map(path -> path.toRenderLocal(origin)).filter(path -> CarrierEffectGeometry.validRay(
                    point(path.origin()), point(path.target()), state.column))
                .map(jp.morrowgear.drone.carrier.CarrierBeamPath.Local::target).toList() : List.of();
        state.miningTarget = state.mining && !fireTargets.isEmpty() ? fireTargets.getFirst() : null;
        state.capturePosition = timing.fresh() && entity.workMode() == CarrierPolicy.Mode.MINING
            && timing.captureAge() >= 0 && timing.captureAge() < CarrierEffectTimeline.CAPTURE_TICKS
            && timing.position() != null ? vec(timing.position()).subtract(origin) : null;
        state.captureProgress = timing.captureAge() / CarrierEffectTimeline.CAPTURE_TICKS;
        state.plasmaResponse = entity.plasmaResponse();
        state.cargoModules = entity.cargoModules();
        state.cargoHardpoints = entity.cargoHardpoints();
        state.missileHardpoints = entity.missileHardpoints();
        state.missileLaunchTicks = entity.missileLaunchTicks();
        state.missileSalvoSize = entity.missileSalvoSize();
        state.missilesFired = entity.missilesFired();
        state.missileSourceMask = entity.missileSourceMask();
        state.transferActive = entity.transferActive();
        state.transferPhase = entity.transferPhase(); state.transferDirection = entity.transferDirection();
        state.transferType = entity.transferType(); state.transferHardpoint = entity.transferHardpoint();
        state.transferProgress = transferProgress(state.transferPhase, entity.transferTick(), partialTick);
        state.transferGround = state.transferActive
            ? entity.toLocal(Vec3.atBottomCenterOf(entity.transferContainerPos())) : null;
        var player = Minecraft.getInstance().player;
        state.boardingPad = player != null && entity.isOwnedBy(player.getUUID()) ? entity.visualBoardingPad()
            .map(pos -> Vec3.atBottomCenterOf(pos).add(0, .06, 0)
                .subtract(new Vec3(state.x, state.y, state.z))).orElse(null) : null;
    }

    @Override public void submit(CarrierRenderState state, PoseStack poses,
                                 SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, poses, collector, camera);
        Vec3 eye = camera.pos.subtract(new Vec3(state.x, state.y, state.z));
        double distanceSquared = eye.lengthSqr();
        RuntimeGeometryBudgetPolicy.Detail quality = RuntimeGeometryBudgetController.carrier(distanceSquared, camera.pos);
        boolean near = quality == RuntimeGeometryBudgetPolicy.Detail.FULL;
        RuntimeMesh detail = near ? mesh : quality == RuntimeGeometryBudgetPolicy.Detail.MEDIUM ? distantMesh : farMesh;
        poses.pushPose();
        poses.mulPose(Axis.YP.rotationDegrees(state.heading));
        detail.submit(poses, collector, state.lightCoords, 0, 0, 0, true, state.combat, near, near);
        renderHeavyMissileHatches(state,poses,collector);
        renderExpansionContainers(state, poses, collector, near);
        poses.popPose();
        if (state.trail.size() > 1) {
            Vec3 origin = new Vec3(state.x, state.y, state.z);
            collector.submitCustomGeometry(poses, RenderTypes.entityTranslucentEmissive(TRAIL_TEXTURE), (pose, out) -> {
                for (int i = 1; i < state.trail.size(); i++) {
                    var a = state.trail.get(i - 1); var b = state.trail.get(i);
                    if (a.strip() != b.strip()) continue;
                    float fade = Math.min(a.alpha(state.trailTime), b.alpha(state.trailTime));
                    if (fade <= 0) continue;
                    for (int side = 0; side < 2; side++) {
                        Vec3 start = (side == 0 ? a.left() : a.right()).subtract(origin);
                        Vec3 end = (side == 0 ? b.left() : b.right()).subtract(origin);
                        CombatEffectRenderer.emitBeamRibbon(pose, out, start, end, eye, .9f, 120, 210, 230, (int)(45 * fade));
                        CombatEffectRenderer.emitBeamRibbon(pose, out, start, end, eye, .28f, 215, 245, 255, (int)(165 * fade));
                    }
                }
            });
        }
        if (state.boardingPad != null && eye.distanceToSqr(state.boardingPad) < 96 * 96) {
            Vec3 pad = state.boardingPad;
            collector.submitCustomGeometry(poses, MorrowgearRenderTypes.visibleEnergyCore(), (pose, out) -> {
                for (int x : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
                    Vec3 corner = pad.add(x * .85, 0, z * .85);
                    CombatEffectRenderer.emitBeamRibbon(pose, out, corner,
                        corner.add(-x * .45, 0, 0), eye, .045f, 100, 235, 255, 215);
                    CombatEffectRenderer.emitBeamRibbon(pose, out, corner,
                        corner.add(0, 0, -z * .45), eye, .045f, 100, 235, 255, 215);
                }
            });
        }
        if (state.phase == CarrierPolicy.WorkPhase.IDLE) return;
        int green = state.combat ? 112 : 224;
        int blue = state.combat ? 32 : 255;
        int color = (state.combat ? 255 : 110) << 16 | green << 8 | blue;
        Point start = point(state.beamOrigin);
        if (state.mining && state.column != null) collector.submitCustomGeometry(poses,
            MorrowgearRenderTypes.visibleEnergyCore(), (pose, out) -> {
                Point impactCenter = new Point((state.column.minX() + state.column.maxX()) * .5,
                    state.outlineY + .04, (state.column.minZ() + state.column.maxZ()) * .5);
                geometry(pose, out, CarrierEffectGeometry.circleOutline(impactCenter,
                    CarrierPolicy.MINING_IMPACT_RADIUS, state.outlineY, .16, state.column),
                    0x6EEBFF, 225);
                if (state.phase == CarrierPolicy.WorkPhase.SCAN) {
                    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                        geometry(pose, out, CarrierEffectGeometry.outline(
                            CarrierEffectGeometry.centralChunk(state.column, x, z), state.outlineY, .055, 0),
                            0x54BCD0, x == 0 && z == 0 ? 105 : 58);
                    var scan = CarrierEffectGeometry.centralChunk(state.column, state.scanChunk.x(), state.scanChunk.z());
                    geometry(pose, out, CarrierEffectGeometry.outline(scan, state.outlineY + .018, .18, .08),
                        0xD8FCFF, 235);
                }
                if (state.phase == CarrierPolicy.WorkPhase.FIRE && state.miningTarget != null
                    && state.miningVisual.releasePulse() > 0) {
                    double pulse = state.miningVisual.releasePulse();
                    Point impact = impactCenter;
                    geometry(pose, out, CarrierEffectGeometry.releaseRing(impact,
                        2.0 + 20.0 * pulse, .28 + .42 * pulse, state.column), 0xB8FAFF, (int)(210 * pulse));
                    geometry(pose, out, CarrierEffectGeometry.releaseRing(impact,
                        3.0 + 21.0 * pulse, .45 + .45 * pulse, state.column), 0xFF8D35, (int)(155 * pulse));
                    geometry(pose, out, CarrierEffectGeometry.shockCurtain(impact,
                        2.5 + 20.5 * pulse, 2.0 + 10.0 * pulse, state.column), 0xFFD6A0, (int)(105 * pulse));
                }
            });
        if (!state.scanEnds.isEmpty()) collector.submitCustomGeometry(poses,
            MorrowgearRenderTypes.visibleEnergyCore(), (pose, out) -> {
                for (int i = 0; i < state.scanEnds.size(); i++) {
                    Vec3 end = state.scanEnds.get(i);
                    Vec3 source = wingScanOrigin(state.beamOrigin, state.heading, i);
                    CombatEffectRenderer.emitBeamRibbon(pose, out, source, end, eye,
                        state.phase == CarrierPolicy.WorkPhase.CHARGE ? .08f : .045f,
                        state.combat ? 255 : 90, state.combat ? 170 : 235, state.combat ? 90 : 255, 210);
                    geometry(pose, out, CarrierEffectGeometry.disc(point(end).add(new Point(0,.04,0)),
                        .6,.48,state.column),color,190);
                }
            });
        double charge = switch (state.phase) {
            case CHARGE -> .15 + .85 * state.phaseProgress;
            case COOLDOWN -> .65 * (1 - state.phaseProgress);
            case FIRE -> 1;
            default -> 0;
        };
        if (state.mining && (state.phase == CarrierPolicy.WorkPhase.CHARGE
            || state.phase == CarrierPolicy.WorkPhase.FIRE)) {
            var visual = state.miningVisual;
            Point launch = start.add(new Point(0, -.28 - CarrierMiningEffectPolicy.CHARGE_DESCENT
                * visual.sphereTravel(), 0));
            Point sphere = state.phase == CarrierPolicy.WorkPhase.FIRE && state.miningTarget != null
                ? start.add(new Point(0, -.28 - CarrierMiningEffectPolicy.CHARGE_DESCENT, 0))
                    .lerp(point(state.miningTarget), visual.sphereTravel()) : launch;
            int sphereColor = mixColor(0xFF3518, 0xE8FFFF, visual.plasmaProgress());
            int emitterColor = mixColor(0xFF5A22, 0x8EEFFF, visual.plasmaProgress());
            collector.submitCustomGeometry(poses, MorrowgearRenderTypes.visibleEnergyCore(), (pose, out) -> {
                double emitterFade = state.phase == CarrierPolicy.WorkPhase.CHARGE ? 1 : 1 - visual.sphereTravel();
                for (int i = 0; i < visual.activeEmitters(); i++) {
                    double intensity = state.phase == CarrierPolicy.WorkPhase.CHARGE
                        ? CarrierMiningEffectPolicy.emitterIntensity(i, state.phaseTick) : emitterFade;
                    Point emitter = CarrierEffectGeometry.emitter(start, i, CarrierMiningEffectPolicy.RING_EMITTERS,
                        CarrierMiningEffectPolicy.RING_EMITTER_RADIUS);
                    geometry(pose, out, CarrierEffectGeometry.disc(emitter, .17, .09, null),
                        emitterColor, (int)(205 * emitterFade * intensity));
                    if (visual.sphereRadius() > 0)
                        CombatEffectRenderer.emitBeamRibbon(pose, out, vec(emitter), vec(sphere), eye,
                            (float)(.022 + .025 * intensity), emitterColor >> 16 & 255,
                            emitterColor >> 8 & 255, emitterColor & 255,
                            (int)(205 * Math.max(.25, emitterFade) * intensity));
                }
                if (visual.sphereRadius() > 0) {
                    geometry(pose, out, CarrierEffectGeometry.sphere(sphere, visual.sphereRadius()),
                        sphereColor, state.phase == CarrierPolicy.WorkPhase.CHARGE ? 218 : 242);
                    geometry(pose, out, CarrierEffectGeometry.sphere(sphere, visual.sphereRadius() * .58),
                        mixColor(0xFF9A58, 0xFFFFFF, visual.plasmaProgress()), 238);
                    if (state.phase == CarrierPolicy.WorkPhase.FIRE && visual.sphereTravel() >= 1) {
                        double thermalPulse = .5 + .5 * Math.sin(state.trailTime * .34);
                        geometry(pose, out, CarrierEffectGeometry.releaseRing(sphere,
                            1.45 + thermalPulse * .75, .16 + thermalPulse * .10, state.column),
                            0xFFB14A, 105 + (int)(75 * thermalPulse));
                        geometry(pose, out, CarrierEffectGeometry.shockCurtain(sphere,
                            1.2 + thermalPulse * .45, 1.3 + thermalPulse, state.column),
                            0xEAFDFF, 55 + (int)(55 * thermalPulse));
                    }
                }
            });
        }
        collector.submitCustomGeometry(poses, MorrowgearRenderTypes.condensedBeam(), (pose, out) -> {
            // This light sits inside the unchanged, recessed central optical aperture.
            geometry(pose, out, CarrierEffectGeometry.disc(start.add(new Point(0, .16, 0)), 1.12, .84, null), color, (int)(180 * charge));
            geometry(pose, out, CarrierEffectGeometry.disc(start.add(new Point(0, .15, 0)), .78 * charge, 0, null), color, (int)(200 * charge));
            for (Vec3 end : state.beamEnds) {
                geometry(pose, out, CarrierEffectGeometry.areaBeam(start, point(end), CarrierPolicy.COMBAT_IMPACT_RADIUS), color, 70);
                geometry(pose, out, CarrierEffectGeometry.areaBeam(start, point(end), CarrierPolicy.COMBAT_IMPACT_RADIUS * .7), color, 140);
                CombatEffectRenderer.emitCondensedAtmosphere(pose, out, vec(start), end, eye,
                    (float)state.trailTime, 1.45f, quality, true);
            }
        });
        collector.submitCustomGeometry(poses, MorrowgearRenderTypes.condensedCore(), (pose, out) -> {
            if (charge > .55) geometry(pose, out,
                CarrierEffectGeometry.disc(start.add(new Point(0, .14, 0)), .4 * charge, 0, null), 0xF6FFFF, (int)(200 * charge));
            for (Vec3 end : state.beamEnds) {
                geometry(pose, out, CarrierEffectGeometry.areaBeam(start, point(end), CarrierPolicy.COMBAT_IMPACT_RADIUS * .35), 0xF6FFFF, 235);
                CombatEffectRenderer.emitCondensedCore(pose, out, vec(start), end, eye,
                    (float)state.trailTime, .46f, quality, true);
                geometry(pose, out, CarrierEffectGeometry.disc(point(end), .5, .24, state.column), color, 205);
            }
            if (state.capturePosition != null && CarrierEffectGeometry.validRay(start, point(state.capturePosition), state.column)) {
                // A short, bounded upward sweep only follows a confirmed cargo commit, never a predicted drop.
                for (int i = 0; i < 3; i++) {
                    double t = Math.clamp(state.captureProgress - i * .12, 0, 1);
                    if (t <= 0 || t >= 1) continue;
                    Point center = point(state.capturePosition).scale(1 - t).add(start.scale(t));
                    geometry(pose, out, CarrierEffectGeometry.disc(center, .3 + .15 * t, .23 + .15 * t, state.column),
                        0x64EBFF, (int)(180 * (1 - t)));
                }
                Point impact = point(state.capturePosition).add(new Point(0, .2, 0));
                double pulse = Math.sin(Math.PI * Math.clamp(state.captureProgress, 0, 1));
                int matter = state.plasmaResponse == jp.morrowgear.drone.carrier.CarrierProtection.PlasmaResponse.ABLATE
                    ? 0xFF9A36 : 0xEAFDFF;
                geometry(pose, out, CarrierEffectGeometry.sphere(impact, .26 + .5 * pulse), matter,
                    (int)(210 * (1 - state.captureProgress)));
                geometry(pose, out, CarrierEffectGeometry.releaseRing(impact, .7 + 2.2 * state.captureProgress,
                    .15 + .12 * pulse, state.column), 0x74EFFF, (int)(165 * (1 - state.captureProgress)));
                if (state.plasmaResponse == jp.morrowgear.drone.carrier.CarrierProtection.PlasmaResponse.VAPORIZE)
                    geometry(pose, out, CarrierEffectGeometry.shockCurtain(impact, .55 + 1.7 * state.captureProgress,
                        .45 + 1.4 * pulse, state.column), 0xB8F8FF, (int)(105 * (1 - state.captureProgress)));
            }
        });
    }

    private static void renderHeavyMissileHatches(CarrierRenderState state,PoseStack poses,SubmitNodeCollector collector){
        if(state.missileHardpoints==0)return;
        double sequenceOpen=jp.morrowgear.drone.CarrierHeavyMissilePolicy.hatchOpen(
            state.missileLaunchTicks,state.missileSalvoSize);
        collector.submitCustomGeometry(poses,RenderTypes.entityCutout(TRAIL_TEXTURE),(pose,out)->{
            for(int slot=0;slot<CarrierExpansionPolicy.HARDPOINTS;slot++){
                if((state.missileHardpoints&1<<slot)==0)continue;
                var mount=CarrierCargoVisualPolicy.hardpoint(slot);
                double open=(state.missileSourceMask&1<<slot)!=0?sequenceOpen:0;
                double angle=Math.toRadians(jp.morrowgear.drone.CarrierHeavyMissilePolicy.HATCH_OPEN_ANGLE_DEGREES)*open;
                double w=jp.morrowgear.drone.CarrierHeavyMissilePolicy.HATCH_HALF_WIDTH;
                double z=jp.morrowgear.drone.CarrierHeavyMissilePolicy.HATCH_HALF_LENGTH;
                double y=jp.morrowgear.drone.CarrierHeavyMissilePolicy.HATCH_Y;
                geometry(pose,out,box(mount.x()-w+.12,y-.05,mount.z()-z+.12,
                    mount.x()+w-.12,y+.04,mount.z()+z-.12),0x11171A,255,state.lightCoords);
                geometry(pose,out,hingedHatch(mount.x(),mount.z(),-1,w,z,y,angle),
                    0x303B41,255,state.lightCoords);
                geometry(pose,out,hingedHatch(mount.x(),mount.z(),1,w,z,y,angle),
                    0x303B41,255,state.lightCoords);
                if(open>.08){
                    geometry(pose,out,missileBodies(slot),0x30383C,255,state.lightCoords);
                    geometry(pose,out,missileWarheads(slot),0xC6D0D4,255,state.lightCoords);
                }
            }
        });
    }

    private static List<Quad> hingedHatch(double cx,double cz,int side,double halfWidth,double halfLength,
                                          double hingeY,double angle){
        double gap=.05,hingeX=cx+side*halfWidth;
        double inner=side<0?cx-gap:cx+gap;
        double outer=hingeX;
        List<Quad> closed=box(Math.min(inner,outer),hingeY,cz-halfLength,
            Math.max(inner,outer),hingeY+.14,cz+halfLength);
        double signed=-side*angle;
        return closed.stream().map(q->new Quad(hinge(q.a(),hingeX,hingeY,signed),
            hinge(q.b(),hingeX,hingeY,signed),hinge(q.c(),hingeX,hingeY,signed),
            hinge(q.d(),hingeX,hingeY,signed))).toList();
    }

    private static Point hinge(Point p,double hingeX,double hingeY,double angle){
        double dx=p.x()-hingeX,dy=p.y()-hingeY;
        double cosine=Math.cos(angle),sine=Math.sin(angle);
        return new Point(hingeX+dx*cosine-dy*sine,hingeY+dx*sine+dy*cosine,p.z());
    }

    private void renderExpansionContainers(CarrierRenderState state, PoseStack poses,
                                           SubmitNodeCollector collector, boolean near) {
        int cargoMask=state.cargoHardpoints, missileMask=state.missileHardpoints;
        boolean moving=state.transferActive&&(state.transferPhase==CarrierContainerTransferPolicy.Phase.HOIST
            || state.transferPhase==CarrierContainerTransferPolicy.Phase.PURGE
            || state.transferPhase==CarrierContainerTransferPolicy.Phase.DEPLOY);
        if(moving){
            cargoMask&=~(1<<state.transferHardpoint); missileMask&=~(1<<state.transferHardpoint);
        }
        RuntimeMesh cargo=near?cargoContainer:cargoContainerFar;
        RuntimeMesh missiles=near?missileContainer:missileContainerFar;
        for(int slot=0;slot<CarrierExpansionPolicy.HARDPOINTS;slot++){
            var mount=CarrierCargoVisualPolicy.hardpoint(slot);
            boolean occupied=(cargoMask&1<<slot)!=0||(missileMask&1<<slot)!=0;
            renderExpansionBay(state,poses,collector,slot,occupied);
            if((cargoMask&1<<slot)!=0) submitContainer(cargo,poses,collector,state.lightCoords,mount.x(),mount.y(),mount.z());
            if((missileMask&1<<slot)!=0) submitContainer(missiles,poses,collector,state.lightCoords,mount.x(),mount.y(),mount.z());
        }
        if(!state.transferActive||state.transferHardpoint<0||state.transferGround==null)return;
        var mount=CarrierCargoVisualPolicy.hardpoint(state.transferHardpoint);
        Vec3 anchor=new Vec3(mount.x(),CarrierCargoVisualPolicy.WINCH_Y,mount.z());
        Vec3 ground=state.transferGround;
        Vec3 modelPosition=null;
        double eased=CarrierContainerTransferPolicy.eased(state.transferProgress);
        if(state.transferPhase==CarrierContainerTransferPolicy.Phase.HOIST)
            modelPosition=ground.lerp(new Vec3(mount.x(),mount.y(),mount.z()),eased);
        else if(state.transferPhase==CarrierContainerTransferPolicy.Phase.PURGE)
            modelPosition=new Vec3(mount.x(),mount.y(),mount.z()).add(0,
                -CarrierContainerTransferPolicy.PURGE_DROP*eased,0);
        else if(state.transferPhase==CarrierContainerTransferPolicy.Phase.DEPLOY)
            modelPosition=new Vec3(mount.x(),mount.y()-CarrierContainerTransferPolicy.PURGE_DROP,mount.z())
                .lerp(ground,eased);
        if(modelPosition!=null) submitContainer(state.transferType==CarrierExpansionPolicy.Type.CARGO?cargo:missiles,
            poses,collector,state.lightCoords,modelPosition.x,modelPosition.y,modelPosition.z);

        Vec3 cableEnd=transferCableEnd(state,anchor,ground,modelPosition,mount.y());
        if(cableEnd!=null) renderFourPointSling(state,poses,collector,anchor,cableEnd);
    }

    private static Vec3 transferCableEnd(CarrierRenderState state,Vec3 anchor,Vec3 ground,Vec3 moving,double mountY){
        Vec3 attachedTop=new Vec3(anchor.x,mountY+CarrierContainerTransferPolicy.CONTAINER_HEIGHT,anchor.z);
        Vec3 groundTop=ground.add(0,CarrierContainerTransferPolicy.CONTAINER_HEIGHT,0);
        return switch(state.transferPhase){
            case WINCH_DEPLOY -> anchor.lerp(groundTop,CarrierContainerTransferPolicy.eased(state.transferProgress));
            case CONNECT -> groundTop;
            case TENSION -> state.transferDirection==CarrierContainerTransferPolicy.Direction.LOAD?groundTop:attachedTop;
            case HOIST,PURGE,DEPLOY -> moving==null?null:moving.add(0,CarrierContainerTransferPolicy.CONTAINER_HEIGHT,0);
            case GROUND_CONTACT,RELEASE -> groundTop;
            case WINCH_RETRACT -> (state.transferDirection==CarrierContainerTransferPolicy.Direction.LOAD?attachedTop:groundTop)
                .lerp(anchor,CarrierContainerTransferPolicy.eased(state.transferProgress));
            default -> null;
        };
    }

    private static void renderFourPointSling(CarrierRenderState state,PoseStack poses,SubmitNodeCollector collector,
                                               Vec3 anchorCenter,Vec3 endCenter){
        for(int xSign:new int[]{-1,1})for(int zSign:new int[]{-1,1}){
            Vec3 offset=new Vec3(xSign*CarrierCargoVisualPolicy.CABLE_CORNER_X,0,
                zSign*CarrierCargoVisualPolicy.CABLE_CORNER_Z);
            Vec3 from=anchorCenter.add(offset),to=endCenter.add(offset);
            if(to.distanceToSqr(from)<=.0025)continue;
            List<Quad> strand=cable(from,to,.026);
            collector.submitCustomGeometry(poses,RenderTypes.entityCutout(TRAIL_TEXTURE),
                (pose,out)->geometry(pose,out,strand,0xA9B4B8,255,state.lightCoords));
            collector.submitCustomGeometry(poses,RenderTypes.entityTranslucentEmissive(TRAIL_TEXTURE),
                (pose,out)->geometry(pose,out,strand,0x74EFFF,52));
        }
    }

    private static void submitContainer(RuntimeMesh container,PoseStack poses,SubmitNodeCollector collector,
                                        int light,double x,double y,double z){
        poses.pushPose(); poses.translate(x,y,z);
        poses.mulPose(Axis.YP.rotationDegrees(CarrierCargoVisualPolicy.CONTAINER_YAW));
        container.submit(poses,collector,light,0,0,0,true,false,true,false);
        poses.popPose();
    }

    private static void renderExpansionBay(CarrierRenderState state,PoseStack poses,SubmitNodeCollector collector,
                                           int slot,boolean occupied){
        var mount=CarrierCargoVisualPolicy.hardpoint(slot);
        boolean selected=state.transferActive&&state.transferHardpoint==slot;
        double open=selected?hatchOpen(state.transferPhase,state.transferProgress):0;
        Point center=new Point(mount.x(),CarrierCargoVisualPolicy.COLLAR_TOP,mount.z());
        collector.submitCustomGeometry(poses,RenderTypes.entityCutout(TRAIL_TEXTURE),(pose,out)->{
            geometry(pose,out,bayCollar(center),occupied?0x232C31:0x151B1F,255,state.lightCoords);
            geometry(pose,out,bayRails(center),0x59666B,255,state.lightCoords);
            if(!occupied||selected) geometry(pose,out,bayHatches(center,open),0x2C363B,255,state.lightCoords);
            geometry(pose,out,winchDrums(center,selected),0x879399,255,state.lightCoords);
        });
        if(occupied&&!selected) collector.submitCustomGeometry(poses,
            RenderTypes.entityTranslucentEmissive(TRAIL_TEXTURE),(pose,out)->
                geometry(pose,out,bayLockLights(center),0x74EFFF,185));
    }

    private static double hatchOpen(CarrierContainerTransferPolicy.Phase phase,double progress){
        return switch(phase){
            case HATCH_OPEN -> CarrierContainerTransferPolicy.eased(progress);
            case HATCH_CLOSE -> 1-CarrierContainerTransferPolicy.eased(progress);
            case WINCH_DEPLOY,CONNECT,TENSION,HOIST,ATTACH,PURGE,DEPLOY,GROUND_CONTACT,RELEASE,WINCH_RETRACT -> 1;
            default -> 0;
        };
    }

    private static List<Quad> bayCollar(Point c){
        double x=CarrierCargoVisualPolicy.BAY_HALF_X,z=CarrierCargoVisualPolicy.BAY_HALF_Z;
        double y0=CarrierCargoVisualPolicy.COLLAR_BOTTOM,y1=CarrierCargoVisualPolicy.COLLAR_TOP;
        var out=new java.util.ArrayList<Quad>();
        out.addAll(box(c.x()-x-.18,y0,c.z()-z-.18,c.x()-x,y1,c.z()+z+.18));
        out.addAll(box(c.x()+x,y0,c.z()-z-.18,c.x()+x+.18,y1,c.z()+z+.18));
        out.addAll(box(c.x()-x,y0,c.z()-z-.18,c.x()+x,y1,c.z()-z));
        out.addAll(box(c.x()-x,y0,c.z()+z,c.x()+x,y1,c.z()+z+.18));
        return out;
    }

    private static List<Quad> bayRails(Point c){
        var out=new java.util.ArrayList<Quad>();
        for(int side:new int[]{-1,1}) out.addAll(box(c.x()+side*1.28-.07,-.82,c.z()-2.22,
            c.x()+side*1.28+.07,-.68,c.z()+2.22));
        return out;
    }

    private static List<Quad> bayHatches(Point c,double open){
        double slide=1.62*open,drop=.18*open;
        var out=new java.util.ArrayList<Quad>();
        out.addAll(box(c.x()-1.68-slide,-.25-drop,c.z()-2.58,c.x()-.04-slide,-.12-drop,c.z()+2.58));
        out.addAll(box(c.x()+.04+slide,-.25-drop,c.z()-2.58,c.x()+1.68+slide,-.12-drop,c.z()+2.58));
        return out;
    }

    private static List<Quad> winchDrums(Point c,boolean active){
        var out=new java.util.ArrayList<Quad>();
        for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1}){
            double x=c.x()+sx*CarrierCargoVisualPolicy.CABLE_CORNER_X;
            double z=c.z()+sz*CarrierCargoVisualPolicy.CABLE_CORNER_Z;
            out.addAll(box(x-.14,-.44,z-.20,x+.14,-.20,z+.20));
            if(active)out.addAll(box(x-.20,-.40,z-.08,x+.20,-.30,z+.08));
        }
        return out;
    }

    private static List<Quad> bayLockLights(Point c){
        double y=CarrierCargoVisualPolicy.COLLAR_BOTTOM-.01;
        return List.of(new Quad(new Point(c.x()-.72,y,c.z()-2.51),new Point(c.x()+.72,y,c.z()-2.51),
            new Point(c.x()+.72,y,c.z()-2.43),new Point(c.x()-.72,y,c.z()-2.43)));
    }

    private static List<Quad> missileBodies(int hardpoint){
        var out=new java.util.ArrayList<Quad>();
        for(int cell=0;cell<20;cell++){
            var tube=jp.morrowgear.drone.CarrierHeavyMissilePolicy.launchTube(hardpoint,cell);
            out.addAll(box(tube.x()-.055,tube.y()-.13,tube.z()-.055,
                tube.x()+.055,tube.y()+.17,tube.z()+.055));
        }
        return out;
    }

    private static List<Quad> missileWarheads(int hardpoint){
        var out=new java.util.ArrayList<Quad>();
        for(int cell=0;cell<20;cell++){
            var tube=jp.morrowgear.drone.CarrierHeavyMissilePolicy.launchTube(hardpoint,cell);
            double x=tube.x(),y=tube.y()+.17,z=tube.z(),r=.075;
            Point a=new Point(x-r,y,z-r),b=new Point(x+r,y,z-r);
            Point c=new Point(x+r,y,z+r),d=new Point(x-r,y,z+r),tip=new Point(x,y+.20,z);
            out.add(new Quad(a,b,tip,tip)); out.add(new Quad(b,c,tip,tip));
            out.add(new Quad(c,d,tip,tip)); out.add(new Quad(d,a,tip,tip));
        }
        return out;
    }

    private static List<Quad> box(double x0,double y0,double z0,double x1,double y1,double z1){
        Point a=new Point(x0,y0,z0),b=new Point(x1,y0,z0),c=new Point(x1,y0,z1),d=new Point(x0,y0,z1);
        Point e=new Point(x0,y1,z0),f=new Point(x1,y1,z0),g=new Point(x1,y1,z1),h=new Point(x0,y1,z1);
        return List.of(new Quad(a,d,c,b),new Quad(e,f,g,h),new Quad(a,b,f,e),new Quad(d,h,g,c),
            new Quad(a,e,h,d),new Quad(b,c,g,f));
    }

    private static double transferProgress(CarrierContainerTransferPolicy.Phase phase,int tick,float partial){
        int duration=switch(phase){
            case HATCH_OPEN,HATCH_CLOSE -> CarrierContainerTransferPolicy.HATCH_TICKS;
            case WINCH_DEPLOY,WINCH_RETRACT -> CarrierContainerTransferPolicy.WINCH_DEPLOY_TICKS;
            case CONNECT,TENSION,GROUND_CONTACT,RELEASE -> CarrierContainerTransferPolicy.CONNECT_TICKS;
            case PURGE -> CarrierContainerTransferPolicy.PURGE_TICKS;
            case HOIST,DEPLOY -> CarrierContainerTransferPolicy.HOIST_TICKS;
            case ALIGN -> 10;
            default -> 1;
        };
        return Math.clamp((tick+partial)/duration,0,1);
    }

    private static List<Quad> cable(Vec3 from,Vec3 to,double radius){
        Vec3 axis=to.subtract(from).normalize();
        Vec3 reference=Math.abs(axis.y)<.9?new Vec3(0,1,0):new Vec3(1,0,0);
        Vec3 side=axis.cross(reference).normalize().scale(radius);
        Vec3 up=axis.cross(side).normalize().scale(radius);
        Point a=point(from.add(side).add(up)),b=point(from.subtract(side).add(up));
        Point c=point(from.subtract(side).subtract(up)),d=point(from.add(side).subtract(up));
        Point e=point(to.add(side).add(up)),f=point(to.subtract(side).add(up));
        Point g=point(to.subtract(side).subtract(up)),h=point(to.add(side).subtract(up));
        return List.of(new Quad(a,b,f,e),new Quad(b,c,g,f),new Quad(c,d,h,g),new Quad(d,a,e,h));
    }

    private static Point point(Vec3 p) { return new Point(p.x, p.y, p.z); }
    private static Vec3 vec(Point p) { return new Vec3(p.x(), p.y(), p.z()); }
    private static int mixColor(int from, int to, double amount) {
        double t = Math.clamp(amount, 0, 1);
        int r = (int)Math.round((from >> 16 & 255) * (1 - t) + (to >> 16 & 255) * t);
        int g = (int)Math.round((from >> 8 & 255) * (1 - t) + (to >> 8 & 255) * t);
        int b = (int)Math.round((from & 255) * (1 - t) + (to & 255) * t);
        return r << 16 | g << 8 | b;
    }
    private static Vec3 wingScanOrigin(Vec3 center, float heading, int index) {
        double side = (index & 1) == 0 ? -8.6 : 8.6;
        double angle = Math.toRadians(heading), cosine = Math.cos(angle), sine = Math.sin(angle);
        return center.add(side * cosine + .8 * sine, -.65, -side * sine + .8 * cosine);
    }
    private static void geometry(PoseStack.Pose pose, VertexConsumer out, List<Quad> quads, int color, int alpha) {
        geometry(pose, out, quads, color, alpha, 0xF000F0);
    }
    private static void geometry(PoseStack.Pose pose, VertexConsumer out, List<Quad> quads, int color, int alpha, int light) {
        for (Quad q : quads) {
            Vec3 normal = vec(q.b()).subtract(vec(q.a())).cross(vec(q.c()).subtract(vec(q.a()))).normalize();
            vertex(pose, out, q.a(), normal, color, alpha, light, 0, 0);
            vertex(pose, out, q.b(), normal, color, alpha, light, 0, 1);
            vertex(pose, out, q.c(), normal, color, alpha, light, 1, 1);
            vertex(pose, out, q.d(), normal, color, alpha, light, 1, 0);
        }
    }
    private static void vertex(PoseStack.Pose pose, VertexConsumer out, Point p, Vec3 normal, int color, int alpha,
                               int light, float u, float v) {
        out.addVertex(pose, (float)p.x(), (float)p.y(), (float)p.z()).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, alpha)
            .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
            .setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
    }

    private static List<Quad> cargoPod(Point c, double side) {
        double inner = c.x() - side * 1.25, outer = c.x() + side * 1.25;
        double x0 = Math.min(inner, outer), x1 = Math.max(inner, outer);
        double z0 = c.z() - 1.16, z1 = c.z() + 1.16;
        double bevel = .36, bottom = c.y() - .82, top = c.y() + .18;
        Point a = new Point(x0 + bevel, bottom, z0), b = new Point(x1 - bevel, bottom, z0);
        Point d = new Point(x0 + bevel, bottom, z1), e = new Point(x1 - bevel, bottom, z1);
        Point at = new Point(x0, top, z0 + bevel), bt = new Point(x1, top, z0 + bevel);
        Point dt = new Point(x0, top, z1 - bevel), et = new Point(x1, top, z1 - bevel);
        return List.of(new Quad(a,b,e,d), new Quad(at,dt,et,bt), new Quad(a,at,bt,b),
            new Quad(d,e,et,dt), new Quad(a,d,dt,at), new Quad(b,bt,et,e));
    }
    private static List<Quad> cargoPodArmor(Point c) {
        double x0 = c.x() - 1.04, x1 = c.x() + 1.04, z0 = c.z() - .82, z1 = c.z() + .82, y = c.y() + .195;
        return List.of(new Quad(new Point(x0,y,z0), new Point(x0,y,z1), new Point(x1,y,z1), new Point(x1,y,z0)));
    }
    private static List<Quad> cargoPodSignal(Point c, double side) {
        double outer = c.x() + side * 1.265, y0 = c.y() - .42, y1 = c.y() - .26;
        return List.of(new Quad(new Point(outer,y0,c.z()-.62), new Point(outer,y1,c.z()-.62),
            new Point(outer,y1,c.z()+.62), new Point(outer,y0,c.z()+.62)));
    }
}
