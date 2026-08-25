package jp.morrowgear.drone;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

final class DroneTaskStack {
	private final Deque<Task> suspended = new ArrayDeque<>();
	private final PriorityQueue<Task> pending = new PriorityQueue<>(
		Comparator.comparingInt((Task task) -> task.kind().priority()).reversed()
			.thenComparingLong(Task::sequence));
	private long sequence;

	void suspend(Task task) {
		if (task == null || task.kind() == Kind.IDLE) return;
		if (!suspended.isEmpty() && suspended.peek().sameAssignment(task)) return;
		suspended.push(task.withSequence(sequence++));
	}

	void queue(Task task) {
		if (task == null) return;
		if (!suspended.isEmpty() && suspended.peek().sameAssignment(task)) return;
		if (pending.stream().anyMatch(existing -> existing.sameAssignment(task))) return;
		pending.add(task.withSequence(sequence++));
	}

	Optional<Task> resume() {
		return Optional.ofNullable(takeNext());
	}

	Optional<Task> resume(Function<Task, Optional<Task>> resolver) {
		if (resolver == null) return resume();
		Task candidate;
		while ((candidate = takeNext()) != null) {
			Optional<Task> resolved = resolver.apply(candidate);
			if (resolved != null && resolved.isPresent()) return resolved;
		}
		return Optional.empty();
	}

	void discard(Predicate<Task> predicate) {
		if (predicate == null) return;
		suspended.removeIf(predicate);
		pending.removeIf(predicate);
	}

	private Task takeNext() {
		Task stacked = suspended.peek();
		Task queued = pending.peek();
		if (stacked == null && queued == null) return null;
		if (queued != null && (stacked == null
			|| queued.kind().priority() > stacked.kind().priority())) return pending.remove();
		return suspended.pop();
	}

	void clear() {
		suspended.clear();
		pending.clear();
	}

	int suspendedCount() { return suspended.size(); }
	int pendingCount() { return pending.size(); }

	enum Kind {
		IDLE(0),
		FOLLOW(20),
		ROUTE(30),
		TRACKING(35),
		SECURITY_PATROL(40),
		ENGINEER(45),
		CARGO(50),
		FIELD(55),
		SOLAR_SERVICE(65),
		SALVAGE(70),
		DOCK_SERVICE(75),
		SALVAGE_TOW(85),
		COMBAT(90),
		RECOVERY(95),
		POWER_LOSS(100);

		private final int priority;
		Kind(int priority) { this.priority = priority; }
		int priority() { return priority; }
	}

	record Task(Kind kind, DroneMode mode, SalvageState salvageState, UUID targetId,
		String missionId, long sequence) {
		Task {
			kind = kind == null ? Kind.IDLE : kind;
			mode = mode == null ? DroneMode.STANDBY : mode;
			salvageState = salvageState == null ? SalvageState.IDLE : salvageState;
			missionId = missionId == null ? "" : missionId;
		}

		Task(Kind kind, DroneMode mode, SalvageState salvageState, UUID targetId, String missionId) {
			this(kind, mode, salvageState, targetId, missionId, -1L);
		}

		Task withSequence(long value) {
			return new Task(kind, mode, salvageState, targetId, missionId, value);
		}

		boolean sameAssignment(Task other) {
			return other != null && kind == other.kind && mode == other.mode
				&& salvageState == other.salvageState
				&& java.util.Objects.equals(targetId, other.targetId)
				&& missionId.equals(other.missionId);
		}
	}
}
