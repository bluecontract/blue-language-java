package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Public read-only projection of one effective Process Embedded scope plan.
 *
 * <p>The view exposes declaration provenance and concrete stable-key
 * occurrences without exposing mutable document nodes or the processor's
 * invocation-local plan implementation. It is inspection data only: creating
 * or reading the value executes no contract and consumes no Contracts gas.</p>
 */
public final class EmbeddedScopePlanView {

    /** Identifies the declaration form that produced a concrete child path. */
    public enum Origin {
        /** The child was named directly by {@code Process Embedded.paths}. */
        EXPLICIT,
        /** The child is a direct stable-key collection member. */
        COLLECTION_MEMBER
    }

    private final String scopePath;
    private final List<String> explicitDeclarationPaths;
    private final List<String> collectionDeclarationPaths;
    private final Map<String, List<String>>
            collectionMemberKeysByDeclaration;
    private final List<String> concreteChildPaths;
    private final Map<String, Origin> originsByConcretePath;

    EmbeddedScopePlanView(
            String scopePath,
            List<String> explicitDeclarationPaths,
            List<String> collectionDeclarationPaths,
            Map<String, List<String>> collectionMemberKeysByDeclaration,
            List<String> concreteChildPaths,
            Map<String, Origin> originsByConcretePath) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.explicitDeclarationPaths = immutableList(
                explicitDeclarationPaths, "explicitDeclarationPaths");
        this.collectionDeclarationPaths = immutableList(
                collectionDeclarationPaths, "collectionDeclarationPaths");
        this.collectionMemberKeysByDeclaration = immutableLists(
                collectionMemberKeysByDeclaration,
                "collectionMemberKeysByDeclaration");
        this.concreteChildPaths = immutableList(
                concreteChildPaths, "concreteChildPaths");
        this.originsByConcretePath = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(
                        originsByConcretePath,
                        "originsByConcretePath")));
        if (!this.originsByConcretePath.keySet().equals(
                new java.util.LinkedHashSet<>(this.concreteChildPaths))) {
            throw new IllegalArgumentException(
                    "Concrete paths and origin keys must be identical");
        }
    }

    static EmbeddedScopePlanView from(EmbeddedScopePlan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<String, Origin> origins = new LinkedHashMap<>();
        for (Map.Entry<String, EmbeddedPathOrigin> entry
                : plan.concretePathOrigins().entrySet()) {
            origins.put(
                    entry.getKey(),
                    entry.getValue() == EmbeddedPathOrigin.EXPLICIT
                            ? Origin.EXPLICIT
                            : Origin.COLLECTION_MEMBER);
        }
        return new EmbeddedScopePlanView(
                plan.scopePath(),
                plan.explicitDeclarationPaths(),
                plan.collectionDeclarationPaths(),
                plan.collectionMemberKeysByDeclaration(),
                plan.concreteChildPaths(),
                origins);
    }

    static EmbeddedScopePlanView empty(String scopePath) {
        return new EmbeddedScopePlanView(
                scopePath,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptyList(),
                Collections.<String, Origin>emptyMap());
    }

    /** Returns the absolute path of the declaring scope. */
    public String scopePath() {
        return scopePath;
    }

    /** Returns exact child declarations in effective list order. */
    public List<String> explicitDeclarationPaths() {
        return explicitDeclarationPaths;
    }

    /** Returns collection declarations in effective list order. */
    public List<String> collectionDeclarationPaths() {
        return collectionDeclarationPaths;
    }

    /** Returns canonical direct member keys for every collection declaration. */
    public Map<String, List<String>>
    collectionMemberKeysByDeclaration() {
        return collectionMemberKeysByDeclaration;
    }

    /** Returns combined absolute concrete child paths in canonical order. */
    public List<String> concreteChildPaths() {
        return concreteChildPaths;
    }

    /** Returns declaration origin for every concrete child path. */
    public Map<String, Origin> originsByConcretePath() {
        return originsByConcretePath;
    }

    private static List<String> immutableList(
            List<String> source,
            String label) {
        Objects.requireNonNull(source, label);
        List<String> copy = new ArrayList<>(source.size());
        for (String value : source) {
            copy.add(Objects.requireNonNull(value, label + " value"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, List<String>> immutableLists(
            Map<String, List<String>> source,
            String label) {
        Objects.requireNonNull(source, label);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), label + " key"),
                    immutableList(entry.getValue(), label + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }
}
