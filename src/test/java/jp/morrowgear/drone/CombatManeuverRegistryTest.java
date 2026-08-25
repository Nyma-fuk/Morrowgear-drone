package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class CombatManeuverRegistryTest {
	@AfterEach
	void clearRegistry() {
		CombatManeuverRegistry.clear();
	}

	@Test
	void engagementEpochAndAttackAxisRemainStableWhenParticipantsChange() {
		UUID owner = UUID.randomUUID();
		long epoch = CombatManeuverRegistry.epoch("overworld", owner, 42, "A|GUN", 100);
		Vec3 axis = CombatManeuverRegistry.axis("overworld", owner, 42, "A|GUN",
			new Vec3(1, 0, 0), 100);
		assertEquals(epoch, CombatManeuverRegistry.epoch("overworld", owner, 42, "A|GUN", 180));
		assertEquals(axis, CombatManeuverRegistry.axis("overworld", owner, 42, "A|GUN",
			new Vec3(0, 0, 1), 180));
	}

	@Test
	void separateCombatChannelsReceiveIndependentPlans() {
		UUID owner = UUID.randomUUID();
		Vec3 first = CombatManeuverRegistry.axis("overworld", owner, 42, "A|GUN",
			new Vec3(1, 0, 0), 100);
		Vec3 second = CombatManeuverRegistry.axis("overworld", owner, 42, "B|GUN",
			new Vec3(0, 0, 1), 120);
		assertNotEquals(first, second);
	}
}
