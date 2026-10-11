package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jp.morrowgear.drone.block.DockCenterBlock;
import jp.morrowgear.drone.block.DockPartBlock;
import jp.morrowgear.drone.carrier.CarrierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class MorrowgearMissileEntity extends Entity {
	public enum FlightPhase { EJECT, TURN, BOOST, GUIDED, IMPACT }
	public static final double EXHAUST_OFFSET = -0.40;
	private static final TagKey<Block> PROTECTED_BLOCKS = TagKey.create(Registries.BLOCK,
		Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "missile_protected"));
	private static final EntityDataAccessor<Integer> TARGET =
		SynchedEntityData.defineId(MorrowgearMissileEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> IMPACTED =
		SynchedEntityData.defineId(MorrowgearMissileEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Boolean> MOTOR_IGNITED =
		SynchedEntityData.defineId(MorrowgearMissileEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> FLIGHT_PHASE =
		SynchedEntityData.defineId(MorrowgearMissileEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> HEAVY =
		SynchedEntityData.defineId(MorrowgearMissileEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<String> OWNER =
		SynchedEntityData.defineId(MorrowgearMissileEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> ATTACHED_HOST =
		SynchedEntityData.defineId(MorrowgearMissileEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Vector3fc> ATTACHED_OFFSET =
		SynchedEntityData.defineId(MorrowgearMissileEntity.class, EntityDataSerializers.VECTOR3);
	private UUID launcherId;
	private UUID ownerId;
	private UUID targetUuid;
	private String ownerTeam = "";
	private Vec3 lastAim;
	private int flightTicks;
	private MicroMissilePolicy.LaunchProfile launchProfile = new MicroMissilePolicy.LaunchProfile(0, 0);
	private int fuseTicks = -1;
	private float explosionPower = MicroMissilePolicy.MIN_EXPLOSION_POWER;
	private boolean detonated;
	private boolean terminalGuidance;
	private boolean passedTarget;
	private MicroMissilePolicy.Attachment attachment;
	private final InterpolationHandler interpolation = new InterpolationHandler(this, 2);
	private MissileTrailHistory visualTrail;
	private int penetrationBlocks;

	public MorrowgearMissileEntity(EntityType<? extends MorrowgearMissileEntity> type, Level level) {
		super(type, level);
		setNoGravity(true);
	}

	static boolean launch(ServerLevel level, DroneEntity launcher, LivingEntity target, int shot) {
		MorrowgearMissileEntity missile = MorrowgearDrone.MISSILE.create(level, EntitySpawnReason.TRIGGERED);
		if (missile == null) return false;
		missile.launcherId = launcher.getUUID();
		missile.ownerId = launcher.ownerId();
		missile.entityData.set(OWNER,missile.ownerId.toString());
		missile.targetUuid = target.getUUID();
		missile.launchProfile = new MicroMissilePolicy.LaunchProfile(shot, launcher.getYRot());
		missile.entityData.set(TARGET, target.getId());
		missile.lastAim = target.position().add(0, target.getBbHeight() * 0.55, 0);
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(missile.ownerId);
		if (owner != null && owner.getTeam() != null) missile.ownerTeam = owner.getTeam().getName();
		Vec3 local = MicroMissilePolicy.launchTube(shot);
		Vec3 origin = DroneHardpoints.worldPosition(launcher, local);
		missile.setPos(origin);
		missile.setXRot(-90.0f);
		missile.xRotO = -90.0f;
		missile.setYRot(launcher.getYRot());
		missile.yRotO = launcher.getYRot();
		missile.setDeltaMovement(MicroMissilePolicy.ejectionVelocity(missile.launchProfile, launcher.getDeltaMovement()));
		missile.explosionPower = MicroMissilePolicy.explosionPower(level.getRandom().nextFloat());
		if (!level.addFreshEntity(missile)) return false;
		level.playSound(null, origin.x, origin.y, origin.z, MorrowgearDrone.MISSILE_LAUNCH_SOUND,
			SoundSource.PLAYERS, 0.85f, 0.96f + level.getRandom().nextFloat() * 0.08f);
		return true;
	}

	static boolean launchStrike(ServerLevel level, DroneEntity launcher, Vec3 aim, int shot) {
		if (aim == null || !MicroMissilePolicy.finite(aim)) return false;
		MorrowgearMissileEntity missile = MorrowgearDrone.MISSILE.create(level, EntitySpawnReason.TRIGGERED);
		if (missile == null) return false;
		missile.launcherId = launcher.getUUID();
		missile.ownerId = launcher.ownerId();
		missile.entityData.set(OWNER, missile.ownerId.toString());
		missile.launchProfile = new MicroMissilePolicy.LaunchProfile(shot, launcher.getYRot());
		missile.lastAim = aim;
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(missile.ownerId);
		if (owner != null && owner.getTeam() != null) missile.ownerTeam = owner.getTeam().getName();
		Vec3 origin = DroneHardpoints.worldPosition(launcher, MicroMissilePolicy.launchTube(shot));
		missile.setPos(origin);
		missile.setXRot(-90.0f);
		missile.xRotO = -90.0f;
		missile.setYRot(launcher.getYRot());
		missile.yRotO = launcher.getYRot();
		missile.setDeltaMovement(MicroMissilePolicy.ejectionVelocity(missile.launchProfile,
			launcher.getDeltaMovement()));
		missile.explosionPower = MicroMissilePolicy.explosionPower(level.getRandom().nextFloat());
		if (!level.addFreshEntity(missile)) return false;
		level.playSound(null, origin.x, origin.y, origin.z, MorrowgearDrone.MISSILE_LAUNCH_SOUND,
			SoundSource.PLAYERS, 0.85f, 0.94f + level.getRandom().nextFloat() * 0.08f);
		return true;
	}

	public static boolean launchCarrier(ServerLevel level, CarrierEntity launcher, ServerPlayer owner,
		LivingEntity target, int hardpoint, int cell) {
		MorrowgearMissileEntity missile = MorrowgearDrone.MISSILE.create(level, EntitySpawnReason.TRIGGERED);
		if (missile == null) return false;
		missile.launcherId=launcher.getUUID(); missile.ownerId=owner.getUUID(); missile.targetUuid=target.getUUID();
		missile.entityData.set(OWNER,missile.ownerId.toString());
		missile.entityData.set(HEAVY,true); missile.entityData.set(TARGET,target.getId());
		missile.lastAim=target.position().add(0,target.getBbHeight()*.5,0);
		missile.ownerTeam=owner.getTeam()==null?"":owner.getTeam().getName();
		var tube=CarrierHeavyMissilePolicy.launchTube(hardpoint,cell);
		Vec3 origin=launcher.toWorld(new Vec3(tube.x(),tube.y(),tube.z()));
		missile.setPos(origin); missile.setXRot(-90); missile.xRotO=-90;
		missile.setYRot(launcher.getYRot()); missile.yRotO=launcher.getYRot();
		missile.setDeltaMovement(0,.52,0); missile.explosionPower=CarrierHeavyMissilePolicy.EXPLOSION_POWER;
		return level.addFreshEntity(missile);
	}

	public int targetId() { return entityData.get(TARGET); }
	public boolean ownedBy(UUID owner) { return owner != null && owner.toString().equals(entityData.get(OWNER)); }
	public boolean impacted() { return entityData.get(IMPACTED); }
	public boolean motorIgnited() { return entityData.get(MOTOR_IGNITED); }
	public boolean heavy() { return entityData.get(HEAVY); }
	public FlightPhase flightPhase() { return FlightPhase.values()[Mth.clamp(entityData.get(FLIGHT_PHASE), 0, 4)]; }
	public java.util.List<MissileTrailHistory.Point> visualTrail() {
		return visualTrail == null ? java.util.List.of() : visualTrail.points();
	}

	public Vec3 attachedRenderPosition(float partialTick) {
		Entity host = level().getEntity(entityData.get(ATTACHED_HOST));
		if (!impacted() || host == null || !host.isAlive()) return null;
		Vector3fc offset = entityData.get(ATTACHED_OFFSET);
		Vec3 next = MicroMissilePolicy.attachmentPosition(new Vec3(offset.x(), offset.y(), offset.z()),
			host.getPosition(partialTick), Mth.rotLerp(partialTick, host.yRotO, host.getYRot()));
		return MicroMissilePolicy.mayFollowAttachment(position(), next) ? next : null;
	}

	@Override
	public InterpolationHandler getInterpolation() { return interpolation; }

	@Override
	public boolean shouldRenderAtSqrDistance(double distanceSquared) {
		return MicroMissilePolicy.renderVisible(distanceSquared, Entity.getViewScale());
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(TARGET, -1);
		builder.define(IMPACTED, false);
		builder.define(MOTOR_IGNITED, false);
		builder.define(FLIGHT_PHASE, 0);
		builder.define(HEAVY, false);
		builder.define(OWNER, "");
		builder.define(ATTACHED_HOST, -1);
		builder.define(ATTACHED_OFFSET, new Vector3f());
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) {
			interpolation.interpolate();
			if (visualTrail == null) visualTrail = new MissileTrailHistory();
			Vec3 exhaust = position().add(Vec3.directionFromRotation(getXRot(), getYRot()).scale(EXHAUST_OFFSET));
			visualTrail.tick(exhaust, level().getGameTime(), motorIgnited() && !impacted());
			return;
		}
		if (!(level() instanceof ServerLevel level)) return;
		if (detonated) return;
		if (fuseTicks >= 0) {
			followAttachment(level);
			setDeltaMovement(Vec3.ZERO);
			fuseTicks = MicroMissilePolicy.nextFuse(fuseTicks);
			if (fuseTicks == 0 && (heavy() ? MissileDetonationRuntime.tryAcquireHeavy(level)
				: MissileDetonationRuntime.tryAcquire(level))) detonate(level);
			return;
		}
		if(heavy()) { tickHeavy(level); return; }
		if (++flightTicks > MicroMissilePolicy.MAX_FLIGHT_TICKS) {
			discard();
			return;
		}
		entityData.set(FLIGHT_PHASE, MicroMissilePolicy.flightPhase(flightTicks, false));
		if (flightTicks == MicroMissilePolicy.IGNITION_TICK) {
			entityData.set(MOTOR_IGNITED, true);
			level.playSound(null, getX(), getY(), getZ(), MorrowgearDrone.MISSILE_IGNITION_SOUND,
				SoundSource.PLAYERS, 0.42f, 1.0f);
		}
		// Runtime IDs are only a client hint. Reloads and ID reuse cannot change the target.
		Entity target = targetUuid == null ? null : level.getEntity(targetUuid);
		if (target instanceof LivingEntity living && living.isAlive() && canDamage(level, living)) {
			lastAim = living.position().add(0, living.getBbHeight() * 0.55, 0);
			entityData.set(TARGET, living.getId());
		} else entityData.set(TARGET, -1);
		if (lastAim == null || !MicroMissilePolicy.finite(position())
			|| !MicroMissilePolicy.finite(getDeltaMovement())) {
			discard();
			return;
		}
		MicroMissilePolicy.FlightStep guidance = MicroMissilePolicy.flightStep(position(), getDeltaMovement(),
			lastAim, flightTicks, terminalGuidance, passedTarget, launchProfile);
		terminalGuidance = guidance.terminal();
		passedTarget = guidance.passedTarget();
		Vec3 velocity = guidance.velocity();
		setDeltaMovement(velocity);
		// Cold attitude control points the capsule before its motor changes the flight vector.
		Vec3 facing = guidance.facing();
		if (facing.horizontalDistanceSqr() > 1.0e-8)
			setYRot((float)(Mth.atan2(facing.z, facing.x) * 180.0 / Math.PI) - 90.0f);
		setXRot((float)(-Mth.atan2(facing.y, facing.horizontalDistance()) * 180.0 / Math.PI));
		AABB sweep = getBoundingBox().expandTowards(velocity).inflate(1.0e-5);
		if (!loaded(level, sweep.inflate(1)) || !level.getWorldBorder().isWithinBounds(sweep)) {
			discard();
			return;
		}
		Vec3 from = getBoundingBox().getCenter();
		Vec3 to = from.add(velocity);
		Vec3 halfSize = new Vec3(getBbWidth() * 0.5, getBbHeight() * 0.5, getBbWidth() * 0.5);
		double nearest = Double.POSITIVE_INFINITY;
		for (var shape : level.getBlockCollisions(this, sweep)) {
			for (AABB box : shape.toAabbs())
				nearest = Math.min(nearest, MicroMissilePolicy.sweepFraction(from, to, box, halfSize));
		}
		Entity struck = null;
		for (Entity candidate : level.getEntities(this, sweep, entity -> entity.isAlive()
			&& !entity.isSpectator() && !(entity instanceof MorrowgearMissileEntity)
			&& (entity instanceof LivingEntity ? canDamage(level, entity) : entity.canBeHitByProjectile()))) {
			double fraction = MicroMissilePolicy.sweepFraction(from, to, candidate.getBoundingBox(), halfSize);
			// Block wins ties, so entities behind cover cannot be hit through the wall.
			if (fraction < nearest) { nearest = fraction; struck = candidate; }
		}
		if (nearest <= 1.0) {
			setPos(position().add(velocity.scale(Math.max(0, nearest - 1.0e-5))));
			impact(level, struck);
		} else setPos(position().add(velocity));
	}

	private void tickHeavy(ServerLevel level) {
		if(++flightTicks>CarrierHeavyMissilePolicy.maxFlightTicks(level.getHeight())){discard();return;}
		Entity found=targetUuid==null?null:level.getEntity(targetUuid);
		LivingEntity target=found instanceof LivingEntity living&&living.isAlive()&&canDamage(level,living)?living:null;
		if(target!=null){lastAim=target.position().add(0,target.getBbHeight()*.5,0);entityData.set(TARGET,target.getId());}
		else entityData.set(TARGET,-1);
		if(lastAim==null){discard();return;}
		Vec3 velocity;
		if(flightTicks<=CarrierHeavyMissilePolicy.EJECT_TICKS){
			entityData.set(FLIGHT_PHASE,FlightPhase.EJECT.ordinal()); velocity=new Vec3(0,.52,0);
			if(flightTicks==1)level.playSound(null,getX(),getY(),getZ(),MorrowgearDrone.CARRIER_MISSILE_EJECT_SOUND,
				SoundSource.PLAYERS,.9f,.94f);
		}else if(flightTicks<=CarrierHeavyMissilePolicy.IGNITION_TICKS){
			entityData.set(FLIGHT_PHASE,FlightPhase.BOOST.ordinal()); velocity=new Vec3(0,1.05,0);
			if(flightTicks==CarrierHeavyMissilePolicy.EJECT_TICKS+1){entityData.set(MOTOR_IGNITED,true);
				level.playSound(null,getX(),getY(),getZ(),MorrowgearDrone.CARRIER_MISSILE_IGNITION_SOUND,
					SoundSource.PLAYERS,1,.94f);}
		}else if(flightTicks<=CarrierHeavyMissilePolicy.CLIMB_TICKS){
			entityData.set(FLIGHT_PHASE,FlightPhase.BOOST.ordinal());
			velocity=CarrierHeavyMissilePolicy.climbVelocity(position(),lastAim,flightTicks);
		}else{
			entityData.set(FLIGHT_PHASE,FlightPhase.GUIDED.ordinal());
			Vec3 aim=lastAim.subtract(position());
			velocity=CarrierHeavyMissilePolicy.guidedVelocity(getDeltaMovement(),aim,flightTicks);
		}
		setDeltaMovement(velocity); orient(velocity);
		Vec3 next=position().add(velocity); AABB sweep=getBoundingBox().expandTowards(velocity).inflate(.3);
		if(!loaded(level,sweep.inflate(1))||!level.getWorldBorder().isWithinBounds(sweep)){discard();return;}
		BlockHitResult hit=level.clip(new net.minecraft.world.level.ClipContext(position(),next,
			net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.ANY,this));
		double path=Math.max(1.0e-8,velocity.length());
		double blockFraction=hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS?Double.POSITIVE_INFINITY
			:position().distanceTo(hit.getLocation())/path;
		double targetFraction=target==null?Double.POSITIVE_INFINITY:MicroMissilePolicy.sweepFraction(position(),next,
			target.getBoundingBox(),new Vec3(getBbWidth()*.5,getBbHeight()*.5,getBbWidth()*.5));
		if(target!=null&&targetFraction<blockFraction&&targetFraction<=1.0){
			target.invulnerableTime=0; target.hurtServer(level,level.damageSources().explosion(this,level.getEntity(launcherId)),
				CarrierHeavyMissilePolicy.DIRECT_DAMAGE); setPos(position().add(velocity.scale(Math.max(0,targetFraction))));
			heavyImpact(level); return;
		}
		if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.MISS){
			BlockPos pos=hit.getBlockPos(); BlockState state=level.getBlockState(pos);
			if(mayBreakBlock(level,pos,state)){
				heavyPenetrationEffect(level,hit.getLocation(),penetrationBlocks==0);
				level.setBlock(pos,Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
				penetrationBlocks++; setPos(hit.getLocation().add(velocity.normalize().scale(.55)));
				if(penetrationBlocks%4==1)level.playSound(null,getX(),getY(),getZ(),MorrowgearDrone.CARRIER_MISSILE_PENETRATION_SOUND,
					SoundSource.PLAYERS,.72f,.88f+level.getRandom().nextFloat()*.08f);
				return;
			}
			setPos(hit.getLocation()); heavyImpact(level); return;
		}
		setPos(next);
	}

	private void heavyPenetrationEffect(ServerLevel level,Vec3 contact,boolean firstContact){
		if(firstContact)level.sendParticles(ParticleTypes.EXPLOSION_EMITTER,contact.x,contact.y,contact.z,
			1,0,0,0,0);
		else level.sendParticles(ParticleTypes.EXPLOSION,contact.x,contact.y,contact.z,
			1,.08,.08,.08,0);
		level.sendParticles(ParticleTypes.POOF,contact.x,contact.y,contact.z,
			firstContact?18:6,.35,.28,.35,firstContact?.12:.05);
		level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,contact.x,contact.y,contact.z,
			firstContact?12:4,.22,.18,.22,.035);
	}

	private void orient(Vec3 facing){
		if(facing.horizontalDistanceSqr()>1e-8)setYRot((float)(Mth.atan2(facing.z,facing.x)*180/Math.PI)-90);
		setXRot((float)(-Mth.atan2(facing.y,facing.horizontalDistance())*180/Math.PI));
	}

	private void heavyImpact(ServerLevel level){
		fuseTicks=CarrierHeavyMissilePolicy.FUSE_TICKS; entityData.set(IMPACTED,true);
		entityData.set(FLIGHT_PHASE,FlightPhase.IMPACT.ordinal()); setDeltaMovement(Vec3.ZERO);
		level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,getX(),getY(),getZ(),10,.24,.18,.24,.025);
	}

	private void impact(ServerLevel level, Entity struck) {
		if (fuseTicks >= 0 || detonated) return;
		fuseTicks = MicroMissilePolicy.IMPACT_FUSE_TICKS;
		entityData.set(IMPACTED, true);
		entityData.set(FLIGHT_PHASE, MicroMissilePolicy.flightPhase(flightTicks, true));
		if (struck instanceof LivingEntity) {
			Vec3 local = MicroMissilePolicy.attachmentLocal(position(), struck.position(), struck.getYRot());
			attachment = new MicroMissilePolicy.Attachment(struck.getUUID(), local,
				Mth.wrapDegrees(getYRot() - struck.getYRot()));
			entityData.set(ATTACHED_HOST, struck.getId());
			entityData.set(ATTACHED_OFFSET, new Vector3f((float)local.x, (float)local.y, (float)local.z));
		}
		setDeltaMovement(Vec3.ZERO);
		level.playSound(null, getX(), getY(), getZ(), MorrowgearDrone.MISSILE_IMPACT_SOUND,
			SoundSource.PLAYERS, 0.75f, 0.95f + level.getRandom().nextFloat() * 0.1f);
		level.sendParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 5, 0.08, 0.08, 0.08, 0.015);
	}

	private void followAttachment(ServerLevel level) {
		if (attachment == null) return;
		Entity host = level.getEntity(attachment.host());
		Vec3 next = host == null ? position()
			: MicroMissilePolicy.attachmentPosition(attachment.local(), host.position(), host.getYRot());
		if (host == null || !host.isAlive() || !MicroMissilePolicy.mayFollowAttachment(position(), next)
			|| !level.hasChunkAt(BlockPos.containing(next)) || !level.getWorldBorder().isWithinBounds(BlockPos.containing(next))) {
			attachment = null;
			entityData.set(ATTACHED_HOST, -1);
			return;
		}
		entityData.set(ATTACHED_HOST, host.getId());
		Vec3 local = attachment.local();
		entityData.set(ATTACHED_OFFSET, new Vector3f((float)local.x, (float)local.y, (float)local.z));
		setPos(next);
		setYRot(host.getYRot() + attachment.yawOffset());
	}

	private boolean canDamage(ServerLevel level, Entity entity) {
		if (!(entity instanceof LivingEntity living) || !living.isAlive() || entity.isSpectator()
			|| entity instanceof DroneEntity
			|| entity.getUUID().equals(launcherId) || entity.getUUID().equals(ownerId)
			|| entity.getType().builtInRegistryHolder().is(MorrowgearDrone.COMPAT_FRIENDLY_ENTITIES)) return false;
		if (entity instanceof OwnableEntity ownable && ownable.getOwnerReference() != null
			&& ownable.getOwnerReference().getUUID().equals(ownerId)) return false;
		Entity owner = ownerId == null ? null : level.getEntity(ownerId);
		Entity launcher = launcherId == null ? null : level.getEntity(launcherId);
		if (entity instanceof Player player) {
			if (!(owner instanceof ServerPlayer ownerPlayer) || !MicroMissilePolicy.mayDamagePlayer(
				entity.getUUID().equals(targetUuid), level.isPvpAllowed(), ownerPlayer.canHarmPlayer(player),
				ThreatAssessment.targetDisposition(living, ownerPlayer, ownerPlayer.getLastHurtByMob()))) return false;
		}
		if (owner != null && (entity.isAlliedTo(owner) || owner.isAlliedTo(entity))) return false;
		if (owner == null && !ownerTeam.isEmpty() && entity.getTeam() != null
			&& ownerTeam.equals(entity.getTeam().getName())) return false;
		return launcher == null || (!entity.isAlliedTo(launcher) && !launcher.isAlliedTo(entity));
	}

	private boolean mayBreakBlock(ServerLevel level, BlockPos pos, BlockState state) {
		ServerPlayer owner = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
		return MicroMissilePolicy.mayBreakBlock(level.getGameRules().get(GameRules.MOB_GRIEFING),
			owner != null && owner.mayBuild() && level.mayInteract(owner, pos),
			state.hasBlockEntity() || level.getBlockEntity(pos) != null, state.getDestroySpeed(level, pos),
			state.getBlock() instanceof DockPartBlock || state.getBlock() instanceof DockCenterBlock
				|| state.is(PROTECTED_BLOCKS) || state.is(Blocks.TNT));
	}

	private void detonate(ServerLevel level) {
		if (detonated) return;
		detonated = true;
		AABB blast = getBoundingBox().inflate(explosionPower * 2.0 + 1.0);
		// Never generate/load chunks as a side effect of a missile explosion.
		if (!loaded(level, blast)) {
			discard();
			return;
		}
		Entity launcher = launcherId == null ? null : level.getEntity(launcherId);
		DamageSource source = level.damageSources().explosion(this, launcher);
		ExplosionDamageCalculator calculator = new ExplosionDamageCalculator() {
			@Override public float getEntityDamageAmount(Explosion explosion, Entity entity, float exposure) {
				float damage=super.getEntityDamageAmount(explosion, entity, exposure);
				return heavy()?CarrierHeavyMissilePolicy.explosionEntityDamage(damage)
					:MicroMissilePolicy.entityDamage(damage);
			}
			@Override public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
				return canDamage(level, entity);
			}
			@Override public float getKnockbackMultiplier(Entity entity) {
				return canDamage(level, entity) ? 1.0f : 0.0f;
			}
			@Override public boolean shouldBlockExplode(Explosion explosion, BlockGetter blocks,
				BlockPos pos, BlockState state, float strength) {
				return mayBreakBlock(level, pos, state)
					&& super.shouldBlockExplode(explosion, blocks, pos, state, strength);
			}
			@Override public Optional<Float> getBlockExplosionResistance(Explosion explosion, BlockGetter blocks,
				BlockPos pos, BlockState state, FluidState fluid) {
				if (!state.isAir() && !mayBreakBlock(level, pos, state)) return Optional.of(3_600_000.0f);
				return super.getBlockExplosionResistance(explosion, blocks, pos, state, fluid);
			}
		};
		Map<LivingEntity, Integer> cooldowns = new HashMap<>();
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, blast,
			entity -> canDamage(level, entity))) {
			cooldowns.put(victim, victim.invulnerableTime);
			victim.invulnerableTime = 0;
		}
		try {
			level.explode(this, source, calculator, getX(), getY(), getZ(), explosionPower, false,
				Level.ExplosionInteraction.MOB, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER,
				WeightedList.of(), Holder.direct(heavy()?MorrowgearDrone.CARRIER_MISSILE_EXPLOSION_SOUND:MorrowgearDrone.MISSILE_EXPLOSION_SOUND));
			MissileAudioController.detonated(level, getUUID(), position(), 1);
		} finally {
			cooldowns.forEach((victim, before) -> victim.invulnerableTime =
				MorrowgearCombatDamage.preservedCooldown(before, victim.invulnerableTime));
			discard();
		}
	}

	private static boolean loaded(ServerLevel level, AABB bounds) {
		return level.hasChunksAt(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
			BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ));
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		// An armed impact fuse cannot be erased by another missile in the same salvo.
		if (fuseTicks >= 0) return false;
		discard();
		return true;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		new MicroMissilePolicy.SavedState(launcherId, ownerId, targetUuid, ownerTeam, lastAim,
			flightTicks, fuseTicks, explosionPower, terminalGuidance, passedTarget).write(output);
		launchProfile.write(output);
		output.putBoolean("CarrierHeavy",heavy()); output.putInt("CarrierPenetration",penetrationBlocks);
		if (attachment != null && fuseTicks >= 0) attachment.write(output);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		MicroMissilePolicy.SavedState saved = MicroMissilePolicy.SavedState.read(input);
		launcherId = saved.launcher();
		ownerId = saved.owner();
		entityData.set(OWNER,ownerId==null?"":ownerId.toString());
		targetUuid = saved.target();
		ownerTeam = saved.ownerTeam();
		lastAim = saved.lastAim();
		flightTicks = saved.flightTicks();
		launchProfile = MicroMissilePolicy.LaunchProfile.read(input);
		entityData.set(HEAVY,input.getBooleanOr("CarrierHeavy",false));
		penetrationBlocks=input.getIntOr("CarrierPenetration",0);
		fuseTicks = saved.fuseTicks();
		if(heavy()){
			entityData.set(MOTOR_IGNITED,flightTicks>CarrierHeavyMissilePolicy.EJECT_TICKS&&fuseTicks<0);
			entityData.set(FLIGHT_PHASE,fuseTicks>=0?FlightPhase.IMPACT.ordinal()
				:flightTicks<=CarrierHeavyMissilePolicy.EJECT_TICKS?FlightPhase.EJECT.ordinal()
				:flightTicks<=CarrierHeavyMissilePolicy.CLIMB_TICKS?FlightPhase.BOOST.ordinal():FlightPhase.GUIDED.ordinal());
		}else{
			entityData.set(MOTOR_IGNITED, MicroMissilePolicy.motorIgnited(flightTicks));
			entityData.set(FLIGHT_PHASE, MicroMissilePolicy.flightPhase(flightTicks, fuseTicks >= 0));
		}
		float storedPower=input.getFloatOr("ExplosionPower",heavy()?CarrierHeavyMissilePolicy.EXPLOSION_POWER
			:MicroMissilePolicy.MIN_EXPLOSION_POWER);
		explosionPower = heavy()?Mth.clamp(storedPower,CarrierHeavyMissilePolicy.EXPLOSION_POWER,
			CarrierHeavyMissilePolicy.EXPLOSION_POWER):saved.explosionPower();
		terminalGuidance = saved.terminal();
		passedTarget = saved.passedTarget();
		attachment = fuseTicks >= 0 ? MicroMissilePolicy.Attachment.read(input) : null;
		entityData.set(TARGET, -1);
		entityData.set(IMPACTED, fuseTicks >= 0);
		if (fuseTicks >= 0) setDeltaMovement(Vec3.ZERO);
	}
}
