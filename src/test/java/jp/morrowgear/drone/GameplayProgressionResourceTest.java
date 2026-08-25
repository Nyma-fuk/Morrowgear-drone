package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GameplayProgressionResourceTest {
	private static final Path RECIPES = Path.of("src/main/resources/data/morrowgear_drone/recipe");
	private static final Path SOUNDS = Path.of("src/main/resources/assets/morrowgear_drone/sounds");

	@Test void firstFieldSystemRemainsOverworldCraftable() throws Exception {
		String base = read("controller", "dock_item", "field_drone_unit", "raw_morrow_composite",
			"morrow_alloy_smelting", "lightweight_frame", "basic_control_board", "flight_actuator",
			"standard_battery_pack");
		for (String gate : List.of("blaze_rod", "breeze_rod", "ender_eye", "echo_shard",
			"netherite", "trial_key", "shulker_shell")) assertFalse(base.contains(gate), gate);
		assertTrue(base.contains("minecraft:smelting"));
		assertTrue(base.contains("minecraft:charcoal"));
	}

	@Test void weaponsHaveDistinctVanillaProgressionGates() throws Exception {
		String gun = read("security_module", "autocannon_module");
		String laser = read("laser_module");
		String missile = read("missile_module");
		assertTrue(gun.contains("minecraft:blaze_rod"));
		assertFalse(gun.contains("minecraft:breeze_rod"));
		assertTrue(laser.contains("minecraft:breeze_rod"));
		assertTrue(missile.contains("minecraft:ender_eye"));
	}

	@Test void salvageRecoveryRemainsAvailableBeforeTheNether() throws Exception {
		String salvage = read("recovery_tool", "power_cell", "salvage_module");
		assertTrue(salvage.contains("morrowgear_drone:morrow_alloy"));
		assertTrue(salvage.contains("minecraft:piston"));
		for (String gate : List.of("blaze_rod", "breeze_rod", "ender_eye", "netherite"))
			assertFalse(salvage.contains(gate), gate);
	}

	@Test void everyRegisteredCustomSoundIsARealOggAsset() throws Exception {
		for (String sound : List.of("flight_idle", "flight_cruise", "autocannon_burst",
			"laser_charge", "laser_fire", "laser_hit", "laser_shutdown")) {
			byte[] bytes = Files.readAllBytes(SOUNDS.resolve(sound + ".ogg"));
			assertTrue(bytes.length > 4000, sound + " size");
			assertTrue(bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S', sound);
		}
	}

	private static String read(String... names) throws Exception {
		StringBuilder result = new StringBuilder();
		for (String name : names) result.append(Files.readString(RECIPES.resolve(name + ".json")));
		return result.toString();
	}
}
