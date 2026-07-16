package blue.language.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.model.MarkerContract;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeProviderWrapper;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerMatchContextSubtypeTest {

    @Test
    void exactAndDescendantDeclaredTypesAreCompatible() {
        TypeFixture types = TypeFixture.create();
        ContractMatchingService matching = types.matchingService();

        assertTrue(context(types.event(types.expectedId), matching)
                .eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertTrue(context(types.event(types.childId), matching)
                .eventTypeIsSubtypeOf(reference(types.expectedId)));
    }

    @Test
    void unrelatedDeclaredTypesAreIncompatibleRegardlessOfShape() {
        TypeFixture types = TypeFixture.create();
        ContractMatchingService matching = types.matchingService();

        assertFalse(context(types.event(types.unrelatedSameShapeId), matching)
                .eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertFalse(context(types.differentEvent(), matching)
                .eventTypeIsSubtypeOf(reference(types.expectedId)));
    }

    @Test
    void missingEventDeclaredTypeOrExpectedTypeIsIncompatible() {
        TypeFixture types = TypeFixture.create();
        ContractMatchingService matching = types.matchingService();

        assertFalse(context(null, matching).eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertFalse(context(types.untypedEvent(), matching)
                .eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertFalse(context(types.event(types.expectedId), matching).eventTypeIsSubtypeOf(null));
    }

    @Test
    void pureReferencesUseTheConfiguredProviderWithoutExactTypeLookups() {
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));

        HandlerMatchContext exact = context(types.event(types.expectedId), matching);
        assertTrue(exact.eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertEquals(0, provider.lookupCount());

        HandlerMatchContext child = context(types.event(types.childId), matching);
        assertTrue(child.eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertEquals(2, provider.lookupCount());

        assertTrue(child.eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertEquals(4, provider.lookupCount());
    }

    @Test
    void unrelatedAndUnavailableReferencesHaveBoundedLookups() {
        TypeFixture types = TypeFixture.create();
        CountingMapProvider provider = new CountingMapProvider(types.definitions);
        ContractMatchingService matching = new ContractMatchingService(new Blue(provider));

        assertFalse(context(types.event(types.unrelatedSameShapeId), matching)
                .eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertEquals(1, provider.lookupCount());

        CountingMapProvider unavailable = new CountingMapProvider(Collections.<String, Node>emptyMap());
        ContractMatchingService unavailableMatching = new ContractMatchingService(new Blue(unavailable));
        assertFalse(context(types.event(types.childId), unavailableMatching)
                .eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertEquals(1, unavailable.lookupCount());
    }

    @Test
    void ambiguousProviderResultsRetainFormalSubtypeFailure() {
        TypeFixture types = TypeFixture.create();
        NodeProvider ambiguous = blueId -> Arrays.asList(new Node(), new Node());
        ContractMatchingService matching = new ContractMatchingService(
                new Blue(NodeProviderWrapper.unverified(ambiguous)));

        assertThrows(IllegalStateException.class, () ->
                context(types.event(types.childId), matching)
                        .eventTypeIsSubtypeOf(reference(types.expectedId)));
    }

    @Test
    void providerFreeMatchingSupportsExactAndMaterializedRelationsOnly() {
        TypeFixture types = TypeFixture.create();
        ContractMatchingService matching = new ContractMatchingService();

        assertTrue(context(types.event(types.expectedId), matching)
                .eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertTrue(context(types.materializedChildEvent(), matching)
                .eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertFalse(context(types.event(types.childId), matching)
                .eventTypeIsSubtypeOf(reference(types.expectedId)));
    }

    @Test
    void frozenContextAndIndependentlyReconstructedInputsAgree() {
        TypeFixture types = TypeFixture.create();
        ContractMatchingService matching = types.matchingService();
        Node sourceEvent = types.event(types.childId);
        HandlerMatchContext frozenContext = context(sourceEvent, matching);

        sourceEvent.type(reference(types.unrelatedSameShapeId));

        assertTrue(frozenContext.eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertTrue(frozenContext.eventTypeIsSubtypeOf(reference(types.expectedId)));
        assertTrue(context(types.event(types.childId), matching)
                .eventTypeIsSubtypeOf(reference(types.expectedId)));
    }

    @Test
    void genericMatcherRetainsStructuralFallbackForUnrelatedDeclaredTypes() {
        TypeFixture types = TypeFixture.create();
        ContractMatchingService matching = types.matchingService();

        assertTrue(matching.matches(
                types.event(types.unrelatedSameShapeId),
                types.pattern(types.expectedId)));
        assertFalse(matching.matches(
                types.differentEvent(),
                types.pattern(types.expectedId)));
    }

    private static HandlerMatchContext context(Node event, ContractMatchingService matching) {
        return new HandlerMatchContext(
                "/",
                "handler",
                "channel",
                event,
                Collections.<String, MarkerContract>emptyMap(),
                matching);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class TypeFixture {
        private final String expectedId;
        private final String childId;
        private final String unrelatedSameShapeId;
        private final String unrelatedDifferentShapeId;
        private final Node expectedDefinition;
        private final Map<String, Node> definitions;

        private TypeFixture(String expectedId,
                            String childId,
                            String unrelatedSameShapeId,
                            String unrelatedDifferentShapeId,
                            Node expectedDefinition,
                            Map<String, Node> definitions) {
            this.expectedId = expectedId;
            this.childId = childId;
            this.unrelatedSameShapeId = unrelatedSameShapeId;
            this.unrelatedDifferentShapeId = unrelatedDifferentShapeId;
            this.expectedDefinition = expectedDefinition;
            this.definitions = definitions;
        }

        private static TypeFixture create() {
            Node expected = sameShapeDefinition("Expected Event");
            String expectedId = BlueIdCalculator.calculateBlueId(expected);
            Node child = sameShapeDefinition("Child Event").type(reference(expectedId));
            Node unrelatedSameShape = sameShapeDefinition("Unrelated Same Shape Event");
            Node unrelatedDifferentShape = new Node()
                    .name("Unrelated Different Shape Event")
                    .properties("different", requiredText());
            String childId = BlueIdCalculator.calculateBlueId(child);
            String unrelatedSameShapeId = BlueIdCalculator.calculateBlueId(unrelatedSameShape);
            String unrelatedDifferentShapeId = BlueIdCalculator.calculateBlueId(unrelatedDifferentShape);
            Map<String, Node> definitions = new LinkedHashMap<String, Node>();
            definitions.put(expectedId, expected);
            definitions.put(childId, child);
            definitions.put(unrelatedSameShapeId, unrelatedSameShape);
            definitions.put(unrelatedDifferentShapeId, unrelatedDifferentShape);
            return new TypeFixture(
                    expectedId,
                    childId,
                    unrelatedSameShapeId,
                    unrelatedDifferentShapeId,
                    expected,
                    definitions);
        }

        private ContractMatchingService matchingService() {
            return new ContractMatchingService(new Blue(new MapProvider(definitions)));
        }

        private static Node sameShapeDefinition(String name) {
            return new Node().name(name).properties("kind", requiredText());
        }

        private static Node requiredText() {
            return new Node()
                    .type(reference(TEXT_TYPE_BLUE_ID))
                    .schema(new Schema().required(true));
        }

        private Node event(String typeBlueId) {
            return new Node()
                    .type(reference(typeBlueId))
                    .properties("kind", new Node().value("accepted"));
        }

        private Node materializedChildEvent() {
            Node childType = sameShapeDefinition("Materialized Child Event")
                    .type(expectedDefinition.clone());
            return new Node()
                    .type(childType)
                    .properties("kind", new Node().value("accepted"));
        }

        private Node untypedEvent() {
            return new Node().properties("kind", new Node().value("accepted"));
        }

        private Node pattern(String typeBlueId) {
            return new Node().type(reference(typeBlueId));
        }

        private Node differentEvent() {
            return new Node()
                    .type(reference(unrelatedDifferentShapeId))
                    .properties("different", new Node().value("value"));
        }
    }

    private static class MapProvider implements NodeProvider {
        private final Map<String, Node> definitions;

        private MapProvider(Map<String, Node> definitions) {
            this.definitions = definitions;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node definition = definitions.get(blueId);
            return definition != null
                    ? Collections.singletonList(definition.clone())
                    : null;
        }
    }

    private static final class CountingMapProvider extends MapProvider {
        private int lookupCount;

        private CountingMapProvider(Map<String, Node> definitions) {
            super(definitions);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            lookupCount++;
            return super.fetchByBlueId(blueId);
        }

        private int lookupCount() {
            return lookupCount;
        }
    }
}
