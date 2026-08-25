package jp.morrowgear.drone;

final class RecoveryPolicy {
	private RecoveryPolicy() {
	}

	static int nextLevel(int current) {
		return Math.min(3, Math.max(0, current) + 1);
	}

	static boolean stableProgress(double travelled) {
		return travelled >= 1.2;
	}

	static int escapeDuration(int level, boolean fluidDanger) {
		if (fluidDanger) return 45;
		return switch (level) {
			case 3 -> 52;
			case 2 -> 38;
			default -> 28;
		};
	}
}
