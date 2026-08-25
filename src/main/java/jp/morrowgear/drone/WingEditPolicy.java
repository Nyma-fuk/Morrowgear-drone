package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WingEditPolicy {
	private WingEditPolicy() {
	}

	public static EditPlan plan(List<Member> fleet, String generatedGroup) {
		List<Member> selected = fleet.stream().filter(Member::selected).toList();
		if (selected.isEmpty()) return new EditPlan("", List.of(), EditKind.NONE);

		Map<String, List<Member>> manualGroups = new LinkedHashMap<>();
		for (Member member : fleet) {
			if (isManual(member.groupId())) {
				manualGroups.computeIfAbsent(member.groupId(), ignored -> new java.util.ArrayList<>()).add(member);
			}
		}
		List<Map.Entry<String, List<Member>>> fullySelected = manualGroups.entrySet().stream()
			.filter(entry -> entry.getValue().stream().allMatch(Member::selected))
			.sorted(Comparator.<Map.Entry<String, List<Member>>>comparingInt(entry -> entry.getValue().size())
				.reversed().thenComparing(Map.Entry::getKey))
			.toList();
		String target = fullySelected.isEmpty() ? generatedGroup : fullySelected.getFirst().getKey();
		EditKind kind = fullySelected.size() > 1 ? EditKind.MERGE
			: fullySelected.size() == 1 ? EditKind.JOIN : EditKind.CREATE;
		return new EditPlan(target, selected.stream().map(Member::unitId).toList(), kind);
	}

	private static boolean isManual(String groupId) {
		return groupId != null && groupId.startsWith("WING-");
	}

	public enum EditKind { NONE, CREATE, JOIN, MERGE }

	public record Member(String unitId, String groupId, boolean selected) {
	}

	public record EditPlan(String targetGroup, List<String> unitIds, EditKind kind) {
	}
}
