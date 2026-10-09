package jp.morrowgear.drone.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Reception evidence is not an ACK: unrelated state changes never imply acceptance. */
final class ControllerCommandWatch {
    static final long TIMEOUT_MILLIS = 5000;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    record Observation(String state, String mission) {}
    record Counts(int waiting, int updated, int unknown) {}
    private record Pending(UUID commandId, String command, Observation before, String mission, long sent, int result) {}

    boolean submit(UUID id, String command, Observation before, String mission, long now) {
		return submit(UUID.randomUUID(), id, command, before, mission, now);
	}

	boolean submit(UUID commandId, UUID id, String command, Observation before, String mission, long now) {
        Pending previous = pending.get(id);
        if (previous != null && previous.command.equals(command) && previous.result == 0
            && now - previous.sent < TIMEOUT_MILLIS) return false;
        if (pending.size() >= 512 && !pending.containsKey(id)) pending.remove(pending.keySet().iterator().next());
        pending.put(id, new Pending(commandId, command, before, mission, now, 0));
        return true;
    }

    Counts observe(Map<UUID, Observation> received, long now) {
        pending.replaceAll((id, request) -> {
            if (request.result != 0) return request;
            var authoritative = ControllerCommandResults.get(request.commandId, id);
            if (authoritative != null) {
				int result = switch (authoritative.status()) {
					case ACCEPTED, HELD, EXECUTING -> 0;
					case COMPLETED -> 1;
					case REJECTED, FAILED -> 2;
				};
				return new Pending(request.commandId, request.command, request.before, request.mission, request.sent, result);
			}
            Observation current = received.get(id);
            boolean changed = current != null && !current.equals(request.before)
                && (request.mission.isEmpty() || request.mission.equals(current.mission));
            int result = changed ? 1 : now - request.sent >= TIMEOUT_MILLIS ? 2 : 0;
            return new Pending(request.commandId, request.command, request.before, request.mission, request.sent, result);
        });
        int waiting = 0, updated = 0, unknown = 0;
        for (Pending request : pending.values()) {
            if (request.result == 0) waiting++;
            else if (request.result == 1) updated++;
            else unknown++;
        }
        return new Counts(waiting, updated, unknown);
    }
}
