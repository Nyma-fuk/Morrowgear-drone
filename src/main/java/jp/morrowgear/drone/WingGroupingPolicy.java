package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WingGroupingPolicy {
	private static final int AUTOMATIC_WING_SIZE = 8;

	private WingGroupingPolicy() {
	}

	public static List<Assignment> assign(List<Member> members) {
		List<Assignment> result = new ArrayList<>(members.size());
		Map<String, Integer> missionWingNumbers = new LinkedHashMap<>();
		Map<String, Integer> manualWingCounts = new LinkedHashMap<>();
		for (Member member : members) {
			if (member.groupId().startsWith("WING-") || member.missionId().isBlank()
				|| member.missionExpected() <= 1) continue;
			String key = missionWingKey(member);
			missionWingNumbers.computeIfAbsent(key, ignored -> missionWingNumbers.size() + 1);
		}
		for (int index = 0; index < members.size(); index++) {
			Member member = members.get(index);
			if (member.groupId().startsWith("WING-")) {
				int manualIndex = manualWingCounts.getOrDefault(member.groupId(), 0);
				manualWingCounts.put(member.groupId(), manualIndex + 1);
				int partition = manualIndex / AUTOMATIC_WING_SIZE;
				String partitionKey = partition == 0 ? member.groupId() : member.groupId() + "-" + (partition + 1);
				result.add(new Assignment("manual:" + partitionKey, partitionKey));
			} else if (!member.missionId().isBlank() && member.missionExpected() > 1) {
				String key = missionWingKey(member);
				result.add(new Assignment(key,
					"MISSION-W" + String.format("%02d", missionWingNumbers.get(key))));
			} else {
				int wing = index / AUTOMATIC_WING_SIZE;
				result.add(new Assignment("auto:" + wing, "AUTO-W" + String.format("%02d", wing + 1)));
			}
		}
		return List.copyOf(result);
	}

	private static String missionWingKey(Member member) {
		return "mission:" + member.missionId() + "#" + member.wingIndex();
	}

	public record Member(String groupId, String missionId, int missionExpected, int wingIndex) {
	}

	public record Assignment(String key, String label) {
	}
}
