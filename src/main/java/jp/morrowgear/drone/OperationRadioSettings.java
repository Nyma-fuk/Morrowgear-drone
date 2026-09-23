package jp.morrowgear.drone;

public record OperationRadioSettings(VoiceMode voiceMode, float volume, boolean subtitles) {
    public static final OperationRadioSettings DEFAULT = new OperationRadioSettings(VoiceMode.IMPORTANT, 0.7f, true);
    public OperationRadioSettings {
        if (voiceMode == null) voiceMode = VoiceMode.IMPORTANT;
        volume = Float.isFinite(volume) ? Math.clamp(volume, 0.0f, 1.0f) : 0.7f;
    }
    public boolean audible(OperationEvent event) {
        return volume > 0 && (voiceMode == VoiceMode.STANDARD
                || voiceMode == VoiceMode.IMPORTANT && event.kind().important());
    }
    public enum VoiceMode { OFF, IMPORTANT, STANDARD }
}
