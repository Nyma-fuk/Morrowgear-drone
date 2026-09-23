package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.SupplyNetworkPolicy.Rule;
import jp.morrowgear.drone.SupplyNetworkPolicy.Status;

/** Server-thread ledger only. Items never live in this registry. */
public final class SupplyNetworkRegistry {
	private final Map<Route, Configuration> routes = new LinkedHashMap<>();
	private final Map<UUID, Job> jobs = new LinkedHashMap<>();
	private final Map<Route, Status> statuses = new LinkedHashMap<>();
	private final Runnable dirty;
	private long generation;

	public SupplyNetworkRegistry(Runnable dirty) {
		this.dirty = Objects.requireNonNull(dirty);
	}

	public Configuration configure(Route route, List<Rule> rules, boolean enabled) {
		Objects.requireNonNull(route);
		List<Rule> checked = validateRules(rules);
		Configuration old = routes.values().stream().filter(c -> sameDock(c.route(), route)).findFirst().orElse(null);
		if (old == null && routes.size() >= SupplyNetworkPolicy.MAX_ROUTES)
			throw new IllegalStateException("Supply route limit reached");
		if (routes.values().stream().anyMatch(c -> c.route().dimension().equals(route.dimension())
			&& !c.route().owner().equals(route.owner())
			&& (c.route().source() == route.source() || c.route().dock() == route.dock())))
			throw new IllegalArgumentException("Supply endpoint belongs to another owner");
		if (old != null && old.route().equals(route) && old.rules().equals(checked) && old.enabled() == enabled)
			return old;
		if (old != null) {
			for (Job job : List.copyOf(jobs.values())) if (job.route().equals(old.route()))
				cancel(job.token(), Status.CANCELLED);
			routes.remove(old.route());
			statuses.remove(old.route());
		}
		Configuration config = new Configuration(route, checked, enabled, nextGeneration());
		routes.put(route, config);
		statuses.put(route, enabled ? Status.READY : Status.DISABLED);
		dirty.run();
		return config;
	}

	private static List<Rule> validateRules(List<Rule> rules) {
		if (rules == null || rules.size() != SupplyKind.values().length
			|| rules.stream().anyMatch(Objects::isNull)
			|| rules.stream().map(Rule::kind).distinct().count() != SupplyKind.values().length)
			throw new IllegalArgumentException("Exactly one rule per supply kind is required");
		return rules.stream().sorted(Comparator.comparing(Rule::kind)).toList();
	}

	private long nextGeneration() {
		if (generation == Long.MAX_VALUE) throw new IllegalStateException("Supply generation exhausted");
		return ++generation;
	}

	public List<Configuration> configurations() { return List.copyOf(routes.values()); }
	public List<Job> jobs() { return List.copyOf(jobs.values()); }
	public long generation() { return generation; }
	public Optional<Job> job(UUID drone) { return Optional.ofNullable(jobs.get(drone)); }
	public Optional<Job> job(Token token) {
		return token == null ? Optional.empty() : jobs.values().stream().filter(j -> j.token().equals(token)).findFirst();
	}
	public boolean current(Job job) {
		return job.equals(jobs.get(job.drone())) && configured(job);
	}

	private boolean configured(Job job) {
		if (job.returning()) return true;
		Configuration c = routes.get(job.route());
		return c != null && c.enabled() && c.revision() == job.revision() && job.stage() != Stage.HELD;
	}

	public int reservedSource(Route route, String item) {
		return jobs.values().stream().filter(j -> j.stage() == Stage.RESERVED
			&& j.route().owner().equals(route.owner()) && j.route().dimension().equals(route.dimension())
			&& j.route().source() == route.source() && j.item().equals(item))
			.mapToInt(Job::requested).sum();
	}

	public int incoming(Route route, SupplyKind kind) {
		return jobs.values().stream().filter(j -> j.route().owner().equals(route.owner())
			&& j.route().dimension().equals(route.dimension()) && j.deliveryTarget() == route.dock()
			&& j.stage() != Stage.RETURN_SOURCE && j.kind() == kind
			&& j.stage() != Stage.HELD).mapToInt(j -> j.stage() == Stage.RESERVED
				? j.requested() : j.remaining()).sum();
	}

	public boolean dockReserved(Route route) {
		return jobs.values().stream().anyMatch(j -> j.route().dimension().equals(route.dimension())
			&& j.deliveryTarget() == route.dock() && j.stage() != Stage.HELD && j.stage() != Stage.RETURN_SOURCE);
	}

	public Optional<Job> reserve(Configuration config, UUID drone, SupplyKind kind, String item,
		int quantity, int sourceStock, int targetRoom, long tick) {
		if (!config.equals(routes.get(config.route())) || !config.enabled() || drone == null
			|| item == null || item.isBlank() || kind == null || jobs.containsKey(drone)
			|| DockSupplyPolicy.kind(item) != kind
			|| dockReserved(config.route()) || jobs.size() >= SupplyNetworkPolicy.MAX_JOBS) return Optional.empty();
		int minimum = config.rules().stream().filter(r -> r.kind() == kind).findFirst().orElseThrow().minimum();
		int count = Math.min(Math.min(Math.min(quantity, minimum), targetRoom),
			Math.min(SupplyNetworkPolicy.MAX_TRIP_ITEMS,
				SupplyNetworkPolicy.available(sourceStock, reservedSource(config.route(), item))));
		if (count <= 0) return Optional.empty();
		Job job = new Job(new Token(UUID.randomUUID(), nextGeneration()), config.route(), config.revision(),
			drone, kind, item, count, 0, 0, Stage.RESERVED, Status.IN_TRANSIT, tick, tick, Long.MIN_VALUE);
		put(job);
		return Optional.of(job);
	}

	public boolean loaded(Token token, int moved) {
		Job job = job(token).orElse(null);
		if (job == null || !current(job) || job.stage() != Stage.RESERVED
			|| moved <= 0 || moved > job.requested()) return false;
		put(job.withProgress(moved, 0, Stage.LOADED, Status.IN_TRANSIT, job.seenTick()));
		return true;
	}

	public boolean delivered(Token token, int moved) {
		Job job = job(token).orElse(null);
		if (job == null || !current(job) || (job.stage() != Stage.LOADED && !job.returning())
			|| moved < 0 || moved > job.remaining())
			return false;
		if (moved == job.remaining()) {
			jobs.remove(job.drone());
			status(job.route(), Status.READY);
			dirty.run();
		} else put(job.withProgress(job.loaded(), job.delivered() + moved, job.stage(),
			Status.DOCK_FULL, job.seenTick()));
		return true;
	}

	public void heartbeat(Token token, long tick, boolean charging) {
		Job job = job(token).orElse(null);
		if (job == null || job.stage() == Stage.HELD) return;
		Status status = charging ? Status.RECHARGING
			: job.status() == Status.DOCK_FULL ? Status.DOCK_FULL : job.returning() ? Status.RETURNING : Status.IN_TRANSIT;
		put(job.withProgress(job.loaded(), job.delivered(), job.stage(), status, tick));
	}

	/** Invalidated leases are never reactivated by a delayed callback. */
	public void cancel(Token token, Status reason) {
		Job job = job(token).orElse(null);
		if (job == null) return;
		put(job.withProgress(job.loaded(), job.delivered(), Stage.HELD, reason, job.seenTick()));
	}

	/** The caller chooses a loaded, owner-checked endpoint from configured Docks or the original source. */
	public Optional<Job> redirectRetained(Token token, long endpoint, boolean source) {
		Job job = job(token).orElse(null);
		if (job == null || job.stage() != Stage.HELD || job.remaining() <= 0
			|| source && endpoint != job.route().source()) return Optional.empty();
		if (!source) {
			Configuration destination = routes.values().stream().filter(c -> c.enabled()
				&& c.route().owner().equals(job.route().owner()) && c.route().dimension().equals(job.route().dimension())
				&& c.route().dock() == endpoint).findFirst().orElse(null);
			if (destination == null || dockReserved(destination.route())) return Optional.empty();
		}
		Job redirected = new Job(job.token(), job.route(), job.revision(), job.drone(), job.kind(), job.item(),
			job.requested(), job.loaded(), job.delivered(), source ? Stage.RETURN_SOURCE : Stage.RETURN_DOCK,
			Status.RETURNING, job.assignedTick(), job.seenTick(), endpoint);
		put(redirected);
		return Optional.of(redirected);
	}

	/** Only after the loaded entity confirms the hold is empty, or after its normal drop/store path. */
	public void release(Token token) {
		Job job = job(token).orElse(null);
		if (job != null) { jobs.remove(job.drone()); dirty.run(); }
	}

	public void expireMissing(long tick, List<UUID> present) {
		for (Job job : List.copyOf(jobs.values())) if (!present.contains(job.drone()) && job.stage() != Stage.HELD
			&& tick >= job.seenTick() && tick - job.seenTick() > SupplyNetworkPolicy.MISSING_DRONE_TICKS)
			cancel(job.token(), Status.ASSIGNMENT_LOST);
	}

	public void status(Route route, Status status) {
		if (routes.containsKey(route)) statuses.put(route, Objects.requireNonNull(status));
	}
	public Status status(Route route) {
		return statuses.getOrDefault(route, Status.DISABLED);
	}

	private void put(Job job) {
		jobs.put(job.drone(), job);
		status(job.route(), job.status());
		dirty.run();
	}

	public void restore(long generation, List<Configuration> configs, List<Job> savedJobs) {
		if (generation < 0 || configs.size() > SupplyNetworkPolicy.MAX_ROUTES || savedJobs.size() > SupplyNetworkPolicy.MAX_JOBS)
			throw new IllegalArgumentException("Invalid supply ledger size or generation");
		routes.clear(); jobs.clear(); statuses.clear();
		this.generation = generation;
		for (Configuration config : configs) {
			validateRules(config.rules());
			if (routes.values().stream().anyMatch(c -> sameDock(c.route(), config.route())
				|| (c.route().dimension().equals(config.route().dimension())
					&& !c.route().owner().equals(config.route().owner())
					&& (c.route().source() == config.route().source() || c.route().dock() == config.route().dock()))))
				throw new IllegalArgumentException("Duplicate supply endpoint");
			routes.put(config.route(), config);
			this.generation = Math.max(this.generation, config.revision());
		}
		for (Job job : savedJobs) {
			if (jobs.containsKey(job.drone()) || jobs.values().stream().anyMatch(j -> j.token().equals(job.token())))
				throw new IllegalArgumentException("Duplicate supply assignment");
			boolean duplicateDock = job.stage() != Stage.HELD && dockReserved(job.route());
			Job restored = (job.stage() == Stage.HELD || configured(job)) && !duplicateDock ? job : job.withProgress(job.loaded(), job.delivered(),
				Stage.HELD, Status.RESERVATION_INVALID, job.seenTick());
			jobs.put(restored.drone(), restored);
			this.generation = Math.max(this.generation, job.token().generation());
			status(restored.route(), restored.status());
		}
	}

	private static boolean sameDock(Route a, Route b) {
		return a.owner().equals(b.owner()) && a.dimension().equals(b.dimension()) && a.dock() == b.dock();
	}

	public record Route(UUID owner, String dimension, long source, long dock) {
		public Route {
			if (owner == null || dimension == null || dimension.isBlank() || source == dock)
				throw new IllegalArgumentException("Invalid supply route");
		}
	}

	public record Configuration(Route route, List<Rule> rules, boolean enabled, long revision) {
		public Configuration {
			Objects.requireNonNull(route);
			rules = validateRules(rules);
			if (revision <= 0) throw new IllegalArgumentException("Invalid configuration revision");
		}
	}

	public record Token(UUID id, long generation) {
		public Token {
			if (id == null || generation <= 0) throw new IllegalArgumentException("Invalid supply token");
		}
	}

	public enum Stage { RESERVED, LOADED, HELD, RETURN_SOURCE, RETURN_DOCK }

	public record Job(Token token, Route route, long revision, UUID drone, SupplyKind kind, String item,
		int requested, int loaded, int delivered, Stage stage, Status status, long assignedTick, long seenTick, long fallback) {
		public Job {
			Objects.requireNonNull(token); Objects.requireNonNull(route); Objects.requireNonNull(drone);
			Objects.requireNonNull(kind); Objects.requireNonNull(stage); Objects.requireNonNull(status);
			if (revision <= 0 || item == null || item.isBlank() || requested <= 0
				|| requested > SupplyNetworkPolicy.MAX_TRIP_ITEMS || loaded < 0 || loaded > requested
				|| delivered < 0 || delivered > loaded || (stage == Stage.RESERVED && loaded != 0)
				|| (stage == Stage.LOADED && loaded == 0)
				|| ((stage == Stage.RETURN_SOURCE || stage == Stage.RETURN_DOCK) && (loaded == 0 || fallback == Long.MIN_VALUE)))
				throw new IllegalArgumentException("Invalid supply job");
			if (stage == Stage.RETURN_SOURCE && fallback != route.source())
				throw new IllegalArgumentException("Supply return source does not match the assignment");
		}
		public int remaining() { return loaded - delivered; }
		public boolean returning() { return stage == Stage.RETURN_SOURCE || stage == Stage.RETURN_DOCK; }
		public long deliveryTarget() { return returning() || stage == Stage.HELD && fallback != Long.MIN_VALUE ? fallback : route.dock(); }
		Job withProgress(int loaded, int delivered, Stage stage, Status status, long seen) {
			return new Job(token, route, revision, drone, kind, item, requested, loaded, delivered, stage, status,
				assignedTick, seen, fallback);
		}
	}
}
