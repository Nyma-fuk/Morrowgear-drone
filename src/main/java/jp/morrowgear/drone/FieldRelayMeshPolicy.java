package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deterministic horizontal planning for FIELD baseline and dynamic relay slots. */
public final class FieldRelayMeshPolicy {
	public static final int MAX_ACTIVE_RELAYS = 16;
	public static final double TARGET_SPACING = 88.0;
	public static final double MIN_SEPARATION = 44.0;

	private FieldRelayMeshPolicy() {}

	public enum Duty { BASELINE, DYNAMIC, MESH_RESERVE }
	public record Point(double x, double y, double z) {
		double distanceTo(Point other) {
			double dx = x - other.x, dy = y - other.y, dz = z - other.z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
		Point interpolate(Point other, double ratio) {
			return new Point(x + (other.x - x) * ratio, y + (other.y - y) * ratio,
				z + (other.z - z) * ratio);
		}
	}
	public record Field(UUID id, Point position, int energyPercent, String previousSlot) {}
	public record Demand(String id, Point point, int priority) {}
	public record Slot(String id, Point point, Duty duty, String demandId, int sequence) {}
	public record Assignment(UUID fieldId, Slot slot) {}
	public record Plan(List<Assignment> assignments, int shortage, int baseline,
		int reserve, int dynamic, double minX, double maxX, double minZ, double maxZ) {
		public boolean complete() { return shortage == 0; }
	}

	public static int reserveCount(int available) {
		return available >= 10 ? 2 : available >= 4 ? 1 : 0;
	}

	public static Plan plan(Point origin, List<Field> available, List<Demand> requested) {
		List<Field> fields = available.stream()
			.sorted(Comparator.comparingInt(Field::energyPercent).reversed()
				.thenComparing(field -> field.id().toString()))
			.limit(MAX_ACTIVE_RELAYS).toList();
		if (fields.isEmpty()) return new Plan(List.of(), requiredSlots(origin, requested), 0, 0, 0,
			origin.x(), origin.x(), origin.z(), origin.z());

		List<Demand> demands = requested.stream()
			.sorted(Comparator.comparingInt(Demand::priority).reversed().thenComparing(Demand::id))
			.toList();
		// Dynamic slots already form a contiguous chain out of the controller's
		// direct range, so a separate baseline slot is not required while demand
		// exists. Even a single FIELD aircraft must therefore be allowed to move
		// toward the highest-priority gap instead of circling the controller.
		int maximumDynamic = fields.size();
		List<Slot> dynamicSlots = new ArrayList<>();
		int required = 0;
		for (Demand demand : demands) {
			int count = chainSlots(origin, demand.point());
			required += count;
			for (int sequence = 1; sequence <= count && dynamicSlots.size() < maximumDynamic; sequence++) {
				double ratio = sequence / (double)(count + 1);
				Point point = origin.interpolate(demand.point(), ratio);
				dynamicSlots.add(new Slot("D:" + demand.id() + ":" + sequence, point,
					Duty.DYNAMIC, demand.id(), sequence));
			}
		}

		int remaining = fields.size() - dynamicSlots.size();
		int reserve = Math.max(0, reserveCount(fields.size()) - dynamicSlots.size());
		reserve = Math.min(reserve, Math.max(0, remaining - 1));
		int baseline = remaining - reserve;
		List<Slot> slots = new ArrayList<>(dynamicSlots);
		slots.addAll(baselineSlots(origin, baseline, Duty.BASELINE, 0));
		slots.addAll(baselineSlots(origin, reserve, Duty.MESH_RESERVE, baseline));
		List<Assignment> assignments = assign(fields, slots);
		double minX = origin.x(), maxX = origin.x(), minZ = origin.z(), maxZ = origin.z();
		for (Slot slot : slots) {
			minX = Math.min(minX, slot.point().x()); maxX = Math.max(maxX, slot.point().x());
			minZ = Math.min(minZ, slot.point().z()); maxZ = Math.max(maxZ, slot.point().z());
		}
		return new Plan(List.copyOf(assignments), Math.max(0, required - dynamicSlots.size()),
			baseline, reserve, dynamicSlots.size(), minX, maxX, minZ, maxZ);
	}

	private static int requiredSlots(Point origin, List<Demand> demands) {
		return demands.stream().mapToInt(demand -> chainSlots(origin, demand.point())).sum();
	}

	static int chainSlots(Point origin, Point target) {
		double distance = origin.distanceTo(target);
		if (distance <= DataLinkRelayPolicy.DIRECT_RANGE) return 0;
		return Math.max(0, (int)Math.ceil(distance / TARGET_SPACING) - 1);
	}

	private static List<Slot> baselineSlots(Point origin, int count, Duty duty, int indexOffset) {
		List<Slot> slots = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			int global = indexOffset + index;
			int ring = global / 6 + 1;
			int position = global % 6;
			double angle = position * Math.PI / 3.0 + (ring % 2) * Math.PI / 6.0;
			double radius = TARGET_SPACING * ring;
			Point point = new Point(origin.x() + Math.cos(angle) * radius, origin.y(),
				origin.z() + Math.sin(angle) * radius);
			slots.add(new Slot((duty == Duty.MESH_RESERVE ? "R:" : "B:") + global,
				point, duty, "", global));
		}
		return slots;
	}

	private static List<Assignment> assign(List<Field> fields, List<Slot> slots) {
		Map<String, Slot> byId = new LinkedHashMap<>();
		for (Slot slot : slots) byId.put(slot.id(), slot);
		Set<UUID> usedFields = new HashSet<>();
		Set<String> usedSlots = new HashSet<>();
		List<Assignment> result = new ArrayList<>();
		for (Field field : fields) {
			Slot previous = byId.get(field.previousSlot());
			if (previous == null || !usedSlots.add(previous.id())) continue;
			usedFields.add(field.id());
			result.add(new Assignment(field.id(), previous));
		}
		Map<UUID, Field> remaining = new HashMap<>();
		for (Field field : fields) if (!usedFields.contains(field.id())) remaining.put(field.id(), field);
		for (Slot slot : slots) {
			if (usedSlots.contains(slot.id())) continue;
			Field field = remaining.values().stream()
				.min(Comparator.comparingDouble((Field value) -> value.position().distanceTo(slot.point()))
					.thenComparing(value -> value.id().toString())).orElse(null);
			if (field == null) break;
			remaining.remove(field.id());
			result.add(new Assignment(field.id(), slot));
		}
		result.sort(Comparator.comparing(assignment -> assignment.fieldId().toString()));
		return result;
	}
}
