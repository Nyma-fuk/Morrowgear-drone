package jp.morrowgear.drone.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class HighEnergyBeamPolicyTest {
    @Test void detailIsBoundedAndMonotonic() {
        var full = HighEnergyBeamPolicy.profile(RuntimeGeometryBudgetPolicy.Detail.FULL, false);
        var medium = HighEnergyBeamPolicy.profile(RuntimeGeometryBudgetPolicy.Detail.MEDIUM, false);
        var far = HighEnergyBeamPolicy.profile(RuntimeGeometryBudgetPolicy.Detail.FAR, false);
        assertTrue(full.compressionPackets() > medium.compressionPackets());
        assertTrue(medium.compressionPackets() > far.compressionPackets());
        assertEquals(0, far.filamentSegments());
        assertTrue(full.compressionPackets() <= 12);
    }

    @Test void carrierReadsAsLargerWithoutUnboundedGeometry() {
        var drone = HighEnergyBeamPolicy.profile(RuntimeGeometryBudgetPolicy.Detail.FULL, false);
        var carrier = HighEnergyBeamPolicy.profile(RuntimeGeometryBudgetPolicy.Detail.FULL, true);
        assertTrue(carrier.compressionPackets() > drone.compressionPackets());
        assertTrue(carrier.hazeScale() > drone.hazeScale());
        assertTrue(carrier.filamentSegments() <= 28);
    }

    @Test void packetProgressWrapsAndRejectsInvalidInput() {
        for (int age = 0; age < 200; age++) for (int packet = 0; packet < 6; packet++) {
            double progress = HighEnergyBeamPolicy.packetProgress(age, packet, 6, .19);
            assertTrue(progress >= 0 && progress < 1);
        }
        assertEquals(0, HighEnergyBeamPolicy.packetProgress(Float.NaN, 0, 6, .19));
        assertEquals(0, HighEnergyBeamPolicy.packetProgress(1, 7, 6, .19));
    }
}
