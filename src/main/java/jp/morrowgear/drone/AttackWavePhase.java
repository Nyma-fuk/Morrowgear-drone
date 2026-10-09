package jp.morrowgear.drone;

/** Coordinator-owned phases for one committed attack wave. */
public enum AttackWavePhase {
    WAVE_PLAN,
    WAVE_INGRESS,
    WEAPON_COMMIT,
    WAVE_EXECUTE,
    LASER_COOL_ORBIT,
    WAVE_RECOVERY,
    WAVE_ASSESS,
    COMPLETE
}
