package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class CarrierCargoInventoryIndexTest {
    @Test void indexesAllPagesAndReturnsOnlyMatchingSlots() {
        CarrierCargoInventoryIndex index = new CarrierCargoInventoryIndex();
        Map<Integer, CarrierCargoInventoryIndex.SlotValue> values = Map.of(
            0, value("morrowgear_drone:laser_cell", 8, true),
            54, value("morrowgear_drone:laser_cell", 5, true),
            1727, value("minecraft:stone", 64, false));

        index.rebuild(1728, slot -> values.getOrDefault(slot, value("", 0, false)));

        assertEquals(1728, index.size());
        assertEquals(3, index.usedSlots());
        assertEquals(77, index.totalItems());
        assertEquals(13, index.count("morrowgear_drone:laser_cell"));
        assertArrayEquals(new int[] {0, 54}, index.slots("morrowgear_drone:laser_cell"));
        assertArrayEquals(new int[0], index.slots("minecraft:stone"), "non-extractable inventory must not leak into logistics");
        assertEquals(1725, index.emptySlotCount());
    }

    @Test void rebuildReplacesStaleCountsInsteadOfAccumulatingThem() {
        CarrierCargoInventoryIndex index = new CarrierCargoInventoryIndex();
        index.rebuild(1728, slot -> slot == 1200 ? value("morrowgear_drone:micro_missile_pack", 20, true)
            : value("", 0, false));
        assertEquals(20, index.count("morrowgear_drone:micro_missile_pack"));

        index.rebuild(1728, slot -> slot == 31 ? value("morrowgear_drone:autocannon_magazine", 3, true)
            : value("", 0, false));
        assertEquals(0, index.count("morrowgear_drone:micro_missile_pack"));
        assertEquals(3, index.count("morrowgear_drone:autocannon_magazine"));
        assertArrayEquals(new int[] {31}, index.slots("morrowgear_drone:autocannon_magazine"));
    }

    private static CarrierCargoInventoryIndex.SlotValue value(String item, int count, boolean extractable) {
        return new CarrierCargoInventoryIndex.SlotValue(item, count, extractable);
    }
}
