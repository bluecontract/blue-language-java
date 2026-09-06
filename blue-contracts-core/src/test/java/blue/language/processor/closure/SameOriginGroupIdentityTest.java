package blue.language.processor.closure;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SameOriginGroupIdentityTest {
    private static final DocumentId A = new DocumentId("A"), B = new DocumentId("B"), C = new DocumentId("C"), D = new DocumentId("D");
    private static final String SA = hash('a'), SB = hash('b'), SC = hash('c'), S1 = hash('1'), S2 = hash('2');

    @Test void singletonPrecutSccRetainsOriginalSeedIdentity() {
        assertEquals(SA, SameOriginGroupIdentity.of(seeds(A, SA, B, SA), Collections.emptyList()).identity());
        assertEquals(SA, SameOriginGroupIdentity.of(seeds(A, SA), Collections.emptyList()).identity());
    }

    @Test void consumedDirectedSourceOperationsChangeSettlementNotOriginalSeed() {
        String bFailed = hash('8'), bOther = hash('9');
        String aAfterB = SameOriginGroupIdentity.of(seeds(A, SA), Collections.emptyList(), seeds(B, bFailed)).identity();
        assertNotEquals(SA, aAfterB);
        assertNotEquals(aAfterB, SameOriginGroupIdentity.of(seeds(A, SA), Collections.emptyList(), seeds(B, bOther)).identity());
        String cAfterA = SameOriginGroupIdentity.of(seeds(C, SC), Collections.emptyList(), seeds(A, aAfterB)).identity();
        assertNotEquals(cAfterA, SameOriginGroupIdentity.of(seeds(C, SC), Collections.emptyList(), seeds(A, aAfterB, B, bFailed)).identity(),
                "C binds B only if it directly consumes B; discarded attempt dependencies cannot leak");
        assertThrows(IllegalArgumentException.class, () -> SameOriginGroupIdentity.of(seeds(A, SA), Collections.emptyList(), seeds(A, SA)));
    }

    @Test void inputMapAndMemberIterationOrderCannotReanchorSettlement() {
        String first = group(seeds(A, SA, B, SB, C, SC), join(S1, A, B), join(S2, A, B, C));
        String reordered = group(seeds(C, SC, B, SB, A, SA), join(S1, B, A), join(S2, C, B, A));
        assertEquals(first, reordered);
        assertNotEquals(first, SA); assertNotEquals(first, SB);
    }

    @Test void acceptedSiteOrderAndParticipatingGroupsAreSemantic() {
        Map<DocumentId, String> seeds = seeds(A, SA, B, SB, C, SC);
        String abThenC = group(seeds, join(S1, A, B), join(S2, A, B, C));
        assertNotEquals(abThenC, group(seeds, join(S1, B, C), join(S2, A, B, C)));
        assertNotEquals(abThenC, group(seeds, join(S2, A, B), join(S1, A, B, C)));
        assertNotEquals(abThenC, group(seeds, join(S1, A, B, C)));
        assertNotEquals(abThenC, group(seeds(A, SA, B, SC, C, SB), join(S1, A, B), join(S2, A, B, C)));
    }

    @Test void splitOriginalOrPreviouslyAdmittedGroupsAndNoopsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> group(seeds(A, SA, B, SA, C, SC), join(S1, A, C)));
        assertThrows(IllegalArgumentException.class, () -> group(seeds(A, SA, B, SB, C, SC), join(S1, A, B), join(S2, A, C)));
        assertThrows(IllegalArgumentException.class, () -> group(seeds(A, SA, B, SB), join(S1, A, B), join(S2, A, B)));
        assertThrows(IllegalArgumentException.class, () -> group(seeds(A, SA, B, SB, C, SC), join(S1, A, B), join(S1, A, B, C)));
    }

    @Test void unjoinedForeignAndDuplicateMembersCannotEnterOneOperation() {
        assertThrows(IllegalArgumentException.class, () -> group(seeds(A, SA, B, SB)));
        assertThrows(IllegalArgumentException.class, () -> group(seeds(A, SA, B, SB, C, SC), join(S1, A, B)));
        assertThrows(IllegalArgumentException.class, () -> group(seeds(A, SA, B, SB), join(S1, A, D)));
        assertThrows(IllegalArgumentException.class, () -> join(S1, A, A));
        assertThrows(IllegalArgumentException.class, () -> group(Collections.emptyMap()));
    }

    private static SameOriginGroupIdentity.Admission join(String site, DocumentId... members) {
        return new SameOriginGroupIdentity.Admission(site, Arrays.asList(members));
    }
    private static String group(Map<DocumentId, String> seeds, SameOriginGroupIdentity.Admission... admissions) {
        return SameOriginGroupIdentity.of(seeds, Arrays.asList(admissions)).identity();
    }
    private static Map<DocumentId, String> seeds(Object... entries) {
        Map<DocumentId, String> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((DocumentId) entries[i], (String) entries[i + 1]); return result;
    }
    private static String hash(char c) { char[] value = new char[64]; Arrays.fill(value, c); return "sha256:" + new String(value); }
}
