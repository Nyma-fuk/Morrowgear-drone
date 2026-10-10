package jp.morrowgear.drone.carrier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * World-authoritative identity ledger for detachable carrier expansions.
 *
 * <p>A module has exactly one canonical physical representation. Callers use a revisioned token before
 * moving that representation between an item, a placed block, a carrier hardpoint, or a lift mission.
 * Observing the same identity at another physical witness quarantines the identity instead of guessing
 * which copy is real.</p>
 */
public final class CarrierExpansionLedger extends SavedData {
    private static final Codec<CarrierExpansionPolicy.Type> TYPE_CODEC = Codec.STRING.xmap(
        CarrierExpansionPolicy.Type::byName, CarrierExpansionPolicy.Type::name);
    private static final Codec<CarrierExpansionPolicy.Location> LOCATION_CODEC = Codec.STRING.xmap(
        CarrierExpansionPolicy.Location::valueOf, CarrierExpansionPolicy.Location::name);
    private static final Codec<ConflictReason> CONFLICT_CODEC = Codec.STRING.xmap(
        value -> ConflictReason.valueOf(value.toUpperCase(java.util.Locale.ROOT)), ConflictReason::name);
    private static final Codec<CarrierExpansionPolicy.Module> MODULE_CODEC = RecordCodecBuilder.create(i -> i.group(
        CarrierPolicy.UUID_CODEC.fieldOf("id").forGetter(CarrierExpansionPolicy.Module::id),
        CarrierPolicy.UUID_CODEC.fieldOf("owner").forGetter(CarrierExpansionPolicy.Module::owner),
        TYPE_CODEC.fieldOf("type").forGetter(CarrierExpansionPolicy.Module::type),
        LOCATION_CODEC.fieldOf("location").forGetter(CarrierExpansionPolicy.Module::location),
        Codec.STRING.optionalFieldOf("dimension", "").forGetter(CarrierExpansionPolicy.Module::dimension),
        Codec.LONG.optionalFieldOf("position", Long.MIN_VALUE).forGetter(CarrierExpansionPolicy.Module::position),
        CarrierPolicy.UUID_CODEC.optionalFieldOf("carrier").forGetter(module -> Optional.ofNullable(module.carrier())),
        Codec.INT.optionalFieldOf("hardpoint", -1).forGetter(CarrierExpansionPolicy.Module::hardpoint),
        CarrierPolicy.UUID_CODEC.optionalFieldOf("lift_mission").forGetter(module -> Optional.ofNullable(module.liftMission()))
    ).apply(i, (id, owner, type, location, dimension, position, carrier, hardpoint, liftMission) ->
        new CarrierExpansionPolicy.Module(id, owner, type, location, dimension, position,
            carrier.orElse(null), hardpoint, liftMission.orElse(null))));

    public record Entry(CarrierExpansionPolicy.Module module, long revision, String witness, long tick) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            MODULE_CODEC.fieldOf("module").forGetter(Entry::module),
            Codec.LONG.fieldOf("revision").forGetter(Entry::revision),
            Codec.STRING.fieldOf("witness").forGetter(Entry::witness),
            Codec.LONG.optionalFieldOf("tick", 0L).forGetter(Entry::tick)
        ).apply(i, Entry::new));

        public Entry {
            Objects.requireNonNull(module, "module");
            witness = normalizedWitness(witness, module);
        }
    }

    public record Contender(CarrierExpansionPolicy.Module module, String witness, ConflictReason reason, long tick) {
        static final Codec<Contender> CODEC = RecordCodecBuilder.create(i -> i.group(
            MODULE_CODEC.fieldOf("module").forGetter(Contender::module),
            Codec.STRING.fieldOf("witness").forGetter(Contender::witness),
            CONFLICT_CODEC.fieldOf("reason").forGetter(Contender::reason),
            Codec.LONG.optionalFieldOf("tick", 0L).forGetter(Contender::tick)
        ).apply(i, Contender::new));

        public Contender {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(reason, "reason");
            witness = normalizedWitness(witness, module);
        }
    }

    public record Quarantine(List<Contender> contenders) {
        static final Codec<Quarantine> CODEC = Contender.CODEC.listOf(1, 64)
            .xmap(Quarantine::new, Quarantine::contenders);

        public Quarantine {
            contenders = List.copyOf(contenders);
            if (contenders.isEmpty()) throw new IllegalArgumentException("Quarantine needs a contender");
        }
    }

    public record Token(UUID id, long revision, CarrierExpansionPolicy.Module snapshot, String witness) {}

    public enum ConflictReason {
        DUPLICATE_WITNESS,
        IDENTITY_MISMATCH,
        LOCATION_OCCUPIED,
        PERSISTED_INVARIANT
    }

    public enum Status {
        CREATED,
        REGISTERED,
        APPLIED,
        IDEMPOTENT,
        NOT_FOUND,
        STALE,
        IDENTITY_MISMATCH,
        LOCATION_OCCUPIED,
        QUARANTINED,
        CONTENDER_NOT_FOUND,
        REPLACEMENT_ID_OCCUPIED
    }

    public record Result(Status status, CarrierExpansionPolicy.Module module, long revision, String detail) {
        public boolean success() {
            return status == Status.CREATED || status == Status.REGISTERED || status == Status.APPLIED
                || status == Status.IDEMPOTENT;
        }
    }

    public static final Codec<CarrierExpansionLedger> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.unboundedMap(CarrierPolicy.UUID_CODEC, Entry.CODEC).fieldOf("entries").forGetter(data -> data.entries),
        Codec.unboundedMap(CarrierPolicy.UUID_CODEC, Quarantine.CODEC).optionalFieldOf("quarantines", Map.of())
            .forGetter(data -> data.quarantines),
        Codec.LONG.optionalFieldOf("revision", 0L).forGetter(data -> data.revision)
    ).apply(i, CarrierExpansionLedger::new));

    private static final SavedDataType<CarrierExpansionLedger> SAVED_DATA_TYPE = new SavedDataType<>(
        CarrierModule.id("carrier_expansion_ledger"), CarrierExpansionLedger::new, CODEC,
        DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Entry> entries;
    private final Map<UUID, Quarantine> quarantines;
    private long revision;

    public CarrierExpansionLedger() {
        this(Map.of(), Map.of(), 0L);
    }

    private CarrierExpansionLedger(Map<UUID, Entry> entries, Map<UUID, Quarantine> quarantines, long revision) {
        this.entries = new LinkedHashMap<>();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getKey().equals(entry.getValue().module().id())) this.entries.put(entry.getKey(), entry.getValue());
        });
        this.quarantines = new HashMap<>();
        quarantines.forEach((id, quarantine) -> {
            if (this.entries.containsKey(id)) this.quarantines.put(id, quarantine);
        });
        this.revision = Math.max(revision, this.entries.values().stream().mapToLong(Entry::revision).max().orElse(0L));
        if (quarantinePersistedCollisions()) setDirty();
    }

    public static CarrierExpansionLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SAVED_DATA_TYPE);
    }

    public synchronized CarrierExpansionPolicy.Module create(UUID owner, CarrierExpansionPolicy.Type type) {
        return create(owner, type, "generated-item", 0L).module();
    }

    public synchronized Result create(UUID owner, CarrierExpansionPolicy.Type type, String witness, long tick) {
        CarrierExpansionPolicy.Module module;
        do {
            module = new CarrierExpansionPolicy.Module(UUID.randomUUID(), owner, type,
                CarrierExpansionPolicy.Location.ITEM, "", Long.MIN_VALUE, null, -1, null);
        } while (entries.containsKey(module.id()));
        Entry entry = new Entry(module, nextRevision(), witness, tick);
        entries.put(module.id(), entry);
        setDirty();
        return result(Status.CREATED, entry, "IDENTITY_ALLOCATED");
    }

    /** Registers a representation discovered in the world. A second witness is treated as a clone. */
    public synchronized Result claimPhysical(CarrierExpansionPolicy.Module observed, String witness, long tick) {
        Objects.requireNonNull(observed, "observed");
        String normalized = normalizedWitness(witness, observed);
        Entry current = entries.get(observed.id());
        if (current == null) {
            UUID occupant = occupantAt(observed, observed.id());
            Entry entry = new Entry(observed, nextRevision(), normalized, tick);
            entries.put(observed.id(), entry);
            if (occupant != null) {
                quarantine(observed.id(), new Contender(observed, normalized, ConflictReason.LOCATION_OCCUPIED, tick));
                return result(Status.QUARANTINED, entry, "LOCATION_OCCUPIED_BY:" + occupant);
            }
            setDirty();
            return result(Status.REGISTERED, entry, "PHYSICAL_REPRESENTATION_REGISTERED");
        }
        if (quarantines.containsKey(observed.id())) return result(Status.QUARANTINED, current, "IDENTITY_QUARANTINED");
        if (!sameIdentity(current.module(), observed)) {
            quarantine(observed.id(), new Contender(observed, normalized, ConflictReason.IDENTITY_MISMATCH, tick));
            return result(Status.QUARANTINED, current, "IMMUTABLE_IDENTITY_MISMATCH");
        }
        if (current.module().equals(observed) && current.witness().equals(normalized))
            return result(Status.IDEMPOTENT, current, "ALREADY_REGISTERED");
        quarantine(observed.id(), new Contender(observed, normalized, ConflictReason.DUPLICATE_WITNESS, tick));
        return result(Status.QUARANTINED, current, "DUPLICATE_PHYSICAL_REPRESENTATION");
    }

    public synchronized Result claimItem(CarrierExpansionItemData.Identity identity, String witness, long tick) {
        return claimPhysical(new CarrierExpansionPolicy.Module(identity.id(), identity.owner(), identity.type(),
            CarrierExpansionPolicy.Location.ITEM, "", Long.MIN_VALUE, null, -1, null), witness, tick);
    }

    public synchronized Result claimPlaced(CarrierExpansionItemData.Identity identity, String dimension,
                                           long position, String witness, long tick) {
        return claimPhysical(new CarrierExpansionPolicy.Module(identity.id(), identity.owner(), identity.type(),
            CarrierExpansionPolicy.Location.PLACED, dimension, position, null, -1, null), witness, tick);
    }

    public synchronized Result claimInstalled(CarrierExpansionItemData.Identity identity, UUID carrier,
                                              int hardpoint, String witness, long tick) {
        return claimPhysical(new CarrierExpansionPolicy.Module(identity.id(), identity.owner(), identity.type(),
            CarrierExpansionPolicy.Location.CARRIER, "", Long.MIN_VALUE, carrier, hardpoint, null), witness, tick);
    }

    public synchronized Result claimLifted(CarrierExpansionItemData.Identity identity, UUID mission,
                                           String dimension, long position, String witness, long tick) {
        return claimPhysical(new CarrierExpansionPolicy.Module(identity.id(), identity.owner(), identity.type(),
            CarrierExpansionPolicy.Location.LIFTED, dimension, position, null, -1, mission), witness, tick);
    }

    public synchronized Optional<Token> begin(UUID id) {
        Entry entry = entries.get(id);
        if (entry == null || quarantines.containsKey(id)) return Optional.empty();
        return Optional.of(new Token(id, entry.revision(), entry.module(), entry.witness()));
    }

    /** Atomic compare-and-swap transition. No physical mutation should happen before this succeeds. */
    public synchronized Result commit(Token token, CarrierExpansionPolicy.Module replacement,
                                      String witness, long tick) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(replacement, "replacement");
        Entry current = entries.get(token.id());
        if (current == null) return new Result(Status.NOT_FOUND, null, revision, "UNKNOWN_IDENTITY");
        if (quarantines.containsKey(token.id())) return result(Status.QUARANTINED, current, "IDENTITY_QUARANTINED");
        if (current.revision() != token.revision() || !current.module().equals(token.snapshot())
            || !current.witness().equals(token.witness())) return result(Status.STALE, current, "REVISION_CHANGED");
        if (!sameIdentity(current.module(), replacement))
            return result(Status.IDENTITY_MISMATCH, current, "IMMUTABLE_IDENTITY_MISMATCH");
        String normalized = normalizedWitness(witness, replacement);
        if (current.module().equals(replacement) && current.witness().equals(normalized))
            return result(Status.IDEMPOTENT, current, "ALREADY_COMMITTED");
        UUID occupant = occupantAt(replacement, replacement.id());
        if (occupant != null) return result(Status.LOCATION_OCCUPIED, current, "LOCATION_OCCUPIED_BY:" + occupant);
        Entry next = new Entry(replacement, nextRevision(), normalized, tick);
        entries.put(replacement.id(), next);
        setDirty();
        return result(Status.APPLIED, next, "COMMITTED");
    }

    /** Compatibility wrapper for early call sites. New integrations should use begin/commit. */
    public synchronized boolean transition(UUID id, CarrierExpansionPolicy.Location expected,
                                           CarrierExpansionPolicy.Module replacement) {
        Entry current = entries.get(id);
        if (current == null || current.module().location() != expected) return false;
        return commit(new Token(id, current.revision(), current.module(), current.witness()), replacement,
            defaultWitness(replacement), current.tick()).success();
    }

    public synchronized Result resolveKeepCanonical(UUID id, String resolver, long tick) {
        Entry canonical = entries.get(id);
        if (canonical == null) return new Result(Status.NOT_FOUND, null, revision, "UNKNOWN_IDENTITY");
        if (!quarantines.containsKey(id)) return result(Status.IDEMPOTENT, canonical, "NOT_QUARANTINED");
        UUID occupant = occupantAt(canonical.module(), id);
        if (occupant != null) return result(Status.LOCATION_OCCUPIED, canonical, "LOCATION_OCCUPIED_BY:" + occupant);
        quarantines.remove(id);
        Entry recovered = new Entry(canonical.module(), nextRevision(), canonical.witness(), tick);
        entries.put(id, recovered);
        setDirty();
        return result(Status.APPLIED, recovered, "CANONICAL_CONFIRMED_BY:" + safe(resolver));
    }

    public synchronized Result resolveUseContender(UUID id, String contenderWitness, String resolver, long tick) {
        Entry canonical = entries.get(id);
        Contender contender = contender(id, contenderWitness);
        if (canonical == null) return new Result(Status.NOT_FOUND, null, revision, "UNKNOWN_IDENTITY");
        if (contender == null) return result(Status.CONTENDER_NOT_FOUND, canonical, "UNKNOWN_CONTENDER");
        if (!sameIdentity(canonical.module(), contender.module()))
            return result(Status.IDENTITY_MISMATCH, canonical, "CONTENDER_IDENTITY_MISMATCH");
        UUID occupant = occupantAt(contender.module(), id);
        if (occupant != null) return result(Status.LOCATION_OCCUPIED, canonical, "LOCATION_OCCUPIED_BY:" + occupant);
        Entry recovered = new Entry(contender.module(), nextRevision(), contender.witness(), tick);
        entries.put(id, recovered);
        quarantines.remove(id);
        setDirty();
        return result(Status.APPLIED, recovered, "CONTENDER_ACCEPTED_BY:" + safe(resolver));
    }

    /** Keeps the canonical module and gives one confirmed duplicate a new permanent identity. */
    public synchronized Result resolveRekeyContender(UUID id, String contenderWitness, UUID replacementId,
                                                     String resolver, long tick) {
        Entry canonical = entries.get(id);
        Contender contender = contender(id, contenderWitness);
        if (canonical == null) return new Result(Status.NOT_FOUND, null, revision, "UNKNOWN_IDENTITY");
        if (contender == null) return result(Status.CONTENDER_NOT_FOUND, canonical, "UNKNOWN_CONTENDER");
        if (entries.containsKey(replacementId))
            return result(Status.REPLACEMENT_ID_OCCUPIED, canonical, "REPLACEMENT_ID_EXISTS");
        if (!sameIdentity(canonical.module(), contender.module()))
            return result(Status.IDENTITY_MISMATCH, canonical, "CONTENDER_IDENTITY_MISMATCH");
        CarrierExpansionPolicy.Module rekeyed = rekey(contender.module(), replacementId);
        UUID occupant = occupantAt(rekeyed, replacementId);
        if (occupant != null) return result(Status.LOCATION_OCCUPIED, canonical, "LOCATION_OCCUPIED_BY:" + occupant);
        Entry recoveredDuplicate = new Entry(rekeyed, nextRevision(), contender.witness(), tick);
        entries.put(replacementId, recoveredDuplicate);
        removeContender(id, contenderWitness);
        setDirty();
        return result(Status.APPLIED, recoveredDuplicate, "CONTENDER_REKEYED_BY:" + safe(resolver));
    }

    /** Relocates a quarantined canonical identity to a verified free representation and clears conflicts. */
    public synchronized Result recoverTo(UUID id, CarrierExpansionPolicy.Module replacement, String witness,
                                         String resolver, long tick) {
        Entry canonical = entries.get(id);
        if (canonical == null) return new Result(Status.NOT_FOUND, null, revision, "UNKNOWN_IDENTITY");
        if (!quarantines.containsKey(id)) return result(Status.STALE, canonical, "IDENTITY_NOT_QUARANTINED");
        if (!sameIdentity(canonical.module(), replacement))
            return result(Status.IDENTITY_MISMATCH, canonical, "IMMUTABLE_IDENTITY_MISMATCH");
        UUID occupant = occupantAt(replacement, id);
        if (occupant != null) return result(Status.LOCATION_OCCUPIED, canonical, "LOCATION_OCCUPIED_BY:" + occupant);
        Entry recovered = new Entry(replacement, nextRevision(), witness, tick);
        entries.put(id, recovered);
        quarantines.remove(id);
        setDirty();
        return result(Status.APPLIED, recovered, "RELOCATED_BY:" + safe(resolver));
    }

    public synchronized CarrierExpansionPolicy.Module get(UUID id) {
        Entry entry = entries.get(id);
        return entry == null ? null : entry.module();
    }

    public synchronized Optional<Entry> entry(UUID id) { return Optional.ofNullable(entries.get(id)); }
    public synchronized Optional<Quarantine> quarantine(UUID id) { return Optional.ofNullable(quarantines.get(id)); }
    public synchronized boolean isQuarantined(UUID id) { return quarantines.containsKey(id); }
    public synchronized Map<UUID, Entry> entries() { return Map.copyOf(entries); }
    public synchronized Map<UUID, Quarantine> quarantines() { return Map.copyOf(quarantines); }

    public synchronized boolean hardpointAvailable(UUID carrier, int hardpoint, UUID except) {
        CarrierExpansionPolicy.checkedHardpoint(hardpoint);
        return entries.values().stream().noneMatch(entry -> entry.module().location() == CarrierExpansionPolicy.Location.CARRIER
            && carrier.equals(entry.module().carrier()) && entry.module().hardpoint() == hardpoint
            && !entry.module().id().equals(except));
    }

    public synchronized boolean positionAvailable(String dimension, long position, UUID except) {
        return entries.values().stream().noneMatch(entry -> entry.module().location() == CarrierExpansionPolicy.Location.PLACED
            && dimension.equals(entry.module().dimension()) && entry.module().position() == position
            && !entry.module().id().equals(except));
    }

    /** Releases an exact placed-location witness after the loaded world proves its block is gone. */
    public synchronized Result releaseMissingPlaced(String dimension,long position,String resolver,long tick) {
        Entry occupant=entries.values().stream().filter(entry ->
            entry.module().location()==CarrierExpansionPolicy.Location.PLACED
                && dimension.equals(entry.module().dimension())&&entry.module().position()==position)
            .findFirst().orElse(null);
        if(occupant==null)return new Result(Status.NOT_FOUND,null,revision,"LOCATION_ALREADY_FREE");
        if(quarantines.containsKey(occupant.module().id()))
            return result(Status.QUARANTINED,occupant,"OCCUPANT_IDENTITY_QUARANTINED");
        return commit(new Token(occupant.module().id(),occupant.revision(),occupant.module(),occupant.witness()),
            occupant.module().destroy(),"destroyed/"+safe(resolver),tick);
    }

    private boolean quarantinePersistedCollisions() {
        boolean[] changed = { false };
        Map<String, UUID> occupied = new HashMap<>();
        entries.values().stream().sorted(Comparator.comparingLong(Entry::revision)
            .thenComparing(entry -> entry.module().id())).forEach(entry -> {
                String key = exclusiveLocation(entry.module());
                if (key == null) return;
                UUID previous = occupied.putIfAbsent(key, entry.module().id());
                if (previous != null && !previous.equals(entry.module().id())) {
                    if (quarantines.putIfAbsent(entry.module().id(), new Quarantine(List.of(new Contender(
                        entry.module(), entry.witness(), ConflictReason.PERSISTED_INVARIANT, entry.tick())))) == null)
                        changed[0] = true;
                }
            });
        return changed[0];
    }

    private void quarantine(UUID id, Contender contender) {
        Quarantine current = quarantines.get(id);
        List<Contender> contenders = current == null ? new ArrayList<>() : new ArrayList<>(current.contenders());
        boolean duplicate = contenders.stream().anyMatch(value -> value.witness().equals(contender.witness())
            && value.module().equals(contender.module()));
        if (!duplicate) contenders.add(contender);
        quarantines.put(id, new Quarantine(contenders));
        setDirty();
    }

    private Contender contender(UUID id, String witness) {
        Quarantine quarantine = quarantines.get(id);
        if (quarantine == null) return null;
        String normalized = witness == null ? "" : witness.trim();
        return quarantine.contenders().stream().filter(value -> value.witness().equals(normalized)).findFirst().orElse(null);
    }

    private void removeContender(UUID id, String witness) {
        Quarantine quarantine = quarantines.get(id);
        if (quarantine == null) return;
        List<Contender> remaining = quarantine.contenders().stream()
            .filter(value -> !value.witness().equals(witness)).toList();
        if (remaining.isEmpty()) quarantines.remove(id);
        else quarantines.put(id, new Quarantine(remaining));
    }

    private UUID occupantAt(CarrierExpansionPolicy.Module target, UUID except) {
        String key = exclusiveLocation(target);
        if (key == null) return null;
        return entries.values().stream().map(Entry::module)
            .filter(module -> !module.id().equals(except) && key.equals(exclusiveLocation(module)))
            .map(CarrierExpansionPolicy.Module::id).findFirst().orElse(null);
    }

    private static String exclusiveLocation(CarrierExpansionPolicy.Module module) {
        return switch (module.location()) {
            case ITEM, DESTROYED -> null;
            case PLACED -> "placed/" + module.dimension() + "/" + module.position();
            case CARRIER -> "carrier/" + module.carrier() + "/" + module.hardpoint();
            case LIFTED -> "lift/" + module.liftMission();
        };
    }

    private static CarrierExpansionPolicy.Module rekey(CarrierExpansionPolicy.Module source, UUID id) {
        return new CarrierExpansionPolicy.Module(id, source.owner(), source.type(), source.location(),
            source.dimension(), source.position(), source.carrier(), source.hardpoint(), source.liftMission());
    }

    private static boolean sameIdentity(CarrierExpansionPolicy.Module first, CarrierExpansionPolicy.Module second) {
        return first.id().equals(second.id()) && first.owner().equals(second.owner()) && first.type() == second.type();
    }

    private static String defaultWitness(CarrierExpansionPolicy.Module module) {
        return switch (module.location()) {
            case ITEM -> "item/" + module.id();
            case DESTROYED -> "destroyed/" + module.id();
            case PLACED -> "block/" + module.dimension() + "/" + module.position();
            case CARRIER -> "carrier/" + module.carrier() + "/" + module.hardpoint();
            case LIFTED -> "lift/" + module.liftMission();
        };
    }

    private static String normalizedWitness(String witness, CarrierExpansionPolicy.Module module) {
        String value = witness == null ? "" : witness.trim();
        return value.isEmpty() ? defaultWitness(module) : value;
    }

    private long nextRevision() { return ++revision; }
    private Result result(Status status, Entry entry, String detail) {
        return new Result(status, entry == null ? null : entry.module(), entry == null ? revision : entry.revision(), detail);
    }
    private static String safe(String value) { return value == null || value.isBlank() ? "SYSTEM" : value.trim(); }
}
