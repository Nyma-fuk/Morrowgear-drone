package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;
import jp.morrowgear.drone.carrier.CarrierTrailPolicy;
import jp.morrowgear.drone.carrier.CarrierEntity;
import jp.morrowgear.drone.carrier.CarrierPolicy;
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
        if (entity.workMode() == CarrierPolicy.Mode.MINING && entity.workPhase() != CarrierPolicy.WorkPhase.IDLE)
            hull = hull.minmax(new AABB(Vec3.atLowerCornerOf(entity.operationMin()),
                Vec3.atLowerCornerOf(entity.operationMax().offset(1,1,1))));
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
        Vec3 origin = new Vec3(state.x, state.y, state.z);
        state.beamOrigin = entity.beamOrigin().subtract(origin);
        var timing = timeline.observe(entity.getUUID(), entity.sampleTick(), entity.captureSequence(), entity.capturedItems(),
            entity.tickCount + partialTick, point(Vec3.atCenterOf(entity.lastCapturePosition())));
        state.phase = timing.fresh() ? entity.workPhase() : CarrierPolicy.WorkPhase.IDLE;
        state.phaseProgress = CarrierEffectGeometry.progress(entity.phaseTick(), entity.phaseDuration(), timing.interpolation());
        var min = entity.operationMin(); var max = entity.operationMax();
        state.column = state.combat ? null : new CarrierEffectGeometry.Column(min.getX() - origin.x, max.getX() + 1 - origin.x,
            min.getZ() - origin.z, max.getZ() + 1 - origin.z);
        state.beamEnds = state.phase == CarrierPolicy.WorkPhase.FIRE && entity.beamActive()
            ? entity.beamPaths().stream().limit(1)
                .map(path -> path.toRenderLocal(origin)).filter(path -> CarrierEffectGeometry.validRay(
                    point(path.origin()), point(path.target()), state.column)).map(jp.morrowgear.drone.carrier.CarrierBeamPath.Local::target).toList()
            : List.of();
        state.scanEnds = (state.phase == CarrierPolicy.WorkPhase.SCAN || state.phase == CarrierPolicy.WorkPhase.CHARGE)
            && entity.beamActive() ? entity.beamPaths().stream().limit(CarrierEffectGeometry.MAX_BEAMS)
                .map(path -> path.toRenderLocal(origin)).filter(path -> CarrierEffectGeometry.validRay(
                    point(path.origin()), point(path.target()), state.column))
                .map(jp.morrowgear.drone.carrier.CarrierBeamPath.Local::target).toList() : List.of();
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
        if (!state.scanEnds.isEmpty()) collector.submitCustomGeometry(poses,
            MorrowgearRenderTypes.visibleEnergyCore(), (pose, out) -> {
                for (int i = 0; i < state.scanEnds.size(); i++) {
                    Vec3 end = state.scanEnds.get(i);
                    CombatEffectRenderer.emitBeamRibbon(pose, out, state.beamOrigin, end, eye,
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
        collector.submitCustomGeometry(poses, MorrowgearRenderTypes.energyBeam(), (pose, out) -> {
            // This light sits inside the unchanged, recessed central optical aperture.
            geometry(pose, out, CarrierEffectGeometry.disc(start.add(new Point(0, .16, 0)), 1.12, .84, null), color, (int)(180 * charge));
            geometry(pose, out, CarrierEffectGeometry.disc(start.add(new Point(0, .15, 0)), .78 * charge, 0, null), color, (int)(200 * charge));
            for (Vec3 end : state.beamEnds) {
                if (!state.combat) {
                    geometry(pose, out, CarrierEffectGeometry.workColumn(start,end.y,state.column,.03),color,90);
                    geometry(pose, out, CarrierEffectGeometry.workColumn(start,end.y,state.column,.35),color,150);
                    continue;
                }
                geometry(pose, out, CarrierEffectGeometry.areaBeam(start, point(end), CarrierPolicy.COMBAT_IMPACT_RADIUS), color, 70);
                geometry(pose, out, CarrierEffectGeometry.areaBeam(start, point(end), CarrierPolicy.COMBAT_IMPACT_RADIUS * .7), color, 140);
            }
        });
        collector.submitCustomGeometry(poses, MorrowgearRenderTypes.visibleEnergyCore(), (pose, out) -> {
            if (charge > .55) geometry(pose, out,
                CarrierEffectGeometry.disc(start.add(new Point(0, .14, 0)), .4 * charge, 0, null), 0xF6FFFF, (int)(200 * charge));
            for (Vec3 end : state.beamEnds) {
                if (!state.combat) {
                    geometry(pose, out, CarrierEffectGeometry.workColumn(start,end.y,state.column,.8),0xDBFFFF,190);
                    continue;
                }
                geometry(pose, out, CarrierEffectGeometry.areaBeam(start, point(end), CarrierPolicy.COMBAT_IMPACT_RADIUS * .35), 0xF6FFFF, 235);
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
