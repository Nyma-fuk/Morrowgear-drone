package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierSuppliesTest {
    private static net.minecraft.core.HolderLookup.Provider registries;
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        registries = net.minecraft.data.registries.VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(initializer -> initializer.apply());
    }

    @Test void fiveTypedInputsDoNotConsumeCargoSlotsOrAcceptOtherSupplyKinds() {
        String[] ids = {"morrowgear_drone:power_cell", "morrowgear_drone:laser_cell",
            "morrowgear_drone:autocannon_magazine", "morrowgear_drone:micro_missile_pack", "minecraft:iron_ingot"};
        for (int slot = 0; slot < 5; slot++) for (int kind = 0; kind < 5; kind++)
            assertEquals(slot == kind, CarrierSupplies.accepts(slot, ids[kind]));
        for (int slot = -1; slot <= 5; slot++) {
            assertFalse(CarrierSupplies.accepts(slot, null));
            assertFalse(CarrierSupplies.accepts(slot, "minecraft:coal"));
            assertFalse(CarrierSupplies.accepts(slot, "minecraft:diamond"));
            assertFalse(CarrierSupplies.accepts(slot, "morrowgear_drone:standard_battery_pack"));
        }
        assertTrue(CarrierSupplies.accepts(CarrierSupplies.REPAIR, "minecraft:copper_ingot"));
        assertTrue(CarrierSupplies.accepts(CarrierSupplies.REPAIR, "morrowgear_drone:morrow_alloy"));
        assertEquals(54, CarrierMenu.SUPPLY_START);
        assertEquals(59, CarrierMenu.PLAYER_START);
        assertEquals(95, CarrierMenu.PLAYER_START + 36);
        assertEquals(3456, ship().cargo.getContainerSize());
    }

    @Test void dedicatedInputPrecedesLegacyCargoAndConsumesExactlyOne() {
        var ship = ship();
        var dirty = new AtomicInteger();
        ship.dirty = dirty::incrementAndGet;
        ship.supplies.setItem(CarrierSupplies.REPAIR, new ItemStack(Items.COPPER_INGOT, 2));
        ship.cargo.setItem(53, new ItemStack(Items.IRON_INGOT, 3));
        var first = CarrierSupplies.find(ship, CarrierSupplies.REPAIR);
        assertSame(ship.supplies, first.container());
        var offered = first.one();
        offered.shrink(1);
        assertEquals(2, ship.supplies.getItem(CarrierSupplies.REPAIR).getCount(), "Offering a copy is not consumption");
        int before = dirty.get();
        first.consume();
        assertTrue(dirty.get() > before);
        assertEquals(1, ship.supplies.getItem(CarrierSupplies.REPAIR).getCount());
        assertEquals(3, ship.cargo.getItem(53).getCount());
        first.consume();
        var fallback = CarrierSupplies.find(ship, CarrierSupplies.REPAIR);
        assertSame(ship.cargo, fallback.container());
        fallback.consume();
        assertEquals(2, ship.cargo.getItem(53).getCount());
        assertNull(CarrierSupplies.find(ship, CarrierSupplies.GUN));
    }

    @Test void wrongTypedSavedInputIsPreservedButNeverSuppliedAsAWeapon() {
        var ship = ship();
        ship.supplies.setItem(CarrierSupplies.GUN, new ItemStack(Items.IRON_INGOT, 9));
        assertFalse(ship.supplies.canPlaceItem(CarrierSupplies.GUN, ship.supplies.getItem(CarrierSupplies.GUN)));
        assertNull(CarrierSupplies.find(ship, CarrierSupplies.GUN));
        assertNull(CarrierSupplies.find(ship, CarrierSupplies.REPAIR));
        assertEquals(9, ship.supplies.getItem(CarrierSupplies.GUN).getCount());
    }

    @Test void cellsRequireRoomForAllEnergyAndDoNotDrainAnEmptyOrUnrelatedInventory() {
        assertTrue(CarrierSupplies.canCharge(0));
        assertTrue(CarrierSupplies.canCharge(99000));
        for (int energy : new int[] {-1, 99001, 100000, Integer.MAX_VALUE}) assertFalse(CarrierSupplies.canCharge(energy));
        var ship = ship();
        ship.cargo.setItem(0, new ItemStack(Items.COAL, 64));
        ship.energy = 99000;
        ship.weaponEnergy = 150;
        CarrierSupplies.tick(ship, 20);
        assertEquals(99000 + CarrierPowerPolicy.FLIGHT_GENERATION_PER_TICK, ship.energy);
        assertEquals(150 + CarrierPowerPolicy.WORK_GENERATION_PER_TICK, ship.weaponEnergy);
        assertEquals(64, ship.cargo.getItem(0).getCount());
    }

    @Test void partialAcceptanceAndRejectionPreserveExistingPackCredits() {
        var ship = ship();
        assertEquals(109, CarrierServiceBay.transferPack(ship, CarrierSupplies.GUN, 119, 40, 120,
            offered -> { assertEquals(40, offered); return 10; }));
        assertEquals(4, CarrierServiceBay.transferPack(ship, CarrierSupplies.MISSILES, 4, 2, 5, offered -> 0));
        assertEquals(0, CarrierServiceBay.transferPack(ship, CarrierSupplies.MISSILES, 0, 2, 5,
            offered -> { fail("An empty supply must not call the receiver"); return 0; }));
        assertThrows(IllegalStateException.class, () -> CarrierServiceBay.transferPack(ship, CarrierSupplies.GUN,
            119, 40, 120, offered -> 41));
    }

    @Test void suppliesSaveAlongsideCargoAndOldSavesDecodeWithEmptyInputs() {
        var ship = ship();
        ship.cargo.setItem(53, new ItemStack(Items.DIAMOND, 17));
        var repair = new ItemStack(Items.IRON_INGOT, 23);
        repair.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Bay reserve"));
        ship.supplies.setItem(CarrierSupplies.REPAIR, repair);
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var json = CarrierShip.CODEC.encodeStart(ops, ship).getOrThrow();
        var restored = CarrierShip.CODEC.parse(ops, json).getOrThrow();
        assertEquals(17, restored.cargo.getItem(53).getCount());
        assertEquals(23, restored.supplies.getItem(CarrierSupplies.REPAIR).getCount());
        assertTrue(ItemStack.isSameItemSameComponents(repair, restored.supplies.getItem(CarrierSupplies.REPAIR)));
        json.getAsJsonObject().remove("supplies");
        var legacy = CarrierShip.CODEC.parse(ops, json).getOrThrow();
        assertTrue(legacy.supplies.isEmpty());
        assertEquals(17, legacy.cargo.getItem(53).getCount());
        assertEquals(CarrierPolicy.Stop.RELOAD, legacy.stop);
        assertEquals(3456, legacy.cargo.getContainerSize());
    }

    private static CarrierShip ship() {
        return new CarrierShip(UUID.randomUUID(), 0, new CarrierAnchor("minecraft:overworld", 0, 120, 0));
    }
}
