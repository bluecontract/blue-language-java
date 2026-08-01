package blue.buildlogic.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds the deterministic release-evidence document shared by release tasks. */
public final class ReleaseEvidence {

    public static final String SCHEMA = "blue-release-evidence/1.0";

    private ReleaseEvidence() {}

    public static String create(
            Path root,
            Collection<Path> sourceFiles,
            String sourceCommit,
            String sourceDateEpoch,
            Map<String, String> metadata) {
        SourceSnapshot snapshot = DeterministicHashing.snapshot(root, sourceFiles);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (SourceSnapshot.Entry entry : snapshot.getEntries()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("identity", entry.getIdentity());
            item.put("path", entry.getPath());
            entries.add(item);
        }

        Map<String, Object> evidence = new TreeMap<>();
        evidence.put("metadata", new TreeMap<>(metadata));
        evidence.put("schema", SCHEMA);
        evidence.put("sourceCommit", sourceCommit.trim());
        evidence.put("sourceDateEpoch", SourceDateEpoch.normalize(sourceDateEpoch));
        evidence.put("sourceEntries", entries);
        evidence.put("sourceFileCount", entries.size());
        evidence.put("sourceInputIdentity", snapshot.getIdentity());
        return DeterministicJson.write(evidence);
    }
}
