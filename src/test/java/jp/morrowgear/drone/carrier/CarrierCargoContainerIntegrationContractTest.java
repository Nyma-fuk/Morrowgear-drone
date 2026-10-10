package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CarrierCargoContainerIntegrationContractTest {
    private static String main(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/jp/morrowgear/drone", file));
    }
    private static String carrier(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/jp/morrowgear/drone/carrier", file));
    }

    @Test void everyStorageSlotIsReachableThroughAConstantSizePage() throws Exception {
        String menu = carrier("CarrierCargoContainerMenu.java");
        assertTrue(menu.contains("PAGE_SIZE = 54"));
        assertTrue(menu.contains("PAGES = CarrierCargoContainerBlockEntity.SIZE / PAGE_SIZE"));
        assertTrue(menu.contains("FIRST = 0, PREVIOUS = 1, NEXT = 2, LAST = 3"));
        assertTrue(menu.contains("player.openMenu(new ExtendedMenuProvider"));
        assertTrue(menu.contains("* PAGE_SIZE + slot"));
        assertEquals(32, CarrierCargoContainerBlockEntity.SIZE / CarrierCargoContainerMenu.PAGE_SIZE);
    }

    @Test void giantInventoryUsesChangeDrivenIndexInsteadOfTickScanning() throws Exception {
        String container = carrier("CarrierCargoContainerBlockEntity.java");
        assertTrue(container.contains("CarrierCargoInventoryIndex index"));
        assertTrue(container.contains("if (!indexDirty) return"));
        assertTrue(container.contains("indexedSupplyStocks = buildSupplyStocks()"));
        assertTrue(container.contains("indexedContentsSummary = buildContentsSummary()"));
        assertTrue(container.contains("indexedSupplyStocks()"));
        assertTrue(container.contains("indexedSlots(String item)"));
        assertTrue(container.contains("indexedInsert(ItemStack stack)"));
        assertFalse(container.contains("tick("));
    }

    @Test void supplyRuntimeUsesTheIndexAndPreservesOwnerIsolation() throws Exception {
        String runtime = main("SupplyNetworkRuntime.java");
        assertTrue(runtime.contains("cargo.logisticsAccessible(owner)"));
        assertTrue(runtime.contains("cargo.indexedSupplyStocks()"));
        assertTrue(runtime.contains("cargo.indexedCount(item)"));
        assertTrue(runtime.contains("cargo.indexedSlots(item)"));
        assertTrue(runtime.contains("cargo.indexedInsert(stack)"));
        assertTrue(runtime.contains("container instanceof CarrierCargoContainerBlockEntity"));
    }

    @Test void discoverySummarizesLargeContainersWithoutReadingEverySlot() throws Exception {
        String transport = main("SupplyNetworkTransport.java");
        assertTrue(transport.contains("cargo.logisticsAccessible(owner)"));
        assertTrue(transport.contains("cargo.indexedContentsSummary()"));
        assertTrue(transport.contains("supported(block, player.getUUID())"));
    }

    @Test void everyVisibleContainerCellForwardsOpenAndRecoveryToTheAnchor() throws Exception {
        String footprint = carrier("CarrierContainerFootprint.java");
        String part = carrier("CarrierContainerPartBlock.java");
        String cargo = carrier("CarrierCargoContainerBlock.java");
        String missile = carrier("CarrierMissileContainerBlock.java");
        String recovery = main("item/RecoveryToolItem.java");
        assertTrue(footprint.contains("MIN_X = -2, MAX_X = 2"));
        assertTrue(footprint.contains("MIN_Z = -1, MAX_Z = 1"));
        assertTrue(footprint.contains("MIN_Y = 0, MAX_Y = 2"));
        assertTrue(footprint.contains("REBUILDS_PER_TICK = 64"));
        assertFalse(footprint.contains("getServer().execute"));
        assertTrue(part.contains("CarrierContainerFootprint.open(serverPlayer, pos)"));
        assertTrue(part.contains("InteractionResult.TRY_WITH_EMPTY_HAND"));
        assertTrue(cargo.contains("InteractionResult.TRY_WITH_EMPTY_HAND"));
        assertTrue(missile.contains("InteractionResult.TRY_WITH_EMPTY_HAND"));
        assertTrue(recovery.contains("CarrierContainerFootprint.resolveAnchor"));
        assertTrue(recovery.contains("CarrierContainerFootprint.removeContainer"));
    }
}
