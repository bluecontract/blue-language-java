package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GenderDefinitionLifecycleTest {

    @Test
    void shouldPrepareAndStoreGenderWithoutASampleValue() {
        // Given the user-authored constrained scalar definition.
        try (Fixture fixture = new Fixture()) {
            // When preparing its declaration and its exact stored representation.
            Node resolved = fixture.language.resolution()
                    .resolveDefinition(fixture.source);
            Node canonical = fixture.language.identity()
                    .canonicalIdentityInput(fixture.source);
            // Then both retain the constraint without inventing a payload.
            assertNull(resolved.getValue());
            assertNull(canonical.getValue());
            assertNull(fixture.source.getValue());
            assertNotNull(resolved.getSchema());
            assertEquals(2, resolved.getSchema().getEnum().size());
            assertEquals(fixture.typeId,
                    fixture.language.identity().directBlueId(canonical));
            assertEquals(fixture.typeId,
                    fixture.language.identity().sourceDocumentBlueId(fixture.source));
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.language.resolution().resolve(
                            new Node().type(reference(fixture.typeId))));
        }
    }

    @Test
    void shouldValidateGenderValuesThroughInlineReferenceAndImportedTypes() {
        // Given one exact Gender type and three equivalent authoring forms.
        try (Fixture fixture = new Fixture()) {
            // When values are specialized, then both enum members are accepted.
            for (String value : Arrays.asList("female", "male")) {
                for (Node instance : fixture.instances(value)) {
                    assertEquals(value, fixture.language.resolution()
                            .resolve(instance).getValue());
                }
                for (Node type : Arrays.asList(
                        fixture.canonical, reference(fixture.typeId))) {
                    Node specialized = fixture.language.graph().specialize(
                            type, new Node().value(value));
                    assertEquals(value, fixture.language.resolution()
                            .resolve(specialized).getValue());
                }
            }
            // When the payload is outside the enum or has another scalar kind.
            for (Node instance : fixture.instances("other")) {
                assertThrows(IllegalArgumentException.class,
                        () -> fixture.language.resolution().resolve(instance));
            }
            for (Node type : Arrays.asList(
                    fixture.canonical, reference(fixture.typeId))) {
                assertThrows(IllegalArgumentException.class,
                        () -> fixture.language.resolution().resolve(
                                new Node().type(type.clone()).value(42)));
                assertThrows(IllegalArgumentException.class,
                        () -> fixture.language.graph().specialize(
                                type, new Node().value("other")));
                assertThrows(IllegalArgumentException.class,
                        () -> fixture.language.graph().specialize(
                                type, new Node().value(42)));
            }
            Node importedWrongKind = fixture.imported("42");
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.language.resolution().resolve(importedWrongKind));
        }
    }

    @Test
    void shouldPreserveSourceIdentityAcrossResolutionAndRepresentationChanges() {
        // Given a valid reference-typed value stored under its actual exact ID.
        try (Fixture fixture = new Fixture()) {
            for (String value : Arrays.asList("female", "male")) {
                Node referenced = new Node().type(reference(fixture.typeId)).value(value);
                fixture.provider.addSingleNodes(
                        fixture.language.preprocessing().preprocess(referenced));
                String sourceId = fixture.language.identity()
                        .sourceDocumentBlueId(referenced);
                // When resolving, minimizing, expanding and collapsing the value.
                ResolvedSnapshot snapshot = fixture.language.snapshots().resolve(referenced);
                Node expanded = fixture.language.graph().expand(referenced);
                Node collapsed = fixture.language.graph().collapse(expanded);
                Node minimized = fixture.language.resolution().minimize(referenced);
                // Then the documented Source identity and validation are stable.
                for (Node form : Arrays.asList(
                        fixture.instances(value).get(0), fixture.imported(value),
                        expanded, minimized)) {
                    assertEquals(sourceId,
                            fixture.language.identity().sourceDocumentBlueId(form));
                    assertEquals(value,
                            fixture.language.resolution().resolve(form).getValue());
                }
                assertEquals(sourceId, fixture.language.identity()
                        .sourceDocumentBlueId(collapsed));
                // A pure value reference remains opaque until its payload is
                // demanded through expansion; it is not an absent scalar.
                assertEquals(value, fixture.language.resolution().resolve(
                        fixture.language.graph().expand(collapsed)).getValue());
                // Resolved materializations carry runtime metadata. Their
                // snapshot's certified canonical lane establishes Source identity.
                assertEquals(value, snapshot.resolvedRoot().getValue());
                assertEquals(sourceId, snapshot.blueId());
                assertEquals(sourceId, fixture.language.identity()
                        .directBlueId(snapshot.canonicalRoot()));
                assertNotEquals(sourceId, fixture.language.identity()
                        .sourceDocumentBlueId(new Node().value(value)));
            }
        }
    }

    @Test
    void shouldRetainInheritedCustomChildTypeForPrimitiveSyntax() {
        // Given a nested Gender declaration with no fixed child payload.
        try (Fixture fixture = new Fixture()) {
            for (Node genderType : Arrays.asList(
                    fixture.canonical, reference(fixture.typeId))) {
                Node person = new Node().name("Person")
                        .properties("gender", new Node().type(genderType.clone()));
                Node personCanonical = fixture.language.identity()
                        .canonicalIdentityInput(person);
                String personId = fixture.language.identity().directBlueId(personCanonical);
                fixture.provider.addSingleNodes(personCanonical);
                for (Node personType : Arrays.asList(personCanonical, reference(personId))) {
                    // When primitive syntax supplies an inherited custom field.
                    Node instance = new Node().type(personType.clone())
                            .properties("gender", new Node().value("female"));
                    ResolvedSnapshot snapshot = fixture.language.snapshots().resolve(instance);
                    Node resolved = snapshot.resolvedRoot();
                    Node child = resolved.getProperties().get("gender");
                    // Then the declared custom identity and enum obligation survive.
                    assertEquals("female", child.getValue());
                    assertEquals(fixture.typeId, snapshot.canonicalTypeIdentities()
                            .requireCanonicalTypeBlueId(child.getType()));
                    Node invalid = new Node().type(personType.clone())
                            .properties("gender", new Node().value("other"));
                    assertThrows(IllegalArgumentException.class,
                            () -> fixture.language.resolution().resolve(invalid));
                }
            }
        }
    }

    @Test
    void shouldNarrowInheritedEnumWithoutATypeSample() {
        // Given a subtype that narrows Gender to one allowed member.
        try (Fixture fixture = new Fixture()) {
            Node narrowed = new Node().name("FemaleOnly")
                    .type(reference(fixture.typeId))
                    .schema(new Schema().enumValues(Collections.singletonList(
                            new Node().value("female"))));
            Node canonical = fixture.language.identity().canonicalIdentityInput(narrowed);
            String narrowedId = fixture.language.identity().directBlueId(canonical);
            fixture.provider.addSingleNodes(canonical);
            assertNull(fixture.language.resolution().resolveDefinition(narrowed).getValue());
            assertTrue(fixture.language.resolution().isSubtype(
                    reference(narrowedId), reference(fixture.typeId)));
            // When specializing either equivalent subtype representation.
            for (Node type : Arrays.asList(canonical, reference(narrowedId))) {
                // Then only the narrowed member satisfies the intersection.
                assertEquals("female", fixture.language.resolution()
                        .resolve(new Node().type(type.clone()).value("female")).getValue());
                assertThrows(IllegalArgumentException.class,
                        () -> fixture.language.resolution().resolve(
                                new Node().type(type.clone()).value("male")));
            }
        }
    }

    @Test
    void shouldUseAValidFixedInstanceAsAType() {
        // Given a valid Gender instance prepared and stored as exact content.
        try (Fixture fixture = new Fixture()) {
            Node fixed = fixture.language.identity().canonicalIdentityInput(
                    new Node().type(reference(fixture.typeId)).value("female"));
            String fixedId = fixture.language.identity().directBlueId(fixed);
            fixture.provider.addSingleNodes(fixed);
            // When that instance is itself used as an inline or referenced type.
            for (Node type : Arrays.asList(fixed, reference(fixedId))) {
                // Then its inherited fixed payload satisfies the retained schema.
                assertEquals("female", fixture.language.resolution()
                        .resolve(new Node().type(type.clone())).getValue());
                assertDoesNotThrow(() -> fixture.language.resolution().resolve(
                        new Node().type(type.clone()).value("female")));
                assertThrows(IllegalArgumentException.class,
                        () -> fixture.language.resolution().resolve(
                                new Node().type(type.clone()).value("male")));
            }
        }
    }

    @Test
    void shouldRejectInvalidFixedDefinitionPayloads() {
        // Given a constrained declaration with an actual fixed payload.
        try (Fixture fixture = new Fixture()) {
            // When preparing definitions, then known payload obligations still run.
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.language.resolution().resolveDefinition(
                            fixture.source.clone().value("other")));
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.language.identity().canonicalIdentityInput(
                            fixture.source.clone().value(42)));
            assertDoesNotThrow(() -> fixture.language.resolution().resolveDefinition(
                    fixture.source.clone().value("female")));
        }
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class Fixture implements AutoCloseable {
        private final BasicNodeProvider provider = new BasicNodeProvider();
        private final BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider).build();
        private final Node source = language.codec().parseSource(
                "name: Gender\ntype: Text\nschema:\n  enum: [female, male]\n",
                BlueFormat.YAML);
        private final Node canonical = language.identity().canonicalIdentityInput(source);
        private final String typeId = language.identity().directBlueId(canonical);

        private Fixture() {
            provider.addSingleNodes(canonical);
        }

        private java.util.List<Node> instances(String value) {
            return Arrays.asList(new Node().type(canonical.clone()).value(value),
                    new Node().type(reference(typeId)).value(value), imported(value));
        }

        private Node imported(String yamlValue) {
            return language.codec().parseSource(
                    "blue:\n  imports:\n    Gender:\n      blueId: " + typeId
                            + "\ntype: Gender\nvalue: " + yamlValue + "\n", BlueFormat.YAML);
        }

        @Override
        public void close() {
            language.close();
        }
    }
}
