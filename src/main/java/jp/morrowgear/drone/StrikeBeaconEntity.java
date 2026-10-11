package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Sticky T4-phage-shaped marker and coordinator for a bounded all-weapons strike. */
public final class StrikeBeaconEntity extends Entity {
	private static final EntityDataAccessor<Boolean> ATTACHED = SynchedEntityData.defineId(
		StrikeBeaconEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> HOST = SynchedEntityData.defineId(
		StrikeBeaconEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Vector3fc> NORMAL = SynchedEntityData.defineId(
		StrikeBeaconEntity.class, EntityDataSerializers.VECTOR3);
	private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(
		StrikeBeaconEntity.class, EntityDataSerializers.INT);
	private UUID ownerId;
	private UUID hostId;
	private Vec3 hostOffset = Vec3.ZERO;
	private Vec3 anchor;
	private long deploymentTick;
	private int operationTicks;
	private int completedTicks;
	private long barrageStartTick = -1L;
	private final InterpolationHandler interpolation = new InterpolationHandler(this, 2);

	public StrikeBeaconEntity(EntityType<? extends StrikeBeaconEntity> type, Level level) {
		super(type, level);
		setNoGravity(true);
	}

	public static boolean launch(ServerLevel level, ServerPlayer owner) {
		if (activeForOwner(level, owner.getUUID()).size() >= StrikeBeaconPolicy.MAX_ACTIVE_BEACONS) return false;
		StrikeBeaconEntity beacon = MorrowgearDrone.STRIKE_BEACON.create(level, EntitySpawnReason.TRIGGERED);
		if (beacon == null) return false;
		beacon.ownerId = owner.getUUID();
		beacon.deploymentTick = level.getGameTime();
		Vec3 direction = owner.getLookAngle().normalize();
		Vec3 origin = owner.getEyePosition().add(direction.scale(0.7));
		beacon.setPos(origin);
		beacon.setDeltaMovement(direction.scale(2.15).add(owner.getDeltaMovement().scale(0.18)));
		beacon.updateRotation(beacon.getDeltaMovement());
		return level.addFreshEntity(beacon);
	}

	public static int activeCount(ServerLevel level, UUID owner) {
		return activeForOwner(level, owner).size();
	}

	private static List<StrikeBeaconEntity> activeForOwner(ServerLevel level, UUID owner) {
		java.util.ArrayList<StrikeBeaconEntity> result = new java.util.ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof StrikeBeaconEntity beacon && beacon.isAlive()
				&& owner.equals(beacon.ownerId) && beacon.operationPhase() < 3) result.add(beacon);
		}
		result.sort(Comparator.comparingLong((StrikeBeaconEntity beacon) -> beacon.deploymentTick)
			.thenComparing(beacon -> beacon.getUUID().toString()));
		return List.copyOf(result);
	}

	@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(ATTACHED, false);
		builder.define(HOST, -1);
		builder.define(NORMAL, new Vector3f(0, 1, 0));
		builder.define(PHASE, 0);
	}

	@Override public InterpolationHandler getInterpolation() { return interpolation; }
	public boolean attached() { return entityData.get(ATTACHED); }
	public int operationPhase() { return entityData.get(PHASE); }
	public Vec3 surfaceNormal() {
		Vector3fc value = entityData.get(NORMAL);
		return new Vec3(value.x(), value.y(), value.z());
	}
	public UUID ownerId() { return ownerId; }
	public boolean isOwnedBy(ServerPlayer player) { return player != null && player.getUUID().equals(ownerId); }
	public long barrageElapsedTicks(long gameTime) {
		return barrageStartTick < 0L ? -1L : Math.max(0L, gameTime - barrageStartTick);
	}

	@Override public void tick() {
		super.tick();
		if (level().isClientSide()) {
			interpolation.interpolate();
			return;
		}
		if (!(level() instanceof ServerLevel level) || ownerId == null) { discard(); return; }
		if (!attached()) {
			tickProjectile(level);
			return;
		}
		followHost(level);
		setDeltaMovement(Vec3.ZERO);
		operationTicks++;
		if (operationTicks % 20 == 0) OperationChunkLeaseRuntime.refresh(level, chunkPosition());
		tickSignal(level);
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
		if (owner == null || owner.level() != level || operationTicks > StrikeBeaconPolicy.OPERATION_TIMEOUT_TICKS) {
			releaseAssignedDrones(level, "BEACON LINK LOST");
			discard();
			return;
		}
		if (operationPhase() == 0 && operationTicks >= StrikeBeaconPolicy.ARM_TICKS) {
			entityData.set(PHASE, 1);
			level.playSound(null, position().x, position().y, position().z,
				SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.65f, 1.35f);
		}
		if (operationPhase() == 1) tickMuster(level, owner);
		else if (operationPhase() == 2) tickBarrage(level);
		else if (operationPhase() == 3 && ++completedTicks >= StrikeBeaconPolicy.RETIRE_DELAY_TICKS) discard();
	}

	private void tickProjectile(ServerLevel level) {
		Vec3 from = position();
		Vec3 velocity = getDeltaMovement().add(0, -0.025, 0);
		Vec3 to = from.add(velocity);
		BlockHitResult blockHit = level.clip(new ClipContext(from, to,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		Vec3 entityEnd = blockHit.getType() == HitResult.Type.BLOCK ? blockHit.getLocation() : to;
		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(level, this, from, entityEnd,
			new AABB(from, entityEnd).inflate(0.3), candidate -> candidate.isAlive()
				&& candidate != this && !candidate.getUUID().equals(ownerId)
				&& !(candidate instanceof DroneEntity), 0.2f);
		if (entityHit != null) {
			attachToEntity(level, entityHit);
			return;
		}
		if (blockHit.getType() == HitResult.Type.BLOCK) {
			attachToBlock(level, blockHit);
			return;
		}
		setDeltaMovement(velocity);
		setPos(to);
		updateRotation(velocity);
		if (tickCount > 160) discard();
	}

	private void attachToEntity(ServerLevel level, EntityHitResult hit) {
		Entity host = hit.getEntity();
		hostId = host.getUUID();
		hostOffset = MicroMissilePolicy.attachmentLocal(hit.getLocation(), host.position(), host.getYRot());
		Vec3 velocity = getDeltaMovement();
		Vec3 normal = velocity.lengthSqr() < 1.0e-6 ? new Vec3(0, 1, 0) : velocity.normalize().scale(-1);
		anchor = hit.getLocation().add(normal.scale(0.05));
		entityData.set(HOST, host.getId());
		completeAttachment(level, anchor, normal);
	}

	private void attachToBlock(ServerLevel level, BlockHitResult hit) {
		Direction face = hit.getDirection();
		Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		anchor = hit.getLocation().add(normal.scale(0.035));
		completeAttachment(level, anchor, normal);
	}

	private void completeAttachment(ServerLevel level, Vec3 location, Vec3 normal) {
		setPos(location);
		setDeltaMovement(Vec3.ZERO);
		entityData.set(ATTACHED, true);
		entityData.set(NORMAL, new Vector3f((float)normal.x, (float)normal.y, (float)normal.z));
		updateRotation(normal.scale(-1));
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, location.x, location.y, location.z,
			10, 0.16, 0.16, 0.16, 0.025);
		level.playSound(null, location.x, location.y, location.z, SoundEvents.IRON_GOLEM_DAMAGE,
			SoundSource.PLAYERS, 0.42f, 1.65f);
	}

	private void followHost(ServerLevel level) {
		if (hostId == null) return;
		Entity host = level.getEntity(hostId);
		if (host == null || !host.isAlive()) { hostId = null; entityData.set(HOST, -1); return; }
		Vec3 next = MicroMissilePolicy.attachmentPosition(hostOffset, host.position(), host.getYRot());
		if (MicroMissilePolicy.mayFollowAttachment(position(), next)) {
			anchor = next;
			setPos(next);
			entityData.set(HOST, host.getId());
		}
	}

	private void tickSignal(ServerLevel level) {
		if (Math.floorMod(operationTicks, 20) == 0) {
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getY() + 0.18, getZ(),
				8, 0.18, 0.18, 0.18, 0.015);
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.NOTE_BLOCK_PLING.value(),
				SoundSource.PLAYERS, 0.22f, operationPhase() == 2 ? 1.65f : 1.25f);
		}
	}

	private void tickMuster(ServerLevel level, ServerPlayer owner) {
		if (operationTicks % 10 == 0) rebalancePlanningBeacons(level, owner);
		List<DroneEntity> assigned = assignedDrones(level);
		for (int index = 0; index < assigned.size(); index++)
			assigned.get(index).updateStrikeBeaconSlot(getUUID(), index, assigned.size());
		int arrived = 0;
		for (DroneEntity drone : assigned) {
			Vec3 point = StrikeBeaconPolicy.musterPoint(position(), drone.strikeBeaconSlot(), assigned.size(), level.getGameTime());
			if (StrikeBeaconPolicy.arrived(drone.position(), point)) arrived++;
		}
		if (StrikeBeaconPolicy.beginBarrage(Math.max(0, operationTicks - StrikeBeaconPolicy.ARM_TICKS),
			assigned.size(), arrived)) {
			entityData.set(PHASE, 2);
			barrageStartTick = level.getGameTime();
			assigned.forEach(drone -> drone.beginStrikeBeaconBarrage(getUUID()));
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_POWER_SELECT,
				SoundSource.PLAYERS, 0.9f, 0.72f);
		}
	}

	private void rebalancePlanningBeacons(ServerLevel level, ServerPlayer owner) {
		List<StrikeBeaconEntity> planning = activeForOwner(level, owner.getUUID()).stream()
			.filter(StrikeBeaconEntity::attached).filter(beacon -> beacon.operationPhase() <= 1).toList();
		if (planning.isEmpty()) return;
		// One coordinator pass per owner is enough. Every beacon still owns its own muster and fire clock.
		if (!planning.getFirst().getUUID().equals(getUUID())) return;
		java.util.Set<UUID> planningIds = planning.stream().map(Entity::getUUID)
			.collect(java.util.stream.Collectors.toSet());
		List<DroneEntity> candidates = MorrowgearDrone.ownedDrones(level, owner, 512.0).stream()
			.filter(drone -> drone.strikeBeaconCoordinatorEligible(owner, planning.getFirst(), planningIds))
			.sorted(Comparator.comparing(DroneEntity::unitId)).toList();
		for (int index = 0; index < candidates.size(); index++) {
			DroneEntity drone = candidates.get(index);
			StrikeBeaconEntity assigned = planning.get(StrikeBeaconPolicy.balancedBeaconIndex(index, planning.size()));
			drone.assignOrTransferStrikeBeacon(assigned, planningIds);
		}
		for (StrikeBeaconEntity beacon : planning) {
			List<DroneEntity> assigned = candidates.stream()
				.filter(drone -> drone.strikeBeaconAssigned(beacon.getUUID()))
				.sorted(Comparator.comparing(DroneEntity::unitId)).toList();
			for (int slot = 0; slot < assigned.size(); slot++)
				assigned.get(slot).updateStrikeBeaconSlot(beacon.getUUID(), slot, assigned.size());
		}
	}

	private void tickBarrage(ServerLevel level) {
		List<DroneEntity> assigned = assignedDrones(level);
		if (assigned.isEmpty()) {
			entityData.set(PHASE, 3);
			return;
		}
		boolean active = assigned.stream().anyMatch(drone -> drone.strikeBeaconAssigned(getUUID()));
		if (!active) entityData.set(PHASE, 3);
	}

	private List<DroneEntity> assignedDrones(ServerLevel level) {
		java.util.ArrayList<DroneEntity> result = new java.util.ArrayList<>();
		for (Entity entity : level.getAllEntities())
			if (entity instanceof DroneEntity drone && drone.strikeBeaconAssigned(getUUID())) result.add(drone);
		return result.stream().sorted(Comparator.comparing(DroneEntity::unitId)).toList();
	}

	private void releaseAssignedDrones(ServerLevel level, String reason) {
		assignedDrones(level).forEach(drone -> drone.releaseStrikeBeacon(getUUID(), reason));
	}

	private void updateRotation(Vec3 direction) {
		if (direction.lengthSqr() < 1.0e-8) return;
		Vec3 unit = direction.normalize();
		setYRot((float)(Mth.atan2(unit.x, unit.z) * Mth.RAD_TO_DEG));
		setXRot((float)(Mth.atan2(-unit.y, unit.horizontalDistance()) * Mth.RAD_TO_DEG));
	}

	@Override protected void addAdditionalSaveData(ValueOutput output) {
		if (ownerId != null) output.putString("Owner", ownerId.toString());
		if (hostId != null) output.putString("Host", hostId.toString());
		output.putLong("DeploymentTick", deploymentTick);
		output.putDouble("HostX", hostOffset.x); output.putDouble("HostY", hostOffset.y); output.putDouble("HostZ", hostOffset.z);
		output.putBoolean("Attached", attached()); output.putInt("Phase", operationPhase());
		output.putInt("OperationTicks", operationTicks); output.putInt("CompletedTicks", completedTicks);
		output.putLong("BarrageStartTick", barrageStartTick);
		if (anchor != null) { output.putDouble("AnchorX", anchor.x); output.putDouble("AnchorY", anchor.y); output.putDouble("AnchorZ", anchor.z); }
		Vec3 normal = surfaceNormal();
		output.putDouble("NormalX", normal.x); output.putDouble("NormalY", normal.y); output.putDouble("NormalZ", normal.z);
	}

	@Override protected void readAdditionalSaveData(ValueInput input) {
		ownerId = MicroMissilePolicy.uuid(input.getStringOr("Owner", ""));
		hostId = MicroMissilePolicy.uuid(input.getStringOr("Host", ""));
		deploymentTick = Math.max(0L, input.getLongOr("DeploymentTick", 0L));
		hostOffset = new Vec3(input.getDoubleOr("HostX", 0), input.getDoubleOr("HostY", 0), input.getDoubleOr("HostZ", 0));
		entityData.set(ATTACHED, input.getBooleanOr("Attached", false));
		entityData.set(PHASE, Mth.clamp(input.getIntOr("Phase", 0), 0, 3));
		operationTicks = Math.max(0, input.getIntOr("OperationTicks", 0));
		completedTicks = Math.max(0, input.getIntOr("CompletedTicks", 0));
		barrageStartTick = input.getLongOr("BarrageStartTick", -1L);
		if (operationPhase() == 2 && barrageStartTick < 0L) barrageStartTick = level().getGameTime();
		anchor = new Vec3(input.getDoubleOr("AnchorX", getX()), input.getDoubleOr("AnchorY", getY()), input.getDoubleOr("AnchorZ", getZ()));
		Vec3 normal = new Vec3(input.getDoubleOr("NormalX", 0), input.getDoubleOr("NormalY", 1), input.getDoubleOr("NormalZ", 0));
		if (normal.lengthSqr() < 1.0e-8) normal = new Vec3(0, 1, 0);
		normal = normal.normalize();
		entityData.set(NORMAL, new Vector3f((float)normal.x, (float)normal.y, (float)normal.z));
	}

	@Override public boolean isPickable() { return false; }
	@Override public boolean isPushable() { return false; }
	@Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (amount <= 0) return false;
		releaseAssignedDrones(level, "BEACON DESTROYED");
		discard();
		return true;
	}
	@Override public boolean shouldRenderAtSqrDistance(double distanceSquared) { return distanceSquared < 192 * 192; }
}
