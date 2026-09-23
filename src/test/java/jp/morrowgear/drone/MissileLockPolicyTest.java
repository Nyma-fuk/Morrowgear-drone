package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MissileLockPolicyTest {
	private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

	@Test void lowThreatUsesOneAndBossGetsFiveOnlyWhenThereIsNoCompetingLock() {
		assertEquals(List.of(A), MissileLockPolicy.plan(List.of(contact(A, 0)), 45, 2000).shots());
		assertEquals(List.of(A, A, A, A, A), MissileLockPolicy.plan(List.of(contact(A, 100)), 45, 2000).shots());
		assertEquals(List.of(A, B, A, A, A), MissileLockPolicy.plan(List.of(contact(A, 100), contact(B, 0)), 45, 2000).shots());
	}

	@Test void thresholdsAndPriorityControlExtraAllocationsWithoutForcingFiveShots() {
		for (int threat = 0; threat <= 100; threat++) {
			var candidate = contact(A, threat);
			assertEquals(Math.min(5, 1 + threat / 25), MissileLockPolicy.plan(List.of(candidate), 45, 2000).shots().size());
		}
		assertEquals(List.of(A, B, C), MissileLockPolicy.plan(List.of(contact(C, 1), contact(B, 2), contact(A, 3)), 45, 2000).shots());
		var threatenedPlayer = new MissileLockPolicy.Candidate(B, true, 100, 10, 0, 200);
		assertEquals(List.of(B, A, B, B, B), MissileLockPolicy.plan(List.of(contact(A, 100), threatenedPlayer), 45, 2000).shots());
	}

	@Test void oneMissileOrOneShotsPowerLocksOnlyOneTarget() {
		var contacts = List.of(contact(A, 100), contact(B, 80), contact(C, 20));
		assertEquals(List.of(A), MissileLockPolicy.plan(contacts, 1, 2000).shots());
		assertEquals(List.of(A), MissileLockPolicy.plan(contacts, 45, 29).shots());
		assertEquals(List.of(A, B), MissileLockPolicy.plan(contacts, 45, 30).shots());
		assertTrue(MissileLockPolicy.plan(contacts, 45, 14).shots().isEmpty());
		assertTrue(MissileLockPolicy.plan(contacts, 0, 2000).shots().isEmpty());
		assertTrue(MissileLockPolicy.plan(contacts, -1, -1).shots().isEmpty());
	}

	@Test void priorityKeepsFleetPlayerGuardDangerAndScoreOrdering() {
		var boss = contact(A, 100);
		var guard = new MissileLockPolicy.Candidate(B, true, 0, 1, 1, 500);
		var danger = new MissileLockPolicy.Candidate(C, false, 90, 1, 1, 400);
		assertEquals(List.of(B, C, A), MissileLockPolicy.ranked(List.of(boss, danger, guard))
			.stream().map(MissileLockPolicy.Candidate::target).toList());
	}

	@Test void duplicateReportsNeverCreateExtraLockSlots() {
		var contacts = List.of(contact(A, 1), contact(B, 20), contact(A, 100));
		assertEquals(List.of(A, B), MissileLockPolicy.ranked(contacts).stream().map(MissileLockPolicy.Candidate::target).toList());
		assertEquals(List.of(A, B, A, A, A), MissileLockPolicy.plan(contacts, 45, 2000).shots());
	}

	@Test void deadPrimarySkipsAllItsRemainingShotsButOtherLockedTargetsContinue() {
		var plan = new MissileLockPolicy.Plan(List.of(A, B, A, C, A));
		var progress = plan.start().skipTarget();
		assertSame(plan, progress.plan());
		assertEquals(B, progress.target());
		progress = progress.advance();
		assertEquals(C, progress.target());
		assertTrue(progress.advance().complete());
		assertEquals(List.of(A, B, A, C, A), plan.shots());
		assertEquals(Set.of(A), progress.skipped());
	}

	@Test void everyFailedLaunchAndEveryInvalidTargetHaveFiniteCompletion() {
		var plan = new MissileLockPolicy.Plan(List.of(A, B, A, C, A));
		var failure = plan.start();
		for (int attempts = 0; attempts < 5; attempts++) failure = failure.advance();
		assertTrue(failure.complete());
		assertEquals(failure, failure.advance());
		var invalid = plan.start().skipTarget().skipTarget().skipTarget();
		assertTrue(invalid.complete());
		assertNull(invalid.target());
		assertEquals(invalid, invalid.skipTarget());
	}

	@Test void snapshotsAndInvalidationSetsDoNotAliasCallerCollections() {
		var shots = new ArrayList<>(List.of(A, B));
		var plan = new MissileLockPolicy.Plan(shots);
		shots.clear();
		assertEquals(List.of(A, B), plan.shots());
		assertThrows(UnsupportedOperationException.class, () -> plan.shots().add(C));
		assertThrows(UnsupportedOperationException.class, () -> plan.start().skipped().add(A));
		assertThrows(IllegalArgumentException.class, () -> new MissileLockPolicy.Progress(plan, 3, Set.of()));
	}

	@Test void approachExpiryIncludesTimeRollbackAndTheBoundary() {
		assertFalse(MissileLockPolicy.expired(100, 339));
		assertTrue(MissileLockPolicy.expired(100, 340));
		assertTrue(MissileLockPolicy.expired(100, 99));
	}

	private static MissileLockPolicy.Candidate contact(UUID id, int threat) {
		return new MissileLockPolicy.Candidate(id, false, 0, threat, threat, 100);
	}
}
