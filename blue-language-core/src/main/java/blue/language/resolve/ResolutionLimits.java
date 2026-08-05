package blue.language.resolve;

import blue.language.model.Node;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Stateful policy consulted while expanding and merging a Blue graph.
 *
 * <p>Traversal must pair each accepted
 * {@link #enterPathSegment(String, Node)} with one {@link #exitPathSegment()}.
 * Implementations may use that balanced state to evaluate descendant paths.</p>
 */
public interface ResolutionLimits {

    /** Shared stateless policy that allows all traversal and reconstruction. */
    ResolutionLimits NO_LIMITS = NoLimits.INSTANCE;

    /**
     * Starts a mutable builder for one independent path-based limit.
     *
     * @return new path-limit builder
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Allows every path up to a maximum depth.
     *
     * @param maxDepth maximum number of entered path segments
     * @return new invocation-scoped limits
     */
    static ResolutionLimits withMaxDepth(int maxDepth) {
        return builder().setMaxDepth(maxDepth).addPath("*").build();
    }

    /**
     * Allows one path and each of its prefixes.
     *
     * @param path exact or single-segment-wildcard path
     * @return new invocation-scoped limits
     */
    static ResolutionLimits withSinglePath(String path) {
        return builder().addPath(path).build();
    }

    /**
     * Derives allowed terminal paths from a node graph.
     *
     * @param node graph root to inspect
     * @return new invocation-scoped path limits
     */
    static ResolutionLimits fromNode(Node node) {
        return NodeToPathLimitsConverter.convert(node);
    }

    /**
     * Excludes the supplied paths from expansion and merge traversal.
     *
     * @param paths paths to exclude, or {@code null} for none
     * @return new invocation-scoped limits
     */
    static ResolutionLimits excluding(Collection<String> paths) {
        return new ExcludedPathLimits(paths);
    }

    /**
     * Defers reference expansion below the supplied paths while preserving
     * ordinary merge behavior there.
     *
     * @param paths paths below which references remain deferred
     * @return new invocation-scoped limits
     */
    static ResolutionLimits deferringReferencesAt(Collection<String> paths) {
        return new DeferredReferencePathLimits(paths);
    }

    /**
     * Suppresses expansion of selected properties under one exact type.
     *
     * @param typeBlueId exact declared type identity
     * @param ignoredProperties properties whose expansion is suppressed
     * @return new invocation-scoped limits
     */
    static ResolutionLimits filteringPropertiesForType(
            String typeBlueId,
            Set<String> ignoredProperties) {
        return new TypeSpecificPropertyFilter(
                Objects.requireNonNull(typeBlueId, "typeBlueId"),
                Collections.unmodifiableSet(new LinkedHashSet<>(
                        Objects.requireNonNull(
                                ignoredProperties,
                                "ignoredProperties"))));
    }

    /**
     * Intersects multiple traversal policies in declaration order.
     *
     * @param limits policies to intersect
     * @return new invocation-scoped composite
     */
    static ResolutionLimits allOf(ResolutionLimits... limits) {
        Objects.requireNonNull(limits, "limits");
        ResolutionLimits[] snapshot = limits.clone();
        for (ResolutionLimits limit : snapshot) {
            Objects.requireNonNull(limit, "limit");
        }
        return new CompositeLimits(snapshot);
    }

    /**
     * Tests whether reference expansion may enter a segment.
     *
     * @param pathSegment candidate path segment
     * @param currentNode node at the current traversal position
     * @return whether expansion is allowed
     */
    default boolean shouldExpandPathSegment(String pathSegment, Node currentNode) {
        return shouldExtendPathSegment(pathSegment, currentNode);
    }

    /**
     * Compatibility name for {@link #shouldExpandPathSegment(String, Node)}.
     *
     * @param pathSegment candidate path segment
     * @param currentNode node at the current traversal position
     * @return whether expansion is allowed
     * <p>Implementations must override this method or its canonical
     * counterpart. The reciprocal defaults allow both existing 1.x
     * implementations and new expansion-named implementations to work.</p>
     *
     * <p>New code should implement and call
     * {@link #shouldExpandPathSegment(String, Node)}. This descriptor is
     * retained only for the frozen 1.x binary API.</p>
     */
    default boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
        return shouldExpandPathSegment(pathSegment, currentNode);
    }

    /**
     * Tests whether merging may enter a segment.
     *
     * @param pathSegment candidate path segment
     * @param currentNode node at the current traversal position
     * @return whether merging is allowed
     */
    boolean shouldMergePathSegment(String pathSegment, Node currentNode);

    /**
     * Tests whether a list-history fragment may be reconstructed.
     *
     * @param currentNode current list node
     * @param items candidate reconstructed items
     * @return whether reconstruction is allowed
     */
    default boolean shouldReconstructList(Node currentNode, List<Node> items) {
        return true;
    }

    /**
     * Records entry when no current-node context is available.
     *
     * @param pathSegment accepted path segment
     */
    default void enterPathSegment(String pathSegment) {
        enterPathSegment(pathSegment, null);
    }

    /**
     * Records entry into an accepted segment.
     *
     * @param pathSegment accepted path segment
     * @param currentNode node at the entered position
     */
    void enterPathSegment(String pathSegment, Node currentNode);

    /** Balances the most recent accepted segment entry. */
    void exitPathSegment();

    /** Mutable configuration scope for one path-based limit. */
    final class Builder {
        private final Set<String> allowedPaths = new HashSet<>();
        private int maxDepth = Integer.MAX_VALUE;

        private Builder() {
        }

        /**
         * Adds one exact or wildcard allowed path.
         *
         * @param path allowed path
         * @return this builder
         */
        public Builder addPath(String path) {
            allowedPaths.add(path);
            return this;
        }

        /**
         * Adds each exact or wildcard allowed path.
         *
         * @param paths allowed paths
         * @return this builder
         */
        public Builder addPaths(Collection<String> paths) {
            if (paths != null) {
                allowedPaths.addAll(paths);
            }
            return this;
        }

        /**
         * Sets the maximum number of entered path segments.
         *
         * @param maximumDepth maximum traversal depth
         * @return this builder
         */
        public Builder setMaxDepth(int maximumDepth) {
            this.maxDepth = maximumDepth;
            return this;
        }

        /**
         * Creates an independent stateful policy from this configuration.
         *
         * @return new path-based limits
         */
        public ResolutionLimits build() {
            return new PathLimits(allowedPaths, maxDepth);
        }
    }
}
