package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DockStockViewPolicyTest {
    @Test void unopenedMissilePacksAndOpenedRemainderAreCountedExactlyOnce() {
        var stock = DockStockViewPolicy.packStock(9, 3, DockSupplyPolicy.MISSILE_PACK_ROUNDS);
        assertEquals(5, stock.unitsPerPack());
        assertEquals(48, stock.total());
        assertEquals(9, stock.unopened());
        assertEquals(3, stock.remaining());
    }

    @Test void unopenedMagazinesAndWeaponCellsUseTheirOwnUnits() {
        assertEquals(277, DockStockViewPolicy.packStock(2, 37, DockSupplyPolicy.MAGAZINE_ROUNDS).total());
        assertEquals(2450, DockStockViewPolicy.packStock(2, 450, DockSupplyPolicy.LASER_CELL_ENERGY).total());
    }

    @Test void partialOnlyAndEmptyStocksDoNotNeedAPhysicalPack() {
        assertEquals(4, DockStockViewPolicy.packStock(0, 4, 5).total());
        assertEquals(0, DockStockViewPolicy.packStock(0, 0, 5).total());
    }

    @Test void openingOnePackAndSupplyingOneRoundLowersStockByExactlyOne() {
        var before = DockStockViewPolicy.packStock(10, 0, 5);
        var after = DockStockViewPolicy.packStock(9, 4, 5);
        assertEquals(before.total() - 1, after.total());
    }

    @Test void flightReserveIncludesCreditWithoutPretendingMixedFuelHasOneEnergyValue() {
        assertEquals(104000, DockStockViewPolicy.flightReserve(4000, 100000));
        assertEquals(250, DockStockViewPolicy.flightReserve(0, 250));
        assertEquals(40, DockStockViewPolicy.flightReserve(-1, 40));
    }

    @Test void totalsAreNotClampedToGaugeReferenceAndCannotOverflowAnInt() {
        assertEquals(640004, DockStockViewPolicy.packStock(128000, 4, 5).total());
        assertEquals(2_147_483_647_999L, DockStockViewPolicy.packStock(Integer.MAX_VALUE, 999, 1000).total());
        assertEquals(4_294_967_294L, DockStockViewPolicy.flightReserve(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(0, DockStockViewPolicy.packStock(-1, -1, 5).total());
        assertThrows(IllegalArgumentException.class, () -> DockStockViewPolicy.packStock(1, 0, 0));
    }

    @Test void referenceUsesAuthoritativeUpgradedCapacityOrClearlyFallsBackToStandard() {
        assertEquals(new DockStockViewPolicy.Reference(75, true), DockStockViewPolicy.reference(PayloadCapacity.missiles(0), 75, true));
        assertEquals(new DockStockViewPolicy.Reference(45, false), DockStockViewPolicy.reference(PayloadCapacity.missiles(0), 75, false));
        assertEquals(new DockStockViewPolicy.Reference(240, false), DockStockViewPolicy.reference(PayloadCapacity.gun(0), 0, true));
        assertEquals(new DockStockViewPolicy.Reference(2000, true), DockStockViewPolicy.reference(PayloadCapacity.energy(0), 2000, true));
    }

    @Test void actualTankAndExplicitOneAircraftGaugesAreBoundedWithoutInventingStockCapacity() {
        assertEquals(50, DockStockViewPolicy.gaugePixels(2000, 4000, 100));
        assertEquals(40, DockStockViewPolicy.gaugePixels(3 * DockSupplyPolicy.MISSILE_PACK_ROUNDS, PayloadCapacity.missiles(0), 120));
        assertEquals(120, DockStockViewPolicy.gaugePixels(640 * 5L + 4, 45, 120));
        assertEquals(0, DockStockViewPolicy.gaugePixels(100, 0, 120));
        assertEquals(0, DockStockViewPolicy.gaugePixels(-1, 45, 120));
        assertEquals(0, DockStockViewPolicy.gaugePixels(1, 45, -1));
    }

    @Test void stockRowsStayAboveInventoryHintsAndFooterAtEverySupportedHeight() {
        for (int height = 166; height <= 1080; height++) {
            int rows = DockStockViewPolicy.rowsPerPage(height), pages = DockStockViewPolicy.pageCount(height);
            assertEquals(4, rows * pages);
            assertTrue(DockStockViewPolicy.rowHeight(height) >= 28);
            assertTrue(68 + rows * DockStockViewPolicy.rowHeight(height) <= height - 39, "height=" + height);
            for (int page = 0; page < pages; page++) assertEquals(page * rows, DockStockViewPolicy.firstRow(height, page));
            assertEquals(0, DockStockViewPolicy.firstRow(height, -1));
            assertEquals((pages - 1) * rows, DockStockViewPolicy.firstRow(height, 99));
        }
    }

    @Test void screenReadsSynchronizedCreditsAndUsesTotalsNotPartialOnlyGauges() throws Exception {
        String source = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/DockScreen.java"));
        assertTrue(source.contains("private int page = 2, stockPage;"));
        assertTrue(source.contains("DockStockViewPolicy.flightReserve(stored, credit)"));
        assertTrue(source.contains("DockStockViewPolicy.packStock(menu.supplyCount(supply), menu.value(field), units)"));
        for (String field : new String[] {"WEAPON_POWER", "GUN_CREDIT", "MISSILE_CREDIT", "FUEL_CREDIT"})
            assertTrue(source.contains("DockMenu." + field));
        assertTrue(source.contains("tooltip, stock.total(), reference.amount()"));
        assertTrue(source.contains("DockSupplyPolicy.MISSILE_PACK_ROUNDS"));
        assertTrue(source.contains("gaugePixels(amount, capacity, w)"));
        assertFalse(source.contains("minecraft.level.getBlockEntity"));
    }

    @Test void menuAppendsCapacityFieldsAndUsesActualDroneUpgradeValues() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DockMenu.java"));
        assertTrue(source.contains("DRONE_WEAPON_CAPACITY = 20, DRONE_GUN_CAPACITY = 21, DRONE_MISSILE_CAPACITY = 22"));
        assertTrue(source.contains("VALUE_COUNT = 23, DATA_COUNT = VALUE_COUNT * 2"));
        assertTrue(source.contains("setValue(DRONE_WEAPON_CAPACITY, drone == null ? PayloadCapacity.energy(0) : drone.weaponCapacity())"));
        assertTrue(source.contains("setValue(DRONE_GUN_CAPACITY, drone == null ? PayloadCapacity.gun(0) : drone.gunCapacity())"));
        assertTrue(source.contains("setValue(DRONE_MISSILE_CAPACITY, drone == null ? PayloadCapacity.missiles(0) : drone.missileCapacity())"));
    }
}
