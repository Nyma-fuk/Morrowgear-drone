package jp.morrowgear.drone.carrier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

public final class CarrierDeploymentItem extends Item {
    public CarrierDeploymentItem(Properties properties) { super(properties); }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player))
            return InteractionResult.SUCCESS;
        if (!player.mayBuild() || player.isSpectator() || CarrierInterior.inside(level)
            || !level.mayInteract(player, context.getClickedPos())) return InteractionResult.FAIL;
        var data = CarrierSavedData.get(level.getServer());
        if (data.ships().size() >= CarrierPolicy.MAX_CABINS
            || data.ships().values().stream().filter(s -> s.owner.equals(player.getUUID()) && !s.destroyed).count() >= 4)
            return InteractionResult.FAIL;
        CarrierEntity carrier = CarrierModule.ENTITY.create(level, EntitySpawnReason.TRIGGERED);
        if (carrier == null || !carrier.deploy(player, Vec3.atCenterOf(context.getClickedPos()).add(0, 32, 0)))
            return InteractionResult.FAIL;
        if (!level.addFreshEntity(carrier)) {
            data.abandonDeployment(carrier.getUUID());
            carrier.discard();
            return InteractionResult.FAIL;
        }
        if (!player.isCreative()) context.getItemInHand().shrink(1);
        return InteractionResult.SUCCESS;
    }
}
