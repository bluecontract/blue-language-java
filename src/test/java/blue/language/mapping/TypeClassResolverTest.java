package blue.language.mapping;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeClassResolverTest {

    @Test
    void shouldRequireCanonicalIdentityEvidenceForInlineTypeBodies() {
        // given
        TypeClassResolver resolver = new TypeClassResolver()
                .register("inline-type", String.class);
        Node completedType = new Node().name("Inline type");
        Node instance = new Node().type(completedType);
        AtomicReference<Node> observedType = new AtomicReference<>();
        CanonicalTypeIdentityLookup evidence =
                new CanonicalTypeIdentityLookup() {
                    @Override
                    public boolean hasCompleteCoverage() {
                        return true;
                    }

                    @Override
                    public Optional<CanonicalTypeIdentityEvidence>
                    findCanonicalTypeIdentityEvidence(Node requestedType) {
                        return Optional.of(CanonicalTypeIdentityEvidence
                                .identityOnly(requireCanonicalTypeBlueId(
                                        requestedType)));
                    }

                    @Override
                    public String requireCanonicalTypeBlueId(
                            Node requestedType) {
                        observedType.set(requestedType);
                        return "inline-type";
                    }
                };

        // when
        Executable withoutEvidence = () -> resolver.resolveClass(instance);
        Class<?> resolved = resolver.resolveClass(instance, evidence);

        // then
        assertThrows(IllegalStateException.class, withoutEvidence);
        assertSame(completedType, observedType.get());
        assertSame(String.class, resolved);
    }

    @Test
    void shouldResolvePureTypeReferenceWithoutResolutionEvidence() {
        // given
        TypeClassResolver resolver = new TypeClassResolver()
                .register("exact-type", String.class);
        Node instance = new Node().type(
                new Node().blueId("exact-type"));

        // when
        Class<?> resolved = resolver.resolveClass(instance);

        // then
        assertSame(String.class, resolved);
    }

    @Test
    void shouldPropagateIdentityEvidenceThroughRecursiveConversion() {
        // given
        TypeClassResolver resolver = new TypeClassResolver()
                .register("child-type", Child.class);
        Node completedChildType = new Node()
                .name("Completed child type")
                .blueId("child-type");
        Node source = new Node().properties(
                "child",
                new Node()
                        .type(completedChildType)
                        .properties("value", new Node().value("ok")));
        NodeToObjectConverter converter = new NodeToObjectConverter(resolver);
        AtomicReference<Node> observedType = new AtomicReference<>();
        CanonicalTypeIdentityLookup evidence =
                new CanonicalTypeIdentityLookup() {
                    @Override
                    public boolean hasCompleteCoverage() {
                        return true;
                    }

                    @Override
                    public Optional<CanonicalTypeIdentityEvidence>
                    findCanonicalTypeIdentityEvidence(Node requestedType) {
                        return Optional.of(CanonicalTypeIdentityEvidence
                                .identityOnly(requireCanonicalTypeBlueId(
                                        requestedType)));
                    }

                    @Override
                    public String requireCanonicalTypeBlueId(
                            Node requestedType) {
                        observedType.set(requestedType);
                        return "child-type";
                    }
                };

        // when
        Executable withoutEvidence =
                () -> converter.convert(source, Parent.class);

        // then
        RuntimeException missingEvidence = assertThrows(
                RuntimeException.class, withoutEvidence);
        Throwable rootCause = missingEvidence;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        assertTrue(rootCause instanceof IllegalStateException);
        Parent converted = converter.convert(
                source,
                Parent.class,
                evidence);

        assertSame(completedChildType, observedType.get());
        assertEquals("ok", converted.child.value);
    }

    @Test
    void shouldKeepBlueIdMapViewLiveAndUnmodifiableAcrossRegistration() {
        // given
        TypeClassResolver resolver = new TypeClassResolver();
        Map<String, Class<?>> view = resolver.getBlueIdMap();
        Set<Map.Entry<String, Class<?>>> entries = view.entrySet();

        // when
        resolver.register("retained-live-view", String.class);

        // then
        assertSame(String.class, view.get("retained-live-view"));
        assertEquals(1, entries.size());
        assertTrue(entries.stream().anyMatch(entry ->
                entry.getKey().equals("retained-live-view") && entry.getValue() == String.class));
        assertThrows(UnsupportedOperationException.class, view::clear);
    }

    public static final class Parent {
        public Child child;
    }

    public static final class Child {
        public String value;
    }
}
