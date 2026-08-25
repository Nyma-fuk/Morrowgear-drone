package jp.morrowgear.drone;

import java.util.List;

final class FollowFormationLayout {
	private FollowFormationLayout() {
	}

	static Slot slotFor(int memberIndex, List<Boolean> recovering) {
		if (memberIndex < 0 || memberIndex >= recovering.size()) return new Slot(0, 1, false);
		int activeCount = 0;
		int activeIndex = -1;
		for (int index = 0; index < recovering.size(); index++) {
			if (recovering.get(index)) continue;
			if (index == memberIndex) activeIndex = activeCount;
			activeCount++;
		}
		if (activeIndex >= 0) return new Slot(activeIndex, Math.max(1, activeCount), false);
		return new Slot(activeCount, activeCount + 1, true);
	}

	record Slot(int index, int count, boolean rejoining) {
	}
}
