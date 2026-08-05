package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Focused characterization for the extracted routing collaborators. */
final class RoutingDecompositionTest {

    @Test
    void shouldCoalesceEquivalentSourcesInEncounterOrder() {
        // given
        LogicalDeliveryGrouper grouper = new LogicalDeliveryGrouper();
        ChannelRunner.ExternalClassification zSource = classification(
                "zSource", "handler", "logical", "payload");
        ChannelRunner.ExternalClassification aSource = classification(
                "aSource", "handler", "logical", "payload");

        // when
        List<List<ChannelRunner.ExternalClassification>> groups =
                grouper.group(Arrays.asList(zSource, aSource));

        // then
        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).size());
        assertEquals("zSource", groups.get(0).get(0).sourceChannelKey());
        assertEquals("aSource", groups.get(0).get(1).sourceChannelKey());
    }

    @Test
    void shouldRejectDisagreementWithinOneLogicalDelivery() {
        // given
        LogicalDeliveryGrouper grouper = new LogicalDeliveryGrouper();
        ChannelRunner.ExternalClassification first = classification(
                "first", "handler-a", "logical", "payload");
        ChannelRunner.ExternalClassification second = classification(
                "second", "handler-b", "logical", "payload");

        // when
        Throwable captured = FailureCapture.captureFailure(
                () -> grouper.group(Arrays.asList(first, second)));

        // then
        IllegalArgumentException failure = assertInstanceOf(
                IllegalArgumentException.class,
                captured);
        assertEquals(
                "Logical delivery group is inconsistent at //logical",
                failure.getMessage());
    }

    @Test
    void shouldKeepProcessorManagedChannelsOutOfExternalSourceCatalog() {
        // given
        ChannelContract external = new ChannelContract() { };
        ContractBundle bundle = ContractBundle.builder()
                .addChannel("external", external)
                .addChannel("triggered", new TriggeredEventChannel())
                .build();
        SameScopeChannelCatalog catalog =
                new SameScopeChannelCatalog(bundle);

        // when
        ContractBundle.ChannelBinding externalSource =
                catalog.externalSource("external");
        ContractBundle.ChannelBinding processorManaged =
                catalog.externalSource("triggered");
        ContractBundle.ChannelBinding handlerTarget =
                catalog.handlerTarget("triggered");

        // then
        assertNotNull(externalSource);
        assertNull(processorManaged);
        assertNotNull(handlerTarget);
    }

    @Test
    void shouldWithdrawScopeWithoutRecreatingParticipationOnRead() {
        // given
        ScopeParticipationRegistry registry =
                new ScopeParticipationRegistry(new LinkedHashMap<>());
        registry.participate("/child", ContractBundle.empty());

        // when
        registry.withdraw("/child");
        ContractBundle missing = registry.bundle("/child");

        // then
        assertNull(missing);
        assertEquals(0, registry.scopePaths().size());
    }

    private ChannelRunner.ExternalClassification classification(
            String source,
            String handler,
            String logical,
            String payload) {
        return ChannelRunner.ExternalClassification.acceptedNew(
                "/",
                source,
                handler,
                logical,
                null,
                FrozenNode.fromResolvedNode(new Node().value(payload)),
                null,
                "event-" + source,
                new Node().value(source));
    }
}
