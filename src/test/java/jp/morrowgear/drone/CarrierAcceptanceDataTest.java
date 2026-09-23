package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierAcceptanceDataTest {
    private static net.minecraft.core.HolderLookup.Provider registries;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(initializer -> initializer.apply());
    }

    @Test void uniqueBreakLedgerRejectsDuplicatesAndRequiresEveryFixturePosition() {
        var data = new CarrierAcceptanceData();
        for (int index = 0; index < CarrierAcceptancePolicy.MINE_BLOCKS; index++) {
            assertTrue(data.recordBreak(index));
            assertFalse(data.recordBreak(index));
        }
        assertEquals(CarrierAcceptancePolicy.MINE_BLOCKS, data.recordedBreaks());
        assertEquals(CarrierAcceptancePolicy.mineWordCount(), data.minedWords.size());
        assertTrue(data.breakRecorded(0));
        assertTrue(data.breakRecorded(CarrierAcceptancePolicy.MINE_BLOCKS - 1));
        assertFalse(data.breakRecorded(-1));
        assertFalse(data.breakRecorded(CarrierAcceptancePolicy.MINE_BLOCKS));
    }

    @Test void restartLedgerEvidenceAndFullSixFaceVolumeRoundTrip() {
        var data = new CarrierAcceptanceData();
        for (int index = 0; index < CarrierAcceptancePolicy.MINE_BLOCKS; index++) assertTrue(data.recordBreak(index));
        data.generation = UUID.randomUUID();
        data.counts.putAll(Map.of("broken", 16_384, "captured", 16_394, "full_pending_index", 75));
        data.passed.addAll(List.of("M07_FULL", "M07_RESUME", "M01-M03"));
        data.resume = "MINED";
        data.restartEvidence = Optional.of(new CarrierAcceptanceData.Evidence(
            data.generation, data.minedWords, data.counts, data.passed, data.resume));
        for (int index = 0; index < CarrierAcceptancePolicy.BOX_VOLUME; index++)
            data.restartBlocks.add((index & 1) == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());

        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var encoded = CarrierAcceptanceData.CODEC.encodeStart(ops, data).getOrThrow();
        var restored = CarrierAcceptanceData.CODEC.parse(ops, encoded).getOrThrow();

        assertEquals(CarrierAcceptancePolicy.MINE_BLOCKS, restored.recordedBreaks());
        assertEquals(data.restartEvidence, restored.restartEvidence);
        assertEquals(CarrierAcceptancePolicy.BOX_VOLUME, restored.restartBlocks.size());
        assertEquals(Blocks.STONE.defaultBlockState(), restored.restartBlocks.getFirst());
        assertEquals(Blocks.AIR.defaultBlockState(), restored.restartBlocks.get(1));
    }
}
