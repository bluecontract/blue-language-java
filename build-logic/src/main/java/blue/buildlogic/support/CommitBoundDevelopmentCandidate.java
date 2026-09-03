package blue.buildlogic.support;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.GradleException;

/** Fail-closed validation for an immutable, commit-bound development repository. */
public final class CommitBoundDevelopmentCandidate {

    private static final Pattern VERSION =
            Pattern.compile("3\\.1\\.0-dev\\.([0-9a-f]{40})");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    private CommitBoundDevelopmentCandidate() {}

    public static void verify(
            String version,
            String sourceCommit,
            String repositoryHead,
            String workingTreeStatus) {
        String exactVersion = oneLine(version, "candidate version");
        String exactSourceCommit = oneLine(sourceCommit, "source commit");
        String exactRepositoryHead = oneLine(repositoryHead, "repository HEAD");

        Matcher versionMatcher = VERSION.matcher(exactVersion);
        if (!versionMatcher.matches()) {
            throw new GradleException(
                    "Immutable development candidate version must match "
                            + "3.1.0-dev.<40 lowercase hex commit>: "
                            + exactVersion);
        }
        if (!COMMIT.matcher(exactSourceCommit).matches()) {
            throw new GradleException(
                    "Immutable development candidate source commit must be 40 lowercase hex: "
                            + exactSourceCommit);
        }
        if (!COMMIT.matcher(exactRepositoryHead).matches()) {
            throw new GradleException(
                    "Immutable development candidate repository HEAD must be 40 lowercase hex: "
                            + exactRepositoryHead);
        }
        if (!exactSourceCommit.equals(exactRepositoryHead)) {
            throw new GradleException(
                    "Immutable development candidate source commit does not match repository HEAD: "
                            + exactSourceCommit + " != " + exactRepositoryHead);
        }
        if (!versionMatcher.group(1).equals(exactRepositoryHead)) {
            throw new GradleException(
                    "Immutable development candidate version is not bound to repository HEAD: "
                            + exactVersion + " != 3.1.0-dev." + exactRepositoryHead);
        }
        if (!Objects.requireNonNullElse(workingTreeStatus, "").isBlank()) {
            throw new GradleException(
                    "Immutable development candidate requires a clean Git checkout; "
                            + "git status --porcelain reported changes");
        }
    }

    private static String oneLine(String value, String label) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty() || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new GradleException(label + " must be one non-empty line");
        }
        return normalized;
    }
}
