package jp.morrowgear.drone.carrier;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** A fixed 54-slot window over the complete 1,728-slot ground container. */
public final class CarrierCargoContainerMenu extends AbstractContainerMenu {
    public static final int PAGE_SIZE = 54;
    public static final int PAGES = CarrierCargoContainerBlockEntity.SIZE / PAGE_SIZE;
    public static final int STORAGE_START = 0, PLAYER_START = PAGE_SIZE, PLAYER_END = PLAYER_START + 36;
    public static final int FIRST = 0, PREVIOUS = 1, NEXT = 2, LAST = 3;
    private static final int PAGE = 0, USED = 1, DATA_COUNT = 2;

    private final Container storage;
    private final ContainerData data;
    private final CarrierCargoContainerBlockEntity owner;

    /** Client factory: only the visible page is mirrored client-side. */
    public CarrierCargoContainerMenu(int id, Inventory inventory, CarrierCargoContainerPayload payload) {
        this(id, inventory, new SimpleContainer(PAGE_SIZE), pageData(payload.page()), null);
    }

    public CarrierCargoContainerMenu(int id, Inventory inventory, CarrierCargoContainerBlockEntity cargo) {
        this(id, inventory, cargo, 0);
    }

    private CarrierCargoContainerMenu(int id, Inventory inventory, CarrierCargoContainerBlockEntity cargo, int page) {
        this(id, inventory, new Page(cargo, pageData(page)), pageData(page), cargo);
        ((Page) storage).bind(data);
        refreshSummary();
    }

    private CarrierCargoContainerMenu(int id, Inventory inventory, Container storage, ContainerData data,
                                      CarrierCargoContainerBlockEntity owner) {
        super(CarrierModule.CARGO_CONTAINER_MENU, id);
        this.storage = storage;
        this.data = data;
        this.owner = owner;
        for (int slot = 0; slot < PAGE_SIZE; slot++)
            addSlot(new Slot(storage, slot, 8 + slot % 9 * 18, 30 + slot / 9 * 18));
        addStandardInventorySlots(inventory, 8, 148);
        addDataSlots(data);
        if (owner != null) owner.startOpen(inventory.player);
    }

    public int page() { return Math.clamp(data.get(PAGE), 0, PAGES - 1); }
    public int usedSlots() { return Math.max(0, data.get(USED)); }
    public int pages() { return PAGES; }

    @Override public boolean stillValid(Player player) {
        if (owner == null || !(player instanceof ServerPlayer serverPlayer)) return true;
        return owner.mayAccess(serverPlayer) && owner.stillValid(player);
    }

    @Override public boolean clickMenuButton(Player player, int action) {
        if (!stillValid(player) || !getCarried().isEmpty()) return false;
        int requested = switch (action) {
            case FIRST -> 0;
            case PREVIOUS -> page() - 1;
            case NEXT -> page() + 1;
            case LAST -> PAGES - 1;
            default -> -1;
        };
        if (requested < 0 || requested >= PAGES || requested == page()) return false;
        if (!(player instanceof ServerPlayer serverPlayer) || owner == null) return false;
        resetQuickCraft();
        return open(serverPlayer, owner, requested);
    }

    @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (!stillValid(player)) { resetQuickCraft(); return; }
        if (slot >= slots.size() || slot < -1 && slot != SLOT_CLICKED_OUTSIDE) return;
        super.clicked(slot, button, input, player);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot source = slots.get(index);
        if (!source.hasItem() || !source.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = source.getItem();
        ItemStack original = stack.copy();
        boolean moved = index < PLAYER_START
            ? moveItemStackTo(stack, PLAYER_START, PLAYER_END, true)
            : moveItemStackTo(stack, STORAGE_START, PLAYER_START, false);
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) source.setByPlayer(ItemStack.EMPTY); else source.setChanged();
        source.onTake(player, stack);
        return original;
    }

    @Override public void removed(Player player) {
        super.removed(player);
        if (owner != null) owner.stopOpen(player);
    }

    @Override public void broadcastChanges() { refreshSummary(); super.broadcastChanges(); }
    @Override public void broadcastFullState() { refreshSummary(); super.broadcastFullState(); }

    private void refreshSummary() {
        if (owner != null) data.set(USED, owner.indexedUsedSlots());
    }

    private static SimpleContainerData pageData(int page) {
        SimpleContainerData data = new SimpleContainerData(DATA_COUNT);
        data.set(PAGE, Math.clamp(page, 0, PAGES - 1));
        return data;
    }

    private static boolean open(ServerPlayer player, CarrierCargoContainerBlockEntity cargo, int page) {
        if (!cargo.mayAccess(player) || !cargo.stillValid(player) || page < 0 || page >= PAGES) return false;
        CarrierCargoContainerPayload payload = new CarrierCargoContainerPayload(cargo.getBlockPos().asLong(), page);
        return player.openMenu(new ExtendedMenuProvider<CarrierCargoContainerPayload>() {
            @Override public CarrierCargoContainerPayload getScreenOpeningData(ServerPlayer viewer) { return payload; }
            @Override public Component getDisplayName() { return cargo.getDisplayName(); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player viewer) {
                return new CarrierCargoContainerMenu(id, inventory, cargo, page);
            }
        }).isPresent();
    }

    private static final class Page implements Container {
        private final CarrierCargoContainerBlockEntity owner;
        private ContainerData data;
        private Page(CarrierCargoContainerBlockEntity owner, ContainerData data) { this.owner = owner; this.data = data; }
        private void bind(ContainerData value) { data = value; }
        private int offset(int slot) { return Math.clamp(data.get(PAGE), 0, PAGES - 1) * PAGE_SIZE + slot; }
        @Override public int getContainerSize() { return PAGE_SIZE; }
        @Override public boolean isEmpty() {
            for (int slot = 0; slot < PAGE_SIZE; slot++) if (!getItem(slot).isEmpty()) return false;
            return true;
        }
        @Override public ItemStack getItem(int slot) { return owner.getItem(offset(slot)); }
        @Override public ItemStack removeItem(int slot, int count) { return owner.removeItem(offset(slot), count); }
        @Override public ItemStack removeItemNoUpdate(int slot) { return owner.removeItemNoUpdate(offset(slot)); }
        @Override public void setItem(int slot, ItemStack stack) { owner.setItem(offset(slot), stack); }
        @Override public void setChanged() { owner.setChanged(); }
        @Override public boolean stillValid(Player player) { return owner.stillValid(player); }
        @Override public void clearContent() {
            for (int slot = 0; slot < PAGE_SIZE; slot++) owner.setItem(offset(slot), ItemStack.EMPTY);
        }
    }
}
