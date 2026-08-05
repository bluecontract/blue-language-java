package blue.buildlogic.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.GradleException;

/** Creates and verifies the two-invocation clean-build provenance contract. */
public final class CleanBuildEvidence {

    public static final String CLEAN_SOURCE_SCHEMA =
            "blue-language-java-clean-source-input/1.0";
    public static final String CLEAN_BUILD_SCHEMA =
            "blue-language-java-clean-build/1.0";
    public static final String VERIFICATION_SCHEMA =
            "blue-language-java-clean-build-verification/1.0";
    public static final String EVIDENCE_KIND = "successful-clean-build-marker";

    private CleanBuildEvidence() {}

    /** Captures source identity immediately after the clean task. */
    public static String createCleanSource(
            Path root,
            Collection<Path> sourceFiles,
            String sourceCommit,
            String sourceDateEpoch,
            String cleanTask,
            List<String> invocationTasks,
            List<String> excludedTasks) {
        SourceSnapshot snapshot = DeterministicHashing.snapshot(root, sourceFiles);
        return create(
                CLEAN_SOURCE_SCHEMA,
                cleanTask,
                null,
                sourceCommit,
                sourceDateEpoch,
                snapshot,
                invocationTasks,
                excludedTasks);
    }

    /** Records a successful build over an already captured clean source snapshot. */
    public static String createCleanBuild(
            Path root,
            Collection<Path> sourceFiles,
            String sourceCommit,
            String sourceDateEpoch,
            String cleanTask,
            String buildTask,
            List<String> invocationTasks,
            List<String> excludedTasks) {
        SourceSnapshot snapshot = DeterministicHashing.snapshot(root, sourceFiles);
        return create(
                CLEAN_BUILD_SCHEMA,
                cleanTask,
                buildTask,
                sourceCommit,
                sourceDateEpoch,
                snapshot,
                invocationTasks,
                excludedTasks);
    }

    /** Verifies a prior clean-build marker against the current source invocation. */
    public static Verification verify(
            Path marker,
            Path root,
            Collection<Path> sourceFiles,
            String sourceCommit,
            String sourceDateEpoch,
            String cleanTask,
            String buildTask) {
        SourceSnapshot current = DeterministicHashing.snapshot(root, sourceFiles);
        if (!Files.isRegularFile(marker)) {
            return verification("missing-clean-build-evidence", null, current);
        }
        Marker recorded;
        try {
            recorded = parse(Files.readString(marker, StandardCharsets.UTF_8));
        } catch (RuntimeException | IOException exception) {
            return verification("invalid-clean-build-evidence", null, current);
        }
        String normalizedEpoch = SourceDateEpoch.normalize(sourceDateEpoch);
        String reason = !CLEAN_BUILD_SCHEMA.equals(recorded.schema)
                ? "unexpected-clean-build-evidence-schema"
                : !cleanTask.equals(recorded.cleanTask)
                ? "unexpected-clean-task"
                : !buildTask.equals(recorded.buildTask)
                ? "unexpected-build-task"
                : !sourceCommit.trim().equals(recorded.sourceCommit)
                ? "source-commit-changed-since-clean-build"
                : !normalizedEpoch.equals(recorded.sourceDateEpoch)
                ? "source-date-epoch-changed-since-clean-build"
                : !recorded.excludedTasks.isEmpty()
                ? "clean-build-used-task-exclusions"
                : !current.getIdentity().equals(recorded.sourceInputIdentity)
                        || current.getEntries().size() != recorded.sourceFileCount
                ? "source-inputs-changed-since-clean-build"
                : "verified";
        return verification(reason, recorded, current);
    }

    /** Parses one marker produced by this class. */
    public static Marker parse(String json) {
        String schema = string(json, "schema", true);
        String cleanTask = string(json, "cleanTask", true);
        String buildTask = string(json, "buildTask", false);
        String sourceCommit = string(json, "sourceCommit", true);
        String sourceInputIdentity = string(json, "sourceInputIdentity", true);
        int sourceFileCount = integer(json, "sourceFileCount");
        String sourceDateEpoch = string(json, "sourceDateEpoch", true);
        List<String> excludedTasks = stringArray(json, "excludedTasks");
        List<String> invocationTasks = stringArray(json, "invocationTasks");
        return new Marker(
                schema,
                cleanTask,
                buildTask,
                sourceCommit,
                sourceInputIdentity,
                sourceFileCount,
                sourceDateEpoch,
                excludedTasks,
                invocationTasks);
    }

    private static String create(
            String schema,
            String cleanTask,
            String buildTask,
            String sourceCommit,
            String sourceDateEpoch,
            SourceSnapshot snapshot,
            List<String> invocationTasks,
            List<String> excludedTasks) {
        String commit = sourceCommit == null ? "" : sourceCommit.trim();
        if (!commit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new GradleException("Clean-build source commit is not a Git object identity");
        }
        Map<String, Object> evidence = new TreeMap<>();
        if (buildTask != null) {
            evidence.put("buildTask", oneLine(buildTask, "build task"));
        }
        evidence.put("cleanTask", oneLine(cleanTask, "clean task"));
        evidence.put("excludedTasks", sortedCopy(excludedTasks));
        evidence.put("invocationTasks", new ArrayList<>(invocationTasks));
        evidence.put("schema", schema);
        evidence.put("sourceCommit", commit);
        evidence.put("sourceDateEpoch", SourceDateEpoch.normalize(sourceDateEpoch));
        evidence.put("sourceFileCount", snapshot.getEntries().size());
        evidence.put("sourceInputIdentity", snapshot.getIdentity());
        return DeterministicJson.write(evidence);
    }

    private static Verification verification(
            String reason, Marker recorded, SourceSnapshot current) {
        Map<String, Object> report = new TreeMap<>();
        report.put("currentSourceFileCount", current.getEntries().size());
        report.put("currentSourceInputIdentity", current.getIdentity());
        report.put("evidenceKind", EVIDENCE_KIND);
        report.put("reason", reason);
        report.put("recordedSourceCommit", recorded == null ? null : recorded.sourceCommit);
        report.put(
                "recordedSourceDateEpoch",
                recorded == null ? null : recorded.sourceDateEpoch);
        report.put(
                "recordedSourceInputIdentity",
                recorded == null ? null : recorded.sourceInputIdentity);
        report.put("schema", VERIFICATION_SCHEMA);
        report.put("verified", "verified".equals(reason));
        return new Verification("verified".equals(reason), reason, recorded,
                DeterministicJson.write(report));
    }

    private static List<String> sortedCopy(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }

    private static String oneLine(String value, String description) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.contains("\n") || normalized.contains("\r")) {
            throw new GradleException("Clean-build " + description + " must be one line");
        }
        return normalized;
    }

    private static String string(String json, String key, boolean required) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key)
                + "\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            if (required) {
                throw new GradleException("Clean-build evidence is missing '" + key + "'");
            }
            return null;
        }
        return unescape(matcher.group(1));
    }

    private static int integer(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
                + "\\\":([0-9]+)").matcher(json);
        if (!matcher.find()) {
            throw new GradleException("Clean-build evidence is missing integer '" + key + "'");
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static List<String> stringArray(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
                + "\\\":\\[((?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\"(?:,)?)*)\\]")
                .matcher(json);
        if (!matcher.find()) {
            throw new GradleException("Clean-build evidence is missing array '" + key + "'");
        }
        List<String> values = new ArrayList<>();
        Matcher item = Pattern.compile("\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
                .matcher(matcher.group(1));
        while (item.find()) {
            values.add(unescape(item.group(1)));
        }
        return Collections.unmodifiableList(values);
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!escaped) {
                if (character == '\\') {
                    escaped = true;
                } else {
                    result.append(character);
                }
                continue;
            }
            switch (character) {
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case '\\': result.append('\\'); break;
                case '"': result.append('"'); break;
                default: throw new GradleException("Unsupported JSON escape in clean evidence");
            }
            escaped = false;
        }
        if (escaped) {
            throw new GradleException("Invalid trailing JSON escape in clean evidence");
        }
        return result.toString();
    }

    /** Parsed successful-build marker. */
    public static final class Marker {
        private final String schema;
        private final String cleanTask;
        private final String buildTask;
        private final String sourceCommit;
        private final String sourceInputIdentity;
        private final int sourceFileCount;
        private final String sourceDateEpoch;
        private final List<String> excludedTasks;
        private final List<String> invocationTasks;

        private Marker(
                String schema,
                String cleanTask,
                String buildTask,
                String sourceCommit,
                String sourceInputIdentity,
                int sourceFileCount,
                String sourceDateEpoch,
                List<String> excludedTasks,
                List<String> invocationTasks) {
            this.schema = schema;
            this.cleanTask = cleanTask;
            this.buildTask = buildTask;
            this.sourceCommit = sourceCommit;
            this.sourceInputIdentity = sourceInputIdentity;
            this.sourceFileCount = sourceFileCount;
            this.sourceDateEpoch = sourceDateEpoch;
            this.excludedTasks = excludedTasks;
            this.invocationTasks = invocationTasks;
        }

        public String getSchema() { return schema; }
        public String getCleanTask() { return cleanTask; }
        public String getBuildTask() { return buildTask; }
        public String getSourceCommit() { return sourceCommit; }
        public String getSourceInputIdentity() { return sourceInputIdentity; }
        public int getSourceFileCount() { return sourceFileCount; }
        public String getSourceDateEpoch() { return sourceDateEpoch; }
        public List<String> getExcludedTasks() { return excludedTasks; }
        public List<String> getInvocationTasks() { return invocationTasks; }
    }

    /** Result of matching the first invocation's marker to current source inputs. */
    public static final class Verification {
        private final boolean verified;
        private final String reason;
        private final Marker marker;
        private final String report;

        private Verification(boolean verified, String reason, Marker marker, String report) {
            this.verified = verified;
            this.reason = reason;
            this.marker = marker;
            this.report = report;
        }

        public boolean isVerified() { return verified; }
        public String getReason() { return reason; }
        public Marker getMarker() { return marker; }
        public String getReport() { return report; }
    }
}
