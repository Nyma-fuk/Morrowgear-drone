package jp.morrowgear.drone.carrier;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Common physical contract for detachable cargo and missile containers. */
public interface CarrierExpansionContainer {
    UUID moduleId();
    UUID ownerId();
    CarrierExpansionPolicy.Type moduleType();
    boolean transferReserved();
    void setTransferReserved(boolean value);
    boolean mayAccess(ServerPlayer player);
    ItemStack recoveryItem();
}
