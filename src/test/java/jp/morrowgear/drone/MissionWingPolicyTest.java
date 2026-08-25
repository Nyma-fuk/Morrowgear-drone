package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

final class MissionWingPolicyTest {
	@Test
	void sameMissionInsideOneWingCanFormACohort() {
		assertTrue(MissionWingPolicy.sameMissionWing(
			"route-1", "WING-A", "route-1", "WING-A"));
	}

	@Test
	void dockedOrFlyingDifferentWingsNeverJoinTheSameMissionCohort() {
		assertFalse(MissionWingPolicy.sameMissionWing(
			"route-1", "WING-A", "route-1", "WING-B"));
	}

	@Test
	void differentMissionsInsideOneWingNeverMerge() {
		assertFalse(MissionWingPolicy.sameMissionWing(
			"route-1", "WING-A", "route-2", "WING-A"));
	}

	@Test
	void mixedSelectionIsPartitionedByWingInStableInputOrder() {
		record Member(String id, String wing, boolean docked) {}
		Member a1 = new Member("A1", "WING-A", true);
		Member b1 = new Member("B1", "WING-B", false);
		Member a2 = new Member("A2", "WING-A", false);
		assertEquals(List.of(List.of(a1, a2), List.of(b1)),
			MissionWingPolicy.partitionByWing(List.of(a1, b1, a2), Member::wing));
	}

	@Test
	void dockedAndFlyingMembersOfOneWingRemainTogether() {
		record Member(String id, String wing, boolean docked) {}
		Member docked = new Member("A1", "WING-A", true);
		Member flying = new Member("A2", "WING-A", false);
		assertEquals(List.of(List.of(docked, flying)),
			MissionWingPolicy.partitionByWing(List.of(docked, flying), Member::wing));
	}

	@Test
	void blankLegacyGroupsNormalizeWithoutJoiningNamedWings() {
		record Member(String id, String wing) {}
		Member blank = new Member("P1", "");
		Member alpha = new Member("P2", "ALPHA");
		Member wing = new Member("A1", "WING-A");
		assertEquals(List.of(List.of(blank, alpha), List.of(wing)),
			MissionWingPolicy.partitionByWing(List.of(blank, alpha, wing), Member::wing));
	}
}
