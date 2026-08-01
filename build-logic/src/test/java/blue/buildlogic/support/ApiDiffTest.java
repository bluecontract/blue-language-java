package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class ApiDiffTest {

    @Test
    void shouldClassifyAndOrderApiChangesDeterministically() {
        // given
        java.util.List<String> baseline = Arrays.asList("zeta", "shared", "alpha", "# comment");
        java.util.List<String> current = Arrays.asList("omega", "shared", "beta");

        // when
        ApiDiff diff = ApiDiff.compare(baseline, current);

        // then
        assertEquals(Arrays.asList("beta", "omega"), diff.getAdded());
        assertEquals(Arrays.asList("alpha", "zeta"), diff.getRemoved());
        assertEquals(Arrays.asList("shared"), diff.getUnchanged());
    }
}
