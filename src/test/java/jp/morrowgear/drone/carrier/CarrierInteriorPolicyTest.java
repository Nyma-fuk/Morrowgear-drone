package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierInteriorPolicyTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registries = net.minecraft.data.registries.VanillaRegistries.createLookup();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
            .forEach(initializer -> initializer.apply());
    }

    @Test void allBlocksOfBedsDoorsAndTallPlantsMustFitThePrivateBuildArea() {
        var bedFoot = new BlockPos(4, 65, 2);
        var bed = CarrierInterior.placementFootprint(Blocks.BED.red(), bedFoot, Direction.WEST);
        assertEquals(2, bed.size());
        assertFalse(CarrierPolicy.reservedEntry(0, bedFoot.getX(), bedFoot.getY(), bedFoot.getZ()));
        assertTrue(bed.stream().anyMatch(pos -> CarrierPolicy.reservedEntry(0, pos.getX(), pos.getY(), pos.getZ())),
            "The second bed block would intrude into the protected entry pad");
        for (var block : new net.minecraft.world.level.block.Block[] {Blocks.OAK_DOOR, Blocks.SUNFLOWER}) {
            var footprint = CarrierInterior.placementFootprint(block, new BlockPos(7, 75, 7), Direction.NORTH);
            assertEquals(2, footprint.size());
            assertTrue(footprint.stream().anyMatch(pos -> !CarrierPolicy.inCabin(0, pos.getX(), pos.getY(), pos.getZ())));
        }
        assertEquals(java.util.List.of(bedFoot), CarrierInterior.placementFootprint(Blocks.CHEST, bedFoot, Direction.NORTH));
    }

    @Test void runtimeHullBoxMatchesTheApprovedRectangularV27Envelope() {
        var origin = new net.minecraft.world.phys.Vec3(-100.5, 120, 30.5);
        var box = CarrierEntity.exteriorBounds(origin);
        assertEquals(-113.5, box.minX); assertEquals(-87.5, box.maxX);
        assertEquals(120, box.minY); assertEquals(131, box.maxY);
        assertEquals(5.5, box.minZ); assertEquals(55.5, box.maxZ);
        assertEquals(26, box.getXsize()); assertEquals(50, box.getZsize()); assertEquals(11, box.getYsize());
    }

    @Test void ordinaryBuildingAndStorageRemainAllowedWhileEscapeAndFluidItemsAreBlocked() {
        for (var item : new net.minecraft.world.item.Item[] {Items.OAK_PLANKS, Items.CHEST, Items.FURNACE, Items.BED.red()})
            assertTrue(CarrierInterior.allowedItem(new ItemStack(item)));
        for (var item : new net.minecraft.world.item.Item[] {Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.BUCKET,
            Items.CHORUS_FRUIT, Items.ENDER_PEARL, Items.FLINT_AND_STEEL, Items.TNT, Items.PISTON, Items.DISPENSER})
            assertFalse(CarrierInterior.allowedItem(new ItemStack(item)));
    }

    @Test void automaticBoardingRearmsOnlyAfterLeavingTheExitedShipsZone() {
        assertFalse(CarrierInterior.rearmComplete(true, true, true));
        assertTrue(CarrierInterior.rearmComplete(true, true, false));
        assertTrue(CarrierInterior.rearmComplete(false, true, true));
        assertTrue(CarrierInterior.rearmComplete(true, false, true));
    }

    @Test void exitLatchAffectsAutomaticTouchOnlyAndHasLifecycleCleanup() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/carrier/CarrierInterior.java"));
        String touch = source.substring(source.indexOf("static void touchBoarding("),
            source.indexOf("public static BlockPos boardingPad("));
        String board = source.substring(source.indexOf("public static boolean board("),
            source.indexOf("static boolean ready("));
        assertTrue(touch.contains("automaticBoardingReady(player)"));
        assertFalse(board.contains("automaticBoardingReady"));
        assertTrue(board.contains("AUTO_BOARD_REARM.remove(player.getUUID())"));
        assertTrue(source.contains("AUTO_BOARD_REARM.put(player.getUUID(), new BoardingRearm(shipId, level.dimension()))"));
        assertTrue(source.contains("if (!inside(player.level())) automaticBoardingReady(player)"));
        assertTrue(source.contains("public static void reset() { COOLDOWN.clear(); AUTO_BOARD_REARM.clear(); }"));
    }
}
