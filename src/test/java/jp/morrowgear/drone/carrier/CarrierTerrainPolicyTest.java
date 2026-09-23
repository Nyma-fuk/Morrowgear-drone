package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierTerrainPolicyTest {
    private static Set<String> natural;
    @BeforeAll static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        natural = new HashSet<>();
        var json = com.google.gson.JsonParser.parseString(Files.readString(Path.of(
            "src/main/resources/data/morrowgear_drone/tags/block/carrier_mineable.json"))).getAsJsonObject();
        json.getAsJsonArray("values").forEach(value -> natural.add(value.getAsString()));
    }

    @Test void naturalTagUsesOnlyRealCachedMinecraftBlockIds() {
        for (String id : natural) assertTrue(BuiltInRegistries.BLOCK.containsKey(Identifier.parse(id)), id);
    }

    @Test void ordinaryForestMeadowAndDesertMaterialsAreEligibleWithoutDisablingClaims() {
        for (Block block : new Block[] {Blocks.STONE, Blocks.DIRT, Blocks.SHORT_GRASS, Blocks.TALL_GRASS,
            Blocks.SUNFLOWER, Blocks.OAK_LOG, Blocks.OAK_LEAVES, Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL}) {
            boolean tagged = natural.contains(BuiltInRegistries.BLOCK.getKey(block).toString());
            assertTrue(tagged, block::toString);
            assertNull(CarrierProtection.materialStop(block.defaultBlockState(), tagged, false), block::toString);
            assertEquals(CarrierPolicy.Stop.PROTECTED, CarrierProtection.materialStop(block.defaultBlockState(), tagged, true));
            assertEquals(CarrierPolicy.Stop.PROTECTED, CarrierProtection.materialStop(block.defaultBlockState(), false, false));
        }
    }

    @Test void broadTagsCannotAuthorizeBedrockEquipmentPlayerLeavesOrArbitraryFallingBlocks() {
        for (Block block : new Block[] {Blocks.BEDROCK, Blocks.CHEST, Blocks.SPAWNER,
            Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL, Blocks.ANVIL})
            assertEquals(CarrierPolicy.Stop.PROTECTED, CarrierProtection.materialStop(block.defaultBlockState(), true, false));
        assertEquals(CarrierPolicy.Stop.PROTECTED, CarrierProtection.materialStop(
            Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), true, false));
        assertFalse(natural.contains("minecraft:oak_planks"));
        assertFalse(natural.contains("minecraft:farmland"));
        assertFalse(natural.contains("minecraft:stripped_oak_log"));
    }

    @Test void fluidOnlyBlocksCanBeDrainedButWaterloggedOrProtectedStructuresKeepMaterialPolicy() {
        for (Block block : new Block[] {Blocks.WATER, Blocks.LAVA})
            assertNull(CarrierProtection.materialStop(block.defaultBlockState(), false, false));
        assertTrue(CarrierProtection.drainableFluid(Blocks.WATER.defaultBlockState()));
        assertTrue(CarrierProtection.drainableFluid(Blocks.LAVA.defaultBlockState()));
        assertFalse(CarrierProtection.drainableFluid(Blocks.STONE.defaultBlockState()));
        assertEquals(CarrierPolicy.Stop.PROTECTED,
            CarrierProtection.materialStop(Blocks.WATER.defaultBlockState(), false, true));
        assertNotEquals(0, CarrierWork.REMOVE_FLAGS & Block.UPDATE_KNOWN_SHAPE);
        assertNotEquals(0, CarrierWork.REMOVE_FLAGS & Block.UPDATE_SUPPRESS_DROPS);
        assertNotEquals(0, CarrierWork.REMOVE_FLAGS & Block.UPDATE_CLIENTS);
        assertEquals(0, CarrierWork.REMOVE_FLAGS & Block.UPDATE_NEIGHBORS);
    }
}
