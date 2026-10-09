package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.List;

/** Resource observations, not a substitute for the server's Dock release decision. */
public final class DroneResourceUiPolicy {
	public enum Tone { NOMINAL, CAUTION, BLOCKED }
	public record Metric(String code, String label, int current, int maximum, String value, Tone tone) {
		public int percent() {
			return maximum <= 0 ? 0 : Math.clamp(Math.round(current * 100.0f / maximum), 0, 100);
		}
	}
	public record Snapshot(List<Metric> metrics, String readiness, Tone tone) {}

	private DroneResourceUiPolicy() {}

	public static Snapshot assess(boolean security, SecurityLoadout loadout, int flightPercent,
		int laserPercent, int gunRounds, int gunCapacity, int missiles, int missileCapacity,
		int laserHeat, int structurePercent, int subsystemPercent, boolean serviceReturn) {
		SecurityLoadout selected = loadout == null ? SecurityLoadout.AUTO : loadout;
		List<Metric> metrics = new ArrayList<>();
		metrics.add(percent("FLT", "飛行電力", flightPercent,
			flightPercent < DroneServicePolicy.NORMAL_SORTIE_POWER));
		if (security) {
			metrics.add(percent("LZR", "レーザー電力", laserPercent,
				laserPercent < DroneServicePolicy.NORMAL_SORTIE_POWER));
			if (selected == SecurityLoadout.AUTO || selected == SecurityLoadout.AUTOCANNON) {
				int minimum = (int)Math.ceil(CombatPolicy.GUN_CAPACITY * 0.6);
				metrics.add(amount("GUN", "機関砲弾", gunRounds, Math.max(1, gunCapacity), minimum));
			}
			if (selected == SecurityLoadout.AUTO || selected == SecurityLoadout.MISSILE)
				metrics.add(amount("MSL", "ミサイル", missiles, Math.max(1, missileCapacity), 2));
			if (selected == SecurityLoadout.AUTO || selected == SecurityLoadout.LASER)
				metrics.add(heat(laserHeat));
		}
		metrics.add(percent("STR", "機体構造", structurePercent,
			structurePercent < Math.round(DroneServicePolicy.MINIMUM_SORTIE_HEALTH_RATIO * 100)));
		metrics.add(percent("SYS", "機体システム", subsystemPercent, subsystemPercent < 45));

		List<String> warnings = new ArrayList<>();
		for (Metric metric : metrics) if (metric.tone() == Tone.BLOCKED) warnings.add(metric.label());
		if (security && selected == SecurityLoadout.UNARMED) warnings.add("非武装");
		String readiness;
		Tone tone;
		// AUTO can use another weapon, and a depleted Dock can release a partially
		// supplied aircraft. Resource values alone cannot determine sortie eligibility.
		if (!warnings.isEmpty()) {
			readiness = (serviceReturn ? "補給中 / 要確認: " : "資源注意: ") + String.join("・", warnings);
			tone = Tone.CAUTION;
		} else if (serviceReturn) {
			readiness = "補給中 / 復帰条件を確認中";
			tone = Tone.CAUTION;
		} else {
			readiness = "資源正常";
			tone = Tone.NOMINAL;
		}
		return new Snapshot(List.copyOf(metrics), readiness, tone);
	}

	private static Metric percent(String code, String label, int value, boolean blocked) {
		int safe = Math.clamp(value, 0, 100);
		return new Metric(code, label, safe, 100, safe + "%",
			blocked ? Tone.BLOCKED : safe < 35 ? Tone.CAUTION : Tone.NOMINAL);
	}

	private static Metric amount(String code, String label, int value, int capacity, int minimum) {
		int safe = Math.clamp(value, 0, capacity);
		return new Metric(code, label, safe, capacity, safe + "/" + capacity,
			safe < minimum ? Tone.BLOCKED : safe * 3 < capacity ? Tone.CAUTION : Tone.NOMINAL);
	}

	private static Metric heat(int value) {
		int safe = Math.clamp(value, 0, 1000);
		return new Metric("HEAT", "レーザー熱", safe, 1000, Math.round(safe / 10.0f) + "%",
			safe >= CombatPolicy.LASER_SWITCH_HEAT ? Tone.BLOCKED
				: safe >= 700 ? Tone.CAUTION : Tone.NOMINAL);
	}
}
