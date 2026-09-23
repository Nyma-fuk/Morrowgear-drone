package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CarrierAcceptanceEvidenceScopeTest {
    @Test void automatedVerifierNeverClaimsHumanVisualOrUnimplementedFaultCases() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/CarrierAcceptanceVerification.java"));
        for (String unsupported : new String[] {
            "M04", "M09", "A03", "A07", "N03", "N04", "N05", "N06", "N07", "N08",
            "U01", "U02", "U03", "U04"
        }) assertFalse(source.contains("pass(\"" + unsupported), unsupported + " requires separate evidence");
        assertTrue(source.contains("server-menu evidence, not human UI evidence"));
        assertTrue(source.contains("visual/human interaction attribution requires parent observation"));
    }
}
