package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class CarrierExpansionItemDataTest {
    @Test void itemStoresIdentityButNotTheLargeInventory() {
        ItemStack stack = new ItemStack(Items.CHEST);
        var identity = new CarrierExpansionItemData.Identity(UUID.randomUUID(), UUID.randomUUID(),
            CarrierExpansionPolicy.Type.CARGO);
        CarrierExpansionItemData.write(stack, identity);
        assertEquals(identity, CarrierExpansionItemData.read(stack));
        assertFalse(stack.toString().contains("Items"));
    }
}
