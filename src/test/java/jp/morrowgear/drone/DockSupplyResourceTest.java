package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DockSupplyResourceTest {
	@Test void dedicatedItemsHaveDistinctVanillaCostsAndSinglePackOutput() throws Exception {
		assertEquals(Map.of("minecraft:iron_ingot", 4, "minecraft:gunpowder", 1,
			"minecraft:blaze_powder", 1, "minecraft:copper_ingot", 1), recipeCost("autocannon_magazine"));
		assertEquals(Map.of("minecraft:quartz", 2, "minecraft:glowstone_dust", 1,
			"minecraft:amethyst_shard", 2, "minecraft:redstone", 1, "minecraft:copper_ingot", 1), recipeCost("laser_cell"));
		assertEquals(Map.of("minecraft:iron_ingot", 2, "minecraft:gunpowder", 3,
			"minecraft:ender_eye", 1, "minecraft:copper_ingot", 2, "minecraft:redstone", 1), recipeCost("micro_missile_pack"));
	}

	private static Map<String, Integer> recipeCost(String name) throws Exception {
		JsonObject recipe = JsonParser.parseString(Files.readString(Path.of(
			"src/main/resources/data/morrowgear_drone/recipe", name + ".json"))).getAsJsonObject();
		assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
		assertEquals("equipment", recipe.get("category").getAsString());
		assertEquals("morrowgear_drone:" + name, recipe.getAsJsonObject("result").get("id").getAsString());
		assertEquals(1, recipe.getAsJsonObject("result").get("count").getAsInt());
		Map<String, Integer> cost = new HashMap<>();
		JsonObject key = recipe.getAsJsonObject("key");
		assertEquals(3, recipe.getAsJsonArray("pattern").size());
		for (var row : recipe.getAsJsonArray("pattern")) {
			assertEquals(3, row.getAsString().length());
			for (char symbol : row.getAsString().toCharArray()) {
				if (symbol == ' ') continue;
				String id = key.get(Character.toString(symbol)).getAsString();
				assertTrue(id.startsWith("minecraft:"));
				cost.merge(id, 1, Integer::sum);
			}
		}
		return cost;
	}
}
