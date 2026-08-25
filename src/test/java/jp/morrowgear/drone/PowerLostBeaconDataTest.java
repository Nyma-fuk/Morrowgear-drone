package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PowerLostBeaconDataTest {
	@Test
	void recordsAreIsolatedByOwnerAndRemovedAfterRecovery() {
		PowerLostBeaconData data = new PowerLostBeaconData();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		data.update(first, "MG-DRN-A", "minecraft:overworld", 10, 64, 20, 100);
		data.update(second, "MG-DRN-B", "minecraft:the_nether", -8, 72, 4, 120);
		assertEquals(1, data.forOwner(first).size());
		assertEquals("MG-DRN-A", data.forOwner(first).getFirst().unitId());
		data.remove(first, "MG-DRN-A");
		assertTrue(data.forOwner(first).isEmpty());
		assertEquals(1, data.forOwner(second).size());
	}
}
