package jp.morrowgear.drone.item;

import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.StrikeBeaconMagazinePolicy;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/** Three-round launcher. Left click fires through the network payload; use reloads from inventory. */
public final class StrikeBeaconLauncherItem extends Item {
	public static final int CAPACITY = StrikeBeaconMagazinePolicy.CAPACITY;
	private static final String ROOT = "MorrowgearStrikeBeaconLauncher";

	public StrikeBeaconLauncherItem(Properties properties) { super(properties); }

	public static int rounds(ItemStack stack) {
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null) return CAPACITY;
		CompoundTag data = custom.copyTag().getCompound(ROOT).orElse(null);
		return data == null ? CAPACITY : StrikeBeaconMagazinePolicy.normalize(data.getIntOr("Rounds", CAPACITY));
	}

	public static boolean consumeRound(ItemStack stack) {
		int current = rounds(stack);
		if (current <= 0) return false;
		setRounds(stack, StrikeBeaconMagazinePolicy.afterShot(current));
		return true;
	}

	static void setRounds(ItemStack stack, int rounds) {
		int clamped = StrikeBeaconMagazinePolicy.normalize(rounds);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
			CompoundTag data = root.getCompound(ROOT).orElse(new CompoundTag());
			data.putInt("Rounds", clamped);
			root.put(ROOT, data);
		});
	}

	@Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.FAIL;
		ItemStack launcher = player.getItemInHand(hand);
		int current = rounds(launcher);
		if (current >= CAPACITY) {
			serverPlayer.sendSystemMessage(Component.literal("[MORROWGEAR] BEACON MAGAZINE 3/3"));
			return InteractionResult.SUCCESS;
		}
		int requested = StrikeBeaconMagazinePolicy.reloadDemand(current);
		int loaded = player.isCreative() ? requested : consumeCartridges(serverPlayer, requested);
		if (loaded <= 0) {
			serverPlayer.sendSystemMessage(Component.literal("[MORROWGEAR] BEACON AMMUNITION REQUIRED"));
			return InteractionResult.FAIL;
		}
		setRounds(launcher, StrikeBeaconMagazinePolicy.afterReload(current, loaded));
		serverPlayer.getCooldowns().addCooldown(launcher, 10);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CROSSBOW_LOADING_END,
			SoundSource.PLAYERS, 0.55f, 0.78f);
		serverPlayer.sendSystemMessage(Component.literal("[MORROWGEAR] BEACON RELOAD "
			+ rounds(launcher) + "/" + CAPACITY));
		return InteractionResult.SUCCESS;
	}

	private static int consumeCartridges(ServerPlayer player, int requested) {
		int consumed = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize() && consumed < requested; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.is(MorrowgearDrone.STRIKE_BEACON_CARTRIDGE)) continue;
			int take = Math.min(requested - consumed, stack.getCount());
			stack.shrink(take);
			consumed += take;
		}
		if (consumed > 0) player.getInventory().setChanged();
		return consumed;
	}
}
