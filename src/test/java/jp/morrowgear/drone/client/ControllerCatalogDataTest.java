package jp.morrowgear.drone.client;

import java.nio.file.Files;
import java.nio.file.Path;
import jp.morrowgear.drone.SupplyNetworkPolicy;
import jp.morrowgear.drone.DockSupplyPolicy;
import jp.morrowgear.drone.DroneCommandPolicy;
import jp.morrowgear.drone.DroneStatePolicy;
import jp.morrowgear.drone.FieldOperationType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ControllerCatalogDataTest {
    private String client(String name) throws Exception {
        return Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/" + name + ".java"));
    }
    @Test void numericInputsRejectOverflowAndOutOfRangeWithoutClamping() {
        assertEquals(-512, ControllerNumericPolicy.parse("-512", -512, 512).orElseThrow());
        assertEquals(512, ControllerNumericPolicy.parse("512", -512, 512).orElseThrow());
        for (String input : new String[] {"-513", "513", "", "1.5", "2147483648", "--1"})
            assertTrue(ControllerNumericPolicy.parse(input, -512, 512).isEmpty(), input);
    }
    @Test void manualStartFollowsReorderedPointAndDeletedPointReturnsToNearest() {
        assertEquals(2, ControllerNumericPolicy.movedStart(1, 1, 2));
        assertEquals(1, ControllerNumericPolicy.movedStart(2, 1, 2));
        assertEquals(-1, ControllerNumericPolicy.movedStart(-1, 1, 2));
        assertEquals(-1, ControllerNumericPolicy.removedStart(2, 2));
        assertEquals(1, ControllerNumericPolicy.removedStart(2, 0));
        assertEquals(0, ControllerNumericPolicy.removedStart(0, 2));
    }
    @Test void radiusBoundsAreExactlyThoseAcceptedByExistingServerPolicy() {
        for (FieldOperationType type : new FieldOperationType[] {FieldOperationType.ORE, FieldOperationType.EXCAVATE, FieldOperationType.FORESTRY}) {
            int min = DroneStatePolicy.fieldRadius(type, Integer.MIN_VALUE);
            int max = DroneStatePolicy.fieldRadius(type, Integer.MAX_VALUE);
            assertTrue(DroneCommandPolicy.validFieldRadius(type, min));
            assertTrue(DroneCommandPolicy.validFieldRadius(type, max));
            assertFalse(DroneCommandPolicy.validFieldRadius(type, min - 1));
            assertFalse(DroneCommandPolicy.validFieldRadius(type, max + 1));
        }
        int max = DroneStatePolicy.securityRadius(Integer.MAX_VALUE);
        assertTrue(DroneCommandPolicy.validSecurityRadius(max));
        assertFalse(DroneCommandPolicy.validSecurityRadius(max + 1));
    }
    @Test void routeBoundsIncludeEdgesAndRejectOverflow() {
        assertTrue(ControllerNumericPolicy.withinRouteRange(512, -512, 0, 0));
        assertFalse(ControllerNumericPolicy.withinRouteRange(513, 0, 0, 0));
        assertFalse(ControllerNumericPolicy.withinRouteRange(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0));
    }
    @Test void formRemainsCompactAcrossDesktopGuiScales() {
        for (int scale = 1; scale <= 4; scale++) {
            int width = ControllerNumericPolicy.formWidth(1920 / scale, scale);
            assertTrue(width * scale >= 640 && width * scale <= 800);
            assertTrue(width <= 1920 / scale - 24);
        }
        assertTrue(ControllerNumericPolicy.formWidth(320, 4) <= 296);
    }
    @Test void allReceivedSupplyStatesHaveDistinctJapaneseLabels() {
        var labels = java.util.Arrays.stream(SupplyNetworkPolicy.Status.values()).map(ControllerSupplyText::status).toList();
        assertEquals(labels.size(), labels.stream().distinct().count());
        assertTrue(labels.stream().allMatch(label -> label.codePoints().anyMatch(c -> c > 127)));
        for (var kind : DockSupplyPolicy.SupplyKind.values()) assertFalse(ControllerSupplyText.kind(kind).isBlank());
    }
    @Test void recipesUseReceivedDisplaysAndActualPackConstants() throws Exception {
        String source = client("ControllerEquipmentData");
        assertTrue(source.contains("getRecipeBook().getCollections()"));
        assertTrue(source.contains("entry.resultItems(context)"));
        assertTrue(source.contains("shaped.ingredients()"));
        assertTrue(source.contains("MorrowgearDrone.batteryEnergyForItem(item)"));
        assertTrue(source.contains("DockSupplyPolicy.MAGAZINE_ROUNDS"));
        assertTrue(source.contains("DockSupplyPolicy.LASER_CELL_ENERGY"));
        assertTrue(source.contains("DockSupplyPolicy.MISSILE_PACK_ROUNDS"));
        assertFalse(source.contains("ClientPlayNetworking.send"));
        String dock = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/block/DockBlockEntity.java"));
        assertTrue(dock.contains("DockSupplyPolicy.repairValue(DockSupplyPolicy.itemId(stack))"));
        assertTrue(source.contains("1個あたり修理量 20 HP"));
    }
    @Test void deliveryListUsesFreshSnapshotAndFullIdentityWithoutRemoteCommands() throws Exception {
        String source = client("TacticalDashboard");
        int start = source.indexOf("private ControllerDeliveryScreen.Snapshot deliverySnapshot");
        String method = source.substring(start, source.indexOf("private void drawCarrierOverview", start));
        assertTrue(method.contains("SupplyNetworkClient.dock(dock)"));
        assertTrue(method.contains("received.isEmpty()"));
        assertTrue(method.contains("d.getUUID().equals(delivery.drone())"));
        assertTrue(method.contains("delivery.remaining()"));
        assertTrue(method.contains("ControllerSupplyText.status(delivery.status())"));
        assertFalse(method.contains("sendDroneCommand"));
    }
    @Test void settingsAndNumericFormsAreRealControlsWithoutFlightCommands() throws Exception {
        String source = client("TacticalDashboard");
        assertTrue(source.contains("client.options.forceUnicodeFont().set("));
        assertTrue(source.contains("client.options.save()"));
        String form = client("ControllerNumberScreen");
        assertTrue(form.contains("ControllerNumericPolicy.formWidth("));
        assertTrue(form.contains("modeButtons.get(i).active = current != 0"));
        assertTrue(form.contains("manualButtons.get(i).active = current == 0"));
        assertTrue(form.contains("decreaseButtons.get(i).active = current > 1"));
        assertFalse(form.contains("ClientPlayNetworking"));
    }
    @Test void recoveryUsesResolvedUuidOrUnselectedOverviewAndPreservesLocalAction() throws Exception {
        String source = client("TacticalDashboard");
        assertTrue(source.contains("drawIconButton(g, recoveryOverviewButton(l)"));
        assertTrue(source.contains("drawIconButton(g, recoveryDetailButton(row)"));
        assertTrue(source.contains("new DroneRecoveryScreen(client.gui.screen(), target.getUUID())"));
        assertTrue(source.contains("if (target == null) client.setScreenAndShow(new DroneRecoveryScreen(client.gui.screen()))"));
        assertEquals(2, source.split(java.util.regex.Pattern.quote(
            "ControllerDisplayText.sameDimension(beacon.dimension(), client.level.dimension().identifier().toString())"), -1).length - 1);
        assertFalse(source.contains("beacon.dimension().equals("));
        assertTrue(source.contains("matches.size() == 1 ? matches.getFirst() : null"));
        assertTrue(source.contains("if (recoveryButton(operationRow(l, i)).contains(x, y))"));
        assertTrue(source.contains("ControllerNearbyAccess.interact(client, target)"));
    }
}
