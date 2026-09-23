package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Client presentation scheduling only. Milliseconds use a monotonic client clock. */
public final class OperationRadioQueue {
    public static final int LIMIT = 8;
    public static final long GAP_MS = 4_000;
    public static final long DEDUP_MS = 15_000;
    public static final long TTL_MS = 8_000;
    private final List<Entry> queue = new ArrayList<>();
    private final Map<String, Long> heard = new HashMap<>();
    private UUID owner;
    private Entry awaiting;
    private OperationEvent active;
    private long nextNormal;
    private long activeDeadline;
    private Predicate<OperationEvent> selected = event -> false;

    public void connect(UUID owner) { clear(); this.owner = owner; }
    public void clear() {
        queue.clear(); heard.clear(); awaiting = null; active = null; owner = null;
        nextNormal = 0; activeDeadline = 0;
    }
    public void selection(Predicate<OperationEvent> selected) { this.selected = selected; }

    public void offer(OperationEvent event, long now, long ageMillis) {
        if (!event.owner().equals(owner) || ageMillis < 0 || ageMillis >= TTL_MS) return;
        prune(now);
        if (suppressed(event, now) || queue.stream().anyMatch(entry -> same(entry.event, event))
                || awaiting != null && same(awaiting.event, event) || active != null && same(active, event)) return;
        if (event.kind().interrupts() && awaiting != null && !awaiting.event.kind().interrupts()) {
            queue.add(awaiting);
            awaiting = null; // A late normal authorization cannot block an urgent request.
        }
        Entry next = new Entry(event, now + TTL_MS - ageMillis);
        if (queue.size() + (awaiting == null ? 0 : 1) >= LIMIT) {
            Entry lowest = queue.stream().min(order()).orElseThrow();
            if (priority(next) <= priority(lowest)) return;
            queue.remove(lowest);
        }
        queue.add(next);
    }

    public Optional<OperationEvent> request(long now, OperationRadioSettings settings) {
        prune(now);
        if (awaiting != null) return Optional.empty();
        Entry next = queue.stream().filter(entry -> settings.subtitles() || settings.audible(entry.event))
                .filter(entry -> active == null || entry.event.kind().interrupts() && !active.kind().interrupts())
                .filter(entry -> entry.event.kind().interrupts() || now >= nextNormal)
                .max(order()).orElse(null);
        if (next == null) return Optional.empty();
        queue.remove(next);
        awaiting = next;
        return Optional.of(next.event);
    }

    /** Called only for an owner/session-matched server authorization response. */
    public Optional<Playback> authorized(UUID id, OperationEvent approved, long now, OperationRadioSettings settings) {
        prune(now);
        if (awaiting == null || !awaiting.event.id().equals(id)) return Optional.empty();
        Entry candidate = awaiting;
        awaiting = null;
        if (approved == null || !approved.owner().equals(owner) || !approved.id().equals(id)
                || candidate.deadline <= now || suppressed(approved, now)
                || !settings.subtitles() && !settings.audible(approved)) return Optional.empty();
        boolean interrupt = active != null;
        if (interrupt && (!approved.kind().interrupts() || active.kind().interrupts())) return Optional.empty();
        active = approved;
        activeDeadline = Math.min(candidate.deadline, now + GAP_MS);
        nextNormal = now + GAP_MS;
        heard.put(approved.semanticKey(), now);
        return Optional.of(new Playback(approved, settings.audible(approved), settings.subtitles(), interrupt));
    }

    public void finished(UUID id) { if (active != null && active.id().equals(id)) active = null; }
    public Optional<OperationEvent> active(long now) { prune(now); return Optional.ofNullable(active); }
    public int size() { return queue.size() + (awaiting == null ? 0 : 1); }

    private void prune(long now) {
        queue.removeIf(entry -> entry.deadline <= now || suppressed(entry.event, now));
        heard.values().removeIf(time -> now - time >= DEDUP_MS);
        if (awaiting != null && awaiting.deadline <= now) awaiting = null;
        if (active != null && activeDeadline <= now) active = null;
    }
    private boolean suppressed(OperationEvent event, long now) {
        Long previous = heard.get(event.semanticKey());
        return previous != null && now - previous < DEDUP_MS;
    }
    private static boolean same(OperationEvent a, OperationEvent b) {
        return a.id().equals(b.id()) || a.semanticKey().equals(b.semanticKey());
    }
    private int priority(Entry entry) { return entry.event.kind().priority(selected.test(entry.event)); }
    private Comparator<Entry> order() {
        return Comparator.comparingInt(this::priority).thenComparingLong(entry -> -entry.deadline);
    }
    private record Entry(OperationEvent event, long deadline) {}
    public record Playback(OperationEvent event, boolean voice, boolean subtitle, boolean interrupt) {}
}
