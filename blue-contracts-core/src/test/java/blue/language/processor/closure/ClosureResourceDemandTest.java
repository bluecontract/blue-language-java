package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.NoncommittingExecutionException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused API tests for typed noncommitting closure-resource demands. */
final class ClosureResourceDemandTest {

    @Test
    void retainsVerifiedInlineValueWithoutChangingIdentityOrOrder() {
        Node inline = new Node()
                .name("inline child")
                .properties("state", new Node().value("authored"));
        String suppliedBlueId = DirectBlueIdCalculator.calculateBlueId(
                inline);
        ManagedOccurrenceEvidenceDemand legacy = occurrence(
                hash('a'), hash('b'), 7L, id("source"), "/child",
                "declaration-blue", suppliedBlueId, 3L);

        ManagedOccurrenceEvidenceDemand enriched =
                ManagedOccurrenceEvidenceDemand.derived(
                        hash('a'),
                        hash('b'),
                        7L,
                        id("source"),
                        "/child",
                        "declaration-blue",
                        suppliedBlueId,
                        3L,
                        inline);

        assertEquals(legacy.demandIdentity(), enriched.demandIdentity());
        assertEquals(legacy, enriched,
                "exact inline evidence must not alter demand equality");
        assertEquals(0, legacy.compareTo(enriched),
                "exact inline evidence must not alter demand ordering");
        assertTrue(enriched.suppliedExactValue().isPresent());
        assertEquals(
                NodeWireForm.get(inline, NodeWireForm.Strategy.SIMPLE),
                NodeWireForm.get(
                        enriched.suppliedExactValue().orElseThrow(
                                () -> new AssertionError(
                                        "missing inline resource")),
                        NodeWireForm.Strategy.SIMPLE));
    }

    @Test
    void rejectsMismatchedOrReferenceOnlyInlineResource() {
        Node inline = new Node().name("inline child");
        String suppliedBlueId = DirectBlueIdCalculator.calculateBlueId(
                inline);
        String otherBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("other child"));

        assertThrows(IllegalArgumentException.class,
                () -> ManagedOccurrenceEvidenceDemand.derived(
                        hash('a'), hash('b'), 0L, id("source"), "/child",
                        "declaration-blue", otherBlueId, 0L, inline));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedOccurrenceEvidenceDemand.derived(
                        hash('a'), hash('b'), 0L, id("source"), "/child",
                        "declaration-blue", suppliedBlueId, 0L,
                        new Node().blueId(suppliedBlueId)));
    }

    @Test
    void clonesInlineResourceOnInputAndEveryRead() {
        Node input = new Node().properties(
                "state", new Node().value("authored"));
        Node pristine = input.clone();
        String suppliedBlueId = DirectBlueIdCalculator.calculateBlueId(
                input);
        ManagedOccurrenceEvidenceDemand demand =
                ManagedOccurrenceEvidenceDemand.derived(
                        hash('a'), hash('b'), 0L, id("source"), "/child",
                        "declaration-blue", suppliedBlueId, 0L, input);

        input.properties("state", new Node().value("mutated input"));
        Node firstRead = demand.suppliedExactValue().orElseThrow(
                () -> new AssertionError("missing first inline resource"));
        firstRead.properties("state", new Node().value("mutated read"));
        Node secondRead = demand.suppliedExactValue().orElseThrow(
                () -> new AssertionError("missing second inline resource"));

        assertEquals(
                NodeWireForm.get(pristine, NodeWireForm.Strategy.SIMPLE),
                NodeWireForm.get(
                        secondRead, NodeWireForm.Strategy.SIMPLE));
        assertEquals(suppliedBlueId,
                DirectBlueIdCalculator.calculateBlueId(secondRead));
    }

    @Test
    void legacyConstructorAndFactoryRemainResourceFree()
            throws NoSuchMethodException {
        ManagedOccurrenceEvidenceDemand derived = occurrence(
                hash('a'), hash('b'), 7L, id("source"), "/child",
                "declaration-blue", "value-blue", 3L);
        ManagedOccurrenceEvidenceDemand constructed =
                new ManagedOccurrenceEvidenceDemand(
                        derived.demandIdentity(),
                        hash('a'),
                        hash('b'),
                        7L,
                        id("source"),
                        "/child",
                        "declaration-blue",
                        "value-blue",
                        3L);

        assertEquals(derived, constructed);
        assertFalse(derived.suppliedExactValue().isPresent());
        assertFalse(constructed.suppliedExactValue().isPresent());
        assertEquals(9, ManagedOccurrenceEvidenceDemand.class
                .getConstructor(
                        String.class,
                        String.class,
                        String.class,
                        long.class,
                        DocumentId.class,
                        String.class,
                        String.class,
                        String.class,
                        long.class)
                .getParameterCount());
    }

    @Test
    void duplicateIdentityRetainsInlineResourceInEitherInputOrder() {
        Node inline = new Node().properties(
                "state", new Node().value("authored"));
        String suppliedBlueId = DirectBlueIdCalculator.calculateBlueId(
                inline);
        ManagedOccurrenceEvidenceDemand legacy = occurrence(
                hash('a'), hash('b'), 7L, id("source"), "/child",
                "declaration-blue", suppliedBlueId, 3L);
        ManagedOccurrenceEvidenceDemand enriched =
                ManagedOccurrenceEvidenceDemand.derived(
                        hash('a'), hash('b'), 7L, id("source"), "/child",
                        "declaration-blue", suppliedBlueId, 3L, inline);

        ClosureAttemptResult legacyThenEnriched =
                ClosureAttemptResult.needsResources(
                        Arrays.asList(legacy, enriched));
        ClosureAttemptResult enrichedThenLegacy =
                ClosureAttemptResult.needsResources(
                        Arrays.asList(enriched, legacy));

        assertEquals(legacyThenEnriched.resourceDemands(),
                enrichedThenLegacy.resourceDemands());
        for (ClosureAttemptResult attempt : Arrays.asList(
                legacyThenEnriched, enrichedThenLegacy)) {
            assertEquals(1, attempt.resourceDemands().size());
            ManagedOccurrenceEvidenceDemand retained =
                    (ManagedOccurrenceEvidenceDemand) attempt
                            .resourceDemands().get(0);
            assertTrue(retained.suppliedExactValue().isPresent());
            assertEquals(
                    NodeWireForm.get(
                            inline, NodeWireForm.Strategy.SIMPLE),
                    NodeWireForm.get(
                            retained.suppliedExactValue().orElseThrow(
                                    () -> new AssertionError(
                                            "missing retained resource")),
                            NodeWireForm.Strategy.SIMPLE));
        }
    }

    @Test
    void derivesStableIdentityFromOneDomainAndEveryBoundField() {
        ExactNodeDemand exact = ExactNodeDemand.derived(
                "value-blue", id("source"), "/children/0");
        assertEquals(
                "sha256:c5042bf5cb6fb473ba6c5af2308160abb6acdef01576991f4fdc9f9f8003999e",
                exact.demandIdentity());
        assertEquals(exact, ExactNodeDemand.derived(
                "value-blue", id("source"), "/children/0"));
        assertNotEquals(exact.demandIdentity(), ExactNodeDemand.derived(
                "other-blue", id("source"), "/children/0")
                .demandIdentity());
        assertNotEquals(exact.demandIdentity(), ExactNodeDemand.derived(
                "value-blue", id("other-source"), "/children/0")
                .demandIdentity());
        assertNotEquals(exact.demandIdentity(), ExactNodeDemand.derived(
                "value-blue", id("source"), "/children/1")
                .demandIdentity());

        ManagedOccurrenceEvidenceDemand occurrence = occurrence(
                hash('a'), hash('b'), 7L, id("source"), "/children/0",
                "declaration-blue", "value-blue", 3L);
        assertEquals(
                "sha256:c6c1dea6769fa18a49fdbd7356e9f0840d2fa8cd6c25acd7a279a9c9e72528a8",
                occurrence.demandIdentity());
        assertEquals(occurrence, occurrence(
                hash('a'), hash('b'), 7L, id("source"), "/children/0",
                "declaration-blue", "value-blue", 3L));
        assertIdentityChanges(occurrence, hash('c'), hash('b'), 7L,
                id("source"), "/children/0", "declaration-blue",
                "value-blue", 3L);
        assertIdentityChanges(occurrence, hash('a'), hash('c'), 7L,
                id("source"), "/children/0", "declaration-blue",
                "value-blue", 3L);
        assertIdentityChanges(occurrence, hash('a'), hash('b'), 8L,
                id("source"), "/children/0", "declaration-blue",
                "value-blue", 3L);
        assertIdentityChanges(occurrence, hash('a'), hash('b'), 7L,
                id("other"), "/children/0", "declaration-blue",
                "value-blue", 3L);
        assertIdentityChanges(occurrence, hash('a'), hash('b'), 7L,
                id("source"), "/children/1", "declaration-blue",
                "value-blue", 3L);
        assertIdentityChanges(occurrence, hash('a'), hash('b'), 7L,
                id("source"), "/children/0", "other-declaration",
                "value-blue", 3L);
        assertIdentityChanges(occurrence, hash('a'), hash('b'), 7L,
                id("source"), "/children/0", "declaration-blue",
                "other-value", 3L);
        assertIdentityChanges(occurrence, hash('a'), hash('b'), 7L,
                id("source"), "/children/0", "declaration-blue",
                "value-blue", 4L);
        assertNotEquals(exact.demandIdentity(), occurrence.demandIdentity(),
                "kind is bound by the shared demand-identity domain");
        assertThrows(IllegalArgumentException.class,
                () -> ClosureIdentityService.INSTANCE
                        .closureResourceDemandIdentity(
                                ClosureResourceDemand.Kind.EXACT_NODE,
                                hash('a'),
                                null,
                                null,
                                id("source"),
                                "/children/0",
                                null,
                                "value-blue",
                                null),
                "an exact-node identity must retain explicit nulls for all "
                        + "occurrence-only fields");

        assertThrows(IllegalArgumentException.class,
                () -> new ExactNodeDemand(
                        hash('f'), "value-blue", id("source"), "/children/0"));
        assertThrows(IllegalArgumentException.class,
                () -> occurrence(
                        hash('a'), hash('b'), -1L, id("source"),
                        "/children/0", "declaration-blue", "value-blue", 0L));
        assertThrows(IllegalArgumentException.class,
                () -> ExactNodeDemand.derived(
                        "value-blue", id("source"), "relative"));
    }

    @Test
    void ordersBySourcePathValueOrdinalAndIdentityWithoutKindPriority() {
        ExactNodeDemand exactZ = ExactNodeDemand.derived(
                "a-exact-blue", id("z"), "/z");
        ExactNodeDemand exactA = ExactNodeDemand.derived(
                "zz-exact-blue", id("a"), "/a");

        ManagedOccurrenceEvidenceDemand documentB = occurrence(
                hash('a'), hash('b'), 1L, id("b"), "/a",
                "declaration", "a-value", 0L);
        ManagedOccurrenceEvidenceDemand pathB = occurrence(
                hash('a'), hash('b'), 1L, id("a"), "/b",
                "declaration", "a-value", 0L);
        ManagedOccurrenceEvidenceDemand valueZ = occurrence(
                hash('a'), hash('b'), 1L, id("a"), "/a",
                "declaration", "z-value", 0L);
        ManagedOccurrenceEvidenceDemand ordinalTwo = occurrence(
                hash('a'), hash('b'), 1L, id("a"), "/a",
                "declaration", "a-value", 2L);
        ManagedOccurrenceEvidenceDemand ordinalOne = occurrence(
                hash('a'), hash('b'), 1L, id("a"), "/a",
                "declaration", "a-value", 1L);

        ClosureAttemptResult attempt = ClosureAttemptResult.needsResources(
                Arrays.<ClosureResourceDemand>asList(
                        documentB, exactZ, ordinalTwo, pathB, exactA,
                        valueZ, ordinalOne, ordinalOne));

        assertEquals(Arrays.<ClosureResourceDemand>asList(
                        ordinalOne,
                        ordinalTwo,
                        valueZ,
                        exactA,
                        pathB,
                        documentB,
                        exactZ),
                attempt.resourceDemands());
        assertTrue(attempt.resourceDemands().get(0)
                        instanceof ManagedOccurrenceEvidenceDemand,
                "a managed-evidence demand may precede an exact-node demand");
        assertEquals(Arrays.asList("a-exact-blue", "zz-exact-blue"),
                attempt.requiredExactBlueIds(),
                "the legacy exact projection retains independent lexical order");
        assertThrows(UnsupportedOperationException.class,
                () -> attempt.resourceDemands().clear());
    }

    @Test
    void enforcesAttemptAndNoncommittingSuspensionInvariants() {
        ManagedOccurrenceEvidenceDemand demand = occurrence(
                hash('a'), hash('b'), 0L, id("a"), "/child",
                "declaration", "value", 0L);
        ClosureResourceDemandException controlExit =
                new ClosureResourceDemandException(
                        Arrays.asList(demand, demand));
        assertTrue(controlExit instanceof NoncommittingExecutionException);
        assertEquals(Collections.singletonList(demand),
                controlExit.demands());

        ClosureAttemptResult suspended = ClosureAttemptResult.needsResources(
                controlExit.demands());
        assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES,
                suspended.kind());
        assertFalse(suspended.isComplete());
        assertNull(suspended.processResult());
        assertNull(suspended.totalGas());
        assertEquals(Collections.singletonList(demand),
                suspended.resourceDemands());
        assertTrue(suspended.requiredExactBlueIds().isEmpty(),
                "managed evidence is not projected as an exact-node ID");

        assertThrows(IllegalArgumentException.class,
                () -> ClosureAttemptResult.needsResources(
                        Collections.<ClosureResourceDemand>emptyList()));
        assertThrows(NullPointerException.class,
                () -> ClosureAttemptResult.needsResources(
                        Collections.<ClosureResourceDemand>singletonList(
                                null)));
        assertThrows(NullPointerException.class,
                () -> ClosureAttemptResult.complete(null));
    }

    @Test
    void bridgesLegacyExactIdsIntoTypedDuplicateFreeDemands() {
        ClosureAttemptResult attempt = ClosureAttemptResult.needsResources(
                Arrays.asList("z-blue", "a-blue", "z-blue"));

        assertEquals(Arrays.asList("a-blue", "z-blue"),
                attempt.requiredExactBlueIds());
        assertEquals(2, attempt.resourceDemands().size());
        for (ClosureResourceDemand demand : attempt.resourceDemands()) {
            assertEquals(ClosureResourceDemand.Kind.EXACT_NODE,
                    demand.kind());
            assertTrue(demand instanceof ExactNodeDemand);
            assertEquals(ExactNodeDemand.PROVIDER_SOURCE_DOCUMENT_ID,
                    demand.sourceDocumentId());
            assertEquals(ExactNodeDemand.PROVIDER_LOGICAL_PATH,
                    demand.sourcePath());
        }
    }

    @Test
    void closesConstructionToTheThreeFinalSupportedDemandForms()
            throws IOException {
        assertTrue(Modifier.isAbstract(
                ClosureResourceDemand.class.getModifiers()));
        for (Constructor<?> constructor
                : ClosureResourceDemand.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
            assertFalse(Modifier.isProtected(constructor.getModifiers()));
        }
        assertEquals(ClosureResourceDemand.class,
                ExactNodeDemand.class.getSuperclass());
        assertEquals(ClosureResourceDemand.class,
                ManagedOccurrenceEvidenceDemand.class.getSuperclass());
        assertEquals(ClosureResourceDemand.class,
                SourceInitializationDemand.class.getSuperclass());
        assertTrue(Modifier.isFinal(ExactNodeDemand.class.getModifiers()));
        assertTrue(Modifier.isFinal(
                ManagedOccurrenceEvidenceDemand.class.getModifiers()));
        assertTrue(Modifier.isFinal(SourceInitializationDemand.class.getModifiers()));

        Path workingDirectory = Paths.get("").toAbsolutePath();
        Path production = Files.isDirectory(
                workingDirectory.resolve("src/main/java"))
                ? workingDirectory.resolve("src/main/java")
                : workingDirectory.resolve(
                        "blue-contracts-core/src/main/java");
        List<String> directSubclasses = new ArrayList<String>();
        try (Stream<Path> sources = Files.walk(production)) {
            sources.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String code = new String(
                                    Files.readAllBytes(path),
                                    StandardCharsets.UTF_8);
                            if (code.matches("(?s).*\\bclass\\s+"
                                    + "[A-Za-z_$][A-Za-z0-9_$]*\\s+"
                                    + "extends\\s+"
                                    + "ClosureResourceDemand\\b.*")) {
                                directSubclasses.add(
                                        path.getFileName().toString());
                            }
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        }
        Collections.sort(directSubclasses);
        assertEquals(Arrays.asList(
                        "ExactNodeDemand.java",
                        "ManagedOccurrenceEvidenceDemand.java",
                        "SourceInitializationDemand.java"),
                directSubclasses,
                "Only the three reviewed demand forms may enter the closed hierarchy");
    }

    private static void assertIdentityChanges(
            ManagedOccurrenceEvidenceDemand original,
            String cause,
            String closure,
            long generation,
            DocumentId source,
            String path,
            String declaration,
            String supplied,
            long ordinal) {
        assertNotEquals(original.demandIdentity(), occurrence(
                cause, closure, generation, source, path, declaration,
                supplied, ordinal).demandIdentity());
    }

    private static ManagedOccurrenceEvidenceDemand occurrence(
            String cause,
            String closure,
            long generation,
            DocumentId source,
            String path,
            String declaration,
            String supplied,
            long ordinal) {
        return ManagedOccurrenceEvidenceDemand.derived(
                cause,
                closure,
                generation,
                source,
                path,
                declaration,
                supplied,
                ordinal);
    }

    private static DocumentId id(String value) {
        return new DocumentId(value);
    }

    private static String hash(char value) {
        return "sha256:" + String.join("",
                Collections.nCopies(64, String.valueOf(value)));
    }
}
