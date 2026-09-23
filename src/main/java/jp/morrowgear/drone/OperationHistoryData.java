package jp.morrowgear.drone;

import com.mojang.serialization.Codec;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** One bounded SavedData file per owner, so an inactive owner's history is never evicted by others. */
public final class OperationHistoryData extends SavedData {
    private final OperationHistory history;

    private OperationHistoryData(UUID owner, List<OperationEvent> events) {
        history = new OperationHistory(owner);
        history.replace(events);
    }

    public static OperationHistoryData get(MinecraftServer server, UUID owner) {
        Codec<OperationHistoryData> codec = OperationEventCodecs.EVENT.listOf(0, OperationEvent.HISTORY_LIMIT)
                .fieldOf("events").xmap(events -> new OperationHistoryData(owner, events),
                        data -> data.history.chronological()).codec();
        SavedDataType<OperationHistoryData> type = new SavedDataType<>(
                Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "operation_history/" + owner),
                () -> new OperationHistoryData(owner, List.of()), codec, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
        return server.overworld().getDataStorage().computeIfAbsent(type);
    }

    public void append(OperationEvent event) { if (history.append(event)) setDirty(); }
    public List<OperationEvent> events() { return history.chronological(); }
}
