package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class OperationLocalizationTest {
    @Test void languageKeysAreUniqueRatherThanSilentlyOverwritten() throws Exception {
        for (String language : new String[]{"ja_jp", "en_us"}) {
            try (var reader = new com.google.gson.stream.JsonReader(Files.newBufferedReader(Path.of(
                "src/main/resources/assets/morrowgear_drone/lang/" + language + ".json")))) {
                var names = new java.util.HashSet<String>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    assertTrue(names.add(name), language + ": duplicate " + name);
                    reader.skipValue();
                }
                reader.endObject();
            }
        }
    }

    @Test void everyReportedFactHasLocalizedSourceCountAndMeaning() throws Exception {
        for (String language : new String[]{"ja_jp", "en_us"}) {
            var messages = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/morrowgear_drone/lang/" + language + ".json"))).getAsJsonObject();
            for (var kind : OperationEvent.Kind.values()) {
                assertTrue(messages.has(kind.messageKey()), language + ": " + kind);
                String text = messages.get(kind.messageKey()).getAsString();
                assertEquals(2, text.split("%s", -1).length - 1, kind.messageKey());
                assertFalse(text.formatted("WING-02", 8).contains("%"));
            }
        }
    }
}
