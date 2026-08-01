package blue.language.identity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ListBlueIdFoldTest {

    @Test
    void shouldAppendUsingOnlyEstablishedPrefixAndElementBlueIds() {
        // given
        ListBlueIdFold fold = new ListBlueIdFold(ListBlueIdFoldTest::fakeHash);

        // when
        String actual = fold.appendBlueId("prefix-id", "element-id");

        // then
        assertEquals(
                "hash({$listCons={elem={blueId=element-id}, prev={blueId=prefix-id}}})",
                actual);
    }

    @Test
    void shouldPerformExactlyOneFoldStepPerSuffixElement() {
        // given
        AtomicInteger hashCalls = new AtomicInteger();
        ListBlueIdFold fold = new ListBlueIdFold(value -> {
            hashCalls.incrementAndGet();
            return fakeHash(value);
        });

        // when
        fold.foldSuffix(
                "established-prefix",
                Arrays.asList("first", "second", "third"));

        // then
        assertEquals(3, hashCalls.get());
    }

    @Test
    void shouldPerformExactlyOneFoldStepPerAnchoredAppend() {
        // given
        AtomicInteger hashCalls = new AtomicInteger();
        ListBlueIdFold fold = new ListBlueIdFold(value -> {
            hashCalls.incrementAndGet();
            return fakeHash(value);
        });
        Map<String, Object> previous = Collections.<String, Object>singletonMap(
                "$previous",
                Collections.<String, Object>singletonMap(
                        "blueId",
                        "established-prefix"));

        // when
        fold.fold(
                Arrays.<Object>asList(previous, "first-id", "second-id"),
                String::valueOf);

        // then
        assertEquals(2, hashCalls.get());
    }

    @Test
    void shouldRecomputeOnlyTheChangedElementAndFollowingSuffix() {
        // given
        AtomicInteger hashCalls = new AtomicInteger();
        ListBlueIdFold fold = new ListBlueIdFold(value -> {
            hashCalls.incrementAndGet();
            return fakeHash(value);
        });
        String seed = fold.seedBlueId();
        String prefixBeforeReplacement = fold.foldSuffix(
                seed,
                Collections.singletonList("first"));
        String original = fold.foldSuffix(
                prefixBeforeReplacement,
                Arrays.asList("second", "third"));
        hashCalls.set(0);

        // when
        String replacedFromSuffix = fold.foldSuffix(
                prefixBeforeReplacement,
                Arrays.asList("replacement", "third"));
        int suffixFoldSteps = hashCalls.get();
        String rebuiltFromStart = fold.foldSuffix(
                seed,
                Arrays.asList("first", "replacement", "third"));

        // then
        assertEquals(rebuiltFromStart, replacedFromSuffix);
        assertEquals(2, suffixFoldSteps);
        assertNotEquals(original, replacedFromSuffix);
    }

    @Test
    void shouldFoldInlineAndReferencedElementsByTheSameElementBlueId() {
        // given
        DirectBlueIdCalculator calculator = new DirectBlueIdCalculator(
                ListBlueIdFoldTest::fakeHash);
        Map<String, Object> inline = Collections.<String, Object>singletonMap(
                "value",
                "content");
        String elementBlueId = calculator.directBlueIdFromCanonicalInput(inline);
        Map<String, Object> reference =
                Collections.<String, Object>singletonMap(
                        "blueId",
                        elementBlueId);

        // when
        String inlineListBlueId = calculator.directBlueIdFromCanonicalInput(
                Collections.<Object>singletonList(inline));
        String referenceListBlueId = calculator.directBlueIdFromCanonicalInput(
                Collections.<Object>singletonList(reference));

        // then
        assertEquals(inlineListBlueId, referenceListBlueId);
    }

    @Test
    void shouldRebuildMetadataBearingNodeAroundTheFinalListPayloadBlueId() {
        // given
        DirectBlueIdCalculator calculator = new DirectBlueIdCalculator(
                ListBlueIdFoldTest::fakeHash);
        List<Object> items = Arrays.<Object>asList("first", "second");
        Map<String, Object> listNode = new LinkedHashMap<>();
        listNode.put("name", "Named list");
        listNode.put("items", items);
        String payloadBlueId = calculator.directBlueIdFromCanonicalInput(items);

        // when
        String nodeBlueId = calculator.directBlueIdFromCanonicalInput(listNode);

        // then
        assertEquals(
                fakeHash("{items={blueId=" + payloadBlueId
                        + "}, name=Named list}"),
                nodeBlueId);
        assertNotEquals(payloadBlueId, nodeBlueId);
    }

    private static String fakeHash(Object value) {
        return "hash(" + value + ")";
    }
}
