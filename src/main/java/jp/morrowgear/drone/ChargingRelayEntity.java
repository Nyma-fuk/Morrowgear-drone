package jp.morrowgear.drone;

import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

public final class ChargingRelayEntity extends Entity {
	private static final EntityDataAccessor<String> STATION = SynchedEntityData.defineId(
		ChargingRelayEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> SLOT = SynchedEntityData.defineId(
		ChargingRelayEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> TARGET = SynchedEntityData.defineId(
		ChargingRelayEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> CHARGING = SynchedEntityData.defineId(
		ChargingRelayEntity.class, EntityDataSerializers.BOOLEAN);
	private int targetStableTicks;
	private boolean bayLocked = true;

	public ChargingRelayEntity(EntityType<? extends ChargingRelayEntity> type, Level level) {
		super(type, level);
		setNoGravity(true);
	}

	public void initialize(SolarServiceStationEntity station, int slot) {
		entityData.set(STATION, station.getUUID().toString());
		entityData.set(SLOT, Mth.clamp(slot, 0, 2));
		setPos(station.bayPosition(slot));
	}

	public UUID stationId() {
		try { return UUID.fromString(entityData.get(STATION)); }
		catch (IllegalArgumentException ignored) { return new UUID(0, 0); }
	}
	public int slot() { return entityData.get(SLOT); }
	public int targetId() { return entityData.get(TARGET); }
	public boolean charging() { return entityData.get(CHARGING); }

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(STATION, "");
		builder.define(SLOT, 0);
		builder.define(TARGET, -1);
		builder.define(CHARGING, false);
	}

	@Override
	public void tick() {
		super.tick();
		setNoGravity(true);
		if (!(level() instanceof ServerLevel level)) return;
		Entity stationEntity = level.getEntity(stationId());
		if (!(stationEntity instanceof SolarServiceStationEntity station) || !station.isAlive()) {
			setDeltaMovement(getDeltaMovement().scale(.92));
			return;
		}
		Entity targetEntity = targetId() < 0 ? null : level.getEntity(targetId());
		DroneEntity target = targetEntity instanceof DroneEntity drone && drone.isAlive()
			&& drone.batteryPercent() < drone.solarChargeTargetPercent() ? drone : null;
		if (target == null && tickCount % 20 == slot() * 5) {
			target = station.acquireChargeTarget(slot());
			entityData.set(TARGET, target == null ? -1 : target.getId());
			targetStableTicks = 0;
			if (target != null) bayLocked = false;
		}
		Vec3 desired = target == null ? station.bayPosition(slot())
			: target.position().add(0, target.getBbHeight() + 2.35, 0);
		Vec3 delta = desired.subtract(position());
		if (ChargingRelayPolicy.lockToBay(target != null, bayLocked, delta.length())) {
			bayLocked = true;
			setPos(desired);
			setDeltaMovement(Vec3.ZERO);
			setYRot(station.getYRot());
			entityData.set(CHARGING, false);
			return;
		}
		double speed = target == null ? .34 : .56;
		Vec3 velocity = target == null
			? ChargingRelayPolicy.followVelocity(getDeltaMovement(), position(), desired, Vec3.ZERO, speed)
			: ChargingRelayPolicy.followVelocity(getDeltaMovement(), position(), desired,
				target.getDeltaMovement(), speed);
		setDeltaMovement(velocity);
		setPos(position().add(velocity));
		if (velocity.lengthSqr() > .001) setYRot((float)(Mth.atan2(velocity.z, velocity.x) * 180 / Math.PI) - 90);
		if (target != null && delta.length() < .85) {
			targetStableTicks++;
			entityData.set(CHARGING, ChargingRelayPolicy.beamStable(targetStableTicks));
			SolarServiceRegistry.reserve(station.getUUID(), target.getUUID(), level.getGameTime());
			if (ChargingRelayPolicy.transferThisTick(targetStableTicks, tickCount)
				&& station.provideFlightCharge(ChargingRelayPolicy.TRANSFER_AMOUNT)) {
				target.receiveSolarFlightCharge(ChargingRelayPolicy.TRANSFER_AMOUNT);
			}
			if (target.batteryPercent() >= target.solarChargeTargetPercent()) releaseTarget(target);
		} else if (target != null) {
			targetStableTicks = Math.max(0, targetStableTicks - 1);
			entityData.set(CHARGING, false);
		} else entityData.set(CHARGING, false);
	}

	private void releaseTarget(DroneEntity target) {
		SolarServiceRegistry.release(target.getUUID());
		entityData.set(TARGET, -1);
		entityData.set(CHARGING, false);
		targetStableTicks = 0;
		bayLocked = false;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (amount <= 0) return false;
		Entity target = targetId() < 0 ? null : level.getEntity(targetId());
		if (target instanceof DroneEntity drone) SolarServiceRegistry.release(drone.getUUID());
		discard();
		return true;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putString("Station", entityData.get(STATION));
		output.putInt("Slot", slot());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		entityData.set(STATION, input.getStringOr("Station", ""));
		entityData.set(SLOT, Mth.clamp(input.getIntOr("Slot", 0), 0, 2));
		entityData.set(TARGET, -1);
		entityData.set(CHARGING, false);
		bayLocked = true;
	}
}
