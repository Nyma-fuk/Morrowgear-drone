package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WingGroupingPolicyTest {
	@Test
	void unassignedFleetIsSplitIntoAutomaticEightUnitWings() {
		List<WingGroupingPolicy.Member> members = new ArrayList<>();
		for (int index = 0; index < 18; index++) {
			members.add(new WingGroupingPolicy.Member("ALPHA", "", 1, 0));
		}
		List<WingGroupingPolicy.Assignment> assignments = WingGroupingPolicy.assign(members);
		assertEquals("AUTO-W01", assignments.get(0).label());
		assertEquals("AUTO-W01", assignments.get(7).label());
		assertEquals("AUTO-W02", assignments.get(8).label());
		assertEquals("AUTO-W03", assignments.get(16).label());
	}

	@Test
	void manualWingIdentitySurvivesMissionChanges() {
		var assignment = WingGroupingPolicy.assign(List.of(
			new WingGroupingPolicy.Member("WING-X-01", "mission-a", 24, 2))).getFirst();
		assertEquals("manual:WING-X-01", assignment.key());
		assertEquals("WING-X-01", assignment.label());
	}

	@Test
	void oversizedLegacyManualWingIsPresentedAsEightUnitPartitions() {
		List<WingGroupingPolicy.Member> members = new ArrayList<>();
		for (int index = 0; index < 10; index++) {
			members.add(new WingGroupingPolicy.Member("WING-LEGACY", "", 1, 0));
		}
		List<WingGroupingPolicy.Assignment> assignments = WingGroupingPolicy.assign(members);
		assertEquals("WING-LEGACY", assignments.get(7).label());
		assertEquals("WING-LEGACY-2", assignments.get(8).label());
	}

	@Test
	void activeAutomaticUnitsAreGroupedByTheirMissionWing() {
		var assignments = WingGroupingPolicy.assign(List.of(
			new WingGroupingPolicy.Member("ALPHA", "mission-a", 16, 0),
			new WingGroupingPolicy.Member("ALPHA", "mission-a", 16, 1)));
		assertEquals("MISSION-W01", assignments.get(0).label());
		assertEquals("MISSION-W02", assignments.get(1).label());
	}

	@Test
	void differentMissionsCannotProduceDuplicateVisibleWingLabels() {
		var assignments = WingGroupingPolicy.assign(List.of(
			new WingGroupingPolicy.Member("ALPHA", "mission-a", 8, 0),
			new WingGroupingPolicy.Member("ALPHA", "mission-a", 8, 0),
			new WingGroupingPolicy.Member("ALPHA", "mission-b", 8, 0)));
		assertEquals("MISSION-W01", assignments.get(0).label());
		assertEquals("MISSION-W01", assignments.get(1).label());
		assertEquals("MISSION-W02", assignments.get(2).label());
	}
}
