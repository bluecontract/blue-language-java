package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReproducibleArchiveInspectorTest {

    private static final long NORMALIZED_TIMESTAMP = 315532800000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldAcceptCanonicalEntryOrderAndOneTimestamp() throws Exception {
        // given
        Path archive = archive(
                "canonical.zip",
                Arrays.asList("META-INF/", "META-INF/MANIFEST.MF", "a.txt", "b.txt"),
                Arrays.asList(NORMALIZED_TIMESTAMP, NORMALIZED_TIMESTAMP,
                        NORMALIZED_TIMESTAMP, NORMALIZED_TIMESTAMP));

        // when / then
        assertDoesNotThrow(() -> ReproducibleArchiveInspector.verify(archive));
    }

    @Test
    void shouldRejectFilesystemDependentEntryOrder() throws Exception {
        // given
        Path archive = archive(
                "unordered.zip",
                Arrays.asList("b.txt", "a.txt"),
                Arrays.asList(NORMALIZED_TIMESTAMP, NORMALIZED_TIMESTAMP));

        // when / then
        assertThrows(GradleException.class, () -> ReproducibleArchiveInspector.verify(archive));
    }

    @Test
    void shouldRejectNonNormalizedEntryTimestamps() throws Exception {
        // given
        Path archive = archive(
                "timestamps.zip",
                Arrays.asList("a.txt", "b.txt"),
                Arrays.asList(NORMALIZED_TIMESTAMP, NORMALIZED_TIMESTAMP + 2000L));

        // when / then
        assertThrows(GradleException.class, () -> ReproducibleArchiveInspector.verify(archive));
    }

    private Path archive(String name, List<String> entries, List<Long> timestamps) throws Exception {
        Path archive = temporaryDirectory.resolve(name);
        try (OutputStream output = Files.newOutputStream(archive);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int index = 0; index < entries.size(); index++) {
                ZipEntry entry = new ZipEntry(entries.get(index));
                entry.setTime(timestamps.get(index));
                zip.putNextEntry(entry);
                if (!entry.isDirectory()) {
                    zip.write(entries.get(index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return archive;
    }
}
