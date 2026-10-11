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

	static CargoState start(boolean carryingCargo) {
		return carryingCargo ? CargoState.TO_TARGET : CargoState.TO_SOURCE;
	}

	static CargoState onNetworkHold(boolean retainingCargo) {
		return retainingCargo ? CargoState.WAIT_TARGET : CargoState.UNASSIGNED;
	}
}
