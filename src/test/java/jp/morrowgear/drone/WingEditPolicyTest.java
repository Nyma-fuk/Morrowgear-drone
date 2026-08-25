package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class WingEditPolicyTest {
	@Test
	void selectedUngroupedUnitsCreateOneWing() {
		var plan = WingEditPolicy.plan(List.of(
			new WingEditPolicy.Member("A", "ALPHA", true),
			new WingEditPolicy.Member("B", "ALPHA", true),
			new WingEditPolicy.Member("C", "ALPHA", false)), "WING-NEW");
		assertEquals(WingEditPolicy.EditKind.CREATE, plan.kind());
		assertEquals("WING-NEW", plan.targetGroup());
		assertEquals(List.of("A", "B"), plan.unitIds());
	}

	@Test
	void completeWingAndLooseUnitsJoinExistingWing() {
		var plan = WingEditPolicy.plan(List.of(
			new WingEditPolicy.Member("A", "WING-ONE", true),
			new WingEditPolicy.Member("B", "WING-ONE", true),
			new WingEditPolicy.Member("C", "ALPHA", true)), "WING-NEW");
		assertEquals(WingEditPolicy.EditKind.JOIN, plan.kind());
		assertEquals("WING-ONE", plan.targetGroup());
	}

	@Test
	void completeWingsMergeIntoLargestStableWing() {
		var plan = WingEditPolicy.plan(List.of(
			new WingEditPolicy.Member("A", "WING-SMALL", true),
			new WingEditPolicy.Member("B", "WING-LARGE", true),
			new WingEditPolicy.Member("C", "WING-LARGE", true)), "WING-NEW");
		assertEquals(WingEditPolicy.EditKind.MERGE, plan.kind());
		assertEquals("WING-LARGE", plan.targetGroup());
	}

	@Test
	void partialExistingWingSelectionCreatesDetachedWing() {
		var plan = WingEditPolicy.plan(List.of(
			new WingEditPolicy.Member("A", "WING-ONE", true),
			new WingEditPolicy.Member("B", "WING-ONE", false)), "WING-NEW");
		assertEquals(WingEditPolicy.EditKind.CREATE, plan.kind());
		assertEquals("WING-NEW", plan.targetGroup());
	}
}
