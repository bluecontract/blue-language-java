package blue.language.processor;

import blue.language.processor.model.EmbeddedCollectionEventChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.TriggeredEventChannel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact path, header, and gas rules for embedded-event Channel routing. */
final class EmbeddedCollectionEventChannelSupportTest {

    @Test
    void shouldMatchDirectMembersAndOptionallyTheirDescendants() {
        assertMatch(true, 1, "/orders", "/orders/o1", false);
        assertMatch(false, 0, "/orders", "/orders/o1/payment", false);
        assertMatch(true, 1, "/orders", "/orders/o1/payment", true);
        assertMatch(false, 1, "/orders", "/orders-old/o1", true);
        assertMatch(false, 0, "/orders", "/orders", true);
    }

    @Test
    void shouldCompareDecodedEscapedSegmentsStructurally() {
        assertMatch(true, 1, "/a~1b", "/a~1b/member", false);
        assertMatch(true, 1, "/a~0b", "/a~0b/member", false);
        assertMatch(false, 1, "/a~1b", "/a~0b/member", true);
    }

    @Test
    void shouldDefaultIncludeDescendantsToFalse() {
        EmbeddedCollectionEventChannel channel =
                new EmbeddedCollectionEventChannel();

        assertTrue(ProcessorManagedChannelTypes.contains(channel));
        assertFalse(channel.includesDescendants());
        channel.setIncludeDescendants(Boolean.TRUE);
        assertTrue(channel.includesDescendants());
        channel.setIncludeDescendants(Boolean.FALSE);
        assertFalse(channel.includesDescendants());
    }

    @Test
    void shouldValidateAgainstEffectiveCollectionDeclarationsAndMeterScan() {
        EmbeddedCollectionEventChannel channel = channel("/orders");
        GasMeter gas = new GasMeter(GasSchedule.contracts10());

        EmbeddedCollectionEventChannelSupport.validateHeader(
                channel,
                EmbeddedScopeDeclaration.of(
                        Collections.<String>emptyList(),
                        Arrays.asList("/archive", "/orders")),
                "/",
                GasSchedule.contracts10(),
                new ContractRecognitionMeter(gas));

        assertEquals(2L, quantity(
                gas,
                GasScheduleConstants.ProcessorCounter
                        .EMBEDDED_PATH_ENTRY_READ));
        assertEquals(2L, quantity(
                gas,
                GasScheduleConstants.ProcessorCounter
                        .EMBEDDED_PATH_SEGMENT_VALIDATED));
    }

    @Test
    void shouldStopHeaderComparisonWhenExactPathLengthsDiffer() {
        EmbeddedCollectionEventChannel channel = channel("/orders");
        GasMeter gas = new GasMeter(GasSchedule.contracts10());

        EmbeddedCollectionEventChannelSupport.validateHeader(
                channel,
                EmbeddedScopeDeclaration.of(
                        Collections.<String>emptyList(),
                        Arrays.asList("/orders/archive", "/orders")),
                "/",
                GasSchedule.contracts10(),
                new ContractRecognitionMeter(gas));

        assertEquals(2L, quantity(
                gas,
                GasScheduleConstants.ProcessorCounter
                        .EMBEDDED_PATH_ENTRY_READ));
        assertEquals(1L, quantity(
                gas,
                GasScheduleConstants.ProcessorCounter
                        .EMBEDDED_PATH_SEGMENT_VALIDATED));
    }

    @Test
    void shouldRejectRuntimeSourcePathAbovePortableSegmentLimit() {
        StringBuilder source = new StringBuilder();
        long limit = GasSchedule.contracts10().portableLimit(
                GasScheduleConstants.PortableLimit.RUNTIME_POINTER_SEGMENTS);
        for (int index = 0; index <= limit; index++) {
            source.append("/x");
        }

        PortableLimitExceededException failure = assertThrows(
                PortableLimitExceededException.class,
                () -> EmbeddedCollectionEventChannelSupport.match(
                        "/orders",
                        source.toString(),
                        false,
                        GasSchedule.contracts10()));

        assertEquals(
                GasScheduleConstants.PortableLimit.RUNTIME_POINTER_SEGMENTS,
                failure.limitName());
        assertEquals(limit + 1L, failure.observed());
        assertEquals(limit, failure.limit());
    }

    @Test
    void shouldRejectMissingMalformedUndeclaredAndContractsPaths() {
        assertInvalid(channel(null), declaration("/orders"));
        assertInvalid(channel("orders"), declaration("/orders"));
        assertInvalid(channel("/orders/"), declaration("/orders"));
        assertInvalid(channel("/other"), declaration("/orders"));
        assertInvalid(channel("/contracts/orders"),
                declaration("/contracts/orders"));
    }

    @Test
    void shouldKeepEmbeddedNodeChannelExactAndWildcardFree() {
        EmbeddedNodeChannel channel = new EmbeddedNodeChannel();
        assertTrue(ScopePropagationChain.matchesEmbeddedNodeSourcePath(
                "/", "/orders/o1", channel));

        channel.setSourcePath("/orders/o1");
        assertTrue(ScopePropagationChain.matchesEmbeddedNodeSourcePath(
                "/", "/orders/o1", channel));
        assertFalse(ScopePropagationChain.matchesEmbeddedNodeSourcePath(
                "/", "/orders/o1/payment", channel));

        channel.setSourcePath("/orders");
        assertFalse(ScopePropagationChain.matchesEmbeddedNodeSourcePath(
                "/", "/orders/o1", channel));

        channel.setSourcePath("/orders/*");
        assertFalse(ScopePropagationChain.matchesEmbeddedNodeSourcePath(
                "/", "/orders/o1", channel));
    }

    @Test
    void shouldInterleaveBothEmbeddedChannelTypesInOrdinaryChannelOrder() {
        EmbeddedNodeChannel oldLater = new EmbeddedNodeChannel();
        oldLater.setOrder(Integer.valueOf(20));
        EmbeddedCollectionEventChannel collectionFirst =
                channel("/orders");
        collectionFirst.setOrder(Integer.valueOf(10));
        EmbeddedNodeChannel oldFirstAtSameOrder =
                new EmbeddedNodeChannel();
        oldFirstAtSameOrder.setOrder(Integer.valueOf(10));
        TriggeredEventChannel unrelated = new TriggeredEventChannel();
        unrelated.setOrder(Integer.valueOf(0));
        ContractBundle bundle = ContractBundle.builder()
                .addChannel("z-old", oldLater)
                .addChannel("b-collection", collectionFirst)
                .addChannel("a-old", oldFirstAtSameOrder)
                .addChannel("unrelated", unrelated)
                .build();

        List<ContractBundle.ChannelBinding> channels =
                EmbeddedCollectionEventChannelSupport.orderedChannels(bundle);

        assertEquals(Arrays.asList("a-old", "b-collection", "z-old"),
                Arrays.asList(
                        channels.get(0).key(),
                        channels.get(1).key(),
                        channels.get(2).key()));
    }

    private static EmbeddedCollectionEventChannel channel(String path) {
        EmbeddedCollectionEventChannel result =
                new EmbeddedCollectionEventChannel();
        result.setCollectionPath(path);
        return result;
    }

    private static EmbeddedScopeDeclaration declaration(String path) {
        return EmbeddedScopeDeclaration.of(
                Collections.<String>emptyList(),
                Collections.singletonList(path));
    }

    private static void assertInvalid(
            EmbeddedCollectionEventChannel channel,
            EmbeddedScopeDeclaration declaration) {
        MustUnderstandFailureException failure = assertThrows(
                MustUnderstandFailureException.class,
                () -> EmbeddedCollectionEventChannelSupport.validateHeader(
                        channel,
                        declaration,
                        "/",
                        GasSchedule.contracts10(),
                        null));
        assertEquals(
                ProcessorErrorCategory.InvalidContractBinding,
                failure.errorCategory());
    }

    private static void assertMatch(
            boolean expected,
            int comparedSegments,
            String collection,
            String source,
            boolean includeDescendants) {
        EmbeddedCollectionEventChannelSupport.Match result =
                EmbeddedCollectionEventChannelSupport.match(
                        collection,
                        source,
                        includeDescendants,
                        GasSchedule.contracts10());
        assertEquals(expected, result.matches());
        assertEquals(comparedSegments, result.comparedSegments());
    }

    private static long quantity(GasMeter gas, String counter) {
        long result = 0L;
        for (GasTraceEntry entry : gas.trace()) {
            if (counter.equals(entry.counter())) {
                result += entry.quantity();
            }
        }
        return result;
    }
}
