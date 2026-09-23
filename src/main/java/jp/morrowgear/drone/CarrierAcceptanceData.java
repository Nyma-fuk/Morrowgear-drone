package jp.morrowgear.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jp.morrowgear.drone.carrier.CarrierAnchor;
import jp.morrowgear.drone.carrier.CarrierModule;
import jp.morrowgear.drone.carrier.CarrierPolicy;
import jp.morrowgear.drone.carrier.CarrierServiceBay;
import jp.morrowgear.drone.carrier.CarrierShip;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Separate evidence namespace. Loading it never starts or cleans a world operation. */
final class CarrierAcceptanceData extends SavedData {
    static final UUID NONE = new UUID(0, 0);
    static final Codec<List<ItemStack>> STACKS = ItemStack.OPTIONAL_CODEC.listOf(0, 54 * 64);
    record Manifest(UUID run, UUID baseline, UUID owner, UUID ship, CarrierAnchor origin, BlockPos arena,
                    List<CarrierServiceBay.Identity> drones, List<CarrierAnchor> barrels,
                    Optional<CarrierPolicy.Progress> legacy) {
        static final Codec<Manifest> CODEC = RecordCodecBuilder.create(i -> i.group(
            CarrierPolicy.UUID_CODEC.fieldOf("run").forGetter(Manifest::run),
            CarrierPolicy.UUID_CODEC.fieldOf("baseline").forGetter(Manifest::baseline),
            CarrierPolicy.UUID_CODEC.fieldOf("owner").forGetter(Manifest::owner),
            CarrierPolicy.UUID_CODEC.fieldOf("ship").forGetter(Manifest::ship),
            CarrierAnchor.CODEC.fieldOf("origin").forGetter(Manifest::origin),
            BlockPos.CODEC.fieldOf("arena").forGetter(Manifest::arena),
            CarrierServiceBay.Identity.CODEC.listOf(4, 4).fieldOf("drones").forGetter(Manifest::drones),
            CarrierAnchor.CODEC.listOf(10, 10).fieldOf("barrels").forGetter(Manifest::barrels),
            CarrierPolicy.Progress.CODEC.optionalFieldOf("legacy").forGetter(Manifest::legacy)
        ).apply(i, Manifest::new));
        String tag() { return "MG-CARRIER-ACCEPT-" + run; }
    }
    record Snapshot(List<ItemStack> cargo, List<ItemStack> player, List<ItemStack> supplies,
                    List<ItemStack> storage, Optional<CarrierPolicy.Progress> progress,
                    Optional<CarrierShip.Pending> pending) {
        static final Codec<Snapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
            STACKS.fieldOf("cargo").forGetter(Snapshot::cargo),
            STACKS.fieldOf("player").forGetter(Snapshot::player),
            STACKS.fieldOf("supplies").forGetter(Snapshot::supplies),
            STACKS.fieldOf("storage").forGetter(Snapshot::storage),
            CarrierPolicy.Progress.CODEC.optionalFieldOf("progress").forGetter(Snapshot::progress),
            CarrierShip.Pending.CODEC.optionalFieldOf("pending").forGetter(Snapshot::pending)
        ).apply(i, Snapshot::new));
    }
    record Evidence(UUID generation, List<Long> minedWords, Map<String, Integer> counts,
                    List<String> passed, String resumeStage) {
        static final Codec<Evidence> CODEC = RecordCodecBuilder.create(i -> i.group(
            CarrierPolicy.UUID_CODEC.fieldOf("generation").forGetter(Evidence::generation),
            Codec.LONG.listOf(0, CarrierAcceptancePolicy.mineWordCount()).fieldOf("mined_words").forGetter(Evidence::minedWords),
            Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("counts").forGetter(Evidence::counts),
            Codec.STRING.listOf(0, 64).fieldOf("passed").forGetter(Evidence::passed),
            Codec.STRING.fieldOf("resume_stage").forGetter(Evidence::resumeStage)
        ).apply(i, Evidence::new));
        Evidence {
            minedWords = List.copyOf(minedWords);
            counts = Map.copyOf(counts);
            passed = List.copyOf(passed);
        }
    }
    record Audit(List<Long> minedWords, Optional<Evidence> restartEvidence) {
        static final Codec<Audit> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.listOf(0, CarrierAcceptancePolicy.mineWordCount()).fieldOf("mined_words").forGetter(Audit::minedWords),
            Evidence.CODEC.optionalFieldOf("restart_evidence").forGetter(Audit::restartEvidence)
        ).apply(i, Audit::new));
        Audit { minedWords = List.copyOf(minedWords); }
    }
    static final Codec<CarrierAcceptanceData> CODEC = RecordCodecBuilder.create(i -> i.group(
        Manifest.CODEC.optionalFieldOf("manifest").forGetter(d -> d.manifest),
        Codec.STRING.fieldOf("stage").forGetter(d -> d.stage),
        Codec.STRING.fieldOf("message").forGetter(d -> d.message),
        Codec.STRING.fieldOf("resume").forGetter(d -> d.resume),
        Codec.intRange(0, CarrierAcceptancePolicy.BOX_VOLUME).fieldOf("cursor").forGetter(d -> d.cursor),
        Codec.intRange(0, CarrierAcceptancePolicy.FIXTURE_BLOCKS).fieldOf("placed").forGetter(d -> d.placed),
        CarrierPolicy.UUID_CODEC.fieldOf("generation").forGetter(d -> d.generation),
        CarrierPolicy.UUID_CODEC.fieldOf("boot").forGetter(d -> d.boot),
        Snapshot.CODEC.optionalFieldOf("initial").forGetter(d -> d.initial),
        Snapshot.CODEC.optionalFieldOf("restart").forGetter(d -> d.restart),
        Audit.CODEC.optionalFieldOf("audit", new Audit(List.of(), Optional.empty())).forGetter(d -> new Audit(d.minedWords, d.restartEvidence)),
        BlockState.CODEC.listOf(0, CarrierAcceptancePolicy.SHELL_BLOCKS).fieldOf("shell").forGetter(d -> d.shell),
        BlockState.CODEC.listOf(0, CarrierAcceptancePolicy.BOX_VOLUME).fieldOf("restart_blocks").forGetter(d -> d.restartBlocks),
        Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("counts").forGetter(d -> d.counts),
        Codec.STRING.listOf(0, 64).fieldOf("passed").forGetter(d -> d.passed),
        CarrierPolicy.UUID_CODEC.listOf(0, 4).fieldOf("targets").forGetter(d -> d.targets)
    ).apply(i, CarrierAcceptanceData::new));
    private static final SavedDataType<CarrierAcceptanceData> TYPE = new SavedDataType<>(
        CarrierModule.id("carrier_acceptance"), CarrierAcceptanceData::new, CODEC, null);

    Optional<Manifest> manifest;
    String stage, message, resume;
    int cursor, placed;
    UUID generation, boot;
    Optional<Snapshot> initial, restart;
    Optional<Evidence> restartEvidence;
    List<Long> minedWords;
    List<BlockState> shell, restartBlocks;
    Map<String, Integer> counts;
    List<String> passed;
    List<UUID> targets;

    CarrierAcceptanceData() {
        this(Optional.empty(), "EMPTY", "not run", "", 0, 0, NONE, NONE, Optional.empty(), Optional.empty(),
            new Audit(List.of(), Optional.empty()), List.of(), List.of(), Map.of(), List.of(), List.of());
    }
    private CarrierAcceptanceData(Optional<Manifest> manifest, String stage, String message, String resume,
            int cursor, int placed, UUID generation, UUID boot, Optional<Snapshot> initial,
            Optional<Snapshot> restart, Audit audit, List<BlockState> shell, List<BlockState> restartBlocks,
            Map<String, Integer> counts, List<String> passed, List<UUID> targets) {
        this.manifest = manifest; this.stage = stage; this.message = message; this.resume = resume;
        this.cursor = cursor; this.placed = placed; this.generation = generation; this.boot = boot;
        this.initial = initial; this.restart = restart; this.restartEvidence = audit.restartEvidence();
        this.minedWords = new ArrayList<>(audit.minedWords()); this.shell = new ArrayList<>(shell);
        this.restartBlocks = new ArrayList<>(restartBlocks); this.counts = new HashMap<>(counts);
        this.passed = new ArrayList<>(passed); this.targets = new ArrayList<>(targets);
    }
    static CarrierAcceptanceData get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE); }
    Manifest plan() { return manifest.orElseThrow(() -> new IllegalStateException("deep_start required")); }
    int count(String key) { return counts.getOrDefault(key, 0); }
    void add(String key, int amount) { counts.merge(key, amount, Math::addExact); setDirty(); }
    boolean recordBreak(int index) {
        if (index < 0 || index >= CarrierAcceptancePolicy.MINE_BLOCKS) throw new IllegalArgumentException("mine index");
        while (minedWords.size() < CarrierAcceptancePolicy.mineWordCount()) minedWords.add(0L);
        int word = index >>> 6;
        long mask = 1L << (index & 63), before = minedWords.get(word);
        if ((before & mask) != 0) return false;
        minedWords.set(word, before | mask); setDirty(); return true;
    }
    boolean breakRecorded(int index) {
        if (index < 0 || index >= CarrierAcceptancePolicy.MINE_BLOCKS) return false;
        int word = index >>> 6;
        return word < minedWords.size() && (minedWords.get(word) & 1L << (index & 63)) != 0;
    }
    int recordedBreaks() {
        int count = 0;
        for (long word : minedWords) count = Math.addExact(count, Long.bitCount(word));
        return count;
    }
    void mark(String stage, String message) { this.stage = stage; this.message = message; setDirty(); }
}
