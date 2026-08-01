package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReleaseEvidenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldGenerateTheSameEvidenceForDifferentPathAndMetadataOrders() throws Exception {
        // given
        Path first = Files.writeString(
                temporaryDirectory.resolve("a.txt"), "alpha", StandardCharsets.UTF_8);
        Path second = Files.writeString(
                temporaryDirectory.resolve("b.txt"), "beta", StandardCharsets.UTF_8);
        Map<String, String> forwardMetadata = new LinkedHashMap<>();
        forwardMetadata.put("module", "core");
        forwardMetadata.put("version", "1.0");
        Map<String, String> reverseMetadata = new LinkedHashMap<>();
        reverseMetadata.put("version", "1.0");
        reverseMetadata.put("module", "core");

        // when
        String forward = ReleaseEvidence.create(
                temporaryDirectory,
                Arrays.asList(first, second),
                "commit",
                "00042",
                forwardMetadata);
        String reverse = ReleaseEvidence.create(
                temporaryDirectory,
                Arrays.asList(second, first),
                "commit",
                "42",
                reverseMetadata);

        // then
        assertEquals(forward, reverse);
        assertTrue(forward.contains("\"sourceDateEpoch\":\"42\""));
        assertTrue(forward.indexOf("a.txt") < forward.indexOf("b.txt"));
    }
}
