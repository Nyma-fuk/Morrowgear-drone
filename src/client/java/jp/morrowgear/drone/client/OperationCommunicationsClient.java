package jp.morrowgear.drone.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import jp.morrowgear.drone.OperationClientStore;
import jp.morrowgear.drone.OperationEvent;
import jp.morrowgear.drone.OperationRadioQueue;
import jp.morrowgear.drone.network.OperationCuePayload;
import jp.morrowgear.drone.network.OperationCueRequestPayload;
import jp.morrowgear.drone.network.OperationEventsPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** No UI, sounds, accessibility narrator, cloud TTS or assets are installed by this helper. */
public final class OperationCommunicationsClient {
    public static final long MAX_VOICE_MS = 3_000;
    private static final OperationClientStore STORE = new OperationClientStore();
    private static VoiceSink sink;
    private static UUID speaking;
    private static long stopAt;
    private static boolean registered;
    private OperationCommunicationsClient() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(OperationEventsPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().player == null || !context.client().player.getUUID().equals(payload.owner())) return;
                    if (payload.snapshot()) {
                        stopVoice();
                        STORE.snapshot(payload.session(), payload.owner(), payload.events());
                    } else STORE.live(payload.session(), payload.owner(), payload.serverTick(), payload.events(), now());
                }));
        ClientPlayNetworking.registerGlobalReceiver(OperationCuePayload.TYPE, (payload, context) ->
                context.client().execute(() -> STORE.authorized(payload.session(), payload.eventId(), payload.event(), now(),
                        OperationCommunicationsConfig.current()).ifPresent(OperationCommunicationsClient::present)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { stopVoice(); STORE.disconnect(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = now();
            var settings = OperationCommunicationsConfig.current();
            if (speaking != null && (now >= stopAt || settings.voiceMode() == jp.morrowgear.drone.OperationRadioSettings.VoiceMode.OFF
                    || settings.volume() <= 0)) stopVoice();
            if (client.player == null || STORE.session() == null || !ClientPlayNetworking.canSend(OperationCueRequestPayload.TYPE)) return;
            STORE.request(now, settings).ifPresent(event ->
                    ClientPlayNetworking.send(new OperationCueRequestPayload(STORE.session(), event.id())));
        });
    }

    public static void setVoiceSink(VoiceSink next) { stopVoice(); sink = next; }
    public static void setSelectedContext(Predicate<OperationEvent> selected) { STORE.selection(selected); }
    public static List<OperationEvent> history() { return STORE.history(); }
    public static Optional<OperationEvent> subtitle() {
        return OperationCommunicationsConfig.current().subtitles() ? STORE.subtitle(now()) : Optional.empty();
    }
    public static int queuedCount() { return STORE.queuedCount(); }
    /** Call on the client thread when the native voice finishes before the three-second budget. */
    public static void voiceFinished(UUID eventId) {
        if (eventId.equals(speaking)) speaking = null;
        STORE.voiceFinished(eventId);
    }

    private static void present(OperationRadioQueue.Playback playback) {
        if (playback.interrupt()) stopVoice();
        OperationEvent event = playback.event();
        if (!playback.voice() || sink == null) { STORE.voiceFinished(event.id()); return; }
        try {
            speaking = event.id();
            stopAt = now() + MAX_VOICE_MS;
            if (!sink.play(new VoiceCue(event.id(), event.kind().cueId(), event.voiceNumber(), event.count(),
                    OperationCommunicationsConfig.current().volume()))) voiceFinished(event.id());
        } catch (RuntimeException unavailable) {
            stopVoice(); // An unavailable native voice must leave history and subtitles functional.
        }
    }

    private static void stopVoice() {
        UUID previous = speaking;
        speaking = null;
        if (previous != null) {
            if (sink != null) try { sink.stop(); } catch (RuntimeException ignored) { }
            STORE.voiceFinished(previous);
        }
    }

    private static long now() { return System.nanoTime() / 1_000_000; }

    public interface VoiceSink {
        /** Short owner-channel status, <= 3 seconds. False means unsupported/missing voice. */
        boolean play(VoiceCue cue);
        void stop();
    }
    public record VoiceCue(UUID eventId, String cueId, String voiceNumber, int count, float volume) {}
}
