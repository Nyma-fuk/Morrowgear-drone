package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

public final class DroneStoragePolicy {
	private DroneStoragePolicy() {
	}

	public static Manifest manifest(boolean hasInstalledModule, int cargoStacks, boolean creative) {
		return new Manifest(!creative, !creative && hasInstalledModule, Math.max(0, cargoStacks));
	}

	public static List<ItemStack> returnedItems(ItemStack droneUnit, ItemStack installedModule,
		List<ItemStack> cargo, boolean creative) {
		List<ItemStack> result = new ArrayList<>();
		Manifest manifest = manifest(installedModule != null && !installedModule.isEmpty(),
			cargo == null ? 0 : cargo.size(), creative);
		if (cargo != null) {
			for (ItemStack stack : cargo) if (stack != null && !stack.isEmpty()) result.add(stack.copy());
		}
		if (manifest.returnModule() && installedModule != null) result.add(installedModule.copy());
		if (manifest.returnUnit() && droneUnit != null && !droneUnit.isEmpty()) result.add(droneUnit.copy());
		return List.copyOf(result);
	}

	public record Manifest(boolean returnUnit, boolean returnModule, int cargoStacks) {
	}
}
