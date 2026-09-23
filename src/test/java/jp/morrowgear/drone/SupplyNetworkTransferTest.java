package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.SupplyNetworkPolicy.Status;
import jp.morrowgear.drone.SupplyNetworkRegistry.Job;
import jp.morrowgear.drone.SupplyNetworkRegistry.Route;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class SupplyNetworkTransferTest {
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// 26.2 binds item defaults during data-pack loading; this fixture needs only stack capacity.
		for (var item : List.of(Items.COAL, Items.DIAMOND, Items.IRON_NUGGET))
			if (!item.builtInRegistryHolder().areComponentsBound()) item.builtInRegistryHolder().bindComponents(
				net.minecraft.core.component.DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
	}

	private final SupplyNetworkRegistry registry = new SupplyNetworkRegistry(() -> {});
	private final Route route = new Route(UUID.randomUUID(), "minecraft:overworld", 10, 20);
	private Job reserve() {
		var config = registry.configure(route, SupplyNetworkPolicy.defaults(), true);
		return registry.reserve(config, UUID.randomUUID(), SupplyKind.FUEL, "minecraft:coal", 16, 64, 64, 0).orElseThrow();
	}

	@Test void sourceWhitelistQuantityAndComponentsArePreservedByRealTransfer() {
		Job job = reserve();
		SimpleContainer source = new SimpleContainer(3);
		source.setItem(0, new ItemStack(Items.DIAMOND, 32));
		ItemStack coal = new ItemStack(Items.COAL, 40);
		coal.set(DataComponents.CUSTOM_NAME, Component.literal("Warehouse A"));
		source.setItem(1, coal);
		List<ItemStack> cargo = new ArrayList<>();
		assertEquals(16, SupplyNetworkRuntime.loadReserved(registry, job, source, cargo, stack -> 64));
		assertEquals(32, source.getItem(0).getCount());
		assertEquals(24, source.getItem(1).getCount());
		assertEquals(16, cargo.getFirst().getCount());
		assertEquals("Warehouse A", cargo.getFirst().get(DataComponents.CUSTOM_NAME).getString());
		assertEquals(40, source.getItem(1).getCount() + cargo.getFirst().getCount());
		assertTrue(SupplyNetworkRuntime.loadReserved(registry, job, source, cargo, stack -> 64) <= 0);
		assertEquals(40, source.getItem(1).getCount() + cargo.getFirst().getCount());
	}

	@Test void reservationInvalidationCannotExtractAnything() {
		Job job = reserve();
		SimpleContainer source = new SimpleContainer(new ItemStack(Items.COAL, 40));
		List<ItemStack> cargo = new ArrayList<>();
		registry.cancel(job.token(), Status.CANCELLED);
		assertEquals(0, SupplyNetworkRuntime.loadReserved(registry, job, source, cargo, stack -> 64));
		assertTrue(cargo.isEmpty());
		assertEquals(40, source.getItem(0).getCount());
	}

	@Test void emptyOrMutatedSourceDoesNotLoadUnrelatedItems() {
		Job job = reserve();
		SimpleContainer source = new SimpleContainer(new ItemStack(Items.IRON_NUGGET, 40));
		List<ItemStack> cargo = new ArrayList<>();
		assertEquals(0, SupplyNetworkRuntime.loadReserved(registry, job, source, cargo, stack -> 64));
		assertTrue(cargo.isEmpty());
		assertEquals(40, source.getItem(0).getCount());
	}

	@Test void fullDockBeforeLoadingLeavesItemsInWarehouse() {
		Job job = reserve();
		SimpleContainer source = new SimpleContainer(new ItemStack(Items.COAL, 40));
		List<ItemStack> cargo = new ArrayList<>();
		assertEquals(0, SupplyNetworkRuntime.loadReserved(registry, job, source, cargo, stack -> 0));
		assertEquals(40, source.getItem(0).getCount());
		assertTrue(cargo.isEmpty());
	}

	@Test void fullReturnContainerRetainsSurplusAndRespectsMergeRestrictions() {
		SimpleContainer source = new SimpleContainer(1) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
		};
		source.setItem(0, new ItemStack(Items.COAL, 60));
		ItemStack cargo = new ItemStack(Items.COAL, 16);
		SupplyNetworkRuntime.returnToContainer(source, cargo);
		assertEquals(60, source.getItem(0).getCount());
		assertEquals(16, cargo.getCount());
		SimpleContainer accepting = new SimpleContainer(new ItemStack(Items.COAL, 60));
		SupplyNetworkRuntime.returnToContainer(accepting, cargo);
		assertEquals(64, accepting.getItem(0).getCount());
		assertEquals(12, cargo.getCount());
		assertEquals(76, accepting.getItem(0).getCount() + cargo.getCount());
	}

	@Test void unrelatedLadenCargoPreventsSourceExtraction() {
		Job job = reserve();
		SimpleContainer source = new SimpleContainer(new ItemStack(Items.COAL, 40));
		List<ItemStack> cargo = new ArrayList<>(List.of(new ItemStack(Items.DIAMOND, 2)));
		assertEquals(-1, SupplyNetworkRuntime.loadReserved(registry, job, source, cargo, stack -> 64));
		assertEquals(40, source.getItem(0).getCount());
		assertEquals(2, cargo.getFirst().getCount());
	}
}
