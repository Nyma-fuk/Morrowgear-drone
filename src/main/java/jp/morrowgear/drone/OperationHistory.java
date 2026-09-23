package jp.morrowgear.drone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** An owner boundary and a bounded chronological history, independent of playback lifetime. */
public final class OperationHistory {
    private final UUID owner;
    private final ArrayDeque<OperationEvent> events = new ArrayDeque<>();

    public OperationHistory(UUID owner) { this.owner = owner; }

    public boolean append(OperationEvent event) {
        if (!owner.equals(event.owner()) || events.stream().anyMatch(old -> old.id().equals(event.id()))) return false;
        events.addLast(event);
        while (events.size() > OperationEvent.HISTORY_LIMIT) events.removeFirst();
        return true;
    }

    public void replace(List<OperationEvent> snapshot) {
        events.clear();
        snapshot.forEach(this::append);
    }

    public List<OperationEvent> chronological() { return List.copyOf(events); }
    public List<OperationEvent> newestFirst() {
        List<OperationEvent> result = new ArrayList<>(events);
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }
}
