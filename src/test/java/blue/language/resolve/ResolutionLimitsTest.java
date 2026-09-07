package blue.language.resolve;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.merge.Merger;
import blue.language.merge.ResolvedSnapshot;
import blue.language.merge.SnapshotResolution;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.matching.NodeTypeMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static blue.language.identity.DirectBlueIdCalculator.calculateBlueId;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class ResolutionLimitsTest {

    private ResolutionLimits pathLimits;
    private final Node mockNode = new Node();

    @BeforeEach
    public void setup() {
        pathLimits = ResolutionLimits.builder()
                .addPath("/x/*")
                .addPath("/y")
                .addPath("/a/b/*/c")
                .addPath("/d/0/*")
                .addPath("/e/*/*")
                .addPath("/forX/d/0")
                .addPath("/f/*/*")
                .setMaxDepth(4)
                .build();
    }

    @Test
    public void shouldProcessPathSegmentWithinConfiguredLimits() {
        // given

        // when
        boolean rootIncludesX =
                pathLimits.shouldExpandPathSegment("x", mockNode);
        pathLimits.enterPathSegment("x");
        boolean xIncludesA =
                pathLimits.shouldExpandPathSegment("a", mockNode);
        pathLimits.enterPathSegment("a");
        boolean xaIncludesD =
                pathLimits.shouldExpandPathSegment("d", mockNode);
        pathLimits.exitPathSegment();
        boolean xIncludesY =
                pathLimits.shouldExpandPathSegment("y", mockNode);
        pathLimits.exitPathSegment();
        pathLimits.enterPathSegment("y");
        boolean yIncludesC =
                pathLimits.shouldExpandPathSegment("c", mockNode);
        pathLimits.exitPathSegment();
        pathLimits.enterPathSegment("a");
        pathLimits.enterPathSegment("b");
        boolean abIncludesD =
                pathLimits.shouldExpandPathSegment("d", mockNode);
        pathLimits.enterPathSegment("d");
        boolean abdIncludesC =
                pathLimits.shouldExpandPathSegment("c", mockNode);

        // then
        assertTrue(rootIncludesX);
        assertTrue(xIncludesA);
        assertFalse(xaIncludesD);
        assertTrue(xIncludesY);
        assertFalse(yIncludesC);
        assertTrue(abIncludesD);
        assertTrue(abdIncludesC);
    }

    @Test
    public void shouldEnforceMaximumDepth() {
        // given
        pathLimits.enterPathSegment("a");
        // when
        pathLimits.enterPathSegment("b");
        boolean depthTwoIncludesAny =
                pathLimits.shouldExpandPathSegment("any", mockNode);
        pathLimits.enterPathSegment("any");
        boolean depthThreeIncludesC =
                pathLimits.shouldExpandPathSegment("c", mockNode);
        pathLimits.enterPathSegment("c");
        boolean depthFourIncludesE =
                pathLimits.shouldExpandPathSegment("e", mockNode);

        // then
        assertTrue(depthTwoIncludesAny);
        assertTrue(depthThreeIncludesC);
        assertFalse(depthFourIncludesE);
    }

    @Test
    public void shouldCertifyAuthoredPathRetentionConservatively() {
        // given
        ResolutionLimits deferred = ResolutionLimits
                .deferringReferencesAt(Collections.singleton("/cold"));
        ResolutionLimits pathFiltered = ResolutionLimits.withSinglePath(
                "/selected");

        // when
        ResolutionLimits deferredAndUnlimited = ResolutionLimits.allOf(
                ResolutionLimits.NO_LIMITS,
                deferred);

        // then
        assertTrue(ResolutionLimits.NO_LIMITS.retainsEveryAuthoredPath());
        assertTrue(deferred.retainsEveryAuthoredPath());
        assertTrue(deferredAndUnlimited.retainsEveryAuthoredPath());
        assertFalse(pathFiltered.retainsEveryAuthoredPath());
        assertFalse(ResolutionLimits.allOf(
                deferred,
                pathFiltered).retainsEveryAuthoredPath());
        assertFalse(ResolutionLimits.excluding(
                Collections.emptySet()).retainsEveryAuthoredPath());
        assertFalse(ResolutionLimits.filteringPropertiesForType(
                "type-id",
                Collections.emptySet()).retainsEveryAuthoredPath());
    }

    @Test
    public void shouldKeepLimitedResolverSnapshotDeferredWhenAdapted() {
        // given
        Node exactChild = new Node().properties(
                "payload", new Node().value("present"));
        String childBlueId = calculateBlueId(exactChild);
        BasicNodeProvider provider = new BasicNodeProvider(exactChild);
        Blue blue = new Blue(provider);
        Node source = new Node().properties(
                "child", new Node().blueId(childBlueId));
        ResolutionLimits limits = ResolutionLimits.deferringReferencesAt(
                Collections.singleton("/child"));

        // when
        SnapshotResolution resolution = new Merger(
                blue.getMergingProcessor(), provider)
                .resolveSnapshot(source, limits);
        ResolvedSnapshot adapted = ResolvedSnapshot.fromResolverResult(
                resolution);

        // then
        assertFalse(resolution.isResolutionComplete());
        assertFalse(adapted.isResolutionComplete());
        assertTrue(adapted.frozenResolvedRoot()
                .property("child").isReferenceOnly());
    }

    @Test
    public void shouldMatchSingleWildcard() {
        // given
        pathLimits.enterPathSegment("a");
        // when
        pathLimits.enterPathSegment("b");
        boolean includesAny =
                pathLimits.shouldExpandPathSegment("any", mockNode);
        pathLimits.enterPathSegment("any");
        boolean wildcardIncludesC =
                pathLimits.shouldExpandPathSegment("c", mockNode);

        // then
        assertTrue(includesAny);
        assertTrue(wildcardIncludesC);
    }

    @Test
    public void shouldMatchComplexPath() {
        // given
        pathLimits.enterPathSegment("a");
        // when
        pathLimits.enterPathSegment("b");
        boolean includesC =
                pathLimits.shouldExpandPathSegment("c", mockNode);
        pathLimits.enterPathSegment("c");
        boolean includesE =
                pathLimits.shouldExpandPathSegment("e", mockNode);

        // then
        assertTrue(includesC);
        assertFalse(includesE);
    }

    @Test
    public void shouldRejectInvalidPath() {
        // given
        String invalidRootSegment = "z";
        String candidateChildSegment = "a";

        // when
        pathLimits.enterPathSegment(invalidRootSegment);
        boolean candidateChildIncluded =
                pathLimits.shouldExpandPathSegment(candidateChildSegment, mockNode);

        // then
        assertFalse(candidateChildIncluded);
    }

    @Test
    public void shouldMatchPathWithIndex() {
        // given
        ResolutionLimits limits = pathLimits;

        // when
        limits.enterPathSegment("d");
        boolean includesZero =
                limits.shouldExpandPathSegment("0", mockNode);
        limits.enterPathSegment("0");
        boolean zeroIncludesAny =
                limits.shouldExpandPathSegment("any", mockNode);
        limits.exitPathSegment();
        boolean includesOne =
                limits.shouldExpandPathSegment("1", mockNode);

        // then
        assertTrue(includesZero);
        assertTrue(zeroIncludesAny);
        assertFalse(includesOne);
    }

    @Test
    public void shouldMatchMultipleWildcards() {
        // given
        ResolutionLimits limits = pathLimits;

        // when
        limits.enterPathSegment("e");
        boolean includesZero =
                limits.shouldExpandPathSegment("0", mockNode);
        limits.enterPathSegment("0");
        boolean zeroIncludesOne =
                limits.shouldExpandPathSegment("1", mockNode);

        // then
        assertTrue(includesZero);
        assertTrue(zeroIncludesOne);
    }

    @Test
    public void shouldMatchSpecificIndexPath() {
        // given
        pathLimits = ResolutionLimits.builder()
                .addPath("/forX/d/0")
                .build();

        // when
        boolean rootIncludesForX =
                pathLimits.shouldExpandPathSegment("forX", mockNode);
        pathLimits.enterPathSegment("forX");
        boolean forXIncludesD =
                pathLimits.shouldExpandPathSegment("d", mockNode);
        pathLimits.enterPathSegment("d");
        boolean dIncludesZero =
                pathLimits.shouldExpandPathSegment("0", mockNode);
        pathLimits.enterPathSegment("0");
        boolean zeroIncludesAny =
                pathLimits.shouldExpandPathSegment("any", mockNode);
        pathLimits.exitPathSegment();
        boolean dIncludesOne =
                pathLimits.shouldExpandPathSegment("1", mockNode);

        // then
        assertTrue(rootIncludesForX);
        assertTrue(forXIncludesD);
        assertTrue(dIncludesZero);
        assertFalse(zeroIncludesAny);
        assertFalse(dIncludesOne);
    }

    @Test
    public void shouldMatchEscapedJsonPointerSegments() {
        // given
        pathLimits = ResolutionLimits.builder()
                .addPath("/x/a~1b/c~0d")
                .build();

        // when
        boolean rootIncludesX =
                pathLimits.shouldExpandPathSegment("x", mockNode);
        pathLimits.enterPathSegment("x");
        boolean xIncludesDecodedSlash =
                pathLimits.shouldExpandPathSegment("a/b", mockNode);
        boolean xIncludesEncodedSlash =
                pathLimits.shouldExpandPathSegment("a~1b", mockNode);
        pathLimits.enterPathSegment("a/b");
        boolean slashIncludesDecodedTilde =
                pathLimits.shouldExpandPathSegment("c~d", mockNode);
        boolean slashIncludesSlash =
                pathLimits.shouldExpandPathSegment("c/d", mockNode);

        // then
        assertTrue(rootIncludesX);
        assertTrue(xIncludesDecodedSlash);
        assertFalse(xIncludesEncodedSlash);
        assertTrue(slashIncludesDecodedTilde);
        assertFalse(slashIncludesSlash);
    }

    @Test
    public void shouldMatchTwoLevelWildcard() {
        // given

        // when
        boolean rootIncludesF =
                pathLimits.shouldExpandPathSegment("f", mockNode);
        pathLimits.enterPathSegment("f");
        boolean fIncludesAny =
                pathLimits.shouldExpandPathSegment("anySegment", mockNode);
        pathLimits.enterPathSegment("anySegment");
        boolean firstWildcardIncludesAnother =
                pathLimits.shouldExpandPathSegment(
                        "anotherSegment", mockNode);
        pathLimits.enterPathSegment("anotherSegment");
        boolean secondWildcardIncludesTooDeep =
                pathLimits.shouldExpandPathSegment("tooDeep", mockNode);
        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        boolean fIncludesDifferent =
                pathLimits.shouldExpandPathSegment(
                        "differentSegment", mockNode);
        pathLimits.enterPathSegment("differentSegment");
        boolean differentIncludesLast =
                pathLimits.shouldExpandPathSegment(
                        "lastSegment", mockNode);
        pathLimits.enterPathSegment("lastSegment");
        boolean lastIncludesTooDeep =
                pathLimits.shouldExpandPathSegment(
                        "tooDeepAgain", mockNode);
        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        boolean rootIncludesG =
                pathLimits.shouldExpandPathSegment("g", mockNode);

        // then
        assertTrue(rootIncludesF);
        assertTrue(fIncludesAny);
        assertTrue(firstWildcardIncludesAnother);
        assertFalse(secondWildcardIncludesTooDeep);
        assertTrue(fIncludesDifferent);
        assertTrue(differentIncludesLast);
        assertFalse(lastIncludesTooDeep);
        assertFalse(rootIncludesG);
    }

    @Test
    public void shouldFailClosedWhenFilteredPropertyStillRequiresReferenceEvidence() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Blue blue = new Blue(nodeProvider);

        String a = "name: A\n" +
                   "x:\n" +
                   "  description: aa\n" +
                   "  schema:\n" +
                   "    maxLength: 4\n" +
                   "y:\n" +
                   "  schema:\n" +
                   "    maxLength: 4";
        Node aNode = blue.yamlToNode(a);
        nodeProvider.addSingleNodes(aNode);
        String referencedBlueId = calculateBlueId(new Node().value("some-blue-id"));

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + calculateBlueId(aNode) + "\n" +
                   "x:\n" +
                   "  blueId: " + referencedBlueId + "\n" +
                   "y: abcd";
        Node bNode = blue.yamlToNode(b);
        nodeProvider.addSingleNodes(bNode);

        String bInst = "name: B Inst\n" +
                       "type:\n" +
                       "  blueId: " + calculateBlueId(bNode) + "\n" +
                       "x:\n" +
                       "  blueId: " + referencedBlueId + "\n" +
                       "y: abcd";
        Node bInstNode = blue.yamlToNode(bInst);
        nodeProvider.addSingleNodes(bInstNode);

        String typeBlueId = calculateBlueId(bNode);
        Set<String> ignoredProperties = new HashSet<>(Collections.singletonList("x"));
        ResolutionLimits globalLimits = ResolutionLimits
                .filteringPropertiesForType(typeBlueId, ignoredProperties);

        // when
        boolean result =
                new NodeTypeMatcher(blue)
                        .matchesType(bInstNode, bNode, globalLimits);

        // then
        assertFalse(result);
    }

}
