package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.SupplyNetworkPolicy;
import jp.morrowgear.drone.SupplyNetworkPolicy.Rule;
import jp.morrowgear.drone.SupplyNetworkPolicy.Status;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SupplyNetworkStatusPayload(int requestId, UUID owner, String dimension, List<DockStatus> docks)
    implements CustomPacketPayload {
    public static final Type<SupplyNetworkStatusPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath("morrowgear_drone", "supply_network_status"));
    public static final StreamCodec<ByteBuf, SupplyNetworkStatusPayload> CODEC = StreamCodec.of((buf, value) -> {
        buf.writeInt(value.requestId);
        writeUuid(buf, value.owner);
        ByteBufCodecs.stringUtf8(256).encode(buf, value.dimension);
        buf.writeByte(value.docks.size());
        for (DockStatus dock : value.docks) {
            buf.writeLong(dock.source).writeLong(dock.dock).writeBoolean(dock.enabled);
            ByteBufCodecs.stringUtf8(40).encode(buf, dock.status.name());
            for (Stock stock : dock.stock) {
                buf.writeByte(stock.rule.kind().index());
                buf.writeInt(stock.rule.minimum()).writeByte(stock.rule.priority());
                buf.writeInt(stock.available).writeInt(stock.incoming).writeInt(stock.missing);
            }
            buf.writeByte(dock.deliveries.size());
            for (Delivery job : dock.deliveries) {
                writeUuid(buf, job.drone);
                ByteBufCodecs.stringUtf8(40).encode(buf, job.status.name());
                buf.writeByte(job.kind.index()).writeInt(job.remaining);
            }
        }
    }, buf -> {
        int request = buf.readInt();
        UUID owner = readUuid(buf);
        String dimension = ByteBufCodecs.stringUtf8(256).decode(buf);
        int count = boundedCount(buf, SupplyNetworkPolicy.MAX_ROUTES);
        List<DockStatus> docks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long source = buf.readLong(), dock = buf.readLong();
            boolean enabled = buf.readBoolean();
            Status status = status(buf);
            List<Stock> stocks = new ArrayList<>(5);
            for (int j = 0; j < 5; j++) {
                SupplyKind kind = kind(buf.readUnsignedByte());
                Rule rule = new Rule(kind, buf.readInt(), buf.readUnsignedByte());
                stocks.add(new Stock(rule, buf.readInt(), buf.readInt(), buf.readInt()));
            }
            int jobs = boundedCount(buf, SupplyNetworkPolicy.MAX_JOBS);
            List<Delivery> deliveries = new ArrayList<>(jobs);
            for (int j = 0; j < jobs; j++) deliveries.add(new Delivery(readUuid(buf), status(buf),
                kind(buf.readUnsignedByte()), buf.readInt()));
            docks.add(new DockStatus(source, dock, enabled, status, stocks, deliveries));
        }
        return new SupplyNetworkStatusPayload(request, owner, dimension, docks);
    });

    public SupplyNetworkStatusPayload {
        if (owner == null || dimension == null || dimension.length() > 256 || docks.size() > SupplyNetworkPolicy.MAX_ROUTES)
            throw new IllegalArgumentException("Invalid supply snapshot");
        docks = List.copyOf(docks);
    }
    public record DockStatus(long source, long dock, boolean enabled, Status status, List<Stock> stock, List<Delivery> deliveries) {
        public DockStatus {
            if (status == null || stock.size() != 5 || stock.stream().map(s -> s.rule.kind()).distinct().count() != 5
                || deliveries.size() > SupplyNetworkPolicy.MAX_JOBS) throw new IllegalArgumentException("Invalid Dock supply status");
            stock = List.copyOf(stock);
            deliveries = List.copyOf(deliveries);
        }
        public List<Rule> rules() { return stock.stream().map(Stock::rule).toList(); }
    }
    public record Stock(Rule rule, int available, int incoming, int missing) {
        public Stock {
            if (rule == null || available < -1 || available > 4096 || incoming < 0 || incoming > 4096
                || missing < -1 || missing > SupplyNetworkPolicy.MAX_STOCK) throw new IllegalArgumentException("Invalid supply quantities");
        }
    }
    public record Delivery(UUID drone, Status status, SupplyKind kind, int remaining) {
        public Delivery {
            if (drone == null || status == null || kind == null || remaining < 0 || remaining > SupplyNetworkPolicy.MAX_TRIP_ITEMS)
                throw new IllegalArgumentException("Invalid supply delivery");
        }
    }
    private static int boundedCount(ByteBuf buf, int max) {
        int count = buf.readUnsignedByte();
        if (count > max) throw new DecoderException("Oversized supply snapshot");
        return count;
    }
    private static SupplyKind kind(int id) {
        for (SupplyKind kind : SupplyKind.values()) if (kind.index() == id) return kind;
        throw new DecoderException("Unknown supply kind");
    }
    private static Status status(ByteBuf buf) {
        try { return Status.valueOf(ByteBufCodecs.stringUtf8(40).decode(buf)); }
        catch (IllegalArgumentException error) { throw new DecoderException("Unknown supply status", error); }
    }
    private static void writeUuid(ByteBuf buf, UUID value) { buf.writeLong(value.getMostSignificantBits()).writeLong(value.getLeastSignificantBits()); }
    private static UUID readUuid(ByteBuf buf) { return new UUID(buf.readLong(), buf.readLong()); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
