package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MissileLockPropertiesTest {

	@Test void budgetsAndPermutationRemainStableAcrossGeneratedFleets() {
		Random random = new Random(0x4d47524c);
		for (int sample = 0; sample < 1000; sample++) {
			var candidates = new ArrayList<MissileLockPolicy.Candidate>();
			for (int i = 0, size = random.nextInt(35); i < size; i++) {
				candidates.add(new MissileLockPolicy.Candidate(new UUID(0, random.nextInt(15)),
					random.nextBoolean(), random.nextInt(180) - 40, random.nextInt(180) - 40,
					random.nextInt(180) - 40, random.nextDouble() * 5000));
			}
			int ammunition = random.nextInt(60) - 5;
			int power = random.nextInt(150) - 20;
			var plan = MissileLockPolicy.plan(candidates, ammunition, power);
			int budget = Math.max(0, Math.min(5, Math.min(ammunition,
				power / MicroMissilePolicy.POWER_PER_MISSILE)));
			assertTrue(plan.shots().size() <= budget);
			var eligible = MissileLockPolicy.ranked(candidates).stream()
				.map(MissileLockPolicy.Candidate::target).toList();
			assertTrue(eligible.containsAll(plan.shots()));
			assertEquals(eligible.size(), new HashSet<>(eligible).size());
			Collections.shuffle(candidates, random);
			assertEquals(plan, MissileLockPolicy.plan(candidates, ammunition, power));
		}
	}

	@Test void invalidatedTargetsNeverReappearAndProcessingTerminates() {
		Random random = new Random(91);
		for (int sample = 0; sample < 300; sample++) {
			var orders = new ArrayList<UUID>();
			for (int i = 0; i < 5; i++) orders.add(new UUID(0, random.nextInt(3)));
			var plan = new MissileLockPolicy.Plan(orders);
			var progress = plan.start();
			var invalidated = new HashSet<UUID>();
			int steps = 0;
			while (!progress.complete()) {
				assertFalse(invalidated.contains(progress.target()));
				if (random.nextBoolean()) {
					invalidated.add(progress.target());
					progress = progress.skipTarget();
				} else progress = progress.advance();
				assertTrue(++steps <= 5);
				assertEquals(orders, plan.shots());
			}
			assertNull(progress.target());
			assertEquals(progress, progress.skipTarget());
		}
	}

	@Test void newEmergencyOnlyChangesTheNextPlan() {
		UUID original = new UUID(0, 1);
		UUID urgent = new UUID(0, 2);
		var contacts = new ArrayList<>(List.of(
			new MissileLockPolicy.Candidate(original, false, 0, 30, 30, 100)));
		var locked = MissileLockPolicy.plan(contacts, 1, 1000);
		var progress = locked.start();
		contacts.add(new MissileLockPolicy.Candidate(urgent, true, 100, 100, 100, 200));
		assertEquals(List.of(original), locked.shots());
		assertEquals(original, progress.target());
		assertEquals(List.of(urgent), MissileLockPolicy.plan(contacts, 1, 1000).shots());
		assertThrows(UnsupportedOperationException.class, () -> locked.shots().clear());
	}

	@Test void nonfiniteDistancesAndOversizedOrdersFailClosed() {
		var candidates = new ArrayList<MissileLockPolicy.Candidate>();
		for (double distance : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
			Double.NEGATIVE_INFINITY, -1})
			candidates.add(new MissileLockPolicy.Candidate(UUID.randomUUID(), true, 100, 100, 100, distance));
		assertTrue(MissileLockPolicy.plan(candidates, Integer.MAX_VALUE, Integer.MAX_VALUE).shots().isEmpty());
		assertThrows(IllegalArgumentException.class,
			() -> new MissileLockPolicy.Plan(Collections.nCopies(6, UUID.randomUUID())));
	}
}
