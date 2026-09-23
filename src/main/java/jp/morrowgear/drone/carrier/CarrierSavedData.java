package jp.morrowgear.drone.carrier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class CarrierSavedData extends SavedData {
    public static final Codec<CarrierSavedData> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.unboundedMap(CarrierPolicy.UUID_CODEC, CarrierShip.CODEC).fieldOf("ships").forGetter(d -> d.ships),
        Codec.unboundedMap(CarrierPolicy.UUID_CODEC, Visit.CODEC).fieldOf("visits").forGetter(d -> d.visits),
        Codec.unboundedMap(Codec.STRING, Codec.BOOL).optionalFieldOf("player_builds", Map.of()).forGetter(d -> d.playerBuilds),
        Codec.unboundedMap(CarrierPolicy.UUID_CODEC, Visit.CODEC).optionalFieldOf("assignments", Map.of()).forGetter(d -> d.assignments)
    ).apply(i, CarrierSavedData::new));
    private static final SavedDataType<CarrierSavedData> TYPE = new SavedDataType<>(
        CarrierModule.id("carrier_cabins"), CarrierSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private final Map<UUID, CarrierShip> ships;
    private final Map<UUID, Visit> visits;
    private final Map<String, Boolean> playerBuilds;
    private final Map<UUID, Visit> assignments;

    public CarrierSavedData() { this(Map.of(), Map.of(), Map.of(), Map.of()); }
    private CarrierSavedData(Map<UUID, CarrierShip> ships, Map<UUID, Visit> visits, Map<String, Boolean> playerBuilds,
                             Map<UUID, Visit> assignments) {
        this.ships = new HashMap<>(ships);
        this.visits = new HashMap<>(visits);
        this.playerBuilds = new HashMap<>(playerBuilds);
        this.assignments = new HashMap<>(assignments);
        visits.forEach(this.assignments::putIfAbsent);
        if (ships.values().stream().map(s -> s.cabin).distinct().count() != ships.size())
            throw new IllegalArgumentException("Duplicate carrier cabin assignment");
        this.ships.values().forEach(ship -> ship.dirty = this::setDirty);
    }

    public static CarrierSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }
    public CarrierShip ship(UUID id) { return ships.get(id); }
    public Map<UUID, CarrierShip> ships() { return Map.copyOf(ships); }
    public Visit visit(UUID player) { return visits.get(player); }
    public Visit assignment(UUID player) { return assignments.get(player); }
    public void markPlayerBuilt(String dimension, long position) {
        if (playerBuilds.putIfAbsent(dimension + "/" + position, true) == null) setDirty();
    }
    public boolean playerBuilt(String dimension, long position) { return playerBuilds.containsKey(dimension + "/" + position); }
    public void visit(UUID player, Visit visit) { visits.put(player, visit); assignments.put(player, visit); setDirty(); }
    public void leave(UUID player) { if (visits.remove(player) != null) setDirty(); }
    /** Roll back only an unspawned, unused allocation; never recycle an inhabited or supplied cabin. */
    boolean abandonDeployment(UUID id) {
        CarrierShip ship = ships.get(id);
        if (ship == null || ship.cabinReady || ship.destroyed || !ship.cargo.isEmpty() || !ship.supplies.isEmpty()
            || ship.energy != 0 || ship.weaponEnergy != 0 || ship.progress != null || ship.pending != null
            || ship.bayStock.gun() != 0 || ship.bayStock.missiles() != 0 || !ship.guests.isEmpty()
            || !ship.bay.snapshot().isEmpty() || assignments.values().stream().anyMatch(visit -> visit.ship().equals(id))) return false;
        ships.remove(id);
        setDirty();
        return true;
    }
    public CarrierShip assign(UUID id, UUID owner, CarrierAnchor exterior) {
        CarrierShip previous = ships.get(id);
        if (previous != null) {
            if (!previous.owner.equals(owner)) throw new IllegalStateException("Carrier owner mismatch");
            return previous;
        }
        // Never recycle a destroyed ship's interior or contents.
        int cell = ships.values().stream().mapToInt(s -> s.cabin).max().orElse(-1) + 1;
        if (cell >= CarrierPolicy.MAX_CABINS) throw new IllegalStateException("Carrier cabin capacity reached");
        CarrierShip ship = new CarrierShip(owner, cell, exterior);
        ship.dirty = this::setDirty;
        ships.put(id, ship);
        setDirty();
        return ship;
    }

    public record Visit(UUID ship, CarrierAnchor returnTo, boolean recovery) {
        public Visit(UUID ship, CarrierAnchor returnTo) { this(ship, returnTo, false); }
        public static final Codec<Visit> CODEC = RecordCodecBuilder.create(i -> i.group(
            CarrierPolicy.UUID_CODEC.fieldOf("ship").forGetter(Visit::ship),
            CarrierAnchor.CODEC.fieldOf("return").forGetter(Visit::returnTo),
            Codec.BOOL.optionalFieldOf("recovery", false).forGetter(Visit::recovery)
        ).apply(i, Visit::new));
    }
}
