package jp.morrowgear.drone.client;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/** Received recipe display data, never a client-side crafting or ingredient-availability claim. */
record ControllerRecipeView(int identity, String kind, List<Slot> grid, Slot output, Slot station, String note) {
    ControllerRecipeView { grid = List.copyOf(grid); }
    record Slot(boolean empty, List<ItemStack> candidates) {
        Slot { candidates = candidates.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList(); }
        static Slot blank() { return new Slot(true, List.of()); }
        boolean unresolved() { return !empty && candidates.isEmpty(); }
    }
    record Book(String itemName, String conversion, List<ControllerRecipeView> recipes, String unavailable) {
        Book { recipes = List.copyOf(recipes); }
    }
}
