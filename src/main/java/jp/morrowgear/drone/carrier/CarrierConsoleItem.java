package jp.morrowgear.drone.carrier;

import java.util.Comparator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

public final class CarrierConsoleItem extends Item {
    public CarrierConsoleItem(Properties properties) { super(properties); }
    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
        var id = CarrierInterior.currentShip(serverPlayer);
        if (id != null) CarrierMenu.open(serverPlayer, id);
        else {
            var nearby = level.getEntitiesOfClass(CarrierEntity.class, player.getBoundingBox().inflate(128),
                entity -> CarrierMenu.accessible(serverPlayer, entity.getUUID()));
            var entity = nearby.stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
            if (entity != null && !player.isShiftKeyDown()) CarrierMenu.open(serverPlayer, entity.getUUID());
            else CarrierSavedData.get(serverPlayer.level().getServer()).ships().entrySet().stream()
                .filter(entry -> entry.getValue().destroyed && entry.getValue().owner.equals(player.getUUID()))
                .sorted(Comparator.comparingInt(entry -> entry.getValue().cabin)).findFirst()
                .ifPresent(entry -> CarrierMenu.open(serverPlayer, entry.getKey()));
        }
        return InteractionResult.SUCCESS;
    }
}
