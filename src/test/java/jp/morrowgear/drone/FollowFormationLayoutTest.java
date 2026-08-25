package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FollowFormationLayoutTest {
	@Test
	void recoveryUnitDoesNotLeaveAnEmptySlotInActiveFollowRing() {
		List<Boolean> recovering = List.of(false, false, true, false, false);
		assertEquals(new FollowFormationLayout.Slot(0, 4, false), FollowFormationLayout.slotFor(0, recovering));
		assertEquals(new FollowFormationLayout.Slot(1, 4, false), FollowFormationLayout.slotFor(1, recovering));
		assertEquals(new FollowFormationLayout.Slot(2, 4, false), FollowFormationLayout.slotFor(3, recovering));
		assertEquals(new FollowFormationLayout.Slot(3, 4, false), FollowFormationLayout.slotFor(4, recovering));
	}

	@Test
	void recoveryUnitUsesAProvisionalRejoinSlotOutsideTheActiveLayout() {
		FollowFormationLayout.Slot slot = FollowFormationLayout.slotFor(2,
			List.of(false, false, true, false, false));
		assertEquals(4, slot.index());
		assertEquals(5, slot.count());
		assertTrue(slot.rejoining());
	}
}
