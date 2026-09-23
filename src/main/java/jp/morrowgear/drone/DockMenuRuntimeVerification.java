package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.block.DockBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Opt-in, synchronous developer verification. Registration never runs the checks. */
public final class DockMenuRuntimeVerification {
	private static boolean registered;
	private static boolean running;

	private DockMenuRuntimeVerification() {}

	public static void register() {
		if (registered) return;
		registered = true;
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) ->
			dispatcher.register(Commands.literal("morrowgear_dock_verify")
				.requires(source -> source.getEntity() instanceof ServerPlayer player && authorized(player))
				.then(Commands.argument("dock", BlockPosArgument.blockPos()).executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					// Resolve in the player's real world, not an /execute in command-source world.
					BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, player.level(), "dock");
					if (!(player.level().getBlockEntity(pos) instanceof DockBlockEntity dock)) {
						context.getSource().sendFailure(Component.literal("[MORROWGEAR DOCK VERIFY] existing Dock center required"));
						return 0;
					}
					return run(player, dock);
				}))));
	}

	/** Returns 1 only after all real-menu checks and exact snapshot restoration succeed. */
	public static int run(ServerPlayer player, DockBlockEntity dock) {
		if (player == null || !player.level().getServer().isSameThread()) return 0;
		try {
			require(!running, "verification already running");
			require(authorized(player), "creative operator (game-master permission) required");
			require(player.isAlive() && !player.isRemoved() && !player.hasDisconnected(), "live connected player required");
			require(player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty(),
				"close the active container and clear the cursor first");
			require(dock != null && dock.getLevel() == player.level() && !dock.isRemoved(), "Dock must be in the player's current world");
			require(player.level().hasChunkAt(dock.getBlockPos())
				&& player.level().getBlockEntity(dock.getBlockPos()) == dock, "existing loaded Dock required");
			// Never use the owner-adoption overload to grant access for a fixture.
			require(dock.isOwnedBy(player.getUUID()) && dock.stillValid(player) && dock.canOpen(player),
				"owned, unlocked Dock within normal interaction distance required");
			for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers())
				require(other.containerMenu.slots.stream().noneMatch(slot -> slot.container == dock), "Dock is already open by a player");
		} catch (RuntimeException rejected) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR DOCK VERIFY] REJECTED / " + rejected.getMessage()));
			return 0;
		}

		ServerLevel level = player.level();
		PlayerSnapshot savedPlayer = PlayerSnapshot.capture(player);
		CompoundTag savedDock = dock.saveWithoutMetadata(level.registryAccess()).copy();
		Session session = new Session(player, dock);
		List<RuntimeException> failures = new ArrayList<>();
		running = true;
		try {
			require(player.openMenu(new SimpleMenuProvider((id, inventory, opener) -> {
				require(opener == player && player.containerMenu == player.inventoryMenu && dock.stillValid(player), "menu preconditions changed");
				session.menu = new DockMenu(id, inventory, dock);
				return session.menu;
			}, dock.getDisplayName())).isPresent(), "test menu did not open");
			require(player.containerMenu == session.menu, "test menu was replaced");
			session.checkLayout();
			session.checkStackLimits();
			session.checkShiftClick();
			session.checkRecoverySlots();
			session.checkDrags();
			session.checkSwapAndCollection();
			session.checkPlayerOnlyAndInvalidIndices();
			session.checkInvalidOwner();
			session.closeTestMenu();
			require(player.containerMenu == player.inventoryMenu, "test menu did not close to inventory");
		} catch (RuntimeException failure) {
			failures.add(failure);
		} finally {
			// Attempt every restoration independently even if a preceding stage fails.
			try {
				attempt(failures, "close test menu", session::closeTestMenu);
				attempt(failures, "restore Dock", () -> dock.restoreServiceSnapshotForVerification(savedDock.copy(), level.registryAccess()));
				attempt(failures, "restore player inventory", () -> savedPlayer.restore(player));
				attempt(failures, "verify restoration", () -> {
					require(savedDock.equals(dock.saveWithoutMetadata(level.registryAccess())), "Dock NBT/credits restoration mismatch");
					savedPlayer.verify(player);
				});
				attempt(failures, "synchronize restored inventory", () -> {
					level.sendBlockUpdated(dock.getBlockPos(), dock.getBlockState(), dock.getBlockState(), 3);
					player.inventoryMenu.broadcastFullState();
				});
			} finally {
				running = false;
			}
		}
		if (!failures.isEmpty()) {
			player.sendSystemMessage(Component.literal("[MORROWGEAR DOCK VERIFY] FAIL / "
				+ String.join("; ", failures.stream().map(RuntimeException::getMessage).toList())));
			return 0;
		}
		player.sendSystemMessage(Component.literal("[MORROWGEAR DOCK VERIFY] PASS / real slots, clicks, drags, transfers, invalid-owner guard / inventory and Dock restored"));
		return 1;
	}

	private static boolean authorized(ServerPlayer player) {
		return player.isCreative() && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static void attempt(List<RuntimeException> failures, String stage, Runnable action) {
		try { action.run(); }
		catch (RuntimeException failure) { failures.add(new IllegalStateException(stage + ": " + failure.getMessage(), failure)); }
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}

	private static List<ItemStack> copyContents(Container container) {
		List<ItemStack> copy = new ArrayList<>(container.getContainerSize());
		for (int slot = 0; slot < container.getContainerSize(); slot++) copy.add(container.getItem(slot).copy());
		return copy;
	}

	private static void restoreContents(Container container, List<ItemStack> snapshot) {
		require(container.getContainerSize() == snapshot.size(), "inventory size changed");
		for (int slot = 0; slot < snapshot.size(); slot++) container.setItem(slot, snapshot.get(slot).copy());
		container.setChanged();
	}

	private record PlayerSnapshot(List<ItemStack> inventory, List<ItemStack> crafting,
		ItemStack result, ItemStack carried, int selected) {
		static PlayerSnapshot capture(ServerPlayer player) {
			// Includes equipment, offhand, body and saddle, not just the 36 menu slots.
			return new PlayerSnapshot(copyContents(player.getInventory()), copyContents(player.inventoryMenu.getCraftSlots()),
				player.inventoryMenu.getResultSlot().getItem().copy(), player.inventoryMenu.getCarried().copy(),
				player.getInventory().getSelectedSlot());
		}

		void restore(ServerPlayer player) {
			restoreContents(player.getInventory(), inventory);
			restoreContents(player.inventoryMenu.getCraftSlots(), crafting);
			player.inventoryMenu.getResultSlot().set(result.copy());
			player.inventoryMenu.setCarried(carried.copy());
			player.getInventory().setSelectedSlot(selected);
		}

		void verify(ServerPlayer player) {
			require(ItemStack.listMatches(inventory, copyContents(player.getInventory())), "player inventory/equipment restoration mismatch");
			require(ItemStack.listMatches(crafting, copyContents(player.inventoryMenu.getCraftSlots())), "crafting grid restoration mismatch");
			require(ItemStack.matches(result, player.inventoryMenu.getResultSlot().getItem()), "crafting result restoration mismatch");
			require(ItemStack.matches(carried, player.inventoryMenu.getCarried()), "cursor restoration mismatch");
			require(selected == player.getInventory().getSelectedSlot(), "selected hotbar restoration mismatch");
		}
	}

	private static final class Session {
		final ServerPlayer player;
		final DockBlockEntity dock;
		final Inventory inventory;
		DockMenu menu;

		Session(ServerPlayer player, DockBlockEntity dock) {
			this.player = player;
			this.dock = dock;
			this.inventory = player.getInventory();
		}

		void reset() {
			require(player.containerMenu == menu && dock.stillValid(player), "test menu no longer valid");
			menu.setCarried(ItemStack.EMPTY);
			dock.clearContent();
			inventory.clearContent();
		}

		void click(int slot, int button, ContainerInput input) {
			require(player.containerMenu == menu, "only the active test menu may be driven");
			menu.clicked(slot, button, input, player);
		}

		int count(Item item) {
			int count = menu.getCarried().is(item) ? menu.getCarried().getCount() : 0;
			for (Slot slot : menu.slots) if (slot.getItem().is(item)) count += slot.getItem().getCount();
			return count;
		}

		void checkLayout() {
			require(menu.slots.size() == 63, "expected 27 Dock + 36 player slots");
			for (int slot = 0; slot < 27; slot++) {
				Slot actual = menu.getSlot(slot);
				require(actual.container == dock && actual.getContainerSlot() == slot, "Dock mapping " + slot);
				require(actual.x == 8 + slot % 9 * 18 && actual.y == 18 + slot / 9 * 18, "Dock coordinates " + slot);
			}
			for (int slot = 27; slot < 63; slot++) {
				Slot actual = menu.getSlot(slot);
				int index = slot < 54 ? slot - 18 : slot - 54;
				int y = slot < 54 ? 85 + (slot - 27) / 9 * 18 : 143;
				require(actual.container == inventory && actual.getContainerSlot() == index, "player mapping " + slot);
				require(actual.x == 8 + (slot - 27) % 9 * 18 && actual.y == y, "player coordinates " + slot);
			}
		}

		void checkStackLimits() {
			reset();
			menu.setCarried(new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 16));
			click(6, 0, ContainerInput.PICKUP);
			require(dock.getItem(6).getCount() == 16 && menu.getCarried().isEmpty(), "16-item input insertion");
			menu.setCarried(new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE));
			click(6, 0, ContainerInput.PICKUP);
			require(menu.getCarried().getCount() == 1 && count(SupplyItems.AUTOCANNON_MAGAZINE) == 17, "full 16-stack conservation");
			for (int slot : new int[]{1, 2, 3, 8}) {
				reset();
				Item item = slot == 2 ? MorrowgearDrone.SCOUT_MODULE : slot == 3
					? MorrowgearDrone.AUTOCANNON_MODULE : MorrowgearDrone.STANDARD_BATTERY_PACK;
				menu.setCarried(new ItemStack(item, 16));
				click(slot, 0, ContainerInput.PICKUP);
				require(dock.getItem(slot).getCount() == 1 && menu.getCarried().getCount() == 15, "installed item limit " + slot);
				require(count(item) == 16, "installed item conservation " + slot);
			}
			require(menu.getSlot(0).getMaxStackSize() == 1, "airframe limit");
		}

		void checkShiftClick() {
			reset();
			Item magazine = SupplyItems.AUTOCANNON_MAGAZINE;
			dock.setItem(6, new ItemStack(magazine, 15));
			dock.setItem(26, new ItemStack(magazine, 14));
			inventory.setItem(9, new ItemStack(magazine, 6));
			click(27, 0, ContainerInput.QUICK_MOVE);
			require(inventory.getItem(9).isEmpty() && dock.getItem(6).getCount() == 16
				&& dock.getItem(26).getCount() == 16 && dock.getItem(18).getCount() == 3, "shift-click input/merge/empty priority");
			require(count(magazine) == 35 && dock.getItem(19).isEmpty(), "shift-click conservation");
			reset();
			dock.setItem(6, new ItemStack(magazine, 15));
			for (int slot = 18; slot < 27; slot++) dock.setItem(slot, new ItemStack(magazine, 16));
			inventory.setItem(0, new ItemStack(magazine, 5));
			click(54, 0, ContainerInput.QUICK_MOVE);
			click(54, 0, ContainerInput.QUICK_MOVE);
			require(inventory.getItem(0).getCount() == 4 && count(magazine) == 164, "full Dock retains remainder");
		}

		void checkRecoverySlots() {
			for (int slot : new int[]{7, 9, 10, 11, 12, 13, 14, 15, 16, 17}) {
				reset();
				menu.setCarried(new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 3));
				click(slot, 0, ContainerInput.PICKUP);
				require(dock.getItem(slot).isEmpty() && count(SupplyItems.AUTOCANNON_MAGAZINE) == 3, "recovery insertion rejected " + slot);
				reset();
				dock.setItem(slot, new ItemStack(Items.IRON_NUGGET, 7));
				click(slot, 1, ContainerInput.PICKUP);
				require(menu.getCarried().getCount() == 4 && dock.getItem(slot).getCount() == 3, "right-click extraction " + slot);
				click(27, 0, ContainerInput.PICKUP);
				click(slot, 0, ContainerInput.QUICK_MOVE);
				require(dock.getItem(slot).isEmpty() && inventory.getItem(9).getCount() == 7
					&& inventory.getItem(8).isEmpty() && count(Items.IRON_NUGGET) == 7, "recovery extraction conservation " + slot);
			}
		}

		void drag(int type, int... slots) {
			click(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(0, type), ContainerInput.QUICK_CRAFT);
			for (int slot : slots) click(slot, AbstractContainerMenu.getQuickcraftMask(1, type), ContainerInput.QUICK_CRAFT);
			click(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(2, type), ContainerInput.QUICK_CRAFT);
		}

		void checkDrags() {
			reset();
			menu.setCarried(new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 12));
			drag(0, 6, 18, 19, 7, 2);
			require(dock.getItem(6).getCount() == 4 && dock.getItem(18).getCount() == 4
				&& dock.getItem(19).getCount() == 4 && menu.getCarried().isEmpty(), "left drag distribution");
			require(dock.getItem(7).isEmpty() && dock.getItem(2).isEmpty() && count(SupplyItems.AUTOCANNON_MAGAZINE) == 12, "left drag validation/conservation");
			reset();
			menu.setCarried(new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 5));
			drag(1, 6, 18, 19, 7);
			require(dock.getItem(6).getCount() == 1 && dock.getItem(18).getCount() == 1
				&& dock.getItem(19).getCount() == 1 && menu.getCarried().getCount() == 2, "right drag distribution");
			require(dock.getItem(7).isEmpty() && count(SupplyItems.AUTOCANNON_MAGAZINE) == 5, "right drag conservation");
			reset();
			menu.setCarried(new ItemStack(MorrowgearDrone.STANDARD_BATTERY_PACK, 8));
			drag(0, 1, 8);
			require(dock.getItem(1).getCount() == 1 && dock.getItem(8).getCount() == 1
				&& menu.getCarried().getCount() == 6 && count(MorrowgearDrone.STANDARD_BATTERY_PACK) == 8, "drag installed-slot limits");
		}

		void checkSwapAndCollection() {
			reset();
			dock.setItem(6, new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 3));
			inventory.setItem(0, new ItemStack(SupplyItems.LASER_CELL, 2));
			click(6, 0, ContainerInput.SWAP);
			require(dock.getItem(6).is(SupplyItems.LASER_CELL) && inventory.getItem(0).is(SupplyItems.AUTOCANNON_MAGAZINE)
				&& count(SupplyItems.LASER_CELL) == 2 && count(SupplyItems.AUTOCANNON_MAGAZINE) == 3, "hotbar swap conservation");
			dock.setItem(7, new ItemStack(SupplyItems.LASER_CELL, 2));
			click(7, 0, ContainerInput.SWAP);
			require(dock.getItem(7).is(SupplyItems.LASER_CELL) && inventory.getItem(0).is(SupplyItems.AUTOCANNON_MAGAZINE), "hotbar swap must not insert into recovery");
			reset();
			menu.setCarried(new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE));
			dock.setItem(6, new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 2));
			dock.setItem(18, new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 3));
			dock.setItem(7, new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 4));
			click(27, 0, ContainerInput.PICKUP_ALL);
			require(menu.getCarried().getCount() == 10 && count(SupplyItems.AUTOCANNON_MAGAZINE) == 10 && dock.isEmpty(), "double-click collection conservation");
		}

		void checkPlayerOnlyAndInvalidIndices() {
			reset();
			inventory.setItem(9, new ItemStack(Items.IRON_NUGGET, 12));
			click(27, 0, ContainerInput.QUICK_MOVE);
			require(dock.isEmpty() && inventory.getItem(0).getCount() == 12, "legacy ammo cannot shift into Dock");
			click(54, 0, ContainerInput.QUICK_MOVE);
			require(inventory.getItem(9).getCount() == 12 && count(Items.IRON_NUGGET) == 12, "player inventory round trip");
			for (int index : new int[]{-999, -1, 63, Integer.MAX_VALUE})
				require(menu.quickMoveStack(player, index).isEmpty(), "invalid shift index " + index);
		}

		void checkInvalidOwner() {
			reset();
			dock.setItem(6, new ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 7));
			inventory.setItem(9, new ItemStack(Items.COAL, 3));
			menu.setCarried(new ItemStack(Items.COAL, 2));
			CompoundTag valid = dock.saveWithoutMetadata(player.level().registryAccess()).copy();
			CompoundTag invalid = valid.copy();
			UUID owner = player.getUUID();
			invalid.putString("Owner", new UUID(owner.getMostSignificantBits(), owner.getLeastSignificantBits() ^ 1L).toString());
			invalid.putString("OwnerName", "");
			try {
				dock.restoreServiceSnapshotForVerification(invalid, player.level().registryAccess());
				require(!dock.stillValid(player) && !menu.stillValid(player) && !menu.getSlot(6).mayPickup(player), "raw owner invalidation");
				require(menu.getSlot(6).safeTake(7, 64, player).isEmpty(), "invalid extraction");
				require(menu.quickMoveStack(player, 6).isEmpty() && menu.quickMoveStack(player, 27).isEmpty(), "invalid transfer");
				for (ContainerInput input : new ContainerInput[]{ContainerInput.PICKUP, ContainerInput.QUICK_MOVE,
					ContainerInput.SWAP, ContainerInput.THROW, ContainerInput.QUICK_CRAFT}) click(6, 0, input);
				require(dock.getItem(6).getCount() == 7 && inventory.getItem(9).getCount() == 3
					&& menu.getCarried().getCount() == 2 && count(Items.COAL) == 5, "invalid menu must not mutate stacks");
			} finally {
				dock.restoreServiceSnapshotForVerification(valid, player.level().registryAccess());
			}
			require(dock.stillValid(player), "owner restoration before close");
		}

		void closeTestMenu() {
			if (menu == null) return;
			// Discard only fixture cursor items before vanilla close can drop them into the world.
			menu.setCarried(ItemStack.EMPTY);
			if (player.containerMenu == menu) player.closeContainer();
		}
	}
}
