package jp.morrowgear.drone;

public final class MissionCommandPolicy {
	private MissionCommandPolicy() {
	}

	public static boolean preemptsCargoRoute(String action) {
		if (action == null) return false;
		return action.startsWith("work:") || action.startsWith("move:")
			|| action.startsWith("track:") || action.startsWith("follow:") || action.startsWith("patrol:")
			|| action.equals("standby") || action.equals("return")
			|| action.equals("dock") || action.equals("orbit");
	}
}
