package blue.language.matching;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenSchemaMatcherTest {

    private final FrozenSchemaMatcher matcher = new FrozenSchemaMatcher();

    @Test
    void shouldCountUnicodeCodePointsForLengthConstraints() {
        // given
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                new Node().value("\uD83D\uDE00"));
        Schema schema = new Schema().minLength(1).maxLength(1);

        // when
        boolean matched = matcher.matches(candidate, schema);

        // then
        assertTrue(matched);
    }

    @Test
    void shouldFailClosedForContradictoryNumericBounds() {
        // given
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                new Node().value(new BigDecimal("5")));
        Schema schema = new Schema()
                .minimum(new BigDecimal("10"))
                .maximum(new BigDecimal("1"));

        // when
        boolean matched = matcher.matches(candidate, schema);

        // then
        assertFalse(matched);
    }

    @Test
    void shouldFailClosedWhenNumericKeywordTargetsWrongPayloadKind() {
        // given
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                new Node().value("not-a-number"));
        Schema schema = new Schema().minimum(BigDecimal.ZERO);

        // when
        boolean matched = matcher.matches(candidate, schema);

        // then
        assertFalse(matched);
    }
}
