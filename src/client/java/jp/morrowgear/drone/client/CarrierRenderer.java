package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;
import jp.morrowgear.drone.carrier.CarrierTrailPolicy;
import jp.morrowgear.drone.carrier.CarrierEntity;
import jp.morrowgear.drone.carrier.CarrierPolicy;
import jp.morrowgear.drone.carrier.CarrierMiningEffectPolicy;
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
            }
        });
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
        for (Quad q : quads) {
            Vec3 normal = vec(q.b()).subtract(vec(q.a())).cross(vec(q.c()).subtract(vec(q.a()))).normalize();
            vertex(pose, out, q.a(), normal, color, alpha, 0, 0);
            vertex(pose, out, q.b(), normal, color, alpha, 0, 1);
            vertex(pose, out, q.c(), normal, color, alpha, 1, 1);
            vertex(pose, out, q.d(), normal, color, alpha, 1, 0);
        }
    }
    private static void vertex(PoseStack.Pose pose, VertexConsumer out, Point p, Vec3 normal, int color, int alpha, float u, float v) {
        out.addVertex(pose, (float)p.x(), (float)p.y(), (float)p.z()).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, alpha)
            .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0)
            .setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
    }
}
