package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Keeps the human observation catalogue generated from the typed manifest. */
final class ProcessingMetricReferenceDocumentationTest {

    @Test
    void shouldMatchTheGeneratedProcessingObservationReference()
            throws Exception {
        // given
        Path reference = Paths.get(
                "docs", "reference", "processing-observations.md");

        // when
        String checkedIn = new String(
                Files.readAllBytes(reference), StandardCharsets.UTF_8);

        // then
        assertEquals(ProcessingMetricManifest.markdown(), checkedIn);
    }
}
