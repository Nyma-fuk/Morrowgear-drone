package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import jp.morrowgear.drone.BeamVisibilityPolicy;
import jp.morrowgear.drone.CombatState;
import jp.morrowgear.drone.DroneHardpoints;
import jp.morrowgear.drone.EffectReadabilityPolicy;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.MorrowgearRenderTypes;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class CombatEffectRenderer {
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final Identifier WHITE_TEXTURE = Identifier.fromNamespaceAndPath(
		MorrowgearDrone.MOD_ID, "textures/entity/emissive_white.png");

	private CombatEffectRenderer() {}

	static void render(DroneRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
		Vec3 cameraOffset) {
		boolean gunVisible = state.gunShotAge <= 3 && state.gunTargetOffset != null;
		boolean laserVisible = state.combatState == CombatState.LASER_FIRE
			&& state.combatTargetOffset != null;
		if (!state.combatState.active() && !gunVisible) return;
		Vec3 forward = rotated(state, new Vec3(0, 0, 1));
		Vec3 right = rotated(state, new Vec3(1, 0, 0));
		Vec3 gunOutlet = rotated(state, ((int)(state.ageInTicks - state.gunShotAge) & 1) == 0
			? DroneHardpoints.GUN_LEFT : DroneHardpoints.GUN_RIGHT);
		Vec3 laserOutlet = rotated(state, DroneHardpoints.LASER);
		if (state.combatState == CombatState.LASER_FIRE && state.combatTargetOffset != null) {
			Minecraft client = Minecraft.getInstance();
			if (client.level != null && client.player != null) {
				Vec3 origin = new Vec3(state.x, state.y, state.z);
				HitResult hit = client.level.clip(new ClipContext(origin.add(laserOutlet),
					origin.add(state.combatTargetOffset), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
				if (hit.getType() != HitResult.Type.MISS) state.combatTargetOffset = hit.getLocation().subtract(origin);
			}
		}
		double pixels = EffectProjection.pixelsPerBlock(new Vec3(state.x, state.y, state.z));
		RuntimeGeometryBudgetPolicy.Detail beamDetail = RuntimeGeometryBudgetController.drone(
			state.entityId, new Vec3(state.x, state.y, state.z).add(cameraOffset));
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
			(pose, consumer) -> {
				if (state.combatState == CombatState.FLARE_ENTRY) renderEntryPulse(
					pose, consumer, laserOutlet, forward, state.combatStateAge);
				if (state.combatState == CombatState.LASER_CHARGE) {
					renderCharge(pose, consumer, laserOutlet, forward, right,
						state.combatCharge / 1000.0, state.ageInTicks, pixels);
				}
				if (state.combatState == CombatState.LASER_FIRE && state.combatTargetOffset != null) {
					renderLaserImpact(pose, consumer, laserOutlet,
						state.combatTargetOffset, state.ageInTicks);
				}
			});
		if (gunVisible) {
			collector.submitCustomGeometry(poseStack, MorrowgearRenderTypes.energyBeam(),
				(pose, consumer) -> renderAutocannonTracer(pose, consumer, gunOutlet,
					state.gunTargetOffset, state.gunShotAge, cameraOffset));
			jp.morrowgear.drone.CombatPolicy.TracerSegment tracer =
				jp.morrowgear.drone.CombatPolicy.tracerSegment(gunOutlet,
					state.gunTargetOffset, state.gunShotAge);
			List<BeamSpan> visibleSpans = visibleBeamSpans(state, tracer.start(),
				tracer.end(), cameraOffset);
			if (!visibleSpans.isEmpty()) {
				collector.submitCustomGeometry(poseStack, MorrowgearRenderTypes.visibleEnergyCore(),
					(pose, consumer) -> renderVisibleTracer(pose, consumer, visibleSpans, cameraOffset));
			}
		}
		if (laserVisible) {
			collector.submitCustomGeometry(poseStack, MorrowgearRenderTypes.condensedBeam(),
				(pose, consumer) -> renderHighOutputLaser(pose, consumer, laserOutlet,
					state.combatTargetOffset, state.ageInTicks, cameraOffset, pixels, beamDetail));
			List<BeamSpan> visibleSpans = visibleBeamSpans(state, laserOutlet,
				state.combatTargetOffset, cameraOffset);
			if (!visibleSpans.isEmpty()) {
				collector.submitCustomGeometry(poseStack, MorrowgearRenderTypes.condensedCore(),
					(pose, consumer) -> renderVisibleLaser(pose, consumer, visibleSpans,
						state.ageInTicks, cameraOffset, pixels, beamDetail));
			}
		}
	}

	private static List<BeamSpan> visibleBeamSpans(DroneRenderState state, Vec3 outlet,
		Vec3 target, Vec3 cameraOffset) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) return List.of();
		Vec3 origin = new Vec3(state.x, state.y, state.z);
		Vec3 start = origin.add(outlet);
		Vec3 end = origin.add(target);
		Vec3 delta = end.subtract(start);
		double length = delta.length();
		if (length < 0.001) return List.of();
		int steps = Math.max(1, Math.min(96, (int)Math.ceil(length / 0.38)));
		return BeamVisibilityPolicy.visibleRanges(steps, step -> {
			double progress = step / (double)steps;
			return cameraCanSee(minecraft, origin.add(cameraOffset), start.add(delta.scale(progress)));
		}).stream().map(range -> new BeamSpan(
			outlet.add(target.subtract(outlet).scale(range.start())),
			outlet.add(target.subtract(outlet).scale(range.end())))).toList();
	}

	private static boolean cameraCanSee(Minecraft minecraft, Vec3 camera, Vec3 sample) {
		HitResult hit = minecraft.level.clip(new ClipContext(camera, sample,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
		return hit.getType() == HitResult.Type.MISS
			|| hit.getLocation().distanceToSqr(camera) + 0.12 >= sample.distanceToSqr(camera);
	}

	private static void renderVisibleLaser(PoseStack.Pose pose, VertexConsumer consumer,
		List<BeamSpan> spans, float age, Vec3 cameraOffset, double pixels,
		RuntimeGeometryBudgetPolicy.Detail detail) {
		for (BeamSpan span : spans) {
			emitCondensedCore(pose, consumer, span.start(), span.end(), cameraOffset, age,
				EffectReadabilityPolicy.width(0.058f, pixels, 1.1f, 2.0f), detail, false);
		}
	}

	private static void renderVisibleTracer(PoseStack.Pose pose, VertexConsumer consumer,
		List<BeamSpan> spans, Vec3 cameraOffset) {
		for (BeamSpan span : spans) {
			emitBeamRibbon(pose, consumer, span.start(), span.end(), cameraOffset,
				0.11f, 255, 87, 8, 235);
			emitBeamRibbon(pose, consumer, span.start(), span.end(), cameraOffset,
				0.034f, 255, 248, 218, 255);
		}
	}

	static void renderChargingBeam(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 start, Vec3 end, float age, Vec3 cameraOffset, boolean visibleCore) {
		double pulse = .5 + Math.sin(age * .34) * .5;
		if (visibleCore) {
			emitBeamRibbon(pose, consumer, start, end, cameraOffset,
				(float)(.105 + pulse * .018), 20, 205, 255, 210);
			emitBeamRibbon(pose, consumer, start, end, cameraOffset,
				.038f, 235, 255, 255, 255);
			return;
		}
		emitBeamRibbon(pose, consumer, start, end, cameraOffset,
			(float)(.30 + pulse * .05), 0, 118, 255, 48);
		emitBeamRibbon(pose, consumer, start, end, cameraOffset,
			(float)(.19 + pulse * .025), 0, 224, 255, 138);
		emitBeamRibbon(pose, consumer, start, end, cameraOffset,
			.075f, 184, 252, 255, 248);
	}

	private static void renderEntryPulse(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 outlet, Vec3 forward, long age) {
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		double pulse = 0.88 + Math.sin(age * 0.55) * 0.12;
		Vec3 lens = outlet.add(0, -0.012, 0);
		emitDisc(pose, consumer, lens, right, forward, 0.15 * pulse,
			255, 35, 5, 150);
		emitDisc(pose, consumer, lens.add(0, -0.006, 0), right, forward,
			0.065 * pulse, 255, 212, 120, 235);
		emitRing(pose, consumer, lens, right, forward, 0.19 * pulse,
			0.018f, 255, 72, 8, 180);
	}

	private static void renderAutocannonTracer(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 muzzle, Vec3 aim, int age, Vec3 cameraOffset) {
		jp.morrowgear.drone.CombatPolicy.TracerSegment segment =
			jp.morrowgear.drone.CombatPolicy.tracerSegment(muzzle, aim, age);
		Vec3 travel = segment.end().subtract(segment.start());
		Vec3 direction = travel.lengthSqr() < 0.0001 ? aim.subtract(muzzle).normalize() : travel.normalize();
		Vec3 right = new Vec3(-direction.z, 0, direction.x);
		if (right.lengthSqr() < 0.001) right = new Vec3(1, 0, 0);
		right = right.normalize();
		for (int round = 0; round < 3; round++) {
			double longitudinal = round * 0.21;
			double lateral = (round - 1) * 0.018;
			Vec3 start = segment.start().subtract(direction.scale(longitudinal)).add(right.scale(lateral));
			Vec3 end = segment.end().subtract(direction.scale(longitudinal)).add(right.scale(lateral));
			emitBeamRibbon(pose, consumer, start, end, cameraOffset,
				0.072f, 255, 82, 5, 220);
			emitBeamRibbon(pose, consumer, start, end, cameraOffset,
				0.022f, 255, 250, 222, 255);
		}
		double flash = Math.max(0.0, 1.0 - age * 0.28);
		if (flash > 0.0) {
			Vec3 vertical = new Vec3(0, 1, 0);
			emitDisc(pose, consumer, muzzle.add(direction.scale(0.04)), right, vertical,
				0.12 + flash * 0.10, 255, 224, 145, (int)(120 + flash * 120));
		}
	}

	private static void renderCharge(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 outlet, Vec3 forward, Vec3 right, double charge, float age, double pixels) {
		charge = Math.clamp(charge, 0, 1);
		double pulse = 0.98 + Math.sin(age * 0.18) * 0.02;
		Vec3 normal = right.cross(forward);
		Vec3 lens = outlet.add(normal.scale(.003));
		emitDisc(pose, consumer, lens, right, forward, (0.067 + charge * 0.018) * pulse,
			255, 20, 3, (int)(72 + charge * 78));
		emitDisc(pose, consumer, lens.add(normal.scale(.002)), right, forward,
			(0.036 + charge * 0.018) * pulse, 255, (int)(66 + charge * 116), 12,
			(int)(125 + charge * 105));
		emitDisc(pose, consumer, lens.add(normal.scale(.004)), right, forward,
			EffectReadabilityPolicy.width((float)(0.012 + charge * 0.020), pixels, 1.1f, 1.6f)
				* pulse, 255, 252, 224, (int)(70 + charge * 185));
		emitRing(pose, consumer, lens, right, forward, .09 * pulse,
			(float)(0.008 + charge * 0.005), 255, 78, 8,
			(int)((145 + charge * 90) * EffectReadabilityPolicy.detail(pixels)));
	}

	private static Vec3 rotated(DroneRenderState state, Vec3 local) {
		return DroneHardpoints.rotate(local, state.heading, state.flightPitch, state.flightRoll);
	}

	private static void renderHighOutputLaser(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 outlet, Vec3 target, float age, Vec3 cameraOffset, double pixels,
		RuntimeGeometryBudgetPolicy.Detail detail) {
		emitCondensedAtmosphere(pose, consumer, outlet, target, cameraOffset, age,
			EffectReadabilityPolicy.width(0.58f, pixels, 1.1f, 1.35f), detail, false);
	}

	private static void renderLaserImpact(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 outlet, Vec3 target, float age) {
		Vec3 delta = target.subtract(outlet).normalize();
		Vec3 side = new Vec3(-delta.z, 0, delta.x);
		if (side.lengthSqr() < 0.001) side = new Vec3(1, 0, 0);
		side = side.normalize();
		Vec3 vertical = delta.cross(side).normalize();
		renderImpact(pose, consumer, target, side, vertical, age);
	}

	private static void renderImpact(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 target, Vec3 side, Vec3 vertical, float age) {
		double pulse = 0.96 + Math.sin(age * 0.9) * 0.06;
		emitDisc(pose, consumer, target, side, vertical, 0.13 * pulse,
			255, 252, 232, 245);
		emitRing(pose, consumer, target, side, vertical, 0.22 * pulse,
			0.026f, 255, 94, 12, 185);
		emitRing(pose, consumer, target, side, vertical,
			.31 + .06 * Math.sin(age * .31), .018f, 105, 224, 255, 125);
		for (int i = 0; i < 3; i++) {
			double rise = ((age * .028 + i / 3.0) % 1.0);
			Vec3 vapor = target.add(vertical.scale(.05 + rise * .48))
				.add(side.scale(Math.sin(age * .12 + i * 2.1) * .07));
			emitDisc(pose, consumer, vapor, side, vertical,
				.055 + rise * .085, 205, 238, 242, (int)(62 * (1 - rise)));
		}
	}

	static void emitCondensedAtmosphere(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 start, Vec3 end, Vec3 cameraOffset, float age, float scale,
		RuntimeGeometryBudgetPolicy.Detail detail, boolean carrier) {
		var profile = HighEnergyBeamPolicy.profile(detail, carrier);
		double pressure = .96 + Math.sin(age * .43) * .025;
		emitBeamRibbon(pose, consumer, start, end, cameraOffset,
			(float)(scale * profile.hazeScale() * pressure), 88, 198, 255, carrier ? 34 : 27);
		emitBeamRibbon(pose, consumer, start, end, cameraOffset,
			(float)(scale * .58 * pressure), 255, 27, 5, carrier ? 112 : 88);
		emitBeamRibbon(pose, consumer, start, end, cameraOffset,
			(float)(scale * .27), 255, 165, 42, 212);
	}

	static void emitCondensedCore(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 start, Vec3 end, Vec3 cameraOffset, float age, float coreWidth,
		RuntimeGeometryBudgetPolicy.Detail detail, boolean carrier) {
		Vec3 delta = end.subtract(start);
		double length = delta.length();
		if (length < .001) return;
		var profile = HighEnergyBeamPolicy.profile(detail, carrier);
		float scale = carrier ? Math.max(coreWidth, .34f) : coreWidth;
		emitBeamRibbon(pose, consumer, start, end, cameraOffset,
			scale * 2.55f, 255, 142, 28, 230);
		emitBeamRibbon(pose, consumer, start, end, cameraOffset,
			scale, 255, 255, 248, 255);
		Vec3 direction = delta.scale(1.0 / length);
		Vec3[] basis = beamBasis(direction);
		for (int packet = 0; packet < profile.compressionPackets(); packet++) {
			double progress = HighEnergyBeamPolicy.packetProgress(age, packet,
				profile.compressionPackets(), profile.packetSpeed());
			Vec3 center = start.add(delta.scale(progress));
			double envelope = Math.sin(Math.PI * progress);
			// Compression fronts emerge inside the lens, expand through the ionized air,
			// then collapse into the impact point instead of clipping through the hull.
			double radius = scale * (carrier ? 5.4 : 3.7) * (.18 + .82 * envelope);
			emitRing(pose, consumer, center, basis[0], basis[1], radius,
				scale * (carrier ? .34f : .26f), 192, 244, 255,
				(int)((carrier ? 210 : 175) * (.16 + .84 * envelope)));
		}
		for (int filament = 0; filament < profile.filaments(); filament++)
			emitPlasmaFilament(pose, consumer, start, end, cameraOffset, age,
				scale * (carrier ? 3.6 : 2.65), scale * .34f,
				profile.filamentSegments(), filament, profile.filaments());
	}

	private static void emitPlasmaFilament(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 start, Vec3 end, Vec3 cameraOffset, float age, double radius, float width,
		int segments, int filament, int filamentCount) {
		if (segments <= 0) return;
		Vec3 delta = end.subtract(start), direction = delta.normalize();
		Vec3[] basis = beamBasis(direction);
		Vec3 previous = start;
		for (int segment = 1; segment <= segments; segment++) {
			double t = segment / (double)segments;
			double envelope = Math.sin(Math.PI * t);
			double phase = t * Math.PI * 8 - age * .42
				+ filament * Math.PI * 2 / Math.max(1, filamentCount);
			Vec3 next = start.add(delta.scale(t)).add(basis[0].scale(Math.cos(phase) * radius * envelope))
				.add(basis[1].scale(Math.sin(phase) * radius * envelope));
			emitBeamRibbon(pose, consumer, previous, next, cameraOffset, width,
				105, 225, 255, 155);
			previous = next;
		}
	}

	private static Vec3[] beamBasis(Vec3 direction) {
		Vec3 axisA = direction.cross(new Vec3(0, 1, 0));
		if (axisA.lengthSqr() < .000001) axisA = direction.cross(new Vec3(1, 0, 0));
		axisA = axisA.normalize();
		return new Vec3[]{axisA, direction.cross(axisA).normalize()};
	}

	private static void emitRing(PoseStack.Pose pose, VertexConsumer consumer, Vec3 center,
		Vec3 axisA, Vec3 axisB, double radius, float width,
		int red, int green, int blue, int alpha) {
		Vec3 previous = center.add(axisA.scale(radius));
		for (int segment = 1; segment <= 16; segment++) {
			double phase = segment * Math.PI * 2.0 / 16.0;
			Vec3 next = center.add(axisA.scale(Math.cos(phase) * radius))
				.add(axisB.scale(Math.sin(phase) * radius));
			emitBeam(pose, consumer, previous, next, width, red, green, blue, alpha);
			previous = next;
		}
	}

	private static void emitDisc(PoseStack.Pose pose, VertexConsumer consumer, Vec3 center,
		Vec3 axisA, Vec3 axisB, double radius, int red, int green, int blue, int alpha) {
		for (int segment = 0; segment < 20; segment++) {
			double phase0 = segment * Math.PI * 2.0 / 20.0;
			double phase1 = (segment + 1) * Math.PI * 2.0 / 20.0;
			Vec3 edge0 = center.add(axisA.scale(Math.cos(phase0) * radius))
				.add(axisB.scale(Math.sin(phase0) * radius));
			Vec3 edge1 = center.add(axisA.scale(Math.cos(phase1) * radius))
				.add(axisB.scale(Math.sin(phase1) * radius));
			emitQuad(pose, consumer, center, edge0, edge1, center, red, green, blue, alpha);
			emitQuad(pose, consumer, center, edge1, edge0, center, red, green, blue, alpha);
		}
	}

	private static Vec3 forward(float yawDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		return new Vec3(Math.sin(yaw), 0, -Math.cos(yaw));
	}

	static void emitBeam(PoseStack.Pose pose, VertexConsumer consumer, Vec3 start, Vec3 end,
		float width, int red, int green, int blue, int alpha) {
		Vec3 delta = end.subtract(start);
		if (delta.lengthSqr() < 0.000001) return;
		Vec3 horizontal = new Vec3(-delta.z, 0, delta.x);
		if (horizontal.lengthSqr() < 0.000001) horizontal = new Vec3(1, 0, 0);
		horizontal = horizontal.normalize().scale(width * 0.5);
		Vec3 vertical = new Vec3(0, width * 0.5, 0);
		emitQuad(pose, consumer, start.subtract(vertical), start.add(vertical),
			end.add(vertical), end.subtract(vertical), red, green, blue, alpha);
		emitQuad(pose, consumer, start.subtract(horizontal), start.add(horizontal),
			end.add(horizontal), end.subtract(horizontal), red, green, blue, alpha);
	}

	static void emitBeamRibbon(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 start, Vec3 end, Vec3 cameraOffset, float width,
		int red, int green, int blue, int alpha) {
		Vec3 delta = end.subtract(start);
		if (delta.lengthSqr() < 0.000001) return;
		Vec3 toCamera = cameraOffset.subtract(start.add(end).scale(0.5));
		Vec3 side = delta.cross(toCamera);
		if (side.lengthSqr() < 0.000001) side = delta.cross(new Vec3(0, 1, 0));
		if (side.lengthSqr() < 0.000001) side = new Vec3(1, 0, 0);
		side = side.normalize().scale(width * 0.5);
		emit(pose, consumer, start.subtract(side), red, green, blue, alpha, 0, 0);
		emit(pose, consumer, start.add(side), red, green, blue, alpha, 0, 1);
		emit(pose, consumer, end.add(side), red, green, blue, alpha, 1, 1);
		emit(pose, consumer, end.subtract(side), red, green, blue, alpha, 1, 0);
	}

	private static void emitQuad(PoseStack.Pose pose, VertexConsumer consumer,
		Vec3 a, Vec3 b, Vec3 c, Vec3 d, int red, int green, int blue, int alpha) {
		emit(pose, consumer, a, red, green, blue, alpha, 0, 0);
		emit(pose, consumer, b, red, green, blue, alpha, 0, 1);
		emit(pose, consumer, c, red, green, blue, alpha, 1, 1);
		emit(pose, consumer, d, red, green, blue, alpha, 1, 0);
	}

	private static void emit(PoseStack.Pose pose, VertexConsumer consumer, Vec3 point,
		int red, int green, int blue, int alpha, float u, float v) {
		consumer.addVertex(pose, (float)point.x, (float)point.y, (float)point.z)
			.setColor(red, green, blue, alpha).setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT)
			.setNormal(pose, 0, 1, 0);
	}

	private record BeamSpan(Vec3 start, Vec3 end) {}
}
