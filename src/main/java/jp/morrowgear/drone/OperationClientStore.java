package jp.morrowgear.drone;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Pure client store: snapshot history and the ephemeral presentation queue are separate. */
public final class OperationClientStore {
    private final OperationRadioQueue queue = new OperationRadioQueue();
    private UUID session;
    private UUID owner;
    private OperationHistory history;
    private OperationEvent subtitle;
    private long subtitleUntil;

    public void snapshot(UUID session, UUID owner, List<OperationEvent> events) {
        disconnect();
        this.session = session;
        this.owner = owner;
        history = new OperationHistory(owner);
        history.replace(events);
        queue.connect(owner);
    }

    public void live(UUID session, UUID owner, long serverTick, List<OperationEvent> events, long now) {
        if (!matches(session, owner)) return;
        for (OperationEvent event : events) {
            if (history.append(event) && !event.expired(serverTick)) {
                queue.offer(event, now, (serverTick - event.serverTick()) * 50L);
            }
        }
    }

    public Optional<OperationRadioQueue.Playback> authorized(UUID session, UUID id, OperationEvent event,
            long now, OperationRadioSettings settings) {
        if (this.session == null || !this.session.equals(session)) return Optional.empty();
        var playback = queue.authorized(id, event, now, settings);
        playback.ifPresent(value -> {
            subtitle = value.subtitle() ? value.event() : null;
            subtitleUntil = now + 3_000;
        });
        return playback;
    }

    public Optional<OperationEvent> request(long now, OperationRadioSettings settings) { return queue.request(now, settings); }
    public List<OperationEvent> history() { return history == null ? List.of() : history.newestFirst(); }
    public Optional<OperationEvent> subtitle(long now) { return now < subtitleUntil ? Optional.ofNullable(subtitle) : Optional.empty(); }
    public void selection(Predicate<OperationEvent> predicate) { queue.selection(predicate); }
    public void voiceFinished(UUID id) { queue.finished(id); }
    public int queuedCount() { return queue.size(); }
    public UUID session() { return session; }
    public boolean matches(UUID session, UUID owner) {
        return this.session != null && this.session.equals(session) && this.owner.equals(owner);
    }
    public void disconnect() {
        queue.clear(); history = null; session = null; owner = null; subtitle = null; subtitleUntil = 0;
    }
}
