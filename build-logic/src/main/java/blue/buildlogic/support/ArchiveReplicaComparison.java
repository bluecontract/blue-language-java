package blue.buildlogic.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.gradle.api.GradleException;

/** Byte-for-byte comparison of independently produced archive sets paired by file name. */
public final class ArchiveReplicaComparison {

    public static final String SCHEMA = "blue-archive-replica-comparison/1.0";

    private static final long IDENTICAL_MISMATCH_OFFSET = -1L;
    private static final long MISSING_SIZE = -1L;
    private static final String KEY_ARCHIVE_COUNT = "archiveCount";
    private static final String KEY_ARCHIVES = "archives";
    private static final String KEY_IDENTICAL = "identical";
    private static final String KEY_NAME = "name";
    private static final String KEY_REASON = "reason";
    private static final String KEY_REFERENCE_IDENTITY = "referenceIdentity";
    private static final String KEY_REFERENCE_SIZE = "referenceSize";
    private static final String KEY_REPLICA_IDENTITY = "replicaIdentity";
    private static final String KEY_REPLICA_SIZE = "replicaSize";
    private static final String KEY_SCHEMA = "schema";
    private static final String REASON_IDENTICAL = "identical";
    private static final String REASON_MISSING_REFERENCE = "missing-reference";
    private static final String REASON_MISSING_REPLICA = "missing-replica";
    private static final String REASON_MISMATCH_PREFIX = "byte-mismatch-at-";

    private ArchiveReplicaComparison() {}

    public static Result compare(Collection<Path> references, Collection<Path> replicas) {
        Map<String, Path> referenceByName = uniqueByName(references, "reference");
        Map<String, Path> replicaByName = uniqueByName(replicas, "replica");
        TreeSet<String> names = new TreeSet<>(referenceByName.keySet());
        names.addAll(replicaByName.keySet());
        List<Entry> entries = new ArrayList<>();
        for (String name : names) {
            Path reference = referenceByName.get(name);
            Path replica = replicaByName.get(name);
            entries.add(compare(name, reference, replica));
        }
        return new Result(entries);
    }

    private static Entry compare(String name, Path reference, Path replica) {
        if (reference == null) {
            return new Entry(
                    name,
                    null,
                    identity(replica),
                    MISSING_SIZE,
                    size(replica),
                    false,
                    REASON_MISSING_REFERENCE);
        }
        if (replica == null) {
            return new Entry(
                    name,
                    identity(reference),
                    null,
                    size(reference),
                    MISSING_SIZE,
                    false,
                    REASON_MISSING_REPLICA);
        }
        long mismatch;
        try {
            mismatch = Files.mismatch(reference, replica);
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot compare archive replicas '" + reference + "' and '" + replica + "'",
                    exception);
        }
        boolean identical = mismatch == IDENTICAL_MISMATCH_OFFSET;
        return new Entry(
                name,
                identity(reference),
                identity(replica),
                size(reference),
                size(replica),
                identical,
                identical ? REASON_IDENTICAL : REASON_MISMATCH_PREFIX + mismatch);
    }

    private static Map<String, Path> uniqueByName(Collection<Path> paths, String side) {
        Map<String, Path> values = new TreeMap<>();
        for (Path path : paths) {
            if (!Files.isRegularFile(path)) {
                throw new GradleException("Archive " + side + " is not a regular file: " + path);
            }
            String name = path.getFileName().toString();
            Path previous = values.put(name, path);
            if (previous != null) {
                throw new GradleException(
                        "Archive " + side + " contains duplicate file name '" + name + "': "
                                + previous + " and " + path);
            }
        }
        return values;
    }

    private static String identity(Path path) {
        return path == null ? null : DeterministicHashing.sha256(path);
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new GradleException("Cannot read archive size: " + path, exception);
        }
    }

    /** Immutable comparison result whose JSON never contains host-specific paths. */
    public static final class Result {

        private final List<Entry> entries;

        private Result(List<Entry> entries) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }

        public boolean isIdentical() {
            return entries.stream().allMatch(entry -> entry.identical);
        }

        public String toJson() {
            List<Map<String, Object>> records = new ArrayList<>();
            for (Entry entry : entries) {
                Map<String, Object> record = new TreeMap<>();
                record.put(KEY_IDENTICAL, entry.identical);
                record.put(KEY_NAME, entry.name);
                record.put(KEY_REASON, entry.reason);
                record.put(KEY_REFERENCE_IDENTITY, entry.referenceIdentity);
                record.put(KEY_REFERENCE_SIZE, entry.referenceSize);
                record.put(KEY_REPLICA_IDENTITY, entry.replicaIdentity);
                record.put(KEY_REPLICA_SIZE, entry.replicaSize);
                records.add(record);
            }
            Map<String, Object> report = new TreeMap<>();
            report.put(KEY_ARCHIVE_COUNT, entries.size());
            report.put(KEY_ARCHIVES, records);
            report.put(KEY_IDENTICAL, isIdentical());
            report.put(KEY_SCHEMA, SCHEMA);
            return DeterministicJson.write(report);
        }
    }

    private static final class Entry {

        private final String name;
        private final String referenceIdentity;
        private final String replicaIdentity;
        private final long referenceSize;
        private final long replicaSize;
        private final boolean identical;
        private final String reason;

        private Entry(
                String name,
                String referenceIdentity,
                String replicaIdentity,
                long referenceSize,
                long replicaSize,
                boolean identical,
                String reason) {
            this.name = name;
            this.referenceIdentity = referenceIdentity;
            this.replicaIdentity = replicaIdentity;
            this.referenceSize = referenceSize;
            this.replicaSize = replicaSize;
            this.identical = identical;
            this.reason = reason;
        }
    }
}
