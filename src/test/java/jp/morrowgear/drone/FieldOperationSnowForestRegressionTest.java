package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class FieldOperationSnowForestRegressionTest {
    @Test void engineerWithoutScoutCompletesTheSharedAreaInsteadOfOnlyTheLocalSample() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
        String method = source.substring(source.indexOf("private void tickFieldEngineer("),
            source.indexOf("private void surveyLocalField("));
        assertTrue(method.contains("!linkedScout && !operation.snapshot(level.getGameTime()).scanComplete()"));
        assertTrue(method.contains("scanFieldTargets(level, operation, 128)"));
		assertTrue(method.contains("FieldMissionStatusPolicy.engineer(current.scanComplete()"));
		assertFalse(method.contains("if (linkedScout && !snapshot.scanComplete())"),
			"engineers should process targets already discovered by a linked scout");
        assertFalse(method.contains("fieldLocalSurveyed = true;\n\t\tif (fieldOperationType() == FieldOperationType.FORESTRY"),
            "a one-shot local survey must not skip the rest of a snowy forestry area");
    }
}
