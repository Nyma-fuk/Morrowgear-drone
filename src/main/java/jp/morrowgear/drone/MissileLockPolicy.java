package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable fire orders. Advancing or invalidating a lock never allocates another missile. */
final class MissileLockPolicy {
	static final int MAX_SHOTS = 5;
	static final long MAX_APPROACH_TICKS = 240;
	private static final Comparator<Candidate> PRIORITY = Comparator
		.comparing(Candidate::playerGuard).reversed()
		.thenComparing(Comparator.comparingInt(Candidate::playerDanger).reversed())
		.thenComparing(Comparator.comparingInt(Candidate::score).reversed())
		.thenComparing(Comparator.comparingInt(Candidate::enemyThreat).reversed())
		.thenComparingDouble(Candidate::distanceSquared).thenComparing(Candidate::target);

	private MissileLockPolicy() {}

	record Candidate(UUID target, boolean playerGuard, int playerDanger, int score,
		int enemyThreat, double distanceSquared) {
		Candidate {
			java.util.Objects.requireNonNull(target);
			playerDanger = Math.clamp(playerDanger, 0, 100);
			score = Math.clamp(score, 0, 100);
			enemyThreat = Math.clamp(enemyThreat, 0, 100);
		}
	}

	static List<Candidate> ranked(List<Candidate> contacts) {
		var unique = new HashMap<UUID, Candidate>();
		for (Candidate contact : contacts) {
			if (!Double.isFinite(contact.distanceSquared()) || contact.distanceSquared() < 0) continue;
			unique.merge(contact.target(), contact, (a, b) -> PRIORITY.compare(a, b) <= 0 ? a : b);
		}
		return unique.values().stream().sorted(PRIORITY).toList();
	}

	static Plan plan(List<Candidate> contacts, int ammunition, int power) {
		int budget = Math.max(0, Math.min(MAX_SHOTS, Math.min(ammunition, power / MicroMissilePolicy.POWER_PER_MISSILE)));
		List<Candidate> selected = ranked(contacts).stream().limit(budget).toList();
		int[] assigned = new int[selected.size()];
		java.util.Arrays.fill(assigned, 1);
		int remaining = budget - selected.size();
		for (int i = 0; i < selected.size() && remaining > 0; i++) {
			int extra = Math.min(remaining, demand(selected.get(i)) - 1);
			assigned[i] += extra;
			remaining -= extra;
		}
		var shots = new ArrayList<UUID>();
		// One first shot per selected contact, followed by the fixed extra allocations.
		for (int round = 0; round < MAX_SHOTS; round++) for (int i = 0; i < selected.size(); i++)
			if (assigned[i] > round) shots.add(selected.get(i).target());
		return new Plan(shots);
	}

	static int demand(Candidate contact) {
		return Math.min(MAX_SHOTS, 1 + Math.max(contact.enemyThreat(), contact.playerDanger()) / 25);
	}

	static boolean expired(long started, long now) {
		return now < started || now - started >= MAX_APPROACH_TICKS;
	}

	record Plan(List<UUID> shots) {
		Plan {
			shots = List.copyOf(shots);
			if (shots.size() > MAX_SHOTS) throw new IllegalArgumentException("Missile salvo exceeds five shots");
		}
		Progress start() { return new Progress(this, 0, Set.of()); }
	}

	record Progress(Plan plan, int cursor, Set<UUID> skipped) {
		Progress {
			skipped = Set.copyOf(skipped);
			if (cursor < 0 || cursor > plan.shots().size()) throw new IllegalArgumentException("Invalid missile cursor");
			while (cursor < plan.shots().size() && skipped.contains(plan.shots().get(cursor))) cursor++;
		}
		boolean complete() { return cursor == plan.shots().size(); }
		UUID target() { return complete() ? null : plan.shots().get(cursor); }
		Progress advance() { return new Progress(plan, Math.min(cursor + 1, plan.shots().size()), skipped); }
		Progress skipTarget() {
			if (complete()) return this;
			var invalid = new HashSet<>(skipped);
			invalid.add(target());
			return new Progress(plan, cursor, invalid);
		}
	}
}
