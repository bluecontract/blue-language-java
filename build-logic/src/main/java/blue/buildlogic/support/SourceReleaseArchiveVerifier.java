package blue.buildlogic.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.GradleException;

/** Exact-entry, debris, path, timestamp, and identity checks for a source-release ZIP. */
public final class SourceReleaseArchiveVerifier {

    public static final String SCHEMA = "blue-source-release-verification/1.0";
    private static final int BUFFER_SIZE = 8192;

    private SourceReleaseArchiveVerifier() {}

    public static Result verify(
            Path archive, Collection<String> expectedFileEntries, String rootPrefix) {
        String prefix = normalizePrefix(rootPrefix);
        Set<String> expected = new TreeSet<>();
        for (String entry : expectedFileEntries) {
            String normalized = normalizeEntry(entry);
            if (!normalized.startsWith(prefix + "/")) {
                throw new GradleException(
                        "Expected source-release entry is outside root prefix: " + normalized);
            }
            if (!expected.add(normalized)) {
                throw new GradleException("Duplicate expected source-release entry: " + normalized);
            }
        }
        Set<String> actual = new TreeSet<>();
        Set<String> allNames = new HashSet<>();
        Set<Long> timestamps = new TreeSet<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> values = zip.entries();
            while (values.hasMoreElements()) {
                ZipEntry entry = values.nextElement();
                String name = normalizeEntry(entry.getName());
                if (!allNames.add(name)) {
                    violations.add("duplicate-entry:" + name);
                    continue;
                }
                if (!name.startsWith(prefix + "/")) {
                    violations.add("entry-outside-root-prefix:" + name);
                }
                if (isNonPortable(name)) {
                    violations.add("non-portable-entry:" + name);
                }
                if (isDebris(name)) {
                    violations.add("forbidden-debris:" + name);
                }
                timestamps.add(entry.getTime());
                if (entry.isDirectory()) {
                    continue;
                }
                actual.add(name);
                Map<String, Object> item = new TreeMap<>();
                item.put("crc32", entry.getCrc());
                item.put("identity", DeterministicHashing.sha256(read(zip, entry)));
                item.put("path", name);
                item.put("size", entry.getSize());
                entries.add(item);
            }
        } catch (IOException exception) {
            throw new GradleException("Cannot inspect source-release archive: " + archive, exception);
        }
        if (timestamps.size() > 1) {
            violations.add("non-normalized-entry-timestamps");
        }
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        for (String name : missing) {
            violations.add("missing-entry:" + name);
        }
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);
        for (String name : unexpected) {
            violations.add("unexpected-entry:" + name);
        }
        entries.sort(java.util.Comparator.comparing(item -> String.valueOf(item.get("path"))));
        Collections.sort(violations);
        return new Result(
                archive,
                prefix,
                expected.size(),
                actual.size(),
                timestamps,
                entries,
                violations);
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String normalizePrefix(String value) {
        String prefix = normalizeEntry(value);
        if (prefix.isEmpty() || prefix.contains("/")) {
            throw new GradleException("Source-release root prefix must be one path segment");
        }
        return prefix;
    }

    private static String normalizeEntry(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private static boolean isNonPortable(String name) {
        return name.isEmpty()
                || name.startsWith("/")
                || name.contains("\\")
                || name.equals("..")
                || name.startsWith("../")
                || name.contains("/../")
                || name.contains("//");
    }

    private static boolean isDebris(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String[] segments = lower.split("/");
        for (String segment : segments) {
            if (segment.equals(".git")
                    || segment.equals(".gradle")
                    || segment.equals("build")
                    || segment.equals("node_modules")
                    || segment.equals("__pycache__")
                    || segment.equals("__macosx")
                    || segment.equals(".ds_store")
                    || segment.startsWith("._")) {
                return true;
            }
        }
        return lower.endsWith(".jfr")
                || lower.endsWith(".hprof")
                || lower.endsWith(".heapdump")
                || lower.endsWith(".db")
                || lower.endsWith(".sqlite")
                || lower.endsWith(".sqlite3")
                || lower.endsWith(".pyc")
                || lower.endsWith(".pyo")
                || lower.endsWith(".zip")
                || lower.endsWith(".tar")
                || lower.endsWith(".tar.gz")
                || lower.endsWith(".tgz");
    }

    /** Immutable deterministic verification result. */
    public static final class Result {
        private final Path archive;
        private final String rootPrefix;
        private final int expectedEntryCount;
        private final int actualEntryCount;
        private final Set<Long> timestamps;
        private final List<Map<String, Object>> entries;
        private final List<String> violations;

        private Result(
                Path archive,
                String rootPrefix,
                int expectedEntryCount,
                int actualEntryCount,
                Set<Long> timestamps,
                List<Map<String, Object>> entries,
                List<String> violations) {
            this.archive = archive;
            this.rootPrefix = rootPrefix;
            this.expectedEntryCount = expectedEntryCount;
            this.actualEntryCount = actualEntryCount;
            this.timestamps = Collections.unmodifiableSet(new TreeSet<>(timestamps));
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
        }

        public boolean isValid() {
            return violations.isEmpty();
        }

        public List<String> getViolations() {
            return violations;
        }

        public String toJson() {
            Map<String, Object> report = new TreeMap<>();
            report.put("actualFileEntryCount", actualEntryCount);
            report.put("archiveIdentity", DeterministicHashing.sha256(archive));
            report.put("archiveName", archive.getFileName().toString());
            report.put("entries", entries);
            report.put("expectedFileEntryCount", expectedEntryCount);
            report.put("normalizedTimestamps", new ArrayList<>(timestamps));
            report.put("rootPrefix", rootPrefix);
            report.put("schema", SCHEMA);
            report.put("valid", isValid());
            report.put("violations", violations);
            return DeterministicJson.write(report);
        }
    }
}
