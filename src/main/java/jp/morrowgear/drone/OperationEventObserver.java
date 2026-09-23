package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Bounded owner-local observer; never issues a drone command. All time values are server ticks. */
public final class OperationEventObserver {
    public static final long GROUP_TICKS = 20;
    public static final long STALL_TICKS = 600;
    private static final int PENDING_LIMIT = 128;
    private final UUID owner;
    private final Map<UUID, Track> tracks = new HashMap<>();
    private final Map<Group, Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Issued> issued = new LinkedHashMap<>();
    private long generation;

    public OperationEventObserver(UUID owner) { this.owner = owner; }

    public List<OperationEvent> observe(List<OperationObservation> samples, long tick, long wallMillis) {
        if (samples.size() > OperationEvent.FLEET_LIMIT) throw new IllegalArgumentException("Fleet limit exceeded");
        Set<UUID> present = new HashSet<>();
        for (OperationObservation now : samples) {
            if (!owner.equals(now.owner()) || !present.add(now.unit())) continue;
            Track before = tracks.get(now.unit());
            if (before == null) {
                tracks.put(now.unit(), new Track(now, ++generation, tick));
                continue; // Joining, loading and dimension arrivals are baselines, never historical starts.
            }
            boolean changed = !now.sameMission(before.sample);
            Track track = changed ? new Track(now, ++generation, tick) : before;
            if (changed) track.seen.clear();
            if (!Objects.equals(now.target(), before.sample.target())) {
                track.seen.removeIf(OperationEvent.Kind::requiresTarget);
            }
            if (!now.states().contains(OperationEvent.Kind.CARGO_STALLED)
                    || !before.sample.states().contains(OperationEvent.Kind.CARGO_STALLED)
                    || now.cargoItems() != before.sample.cargoItems()) track.stallSince = tick;
            EnumSet<OperationEvent.Kind> candidates = EnumSet.noneOf(OperationEvent.Kind.class);
            for (OperationEvent.Kind kind : now.states()) {
                if (kind == OperationEvent.Kind.CARGO_STALLED) {
                    if (tick - track.stallSince >= STALL_TICKS && !track.seen.contains(kind)) candidates.add(kind);
                } else if (changed || !before.sample.states().contains(kind)
                        || kind.requiresTarget() && !Objects.equals(now.target(), before.sample.target())) {
                    candidates.add(kind);
                }
            }
            if (!changed && before.sample.rejoining() && now.supports(OperationEvent.Kind.MISSION_RESUMED)) {
                candidates.add(OperationEvent.Kind.MISSION_RESUMED);
            }
            if (before.sample.servicing() && before.sample.docked()
                    && now.supports(OperationEvent.Kind.SERVICE_COMPLETE)) {
                candidates.add(OperationEvent.Kind.SERVICE_COMPLETE);
            }
            if (!now.states().contains(OperationEvent.Kind.CARGO_STALLED)) track.seen.remove(OperationEvent.Kind.CARGO_STALLED);
            track.sample = now;
            tracks.put(now.unit(), track);
            for (OperationEvent.Kind kind : candidates) {
                if (!now.supports(kind) || kind.oncePerMission() && track.seen.contains(kind)) continue;
                track.seen.add(kind);
                add(now, track.generation, kind, tick, wallMillis);
            }
        }
        tracks.keySet().retainAll(present);
        issued.values().removeIf(value -> value.event.expired(tick));
        List<OperationEvent> result = new ArrayList<>();
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending group = iterator.next().getValue();
            if (tick - group.tick < GROUP_TICKS) continue;
            OperationObservation source = group.source;
            UUID context = UUID.nameUUIDFromBytes((source.missionKey() + "\u0000"
                    + (group.kind.requiresTarget() ? source.target() : "")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            OperationEvent event = new OperationEvent(UUID.randomUUID(), owner, context, source.unit(), group.tick, group.wallMillis,
                    group.kind, source.wing(), source.dimension(), source.sourceName(), source.voiceNumber(),
                    group.proofs.size(), source.x(), source.y(), source.z());
            issued.put(event.id(), new Issued(event, List.copyOf(group.proofs.values())));
            while (issued.size() > OperationEvent.HISTORY_LIMIT) issued.remove(issued.keySet().iterator().next());
            result.add(event); // Historical truth is retained even when already too stale to speak.
            iterator.remove();
        }
        return List.copyOf(result);
    }

    private void add(OperationObservation sample, long version, OperationEvent.Kind kind, long tick, long wallMillis) {
        String scope = sample.wing().isBlank() ? sample.unit().toString() : sample.wing();
        Group key = new Group(scope, sample.dimension(), sample.missionKey(), kind,
                kind.requiresTarget() ? sample.target() : null);
        if (!pending.containsKey(key) && pending.size() >= PENDING_LIMIT) {
            Group lowest = pending.keySet().stream().min(java.util.Comparator.comparingInt(
                    candidate -> candidate.kind.priority(false))).orElseThrow();
            if (lowest.kind.priority(false) >= kind.priority(false)) return;
            pending.remove(lowest);
        }
        Pending group = pending.computeIfAbsent(key, ignored -> new Pending(sample, kind, tick, wallMillis));
        group.proofs.put(sample.unit(), new Proof(sample.unit(), version, sample,
                kind.requiresTarget() ? sample.target() : null));
    }

    /** Re-read the actual entities immediately before authorization; never trust a client owner/target. */
    public Optional<OperationEvent> authorize(UUID eventId, List<OperationObservation> current, long tick) {
        Issued value = issued.get(eventId);
        if (value == null || value.event.expired(tick)) return Optional.empty();
        Map<UUID, OperationObservation> fresh = new HashMap<>();
        current.stream().limit(OperationEvent.FLEET_LIMIT).filter(sample -> owner.equals(sample.owner()))
                .forEach(sample -> fresh.put(sample.unit(), sample));
        int valid = 0;
        for (Proof proof : value.proofs) {
            OperationObservation now = fresh.get(proof.unit);
            Track track = tracks.get(proof.unit);
            if (now != null && track != null && track.generation == proof.generation
                    && now.sameMission(proof.sample) && now.supports(value.event.kind())
                    && (proof.target == null || proof.target.equals(now.target()) && now.targetAlive())) valid++;
        }
        return valid == 0 ? Optional.empty() : Optional.of(value.event.withCount(valid));
    }

    public int trackedCount() { return tracks.size(); }
    public int pendingCount() { return pending.size(); }

    private static final class Track {
        OperationObservation sample;
        final long generation;
        final EnumSet<OperationEvent.Kind> seen = EnumSet.noneOf(OperationEvent.Kind.class);
        long stallSince;
        Track(OperationObservation sample, long generation, long tick) {
            this.sample = sample;
            this.generation = generation;
            this.stallSince = tick;
            seen.addAll(sample.states());
            seen.remove(OperationEvent.Kind.CARGO_STALLED);
        }
    }
    private record Group(String scope, String dimension, String mission, OperationEvent.Kind kind, UUID target) {}
    private record Proof(UUID unit, long generation, OperationObservation sample, UUID target) {}
    private record Issued(OperationEvent event, List<Proof> proofs) {}
    private static final class Pending {
        final OperationObservation source;
        final OperationEvent.Kind kind;
        final long tick;
        final long wallMillis;
        final Map<UUID, Proof> proofs = new LinkedHashMap<>();
        Pending(OperationObservation source, OperationEvent.Kind kind, long tick, long wallMillis) {
            this.source = source;
            this.kind = kind;
            this.tick = tick;
            this.wallMillis = wallMillis;
        }
    }
}
