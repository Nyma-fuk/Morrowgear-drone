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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

public final class MorrowgearMissileEntity extends Entity {
	private static final EntityDataAccessor<Integer> TARGET =
		SynchedEntityData.defineId(MorrowgearMissileEntity.class, EntityDataSerializers.INT);
	private UUID launcherId = new UUID(0, 0);
	private int flightTicks;

	public MorrowgearMissileEntity(EntityType<? extends MorrowgearMissileEntity> type, Level level) {
		super(type, level);
		setNoGravity(true);
	}

	static void launch(ServerLevel level, DroneEntity launcher, LivingEntity target) {
		MorrowgearMissileEntity missile = MorrowgearDrone.MISSILE.create(level,
			net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
		if (missile == null) return;
		missile.launcherId = launcher.getUUID();
		missile.entityData.set(TARGET, target.getId());
		double yaw = Math.toRadians(launcher.getYRot());
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		missile.setPos(launcher.position().add(forward.scale(0.7)).add(0, 0.22, 0));
		missile.setDeltaMovement(forward.scale(0.34).add(0, 0.68, 0));
		level.addFreshEntity(missile);
	}

	public int targetId() { return entityData.get(TARGET); }

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(TARGET, -1);
	}

	@Override
	public void tick() {
		super.tick();
		flightTicks++;
		if (level().isClientSide()) return;
		if (!(level() instanceof ServerLevel level)) return;
		Entity entity = targetId() < 0 ? null : level.getEntity(targetId());
		LivingEntity target = entity instanceof LivingEntity living && living.isAlive() ? living : null;
		if (target == null || flightTicks > 120) {
			discard();
			return;
		}

		Vec3 aim = target.position().add(0, target.getBbHeight() * 0.72, 0);
		Vec3 desired;
		if (flightTicks < 12) {
			desired = getDeltaMovement().multiply(1, 0, 1).add(0, 1.35, 0);
		} else {
			double topHeight = Math.max(4.0, Math.min(8.0, position().distanceTo(aim) * 0.32));
			Vec3 topAttackAim = flightTicks < 28 ? aim.add(0, topHeight, 0) : aim;
			desired = topAttackAim.subtract(position());
		}
		if (desired.lengthSqr() < 0.001) desired = new Vec3(0, -1, 0);
		double speed = flightTicks < 12 ? 0.76 : 0.96;
		Vec3 velocity = getDeltaMovement().scale(0.72).add(desired.normalize().scale(speed * 0.28));
		if (velocity.length() > speed) velocity = velocity.normalize().scale(speed);
		setDeltaMovement(velocity);
		setYRot((float)(Mth.atan2(velocity.z, velocity.x) * 180.0 / Math.PI) - 90.0f);

		if (position().distanceTo(aim) <= 1.15) {
			detonate(level, target);
			return;
		}
		if (!level.noCollision(this, getBoundingBox().move(velocity))) {
			detonate(level, target);
			return;
		}
		setPos(position().add(velocity));
	}

	private void detonate(ServerLevel level, LivingEntity target) {
		Entity launcher = level.getEntity(launcherId);
		DamageSource source = launcher instanceof DroneEntity drone
			? level.damageSources().mobAttack(drone) : level.damageSources().generic();
		if (distanceTo(target) <= 2.6) MorrowgearCombatDamage.apply(level, target, source, 10.0f);
		discard();
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		discard();
		return true;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putString("Launcher", launcherId.toString());
		output.putInt("Target", targetId());
		output.putInt("FlightTicks", flightTicks);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		try {
			launcherId = UUID.fromString(input.getStringOr("Launcher",
				"00000000-0000-0000-0000-000000000000"));
		} catch (IllegalArgumentException ignored) {
			launcherId = new UUID(0, 0);
		}
		entityData.set(TARGET, input.getIntOr("Target", -1));
		flightTicks = Math.max(0, input.getIntOr("FlightTicks", 0));
	}
}
