package blue.language.processor;

import blue.language.identity.BlueIds;
import blue.language.processor.util.PointerUtils;

import java.util.Objects;

/**
 * One concrete Process Embedded path and the exact declaration-field value
 * that contributed it.
 *
 * <p>The declaration identity is the BlueId of the effective {@code paths}
 * or {@code collectionPaths} field. It is never a synthetic identity for a
 * merged effective contract.</p>
 */
public final class ManagedProcessEmbeddedPath
        implements Comparable<ManagedProcessEmbeddedPath> {

    private final String absolutePath;
    private final String declarationContributionBlueId;

    /**
     * Creates one immutable concrete-path projection.
     *
     * @param absolutePath normalized, non-root absolute occurrence path
     * @param declarationContributionBlueId exact BlueId of the effective
     * Process Embedded declaration field that contributes the path
     */
    public ManagedProcessEmbeddedPath(
            String absolutePath,
            String declarationContributionBlueId) {
        String path = Objects.requireNonNull(absolutePath, "absolutePath");
        String normalized = PointerUtils.assertValidRuntimePointer(path);
        if (!path.equals(normalized) || "/".equals(path)) {
            throw new IllegalArgumentException(
                    "Managed Process Embedded path must be normalized and non-root");
        }
        this.absolutePath = path;
        this.declarationContributionBlueId = BlueIds.requirePlainBlueId(
                Objects.requireNonNull(
                        declarationContributionBlueId,
                        "declarationContributionBlueId"),
                "Process Embedded declaration contribution");
    }

    /**
     * Returns the normalized absolute occurrence path.
     *
     * @return normalized, non-root absolute occurrence path
     */
    public String absolutePath() {
        return absolutePath;
    }

    /**
     * Returns the exact contributing declaration-field BlueId.
     *
     * @return exact BlueId of the effective Process Embedded declaration
     * field that contributes the path
     */
    public String declarationContributionBlueId() {
        return declarationContributionBlueId;
    }

    @Override
    public int compareTo(ManagedProcessEmbeddedPath other) {
        int order = ExternalOrderKey.compareTextCodePoints(
                absolutePath, other.absolutePath);
        return order != 0
                ? order
                : ExternalOrderKey.compareTextCodePoints(
                        declarationContributionBlueId,
                        other.declarationContributionBlueId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ManagedProcessEmbeddedPath)) {
            return false;
        }
        ManagedProcessEmbeddedPath that = (ManagedProcessEmbeddedPath) other;
        return absolutePath.equals(that.absolutePath)
                && declarationContributionBlueId.equals(
                        that.declarationContributionBlueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(absolutePath, declarationContributionBlueId);
    }
}
