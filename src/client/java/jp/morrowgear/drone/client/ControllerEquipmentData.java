package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.List;
import jp.morrowgear.drone.DockSupplyPolicy;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.*;

final class ControllerEquipmentData {
    private ControllerEquipmentData() {}

    static String conversion(String id) {
        var item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("morrowgear_drone", id));
        int energy = MorrowgearDrone.batteryEnergyForItem(item);
        if (energy > 0) return "1個あたり飛行電力 " + energy;
        return switch (id) {
            case "autocannon_magazine" -> "1個あたり機関砲弾 " + DockSupplyPolicy.MAGAZINE_ROUNDS + " 発";
            case "laser_cell" -> "1個あたり兵装電力 " + DockSupplyPolicy.LASER_CELL_ENERGY;
            case "micro_missile_pack" -> "1個あたりミサイル " + DockSupplyPolicy.MISSILE_PACK_ROUNDS + " 発";
			case "strike_beacon_cartridge" -> "1個あたり強襲ビーコン 1 発 / 装填上限 3 発";
            // DockBlockEntity's private repairValue is covered by a source contract test.
            case "morrow_alloy" -> "1個あたり修理量 20 HP";
            default -> "補給換算の対象外";
        };
    }

    static ControllerRecipeView.Book recipes(Minecraft client, String id) {
        String name = net.minecraft.network.chat.Component.translatable("item.morrowgear_drone." + id).getString();
        if (client.player == null || client.level == null)
            return new ControllerRecipeView.Book(name, conversion(id), List.of(), "レシピ未取得: ワールド未接続");
        var item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("morrowgear_drone", id));
        ContextMap context = SlotDisplayContext.fromLevel(client.level);
        var recipes = client.player.getRecipeBook().getCollections().stream()
            .flatMap(collection -> collection.getRecipes().stream()).distinct()
            .filter(entry -> entry.resultItems(context).stream().anyMatch(stack -> stack.is(item)))
            .sorted(java.util.Comparator.comparingInt(entry -> entry.id().index())).toList();
        List<ControllerRecipeView> views = new ArrayList<>();
        for (var entry : recipes) {
            RecipeDisplay display = entry.display();
            List<ControllerRecipeView.Slot> grid = new ArrayList<>();
            String kind;
            String note = "受信済み・解放済み / 製作操作は作業設備で";
            if (display instanceof ShapedCraftingRecipeDisplay shaped) {
                kind = "定形 " + shaped.width() + " × " + shaped.height();
                if (shaped.width() > 3 || shaped.height() > 3 || shaped.width() < 1 || shaped.height() < 1) {
                    for (int i = 0; i < 9; i++) grid.add(new ControllerRecipeView.Slot(false, List.of()));
                    note = "この配置サイズは表示未対応";
                } else for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++) {
                    int index = ControllerRecipeLayout.shapedIndex(column, row, shaped.width(), shaped.height());
                    grid.add(index < 0 ? ControllerRecipeView.Slot.blank() : slot(shaped.ingredients().get(index), context));
                }
            } else {
                List<SlotDisplay> inputs = switch (display) {
                    case ShapelessCraftingRecipeDisplay recipe -> recipe.ingredients();
                    case FurnaceRecipeDisplay recipe -> List.of(recipe.ingredient());
                    case StonecutterRecipeDisplay recipe -> List.of(recipe.input());
                    case SmithingRecipeDisplay recipe -> List.of(recipe.template(), recipe.base(), recipe.addition());
                    default -> List.of();
                };
                kind = switch (display) {
                    case ShapelessCraftingRecipeDisplay ignored -> "不定形";
                    case FurnaceRecipeDisplay ignored -> "精錬";
                    case StonecutterRecipeDisplay ignored -> "石切り";
                    case SmithingRecipeDisplay ignored -> "鍛冶: 型・素材・追加材";
                    default -> "未対応形式";
                };
                if (inputs.isEmpty() || inputs.size() > 9) note = "材料配置はこのレシピ形式に未対応";
                for (int i = 0; i < 9; i++) grid.add(inputs.isEmpty() || inputs.size() > 9
                    ? new ControllerRecipeView.Slot(false, List.of())
                    : i < inputs.size() ? slot(inputs.get(i), context) : ControllerRecipeView.Slot.blank());
                if (display instanceof FurnaceRecipeDisplay furnace)
                    note = "加工 " + furnace.duration() + " tick / 経験値 " + furnace.experience() + " / 燃料は設備で確認";
            }
            views.add(new ControllerRecipeView(entry.id().index(), kind, grid,
                slot(display.result(), context), slot(display.craftingStation(), context), note));
        }
        return new ControllerRecipeView.Book(name, conversion(id), views,
            views.isEmpty() ? "レシピ未取得: 受信済み・解放済みレシピに該当なし" : "");
    }

    private static ControllerRecipeView.Slot slot(SlotDisplay display, ContextMap context) {
        // Empty is part of the received layout. An unresolved tag/recipe slot is not an empty cell.
        if (display instanceof SlotDisplay.Empty) return ControllerRecipeView.Slot.blank();
        return new ControllerRecipeView.Slot(false, display.resolveForStacks(context));
    }
}
