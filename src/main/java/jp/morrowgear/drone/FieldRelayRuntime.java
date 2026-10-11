package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative assignment state for the mobile FIELD communications mesh. */
final class FieldRelayRuntime {
	private static final int PLAN_INTERVAL = 20;
	private static final int TERRAIN_DATUM_RADIUS = 24;
	private static final Map<Key, Snapshot> SNAPSHOTS = new HashMap<>();
	private static final Map<Key, Map<String, RequestedDemand>> REQUESTED = new HashMap<>();
	private static final Map<Key, Vec3> DEPLOYMENT_ANCHORS = new HashMap<>();
	private static boolean registered;

	private FieldRelayRuntime() {}

	static synchronized void register() {
		if (registered) return;
		registered = true;
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
	}

	static synchronized Snapshot snapshot(ServerPlayer owner) {
		ServerLevel level = owner.level();
		Key key = new Key(level, owner.getUUID());
		Snapshot previous = SNAPSHOTS.get(key);
		long tick = level.getGameTime();
		if (previous != null && tick - previous.tick() < PLAN_INTERVAL) return previous;

		List<DroneEntity> fleet = MorrowgearDrone.ownedDrones(level, owner, 512);
		Vec3 anchor = deploymentAnchor(owner, fleet);
		List<FieldRelayMeshPolicy.Field> fields = fleet.stream()
			// A short obstacle-recovery manoeuvre must not discard and reshuffle the
			// relay's slot. The aircraft keeps the flight assignment while the graph
			// below excludes it as an active radio node until recovery completes.
			.filter(DroneEntity::fieldRelayControlsMeshFlight)
			.map(drone -> new FieldRelayMeshPolicy.Field(drone.getUUID(), point(drone.position()),
				drone.batteryPercent(), previous == null ? "" : previous.slotId(drone.getUUID())))
			.toList();
		List<FieldRelayMeshPolicy.Demand> demands = demands(level, anchor, fleet);
		FieldRelayMeshPolicy.Plan plan = FieldRelayMeshPolicy.plan(point(anchor), fields, demands);
		double flightPlaneY = previous == null ? initialFlightPlane(level, anchor)
			: previous.flightPlaneY();
		Map<UUID, Assignment> assignments = new LinkedHashMap<>();
		for (FieldRelayMeshPolicy.Assignment assignment : plan.assignments()) {
			FieldRelayMeshPolicy.Slot slot = assignment.slot();
			Vec3 horizontal = vec(slot.point());
			Vec3 target = new Vec3(horizontal.x, flightPlaneY, horizontal.z);
			assignments.put(assignment.fieldId(), new Assignment(assignment.fieldId(), slot.id(),
				target, slot.duty(), slot.demandId(), slot.sequence()));
		}
		Snapshot next = new Snapshot(tick, anchor, flightPlaneY,
			Map.copyOf(assignments), plan);
		SNAPSHOTS.put(key, next);
		return next;
	}

	static Assignment assignment(ServerPlayer owner, DroneEntity drone) {
		return snapshot(owner).assignments().get(drone.getUUID());
	}

	static List<DataLinkRelayPolicy.Node> relayNodes(ServerPlayer owner) {
		Snapshot snapshot = snapshot(owner);
		Map<UUID, DroneEntity> fields = new HashMap<>();
		for (DroneEntity drone : MorrowgearDrone.ownedDrones(owner.level(), owner, 512))
			if (snapshot.assignments().containsKey(drone.getUUID()) && drone.fieldRelayAvailableForMesh())
				fields.put(drone.getUUID(), drone);
		return snapshot.assignments().keySet().stream().map(fields::get).filter(java.util.Objects::nonNull)
			.map(drone -> new DataLinkRelayPolicy.Node(drone.getUUID(),
				new DataLinkRelayPolicy.Point(drone.getX(), drone.getY(), drone.getZ()), drone.batteryPercent()))
			.toList();
	}

	static MeshStatus status(ServerPlayer owner) {
		Snapshot snapshot = snapshot(owner);
		Map<UUID, DroneEntity> fields = new HashMap<>();
		for (DroneEntity drone : MorrowgearDrone.ownedDrones(owner.level(), owner, 512)) fields.put(drone.getUUID(), drone);
		int established = 0;
		for (Assignment assignment : snapshot.assignments().values()) {
			DroneEntity drone = fields.get(assignment.fieldId());
			if (drone != null && drone.fieldRelayAvailableForMesh()
				&& FieldRelayFlightPolicy.established(drone.position(), assignment.target())) established++;
		}
		FieldRelayMeshPolicy.Plan plan = snapshot.plan();
		String state = plan.shortage() > 0 ? "SHORT" : snapshot.assignments().isEmpty() ? "DIRECT"
			: established < snapshot.assignments().size() ? "BUILDING" : "STABLE";
		return new MeshStatus(state, snapshot.assignments().size(), established, plan.baseline(), plan.reserve(),
			plan.dynamic(), plan.shortage(), plan.minX() - DataLinkRelayPolicy.FIELD_RANGE,
			plan.maxX() + DataLinkRelayPolicy.FIELD_RANGE, plan.minZ() - DataLinkRelayPolicy.FIELD_RANGE,
			plan.maxZ() + DataLinkRelayPolicy.FIELD_RANGE);
	}

	static synchronized void requestCoverage(ServerPlayer owner, Vec3 target, int priority) {
		if (owner == null || target == null) return;
		Key key = new Key(owner.level(), owner.getUUID());
		long tick = owner.level().getGameTime();
		int cellX = Math.floorDiv((int)Math.floor(target.x), 48);
		int cellZ = Math.floorDiv((int)Math.floor(target.z), 48);
		String id = "COMMAND:" + cellX + ":" + cellZ;
		REQUESTED.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(id,
			new RequestedDemand(id, target, Math.max(100, priority), tick + 400L));
		SNAPSHOTS.remove(key);
	}

	static synchronized void invalidate(ServerPlayer owner) {
		if (owner != null) SNAPSHOTS.remove(new Key(owner.level(), owner.getUUID()));
	}

	static synchronized Vec3 deploymentAnchor(ServerPlayer owner) {
		return deploymentAnchor(owner, MorrowgearDrone.ownedDrones(owner.level(), owner, 512));
	}

	static synchronized void authorizationChanged(ServerPlayer owner) {
		if (owner == null) return;
		Key key = new Key(owner.level(), owner.getUUID());
		SNAPSHOTS.remove(key);
		boolean active = MorrowgearDrone.ownedDrones(owner.level(), owner, 512).stream()
			.anyMatch(DroneEntity::fieldMeshAuthorized);
		if (!active) DEPLOYMENT_ANCHORS.remove(key);
	}

	static synchronized void clearOwner(UUID owner) {
		SNAPSHOTS.entrySet().removeIf(entry -> entry.getKey().owner().equals(owner));
		REQUESTED.entrySet().removeIf(entry -> entry.getKey().owner().equals(owner));
		DEPLOYMENT_ANCHORS.entrySet().removeIf(entry -> entry.getKey().owner().equals(owner));
	}

	static synchronized void clear() {
		SNAPSHOTS.clear();
		REQUESTED.clear();
		DEPLOYMENT_ANCHORS.clear();
	}

	private static Vec3 deploymentAnchor(ServerPlayer owner, List<DroneEntity> fleet) {
		Key key = new Key(owner.level(), owner.getUUID());
		List<DroneEntity> authorized = fleet.stream().filter(DroneEntity::fieldMeshAuthorized)
			.sorted(Comparator.comparing(drone -> drone.getUUID().toString())).toList();
		Vec3 retained = DEPLOYMENT_ANCHORS.get(key);
		if (retained != null && !authorized.isEmpty()) return retained;
		if (authorized.isEmpty()) DEPLOYMENT_ANCHORS.remove(key);
		List<Vec3> persisted = authorized.stream().map(DroneEntity::fieldMeshAnchor)
			.filter(java.util.Objects::nonNull).toList();
		List<Vec3> positions = authorized.stream().map(DroneEntity::position).toList();
		Vec3 anchor = FieldRelayDeploymentPolicy.resolve(persisted, positions, owner.position());
		DEPLOYMENT_ANCHORS.put(key, anchor);
		for (DroneEntity drone : authorized) drone.adoptFieldMeshAnchor(anchor);
		return anchor;
	}

	private static List<FieldRelayMeshPolicy.Demand> demands(ServerLevel level, Vec3 origin,
		List<DroneEntity> fleet) {
		Map<String, FieldRelayMeshPolicy.Demand> demands = new LinkedHashMap<>();
		Key key = fleet.isEmpty() ? null : new Key(level, fleet.getFirst().ownerId());
		if (key != null) {
			Map<String, RequestedDemand> requested = REQUESTED.get(key);
			if (requested != null) {
				requested.values().removeIf(value -> value.expiresAt() < level.getGameTime());
				for (RequestedDemand value : requested.values())
					addDemand(demands, value.id(), value.target(), value.priority(), origin);
				if (requested.isEmpty()) REQUESTED.remove(key);
			}
		}
		for (DroneEntity drone : fleet) {
			if (drone.isPowerLost()) addDemand(demands, "LOST:" + drone.getUUID(), drone.position(), 500, origin);
			if (drone.strikeBeaconActive()) addDemand(demands, "BEACON:" + drone.missionId(), drone.position(), 450, origin);
			if (drone.combatActive() || drone.emergencyInterceptActive()) {
				Entity target = level.getEntity(drone.combatTargetId());
				addDemand(demands, "COMBAT:" + Math.max(0, drone.combatTargetId()),
					target == null ? drone.position() : target.position(), 400, origin);
			}
			if (drone.salvageTargetEntityId() >= 0)
				addDemand(demands, "SALVAGE:" + drone.salvageTargetEntityId(), drone.position(), 300, origin);
			if (drone.hasActiveFieldOperation()) addDemand(demands, "FIELD:" + drone.fieldOrderId(),
				Vec3.atCenterOf(drone.fieldAnchor()), 200, origin);
			if (drone.cargoState() != CargoState.UNASSIGNED) {
				BlockPos endpoint = drone.hasCargoTarget() ? drone.cargoTarget()
					: drone.hasCargoSource() ? drone.cargoSource() : drone.blockPosition();
				addDemand(demands, "CARGO:" + drone.missionId(), Vec3.atCenterOf(endpoint), 150, origin);
			}
			if (drone.hasPatrolRoute() || drone.hasWaypoint()) addDemand(demands,
				"ROUTE:" + (drone.missionId().isBlank() ? drone.groupId() : drone.missionId()),
				Vec3.atCenterOf(drone.hasWaypoint() ? drone.waypointPos() : drone.blockPosition()), 100, origin);
			if (drone.hasDock()) addDemand(demands, "DOCK:" + drone.dockPos().asLong(),
				Vec3.atCenterOf(drone.dockPos()), 180, origin);
		}
		return demands.values().stream()
			.sorted(Comparator.comparingInt(FieldRelayMeshPolicy.Demand::priority).reversed()
				.thenComparing(FieldRelayMeshPolicy.Demand::id))
			.limit(8).toList();
	}

	private static void addDemand(Map<String, FieldRelayMeshPolicy.Demand> demands, String id,
		Vec3 target, int priority, Vec3 origin) {
		if (target == null || target.subtract(origin).horizontalDistance() <= DataLinkRelayPolicy.DIRECT_RANGE) return;
		FieldRelayMeshPolicy.Demand current = demands.get(id);
		if (current == null || priority > current.priority()) demands.put(id,
			new FieldRelayMeshPolicy.Demand(id, new FieldRelayMeshPolicy.Point(target.x, origin.y, target.z), priority));
	}

	private static double initialFlightPlane(ServerLevel level, Vec3 origin) {
		double surface = Double.NEGATIVE_INFINITY;
		int[][] offsets = {{0, 0}, {TERRAIN_DATUM_RADIUS, 0}, {-TERRAIN_DATUM_RADIUS, 0},
			{0, TERRAIN_DATUM_RADIUS}, {0, -TERRAIN_DATUM_RADIUS},
			{TERRAIN_DATUM_RADIUS, TERRAIN_DATUM_RADIUS}, {TERRAIN_DATUM_RADIUS, -TERRAIN_DATUM_RADIUS},
			{-TERRAIN_DATUM_RADIUS, TERRAIN_DATUM_RADIUS}, {-TERRAIN_DATUM_RADIUS, -TERRAIN_DATUM_RADIUS}};
		for (int[] offset : offsets) {
			BlockPos column = BlockPos.containing(origin.x + offset[0], level.getSeaLevel(),
				origin.z + offset[1]);
			if (level.hasChunkAt(column)) surface = Math.max(surface,
				level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()));
		}
		if (!Double.isFinite(surface)) surface = level.getSeaLevel();
		// Fix the plane when deployment starts; later player movement must not drag the
		// whole mesh. A controller or the first authorized FIELD aircraft can already
		// be high above the terrain, however, and forcing that aircraft down to the
		// terrain-only plane can put it outside the controller's spherical radio range.
		double deploymentPlane = Math.max(surface + FieldRelayFlightPolicy.HOLD_CLEARANCE, origin.y);
		return Math.max(level.getMinY() + 12.0, Math.min(level.getMaxY() - 8.0,
			deploymentPlane));
	}

	private static FieldRelayMeshPolicy.Point point(Vec3 value) {
		return new FieldRelayMeshPolicy.Point(value.x, value.y, value.z);
	}
	private static Vec3 vec(FieldRelayMeshPolicy.Point value) { return new Vec3(value.x(), value.y(), value.z()); }

	record Assignment(UUID fieldId, String slotId, Vec3 target, FieldRelayMeshPolicy.Duty duty,
		String demandId, int sequence) {}
	record MeshStatus(String state, int assigned, int established, int baseline, int reserve,
		int dynamic, int shortage, double minX, double maxX, double minZ, double maxZ) {}
	record Snapshot(long tick, Vec3 origin, double flightPlaneY,
		Map<UUID, Assignment> assignments, FieldRelayMeshPolicy.Plan plan) {
		String slotId(UUID field) {
			Assignment assignment = assignments.get(field);
			return assignment == null ? "" : assignment.slotId();
		}
	}
	private record RequestedDemand(String id, Vec3 target, int priority, long expiresAt) {}
	private record Key(ServerLevel level, UUID owner) {}
}
