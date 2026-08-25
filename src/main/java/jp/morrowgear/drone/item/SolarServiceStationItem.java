package jp.morrowgear.drone.item;

import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.SolarServiceStationEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

public final class SolarServiceStationItem extends Item {
	public SolarServiceStationItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level)
			|| !(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
		BlockPos origin = context.getClickedPos().relative(context.getClickedFace()).above(4);
		SolarServiceStationEntity station = MorrowgearDrone.SOLAR_SERVICE_STATION.create(level,
			EntitySpawnReason.TRIGGERED);
		if (station == null) return InteractionResult.FAIL;
		station.deploy(player, origin);
		level.addFreshEntity(station);
		if (!player.isCreative()) context.getItemInHand().shrink(1);
		return InteractionResult.SUCCESS;
	}
}
