package jp.morrowgear.drone;

import static jp.morrowgear.drone.CarrierAcceptanceVerification.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import jp.morrowgear.drone.carrier.CarrierCargoStorage;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierAcceptanceWarehouseTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registries = net.minecraft.data.registries.VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(initializer -> initializer.apply());
    }

    private static List<Container> barrels() {
        List<Container> result = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            Container barrel = new SimpleContainer(27);
            ItemStack marker = new ItemStack(Items.PAPER);
            marker.set(DataComponents.CUSTOM_NAME, Component.literal("owned warehouse"));
            barrel.setItem(26, marker);
            result.add(barrel);
        }
        return result;
    }

    @Test void lastBarrelPartialIsFilledBeforeFirstBarrelEmptySlot() {
        var barrels = barrels();
        barrels.get(9).setItem(25, new ItemStack(Items.DIAMOND, 60));
        var plan = warehousePlan(barrels, new ItemStack(Items.DIAMOND, 8), warehouseTargets(3));
        assertEquals(2, plan.size());
        assertEquals(9, plan.get(0).barrel());
        assertEquals(25, plan.get(0).slot());
        assertEquals(4, plan.get(0).count());
        assertEquals(0, plan.get(1).barrel());
        assertEquals(0, plan.get(1).slot());
        assertEquals(4, plan.get(1).count());
    }

    @Test void planningIsReadOnlyAndCopiesItsBeforeState() {
        var barrels = barrels();
        barrels.get(2).setItem(4, new ItemStack(Items.DIAMOND, 61));
        ItemStack offered = new ItemStack(Items.DIAMOND, 7);
        var plan = warehousePlan(barrels, offered, warehouseTargets(0));
        plan.getFirst().before().setCount(1);
        assertEquals(61, barrels.get(2).getItem(4).getCount());
        assertEquals(7, offered.getCount());
        assertTrue(barrels.getFirst().getItem(0).isEmpty());
    }

    @Test void componentsAndOwnershipMarkerAreNeverMergedOrOverwritten() {
        var barrels = barrels();
        for (Container barrel : barrels) for (int slot = 0; slot < 26; slot++) barrel.setItem(slot, new ItemStack(Items.STONE, 64));
        ItemStack named = new ItemStack(Items.DIAMOND, 3);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("original reserve"));
        barrels.getFirst().setItem(0, named);
        barrels.get(9).setItem(26, new ItemStack(Items.DIAMOND, 1));
        assertTrue(warehousePlan(barrels, new ItemStack(Items.DIAMOND), warehouseTargets(0)).isEmpty());
        assertEquals(3, barrels.getFirst().getItem(0).getCount());
        assertEquals(1, barrels.get(9).getItem(26).getCount());
    }

    @Test void fragmentedManualTransferContinuesWithinSixteenClicksWithoutAllocatingEmpties() {
        var barrels = barrels();
        for (int slot = 0; slot < 64; slot++) barrels.get(slot / 26).setItem(slot % 26, new ItemStack(Items.DIAMOND, 63));
        ItemStack held = new ItemStack(Items.DIAMOND, 64);
        int transferred = 0, ticks = 0;
        while (!held.isEmpty()) {
            var plan = warehousePlan(barrels, held, warehouseTargets(0));
            assertTrue(plan.size() * 3 <= CarrierAcceptancePolicy.CLICK_BUDGET);
            assertTrue(plan.stream().allMatch(move -> !move.before().isEmpty()));
            transferred += apply(barrels, held, plan);
            assertTrue(++ticks <= 13);
        }
        assertEquals(13, ticks);
        assertEquals(64, transferred);
        assertTrue(barrels.get(2).getItem(12).isEmpty());
        for (int slot = 0; slot < 64; slot++) assertEquals(64, barrels.get(slot / 26).getItem(slot % 26).getCount());
    }

    @Test void automaticFragmentationFallbackExtractsOneAndReservesCargoClicks() {
        var barrels = barrels();
        for (int slot = 0; slot < 10; slot++) barrels.get(slot).setItem(0, new ItemStack(Items.DIAMOND, 63));
        ItemStack cargo = new ItemStack(Items.DIAMOND, 64);
        int capacity = warehousePlan(barrels, cargo, warehouseTargets(3)).stream().mapToInt(WarehouseMove::count).sum();
        assertEquals(4, capacity);
        assertEquals(1, extractionCount(cargo, ItemStack.EMPTY, capacity));
        var plan = warehousePlan(barrels, cargo.copyWithCount(1), warehouseTargets(3));
        assertEquals(1, plan.size());
        assertTrue(3 + plan.size() * 3 <= CarrierAcceptancePolicy.CLICK_BUDGET);
    }

    @Test void initialEighteenSurvivesAutomaticExtractionAndSameItemManualAudit() {
        ItemStack original = new ItemStack(Items.COBBLESTONE, 18);
        var cargo = new CarrierCargoStorage(List.of(new ItemStack(Items.COBBLESTONE, 64)), () -> {});
        int moved = 0;
        while (cargo.getItem(0).getCount() > 18) {
            int count = extractionCount(cargo.getItem(0), original, 64);
            assertEquals(1, count);
            moved += cargo.removeItem(0, count).getCount();
            assertTrue(baselineCargoRetained(List.of(original), cargo));
        }
        assertEquals(46, moved);
        assertEquals(18, cargo.getItem(0).getCount());
        cargo.setItem(0, ItemStack.EMPTY);
        assertFalse(baselineCargoRetained(List.of(original), cargo));
        cargo.setItem(0, new ItemStack(Items.COBBLESTONE, 17));
        assertFalse(baselineCargoRetained(List.of(original), cargo));
        cargo.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        assertTrue(baselineCargoRetained(List.of(original), cargo));
        var named = new ItemStack(Items.COBBLESTONE, 64);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("different components"));
        cargo.setItem(0, named);
        assertFalse(baselineCargoRetained(List.of(original), cargo));
    }

    @Test void threeOriginalPartialStacksAndAllFixtureLootFitWithoutFragmentationOrLoss() {
        var original = List.of(new ItemStack(Items.COBBLESTONE, 18), new ItemStack(Items.COBBLED_DEEPSLATE, 18), new ItemStack(Items.RAW_GOLD, 10));
        var cargo = new CarrierCargoStorage(original, () -> {});
        var drops = List.of(new ItemStack(Items.COBBLESTONE, 8960), new ItemStack(Items.COBBLED_DEEPSLATE, 6144),
            new ItemStack(Items.EMERALD, 256), new ItemStack(Items.DIAMOND, 256), new ItemStack(Items.RAW_COPPER, 3),
            new ItemStack(Items.RAW_GOLD, 255), new ItemStack(Items.LAPIS_LAZULI, 6), new ItemStack(Items.RAW_IRON, 255),
            new ItemStack(Items.REDSTONE, 4), new ItemStack(Items.COAL, 255));
        cargo.commit(cargo.plan(drops).orElseThrow());
        var barrels = barrels();
        int total = 0;
        for (int slot = 0; slot < cargo.getContainerSize(); slot++) {
            ItemStack baseline = slot < original.size() ? original.get(slot) : ItemStack.EMPTY;
            while (cargo.getItem(slot).getCount() > baseline.getCount()) {
                var current = cargo.getItem(slot);
                int extra = current.getCount() - baseline.getCount();
                int capacity = warehousePlan(barrels, current.copyWithCount(extra), warehouseTargets(3)).stream().mapToInt(WarehouseMove::count).sum();
                int count = extractionCount(current, baseline, capacity);
                ItemStack held = cargo.removeItem(slot, count);
                var plan = warehousePlan(barrels, held, warehouseTargets(3));
                assertTrue(3 + plan.size() * 3 <= CarrierAcceptancePolicy.CLICK_BUDGET);
                total += apply(barrels, held, plan);
                assertTrue(held.isEmpty());
            }
        }
        assertEquals(16394, total);
        assertEquals(46, cargo.itemCount());
        assertTrue(baselineCargoRetained(original, cargo));
        int used = 0, stored = 0;
        for (Container barrel : barrels) {
            for (int slot = 0; slot < 26; slot++) if (!barrel.getItem(slot).isEmpty()) {
                used++; stored += barrel.getItem(slot).getCount();
            }
            assertEquals(1, barrel.getItem(26).getCount());
            assertTrue(barrel.getItem(26).is(Items.PAPER));
        }
        assertEquals(259, used);
        assertEquals(total, stored);
        for (ItemStack drop : drops) {
            int actual = 0;
            for (Container barrel : barrels) for (int slot = 0; slot < 26; slot++)
                if (ItemStack.isSameItemSameComponents(drop, barrel.getItem(slot))) actual += barrel.getItem(slot).getCount();
            assertEquals(drop.getCount(), actual);
        }
    }

    @Test void fullWarehouseAndInvalidBudgetsCannotConsumeCargo() {
        var barrels = barrels();
        for (Container barrel : barrels) for (int slot = 0; slot < 26; slot++) barrel.setItem(slot, new ItemStack(Items.STONE, 64));
        ItemStack offered = new ItemStack(Items.DIAMOND, 64);
        assertTrue(warehousePlan(barrels, offered, warehouseTargets(3)).isEmpty());
        assertThrows(IllegalStateException.class, () -> extractionCount(offered, ItemStack.EMPTY, 0));
        assertThrows(IllegalArgumentException.class, () -> warehouseTargets(-1));
        assertThrows(IllegalArgumentException.class, () -> warehouseTargets(17));
        assertThrows(IllegalArgumentException.class, () -> warehousePlan(barrels, offered, -1));
        assertTrue(warehousePlan(barrels, offered, 0).isEmpty());
        assertEquals(64, offered.getCount());
    }

    private static int apply(List<Container> barrels, ItemStack held, List<WarehouseMove> plan) {
        int moved = 0;
        for (WarehouseMove move : plan) {
            Container barrel = barrels.get(move.barrel());
            assertTrue(ItemStack.matches(move.before(), barrel.getItem(move.slot())));
            barrel.setItem(move.slot(), held.copyWithCount(move.before().getCount() + move.count()));
            held.shrink(move.count());
            moved += move.count();
        }
        return moved;
    }
}
