package blue.language.provider;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.Properties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;

/**
 * Compatibility facade over exact, content-addressed physical fragments for
 * one or more ordinary Blue roots.
 *
 * <p>Every inline semantic {@link Node} is retained as a shallow fragment. Its
 * semantic Node children are represented by pure BlueId references while
 * scalar and non-Node metadata remain inline. Fragment assembly, admission
 * validation, selected-cut traversal, and provider reads are implemented by
 * focused package collaborators.</p>
 *
 * <p>This utility deliberately does not flatten cyclic sets. A finalized
 * cyclic-member reference is retained as an opaque external edge and is not
 * served by this fragment set. Its materialization still requires proof from
 * a cyclic-set-aware provider. Placeholders, object cycles, and cycles formed
 * by mixing inline content with local references remain invalid.</p>
 */
public final class ExactNodeGraphFragments {

    private final List<RootRepresentation> roots;
    private final List<String> blueIds;
    private final SortedMap<String, Node> fragments;
    private final NodeProvider provider;

    /**
     * Splits every semantic child boundary of supplied exact roots.
     *
     * @param exactRoots non-empty ordinary exact roots
     * @throws IllegalArgumentException when a root is null, a pure reference,
     *                                  cyclic, or otherwise not fragmentable
     */
    public ExactNodeGraphFragments(Node... exactRoots) {
        this(requireRootArray(exactRoots));
    }

    /**
     * Splits every semantic child boundary of supplied exact roots.
     *
     * @param exactRoots non-empty ordinary exact roots
     * @throws IllegalArgumentException when a root is null, a pure reference,
     *                                  cyclic, or otherwise not fragmentable
     */
    public ExactNodeGraphFragments(
            Collection<? extends Node> exactRoots) {
        Objects.requireNonNull(exactRoots, "exactRoots");
        if (exactRoots.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one exact ordinary Blue root is required.");
        }

        List<Node> suppliedRoots = validateRoots(exactRoots);
        ExactFragmentAssembler assembler = new ExactFragmentAssembler();
        List<RootRepresentation> retainedRoots = new ArrayList<>(
                suppliedRoots.size());
        for (int index = 0; index < suppliedRoots.size(); index++) {
            Node root = suppliedRoots.get(index);
            ExactFragmentSupport.FragmentRecord record =
                    assembler.record(root, "root[" + index + "]");
            retainedRoots.add(new RootRepresentation(
                    record.blueId,
                    root,
                    record.directFragment));
        }
        assembler.rejectMixedReferenceCycles();

        this.roots = Collections.unmodifiableList(retainedRoots);
        this.fragments = ExactFragmentSupport
                .immutableFragmentSnapshot(assembler.fragments());
        this.blueIds = Collections.unmodifiableList(
                new ArrayList<>(fragments.keySet()));
        this.provider = new ExactFragmentProvider(fragments);
    }

    /**
     * Splits one exact root only at selected RFC 6901 cuts.
     *
     * <p>Every node on a root-to-cut path becomes one exact fragment. Other
     * descendants stay inline. Authored cut order and duplicate cuts do not
     * affect fragment identities or provider results.</p>
     *
     * @param exactRoot exact ordinary Blue content, not a pure reference
     * @param cuts RFC 6901 pointers relative to {@code exactRoot}; the empty
     *             pointer selects the root
     * @return immutable exact-fragment graph
     */
    public static ExactNodeGraphFragments split(
            Node exactRoot,
            Collection<String> cuts) {
        Objects.requireNonNull(exactRoot, "exactRoot");
        Objects.requireNonNull(cuts, "cuts");
        ExactFragmentGraphValidator validator =
                new ExactFragmentGraphValidator();
        validator.validate(exactRoot, "root[0]");
        requireInlineRoot(exactRoot, null);

        SelectiveExactFragmentAssembler assembler =
                new SelectiveExactFragmentAssembler(
                        ExactFragmentSupport.cutSelection(cuts));
        ExactFragmentSupport.FragmentRecord root =
                assembler.record(exactRoot, "root[0]");
        assembler.rejectMixedReferenceCycles();
        return new ExactNodeGraphFragments(
                Collections.singletonList(new RootRepresentation(
                        root.blueId,
                        exactRoot,
                        root.directFragment)),
                assembler.fragments());
    }

    private ExactNodeGraphFragments(
            List<RootRepresentation> roots,
            Map<String, Node> fragments) {
        this.roots = Collections.unmodifiableList(
                new ArrayList<>(roots));
        this.fragments = ExactFragmentSupport
                .immutableFragmentSnapshot(fragments);
        this.blueIds = Collections.unmodifiableList(
                new ArrayList<>(this.fragments.keySet()));
        this.provider = new ExactFragmentProvider(this.fragments);
    }

    /**
     * Returns root representations in caller-supplied order.
     *
     * @return immutable retained root representations
     */
    public List<RootRepresentation> roots() {
        return roots;
    }

    /**
     * Returns all local fragment identities in canonical lexical order.
     *
     * @return immutable lexical identity list
     */
    public List<String> blueIds() {
        return blueIds;
    }

    /**
     * Returns a lexically ordered snapshot keyed by exact BlueId.
     *
     * <p>Every returned node is a defensive copy. Mutating one cannot affect
     * this fragment set or its provider.</p>
     *
     * @return immutable lexical map of defensive fragment copies
     */
    public Map<String, Node> fragments() {
        return ExactFragmentSupport.immutableFragmentSnapshot(fragments);
    }

    /**
     * Returns the typed in-memory provider over exact shallow fragments.
     *
     * <p>Known identities return {@link NodeProviderOutcome#FOUND}; unknown
     * identities, including opaque cyclic-member edges, return
     * {@link NodeProviderOutcome#NOT_FOUND}.</p>
     *
     * @return immutable in-memory fragment provider
     */
    public NodeProvider provider() {
        return provider;
    }

    private static Collection<? extends Node> requireRootArray(
            Node[] exactRoots) {
        Objects.requireNonNull(exactRoots, "exactRoots");
        return Arrays.asList(exactRoots);
    }

    private static List<Node> validateRoots(
            Collection<? extends Node> exactRoots) {
        List<Node> suppliedRoots = new ArrayList<>(exactRoots.size());
        int index = 0;
        for (Node root : exactRoots) {
            if (root == null) {
                throw new IllegalArgumentException(
                        "Exact ordinary Blue root " + index
                                + " must not be null.");
            }
            suppliedRoots.add(root);
            index++;
        }
        ExactFragmentGraphValidator validator =
                new ExactFragmentGraphValidator();
        for (index = 0; index < suppliedRoots.size(); index++) {
            Node root = suppliedRoots.get(index);
            validator.validate(root, "root[" + index + "]");
            requireInlineRoot(root, index);
        }
        return suppliedRoots;
    }

    private static void requireInlineRoot(Node root, Integer index) {
        if (!root.isReferenceOnly()) {
            return;
        }
        String label = index == null
                ? "Exact ordinary Blue root"
                : "Exact ordinary Blue root " + index;
        throw new IllegalArgumentException(
                label + " is a pure reference; exact content is required.");
    }

    /** The original, shallow-fragment, and pure-reference root forms. */
    public static final class RootRepresentation {

        private final String blueId;
        private final Node original;
        private final Node directFragment;

        private RootRepresentation(
                String blueId,
                Node original,
                Node directFragment) {
            this.blueId = Objects.requireNonNull(
                    blueId,
                    Properties.OBJECT_BLUE_ID);
            this.original = Objects.requireNonNull(
                    original,
                    "original").clone();
            this.directFragment = Objects.requireNonNull(
                    directFragment,
                    "directFragment").clone();
        }

        /** @return exact root BlueId */
        public String blueId() {
            return blueId;
        }

        /** @return defensive copy of the caller-supplied root */
        public Node original() {
            return original.clone();
        }

        /** @return defensive shallow-fragment root copy */
        public Node directFragment() {
            return directFragment.clone();
        }

        /** @return fresh pure reference to the root identity */
        public Node pureReference() {
            return new Node().blueId(blueId);
        }
    }
}
