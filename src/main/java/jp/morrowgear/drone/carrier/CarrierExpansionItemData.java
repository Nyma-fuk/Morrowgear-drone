package jp.morrowgear.drone.carrier;

import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** A physical module carries identity only. Its inventory remains in world SavedData. */
public final class CarrierExpansionItemData {
    private static final String ROOT = "MorrowgearCarrierExpansion";
    private static final int FORMAT = 1;

    private CarrierExpansionItemData() {}

    public record Identity(UUID id, UUID owner, CarrierExpansionPolicy.Type type) {}

    public static void write(ItemStack stack, Identity identity) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Format", FORMAT);
            tag.putString("Id", identity.id().toString());
            tag.putString("Owner", identity.owner().toString());
            tag.putString("Type", identity.type().name());
            root.put(ROOT, tag);
        });
    }

    public static Identity read(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return null;
        CompoundTag root = custom.copyTag();
        CompoundTag tag = root.getCompound(ROOT).orElse(null);
        if (tag == null || tag.getIntOr("Format", 0) != FORMAT) return null;
        try {
            return new Identity(UUID.fromString(tag.getStringOr("Id", "")),
                UUID.fromString(tag.getStringOr("Owner", "")),
                CarrierExpansionPolicy.Type.byName(tag.getStringOr("Type", "CARGO")));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
