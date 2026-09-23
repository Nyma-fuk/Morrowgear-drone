package jp.morrowgear.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.DroneRole;
import jp.morrowgear.drone.FlightAttitude;
import jp.morrowgear.drone.FlightPresentation;
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
	private final Map<DroneRole, RuntimeMesh> meshes = new EnumMap<>(DroneRole.class);
	private final Map<DroneRole, RuntimeMesh> distantMeshes = new EnumMap<>(DroneRole.class);
	private final Map<DroneRole, RuntimeMesh> farMeshes = new EnumMap<>(DroneRole.class);
	private final Map<DroneEntity, Presentation> presentations = new java.util.WeakHashMap<>();

	public DroneRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 1.25f;
		ResourceManager resources = context.getResourceManager();
		load(resources, DroneRole.FIELD, "field");
		load(resources, DroneRole.CARGO, "cargo");
		load(resources, DroneRole.SCOUT, "scout");
		load(resources, DroneRole.ENGINEER, "engineer");
		load(resources, DroneRole.SECURITY, "security");
		load(resources, DroneRole.SALVAGE, "salvage");
	}

	private void load(ResourceManager resources, DroneRole role, String name) {
		meshes.put(role, RuntimeMesh.load(name));
		distantMeshes.put(role, RuntimeMesh.load(name + "_lod"));
		farMeshes.put(role, RuntimeMesh.load(name + "_far", name + "_lod"));
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
		boolean loweringGear = entity.isDocked() || (entity.mode() == jp.morrowgear.drone.DroneMode.RETURN
			|| entity.mode() == jp.morrowgear.drone.DroneMode.DOCK)
			&& entity.hasDock() && entity.position().distanceTo(Vec3.atCenterOf(entity.dockPos())) < 4;
		boolean working = entity.engineerState() == jp.morrowgear.drone.EngineerState.REPAIRING
			|| entity.fieldOperationState() == jp.morrowgear.drone.FieldOperationState.WORKING
			|| entity.fieldOperationState() == jp.morrowgear.drone.FieldOperationState.PLANTING
			|| entity.salvageState().ordinal() >= jp.morrowgear.drone.SalvageState.HOOK.ordinal();
		boolean equipment = FlightPresentation.deployEquipment(entity.role(), entity.combatWeapon(),
			entity.combatState(), state.docked, state.powerLost, working);
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
		Vec3 velocity = entity.getDeltaMovement();
		Presentation motion = presentations.computeIfAbsent(entity, ignored -> new Presentation(
			entity.tickCount + partialTick, state.heading, loweringGear));
		double now = entity.tickCount + partialTick, dt = Math.max(0, now - motion.time);
		float yawRate = dt > .0001 && dt < 5 ? Mth.wrapDegrees(state.heading - motion.heading) / (float)dt : 0;
		float pitch = state.docked || state.powerLost ? 0 : FlightAttitude.pitch(velocity, state.heading);
		float roll = state.docked || state.powerLost ? 0
			: FlightAttitude.coordinatedRoll(velocity, state.heading, yawRate);
		motion.pitch = FlightPresentation.approach(motion.pitch, pitch, dt, 3.5, .035);
		motion.roll = FlightPresentation.approach(motion.roll, roll, dt, 4, .045);
		motion.gear = state.docked ? 1 : FlightPresentation.approach(motion.gear, loweringGear ? 1 : 0, dt, 5, .1);
		motion.equipment = FlightPresentation.approach(motion.equipment, equipment ? 1 : 0, dt, 2.5, .16);
		motion.heading = state.heading; motion.time = now;
		state.flightPitch = state.docked ? 0 : motion.pitch;
		state.flightRoll = state.docked ? 0 : motion.roll;
		state.gearDeployment = motion.gear;
		state.equipmentDeployment = motion.equipment;
		if (state.combatTargetOffset != null) {
			Vec3 worldAim = entity.position().add(state.combatTargetOffset);
			if (state.combatState == jp.morrowgear.drone.CombatState.LASER_FIRE) {
				if (motion.targetId != entity.combatTargetId() || motion.aim == null)
					motion.aim = worldAim;
				else motion.aim = motion.aim.lerp(worldAim, -Math.expm1(-Math.min(dt, 4) / 1.5));
				worldAim = motion.aim;
			} else motion.aim = null;
			motion.targetId = entity.combatTargetId();
			state.combatTargetOffset = worldAim.subtract(new Vec3(state.x, state.y, state.z));
		} else { motion.aim = null; motion.targetId = -1; }
	}

	@Override
	public void submit(DroneRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		super.submit(state, poseStack, collector, camera);
		if (!state.powerLost) {
			FormationTrailController.render(state, poseStack, collector,
				camera.pos.subtract(new Vec3(state.x, state.y, state.z)));
			CombatEffectRenderer.render(state, poseStack, collector,
				camera.pos.subtract(new Vec3(state.x, state.y, state.z)));
			ScoutScanRenderer.render(state, poseStack, collector,
				camera.pos.subtract(new Vec3(state.x, state.y, state.z)));
		}
		SalvageEffectRenderer.render(state, poseStack, collector);
		RuntimeGeometryBudgetPolicy.Detail quality = RuntimeGeometryBudgetController.drone(state.entityId, camera.pos);
		boolean distant = quality != RuntimeGeometryBudgetPolicy.Detail.FULL;
		Map<DroneRole, RuntimeMesh> detail = quality == RuntimeGeometryBudgetPolicy.Detail.FAR ? farMeshes
			: distant ? distantMeshes : meshes;
		RuntimeMesh mesh = detail.getOrDefault(state.role, detail.get(DroneRole.FIELD));
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(-state.heading));
		poseStack.mulPose(Axis.XP.rotation(state.flightPitch));
		poseStack.mulPose(Axis.ZP.rotation(state.flightRoll));
		float rotorAngle = state.docked || state.powerLost ? 0.0f : state.ageInTicks * 0.62f;
		mesh.submit(poseStack, collector, state.lightCoords, rotorAngle,
			state.gearDeployment, state.equipmentDeployment, !state.powerLost,
			state.combatState.active(), !distant, !distant);
		poseStack.popPose();
	}

	private static final class Presentation {
		double time;
		float heading, pitch, roll, gear, equipment;
		Vec3 aim;
		int targetId = -1;
		Presentation(double time, float heading, boolean gear) {
			this.time = time; this.heading = heading; this.gear = gear ? 1 : 0;
		}
	}
}
