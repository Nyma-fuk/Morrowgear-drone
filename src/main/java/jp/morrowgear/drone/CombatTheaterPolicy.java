package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.phys.Vec3;

final class CombatTheaterPolicy {
	static final double CLUSTER_HORIZONTAL_DISTANCE = 12.0;
	static final double CLUSTER_VERTICAL_DISTANCE = 6.0;
	static final int MAX_UNITS_PER_TARGET = 8;
	private static final double RESERVE_RATIO = 0.20;

	private CombatTheaterPolicy() {}

	static Plan allocate(List<Contact> rawContacts, List<Unit> units) {
		List<Contact> contacts = deduplicate(rawContacts);
		if (contacts.isEmpty()) return Plan.empty(units.size());
		List<Cluster> clusters = clusters(contacts);
		Map<String, Assignment> assignments = new LinkedHashMap<>();
		Set<String> used = new HashSet<>();

		// An active attack pass owns its aircraft until the pass completes.
		for (Unit unit : units.stream().filter(Unit::committed)
			.sorted(Comparator.comparing(Unit::unitId)).toList()) {
			Contact contact = contacts.stream().filter(c -> c.entityId() == unit.currentTarget()).findFirst().orElse(null);
			if (contact == null || countFor(assignments, contact.entityId()) >= MAX_UNITS_PER_TARGET) continue;
			assignments.put(unit.unitId(), new Assignment(unit.unitId(), contact.entityId(),
				Math.max(0, unit.currentSlot()), 1, clusterId(clusters, contact.entityId()), contact));
			used.add(unit.unitId());
		}

		boolean operationalEmergency = contacts.stream().anyMatch(CombatTheaterPolicy::operationalEmergency);
		int eligible = (int)units.stream().filter(unit -> eligible(unit, operationalEmergency)).count();
		int reserve = contacts.size() <= 1 ? 0 : Math.max(1, (int)Math.ceil(eligible * RESERVE_RATIO));
		if (operationalEmergency) reserve = 0;
		int deploymentLimit = Math.max(assignments.size(), Math.max(0, eligible - reserve));

		List<Contact> queue = new ArrayList<>();
		for (Cluster cluster : clusters) {
			int demand = Math.max(clusterDemand(cluster), cluster.contacts().stream()
				.mapToInt(contact -> targetDemand(contact, contacts)).sum());
			List<Contact> ordered = cluster.contacts().stream().sorted(CONTACT_PRIORITY).toList();
			for (int index = 0; index < demand; index++) queue.add(ordered.get(index % ordered.size()));
		}
		queue.sort(CONTACT_PRIORITY);
		for (Contact contact : queue) {
			if (assignments.size() >= deploymentLimit) break;
			if (countFor(assignments, contact.entityId()) >= targetDemand(contact, contacts)) continue;
			Unit selected = units.stream().filter(unit -> !used.contains(unit.unitId()))
				.filter(unit -> eligible(unit, operationalEmergency(contact)))
				.min(unitComparator(contact)).orElse(null);
			if (selected == null) continue;
			used.add(selected.unitId());
			int slot = countFor(assignments, contact.entityId());
			assignments.put(selected.unitId(), new Assignment(selected.unitId(), contact.entityId(), slot,
				1, clusterId(clusters, contact.entityId()), contact));
		}

		Map<Integer, Integer> targetCounts = new HashMap<>();
		assignments.values().forEach(a -> targetCounts.merge(a.targetId(), 1, Integer::sum));
		Map<String, Assignment> normalized = new LinkedHashMap<>();
		assignments.values().stream().sorted(Comparator.comparing(Assignment::unitId)).forEach(a ->
			normalized.put(a.unitId(), new Assignment(a.unitId(), a.targetId(), a.slot(),
				targetCounts.getOrDefault(a.targetId(), 1), a.clusterId(), a.contact())));
		int actualReserve = Math.max(0, eligible - normalized.size());
		return new Plan(Map.copyOf(normalized), List.copyOf(clusters), contacts.size(), actualReserve);
	}

	private static List<Contact> deduplicate(List<Contact> contacts) {
		Map<Integer, Contact> byEntity = new HashMap<>();
		for (Contact contact : contacts) byEntity.merge(contact.entityId(), contact,
			(left, right) -> CONTACT_PRIORITY.compare(left, right) <= 0 ? left : right);
		return byEntity.values().stream().sorted(CONTACT_PRIORITY).toList();
	}

	private static List<Cluster> clusters(List<Contact> contacts) {
		List<List<Contact>> groups = new ArrayList<>();
		for (Contact contact : contacts) {
			List<List<Contact>> matches = groups.stream().filter(group -> group.stream()
				.anyMatch(other -> connected(contact, other))).toList();
			if (matches.isEmpty()) groups.add(new ArrayList<>(List.of(contact)));
			else {
				List<Contact> primary = matches.getFirst();
				primary.add(contact);
				for (int index = 1; index < matches.size(); index++) {
					primary.addAll(matches.get(index));
					groups.remove(matches.get(index));
				}
			}
		}
		List<Cluster> result = new ArrayList<>();
		groups.stream().sorted(Comparator.comparingInt(CombatTheaterPolicy::groupPriority).reversed())
			.forEach(group -> result.add(new Cluster("FRONT-" + String.format("%02d", result.size() + 1),
				List.copyOf(group), groupPriority(group))));
		return List.copyOf(result);
	}

	private static boolean connected(Contact left, Contact right) {
		double dx = left.position().x - right.position().x;
		double dz = left.position().z - right.position().z;
		return dx * dx + dz * dz <= CLUSTER_HORIZONTAL_DISTANCE * CLUSTER_HORIZONTAL_DISTANCE
			&& Math.abs(left.position().y - right.position().y) <= CLUSTER_VERTICAL_DISTANCE
			&& headingCompatible(left.velocity(), right.velocity()) && etaCompatible(left, right);
	}

	private static boolean headingCompatible(Vec3 left, Vec3 right) {
		Vec3 a = left.multiply(1, 0, 1);
		Vec3 b = right.multiply(1, 0, 1);
		if (a.lengthSqr() < 0.0025 || b.lengthSqr() < 0.0025) return true;
		return a.normalize().dot(b.normalize()) >= Math.cos(Math.toRadians(75.0));
	}

	private static boolean etaCompatible(Contact left, Contact right) {
		double leftSpeed = left.velocity().multiply(1, 0, 1).length();
		double rightSpeed = right.velocity().multiply(1, 0, 1).length();
		if (leftSpeed < 0.05 || rightSpeed < 0.05) return true;
		double leftEta = left.playerDistance() / leftSpeed;
		double rightEta = right.playerDistance() / rightSpeed;
		return Math.abs(leftEta - rightEta) <= 80.0;
	}

	private static int groupPriority(List<Contact> contacts) {
		int danger = contacts.stream().mapToInt(Contact::playerDanger).max().orElse(0);
		int mass = contacts.stream().mapToInt(Contact::enemyThreat).sum();
		int attrition = contacts.stream().mapToInt(Contact::attritionPressure).max().orElse(0);
		return danger * 4 + Math.min(300, mass) + attrition * 3 + contacts.size() * 5;
	}

	private static int clusterDemand(Cluster cluster) {
		int contacts = cluster.contacts().size();
		int threatMass = cluster.contacts().stream().mapToInt(Contact::enemyThreat).sum();
		int highValue = (int)cluster.contacts().stream().filter(c -> c.enemyThreat() >= 40).count();
		return Math.max(1, Math.min(contacts * MAX_UNITS_PER_TARGET,
			(int)Math.ceil(contacts * 0.50) + (int)Math.ceil(threatMass / 100.0) + highValue));
	}

	private static int targetDemand(Contact contact, List<Contact> contacts) {
		int localContacts = (int)contacts.stream().filter(other -> connected(contact, other)).count();
		int adaptive = GuardDispatchPolicy.responderCount(contact.enemyThreat(), contact.playerDanger(),
			contact.attritionPressure(), MAX_UNITS_PER_TARGET);
		int durable = CombatPolicy.requiredAttackers(localContacts, contact.targetHealth(), MAX_UNITS_PER_TARGET);
		return Math.min(MAX_UNITS_PER_TARGET, Math.max(1,
			Math.max(adaptive, durable) + Math.min(2, Math.max(0, localContacts - 1) / 8)));
	}

	private static boolean eligible(Unit unit, boolean emergency) {
		return unit.recoveryLevel() <= 0 && unit.weaponPower() >= GuardDispatchPolicy.MIN_WEAPON_RESERVE
			&& unit.battery() >= (emergency ? 5 : 15)
			&& (!unit.serviceReturn() || emergency && unit.emergencyServiceReady());
	}

	static boolean operationalEmergency(Contact contact) {
		return contact != null && (contact.playerDanger() >= 24 || contact.enemyThreat() >= 14
			|| contact.attritionPressure() >= 20);
	}

	private static Comparator<Unit> unitComparator(Contact contact) {
		return Comparator.comparingInt((Unit unit) -> unit.currentTarget() == contact.entityId() ? 0 : 1)
			// Preserve route and field Wings while ready reserve aircraft can satisfy demand.
			// Source-Wing affinity only breaks ties inside the same disruption class.
			.thenComparingInt(CombatTheaterPolicy::missionDisruptionRank)
			.thenComparingInt(unit -> unit.groupId().equals(contact.sourceWing()) ? 0 : 1)
			.thenComparingInt(unit -> unit.docked() ? 1 : 0)
			.thenComparingDouble(unit -> unit.position().distanceToSqr(contact.position()))
			.thenComparing(Unit::unitId);
	}

	private static int missionDisruptionRank(Unit unit) {
		if (unit.idle() && !unit.activeMission()) return 0;
		if (!unit.activeMission()) return 1;
		return 2;
	}

	private static int countFor(Map<String, Assignment> assignments, int targetId) {
		return (int)assignments.values().stream().filter(a -> a.targetId() == targetId).count();
	}

	private static String clusterId(List<Cluster> clusters, int targetId) {
		return clusters.stream().filter(cluster -> cluster.contacts().stream()
			.anyMatch(contact -> contact.entityId() == targetId)).map(Cluster::id).findFirst().orElse("FRONT-01");
	}

	private static final Comparator<Contact> CONTACT_PRIORITY = Comparator
		.comparingInt(Contact::playerDanger).reversed()
		.thenComparing(Comparator.comparingInt(Contact::enemyThreat).reversed())
		.thenComparing(Comparator.comparingInt(Contact::score).reversed())
		.thenComparingInt(Contact::entityId);

	record Contact(int entityId, int score, int enemyThreat, int playerDanger,
		float targetHealth, int attritionPressure, Vec3 position, Vec3 velocity,
		double playerDistance, String sourceWing, String sourceMission) {}
	record Unit(String unitId, String groupId, boolean idle, boolean activeMission, boolean docked,
		boolean serviceReturn, boolean committed, int currentTarget, int currentSlot, int battery,
		int weaponPower, int recoveryLevel, boolean emergencyServiceReady, Vec3 position) {}
	record Assignment(String unitId, int targetId, int slot, int count, String clusterId, Contact contact) {}
	record Cluster(String id, List<Contact> contacts, int priority) {}
	record Plan(Map<String, Assignment> assignments, List<Cluster> clusters, int hostileCount, int reserveCount) {
		static Plan empty(int reserve) { return new Plan(Map.of(), List.of(), 0, reserve); }
	}
}
