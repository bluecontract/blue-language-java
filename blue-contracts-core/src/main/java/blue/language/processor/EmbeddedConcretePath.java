package blue.language.processor;

import java.util.Objects;

/**
 * Immutable concrete child path with its declaration provenance.
 *
 * <p>The value retains only text and enum state. It deliberately owns no
 * mutable document node or provider materialization.</p>
 */
final class EmbeddedConcretePath {

    private final String absolutePath;
    private final EmbeddedPathOrigin origin;
    private final String declarationPath;
    private final String memberKey;

    /**
     * Creates one concrete embedded path.
     *
     * @param absolutePath resolved absolute child path
     * @param origin declaration form that produced the path
     * @param declarationPath normalized authored declaration path
     * @param memberKey exact collection member key, or {@code null} for an
     *        explicit path
     */
    EmbeddedConcretePath(
            String absolutePath,
            EmbeddedPathOrigin origin,
            String declarationPath,
            String memberKey) {
        this.absolutePath = Objects.requireNonNull(
                absolutePath, "absolutePath");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.declarationPath = Objects.requireNonNull(
                declarationPath, "declarationPath");
        if (origin == EmbeddedPathOrigin.EXPLICIT && memberKey != null) {
            throw new IllegalArgumentException(
                    "An explicit embedded path cannot have a member key");
        }
        if (origin == EmbeddedPathOrigin.COLLECTION_MEMBER
                && memberKey == null) {
            throw new IllegalArgumentException(
                    "A collection-member path requires its exact member key");
        }
        this.memberKey = memberKey;
    }

    /** Returns the resolved absolute child path. */
    String absolutePath() {
        return absolutePath;
    }

    /** Returns the declaration form that produced this path. */
    EmbeddedPathOrigin origin() {
        return origin;
    }

    /** Returns the normalized authored declaration path. */
    String declarationPath() {
        return declarationPath;
    }

    /**
     * Returns the exact unescaped collection key.
     *
     * @return collection key, or {@code null} for an explicit path
     */
    String memberKey() {
        return memberKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EmbeddedConcretePath)) {
            return false;
        }
        EmbeddedConcretePath that = (EmbeddedConcretePath) other;
        return absolutePath.equals(that.absolutePath)
                && origin == that.origin
                && declarationPath.equals(that.declarationPath)
                && Objects.equals(memberKey, that.memberKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                absolutePath, origin, declarationPath, memberKey);
    }

    @Override
    public String toString() {
        return "EmbeddedConcretePath{" + absolutePath
                + ", origin=" + origin
                + ", declaration=" + declarationPath
                + (memberKey != null ? ", memberKey=" + memberKey : "")
                + '}';
    }
}
