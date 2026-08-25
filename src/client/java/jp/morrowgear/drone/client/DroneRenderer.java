package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.DroneRole;
import jp.morrowgear.drone.FlightAttitude;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class DroneRenderer extends EntityRenderer<DroneEntity, DroneRenderState> {
	private static final Identifier WHITE_TEXTURE = Identifier.fromNamespaceAndPath(
		MorrowgearDrone.MOD_ID, "textures/entity/emissive_white.png");
	private final Map<DroneRole, DroneMesh> meshes = new EnumMap<>(DroneRole.class);

	public DroneRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.72f;
		ResourceManager resources = context.getResourceManager();
		load(resources, DroneRole.FIELD, "field");
		load(resources, DroneRole.CARGO, "field");
		load(resources, DroneRole.SCOUT, "scout");
		load(resources, DroneRole.ENGINEER, "engineer");
		load(resources, DroneRole.SECURITY, "guard");
		load(resources, DroneRole.SALVAGE, "salvage");
	}

	private void load(ResourceManager resources, DroneRole role, String name) {
		Identifier id = Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "models/entity/family_a_" + name + ".mgm");
		try {
			meshes.put(role, DroneMesh.read(resources.getResourceOrThrow(id).open()));
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot load approved Morrowgear drone mesh " + id, exception);
		}
	}

	@Override
	public DroneRenderState createRenderState() {
		return new DroneRenderState();
	}

	@Override
	public void extractRenderState(DroneEntity entity, DroneRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.docked = entity.isDocked();
		state.powerLost = entity.isPowerLost();
		state.entityId = entity.getUUID();
		state.role = entity.role();
		state.combatState = entity.combatState();
		state.combatWeapon = entity.combatWeapon();
		state.combatCharge = entity.combatCharge();
		state.combatShotAge = entity.combatShotAge();
		state.combatStateAge = Math.max(0L, entity.level().getGameTime() - entity.combatStateTick());
		state.salvageState = entity.salvageState();
		net.minecraft.world.entity.Entity salvageTarget = entity.level().getEntity(entity.salvageTargetEntityId());
		state.salvageTargetOffset = salvageTarget == null ? null
			: salvageTarget.position().add(0, salvageTarget.getBbHeight(), 0).subtract(entity.position());
		net.minecraft.world.entity.Entity combatTarget = entity.level().getEntity(entity.combatTargetId());
		state.combatTargetOffset = combatTarget == null ? null
			: (state.combatState == jp.morrowgear.drone.CombatState.GUN_RUN
				&& state.combatShotAge <= 4)
				|| (state.combatState == jp.morrowgear.drone.CombatState.LASER_FIRE
					&& entity.hasValidCombatVisualAim())
				? entity.combatVisualAim()
				: state.combatState == jp.morrowgear.drone.CombatState.LASER_FIRE
					? null
					: combatTarget.position().add(0, combatTarget.getBbHeight() * 0.58, 0)
						.subtract(entity.position());
		state.heading = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
		if (state.docked) {
			state.flightPitch = 0;
			state.flightRoll = 0;
			return;
		}
		Vec3 velocity = entity.getDeltaMovement();
		state.flightPitch = FlightAttitude.pitch(velocity, entity.getYRot());
		state.flightRoll = FlightAttitude.roll(velocity, entity.getYRot());
		if ((state.combatState == jp.morrowgear.drone.CombatState.LASER_CHARGE
			|| state.combatState == jp.morrowgear.drone.CombatState.LASER_FIRE)
			&& state.combatTargetOffset != null) {
			double horizontal = state.combatTargetOffset.multiply(1, 0, 1).length();
			state.flightPitch = Mth.clamp((float)(Math.atan2(
				-state.combatTargetOffset.y, Math.max(0.01, horizontal)) * 0.45), -0.12f, 0.38f);
		}
	}

	@Override
	public void submit(DroneRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		super.submit(state, poseStack, collector, camera);
		if (!state.powerLost) {
			FormationTrailController.render(state.entityId, new Vec3(state.x, state.y, state.z), poseStack, collector);
			CombatEffectRenderer.render(state, poseStack, collector,
				camera.pos.subtract(new Vec3(state.x, state.y, state.z)));
		}
		SalvageEffectRenderer.render(state, poseStack, collector);
		DroneMesh mesh = meshes.getOrDefault(state.role, meshes.get(DroneRole.FIELD));
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(-state.heading));
		poseStack.mulPose(Axis.XP.rotation(state.flightPitch));
		poseStack.mulPose(Axis.ZP.rotation(state.flightRoll));
		float rotorAngle = state.docked || state.powerLost ? 0.0f : state.ageInTicks * 0.62f;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE_TEXTURE),
			(pose, consumer) -> mesh.render(pose, consumer, state.lightCoords, rotorAngle));
		if (state.powerLost) {
			// A disabled airframe retains only its unlit material pass.
		} else if (state.combatState.active()) {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> mesh.renderCombatEmissive(pose, consumer, rotorAngle, 1.0f, 255));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> mesh.renderCombatEmissive(pose, consumer, rotorAngle, 1.035f, 82));
		} else {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> mesh.renderEmissive(pose, consumer, rotorAngle, 1.0f, 255));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> mesh.renderEmissive(pose, consumer, rotorAngle, 1.025f, 72));
		}
		if (state.powerLost) {
			// Turbine illumination and rotor motion stop with flight power.
		} else if (state.combatState.active()) {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> mesh.renderCombatTurbines(pose, consumer, rotorAngle, 1.0f, 245));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> mesh.renderCombatTurbines(pose, consumer, rotorAngle, 1.07f, 72));
		} else {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> mesh.renderTurbines(pose, consumer, rotorAngle, 1.0f, 245));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE),
				(pose, consumer) -> mesh.renderTurbines(pose, consumer, rotorAngle, 1.06f, 64));
		}
		poseStack.popPose();
	}
}
