package jp.morrowgear.drone.carrier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Finite server hold. Mining copies only changed slots; menus expose one fixed page. */
public final class CarrierCargoStorage implements Container {
    private final ItemStack[] stacks = new ItemStack[CarrierPolicy.STORAGE_SLOTS];
    private final Item[] indexedItems = new Item[stacks.length];
    private final int[] indexedCounts = new int[stacks.length];
    private final BitSet empty = new BitSet(stacks.length), occupied = new BitSet(stacks.length);
    private final Map<Item, BitSet> partial = new IdentityHashMap<>();
    private final Runnable dirty;
    private boolean indexDirty;
    private long revision;
    private long items;
    private long plans, failedPlans, examinedSlots, copiedStacks, committedPlans, committedSlots;
    private long indexRefreshSlots, planNanos, maxPlanNanos;
    private long lookupCalls, lookupSlots;

    public CarrierCargoStorage(List<ItemStack> saved, Runnable dirty) {
        if (saved.size() > stacks.length) throw new IllegalArgumentException("Oversized carrier cargo");
        this.dirty = dirty;
        Arrays.fill(stacks, ItemStack.EMPTY);
        empty.set(0, stacks.length);
        for (int slot = 0; slot < saved.size(); slot++) {
            stacks[slot] = saved.get(slot).copy();
            reindex(slot);
        }
        indexRefreshSlots = 0;
    }

    @Override public int getContainerSize() { return stacks.length; }
    @Override public int getMaxStackSize() { return 64; }
    @Override public boolean isEmpty() { return usedSlots() == 0; }
    @Override public ItemStack getItem(int slot) { checkSlot(slot); return stacks[slot]; }
    @Override public boolean stillValid(Player player) { return true; }

    @Override public void setItem(int slot, ItemStack stack) {
        checkSlot(slot);
        stacks[slot] = stack.copy();
        if (!stacks[slot].isEmpty()) stacks[slot].setCount(Math.min(stacks[slot].getCount(), limit(stacks[slot])));
        reindex(slot);
        revision++;
        dirty.run();
    }

    @Override public ItemStack removeItem(int slot, int count) {
        checkSlot(slot);
        if (count <= 0 || stacks[slot].isEmpty()) return ItemStack.EMPTY;
        ItemStack removed = stacks[slot].split(count);
        if (stacks[slot].isEmpty()) stacks[slot] = ItemStack.EMPTY;
        reindex(slot);
        revision++;
        dirty.run();
        return removed;
    }

    @Override public ItemStack removeItemNoUpdate(int slot) {
        checkSlot(slot);
        ItemStack removed = stacks[slot];
        stacks[slot] = ItemStack.EMPTY;
        reindex(slot);
        revision++;
        if (!removed.isEmpty()) dirty.run();
        return removed;
    }

    @Override public void clearContent() {
        Arrays.fill(stacks, ItemStack.EMPTY);
        Arrays.fill(indexedItems, null);
        Arrays.fill(indexedCounts, 0);
        partial.clear(); occupied.clear(); empty.set(0, stacks.length);
        items = 0; indexDirty = false;
        revision++;
        dirty.run();
    }

    /** External callers that mutate a returned stack must notify the container. */
    @Override public void setChanged() { indexDirty = true; revision++; dirty.run(); }

    public int usedSlots() { ensureIndex(); return occupied.cardinality(); }
    public long itemCount() { ensureIndex(); return items; }

    /** Predicate is read-only; empty pages are skipped without copying their stacks. */
    public int findFirst(Predicate<ItemStack> predicate) {
        ensureIndex();
        lookupCalls++;
        for (int slot = occupied.nextSetBit(0); slot >= 0; slot = occupied.nextSetBit(slot + 1)) {
            lookupSlots++;
            if (predicate.test(stacks[slot])) return slot;
        }
        return -1;
    }

    public List<ItemStack> snapshot() {
        ensureIndex();
        List<ItemStack> result = new ArrayList<>(occupied.length());
        for (int slot = 0; slot < occupied.length(); slot++) result.add(stacks[slot].copy());
        return result;
    }

    public Container page(int page) {
        if (page < 0 || page >= CarrierPolicy.CARGO_PAGES) throw new IllegalArgumentException("Invalid cargo page");
        return new Page(page * CarrierPolicy.CARGO_SLOTS);
    }

    public Optional<Plan> plan(List<ItemStack> incoming) {
        long start = System.nanoTime();
        plans++;
        try {
            ensureIndex();
            Map<Integer, Change> changes = new LinkedHashMap<>();
            for (ItemStack drop : incoming) {
                if (drop.isEmpty()) continue;
                int left = drop.getCount();
                for (Change change : changes.values()) {
                    if (left == 0) break;
                    examinedSlots++;
                    if (ItemStack.isSameItemSameComponents(change.after, drop)) {
                        int added = Math.min(left, Math.max(0, limit(change.after) - change.after.getCount()));
                        change.after.grow(added);
                        left -= added;
                    }
                }
                BitSet candidates = partial.get(drop.getItem());
                for (int slot = candidates == null ? -1 : candidates.nextSetBit(0);
                     slot >= 0 && left > 0; slot = candidates.nextSetBit(slot + 1)) {
                    if (changes.containsKey(slot)) continue;
                    examinedSlots++;
                    ItemStack current = stacks[slot];
                    if (!ItemStack.isSameItemSameComponents(current, drop)) continue;
                    int added = Math.min(left, Math.max(0, limit(current) - current.getCount()));
                    if (added == 0) continue;
                    ItemStack before = copy(current), after = copy(current);
                    after.grow(added);
                    changes.put(slot, new Change(before, after));
                    left -= added;
                }
                for (int slot = empty.nextSetBit(0); slot >= 0 && left > 0; slot = empty.nextSetBit(slot + 1)) {
                    if (changes.containsKey(slot)) continue;
                    examinedSlots++;
                    int added = Math.min(left, limit(drop));
                    if (added <= 0) break;
                    ItemStack after = drop.copyWithCount(added);
                    copiedStacks++;
                    changes.put(slot, new Change(ItemStack.EMPTY, after));
                    left -= added;
                }
                if (left > 0) { failedPlans++; return Optional.empty(); }
            }
            return Optional.of(new Plan(this, changes));
        } finally {
            long elapsed = System.nanoTime() - start;
            planNanos += elapsed;
            maxPlanNanos = Math.max(maxPlanNanos, elapsed);
        }
    }

    public boolean canCommit(Plan plan) {
        if (plan == null || plan.owner != this || plan.committed || plan.revision != revision) return false;
        return plan.changes.entrySet().stream().allMatch(entry ->
            same(stacks[entry.getKey()], entry.getValue().before));
    }

    public void commit(Plan plan) {
        if (!canCommit(plan)) throw new IllegalStateException("Stale or already committed cargo plan");
        ensureIndex();
        plan.committed = true;
        for (var entry : plan.changes.entrySet()) {
            stacks[entry.getKey()] = copy(entry.getValue().after);
            reindex(entry.getKey());
        }
        committedPlans++;
        committedSlots += plan.changes.size();
        revision++;
        if (!plan.changes.isEmpty()) dirty.run();
    }

    public Metrics metrics() {
        return new Metrics(plans, failedPlans, examinedSlots, copiedStacks, committedPlans, committedSlots,
            indexRefreshSlots, planNanos, maxPlanNanos, lookupCalls, lookupSlots);
    }

    public record Metrics(long plans, long failedPlans, long examinedSlots, long copiedStacks,
                          long committedPlans, long committedSlots, long indexRefreshSlots,
                          long planNanos, long maxPlanNanos, long lookupCalls, long lookupSlots) {}

    public static final class Plan {
        private final CarrierCargoStorage owner;
        private final Map<Integer, Change> changes;
        private final long revision;
        private boolean committed;
        private Plan(CarrierCargoStorage owner, Map<Integer, Change> changes) {
            this.owner = owner; this.changes = changes; this.revision = owner.revision;
        }
        public int changedSlots() { return changes.size(); }
    }

    private record Change(ItemStack before, ItemStack after) {}
    private ItemStack copy(ItemStack stack) { if (!stack.isEmpty()) copiedStacks++; return stack.copy(); }
    private static int limit(ItemStack stack) { return Math.min(64, stack.getMaxStackSize()); }
    private static boolean same(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && (a.isEmpty() && b.isEmpty() || ItemStack.isSameItemSameComponents(a, b));
    }
    private void checkSlot(int slot) {
        if (slot < 0 || slot >= stacks.length) throw new IndexOutOfBoundsException(slot);
    }
    private void ensureIndex() {
        if (!indexDirty) return;
        for (int slot = 0; slot < stacks.length; slot++) reindex(slot);
        indexDirty = false;
    }
    private void reindex(int slot) {
        indexRefreshSlots++;
        Item previous = indexedItems[slot];
        if (previous != null) {
            BitSet candidates = partial.get(previous);
            if (candidates != null) {
                candidates.clear(slot);
                if (candidates.isEmpty()) partial.remove(previous);
            }
        }
        ItemStack stack = stacks[slot];
        int count = stack.isEmpty() ? 0 : stack.getCount();
        items += (long) count - indexedCounts[slot];
        indexedCounts[slot] = count;
        indexedItems[slot] = count == 0 ? null : stack.getItem();
        empty.set(slot, count == 0);
        occupied.set(slot, count != 0);
        if (count > 0 && count < limit(stack)) partial.computeIfAbsent(stack.getItem(), key -> new BitSet()).set(slot);
    }

    private final class Page implements Container {
        private final int offset;
        private Page(int offset) { this.offset = offset; }
        private int global(int slot) {
            if (slot < 0 || slot >= CarrierPolicy.CARGO_SLOTS) throw new IndexOutOfBoundsException(slot);
            return offset + slot;
        }
        @Override public int getContainerSize() { return CarrierPolicy.CARGO_SLOTS; }
        @Override public int getMaxStackSize() { return CarrierCargoStorage.this.getMaxStackSize(); }
        @Override public boolean isEmpty() {
            for (int i = 0; i < getContainerSize(); i++) if (!getItem(i).isEmpty()) return false;
            return true;
        }
        @Override public ItemStack getItem(int slot) { return CarrierCargoStorage.this.getItem(global(slot)); }
        @Override public void setItem(int slot, ItemStack stack) { CarrierCargoStorage.this.setItem(global(slot), stack); }
        @Override public ItemStack removeItem(int slot, int count) { return CarrierCargoStorage.this.removeItem(global(slot), count); }
        @Override public ItemStack removeItemNoUpdate(int slot) { return CarrierCargoStorage.this.removeItemNoUpdate(global(slot)); }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public void setChanged() {
            for (int slot = offset; slot < offset + getContainerSize(); slot++) reindex(slot);
            revision++;
            dirty.run();
        }
        @Override public void clearContent() {
            for (int slot = offset; slot < offset + getContainerSize(); slot++) {
                stacks[slot] = ItemStack.EMPTY;
                reindex(slot);
            }
            revision++;
            dirty.run();
        }
    }
}
