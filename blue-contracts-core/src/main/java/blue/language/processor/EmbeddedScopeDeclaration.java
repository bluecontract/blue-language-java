package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable structural Process Embedded declaration before scope expansion.
 *
 * <p>The declaration owns independent copies of the exact and collection
 * path lists. It intentionally retains authored order and leaves semantic
 * validation to the embedded-scope planner.</p>
 */
final class EmbeddedScopeDeclaration {

    static final EmbeddedScopeDeclaration EMPTY =
            new EmbeddedScopeDeclaration(
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList());

    private final List<String> explicitPaths;
    private final List<String> collectionPaths;

    private EmbeddedScopeDeclaration(
            List<String> explicitPaths,
            List<String> collectionPaths) {
        this.explicitPaths = immutableCopy(explicitPaths);
        this.collectionPaths = immutableCopy(collectionPaths);
    }

    /**
     * Creates a structural declaration, treating a missing list as empty.
     *
     * @param explicitPaths authored exact paths, or {@code null}
     * @param collectionPaths authored collection paths, or {@code null}
     * @return immutable declaration, or the shared empty declaration
     */
    static EmbeddedScopeDeclaration of(
            List<String> explicitPaths,
            List<String> collectionPaths) {
        if ((explicitPaths == null || explicitPaths.isEmpty())
                && (collectionPaths == null || collectionPaths.isEmpty())) {
            return EMPTY;
        }
        return new EmbeddedScopeDeclaration(
                explicitPaths != null
                        ? explicitPaths
                        : Collections.<String>emptyList(),
                collectionPaths != null
                        ? collectionPaths
                        : Collections.<String>emptyList());
    }

    /** Returns the shared declaration containing no paths. */
    static EmbeddedScopeDeclaration empty() {
        return EMPTY;
    }

    /** Returns exact paths in authored declaration order. */
    List<String> explicitPaths() {
        return explicitPaths;
    }

    /** Returns collection paths in authored declaration order. */
    List<String> collectionPaths() {
        return collectionPaths;
    }

    /** Reports whether both declaration lists are empty. */
    boolean isEmpty() {
        return explicitPaths.isEmpty() && collectionPaths.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EmbeddedScopeDeclaration)) {
            return false;
        }
        EmbeddedScopeDeclaration that = (EmbeddedScopeDeclaration) other;
        return explicitPaths.equals(that.explicitPaths)
                && collectionPaths.equals(that.collectionPaths);
    }

    @Override
    public int hashCode() {
        return Objects.hash(explicitPaths, collectionPaths);
    }

    @Override
    public String toString() {
        return "EmbeddedScopeDeclaration{explicitPaths=" + explicitPaths
                + ", collectionPaths=" + collectionPaths + '}';
    }

    private static List<String> immutableCopy(List<String> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
