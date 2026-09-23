package jp.morrowgear.drone;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

public final class SupplyItems {
	public static final Item AUTOCANNON_MAGAZINE = registerItem("autocannon_magazine");
	public static final Item LASER_CELL = registerItem("laser_cell");
	public static final Item MICRO_MISSILE_PACK = registerItem("micro_missile_pack");

	private SupplyItems() {}

	/** Call during common initialization, before registries freeze. */
	public static void register() {}

	private static Item registerItem(String path) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM,
			Identifier.fromNamespaceAndPath("morrowgear_drone", path));
		return Registry.register(BuiltInRegistries.ITEM, key,
			new Item(new Item.Properties().setId(key).stacksTo(16)));
	}
}
