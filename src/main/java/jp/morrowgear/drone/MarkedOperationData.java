package jp.morrowgear.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent 3D work envelopes produced by the four-point operation marker. */
public final class MarkedOperationData extends SavedData {
    private static final Codec<MarkedOperationData> CODEC = Codec.unboundedMap(Codec.STRING, Zone.CODEC)
        .fieldOf("zones").xmap(MarkedOperationData::new, data -> data.zones).codec();
    private static final SavedDataType<MarkedOperationData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "marked_operations"),
        MarkedOperationData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private final Map<String, Zone> zones;

    public MarkedOperationData() { this(new HashMap<>()); }
    private MarkedOperationData(Map<String, Zone> zones) { this.zones = new HashMap<>(zones); }
    public static MarkedOperationData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }
    public void put(UUID owner, String id, String dimension, BlockPos min, BlockPos max, long tick) {
        Zone zone = Zone.of(owner, id, dimension, min, max, tick);
        zones.put(key(owner, id), zone); setDirty();
    }
    public Zone find(UUID owner, String id, String dimension) {
        Zone zone = zones.get(key(owner, id));
        return zone != null && zone.dimension.equals(dimension) ? zone : null;
    }
    private static String key(UUID owner, String id) { return owner + "/" + id; }

    public record Zone(String owner, String id, String dimension, long min, long max, long createdTick) {
        static final Codec<Zone> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("owner").forGetter(Zone::owner), Codec.STRING.fieldOf("id").forGetter(Zone::id),
            Codec.STRING.fieldOf("dimension").forGetter(Zone::dimension), Codec.LONG.fieldOf("min").forGetter(Zone::min),
            Codec.LONG.fieldOf("max").forGetter(Zone::max), Codec.LONG.fieldOf("created_tick").forGetter(Zone::createdTick)
        ).apply(i, Zone::new));
        static Zone of(UUID owner, String id, String dimension, BlockPos a, BlockPos b, long tick) {
            BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
            return new Zone(owner.toString(), id, dimension, min.asLong(), max.asLong(), tick);
        }
        public BlockPos minPos() { return BlockPos.of(min); }
        public BlockPos maxPos() { return BlockPos.of(max); }
    }
}
