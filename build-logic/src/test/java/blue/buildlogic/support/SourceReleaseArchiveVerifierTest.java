package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceReleaseArchiveVerifierTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldAcceptAnExactPortableNormalizedSourceArchive() throws Exception {
        // given
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("blue-1.0/.cz.toml", "version = \"1.0\"\n");
        entries.put("blue-1.0/build-logic/build.gradle", "plugins {}\n");
        entries.put("blue-1.0/blue-language-core/src/main/java/Core.java", "class Core {}\n");
        Path archive = zip("source.zip", entries);

        // when
        SourceReleaseArchiveVerifier.Result result = SourceReleaseArchiveVerifier.verify(
                archive, entries.keySet(), "blue-1.0");

        // then
        assertTrue(result.isValid());
        assertTrue(result.toJson().contains("\"valid\":true"));
        assertTrue(result.toJson().contains("\"expectedFileEntryCount\":3"));
    }

    @Test
    void shouldRejectMissingUnexpectedAndDebrisEntriesDeterministically() throws Exception {
        // given
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("blue-1.0/.cz.toml", "version = \"1.0\"\n");
        entries.put("blue-1.0/build/local.txt", "debris\n");
        Path archive = zip("invalid.zip", entries);

        // when
        SourceReleaseArchiveVerifier.Result result = SourceReleaseArchiveVerifier.verify(
                archive,
                Arrays.asList("blue-1.0/.cz.toml", "blue-1.0/README.md"),
                "blue-1.0");

        // then
        assertFalse(result.isValid());
        String report = result.toJson();
        assertTrue(report.contains("forbidden-debris:blue-1.0/build/local.txt"));
        assertTrue(report.contains("missing-entry:blue-1.0/README.md"));
        assertTrue(report.contains("unexpected-entry:blue-1.0/build/local.txt"));
    }

    private Path zip(String name, Map<String, String> entries) throws IOException {
        Path output = temporaryDirectory.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                ZipEntry value = new ZipEntry(entry.getKey());
                value.setTime(0L);
                zip.putNextEntry(value);
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output;
    }
}
