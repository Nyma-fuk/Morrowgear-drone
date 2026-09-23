package jp.morrowgear.drone.carrier;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.Arrays;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record CarrierViewPayload(int menu, UUID ship, boolean owner, boolean destroyed, int mode, int stop,
    UUID generation, String dimension, int chunkX, int chunkZ, int minY, int maxY, int cursor, int total,
    int energy, int weaponEnergy, int bayUsed, int previewTicks, List<UUID> guests, List<UUID> drones, boolean moving,
    int previewMode, Navigation navigation, Terrain terrain, List<DroneCandidate> candidates)
    implements CustomPacketPayload {
    public record DroneCandidate(UUID drone, String label) {
        public DroneCandidate {
            label = label == null ? "" : label;
            if (label.length() > 48) label = label.substring(0, 48);
        }
    }
    public record Terrain(String dimension, int centerX, int centerZ, int spacing, int width, int height, byte[] colors) {
        public static final int MAX_SIDE = 49, MAX_CELLS = MAX_SIDE * MAX_SIDE;
        public static final Terrain EMPTY = new Terrain("", 0, 0, 1, 0, 0, new byte[0]);
        public Terrain {
            dimension = dimension == null ? "" : dimension;
            colors = colors == null ? new byte[0] : colors.clone();
            if (spacing < 1 || width < 0 || height < 0 || width > MAX_SIDE || height > MAX_SIDE
                || (long) width * height != colors.length)
                throw new IllegalArgumentException("Invalid carrier terrain bounds");
        }
        @Override public byte[] colors() { return colors.clone(); }
        public static Terrain unavailable(String dimension) { return new Terrain(dimension, 0, 0, 1, 0, 0, new byte[0]); }
        public boolean specified() { return !dimension.isBlank(); }
        public boolean available() { return width > 0 && height > 0 && !dimension.isBlank(); }
        public int packedAt(double worldX, double worldZ) {
            if (!available()) return -1;
            int column = (int)Math.round((worldX - centerX) / spacing) + width / 2;
            int row = (int)Math.round((worldZ - centerZ) / spacing) + height / 2;
            if (column < 0 || row < 0 || column >= width || row >= height) return -1;
            int packed = colors[row * width + column] & 0xff;
            return packed == 0 ? -1 : packed;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Terrain value && centerX == value.centerX && centerZ == value.centerZ
                && spacing == value.spacing && width == value.width && height == value.height
                && dimension.equals(value.dimension) && Arrays.equals(colors, value.colors);
        }
        @Override public int hashCode() {
            return 31 * java.util.Objects.hash(dimension, centerX, centerZ, spacing, width, height) + Arrays.hashCode(colors);
        }
    }
    public record Storage(int page, int pages, int usedSlots, int totalSlots, long items) {
        public static final Storage EMPTY = new Storage(0, CarrierPolicy.CARGO_PAGES, 0, CarrierPolicy.STORAGE_SLOTS, 0);
        public static final Codec<Storage> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(0, CarrierPolicy.CARGO_PAGES - 1).fieldOf("page").forGetter(Storage::page),
            Codec.intRange(1, CarrierPolicy.CARGO_PAGES).fieldOf("pages").forGetter(Storage::pages),
            Codec.intRange(0, CarrierPolicy.STORAGE_SLOTS).fieldOf("used").forGetter(Storage::usedSlots),
            Codec.intRange(0, CarrierPolicy.STORAGE_SLOTS).fieldOf("slots").forGetter(Storage::totalSlots),
            Codec.LONG.validate(value -> value >= 0 ? com.mojang.serialization.DataResult.success(value)
                : com.mojang.serialization.DataResult.error(() -> "Negative stored item count")).fieldOf("items").forGetter(Storage::items)
        ).apply(i, Storage::new));
    }
    public record Effects(CarrierPolicy.WorkPhase phase, int phaseTick, int phaseDuration, int sampleTick,
                          List<Vec3> beamAims, int captureSequence, int minedBlocks, int capturedItems,
                          BlockPos lastCapturePosition, int combatRadius) {
        public static final Effects EMPTY = new Effects(CarrierPolicy.WorkPhase.IDLE, 0, 0, 0, List.of(), 0, 0, 0, BlockPos.ZERO, 0);
        public Effects { beamAims = List.copyOf(beamAims); lastCapturePosition = lastCapturePosition.immutable(); }
        private static final Codec<CarrierPolicy.WorkPhase> PHASE_CODEC = Codec.intRange(0, CarrierPolicy.WorkPhase.values().length - 1)
            .xmap(value -> CarrierPolicy.WorkPhase.values()[value], Enum::ordinal);
        public static final Codec<Effects> CODEC = RecordCodecBuilder.create(i -> i.group(
            PHASE_CODEC.fieldOf("phase").forGetter(Effects::phase),
            Codec.intRange(0, CarrierPolicy.COMBAT_TICKS).fieldOf("phase_tick").forGetter(Effects::phaseTick),
            Codec.intRange(0, CarrierPolicy.COMBAT_TICKS).fieldOf("duration").forGetter(Effects::phaseDuration),
            Codec.INT.fieldOf("sample_tick").forGetter(Effects::sampleTick),
            Vec3.CODEC.listOf(0, CarrierPolicy.COMBAT_TARGETS).fieldOf("aims").forGetter(Effects::beamAims),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("sequence").forGetter(Effects::captureSequence),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("mined").forGetter(Effects::minedBlocks),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("captured").forGetter(Effects::capturedItems),
            BlockPos.CODEC.fieldOf("capture_pos").forGetter(Effects::lastCapturePosition),
            Codec.intRange(0, CarrierPolicy.COMBAT_RADIUS).fieldOf("radius").forGetter(Effects::combatRadius)
        ).apply(i, Effects::new));
        static Effects of(CarrierEntity entity, CarrierShip ship) {
            boolean entityCurrent = entity != null && entity.workMode() == ship.mode;
            var phase = entityCurrent ? entity.workPhase() : ship.workPhase();
            boolean combat = ship.mode == CarrierPolicy.Mode.COMBAT || ship.preview != null && ship.previewMode == CarrierPolicy.Mode.COMBAT;
            return new Effects(phase, entityCurrent ? entity.phaseTick() : ship.workPhaseTick(),
                entityCurrent ? entity.phaseDuration() : ship.workPhaseDuration(),
                entity == null ? 0 : entity.sampleTick(),
                entity == null || ship.mode == CarrierPolicy.Mode.IDLE || entity.workMode() != ship.mode ? List.of() : entity.beamAims(),
                entity == null ? 0 : entity.captureSequence(), entity == null ? 0 : entity.minedBlocks(),
                entity == null ? 0 : entity.capturedItems(), entity == null ? BlockPos.ZERO : entity.lastCapturePosition(),
                combat ? CarrierPolicy.COMBAT_RADIUS : 0);
        }
    }
    public record Bay(UUID drone, int slot, boolean aligned, int phase) {
        public Bay(UUID drone, int slot, boolean aligned) {
            this(drone, slot, aligned, aligned ? CarrierServiceBay.Phase.SERVICE.ordinal() : CarrierServiceBay.Phase.APPROACH.ordinal());
        }
        public static final Codec<Bay> CODEC = RecordCodecBuilder.create(i -> i.group(
            CarrierPolicy.UUID_CODEC.fieldOf("drone").forGetter(Bay::drone),
            Codec.intRange(0, CarrierPolicy.BAY_SLOTS - 1).fieldOf("slot").forGetter(Bay::slot),
            Codec.BOOL.fieldOf("aligned").forGetter(Bay::aligned),
            Codec.intRange(0, CarrierServiceBay.Phase.values().length - 1).optionalFieldOf("phase", CarrierServiceBay.Phase.APPROACH.ordinal()).forGetter(Bay::phase)
        ).apply(i, Bay::new));
    }
    public record Navigation(CarrierAnchor exterior, Optional<CarrierAnchor> destination, int combatTicks,
                              List<Bay> bays, int gunCredit, int missileCredit, Storage storage, Effects effects,
                              boolean navigationPaused, int loadWaitTicks, int commandRevision) {
        public Navigation(CarrierAnchor exterior, Optional<CarrierAnchor> destination, int combatTicks) {
            this(exterior, destination, combatTicks, List.of(), 0, 0);
        }
        public Navigation(CarrierAnchor exterior, Optional<CarrierAnchor> destination, int combatTicks,
                           List<Bay> bays, int gunCredit, int missileCredit) {
            this(exterior, destination, combatTicks, bays, gunCredit, missileCredit, Storage.EMPTY, Effects.EMPTY, false, 0, 0);
        }
        public Navigation(CarrierAnchor exterior, Optional<CarrierAnchor> destination, int combatTicks,
                          List<Bay> bays, int gunCredit, int missileCredit, Storage storage, Effects effects,
                          boolean navigationPaused, int loadWaitTicks) {
            this(exterior, destination, combatTicks, bays, gunCredit, missileCredit, storage, effects,
                navigationPaused, loadWaitTicks, 0);
        }
        public Navigation { bays = List.copyOf(bays); }
        public Navigation withStorage(Storage value) {
            return new Navigation(exterior, destination, combatTicks, bays, gunCredit, missileCredit, value, effects, navigationPaused, loadWaitTicks, commandRevision);
        }
        public static final Codec<Navigation> CODEC = RecordCodecBuilder.create(i -> i.group(
            CarrierAnchor.CODEC.fieldOf("exterior").forGetter(Navigation::exterior),
            CarrierAnchor.CODEC.optionalFieldOf("destination").forGetter(Navigation::destination),
            Codec.intRange(0, CarrierPolicy.COMBAT_TICKS).fieldOf("combat_ticks").forGetter(Navigation::combatTicks),
            Bay.CODEC.listOf(0, CarrierPolicy.BAY_SLOTS).optionalFieldOf("bays", List.of()).forGetter(Navigation::bays),
            Codec.intRange(0, jp.morrowgear.drone.DockSupplyPolicy.MAGAZINE_ROUNDS - 1).optionalFieldOf("gun_credit", 0).forGetter(Navigation::gunCredit),
            Codec.intRange(0, jp.morrowgear.drone.DockSupplyPolicy.MISSILE_PACK_ROUNDS - 1).optionalFieldOf("missile_credit", 0).forGetter(Navigation::missileCredit),
            Storage.CODEC.optionalFieldOf("storage", Storage.EMPTY).forGetter(Navigation::storage),
            Effects.CODEC.optionalFieldOf("effects", Effects.EMPTY).forGetter(Navigation::effects),
            Codec.BOOL.optionalFieldOf("paused", false).forGetter(Navigation::navigationPaused),
            Codec.intRange(0, CarrierNavigation.LOAD_WAIT_TICKS).optionalFieldOf("wait", 0).forGetter(Navigation::loadWaitTicks),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("command_revision", 0).forGetter(Navigation::commandRevision)
        ).apply(i, Navigation::new));
    }
    public static final Type<CarrierViewPayload> TYPE = new Type<>(CarrierModule.id("carrier_view"));
    public CarrierViewPayload(int menu, UUID ship, boolean owner, boolean destroyed, int mode, int stop,
        UUID generation, String dimension, int chunkX, int chunkZ, int minY, int maxY, int cursor, int total,
        int energy, int weaponEnergy, int bayUsed, int previewTicks, List<UUID> guests, List<UUID> drones,
        boolean moving, int previewMode, Navigation navigation) {
        this(menu, ship, owner, destroyed, mode, stop, generation, dimension, chunkX, chunkZ, minY, maxY,
            cursor, total, energy, weaponEnergy, bayUsed, previewTicks, guests, drones, moving, previewMode,
            navigation, Terrain.EMPTY, List.of());
    }
    public static final StreamCodec<ByteBuf, CarrierViewPayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeInt(p.menu); CarrierCommandPayload.writeUuid(buf, p.ship);
        buf.writeBoolean(p.owner); buf.writeBoolean(p.destroyed); buf.writeByte(p.mode); buf.writeByte(p.stop);
        CarrierCommandPayload.writeUuid(buf, p.generation);
        ByteBufCodecs.stringUtf8(256).encode(buf, p.dimension);
        buf.writeInt(p.chunkX); buf.writeInt(p.chunkZ); buf.writeInt(p.minY); buf.writeInt(p.maxY);
        buf.writeInt(p.cursor); buf.writeInt(p.total); buf.writeInt(p.energy); buf.writeInt(p.weaponEnergy); buf.writeInt(p.bayUsed);
        buf.writeInt(p.previewTicks);
        buf.writeByte(p.guests.size()); p.guests.forEach(id -> CarrierCommandPayload.writeUuid(buf, id));
        buf.writeByte(p.drones.size()); p.drones.forEach(id -> CarrierCommandPayload.writeUuid(buf, id));
        buf.writeBoolean(p.moving);
        buf.writeByte(p.previewMode);
        ByteBufCodecs.fromCodec(Navigation.CODEC).encode(buf, p.navigation);
        writeTerrain(buf, p.terrain);
        buf.writeByte(p.candidates.size());
        for (DroneCandidate candidate : p.candidates) {
            CarrierCommandPayload.writeUuid(buf, candidate.drone);
            ByteBufCodecs.stringUtf8(64).encode(buf, candidate.label);
        }
    }, buf -> {
        int menu = buf.readInt(); UUID ship = CarrierCommandPayload.readUuid(buf);
        boolean owner = buf.readBoolean(), destroyed = buf.readBoolean();
        int mode = buf.readUnsignedByte(), stop = buf.readUnsignedByte();
        if (mode >= CarrierPolicy.Mode.values().length || stop >= CarrierPolicy.Stop.values().length)
            throw new DecoderException("Invalid carrier state");
        UUID generation = CarrierCommandPayload.readUuid(buf);
        String dimension = ByteBufCodecs.stringUtf8(256).decode(buf);
        int cx = buf.readInt(), cz = buf.readInt(), min = buf.readInt(), max = buf.readInt();
        int cursor = buf.readInt(), total = buf.readInt(), energy = buf.readInt(), weapon = buf.readInt(), bays = buf.readInt(), ticks = buf.readInt();
        List<UUID> guests = readIds(buf, 32), drones = readIds(buf, CarrierPolicy.BAY_SLOTS);
        boolean moving = buf.readBoolean();
        int previewMode = buf.readUnsignedByte();
        if (previewMode >= CarrierPolicy.Mode.values().length) throw new DecoderException("Invalid carrier preview mode");
        Navigation navigation = ByteBufCodecs.fromCodec(Navigation.CODEC).decode(buf);
        Terrain terrain = readTerrain(buf);
        int candidateCount = buf.readUnsignedByte();
        if (candidateCount > 32) throw new DecoderException("Oversized carrier candidate list");
        var candidates = new java.util.ArrayList<DroneCandidate>(candidateCount);
        for (int i = 0; i < candidateCount; i++) candidates.add(new DroneCandidate(
            CarrierCommandPayload.readUuid(buf), ByteBufCodecs.stringUtf8(64).decode(buf)));
        return new CarrierViewPayload(menu, ship, owner, destroyed, mode, stop, generation, dimension,
            cx, cz, min, max, cursor, total, energy, weapon, bays, ticks, guests, drones, moving, previewMode,
            navigation, terrain, candidates);
    });
    public CarrierViewPayload {
        guests = List.copyOf(guests); drones = List.copyOf(drones); candidates = List.copyOf(candidates);
        terrain = terrain == null ? Terrain.EMPTY : terrain;
        if (candidates.size() > 32) throw new IllegalArgumentException("Oversized carrier candidate list");
    }
    public CarrierViewPayload withStorage(Storage value) {
        return new CarrierViewPayload(menu, ship, owner, destroyed, mode, stop, generation, dimension, chunkX, chunkZ,
            minY, maxY, cursor, total, energy, weaponEnergy, bayUsed, previewTicks, guests, drones, moving, previewMode,
            navigation.withStorage(value), terrain, candidates);
    }
    public CarrierViewPayload withTerrain(Terrain value) {
        return new CarrierViewPayload(menu, ship, owner, destroyed, mode, stop, generation, dimension, chunkX, chunkZ,
            minY, maxY, cursor, total, energy, weaponEnergy, bayUsed, previewTicks, guests, drones, moving, previewMode,
            navigation, value, candidates);
    }
    public CarrierViewPayload withCandidates(List<DroneCandidate> value) {
        return new CarrierViewPayload(menu, ship, owner, destroyed, mode, stop, generation, dimension, chunkX, chunkZ,
            minY, maxY, cursor, total, energy, weaponEnergy, bayUsed, previewTicks, guests, drones, moving, previewMode,
            navigation, terrain, value);
    }
    public CarrierViewPayload withMenu(int value) {
        return new CarrierViewPayload(value, ship, owner, destroyed, mode, stop, generation, dimension, chunkX, chunkZ,
            minY, maxY, cursor, total, energy, weaponEnergy, bayUsed, previewTicks, guests, drones, moving, previewMode,
            navigation, terrain, candidates);
    }
    private static void writeTerrain(ByteBuf buf, Terrain terrain) {
        buf.writeBoolean(terrain.specified());
        if (!terrain.specified()) return;
        ByteBufCodecs.stringUtf8(256).encode(buf, terrain.dimension);
        buf.writeInt(terrain.centerX); buf.writeInt(terrain.centerZ); buf.writeByte(terrain.spacing);
        buf.writeByte(terrain.width); buf.writeByte(terrain.height);
        byte[] colors = terrain.colors; buf.writeShort(colors.length); buf.writeBytes(colors);
    }
    private static Terrain readTerrain(ByteBuf buf) {
        if (!buf.readBoolean()) return Terrain.EMPTY;
        String dimension = ByteBufCodecs.stringUtf8(256).decode(buf);
        int centerX = buf.readInt(), centerZ = buf.readInt(), spacing = buf.readUnsignedByte();
        int width = buf.readUnsignedByte(), height = buf.readUnsignedByte(), count = buf.readUnsignedShort();
        if (spacing < 1 || width > Terrain.MAX_SIDE || height > Terrain.MAX_SIDE || count != width * height)
            throw new DecoderException("Invalid carrier terrain bounds");
        byte[] colors = new byte[count]; buf.readBytes(colors);
        return new Terrain(dimension, centerX, centerZ, spacing, width, height, colors);
    }
    private static List<UUID> readIds(ByteBuf buf, int max) {
        int count = buf.readUnsignedByte();
        if (count > max) throw new DecoderException("Oversized carrier list");
        var result = new java.util.ArrayList<UUID>(count);
        for (int i = 0; i < count; i++) result.add(CarrierCommandPayload.readUuid(buf));
        return result;
    }
    public static CarrierViewPayload of(int menu, UUID id, CarrierShip ship, ServerPlayer player) {
        return of(menu, id, ship, player, 0);
    }
    public static CarrierViewPayload of(int menu, UUID id, CarrierShip ship, ServerPlayer player, int cargoPage) {
        var op = ship.preview != null ? ship.preview : ship.activeCombat != null ? ship.activeCombat
            : ship.progress == null ? null : ship.progress.operation();
        boolean owner = ship.owner.equals(player.getUUID());
        var exterior = CarrierModule.find(player.level().getServer(), id);
        var bays = ship.bay.snapshot().stream().map(lease -> {
            boolean aligned = exterior != null && ship.bay.aligned(exterior, lease);
            int phase = exterior == null ? CarrierServiceBay.Phase.HOLDING.ordinal() : ship.bay.phase(exterior, lease).ordinal();
            return new Bay(lease.identity().drone(), lease.slot(), aligned, phase);
        }).toList();
        return new CarrierViewPayload(menu, id, owner, ship.destroyed, ship.mode.ordinal(), ship.stop.ordinal(),
            op == null ? CarrierCommandPayload.NONE : op.generation(), op == null ? ship.exterior.dimension() : op.dimension(),
            op == null ? 0 : op.chunkX(), op == null ? 0 : op.chunkZ(), op == null ? 0 : op.minY(), op == null ? 0 : op.maxY(),
            ship.progress == null || op != ship.progress.operation() ? 0 : ship.progress.cursor(), op == null ? 0 : op.volume(),
            ship.energy, ship.weaponEnergy, ship.bay.snapshot().size(), (int) Math.max(0, ship.previewUntil - player.level().getServer().getTickCount()),
            owner ? List.copyOf(ship.guests) : List.of(), ship.bay.snapshot().stream().map(l -> l.identity().drone()).toList(),
            ship.destination != null && !ship.navigationPaused, ship.previewMode.ordinal(),
            new Navigation(exterior == null ? ship.exterior : CarrierAnchor.at(exterior), Optional.ofNullable(ship.destination),
                ship.combatRemaining, bays, ship.bayStock.gun(), ship.bayStock.missiles(),
                new Storage(cargoPage, CarrierPolicy.CARGO_PAGES, ship.cargo.usedSlots(), CarrierPolicy.STORAGE_SLOTS, ship.cargo.itemCount()),
                Effects.of(exterior, ship), ship.navigationPaused, ship.navigationWaitTicks, ship.commandRevision));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
