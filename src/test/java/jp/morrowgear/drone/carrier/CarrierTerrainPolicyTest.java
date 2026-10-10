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
    private static Set<String> ablative;
    @BeforeAll static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        natural = new HashSet<>();
        var json = com.google.gson.JsonParser.parseString(Files.readString(Path.of(
            "src/main/resources/data/morrowgear_drone/tags/block/carrier_mineable.json"))).getAsJsonObject();
        json.getAsJsonArray("values").forEach(value -> natural.add(value.getAsString()));
        ablative = new HashSet<>();
        var plasma = com.google.gson.JsonParser.parseString(Files.readString(Path.of(
            "src/main/resources/data/morrowgear_drone/tags/block/carrier_plasma_ablative.json"))).getAsJsonObject();
        plasma.getAsJsonArray("values").forEach(value -> ablative.add(value.getAsString()));
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

    @Test void nonPhysicalLightMarkersVaporizeButCannotOverrideExplicitProtection() {
        var light = Blocks.LIGHT.defaultBlockState();
        assertNull(CarrierProtection.materialStop(light, false, false));
        assertEquals(CarrierProtection.PlasmaResponse.VAPORIZE,
            CarrierProtection.plasmaResponse(light, false, false, false));
        assertEquals(CarrierPolicy.Stop.PROTECTED,
            CarrierProtection.materialStop(light, false, true));
        assertEquals(CarrierProtection.PlasmaResponse.RESIST,
            CarrierProtection.plasmaResponse(light, false, false, true));
        assertFalse(CarrierProtection.recoversDrops(CarrierProtection.PlasmaResponse.VAPORIZE, false));
    }

    @Test void plasmaMaterialTiersDecideWhetherAnyMiningRewardSurvives() {
        assertTrue(natural.containsAll(ablative));
        for (String id : ablative) assertTrue(BuiltInRegistries.BLOCK.containsKey(Identifier.parse(id)), id);
        for (Block block : new Block[] {Blocks.DIRT, Blocks.STONE, Blocks.GOLD_ORE})
            assertEquals(CarrierProtection.PlasmaResponse.VAPORIZE,
                CarrierProtection.plasmaResponse(block.defaultBlockState(), true, false, false));
        for (Block block : new Block[] {Blocks.DEEPSLATE, Blocks.TUFF, Blocks.ANCIENT_DEBRIS})
            assertEquals(CarrierProtection.PlasmaResponse.ABLATE,
                CarrierProtection.plasmaResponse(block.defaultBlockState(), true, true, false));
        assertEquals(CarrierProtection.PlasmaResponse.RESIST,
            CarrierProtection.plasmaResponse(Blocks.BEDROCK.defaultBlockState(), true, false, false));
        assertEquals(CarrierProtection.PlasmaResponse.RESIST,
            CarrierProtection.plasmaResponse(Blocks.CHEST.defaultBlockState(), false, false, true));
    }

    @Test void plasmaClassificationCoversEveryTagBoundaryAndUsesStrictPriority() {
        var state = Blocks.STONE.defaultBlockState();
        for (boolean naturalTag : new boolean[] {false, true})
            for (boolean ablativeTag : new boolean[] {false, true})
                for (boolean protectedTag : new boolean[] {false, true}) {
                    var expected = protectedTag ? CarrierProtection.PlasmaResponse.RESIST
                        : ablativeTag ? CarrierProtection.PlasmaResponse.ABLATE
                        : naturalTag ? CarrierProtection.PlasmaResponse.VAPORIZE
                        : CarrierProtection.PlasmaResponse.RESIST;
                    assertEquals(expected, CarrierProtection.plasmaResponse(
                        state, naturalTag, ablativeTag, protectedTag),
                        "natural=" + naturalTag + " ablative=" + ablativeTag + " protected=" + protectedTag);
                }
        assertEquals(CarrierProtection.PlasmaResponse.RESIST,
            CarrierProtection.plasmaResponse(Blocks.BEDROCK.defaultBlockState(), true, true, false));
        assertEquals(CarrierProtection.PlasmaResponse.RESIST,
            CarrierProtection.plasmaResponse(Blocks.CHEST.defaultBlockState(), true, true, false));
    }

    @Test void onlyNonFluidAblativeResidueCanGenerateDrops() {
        for (var response : CarrierProtection.PlasmaResponse.values())
            for (boolean fluid : new boolean[] {false, true})
                assertEquals(response == CarrierProtection.PlasmaResponse.ABLATE && !fluid,
                    CarrierProtection.recoversDrops(response, fluid), response + " fluid=" + fluid);
    }

    @Test void strategicCarrierMiningNeverResistsProtectedOrUnbreakableBlocks() {
        for (Block block : new Block[] {Blocks.BEDROCK, Blocks.BARRIER, Blocks.CHEST, Blocks.SPAWNER,
            Blocks.COMMAND_BLOCK, Blocks.END_PORTAL_FRAME, Blocks.REINFORCED_DEEPSLATE})
            assertEquals(CarrierProtection.PlasmaResponse.VAPORIZE,
                CarrierProtection.carrierMiningResponse(block.defaultBlockState()), block::toString);
        assertEquals(CarrierProtection.PlasmaResponse.VAPORIZE,
            CarrierProtection.carrierMiningResponse(false));
        assertEquals(CarrierProtection.PlasmaResponse.ABLATE,
            CarrierProtection.carrierMiningResponse(true));
    }

    @Test void carrierMiningHotLoopCannotRegressToClaimOrBedrockVetoes() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/jp/morrowgear/drone/carrier/CarrierWork.java"));
        assertTrue(source.contains("CarrierProtection.carrierMiningResponse(state)"));
        assertFalse(source.contains("CarrierProtection.permitted(owner, level, pos, state)"));
        assertFalse(source.contains("sameUnclaimedBlock"));
        assertFalse(source.contains("if (state.is(Blocks.BEDROCK))"));
    }

    @Test void plasmaCacheIsOneEntryPerCanonicalStateAndCanBeInvalidatedAfterReload() {
        CarrierProtection.clearPlasmaResponseCache();
        var stone = Blocks.STONE.defaultBlockState();
        var dirt = Blocks.DIRT.defaultBlockState();
        CarrierProtection.plasmaResponse(stone);
        CarrierProtection.plasmaResponse(stone);
        assertEquals(1, CarrierProtection.plasmaResponseCacheSize());
        CarrierProtection.plasmaResponse(dirt);
        assertEquals(2, CarrierProtection.plasmaResponseCacheSize());
        CarrierProtection.carrierMiningResponse(stone);
        CarrierProtection.carrierMiningResponse(stone);
        assertEquals(1, CarrierProtection.carrierMiningResponseCacheSize());
        CarrierProtection.clearPlasmaResponseCache();
        assertEquals(0, CarrierProtection.plasmaResponseCacheSize());
        assertEquals(0, CarrierProtection.carrierMiningResponseCacheSize());
    }
}
