package jp.morrowgear.drone.network;

import java.util.ArrayList;
import java.util.List;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CargoLiftStatusPayload(List<Row> rows) implements CustomPacketPayload {
    public CargoLiftStatusPayload { rows = List.copyOf(rows); }
    public record Row(long source, String label, String mission, String status, int distance) {}

    public static final Type<CargoLiftStatusPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "cargo_lift_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CargoLiftStatusPayload> CODEC = StreamCodec.of((buffer, value) -> {
        ByteBufCodecs.VAR_INT.encode(buffer, value.rows.size());
        for (Row row : value.rows) {
            buffer.writeLong(row.source);
            ByteBufCodecs.STRING_UTF8.encode(buffer, row.label);
            ByteBufCodecs.STRING_UTF8.encode(buffer, row.mission);
            ByteBufCodecs.STRING_UTF8.encode(buffer, row.status);
            ByteBufCodecs.VAR_INT.encode(buffer, row.distance);
        }
    }, buffer -> {
        int size = Math.clamp(ByteBufCodecs.VAR_INT.decode(buffer), 0, 512);
        List<Row> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) rows.add(new Row(buffer.readLong(),
            ByteBufCodecs.STRING_UTF8.decode(buffer), ByteBufCodecs.STRING_UTF8.decode(buffer),
            ByteBufCodecs.STRING_UTF8.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer)));
        return new CargoLiftStatusPayload(rows);
    });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
