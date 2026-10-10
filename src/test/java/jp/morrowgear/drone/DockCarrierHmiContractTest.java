package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source contracts supplement, but do not replace, ordinary in-game inventory interaction tests. */
class DockCarrierHmiContractTest {
    private static String client(String name) throws Exception {
        return Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/" + name + ".java"));
    }

    @Test void equipmentThemeDoesNotReplaceNativeInventoryInput() throws Exception {
        String theme = client("DockCarrierHmiScreen");
        assertTrue(theme.contains("extends HmiContainerScreen<T>"));
        for (String method : new String[]{"mouseClicked(", "mouseDragged(", "mouseReleased(", "slotClicked(",
            "keyPressed(", "onClose(", "removed(", "extractSlots("}) assertFalse(theme.contains(method), method);
        for (String name : new String[]{"DockScreen", "CarrierScreen"})
            assertTrue(client(name).contains("extends DockCarrierHmiScreen<"));
    }

    @Test void nativeSlotsStillHandleClicksDragHotbarAndHiddenBoundaries() throws Exception {
        String base = client("HmiContainerScreen");
        for (String contract : new String[]{"super.mouseClicked(event, doubleClick)", "super.mouseDragged(event, dx, dy)",
            "super.mouseReleased(event)", "super.slotClicked(slot, id, button, input)",
            "super.checkHotbarKeyPressed(event)", "slotVisible(menu.slots.get(id))",
            "super.extractSlot(g, slot, mx, my)", "quickCraftSlots.clear()"}) assertTrue(base.contains(contract), contract);
    }

    @Test void cargoTransitionsKeepCursorAndRevisionGuards() throws Exception {
        String carrier = client("CarrierScreen");
        assertTrue(carrier.contains("!menu.getCarried().isEmpty() || cargoPending()"));
        assertTrue(carrier.contains("target, menu.getStateId(), menu.cargoPage()"));
        assertTrue(carrier.contains("cargoReady() && !confirmation.busy()"));
        assertTrue(carrier.contains("CarrierUiPolicy.slotVisible(slot.index, height, playerPage)"));
        assertTrue(carrier.contains("view().owner() && !cargoPending()"));
        assertTrue(client("DockScreen").contains("cancelSlotGesture(); network = new SupplyNetworkPanel(this)"));
    }

    @Test void allCarrierDestinationsAndCommandsRemainAvailable() throws Exception {
        String carrier = client("CarrierScreen");
        for (String tab : new String[]{"OVERVIEW", "MINING", "LASER", "MISSILE", "MOVE", "CARGO", "SUPPLY", "CABIN", "DIAGNOSTICS"})
            assertTrue(carrier.contains(tab + "(\""), tab);
        for (String action : new String[]{"STOP", "ACTIVATE", "MOVE", "RESUME_PREVIEW", "PREVIEW_COMBAT", "PREVIEW_MINING",
            "REFUEL", "CHARGE_WEAPON", "CARGO_PAGE", "RESERVE_BAY", "RELEASE_BAY", "BOARD", "EXIT",
            "RECOVER_CABIN", "ALLOW_GUEST", "REVOKE_GUEST", "PREVIEW_MISSILE_COMBAT", "MOVE_MISSILE_COMBAT"})
            assertTrue(carrier.contains("Action." + action), action);
        assertTrue(carrier.contains("地上コンテナ"));
        assertTrue(carrier.contains("搭載済み"));
        assertTrue(carrier.contains("安全地点へ降ろす"));
        assertTrue(carrier.contains("!mapTab()"));
        assertTrue(carrier.contains("confirmation.canStart(now())"));
        assertFalse(carrier.contains("ONLINE"));
    }

    @Test void dockResourcesArePaginatedWithoutReplacingRealInventoryOrUnits() throws Exception {
        String dock = client("DockScreen");
        assertTrue(dock.contains("resourcePage * resourceRows()"));
        assertTrue(dock.contains("resourceCount != aircraftResources().metrics().size()"));
        assertTrue(dock.contains("DockStockViewPolicy.packStock"));
        assertTrue(dock.contains("DockStockViewPolicy.flightReserve"));
        assertTrue(dock.contains("DockSupplyPolicy.MISSILE_PACK_ROUNDS"));
        assertTrue(dock.contains("menu.shortage(supply)"));
        assertFalse(dock.contains("menu.clicked("));
        assertFalse(dock.contains("menu.slots.clear("));
    }

    @Test void dockDepartureReadinessRemainsServerOwned() throws Exception {
        String dock = client("DockScreen");
        assertTrue(dock.contains("switch (menu.value(DockMenu.READINESS))"));
        assertTrue(dock.contains("case DockMenu.READY"));
        assertTrue(dock.contains("case DockMenu.BLOCKED"));
        assertFalse(dock.contains("snapshot.overall()"));
        assertFalse(dock.contains("aircraftResources().overall()"));
    }

    @Test void supplyPagesDoNotReserveSpaceForHiddenCargoSlots() throws Exception {
        String carrier = client("CarrierScreen");
        assertTrue(carrier.contains("return page == 0 && !small() ? 200 : 6;"));
        assertTrue(carrier.contains("DockCarrierLayout.supplyTabWidth(width, x, 4)"));
        assertTrue(carrier.contains("powerX(1), powerY(1), powerWidth()"));
    }
}
