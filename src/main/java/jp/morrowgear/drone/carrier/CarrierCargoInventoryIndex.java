package jp.morrowgear.drone.carrier;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Change-driven summary for the 1,728-slot ground cargo container.
 * Queries are proportional to the number of matching slots, not total capacity.
 */
public final class CarrierCargoInventoryIndex {
    public record SlotValue(String item, int count, boolean extractable) {
        public SlotValue {
            item = item == null ? "" : item;
            if (count < 0) throw new IllegalArgumentException("Negative item count");
        }
        public boolean empty() { return item.isBlank() || count == 0; }
    }

    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, BitSet> slots = new LinkedHashMap<>();
    private final BitSet emptySlots = new BitSet();
    private int size;
    private int usedSlots;
    private long totalItems;

    public void rebuild(int inventorySize, IntFunction<SlotValue> reader) {
        if (inventorySize < 0) throw new IllegalArgumentException("Negative inventory size");
        counts.clear();
        slots.clear();
        emptySlots.clear();
        size = inventorySize;
        usedSlots = 0;
        totalItems = 0;
        for (int slot = 0; slot < inventorySize; slot++) {
            SlotValue value = reader.apply(slot);
            if (value == null || value.empty()) {
                emptySlots.set(slot);
                continue;
            }
            usedSlots++;
            totalItems += value.count();
            if (!value.extractable()) continue;
            counts.merge(value.item(), value.count(), Integer::sum);
            slots.computeIfAbsent(value.item(), ignored -> new BitSet()).set(slot);
        }
    }

    public int size() { return size; }
    public int usedSlots() { return usedSlots; }
    public long totalItems() { return totalItems; }
    public int count(String item) { return counts.getOrDefault(item, 0); }
    public Map<String, Integer> counts() { return Map.copyOf(counts); }
    public int emptySlotCount() { return emptySlots.cardinality(); }
    public int[] slots(String item) { return bits(slots.get(item)); }
    public int[] emptySlots() { return bits(emptySlots); }

    private static int[] bits(BitSet values) {
        if (values == null || values.isEmpty()) return new int[0];
        int[] result = new int[values.cardinality()];
        int output = 0;
        for (int slot = values.nextSetBit(0); slot >= 0; slot = values.nextSetBit(slot + 1)) result[output++] = slot;
        return result;
    }
}
