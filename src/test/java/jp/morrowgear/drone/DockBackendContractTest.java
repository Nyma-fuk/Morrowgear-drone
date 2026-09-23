package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Static integration guards, not a replacement for player interaction tests. */
final class DockBackendContractTest {
	private static final Path JAVA = Path.of("src/main/java/jp/morrowgear/drone");

	@Test void allLegacyInventoryIndicesAndSaveKeysRemainUnchanged() throws Exception {
		String dock = Files.readString(JAVA.resolve("block/DockBlockEntity.java"));
		Map<String, Integer> slots = Map.ofEntries(Map.entry("SLOT_DRONE", 0), Map.entry("SLOT_BATTERY", 1),
			Map.entry("SLOT_ROLE_MODULE", 2), Map.entry("SLOT_WEAPON_MODULE", 3), Map.entry("SLOT_POWER_INPUT", 4),
			Map.entry("SLOT_REPAIR", 5), Map.entry("SLOT_AMMUNITION", 6), Map.entry("SLOT_RECOVERY_OUTPUT", 7),
			Map.entry("SLOT_RESERVED", 8), Map.entry("RECOVERY_BUFFER_START", 9), Map.entry("RECOVERY_BUFFER_END", 17),
			Map.entry("SUPPLY_BUFFER_START", 18), Map.entry("SUPPLY_BUFFER_END", 26), Map.entry("INVENTORY_SIZE", 27));
		for (var entry : slots.entrySet()) assertTrue(dock.contains(entry.getKey() + " = " + entry.getValue() + ";"));
		assertTrue(dock.contains("ContainerHelper.saveAllItems(output, items)"));
		assertTrue(dock.contains("ContainerHelper.loadAllItems(input, items)"));
		assertTrue(dock.contains("output.putInt(\"StoredPower\", storedPower)"));
		assertTrue(dock.contains("output.putInt(\"FuelCredit\", fuelCredit)"));
		assertTrue(dock.contains("DockSupplyPolicy.PackCredits.read(input)"));
		assertTrue(dock.contains("new DockSupplyPolicy.PackCredits(weaponCredit, autocannonCredit, missileCredit).write(output)"));
	}

	@Test void serverWiringHasNoOldAmmoOrCreativePowerPath() throws Exception {
		String dock = Files.readString(JAVA.resolve("block/DockBlockEntity.java"));
		assertFalse(dock.contains("IRON_NUGGET"));
		assertFalse(dock.contains("FIREWORK_ROCKET"));
		assertFalse(dock.contains("instabuild"));
		assertFalse(dock.contains("isCreative()"));
		assertTrue(dock.contains("private int storedPower;"));
		assertTrue(dock.contains("return hasFlightPowerSupply();"));
		assertTrue(dock.contains("weaponCredit > 0 || supplyCount(SupplyKind.LASER) > 0"));
		assertTrue(dock.contains("isOwnedBy(player.getUUID())"));
		assertTrue(dock.contains("player.level() == level"));
		assertTrue(dock.contains("player.distanceToSqr(Vec3.atCenterOf(worldPosition)) <= 64.0"));
	}

	@Test void customMenuUsesVanillaInteractionWithValidatedSlotsAndWorldFreeClientConstruction() throws Exception {
		String menu = Files.readString(JAVA.resolve("DockMenu.java"));
		assertTrue(menu.contains("new SimpleContainer(DockSupplyPolicy.INVENTORY_SIZE), new SimpleContainerData(DATA_COUNT)"));
		assertTrue(menu.contains("DockSupplyPolicy.mayPlace(index, stack) && container.canPlaceItem(index, stack)"));
		assertTrue(menu.contains("super.clicked(slot, button, input, player)"));
		assertTrue(menu.contains("if (!stillValid(player))"));
		assertTrue(menu.contains("source.mayPickup(player)"));
		assertTrue(menu.contains("destination.mayPlace(stack)"));
		assertTrue(menu.contains("refreshServerData(); super.broadcastChanges()"));
		assertTrue(menu.contains("data.get(field * 2) & 0xffff"));
		assertTrue(menu.contains("data.get(field * 2 + 1) & 0xffff"));
		assertFalse(menu.contains("Minecraft.getInstance"));
		assertFalse(menu.contains("net.minecraft.client"));
	}
}
