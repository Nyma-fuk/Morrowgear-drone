package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierCargoStorageTest {
    private static net.minecraft.core.HolderLookup.Provider registries;
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        registries = net.minecraft.data.registries.VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(initializer -> initializer.apply());
    }
    private static CarrierCargoStorage empty() { return new CarrierCargoStorage(List.of(), () -> {}); }
    private static CarrierShip ship() {
        return new CarrierShip(UUID.randomUUID(), 0, new CarrierAnchor("minecraft:overworld", 0, 120, 0));
    }

    @Test void finiteCapacityAndVisibleMenuSizeAreIndependent() {
        var cargo = empty();
        assertEquals(3456, cargo.getContainerSize());
        assertEquals(64, CarrierPolicy.CARGO_PAGES);
        assertEquals(54, CarrierMenu.SUPPLY_START);
        assertEquals(59, CarrierMenu.PLAYER_START);
        assertEquals(95, CarrierMenu.TOTAL_SLOTS);
        for (int page = 0; page < 64; page++) assertEquals(54, cargo.page(page).getContainerSize());
        assertThrows(IllegalArgumentException.class, () -> cargo.page(-1));
        assertThrows(IllegalArgumentException.class, () -> cargo.page(64));
    }

    @Test void ordinarySetItemUsesVanillaContainerAndItemStackLimits() {
        var cargo = empty();
        var oversized = new ItemStack(Items.COBBLESTONE, 99);
        cargo.setItem(0, oversized);
        cargo.page(1).setItem(0, new ItemStack(Items.ENDER_PEARL, 99));
        cargo.page(63).setItem(53, new ItemStack(Items.NETHERITE_PICKAXE, 99));
        assertEquals(64, cargo.getItem(0).getCount());
        assertEquals(16, cargo.getItem(54).getCount());
        assertEquals(1, cargo.getItem(3455).getCount());
        assertEquals(81, cargo.itemCount());
        assertEquals(99, oversized.getCount());
        var extended = new ItemStack(Items.DIAMOND, 99);
        extended.set(DataComponents.MAX_STACK_SIZE, 99);
        cargo.page(2).setItem(0, extended);
        assertEquals(64, cargo.getMaxStackSize());
        assertEquals(64, cargo.page(2).getMaxStackSize());
        assertEquals(64, cargo.page(2).getMaxStackSize(extended));
        assertEquals(64, cargo.getItem(108).getCount());
        var slot = new net.minecraft.world.inventory.Slot(cargo.page(2), 0, 0, 0);
        assertEquals(64, slot.getMaxStackSize(extended));
    }

    @Test void supplyFallbackVisitsOnlyOccupiedSlotsAndCanReachTheLastPage() {
        var ship = ship();
        ship.cargo.setItem(2000, new ItemStack(Items.COBBLESTONE, 64));
        ship.cargo.setItem(3455, new ItemStack(Items.IRON_INGOT, 7));
        var before = ship.cargo.metrics();
        var source = CarrierSupplies.find(ship, CarrierSupplies.REPAIR);
        assertNotNull(source);
        assertEquals(3455, source.slot());
        assertEquals(2, ship.cargo.metrics().lookupSlots() - before.lookupSlots());
        source.consume();
        assertEquals(6, ship.cargo.getItem(3455).getCount());
        assertEquals(70, ship.cargo.itemCount());
        ship.supplies.setItem(CarrierSupplies.REPAIR, new ItemStack(Items.COPPER_INGOT));
        var calls = ship.cargo.metrics().lookupCalls();
        assertSame(ship.supplies, CarrierSupplies.find(ship, CarrierSupplies.REPAIR).container());
        assertEquals(calls, ship.cargo.metrics().lookupCalls());
    }

    @Test void everyPageMapsExactlyToItsOwn54Slots() {
        var cargo = empty();
        for (int page = 0; page < 64; page++) {
            var view = cargo.page(page);
            view.setItem(0, new ItemStack(Items.IRON_INGOT, page + 1));
            view.setItem(53, new ItemStack(Items.DIAMOND));
        }
        assertEquals(128, cargo.usedSlots());
        for (int page = 0; page < 64; page++) {
            assertEquals(page + 1, cargo.getItem(page * 54).getCount());
            assertTrue(cargo.getItem(page * 54 + 53).is(Items.DIAMOND));
            assertThrows(IndexOutOfBoundsException.class, () -> cargo.page(0).getItem(54));
        }
        cargo.page(31).clearContent();
        assertEquals(126, cargo.usedSlots());
        assertEquals(31, cargo.getItem(30 * 54).getCount());
        assertEquals(33, cargo.getItem(32 * 54).getCount());
    }

    @Test void old54SlotSaveIsPreservedOnFirstPageAndNewLastPageRoundTrips() {
        var original = ship();
        original.cargo.setItem(53, new ItemStack(Items.DIAMOND, 17));
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var old = CarrierShip.CODEC.encodeStart(ops, original).getOrThrow();
        assertEquals(54, old.getAsJsonObject().getAsJsonArray("cargo").size());
        var restored = CarrierShip.CODEC.parse(ops, old).getOrThrow();
        assertEquals(17, restored.cargo.page(0).getItem(53).getCount());
        assertTrue(restored.cargo.page(1).isEmpty());
        assertEquals(3456, restored.cargo.getContainerSize());
        restored.cargo.setItem(3455, new ItemStack(Items.EMERALD, 31));
        var encoded = CarrierShip.CODEC.encodeStart(ops, restored).getOrThrow();
        assertEquals(3456, encoded.getAsJsonObject().getAsJsonArray("cargo").size());
        var latest = CarrierShip.CODEC.parse(ops, encoded).getOrThrow();
        assertEquals(31, latest.cargo.page(63).removeItem(53, 64).getCount());
        assertEquals(17, latest.cargo.itemCount());
        assertEquals(54, latest.cargo.snapshot().size());
        latest.cargo.removeItem(53, 64);
        assertEquals(List.of(), latest.cargo.snapshot());
    }

    @Test void planningIsAtomicWhenOnlyPartOfTheBlockLootFits() {
        List<ItemStack> saved = new ArrayList<>();
        for (int slot = 0; slot < 3456; slot++) saved.add(new ItemStack(Items.COBBLESTONE, slot == 0 ? 63 : 64));
        var dirty = new AtomicInteger();
        var cargo = new CarrierCargoStorage(saved, dirty::incrementAndGet);
        ItemStack incoming = new ItemStack(Items.COBBLESTONE, 2);
        assertTrue(cargo.plan(List.of(incoming)).isEmpty());
        assertEquals(63, cargo.getItem(0).getCount());
        assertEquals(221183, cargo.itemCount());
        assertEquals(2, incoming.getCount());
        assertEquals(0, dirty.get());
        assertEquals(1, cargo.metrics().failedPlans());
    }

    @Test void lastFreeSlotDoesNotRequireCopyingOrScanningFilledPages() {
        var cargo = empty();
        for (int slot = 0; slot < 3455; slot++) cargo.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        var before = cargo.metrics();
        var plan = cargo.plan(List.of(new ItemStack(Items.DIAMOND))).orElseThrow();
        assertEquals(1, plan.changedSlots());
        cargo.commit(plan);
        var after = cargo.metrics();
        assertEquals(1, after.examinedSlots() - before.examinedSlots());
        assertEquals(2, after.copiedStacks() - before.copiedStacks());
        assertEquals(1, after.indexRefreshSlots() - before.indexRefreshSlots());
        assertTrue(cargo.getItem(3455).is(Items.DIAMOND));
    }

    @Test void exactCapacityAcceptsTheLastItemAndRejectsAllLaterLootAtomically() {
        var cargo = empty();
        for (int slot = 0; slot < 3456; slot++) cargo.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        cargo.removeItem(3455, 1);
        assertTrue(cargo.plan(List.of(new ItemStack(Items.COBBLESTONE), new ItemStack(Items.DIAMOND))).isEmpty());
        assertEquals(221183, cargo.itemCount());
        cargo.commit(cargo.plan(List.of(new ItemStack(Items.COBBLESTONE))).orElseThrow());
        assertEquals(221184, cargo.itemCount());
        assertEquals(3456, cargo.usedSlots());
        assertTrue(cargo.plan(List.of(new ItemStack(Items.COBBLESTONE))).isEmpty());
        assertEquals(221184, cargo.itemCount());
        assertThrows(IllegalArgumentException.class, () -> new CarrierCargoStorage(
            java.util.Collections.nCopies(3457, ItemStack.EMPTY), () -> {}));
    }

    @Test void successiveBlockPlansHaveConstantWorkEvenAcrossManyPages() {
        var cargo = empty();
        for (int block = 0; block < 16384; block++) {
            var plan = cargo.plan(List.of(new ItemStack(Items.COBBLESTONE))).orElseThrow();
            assertEquals(1, plan.changedSlots());
            cargo.commit(plan);
        }
        assertEquals(16384, cargo.itemCount());
        assertEquals(256, cargo.usedSlots());
        var metrics = cargo.metrics();
        assertEquals(16384, metrics.examinedSlots());
        assertTrue(metrics.copiedStacks() <= 16384 * 3L);
        assertEquals(16384, metrics.committedSlots());
        assertEquals(16384, metrics.indexRefreshSlots());
        assertTrue(metrics.planNanos() >= metrics.maxPlanNanos());
    }

    @Test void duplicatedInputsMergeWithoutAliasingTheOfferedItems() {
        var cargo = empty();
        var offered = new ItemStack(Items.COBBLESTONE, 40);
        var plan = cargo.plan(List.of(offered, offered)).orElseThrow();
        assertEquals(0, cargo.itemCount());
        assertEquals(2, plan.changedSlots());
        offered.setCount(1);
        cargo.commit(plan);
        assertEquals(80, cargo.itemCount());
        assertEquals(64, cargo.getItem(0).getCount());
        assertEquals(16, cargo.getItem(1).getCount());
    }

    @Test void componentsAndUnstackableDropsKeepDistinctSlots() {
        var cargo = empty();
        var named = new ItemStack(Items.DIAMOND, 3);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Owner reserve"));
        var tool = new ItemStack(Items.NETHERITE_PICKAXE);
        tool.setDamageValue(77);
        cargo.commit(cargo.plan(List.of(named, new ItemStack(Items.DIAMOND, 2), tool, tool)).orElseThrow());
        assertEquals(4, cargo.usedSlots());
        assertEquals(7, cargo.itemCount());
        assertEquals(77, cargo.getItem(2).getDamageValue());
        assertEquals(1, cargo.getItem(3).getCount());
        var restored = new CarrierCargoStorage(cargo.snapshot(), () -> {});
        assertTrue(ItemStack.isSameItemSameComponents(named, restored.getItem(0)));
        assertEquals(77, restored.getItem(3).getDamageValue());
    }

    @Test void staleForeignAndReplayedPlansCannotOverwriteOrDuplicateItems() {
        var cargo = empty();
        var other = empty();
        var plan = cargo.plan(List.of(new ItemStack(Items.DIAMOND, 8))).orElseThrow();
        assertFalse(other.canCommit(plan));
        assertThrows(IllegalStateException.class, () -> other.commit(plan));
        cargo.commit(plan);
        assertFalse(cargo.canCommit(plan));
        assertThrows(IllegalStateException.class, () -> cargo.commit(plan));
        assertEquals(8, cargo.itemCount());
        var stale = cargo.plan(List.of(new ItemStack(Items.DIAMOND))).orElseThrow();
        cargo.removeItem(0, 1);
        cargo.setItem(0, new ItemStack(Items.DIAMOND, 8));
        assertFalse(cargo.canCommit(stale), "Even an ABA change invalidates an outstanding plan");
        assertThrows(IllegalStateException.class, () -> cargo.commit(stale));
        assertEquals(8, cargo.itemCount());
    }

    @Test void pageNotifiesOnlyIts54SlotsAfterVanillaInPlaceStackChanges() {
        var cargo = empty();
        var page = cargo.page(63);
        page.setItem(53, new ItemStack(Items.COBBLESTONE, 64));
        var before = cargo.metrics();
        page.getItem(53).shrink(1);
        page.setChanged();
        assertEquals(63, cargo.itemCount());
        assertEquals(54, cargo.metrics().indexRefreshSlots() - before.indexRefreshSlots());
        cargo.commit(cargo.plan(List.of(new ItemStack(Items.COBBLESTONE))).orElseThrow());
        assertEquals(64, cargo.getItem(3455).getCount());
        assertEquals(1, cargo.usedSlots());
    }

    @Test void directContainerMutationNotificationRebuildsTheIndexBeforePlanning() {
        var cargo = empty();
        cargo.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        cargo.getItem(0).shrink(1);
        cargo.setChanged();
        cargo.commit(cargo.plan(List.of(new ItemStack(Items.COBBLESTONE))).orElseThrow());
        assertEquals(64, cargo.itemCount());
        assertEquals(1, cargo.usedSlots());
    }

    @Test void removalsAndSavedCopiesCannotRetainAliasedInventoryStacks() {
        var cargo = empty();
        var offered = new ItemStack(Items.IRON_INGOT, 7);
        cargo.setItem(55, offered);
        offered.shrink(6);
        var snapshot = cargo.snapshot();
        snapshot.get(55).shrink(6);
        assertEquals(7, cargo.itemCount());
        assertEquals(7, cargo.page(1).removeItemNoUpdate(1).getCount());
        assertTrue(cargo.isEmpty());
        assertEquals(List.of(), cargo.snapshot());
    }

    @Test void pageSwitchPolicyRejectsCarriedStacksStaleStateAndOutOfRangePages() {
        assertTrue(CarrierMenu.validPageRequest(63, 8, 0, 8, 0, true));
        assertFalse(CarrierMenu.validPageRequest(1, 8, 0, 8, 0, false));
        assertFalse(CarrierMenu.validPageRequest(1, 7, 0, 8, 0, true));
        assertFalse(CarrierMenu.validPageRequest(1, 8, 1, 8, 0, true));
        assertFalse(CarrierMenu.validPageRequest(0, 8, 0, 8, 0, true));
        assertFalse(CarrierMenu.validPageRequest(-1, 8, 0, 8, 0, true));
        assertFalse(CarrierMenu.validPageRequest(64, 8, 0, 8, 0, true));
    }
}
