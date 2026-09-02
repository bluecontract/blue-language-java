package blue.language.mapping;

import blue.language.Blue;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.BlueId;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeToObjectConverterBlueIdIdentityTest {

    @Test
    void shouldKeepBlueIdMappingStableAcrossEquivalentTypeForms() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Subject Type\n"
                        + "fixed: inherited");
        String subjectTypeBlueId = provider.getBlueIdByName("Subject Type");
        Blue blue = new Blue(provider);
        Node referencedSource = blue.yamlToNode(
                "subject:\n"
                        + "  type:\n"
                        + "    blueId: " + subjectTypeBlueId + "\n"
                        + "  payload: stable");
        Node inlineSource = blue.yamlToNode(
                "subject:\n"
                        + "  type:\n"
                        + "    name: Subject Type\n"
                        + "    fixed: inherited\n"
                        + "  payload: stable");
        ResolvedSnapshot referencedSnapshot = blue.resolveToSnapshot(
                referencedSource);
        ResolvedSnapshot inlineSnapshot = blue.resolveToSnapshot(
                inlineSource);
        Node resolvedSubject = referencedSnapshot.resolvedNodeAt(
                "/subject");
        NodeToObjectConverter converter = new NodeToObjectConverter(
                new TypeClassResolver());

        // when
        BlueIdHolder referenced = converter.convert(
                referencedSnapshot,
                BlueIdHolder.class);
        BlueIdHolder inline = converter.convert(
                inlineSnapshot,
                BlueIdHolder.class);

        // then
        assertTrue(referencedSource.getProperties().get("subject")
                .getType().isReferenceOnly());
        assertFalse(resolvedSubject.getType().isReferenceOnly());
        assertEquals("inherited", resolvedSubject.getProperties()
                .get("fixed").getValue());
        assertEquals(referenced.subject, inline.subject);
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        referencedSnapshot.canonicalNodeAt("/subject")),
                referenced.subject);
        Node resolvedFormHashInput = NodeToBlueIdInput
                .stripResolvedBlueIdMetadata(resolvedSubject.clone())
                .type(new Node().blueId(subjectTypeBlueId));
        assertNotEquals(referenced.subject,
                DirectBlueIdCalculator.calculateBlueId(
                        resolvedFormHashInput));
    }

    @Test
    void shouldRejectResolvedBlueIdMappingWithOnlyTypeEvidence() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs("name: Subject Type");
        Blue blue = new Blue(provider);
        Node source = blue.yamlToNode(
                "subject:\n"
                        + "  type:\n"
                        + "    blueId: "
                        + provider.getBlueIdByName("Subject Type") + "\n"
                        + "  payload: stable");
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(source);
        Node resolved = snapshot.resolvedRoot();
        NodeToObjectConverter converter = new NodeToObjectConverter(
                new TypeClassResolver());

        // when
        Executable conversion = () -> converter.convert(
                resolved,
                BlueIdHolder.class,
                snapshot.canonicalTypeIdentities());

        // then
        RuntimeException failure = assertThrows(
                RuntimeException.class, conversion);
        Throwable rootCause = rootCause(failure);
        assertInstanceOf(IllegalStateException.class, rootCause);
        assertTrue(rootCause.getMessage().contains(
                "authoritative Canonical Identity Input"));
    }

    @Test
    void shouldRejectDetachedResolvedBlueIdMappingWhenTypesRemainReferences() {
        // given
        String subjectTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Referenced subject type"));
        Node detachedResolved = new Node().properties(
                "subject",
                new Node()
                        .type(new Node().blueId(subjectTypeBlueId))
                        .properties("payload", new Node().value("stable")));
        NodeToObjectConverter converter = new NodeToObjectConverter(
                new TypeClassResolver());

        // when
        Executable conversion = () -> converter.convert(
                detachedResolved,
                BlueIdHolder.class,
                CanonicalTypeIdentityLookup.incomplete());

        // then
        RuntimeException failure = assertThrows(
                RuntimeException.class, conversion);
        Throwable rootCause = rootCause(failure);
        assertInstanceOf(IllegalStateException.class, rootCause);
        assertTrue(rootCause.getMessage().contains(
                "authoritative Canonical Identity Input"));
    }

    @Test
    void shouldBindOmitOnlyProjectionToMatchingCanonicalPaths() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs("name: Projected Subject Type");
        Blue blue = new Blue(provider);
        Node source = blue.yamlToNode(
                "subject:\n"
                        + "  type:\n"
                        + "    blueId: "
                        + provider.getBlueIdByName("Projected Subject Type")
                        + "\n"
                        + "  payload: stable\n"
                        + "deferred: cold");
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(source);
        NodeToObjectConverter converter = new NodeToObjectConverter(
                new TypeClassResolver());

        // when
        BlueIdHolder converted = converter
                .convertWithTypeOmittingProperties(
                        snapshot,
                        "/",
                        Collections.singleton("deferred"),
                        BlueIdHolder.class,
                        true);

        // then
        assertEquals(
                snapshot.canonicalBlueIdAt("/subject"),
                converted.subject);
        assertNull(converted.deferred);
    }

    @Test
    void shouldNotExposeCallerSuppliedSnapshotProjection() {
        // given

        // when
        boolean exposed = Arrays.stream(
                        NodeToObjectConverter.class.getMethods())
                .map(Method::getParameterTypes)
                .anyMatch(parameters -> Arrays.asList(parameters)
                        .contains(ResolvedSnapshot.class)
                        && Arrays.asList(parameters).contains(Node.class));

        // then
        assertFalse(exposed,
                "public mapping APIs must not pair a snapshot path with a "
                        + "caller-supplied resolved Node");
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static final class BlueIdHolder {
        @BlueId
        public String subject;
        public String deferred;
    }
}
