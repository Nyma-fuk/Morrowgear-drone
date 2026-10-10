package jp.morrowgear.drone.carrier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jp.morrowgear.drone.DockSupplyPolicy;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class CarrierCargoContainerBlockEntity extends BaseContainerBlockEntity
    implements CarrierExpansionContainer, ExtendedMenuProvider<CarrierCargoContainerPayload> {
    public static final int SIZE = CarrierPolicy.STORAGE_SLOTS_PER_MODULE;
    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private final CarrierCargoInventoryIndex index = new CarrierCargoInventoryIndex();
    private UUID cargoId = UUID.randomUUID();
    private UUID owner = new UUID(0, 0);
    private boolean transferReserved;
    private boolean indexDirty = true;
    private List<LogisticsStock> indexedSupplyStocks = List.of();
    private String indexedContentsSummary = "空";

    public record LogisticsStock(String item, DockSupplyPolicy.SupplyKind kind, int count, ItemStack sample) {}

    public CarrierCargoContainerBlockEntity(BlockPos pos, BlockState state) {
        super(CarrierModule.CARGO_CONTAINER_BLOCK_ENTITY, pos, state);
    }

    public void initialize(ServerPlayer player, ItemStack source) {
        initialize(player.getUUID(), source);
    }
    public void initialize(UUID placedOwner, ItemStack source) {
        owner = placedOwner;
        var identity = CarrierExpansionItemData.read(source);
        if (identity != null && identity.owner().equals(owner)) cargoId = identity.id();
        ItemContainerContents contents = source.getOrDefault(net.minecraft.core.component.DataComponents.CONTAINER,
            ItemContainerContents.EMPTY);
        contents.copyInto(items);
        indexDirty = true;
        setChanged();
        if (level instanceof ServerLevel serverLevel) CarrierContainerFootprint.ensure(serverLevel, getBlockPos());
    }

    public UUID cargoId() { return cargoId; }
    @Override public UUID moduleId() { return cargoId; }
    public UUID ownerId() { return owner; }
    @Override public CarrierExpansionPolicy.Type moduleType() { return CarrierExpansionPolicy.Type.CARGO; }
    public boolean transferReserved() { return transferReserved; }
    public void setTransferReserved(boolean value) { transferReserved = value; setChanged(); }
    public boolean mayAccess(ServerPlayer player) { return owner.equals(player.getUUID()) && !transferReserved; }

    @Override public void setLevel(net.minecraft.world.level.Level value) {
        super.setLevel(value);
        if (value instanceof ServerLevel serverLevel) CarrierContainerFootprint.ensureAfterLoad(serverLevel, getBlockPos());
        if(value instanceof ServerLevel serverLevel&&!owner.equals(new UUID(0,0))){
            var identity=new CarrierExpansionItemData.Identity(cargoId,owner,moduleType());
            if(!CarrierExpansionTransactions.placed(serverLevel,identity,getBlockPos()).success())transferReserved=true;
        }
    }

    @Override public ItemStack recoveryItem() {
        ItemStack result = new ItemStack(CarrierModule.CARGO_MODULE_ITEM);
        CarrierExpansionItemData.write(result, new CarrierExpansionItemData.Identity(cargoId, owner,
            CarrierExpansionPolicy.Type.CARGO));
        result.set(net.minecraft.core.component.DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        return result;
    }

    @Override protected Component getDefaultName() { return Component.translatable("container.morrowgear_drone.carrier_cargo"); }
    @Override public int getContainerSize() { return SIZE; }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> value) { items = value; indexDirty = true; }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new CarrierCargoContainerMenu(id, inventory, this);
    }
    @Override public CarrierCargoContainerPayload getScreenOpeningData(ServerPlayer player) {
        return new CarrierCargoContainerPayload(getBlockPos().asLong(), 0);
    }

    @Override public void setChanged() {
        super.setChanged();
        indexDirty = true;
    }

    @Override public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = super.removeItemNoUpdate(slot);
        if (!removed.isEmpty()) indexDirty = true;
        return removed;
    }

    @Override public void clearContent() {
        super.clearContent();
        setChanged();
    }

    public boolean logisticsAccessible(UUID requester) {
        return requester != null && owner.equals(requester) && !transferReserved;
    }

    public int indexedCount(String item) { ensureIndex(); return index.count(item); }
    public int[] indexedSlots(String item) { ensureIndex(); return index.slots(item); }
    public int indexedUsedSlots() { ensureIndex(); return index.usedSlots(); }
    public long indexedItemCount() { ensureIndex(); return index.totalItems(); }

    public List<LogisticsStock> indexedSupplyStocks() {
        ensureIndex();
        return indexedSupplyStocks;
    }

    private List<LogisticsStock> buildSupplyStocks() {
        List<LogisticsStock> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : index.counts().entrySet()) {
            int[] slots = index.slots(entry.getKey());
            if (slots.length == 0) continue;
            ItemStack sample = items.get(slots[0]);
            DockSupplyPolicy.SupplyKind kind = DockSupplyPolicy.kind(sample);
            if (kind != null) result.add(new LogisticsStock(entry.getKey(), kind, entry.getValue(), sample.copyWithCount(1)));
        }
        result.sort(Comparator.comparingInt((LogisticsStock stock) -> DockSupplyPolicy.deliveryPriority(stock.item())).reversed()
            .thenComparing(LogisticsStock::item));
        return List.copyOf(result);
    }

    public int indexedRoom(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        ensureIndex();
        long room = (long) index.emptySlotCount() * getMaxStackSize(stack);
        for (int slot : index.slots(DockSupplyPolicy.itemId(stack))) {
            ItemStack present = items.get(slot);
            if (canPlaceItem(slot, stack) && ItemStack.isSameItemSameComponents(present, stack))
                room += Math.max(0, getMaxStackSize(stack) - present.getCount());
        }
        return (int)Math.min(Integer.MAX_VALUE, room);
    }

    /** Inserts through indexed candidates and leaves any overflow in the caller's stack. */
    public void indexedInsert(ItemStack stack) {
        if (stack.isEmpty()) return;
        ensureIndex();
        String item = DockSupplyPolicy.itemId(stack);
        for (int slot : index.slots(item)) {
            if (stack.isEmpty()) break;
            ItemStack present = items.get(slot);
            if (!canPlaceItem(slot, stack) || !ItemStack.isSameItemSameComponents(present, stack)) continue;
            int moved = Math.min(stack.getCount(), Math.max(0, getMaxStackSize(stack) - present.getCount()));
            if (moved <= 0) continue;
            present.grow(moved);
            stack.shrink(moved);
        }
        for (int slot : index.emptySlots()) {
            if (stack.isEmpty()) break;
            if (!canPlaceItem(slot, stack)) continue;
            int moved = Math.min(stack.getCount(), getMaxStackSize(stack));
            items.set(slot, stack.copyWithCount(moved));
            stack.shrink(moved);
        }
        setChanged();
    }

    /** Compact inventory description used by the logistics map without a full slot scan. */
    public String indexedContentsSummary() {
        ensureIndex();
        return indexedContentsSummary;
    }

    private String buildContentsSummary() {
        if (index.usedSlots() == 0) return "空";
        Map<String, Integer> totals = new LinkedHashMap<>(index.counts());
        List<Map.Entry<String, Integer>> top = new ArrayList<>(totals.entrySet());
        top.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey));
        String summary = top.stream().limit(3).map(entry -> displayName(entry.getKey()) + " x" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(" / "));
        if (top.size() > 3) summary += " / 他" + (top.size() - 3) + "種";
        summary += " / " + index.usedSlots() + "/" + SIZE + "枠";
        return summary.length() <= 48 ? summary : summary.substring(0, 47) + "…";
    }

    private String displayName(String item) {
        int[] slots = index.slots(item);
        return slots.length == 0 ? item : items.get(slots[0]).getHoverName().getString();
    }

    private void ensureIndex() {
        if (!indexDirty) return;
        index.rebuild(items.size(), slot -> {
            ItemStack stack = items.get(slot);
            return stack.isEmpty() ? new CarrierCargoInventoryIndex.SlotValue("", 0, false)
                : new CarrierCargoInventoryIndex.SlotValue(DockSupplyPolicy.itemId(stack), stack.getCount(),
                    canTakeItem(this, slot, stack));
        });
        indexedSupplyStocks = buildSupplyStocks();
        indexedContentsSummary = buildContentsSummary();
        indexDirty = false;
    }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output); ContainerHelper.saveAllItems(output, items);
        output.putString("CargoId", cargoId.toString()); output.putString("Owner", owner.toString());
        output.putBoolean("TransferReserved", transferReserved);
    }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input); items = NonNullList.withSize(SIZE, ItemStack.EMPTY); ContainerHelper.loadAllItems(input, items);
        try { cargoId = UUID.fromString(input.getStringOr("CargoId", UUID.randomUUID().toString())); }
        catch (IllegalArgumentException ignored) { cargoId = UUID.randomUUID(); }
        try { owner = UUID.fromString(input.getStringOr("Owner", new UUID(0, 0).toString())); }
        catch (IllegalArgumentException ignored) { owner = new UUID(0, 0); }
        transferReserved = false;
        indexDirty = true;
    }
}
