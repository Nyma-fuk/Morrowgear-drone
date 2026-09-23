package jp.morrowgear.drone.carrier;

import java.util.UUID;
import java.util.List;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Dedicated menu with vanilla slot synchronization and a typed state snapshot for the parent screen. */
public final class CarrierMenu extends AbstractContainerMenu {
    public static final int CARGO_START = 0, SUPPLY_START = CarrierPolicy.CARGO_SLOTS;
    public static final int PLAYER_START = SUPPLY_START + CarrierSupplies.SIZE;
    public static final int TOTAL_SLOTS = PLAYER_START + 36;
    public static final int CARGO_Y = 18, SUPPLY_Y = 126, PLAYER_Y = 150;
    public final UUID shipId;
    private final Player viewer;
    private final int cargoPage;
    private CarrierViewPayload view;
    private long lastAction = Long.MIN_VALUE / 2;
    private long lastSync;
    private long lastExteriorSync;
    private CarrierViewPayload.Terrain terrain;
    private List<CarrierViewPayload.DroneCandidate> candidates;

    public CarrierMenu(int id, Inventory inventory, CarrierViewPayload initial) {
        this(id, inventory, initial, new SimpleContainer(CarrierPolicy.CARGO_SLOTS) {
            @Override public int getMaxStackSize() { return 64; }
        }, new SimpleContainer(CarrierSupplies.SIZE));
    }
    private CarrierMenu(int id, Inventory inventory, CarrierViewPayload initial, Container cargo, Container supplies) {
        super(CarrierModule.MENU, id);
        shipId = initial.ship();
        view = initial;
        terrain = initial.terrain();
        candidates = initial.candidates();
        viewer = inventory.player;
        if (viewer instanceof ServerPlayer player) lastExteriorSync = player.level().getServer().getTickCount();
        cargoPage = initial.navigation().storage().page();
        for (int slot = 0; slot < CarrierPolicy.CARGO_SLOTS; slot++) {
            addSlot(new Slot(cargo, slot, 8 + slot % 9 * 18, CARGO_Y + slot / 9 * 18) {
                @Override public boolean mayPickup(Player player) { return owner() && stillValid(player); }
                @Override public boolean mayPlace(ItemStack stack) { return owner() && stillValid(viewer); }
            });
        }
        for (int slot = 0; slot < CarrierSupplies.SIZE; slot++) {
            final int supply = slot;
            addSlot(new Slot(supplies, slot, 8 + slot * 36, SUPPLY_Y) {
                @Override public boolean mayPickup(Player player) { return owner() && stillValid(player); }
                @Override public boolean mayPlace(ItemStack stack) {
                    return owner() && stillValid(viewer) && CarrierSupplies.accepts(supply, jp.morrowgear.drone.DockSupplyPolicy.itemId(stack));
                }
            });
        }
        addStandardInventorySlots(inventory, 8, PLAYER_Y);
    }
    public CarrierViewPayload view() { return view; }
    public int cargoPage() { return cargoPage; }
    public void acceptView(CarrierViewPayload update) {
        if (update.menu() == containerId && update.ship().equals(shipId)
            && update.navigation().storage().page() == cargoPage) {
            if (update.terrain().specified()) terrain = update.terrain();
            candidates = update.candidates();
            view = update.withTerrain(terrain).withCandidates(candidates);
        }
    }
    private boolean owner() {
        if (!(viewer instanceof ServerPlayer player)) return view.owner();
        var ship = CarrierSavedData.get(player.level().getServer()).ship(shipId);
        return ship != null && ship.owner.equals(player.getUUID());
    }
    public boolean rateLimit(long tick) {
        if (tick - lastAction < 5) return false;
        lastAction = tick;
        return true;
    }
    @Override public boolean stillValid(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        return accessible(serverPlayer, shipId);
    }
    public static boolean accessible(ServerPlayer player, UUID id) {
        var ship = CarrierSavedData.get(player.level().getServer()).ship(id);
        if (ship == null || !ship.permits(player.getUUID()) || !player.isAlive() || player.isSpectator()) return false;
        if (id.equals(CarrierInterior.currentShip(player))) return true;
        if (ship.destroyed) return ship.owner.equals(player.getUUID());
        var entity = CarrierModule.find(player.level().getServer(), id);
        return entity != null && entity.level() == player.level() && entity.distanceToSqr(player) <= 128 * 128;
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!activeFor(player) || !owner() || !stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem(), copy = stack.copy();
        boolean moved;
        if (index < PLAYER_START) moved = moveItemStackTo(stack, PLAYER_START, slots.size(), true);
        else {
            moved = moveItemStackTo(stack, SUPPLY_START, PLAYER_START, false);
            if (!stack.isEmpty()) moved = moveItemStackTo(stack, CARGO_START, SUPPLY_START, false) || moved;
        }
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, stack);
        return copy;
    }
    @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (!activeFor(player) || !owner() || !stillValid(player)) { resetQuickCraft(); return; }
        if (slot >= slots.size() || slot < -1 && slot != SLOT_CLICKED_OUTSIDE) return;
        super.clicked(slot, button, input, player);
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (viewer instanceof ServerPlayer player) {
            long now = player.level().getServer().getTickCount();
            if (now - lastSync >= 10) { sync(player); lastSync = now; }
        }
    }
    public void sync(ServerPlayer player) {
        var ship = CarrierSavedData.get(player.level().getServer()).ship(shipId);
        if (ship == null || !activeFor(player) || !stillValid(player)) return;
        long now = player.level().getServer().getTickCount();
        boolean refreshExterior = now - lastExteriorSync >= 40;
        if (refreshExterior) {
            var exterior = CarrierModule.find(player.level().getServer(), shipId);
            terrain = exterior == null ? CarrierViewPayload.Terrain.unavailable(ship.exterior.dimension())
                : CarrierExteriorSnapshot.terrain(exterior);
            candidates = ship.owner.equals(player.getUUID())
                ? CarrierExteriorSnapshot.candidates(exterior, ship.owner) : List.of();
            lastExteriorSync = now;
        }
        view = CarrierViewPayload.of(containerId, shipId, ship, player, cargoPage)
            .withTerrain(terrain).withCandidates(candidates);
        CarrierViewPayload outbound = refreshExterior ? view : view.withTerrain(CarrierViewPayload.Terrain.EMPTY);
        if (ServerPlayNetworking.canSend(player, CarrierViewPayload.TYPE)) ServerPlayNetworking.send(player, outbound);
    }

    private boolean activeFor(Player player) {
        return player == viewer && (!(player instanceof ServerPlayer) || player.containerMenu == this);
    }

    static boolean validPageRequest(int requestedPage, int expectedStateId, int expectedPage,
                                    int stateId, int currentPage, boolean carriedEmpty) {
        return requestedPage >= 0 && requestedPage < CarrierPolicy.CARGO_PAGES
            && requestedPage != currentPage && expectedPage == currentPage
            && expectedStateId == stateId && carriedEmpty;
    }

    public boolean changeCargoPage(ServerPlayer player, int requestedPage, int expectedStateId, int expectedPage) {
        if (!activeFor(player) || !owner() || !stillValid(player)) return false;
        resetQuickCraft();
        if (!validPageRequest(requestedPage, expectedStateId, expectedPage, getStateId(), cargoPage, getCarried().isEmpty())) {
            broadcastFullState();
            sync(player);
            return false;
        }
        // A fresh menu ID prevents queued vanilla clicks from addressing a different page.
        open(player, shipId, requestedPage);
        if (player.containerMenu instanceof CarrierMenu next && next != this && next.cargoPage == requestedPage) {
            next.lastAction = lastAction;
            return true;
        }
        return false;
    }

    public static void open(ServerPlayer player, UUID id) {
        open(player, id, 0);
    }
    private static void open(ServerPlayer player, UUID id, int page) {
        if (!accessible(player, id)) return;
        var ship = CarrierSavedData.get(player.level().getServer()).ship(id);
        var exterior = CarrierModule.find(player.level().getServer(), id);
        var opening = CarrierViewPayload.of(-1, id, ship, player, page)
            .withTerrain(exterior == null ? CarrierViewPayload.Terrain.unavailable(ship.exterior.dimension())
                : CarrierExteriorSnapshot.terrain(exterior))
            .withCandidates(ship.owner.equals(player.getUUID())
                ? CarrierExteriorSnapshot.candidates(exterior, ship.owner) : List.of());
        player.openMenu(new ExtendedMenuProvider<CarrierViewPayload>() {
            @Override public CarrierViewPayload getScreenOpeningData(ServerPlayer viewer) {
                return opening;
            }
            @Override public Component getDisplayName() { return Component.translatable("container.morrowgear_drone.carrier"); }
            @Override public AbstractContainerMenu createMenu(int menu, Inventory inventory, Player viewer) {
                return new CarrierMenu(menu, inventory, opening.withMenu(menu), ship.cargo.page(page), ship.supplies);
            }
        });
    }
}
