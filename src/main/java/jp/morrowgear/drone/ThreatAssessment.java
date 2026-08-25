package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class ThreatAssessment {
	private static final Map<UUID, CachedSnapshot> CACHE = new HashMap<>();

	private ThreatAssessment() {
	}

	static Snapshot assess(ServerLevel level, ServerPlayer owner) {
		CachedSnapshot cached = CACHE.get(owner.getUUID());
		long tick = level.getGameTime();
		if (cached != null && cached.tick == tick) return cached.snapshot;
		Snapshot snapshot = calculate(level, owner);
		CACHE.put(owner.getUUID(), new CachedSnapshot(tick, snapshot));
		return snapshot;
	}

	private static Snapshot calculate(ServerLevel level, ServerPlayer owner) {
		LivingEntity recentAttacker = owner.tickCount - owner.getLastHurtByMobTimestamp() <= 200
			? owner.getLastHurtByMob() : null;
		List<LivingEntity> threats = level.getEntitiesOfClass(LivingEntity.class,
			owner.getBoundingBox().inflate(24.0, 12.0, 24.0), entity -> isThreat(entity, owner, recentAttacker));

		int score = 0;
		int enemyThreat = 0;
		int playerDanger = 0;
		LivingEntity primary = null;
		int primaryScore = Integer.MIN_VALUE;
		boolean direct = false;
		for (LivingEntity threat : threats) {
			boolean recent = threat == recentAttacker;
			boolean attackingOwner = threat instanceof Mob mob && mob.getTarget() == owner;
			int entityThreat = entityThreat(threat);
			int entityDanger = EnemyThreatPolicy.playerDanger(entityThreat, threat.distanceTo(owner),
				attackingOwner, recent, false);
			int individual = entityThreat + entityDanger;
			score += individual;
			enemyThreat = Math.max(enemyThreat, entityThreat);
			playerDanger = Math.max(playerDanger, entityDanger);
			if (individual > primaryScore) {
				primaryScore = individual;
				primary = threat;
			}
			direct |= attackingOwner || recent;
		}
		if (threats.size() > 1) {
			int crowd = Math.min(20, (threats.size() - 1) * 3);
			enemyThreat = Mth.clamp(enemyThreat + crowd, 0, 100);
			playerDanger = Mth.clamp(playerDanger + crowd / 2, 0, 100);
		}

		IncomingProjectile incoming = level.getEntitiesOfClass(Projectile.class,
			owner.getBoundingBox().inflate(32.0), projectile -> projectile.getOwner() != owner)
			.stream().map(projectile -> predictImpact(projectile, owner))
			.filter(prediction -> prediction != null)
			.min(Comparator.comparingDouble(IncomingProjectile::ticksToImpact)).orElse(null);
		Vec3 threatPosition = primary == null ? owner.position().add(owner.getLookAngle().multiply(1, 0, 1).scale(6.0)) : primary.position();
		Vec3 intercept = null;
		if (incoming != null) {
			playerDanger = Mth.clamp(playerDanger + 24, 0, 100);
			direct = true;
			threatPosition = incoming.projectile.position();
			if (primary == null && incoming.projectile.getOwner() instanceof LivingEntity source
				&& source != owner && source.isAlive()) {
				primary = source;
				enemyThreat = Math.max(enemyThreat, entityThreat(source));
			}
			Vec3 towardProjectile = incoming.projectile.position().subtract(owner.position()).multiply(1, 0, 1);
			if (towardProjectile.lengthSqr() < 0.001) towardProjectile = new Vec3(0, 0, 1);
			double interceptY = Math.max(owner.getY() + 0.05,
				Math.min(owner.getEyeY() + 0.15, incoming.impactPoint.y - 0.45));
			Vec3 horizontalAnchor = owner.position().add(towardProjectile.normalize().scale(1.65));
			intercept = new Vec3(horizontalAnchor.x, interceptY, horizontalAnchor.z);
		}

		int environmental = environmentalRisk(level, owner.blockPosition());
		playerDanger = Mth.clamp(playerDanger + environmental, 0, 100);
		score = Mth.clamp((int)Math.ceil(enemyThreat * 0.72 + playerDanger * 0.58), 0, 100);
		int enemyCount = threats.size();
		if (primary != null && !threats.contains(primary)) enemyCount++;
		return new Snapshot(score, ThreatBand.fromScore(score), threatPosition, intercept, direct,
			primary == null ? -1 : primary.getId(), enemyCount, environmental, enemyThreat, playerDanger);
	}

	static boolean isThreat(LivingEntity entity, ServerPlayer owner, LivingEntity recentAttacker) {
		return targetDisposition(entity, owner, recentAttacker).engageable();
	}

	static TargetDisposition targetDisposition(LivingEntity entity, ServerPlayer owner,
		LivingEntity recentAttacker) {
		boolean configuredFriendly = entity.getType().builtInRegistryHolder()
			.is(MorrowgearDrone.COMPAT_FRIENDLY_ENTITIES);
		boolean configuredHostile = entity.getType().builtInRegistryHolder()
			.is(MorrowgearDrone.COMPAT_HOSTILE_ENTITIES);
		return TargetTrackingPolicy.classify(new TargetTrackingPolicy.Factors(
			entity.isAlive(), entity == owner, entity instanceof DroneEntity,
			configuredFriendly || entity.isAlliedTo(owner) || owner.isAlliedTo(entity), false,
			configuredHostile || entity instanceof Enemy, entity instanceof Mob mob && mob.getTarget() == owner,
			entity == recentAttacker));
	}

	static int entityThreat(LivingEntity entity) {
		var attackAttribute = entity.getAttribute(Attributes.ATTACK_DAMAGE);
		var armorAttribute = entity.getAttribute(Attributes.ARMOR);
		var speedAttribute = entity.getAttribute(Attributes.MOVEMENT_SPEED);
		double attack = attackAttribute == null ? 0.0 : attackAttribute.getValue();
		double armor = armorAttribute == null ? 0.0 : armorAttribute.getValue();
		double speed = speedAttribute == null ? 0.0 : speedAttribute.getValue();
		int special = specialRisk(entity);
		return EnemyThreatPolicy.assess(new EnemyThreatPolicy.Factors(entity.getMaxHealth(), attack,
			armor, speed, special)).score();
	}

	private static int specialRisk(LivingEntity entity) {
		if (entity.getType().builtInRegistryHolder().is(MorrowgearDrone.COMPAT_HIGH_VALUE_ENTITIES)) return 20;
		if (entity instanceof EnderDragon || entity instanceof WitherBoss) return 18;
		if (entity instanceof Warden) return 16;
		if (entity instanceof Ravager) return 9;
		if (entity instanceof Ghast || entity instanceof Creeper) return 8;
		if (entity instanceof Blaze || entity instanceof Phantom) return 6;
		if (entity instanceof Pillager || entity instanceof AbstractSkeleton || entity instanceof Drowned) return 5;
		if (entity instanceof Zombie || entity instanceof Spider) return 2;
		return 0;
	}

	private static IncomingProjectile predictImpact(Projectile projectile, ServerPlayer owner) {
		Entity projectileOwner = projectile.getOwner();
		if (projectileOwner instanceof DroneEntity || projectileOwner == owner) return null;
		Vec3 velocity = projectile.getDeltaMovement();
		double speedSquared = velocity.lengthSqr();
		if (speedSquared < 0.0004) return null;
		double gravity = projectile instanceof AbstractArrow ? 0.05 : 0.03;
		ProjectileDefense.TrajectoryPrediction prediction = ProjectileDefense.predictClosestToVerticalTarget(
			projectile.position(), velocity, owner.getX(), owner.getZ(), owner.getY(), owner.getEyeY(),
			gravity, 0.99, 40);
		return prediction.missDistance() <= 2.4
			? new IncomingProjectile(projectile, prediction.ticks(), prediction.closestPoint()) : null;
	}

	private static int environmentalRisk(ServerLevel level, BlockPos ownerPos) {
		int risk = 0;
		if (level.isDarkOutside()) risk += 2;
		if (level.getBrightness(LightLayer.BLOCK, ownerPos) <= 7) risk += 2;
		if (!level.canSeeSky(ownerPos)) risk += 2;
		int spawnable = 0;
		int[] offsets = {-16, -8, 0, 8, 16};
		for (int x : offsets) {
			for (int z : offsets) {
				if (x == 0 && z == 0) continue;
				int worldX = ownerPos.getX() + x;
				int worldZ = ownerPos.getZ() + z;
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
				BlockPos candidate = new BlockPos(worldX, y, worldZ);
				if (level.getBlockState(candidate).isAir() && level.getBlockState(candidate.above()).isAir()
					&& level.getBrightness(LightLayer.BLOCK, candidate) <= 7) spawnable++;
			}
		}
		return risk + Math.min(4, spawnable / 5);
	}

	record Snapshot(int score, ThreatBand band, Vec3 threatPosition, Vec3 interceptPosition,
		boolean directThreat, int primaryEntityId, int enemyCount, int environmentalRisk,
		int enemyThreat, int playerDanger) {
		Snapshot(int score, ThreatBand band, Vec3 threatPosition, Vec3 interceptPosition,
			boolean directThreat, int primaryEntityId, int enemyCount, int environmentalRisk) {
			this(score, band, threatPosition, interceptPosition, directThreat, primaryEntityId,
				enemyCount, environmentalRisk, score, directThreat ? score : 0);
		}
	}

	private record IncomingProjectile(Projectile projectile, double ticksToImpact, Vec3 impactPoint) {
	}

	private record CachedSnapshot(long tick, Snapshot snapshot) {
	}
}
