package blue.buildlogic.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.gradle.api.GradleException;

/** Creates the aggregate release receipt binding artifacts and verification evidence by hash. */
public final class AggregateReleaseReceipt {

    public static final String SCHEMA = "blue-aggregate-release-receipt/1.0";
    public static final String VERIFICATION_SCHEMA =
            "blue-aggregate-release-receipt-verification/1.0";

    private static final String KEY_API = "api";
    private static final String KEY_ARTIFACTS = "artifacts";
    private static final String KEY_CURRENT_RECEIPT_IDENTITY = "currentReceiptIdentity";
    private static final String KEY_FILE_COUNT = "fileCount";
    private static final String KEY_FILES = "files";
    private static final String KEY_FIXTURES = "fixtures";
    private static final String KEY_IDENTITY = "identity";
    private static final String KEY_METADATA = "metadata";
    private static final String KEY_PATH = "path";
    private static final String KEY_RECORDED_RECEIPT_IDENTITY = "recordedReceiptIdentity";
    private static final String KEY_SCHEMA = "schema";
    private static final String KEY_SIZE = "size";
    private static final String KEY_SOURCE_COMMIT = "sourceCommit";
    private static final String KEY_SOURCE_DATE_EPOCH = "sourceDateEpoch";
    private static final String KEY_TESTS = "tests";
    private static final String KEY_VERIFIED = "verified";
    private static final String KEY_VERIFICATION = "verification";

    private AggregateReleaseReceipt() {}

    public static String create(
            Path root,
            Collection<Path> artifacts,
            Collection<Path> testEvidence,
            Collection<Path> fixtureEvidence,
            Collection<Path> apiEvidence,
            Collection<Path> verificationEvidence,
            String sourceCommit,
            String sourceDateEpoch,
            Map<String, String> metadata) {
        Map<String, Object> receipt = new TreeMap<>();
        receipt.put(KEY_API, group(root, apiEvidence));
        receipt.put(KEY_ARTIFACTS, group(root, artifacts));
        receipt.put(KEY_FIXTURES, group(root, fixtureEvidence));
        receipt.put(KEY_METADATA, new TreeMap<>(metadata));
        receipt.put(KEY_SCHEMA, SCHEMA);
        receipt.put(KEY_SOURCE_COMMIT, oneLine(sourceCommit, "source commit"));
        receipt.put(KEY_SOURCE_DATE_EPOCH, SourceDateEpoch.normalize(sourceDateEpoch));
        receipt.put(KEY_TESTS, group(root, testEvidence));
        receipt.put(KEY_VERIFICATION, group(root, verificationEvidence));
        return DeterministicJson.write(receipt);
    }

    /** Produces a deterministic receipt proving exact equality with the recomputed receipt. */
    public static Verification verify(Path recordedReceipt, String expectedReceipt) {
        byte[] recorded;
        try {
            recorded = Files.readAllBytes(recordedReceipt);
        } catch (IOException exception) {
            throw new GradleException("Cannot read aggregate release receipt: " + recordedReceipt, exception);
        }
        byte[] expected = expectedReceipt.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean matches = java.util.Arrays.equals(recorded, expected);
        Map<String, Object> report = new TreeMap<>();
        report.put(KEY_CURRENT_RECEIPT_IDENTITY, DeterministicHashing.sha256(expected));
        report.put(KEY_RECORDED_RECEIPT_IDENTITY, DeterministicHashing.sha256(recordedReceipt));
        report.put(KEY_SCHEMA, VERIFICATION_SCHEMA);
        report.put(KEY_VERIFIED, matches);
        return new Verification(matches, DeterministicJson.write(report));
    }

    private static Map<String, Object> group(Path root, Collection<Path> files) {
        SourceSnapshot snapshot = DeterministicHashing.snapshot(root, files);
        Path normalizedRoot = realPath(root);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (SourceSnapshot.Entry entry : snapshot.getEntries()) {
            Map<String, Object> item = new TreeMap<>();
            item.put(KEY_IDENTITY, entry.getIdentity());
            item.put(KEY_PATH, entry.getPath());
            item.put(KEY_SIZE, size(normalizedRoot.resolve(entry.getPath())));
            entries.add(item);
        }
        Map<String, Object> group = new TreeMap<>();
        group.put(KEY_FILE_COUNT, entries.size());
        group.put(KEY_FILES, entries);
        group.put(KEY_IDENTITY, snapshot.getIdentity());
        return group;
    }

    private static Path realPath(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException exception) {
            throw new GradleException("Cannot resolve aggregate receipt root: " + root, exception);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            throw new GradleException("Cannot read aggregate receipt input size: " + file, exception);
        }
    }

    private static String oneLine(String value, String description) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new GradleException("Aggregate release " + description + " must be one non-empty line");
        }
        return normalized;
    }

    /** Result of comparing a checked receipt with current release inputs. */
    public static final class Verification {

        private final boolean verified;
        private final String report;

        private Verification(boolean verified, String report) {
            this.verified = verified;
            this.report = report;
        }

        public boolean isVerified() {
            return verified;
        }

        public String getReport() {
            return report;
        }
    }
}
