package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deeply immutable entry plan for the immediate embedded children of a scope.
 *
 * <p>Declaration order and planner-supplied canonical concrete order are
 * retained through deterministic lists and insertion-ordered maps. The plan
 * contains no mutable document nodes and performs no executable work.</p>
 */
final class EmbeddedScopePlan {

    private final String scopePath;
    private final List<String> explicitDeclarationPaths;
    private final List<String> collectionDeclarationPaths;
    private final Map<String, List<String>>
            collectionMemberKeysByDeclaration;
    private final List<EmbeddedConcretePath> concretePaths;
    private final List<String> concreteChildPaths;
    private final Map<String, EmbeddedPathOrigin> concretePathOrigins;

    /**
     * Creates an immutable scope plan from planner-owned deterministic input.
     *
     * @param scopePath absolute path of the declaring scope
     * @param explicitDeclarationPaths normalized exact declarations
     * @param collectionDeclarationPaths normalized collection declarations
     * @param collectionMemberKeysByDeclaration complete ordered member keys
     *        for every collection declaration
     * @param concretePaths combined concrete paths in canonical order
     */
    EmbeddedScopePlan(
            String scopePath,
            List<String> explicitDeclarationPaths,
            List<String> collectionDeclarationPaths,
            Map<String, List<String>> collectionMemberKeysByDeclaration,
            List<EmbeddedConcretePath> concretePaths) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.explicitDeclarationPaths = immutableStrings(
                explicitDeclarationPaths, "explicit declaration path");
        this.collectionDeclarationPaths = immutableStrings(
                collectionDeclarationPaths, "collection declaration path");
        this.collectionMemberKeysByDeclaration = immutableMemberKeys(
                this.collectionDeclarationPaths,
                collectionMemberKeysByDeclaration);
        this.concretePaths = immutableConcretePaths(concretePaths);

        List<String> childPaths = new ArrayList<>(this.concretePaths.size());
        Map<String, EmbeddedPathOrigin> origins = new LinkedHashMap<>();
        for (EmbeddedConcretePath concretePath : this.concretePaths) {
            String absolutePath = concretePath.absolutePath();
            if (origins.put(absolutePath, concretePath.origin()) != null) {
                throw new IllegalArgumentException(
                        "Duplicate concrete embedded path: " + absolutePath);
            }
            childPaths.add(absolutePath);
        }
        this.concreteChildPaths = Collections.unmodifiableList(childPaths);
        this.concretePathOrigins = Collections.unmodifiableMap(origins);
    }

    /** Returns the absolute path of the declaring scope. */
    String scopePath() {
        return scopePath;
    }

    /** Returns exact declarations in their effective declaration order. */
    List<String> explicitDeclarationPaths() {
        return explicitDeclarationPaths;
    }

    /** Returns collection declarations in effective declaration order. */
    List<String> collectionDeclarationPaths() {
        return collectionDeclarationPaths;
    }

    /**
     * Returns complete direct member keys for each collection declaration.
     *
     * @return deeply immutable insertion-ordered mapping
     */
    Map<String, List<String>> collectionMemberKeysByDeclaration() {
        return collectionMemberKeysByDeclaration;
    }

    /** Returns concrete paths with full declaration provenance. */
    List<EmbeddedConcretePath> concretePaths() {
        return concretePaths;
    }

    /** Returns combined concrete child paths in canonical planner order. */
    List<String> concreteChildPaths() {
        return concreteChildPaths;
    }

    /** Returns each concrete path's origin in concrete-path order. */
    Map<String, EmbeddedPathOrigin> concretePathOrigins() {
        return concretePathOrigins;
    }

    private static List<String> immutableStrings(
            List<String> source,
            String label) {
        Objects.requireNonNull(source, label + "s");
        List<String> copy = new ArrayList<>(source.size());
        for (String value : source) {
            copy.add(Objects.requireNonNull(value, label));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, List<String>> immutableMemberKeys(
            List<String> declarations,
            Map<String, List<String>> source) {
        Objects.requireNonNull(source, "collectionMemberKeysByDeclaration");
        Set<String> uniqueDeclarations = new LinkedHashSet<>(declarations);
        if (uniqueDeclarations.size() != declarations.size()
                || !uniqueDeclarations.equals(source.keySet())) {
            throw new IllegalArgumentException(
                    "Collection member keys must match collection declarations");
        }

        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (String declaration : declarations) {
            copy.put(declaration, immutableStrings(
                    source.get(declaration), "collection member key"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<EmbeddedConcretePath> immutableConcretePaths(
            List<EmbeddedConcretePath> source) {
        Objects.requireNonNull(source, "concretePaths");
        List<EmbeddedConcretePath> copy = new ArrayList<>(source.size());
        for (EmbeddedConcretePath path : source) {
            copy.add(Objects.requireNonNull(path, "concrete path"));
        }
        return Collections.unmodifiableList(copy);
    }
}
