package blue.language.processor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Writes deterministic locality observations when the focused evidence task
 * supplies an output directory. Ordinary unit-test execution remains free of
 * filesystem side effects.
 */
final class SemanticLocalityEvidenceWriter {

    static final String OUTPUT_DIRECTORY_PROPERTY =
            "blue.semantic.locality.evidence.dir";

    private static final ObjectMapper JSON = new ObjectMapper();

    private SemanticLocalityEvidenceWriter() {
    }

    static void write(String fileName, Map<String, Object> evidence) {
        String directory = System.getProperty(OUTPUT_DIRECTORY_PROPERTY);
        if (directory == null || directory.trim().isEmpty()) {
            return;
        }
        Path output = Paths.get(directory).resolve(fileName);
        try {
            Files.createDirectories(output.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(
                    output.toFile(), evidence);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to write semantic locality evidence: " + output,
                    failure);
        }
    }
}
