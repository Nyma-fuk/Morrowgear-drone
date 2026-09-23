package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierSavedDataTest {
    private static net.minecraft.core.HolderLookup.Provider registries;
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        registries = net.minecraft.data.registries.VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(initializer -> initializer.apply());
    }
    @Test void fullHoldDoesNotMutateOrPartiallyConsumeAnyLoot() {
        var current = new CarrierCargoStorage(List.of(), () -> {});
        for (int slot = 0; slot < current.getContainerSize(); slot++) current.setItem(slot, new ItemStack(Items.DIRT, 64));
        current.setItem(0, new ItemStack(Items.COBBLESTONE, 63));
        var incoming = List.of(new ItemStack(Items.COBBLESTONE, 2));
        assertTrue(current.plan(incoming).isEmpty());
        assertEquals(63, current.getItem(0).getCount());
        assertEquals(2, incoming.getFirst().getCount());
        current.setItem(1, ItemStack.EMPTY);
        current.commit(current.plan(incoming).orElseThrow());
        assertEquals(64, current.getItem(0).getCount());
        assertEquals(1, current.getItem(1).getCount());
    }
    @Test void failedDeploymentRollsBackOnlyUnusedCabinsAndPreservesEveryRealAssignment() {
        var data = new CarrierSavedData();
        UUID owner = UUID.randomUUID(), unused = UUID.randomUUID(), occupied = UUID.randomUUID(), supplied = UUID.randomUUID();
        var anchor = new CarrierAnchor("minecraft:overworld", 0, 120, 0);
        data.assign(unused, owner, anchor);
        assertTrue(data.abandonDeployment(unused));
        assertNull(data.ship(unused));
        var real = data.assign(occupied, owner, anchor);
        data.visit(owner, new CarrierSavedData.Visit(occupied, anchor));
        data.leave(owner);
        assertFalse(data.abandonDeployment(occupied), "A persistent return assignment must not be recycled");
        var cargo = data.assign(supplied, owner, anchor);
        cargo.cargo.setItem(53, new ItemStack(Items.DIAMOND));
        assertFalse(data.abandonDeployment(supplied));
        real.destroyed = true;
        assertFalse(data.abandonDeployment(occupied));
        assertEquals(2, data.ships().size());
    }
    @Test void assignmentsInventoryPendingLootAndDeathReturnRoundTrip() {
        var data = new CarrierSavedData();
        UUID shipId = UUID.randomUUID(), owner = UUID.randomUUID(), guest = UUID.randomUUID();
        var ship = data.assign(shipId, owner, new CarrierAnchor("minecraft:overworld", -17, 160, 32));
        ship.cabinReady = true;
        ship.energy = 45000;
        ship.weaponEnergy = 98765;
        ship.miningStartupCharged = true;
        ship.bayStock = new CarrierServiceBay.Stock(119, 4);
        data.markPlayerBuilt("minecraft:overworld", new BlockPos(1, 2, 3).asLong());
        ship.guests.add(guest);
        var op = CarrierPolicy.Operation.at(UUID.randomUUID(), "minecraft:overworld", -17, 32, -64, 154);
        ship.progress = new CarrierPolicy.Progress(op, 257, List.of(0));
        // A full hold may pause on a visible foreground block ahead of the unchanged raster cursor.
        ship.pending = new CarrierShip.Pending(new BlockPos(op.x(257) + 2, op.y(257), op.z(257) + 2),
            Blocks.STONE.defaultBlockState(), List.of(new ItemStack(Items.COBBLESTONE, 1)));
        ship.cargo.setItem(0, new ItemStack(Items.DIAMOND, 31));
        ship.cargo.setItem(53, new ItemStack(Items.IRON_INGOT, 63));
        ship.mode = CarrierPolicy.Mode.MINING;
        data.visit(guest, new CarrierSavedData.Visit(shipId, new CarrierAnchor("minecraft:overworld", 5, 70, 9)));
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var encoded = CarrierSavedData.CODEC.encodeStart(ops, data).getOrThrow();
        var decoded = CarrierSavedData.CODEC.parse(ops, encoded).getOrThrow();
        var restored = decoded.ship(shipId);
        assertEquals(ship.cabin, restored.cabin);
        assertEquals(45000, restored.energy);
        assertEquals(98765, restored.weaponEnergy);
        assertTrue(restored.miningStartupCharged);
        assertEquals(new CarrierServiceBay.Stock(119, 4), restored.bayStock);
        assertTrue(decoded.playerBuilt("minecraft:overworld", new BlockPos(1, 2, 3).asLong()));
        assertEquals(ship.progress, restored.progress);
        assertEquals(ship.pending.pos(), restored.pending.pos());
        assertNotEquals(new BlockPos(op.x(257), op.y(257), op.z(257)), restored.pending.pos());
        assertEquals(ship.pending.state(), restored.pending.state());
        assertEquals(1, restored.pending.drops().getFirst().getCount());
        assertEquals(31, restored.cargo.getItem(0).getCount());
        assertEquals(63, restored.cargo.getItem(53).getCount());
        assertTrue(restored.cargo.getItem(1).isEmpty());
        assertEquals(CarrierPolicy.Mode.IDLE, restored.mode);
        assertEquals(CarrierPolicy.Stop.RELOAD, restored.stop);
        assertNull(restored.preview);
        assertTrue(restored.permits(guest));
        assertEquals(data.visit(guest), decoded.visit(guest));
        assertSame(restored, decoded.assign(shipId, owner, ship.exterior));
        restored.destroyed = true;
        assertNotEquals(restored.cabin, decoded.assign(UUID.randomUUID(), owner, ship.exterior).cabin);
        assertThrows(IllegalStateException.class, () -> decoded.assign(shipId, guest, ship.exterior));
    }
    @Test void legacyShipWithoutMiningStartupFlagDecodesAsUncharged() {
        var ship = new CarrierShip(UUID.randomUUID(), 0, new CarrierAnchor("minecraft:overworld", 0, 120, 0));
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var encoded = CarrierShip.CODEC.encodeStart(ops, ship).getOrThrow().getAsJsonObject();
        encoded.remove("mining_startup_charged");
        assertFalse(CarrierShip.CODEC.parse(ops, encoded).getOrThrow().miningStartupCharged);
    }
    @Test void cabinDimensionDecodesWithCachedMinecraftCodec() throws Exception {
        var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/data/morrowgear_drone/dimension/carrier_interior.json")));
        var result = net.minecraft.world.level.dimension.LevelStem.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), json);
        assertTrue(result.result().isPresent(), () -> result.error().map(Object::toString).orElse("No dimension"));
    }
    @Test void componentsAndUnstackableLootSurvivePlanningAndNbtRoundTrip() {
        var tool = new ItemStack(Items.NETHERITE_PICKAXE);
        tool.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Home tool"));
        tool.setDamageValue(77);
        var ship = new CarrierShip(UUID.randomUUID(), 0, new CarrierAnchor("minecraft:overworld", 0, 120, 0));
        ship.cargo.commit(ship.cargo.plan(List.of(tool, tool)).orElseThrow());
        assertEquals(1, ship.cargo.getItem(0).getCount());
        assertEquals(1, ship.cargo.getItem(1).getCount());
        assertEquals(77, ship.cargo.getItem(0).getDamageValue());
        var ops = RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, registries);
        var encoded = CarrierShip.CODEC.encodeStart(ops, ship).getOrThrow();
        var restored = CarrierShip.CODEC.parse(ops, encoded).getOrThrow();
        assertTrue(ItemStack.isSameItemSameComponents(tool, restored.cargo.getItem(0)));
        assertTrue(ItemStack.isSameItemSameComponents(tool, restored.cargo.getItem(1)));
    }
    @Test void stopRetainsImmutableProgressCargoAndCabinButCannotResumeOnItsOwn() {
        var ship = new CarrierShip(UUID.randomUUID(), 0, new CarrierAnchor("minecraft:overworld", 0, 120, 0));
        var operation = CarrierPolicy.Operation.at(UUID.randomUUID(), "minecraft:overworld", 0, 0, -64, 114);
        ship.progress = new CarrierPolicy.Progress(operation, 900, List.of(1));
        ship.cargo.setItem(0, new ItemStack(Items.DIAMOND, 7));
        ship.preview = operation;
        ship.previewUntil = 200;
        ship.mode = CarrierPolicy.Mode.MINING;
        ship.destination = new CarrierAnchor("minecraft:overworld", 60, 120, 0);
        ship.stop(CarrierPolicy.Stop.FULL);
        assertEquals(900, ship.progress.cursor());
        assertEquals(operation, ship.progress.operation());
        assertEquals(7, ship.cargo.getItem(0).getCount());
        assertNull(ship.preview);
        assertEquals(new CarrierAnchor("minecraft:overworld", 60, 120, 0), ship.destination);
        assertTrue(ship.navigationPaused);
        assertEquals(CarrierPolicy.Mode.IDLE, ship.mode);
        assertEquals(CarrierPolicy.Stop.FULL, ship.stop);
    }
    @Test void explicitWreckRecoveryPersistsWithoutEvacuatingTheOwnerAgain() {
        var visit = new CarrierSavedData.Visit(UUID.randomUUID(), new CarrierAnchor("minecraft:overworld", 7, 80, 4), true);
        var encoded = CarrierSavedData.Visit.CODEC.encodeStart(JsonOps.INSTANCE, visit).getOrThrow();
        assertTrue(CarrierSavedData.Visit.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().recovery());
        encoded.getAsJsonObject().remove("recovery");
        assertFalse(CarrierSavedData.Visit.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().recovery());
    }
    @Test void leavingClearsActiveVisitButKeepsSavedCabinAssignmentForRespawn() {
        var data = new CarrierSavedData();
        UUID player = UUID.randomUUID(), ship = UUID.randomUUID();
        var visit = new CarrierSavedData.Visit(ship, new CarrierAnchor("minecraft:overworld", 10, 70, 20));
        data.visit(player, visit);
        data.leave(player);
        assertNull(data.visit(player));
        assertEquals(visit, data.assignment(player));
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var restored = CarrierSavedData.CODEC.parse(ops, CarrierSavedData.CODEC.encodeStart(ops, data).getOrThrow()).getOrThrow();
        assertNull(restored.visit(player));
        assertEquals(visit, restored.assignment(player));
    }
}
