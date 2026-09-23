package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

final class MissileCapsuleLaunchTest {
	@Test void ejectThenColdTurnThenBoostHaveDistinctBoundaries() {
		assertEquals(2, MicroMissilePolicy.SALVO_INTERVAL_TICKS);
		assertEquals(5, MissileLockPolicy.MAX_SHOTS);
		for (int age = 0; age <= 24; age++) {
			assertEquals(age <= 6 ? 0 : age <= 12 ? 1 : age <= 20 ? 2 : 3, MicroMissilePolicy.flightPhase(age, false));
			assertEquals(age >= 13, MicroMissilePolicy.motorIgnited(age));
			assertEquals(4, MicroMissilePolicy.flightPhase(age, true));
		}
	}

	@Test void capsuleRotatesBeforeThrustAndWithoutChangingTheColdDrift() {
		var profile = new MicroMissilePolicy.LaunchProfile(2, 0);
		Vec3 position = new Vec3(0, 16, 0), aim = new Vec3(0, 0, 48);
		Vec3 velocity = MicroMissilePolicy.ejectionVelocity(profile, Vec3.ZERO);
		Vec3 previousFacing = new Vec3(0, 1, 0);
		for (int age = 1; age <= 13; age++) {
			var step = MicroMissilePolicy.flightStep(position, velocity, aim, age, false, false, profile);
			if (age <= 6) assertEquals(new Vec3(0, 1, 0), step.facing());
			if (age > 6 && age <= 12) {
				assertTrue(step.velocity().y < velocity.y);
				assertTrue(Math.acos(Math.clamp(step.facing().dot(previousFacing), -1, 1)) < Math.toRadians(24));
				assertEquals(velocity.x * .96, step.velocity().x, 1e-10);
				assertEquals(velocity.z * .96, step.velocity().z, 1e-10);
			}
			if (age == 12) assertTrue(step.facing().dot(step.velocity().normalize()) < .5, "attitude is not the cold velocity vector");
			if (age == 13) {
				Vec3 thrust = step.velocity().subtract(velocity.scale(.75));
				assertEquals(1, thrust.normalize().dot(step.facing()), 1e-10);
				assertTrue(step.velocity().length() > velocity.length());
			}
			velocity = step.velocity(); previousFacing = step.facing(); position = position.add(velocity);
		}
	}

	@Test void fiveStaggeredShotsDoNotShareOneTubeOrOneFlightLine() {
		List<Vec3> positions = new ArrayList<>(), velocities = new ArrayList<>();
		for (int shot = 0; shot < 5; shot++) {
			positions.add(MicroMissilePolicy.launchTube(shot));
			velocities.add(MicroMissilePolicy.ejectionVelocity(new MicroMissilePolicy.LaunchProfile(shot, 0), Vec3.ZERO));
		}
		for (int tick = 1; tick <= 20; tick++) {
			for (int shot = 0; shot < 5; shot++) {
				int age = tick - shot * MicroMissilePolicy.SALVO_INTERVAL_TICKS;
				if (age <= 0) continue;
				var step = MicroMissilePolicy.flightStep(positions.get(shot), velocities.get(shot), new Vec3(0, -12, 48),
					age, false, false, new MicroMissilePolicy.LaunchProfile(shot, 0));
				velocities.set(shot, step.velocity()); positions.set(shot, positions.get(shot).add(step.velocity()));
			}
			if (tick < 9) continue;
			for (int a = 0; a < 5; a++) for (int b = a + 1; b < 5; b++)
				assertTrue(positions.get(a).distanceTo(positions.get(b)) > .32, "capsule overlap tick=" + tick + " shots=" + a + "," + b);
		}
		assertTrue(positions.get(0).x < -.5 && positions.get(1).x > .5, "both sides remain visibly distinct at boost");
	}

	@Test void allFiveFanLanesRemainAccurateAcrossTheExistingLaunchEnvelope() {
		int hits = 0, total = 0;
		Vec3 half = new Vec3(.14, .14, .14);
		for (float yaw : new float[] {0, 90, -90, 180}) for (int shot = 0; shot < 5; shot++)
			for (int height : new int[] {8, 16, 34}) for (int range : new int[] {24, 32, 48, 64}) {
				var profile = new MicroMissilePolicy.LaunchProfile(shot, yaw);
				Vec3 position = DroneHardpoints.rotate(MicroMissilePolicy.launchTube(shot), yaw, 0, 0).add(0, height, 0);
				Vec3 velocity = MicroMissilePolicy.ejectionVelocity(profile, Vec3.ZERO);
				Vec3 aim = DroneHardpoints.rotate(new Vec3(0, .7, range), yaw, 0, 0);
				AABB target = new AABB(aim.x - .3, 0, aim.z - .3, aim.x + .3, 1.8, aim.z + .3);
				AABB ground = new AABB(-100, -1, -100, 100, 0, 100);
				boolean terminal = false, passed = false, groundHit = false, hit = false;
				String scenario = "shot=" + shot + " yaw=" + yaw + " height=" + height + " range=" + range;
				for (int age = 1; age <= MicroMissilePolicy.MAX_FLIGHT_TICKS; age++) {
					var step = MicroMissilePolicy.flightStep(position, velocity, aim, age, terminal, passed, profile);
					velocity = step.velocity(); terminal = step.terminal(); passed = step.passedTarget();
					assertTrue(MicroMissilePolicy.finite(velocity) && MicroMissilePolicy.finite(step.facing()));
					assertEquals(1, step.facing().length(), 1e-8);
					assertTrue(velocity.length() <= MicroMissilePolicy.TERMINAL_SPEED + 1e-8);
					Vec3 from = position.add(0, half.y, 0), to = from.add(velocity);
					hit |= MicroMissilePolicy.sweepFraction(from, to, target, half) <= 1;
					double terrain = MicroMissilePolicy.sweepFraction(from, to, ground, half);
					position = position.add(velocity.scale(Math.min(1, terrain)));
					if (terrain <= 1) {
						groundHit = true;
						assertTrue(age < 110, "must not orbit: " + scenario);
						assertTrue(position.subtract(aim).horizontalDistance() < 3, "impact near retained target: " + scenario);
						break;
					}
				}
				assertTrue(terminal && groundHit, scenario);
				total++; if (hit) hits++;
			}
		assertTrue(hits >= total * .75, "preserve the established direct-hit threshold: " + hits + "/" + total);
	}

	@Test void frozenLaunchFrameSurvivesEveryColdAndBoostSaveBoundary() {
		UUID target = UUID.randomUUID();
		for (int shot = 0; shot < 5; shot++) for (int age = 0; age <= 21; age++) {
			var profile = new MicroMissilePolicy.LaunchProfile(shot, 117);
			var state = new MicroMissilePolicy.SavedState(UUID.randomUUID(), UUID.randomUUID(), target, "owner-team",
				new Vec3(12, 2, 40), age, -1, 4.5f, false, false);
			TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
			profile.write(output); state.write(output);
			var input = input(output.buildResult());
			var restored = MicroMissilePolicy.LaunchProfile.read(input);
			var saved = MicroMissilePolicy.SavedState.read(input);
			assertEquals(profile, restored); assertEquals(state, saved);
			assertEquals(target, saved.target());
			Vec3 velocity = MicroMissilePolicy.ejectionVelocity(profile, Vec3.ZERO);
			assertEquals(MicroMissilePolicy.flightStep(new Vec3(0, 12, 0), velocity, state.lastAim(), age + 1, false, false, profile),
				MicroMissilePolicy.flightStep(new Vec3(0, 12, 0), velocity, saved.lastAim(), age + 1, false, false, restored));
		}
	}

	@Test void malformedAndLegacyLaunchFramesRemainFiniteAndBounded() {
		assertEquals(new MicroMissilePolicy.LaunchProfile(0, 0), MicroMissilePolicy.LaunchProfile.read(input(new CompoundTag())));
		for (int tube : new int[] {Integer.MIN_VALUE, -1, 0, 7, 8, Integer.MAX_VALUE})
			for (float yaw : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE}) {
				var profile = new MicroMissilePolicy.LaunchProfile(tube, yaw);
				assertTrue(profile.tube() >= 0 && profile.tube() < 8);
				assertTrue(MicroMissilePolicy.finite(profile.fan()));
				Vec3 velocity = MicroMissilePolicy.ejectionVelocity(profile, new Vec3(Double.NaN, 0, 0));
				assertTrue(MicroMissilePolicy.finite(velocity) && velocity.length() < .5);
			}
	}

	@Test void inheritedLauncherSpeedCannotCollapseOrOverpowerFanLanes() {
		for (float yaw : new float[] {0, 90, 180, -90}) {
			var a = new MicroMissilePolicy.LaunchProfile(0, yaw);
			var b = new MicroMissilePolicy.LaunchProfile(1, yaw);
			Vec3 inherited = new Vec3(900, 500, -600);
			Vec3 left = MicroMissilePolicy.ejectionVelocity(a, inherited), right = MicroMissilePolicy.ejectionVelocity(b, inherited);
			assertEquals(.26, left.distanceTo(right), 1e-8);
			assertEquals(.38, left.y);
			assertTrue(left.horizontalDistance() < .27);
		}
	}

	@Test void runtimeKeepsImmutableCursorSweepFuseDamageAndNewPhasePersistence() throws Exception {
		String drone = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String missile = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/MorrowgearMissileEntity.java"));
		assertTrue(drone.contains("MorrowgearMissileEntity.launch(level, this, target, missileLock.cursor())"));
		assertTrue(missile.contains("new MicroMissilePolicy.LaunchProfile(shot, launcher.getYRot())"));
		assertTrue(missile.contains("Vec3 facing = guidance.facing()"));
		assertEquals(-.40, MorrowgearMissileEntity.EXHAUST_OFFSET);
		assertTrue(missile.contains(".scale(EXHAUST_OFFSET)"));
		assertTrue(missile.contains("flightTicks == MicroMissilePolicy.IGNITION_TICK"));
		assertTrue(missile.contains("MicroMissilePolicy.motorIgnited(flightTicks)"));
		assertTrue(missile.contains("launchProfile.write(output)"));
		assertTrue(missile.contains("MicroMissilePolicy.LaunchProfile.read(input)"));
		assertTrue(missile.contains("MicroMissilePolicy.sweepFraction(from, to, box, halfSize)"));
		assertTrue(missile.contains("MicroMissilePolicy.entityDamage(super.getEntityDamageAmount("));
		assertTrue(missile.contains("if (fuseTicks >= 0) return false"));
		assertTrue(missile.contains("attachment.write(output)"));
	}

	private static net.minecraft.world.level.storage.ValueInput input(CompoundTag tag) {
		return TagValueInput.create(ProblemReporter.DISCARDING, HolderLookup.Provider.create(Stream.empty()), tag);
	}
}
