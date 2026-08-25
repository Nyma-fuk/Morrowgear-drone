package jp.morrowgear.drone;

final class FormationCatchUpPolicy {
	static final double ENTER_DISTANCE = 7.0;
	static final double EXIT_DISTANCE = 3.5;

	private FormationCatchUpPolicy() {
	}

	static boolean update(boolean active, boolean eligible, double slotDistance) {
		if (!eligible) return false;
		if (active) return slotDistance > EXIT_DISTANCE;
		return slotDistance >= ENTER_DISTANCE;
	}
}
