package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SolarServiceStationEntity extends PathfinderMob {
	public static final int ENERGY_CAPACITY = 24000;
	private static final EntityDataAccessor<String> OWNER = SynchedEntityData.defineId(
		SolarServiceStationEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> ENERGY = SynchedEntityData.defineId(
		SolarServiceStationEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> ACTIVE_RELAYS = SynchedEntityData.defineId(
		SolarServiceStationEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> WAITING_AIRCRAFT = SynchedEntityData.defineId(
		SolarServiceStationEntity.class, EntityDataSerializers.INT);
	private Vec3 anchor;
	private final BlockPos[] lightPositions = new BlockPos[SolarStationLightingPolicy.SATELLITE_COUNT];

	public SolarServiceStationEntity(EntityType<? extends SolarServiceStationEntity> type, Level level) {
		super(type, level);
		setNoGravity(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 80.0)
			.add(Attributes.KNOCKBACK_RESISTANCE, 1.0).add(Attributes.FOLLOW_RANGE, 32.0);
	}

	public void deploy(ServerPlayer owner, net.minecraft.core.BlockPos position) {
		entityData.set(OWNER, owner.getUUID().toString());
		anchor = Vec3.atCenterOf(position);
		setPos(anchor);
		setPersistenceRequired();
	}

	public UUID ownerId() {
		try { return UUID.fromString(entityData.get(OWNER)); }
		catch (IllegalArgumentException ignored) { return new UUID(0, 0); }
	}

	public int energyPercent() { return entityData.get(ENERGY) * 100 / ENERGY_CAPACITY; }
	public int activeRelays() { return entityData.get(ACTIVE_RELAYS); }
	public int waitingAircraft() { return entityData.get(WAITING_AIRCRAFT); }

	public boolean provideFlightCharge(int amount) {
		int requested = Math.max(0, amount);
		int stored = entityData.get(ENERGY);
		if (requested == 0 || stored < requested) return false;
		entityData.set(ENERGY, stored - requested);
		return true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(OWNER, "");
		builder.define(ENERGY, ENERGY_CAPACITY / 2);
		builder.define(ACTIVE_RELAYS, 0);
		builder.define(WAITING_AIRCRAFT, 0);
	}

	@Override
	public void tick() {
		super.tick();
		setNoGravity(true);
		if (anchor == null) anchor = position();
		double hover = Math.sin(tickCount * 0.035) * 0.045;
		setPos(anchor.x, anchor.y + hover, anchor.z);
		setDeltaMovement(Vec3.ZERO);
		setYRot((tickCount * 0.18f) % 360.0f);
		if (!(level() instanceof ServerLevel level)) return;
		if (tickCount % SolarStationLightingPolicy.UPDATE_INTERVAL_TICKS == 0) updateServiceLights(level);
		if (tickCount % 20 == 0) level.getChunkSource().addTicketWithRadius(
			MorrowgearDrone.DRONE_OPERATION_TICKET, chunkPosition(),
			RemoteOperationPolicy.ENTITY_TICKING_TICKET_RADIUS);
		if (level.getBrightness(LightLayer.SKY, blockPosition()) >= 12
			&& level.canSeeSky(blockPosition()) && tickCount % 2 == 0) {
			entityData.set(ENERGY, Math.min(ENERGY_CAPACITY, entityData.get(ENERGY) + 5));
		}
		SolarServiceRegistry.heartbeat(getUUID(), ownerId(), level.dimension().toString(),
			getX(), getY(), getZ(), energyPercent(), level.getGameTime());
		entityData.set(WAITING_AIRCRAFT, SolarServiceRegistry.waitingCount(getUUID(), level.getGameTime()));
		maintainRelays(level);
	}

	public Vec3 lightSatellitePosition(int slot) {
		return position().add(SolarStationLightingPolicy.orbitOffset(slot, -getYRot()));
	}

	private void updateServiceLights(ServerLevel level) {
		for (int slot = 0; slot < lightPositions.length; slot++) {
			BlockPos next = BlockPos.containing(lightSatellitePosition(slot));
			if (next.equals(lightPositions[slot])) continue;
			removeServiceLight(level, lightPositions[slot]);
			if (level.getBlockState(next).isAir() || level.getBlockState(next).is(Blocks.LIGHT)) {
				level.setBlockAndUpdate(next, Blocks.LIGHT.defaultBlockState());
				lightPositions[slot] = next;
			} else lightPositions[slot] = null;
		}
	}

	private boolean usesManagedLight(BlockPos pos) {
		if (pos == null) return false;
		for (BlockPos lightPosition : lightPositions) if (pos.equals(lightPosition)) return true;
		return false;
	}

	private void clearServiceLights(ServerLevel level) {
		for (int slot = 0; slot < lightPositions.length; slot++) {
			removeServiceLight(level, lightPositions[slot]);
			lightPositions[slot] = null;
		}
	}

	private void removeServiceLight(ServerLevel level, BlockPos pos) {
		if (pos == null || !level.getBlockState(pos).is(Blocks.LIGHT)) return;
		boolean sharedByStation = !level.getEntitiesOfClass(SolarServiceStationEntity.class,
			new AABB(pos).inflate(8.0), station -> station != this && station.usesManagedLight(pos)).isEmpty();
		boolean sharedByDrone = !level.getEntitiesOfClass(DroneEntity.class,
			new AABB(pos).inflate(10.0), drone -> drone.usesManagedLight(pos)).isEmpty();
		if (!sharedByStation && !sharedByDrone) level.removeBlock(pos, false);
	}

	private void maintainRelays(ServerLevel level) {
		List<ChargingRelayEntity> relays = level.getEntitiesOfClass(ChargingRelayEntity.class,
			getBoundingBox().inflate(24), relay -> getUUID().equals(relay.stationId()));
		boolean[] present = new boolean[3];
		for (ChargingRelayEntity relay : relays) if (relay.slot() >= 0 && relay.slot() < 3) present[relay.slot()] = true;
		for (int slot = 0; slot < 3; slot++) {
			if (present[slot]) continue;
			ChargingRelayEntity relay = MorrowgearDrone.CHARGING_RELAY.create(level, EntitySpawnReason.TRIGGERED);
			if (relay == null) continue;
			relay.initialize(this, slot);
			level.addFreshEntity(relay);
		}
		entityData.set(ACTIVE_RELAYS, (int)relays.stream().filter(ChargingRelayEntity::charging).count());
	}

	public Vec3 bayPosition(int slot) {
		double angle = Math.toRadians(90 + Mth.clamp(slot, 0, 2) * 120) + Math.toRadians(getYRot());
		return position().add(Math.cos(angle) * 1.35, .34, Math.sin(angle) * 1.35);
	}

	public DroneEntity acquireChargeTarget(int slot) {
		if (!(level() instanceof ServerLevel serverLevel)) return null;
		List<DroneEntity> candidates = serverLevel.getEntitiesOfClass(DroneEntity.class,
			new AABB(position(), position()).inflate(14), drone -> drone.isOwnedBy(ownerId())
				&& !drone.isDocked() && !drone.isPowerLost()
				&& drone.batteryPercent() < drone.solarChargeTargetPercent());
		return candidates.stream().filter(drone -> SolarServiceRegistry.reservedSlot(getUUID(), drone.getUUID(),
			serverLevel.getGameTime()) == slot).min(Comparator.comparingInt(DroneEntity::batteryPercent)
			.thenComparingDouble(drone -> drone.distanceToSqr(this))).orElse(null);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return super.hurtServer(level, source, amount);
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		if (level() instanceof ServerLevel serverLevel) {
			clearServiceLights(serverLevel);
			SolarServiceRegistry.removeStation(getUUID());
		}
		super.remove(reason);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("Owner", entityData.get(OWNER));
		output.putInt("Energy", entityData.get(ENERGY));
		Vec3 saved = anchor == null ? position() : anchor;
		output.putDouble("AnchorX", saved.x);
		output.putDouble("AnchorY", saved.y);
		output.putDouble("AnchorZ", saved.z);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		entityData.set(OWNER, input.getStringOr("Owner", ""));
		entityData.set(ENERGY, Mth.clamp(input.getIntOr("Energy", ENERGY_CAPACITY / 2), 0, ENERGY_CAPACITY));
		anchor = new Vec3(input.getDoubleOr("AnchorX", getX()), input.getDoubleOr("AnchorY", getY()),
			input.getDoubleOr("AnchorZ", getZ()));
		setPersistenceRequired();
	}
}
