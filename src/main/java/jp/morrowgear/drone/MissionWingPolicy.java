package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class MissionWingPolicy {
	private MissionWingPolicy() {
	}

	public static boolean sameMissionWing(String missionId, String groupId,
		String candidateMissionId, String candidateGroupId) {
		return missionId != null && !missionId.isBlank()
			&& missionId.equals(candidateMissionId)
			&& normalizedGroup(groupId).equals(normalizedGroup(candidateGroupId));
	}

	public static String normalizedGroup(String groupId) {
		return groupId == null || groupId.isBlank() ? "ALPHA" : groupId;
	}

	public static <T> List<List<T>> partitionByWing(List<T> members,
		Function<T, String> groupId) {
		Map<String, List<T>> wings = new LinkedHashMap<>();
		for (T member : members) {
			wings.computeIfAbsent(normalizedGroup(groupId.apply(member)), ignored -> new ArrayList<>())
				.add(member);
		}
		return wings.values().stream().map(List::copyOf).toList();
	}
}
