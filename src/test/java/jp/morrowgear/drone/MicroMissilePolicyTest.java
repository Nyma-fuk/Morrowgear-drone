package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class MicroMissilePolicyTest {
	private static final Vec3 HALF_SIZE = new Vec3(0.14, 0.14, 0.14);

	@Test void eachMissileHalvesEntityDamageWithoutChangingBlastRadius() {
		for (float damage : new float[] {0, 1, 8.5f, 20, 65, 500})
			assertEquals(damage / 2.0f, MicroMissilePolicy.entityDamage(damage));
		assertEquals(0, MicroMissilePolicy.entityDamage(-10));
		assertEquals(0, MicroMissilePolicy.entityDamage(Float.NaN));
		assertEquals(0, MicroMissilePolicy.entityDamage(Float.POSITIVE_INFINITY));
		assertEquals(4.0f, MicroMissilePolicy.MIN_EXPLOSION_POWER);
		assertEquals(5.0f, MicroMissilePolicy.MAX_EXPLOSION_POWER);
	}

	@Test void multiLockKeepsMagazineCapacityHatchDelayAndShotInterval() {
		assertEquals(45, CombatPolicy.MISSILE_CAPACITY);
		assertEquals(15, MicroMissilePolicy.POWER_PER_MISSILE);
		assertEquals(8, MicroMissilePolicy.HATCH_OPEN_TICKS);
		assertEquals(2, MicroMissilePolicy.SALVO_INTERVAL_TICKS);
	}

	@Test void dorsalHardpointsMatchApprovedMeshAndAlternateSides() {
		double[] rows = {0.348, 0.213, 0.078, -0.04};
		for (int i = 0; i < 8; i++) {
			Vec3 tube = MicroMissilePolicy.launchTube(i);
			assertEquals(i % 2 == 0 ? -0.1146 : 0.1146, tube.x);
			assertEquals(0.56, tube.y);
			assertEquals(rows[i / 2], tube.z);
		}
		assertEquals(MicroMissilePolicy.launchTube(0), MicroMissilePolicy.launchTube(8));
	}

	@Test void firstShotWaitsForOpenHatchAndSubsequentShotsHaveShortSpacing() {
		long next = 108;
		int shots = 0;
		for (long tick = 100; tick <= 116; tick++) {
			if (!MicroMissilePolicy.launchReady(tick - 100, tick, next)) continue;
			assertEquals(108 + shots * 2, tick);
			shots++;
			next = tick + MicroMissilePolicy.SALVO_INTERVAL_TICKS;
		}
		assertEquals(5, shots);
		assertFalse(MicroMissilePolicy.launchReady(7, 107, 0), "even a stale timer cannot skip the hatch delay");
	}

	@Test void sweptVolumeCatchesThinWallsEvenWhenBothEndpointsAreClear() {
		AABB wall = new AABB(5, -1, -1, 5.0625, 1, 1);
		assertEquals(0.486, MicroMissilePolicy.sweepFraction(Vec3.ZERO,
			new Vec3(10, 0, 0), wall, HALF_SIZE), 1.0e-8);
	}

	@Test void radiusGrazingAndCornerHitsCannotTunnelButNearMissesStayClear() {
		AABB obstacle = new AABB(2, 0.1, -1, 2.1, 1, 1);
		assertTrue(Double.isFinite(MicroMissilePolicy.sweepFraction(Vec3.ZERO,
			new Vec3(4, 0, 0), obstacle, HALF_SIZE)));
		assertEquals(Double.POSITIVE_INFINITY, MicroMissilePolicy.sweepFraction(Vec3.ZERO,
			new Vec3(4, 0, 0), obstacle.move(0, 0.1, 0), HALF_SIZE));
		AABB corner = new AABB(5, -0.1, 5, 5.05, 0.1, 5.05);
		assertTrue(Double.isFinite(MicroMissilePolicy.sweepFraction(Vec3.ZERO,
			new Vec3(10, 0, 10), corner, HALF_SIZE)));
	}

	@Test void overlappingLaunchAndZeroMovementAreHandledWithoutNan() {
		AABB box = new AABB(-1, -1, -1, 1, 1, 1);
		assertEquals(0, MicroMissilePolicy.sweepFraction(Vec3.ZERO, Vec3.ZERO, box, HALF_SIZE));
		assertEquals(Double.POSITIVE_INFINITY, MicroMissilePolicy.sweepFraction(Vec3.ZERO,
			Vec3.ZERO, box.move(5, 0, 0), HALF_SIZE));
	}

	@Test void nearestBlockOccludesAnEntityBehindIt() {
		Vec3 end = new Vec3(20, 0, 0);
		double wall = MicroMissilePolicy.sweepFraction(Vec3.ZERO, end,
			new AABB(4, -1, -1, 4.1, 1, 1), HALF_SIZE);
		double victim = MicroMissilePolicy.sweepFraction(Vec3.ZERO, end,
			new AABB(6, -1, -0.3, 6.6, 1, 0.3), HALF_SIZE);
		assertTrue(wall < victim);
		assertEquals(wall, Math.min(wall, victim));
		assertEquals(victim, Math.min(Double.POSITIVE_INFINITY, victim));
	}

	@Test void terminalCorrectionIsLimitedEvenForOppositeOrStationaryVelocity() {
		Vec3 forward = new Vec3(0, 0, 1);
		for (Vec3 aim : new Vec3[] {new Vec3(1, 0, 0), new Vec3(0, 0, -1), new Vec3(1, 1, 0)}) {
			Vec3 corrected = MicroMissilePolicy.turnToward(forward, aim,
				MicroMissilePolicy.TERMINAL_TURN_RADIANS);
			assertEquals(1, corrected.length(), 1.0e-8);
			assertTrue(Math.acos(forward.dot(corrected)) <= MicroMissilePolicy.TERMINAL_TURN_RADIANS + 1.0e-8);
		}
		assertTrue(MicroMissilePolicy.finite(MicroMissilePolicy.turnToward(Vec3.ZERO,
			new Vec3(0, -1, 0), MicroMissilePolicy.TERMINAL_TURN_RADIANS)));
	}

	@Test void targetDeathDoesNotPreventFlightToTheRetainedPoint() {
		Vec3 position = new Vec3(0, 6, 0);
		Vec3 velocity = new Vec3(0, 0.58, 0.2);
		Vec3 lastPoint = new Vec3(0, 0.7, 12);
		double closest = Double.POSITIVE_INFINITY;
		boolean reachedGround = false;
		boolean terminal = false, passed = false;
		for (int tick = 1; tick <= MicroMissilePolicy.MAX_FLIGHT_TICKS; tick++) {
			var step = MicroMissilePolicy.flightStep(position, velocity, lastPoint, tick, terminal, passed);
			velocity = step.velocity();
			terminal = step.terminal();
			passed = step.passedTarget();
			assertTrue(MicroMissilePolicy.finite(velocity));
			assertTrue(velocity.length() <= MicroMissilePolicy.TERMINAL_SPEED + 1.0e-8);
			position = position.add(velocity);
			closest = Math.min(closest, position.distanceTo(lastPoint));
			if (position.y <= 0) { reachedGround = true; break; }
		}
		assertTrue(closest < 4.0, "must approach the last known target point: " + closest);
		assertTrue(reachedGround, "a missed pass must terminate on terrain, not orbit indefinitely");
	}

	@Test void stationaryTargetsReceiveAccurateDescendingApproachesAcrossTheEngagementEnvelope() {
		int directHits = 0;
		for (double height : new double[] {8, 16, 34}) for (double range : new double[] {24, 32, 48, 64}) {
			String scenario = "range=" + range + " height=" + height;
			Vec3 position = new Vec3(0, height, 0);
			Vec3 velocity = new Vec3(0, 0.58, 0.2);
			Vec3 aim = new Vec3(0, 0.7, range);
			AABB target = new AABB(-0.3, 0, range - 0.3, 0.3, 1.8, range + 0.3);
			AABB ground = new AABB(-100, -1, -100, 100, 0, 100);
			boolean terminal = false, passed = false, groundHit = false, directHit = false;
			double closest = Double.POSITIVE_INFINITY;
			for (int tick = 1; tick <= MicroMissilePolicy.MAX_FLIGHT_TICKS; tick++) {
				var step = MicroMissilePolicy.flightStep(position, velocity, aim, tick, terminal, passed);
				if (step.terminal() && !terminal) {
					assertTrue(velocity.y < 0, scenario + " terminal must begin after the apex");
					assertTrue(velocity.normalize().dot(aim.subtract(position).normalize())
						>= MicroMissilePolicy.TERMINAL_ALIGNMENT_COS, scenario + " aligned attack axis");
				}
				velocity = step.velocity();
				terminal = step.terminal();
				passed = step.passedTarget();
				Vec3 fromCenter = position.add(0, HALF_SIZE.y, 0);
				Vec3 toCenter = fromCenter.add(velocity);
				directHit |= MicroMissilePolicy.sweepFraction(fromCenter, toCenter, target, HALF_SIZE) <= 1;
				double terrain = MicroMissilePolicy.sweepFraction(fromCenter, toCenter, ground, HALF_SIZE);
				position = position.add(velocity.scale(terrain <= 1 ? terrain : 1));
				closest = Math.min(closest, position.distanceTo(aim));
				if (terrain <= 1) {
					groundHit = true;
					assertTrue(position.subtract(aim).horizontalDistance() < 3.0, scenario + " nearby terrain impact");
					assertTrue(tick < 110, scenario + " must not orbit");
					break;
				}
			}
			assertTrue(terminal && groundHit, scenario + " descending ground contact");
			assertTrue(closest < 1.5, scenario + " closest=" + closest);
			if (directHit) directHits++;
		}
		assertTrue(directHits >= 9, "stationary targets must actually be hit, not merely circled: " + directHits);
	}

	@Test void terminalMissCannotReacquireAndOrbitEvenAfterLeavingTerminalRange() {
		var step = MicroMissilePolicy.flightStep(new Vec3(0, 4, 30), new Vec3(0, -0.2, 0.8),
			new Vec3(0, 0, 12), 40, true, true);
		assertTrue(step.passedTarget());
		assertTrue(step.velocity().z > 0);
		assertTrue(step.velocity().y < -0.2);
	}

	@Test void fuseCountsDownExactlyOnceAndNeverArmsItself() {
		assertEquals(-1, MicroMissilePolicy.nextFuse(-1));
		int fuse = MicroMissilePolicy.IMPACT_FUSE_TICKS;
		assertEquals(12, fuse);
		for (int tick = 1; tick <= 12; tick++) {
			fuse = MicroMissilePolicy.nextFuse(fuse);
			assertEquals(12 - tick, fuse);
		}
		assertEquals(0, MicroMissilePolicy.nextFuse(fuse));
	}

	@Test void explosionPowerUsesBoundedGamePowerNotAnExactTntSum() {
		assertEquals(4.0f, MicroMissilePolicy.explosionPower(0));
		assertEquals(5.0f, MicroMissilePolicy.explosionPower(1));
		assertTrue(Math.pow(MicroMissilePolicy.explosionPower(1) / 4, 3) <= 2.0);
		assertEquals(4.0f, MicroMissilePolicy.explosionPower(Float.NaN));
	}

	@Test void terrainProtectionDenialsAlwaysWin() {
		assertTrue(MicroMissilePolicy.mayBreakBlock(true, true, false, 1, false));
		assertFalse(MicroMissilePolicy.mayBreakBlock(false, true, false, 1, false));
		assertFalse(MicroMissilePolicy.mayBreakBlock(true, false, false, 1, false));
		assertFalse(MicroMissilePolicy.mayBreakBlock(true, true, true, 1, false));
		assertFalse(MicroMissilePolicy.mayBreakBlock(true, true, false, -1, false));
		assertFalse(MicroMissilePolicy.mayBreakBlock(true, true, false, 1, true));
	}

	@Test void onlyTheDesignatedHostilePlayerCanTakePvpDamage() {
		for (TargetDisposition disposition : TargetDisposition.values()) {
			assertEquals(disposition.engageable(), MicroMissilePolicy.mayDamagePlayer(true, true, true, disposition));
			assertFalse(MicroMissilePolicy.mayDamagePlayer(false, true, true, disposition));
			assertFalse(MicroMissilePolicy.mayDamagePlayer(true, false, true, disposition));
			assertFalse(MicroMissilePolicy.mayDamagePlayer(true, true, false, disposition));
		}
	}

	@Test void everyFuseStageAndStableIdentitySurviveNbtRoundTrip() {
		for (int fuse = -1; fuse <= MicroMissilePolicy.IMPACT_FUSE_TICKS; fuse++) {
			var state = new MicroMissilePolicy.SavedState(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "friendly", new Vec3(80.5, 64.2, -41.3), 37, fuse, 4.75f, true, true);
			TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
			state.write(output);
			CompoundTag tag = output.buildResult();
			assertFalse(tag.contains("Target"), "unstable entity IDs must never be persisted");
			assertEquals(state, read(tag));
		}
	}

	@Test void legacyIdsAndMalformedSaveDataCannotRetargetOrAmplifyExplosions() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Target", 1234);
		tag.putString("TargetUuid", "bad-uuid");
		tag.putInt("FlightTicks", Integer.MAX_VALUE);
		tag.putInt("ImpactFuse", Integer.MAX_VALUE);
		tag.putFloat("ExplosionPower", Float.POSITIVE_INFINITY);
		tag.putBoolean("HasLastAim", true);
		tag.putDouble("AimX", Double.NaN);
		var state = read(tag);
		assertNull(state.target());
		assertNull(state.lastAim());
		assertEquals(MicroMissilePolicy.MAX_FLIGHT_TICKS, state.flightTicks());
		assertEquals(12, state.fuseTicks());
		assertEquals(4.0f, state.explosionPower());
		assertEquals(-1, read(new CompoundTag()).fuseTicks());
	}

	@Test void attachmentFollowsTranslationAndRotationButNotTeleportation() {
		Vec3 host = new Vec3(100, 64, 200);
		Vec3 impact = host.add(0.3, 1.2, 0.4);
		for (float yaw : new float[] {-180, -90, 0, 45, 170}) {
			Vec3 local = MicroMissilePolicy.attachmentLocal(impact, host, yaw);
			assertTrue(impact.distanceTo(MicroMissilePolicy.attachmentPosition(local, host, yaw)) < 1e-6);
			Vec3 translated = MicroMissilePolicy.attachmentPosition(local, host.add(1, 0.2, 0), yaw);
			assertTrue(impact.add(1, 0.2, 0).distanceTo(translated) < 1e-6);
			Vec3 turned = MicroMissilePolicy.attachmentPosition(local, host, yaw + 90);
			assertEquals(impact.distanceTo(host), turned.distanceTo(host), 1e-6);
			assertTrue(MicroMissilePolicy.mayFollowAttachment(impact, turned));
		}
		assertFalse(MicroMissilePolicy.mayFollowAttachment(impact, impact.add(9, 0, 0)));
		assertFalse(MicroMissilePolicy.mayFollowAttachment(impact, new Vec3(Double.NaN, 0, 0)));
	}

	@Test void attachmentIdentitySurvivesSavingWithoutPersistingRuntimeEntityIds() {
		var attachment = new MicroMissilePolicy.Attachment(UUID.randomUUID(), new Vec3(.3, 1.2, -.4), 25);
		TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
		attachment.write(output);
		CompoundTag tag = output.buildResult();
		assertFalse(tag.contains("AttachedHostId"));
		assertEquals(attachment, MicroMissilePolicy.Attachment.read(TagValueInput.create(ProblemReporter.DISCARDING,
			HolderLookup.Provider.create(Stream.empty()), tag)));
		for (CompoundTag invalid : new CompoundTag[] {new CompoundTag(), tag.copy()}) {
			invalid.putDouble("AttachedX", Double.NaN);
			assertNull(MicroMissilePolicy.Attachment.read(TagValueInput.create(ProblemReporter.DISCARDING,
				HolderLookup.Provider.create(Stream.empty()), invalid)));
		}
	}

	@Test void independentImpactTimersPreserveImpactOrderNotLaunchOrder() {
		int[] impactTimes = {9, 5, 7, 12};
		int[] fuses = {-1, -1, -1, -1};
		java.util.List<Integer> detonations = new java.util.ArrayList<>();
		for (int tick = 0; tick <= 30; tick++) for (int missile = 0; missile < 4; missile++) {
			if (tick == impactTimes[missile]) fuses[missile] = MicroMissilePolicy.IMPACT_FUSE_TICKS;
			else if (fuses[missile] > 0) {
				fuses[missile] = MicroMissilePolicy.nextFuse(fuses[missile]);
				if (fuses[missile] == 0) {
					assertEquals(impactTimes[missile] + 12, tick);
					detonations.add(missile);
				}
			}
		}
		assertEquals(java.util.List.of(1, 2, 0, 3), detonations);
	}

	private static MicroMissilePolicy.SavedState read(CompoundTag tag) {
		return MicroMissilePolicy.SavedState.read(TagValueInput.create(ProblemReporter.DISCARDING,
			HolderLookup.Provider.create(Stream.empty()), tag));
	}
}
