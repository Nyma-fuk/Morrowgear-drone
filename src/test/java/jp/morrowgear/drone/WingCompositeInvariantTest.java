package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

final class WingCompositeInvariantTest {
	@TestFactory
	Stream<DynamicTest> automaticFleetSizesFromOneToOneHundredStayWithinEightPerWing() {
		return IntStream.rangeClosed(1, 100).mapToObj(size -> DynamicTest.dynamicTest("fleet size " + size, () -> {
			List<WingGroupingPolicy.Member> members = IntStream.range(0, size)
				.mapToObj(index -> new WingGroupingPolicy.Member("ALPHA", "", 1, 0)).toList();
			assertWingCapacityAndCoverage(size, WingGroupingPolicy.assign(members));
		}));
	}

	@TestFactory
	Stream<DynamicTest> missionAndManualWingsStayStableAcrossRoleMixesAndScales() {
		return IntStream.rangeClosed(1, 64).mapToObj(size -> DynamicTest.dynamicTest("mixed fleet " + size, () -> {
			List<WingGroupingPolicy.Member> members = new ArrayList<>();
			for (int index = 0; index < size; index++) {
				String group = index % 3 == 0 ? "WING-MANUAL" : "ALPHA";
				String mission = index % 3 == 1 ? "mission-shared" : "";
				members.add(new WingGroupingPolicy.Member(group, mission, mission.isBlank() ? 1 : size, index / 8));
			}
			assertWingCapacityAndCoverage(size, WingGroupingPolicy.assign(members));
		}));
	}

	@TestFactory
	Stream<DynamicTest> membershipBoundariesAreDeterministicForEveryTargetSize() {
		return IntStream.rangeClosed(-1, 10).mapToObj(size -> DynamicTest.dynamicTest("target size " + size, () -> {
			WingMembershipPolicy.Result expected = size < 0 ? WingMembershipPolicy.Result.INVALID_WING
				: size >= 8 ? WingMembershipPolicy.Result.WING_FULL : WingMembershipPolicy.Result.ACCEPTED;
			assertEquals(expected, WingMembershipPolicy.evaluate("ALPHA", "WING-TEST", size));
			assertEquals(WingMembershipPolicy.Result.UNCHANGED,
				WingMembershipPolicy.evaluate("WING-TEST", "WING-TEST", size));
		}));
	}

	private static void assertWingCapacityAndCoverage(int expected,
		List<WingGroupingPolicy.Assignment> assignments) {
		assertEquals(expected, assignments.size());
		Map<String, Integer> counts = new LinkedHashMap<>();
		assignments.forEach(assignment -> counts.merge(assignment.key(), 1, Integer::sum));
		assertTrue(counts.values().stream().allMatch(count -> count <= WingMembershipPolicy.MAX_MEMBERS), counts::toString);
	}
}
