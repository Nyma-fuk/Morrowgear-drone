package jp.morrowgear.drone.client;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** UUID ownership survives entity reloads; commands see only currently resolved IDs. */
final class ControllerSelection extends AbstractSet<Integer> {
    private final Set<UUID> identities = new LinkedHashSet<>();
    private final Map<Integer, UUID> received = new LinkedHashMap<>();

    void receive(Map<Integer, UUID> entities) {
        received.clear();
        received.putAll(entities);
    }

    int unavailableCount() {
        return (int) identities.stream().filter(id -> !received.containsValue(id)).count();
    }

    Set<UUID> identities() { return Set.copyOf(identities); }

    @Override public boolean add(Integer id) {
        UUID identity = received.get(id);
        return identity != null && identities.add(identity);
    }

    @Override public boolean contains(Object id) { return identities.contains(received.get(id)); }
    @Override public boolean remove(Object id) { return identities.remove(received.get(id)); }
    @Override public void clear() { identities.clear(); }
    @Override public int size() {
        return (int) received.values().stream().filter(identities::contains).count();
    }

    @Override public Iterator<Integer> iterator() {
        Iterator<Integer> snapshot = received.entrySet().stream()
            .filter(entry -> identities.contains(entry.getValue())).map(Map.Entry::getKey).toList().iterator();
        return new Iterator<>() {
            private Integer current;
            public boolean hasNext() { return snapshot.hasNext(); }
            public Integer next() { return current = snapshot.next(); }
            public void remove() {
                if (current == null) throw new IllegalStateException();
                ControllerSelection.this.remove(current);
                current = null;
            }
        };
    }
}
