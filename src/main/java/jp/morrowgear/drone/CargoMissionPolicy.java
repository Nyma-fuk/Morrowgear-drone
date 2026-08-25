package jp.morrowgear.drone;

final class CargoMissionPolicy {
	private CargoMissionPolicy() {
	}

	static CargoState afterSource(boolean carryingCargo) {
		return carryingCargo ? CargoState.TO_TARGET : CargoState.WAIT_SOURCE;
	}

	static CargoState afterTarget(boolean carryingCargo) {
		return carryingCargo ? CargoState.WAIT_TARGET : CargoState.TO_SOURCE;
	}
}
