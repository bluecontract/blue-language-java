package blue.language.snapshot;

import blue.language.merge.ResolvedReferenceCache;
import blue.language.model.Node;
import blue.language.model.Schema;
import org.junit.jupiter.api.Test;

import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.*;

final class SchemaStructuralProvenanceTest {
    @Test
    void shouldKeepWireEquivalentSchemaRepresentationsSeparateInStructuralCache() {
        // given
        try (ResolvedReferenceCache cache = new ResolvedReferenceCache()) {
            Node implicit = new Node().schema(new Schema().minLength(new Node().value(1)));
            Node explicit = new Node().schema(new Schema().minLength(
                    new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID)).value(1)));
            // when
            FrozenNode first = cache.freezeResolved(implicit);
            FrozenNode second = cache.freezeResolved(explicit);
            FrozenNode repeated = cache.freezeResolved(explicit.clone());
            // then
            assertNotSame(first, second);
            assertNull(first.toNode().getSchema().getMinLength().getType());
            assertEquals(INTEGER_TYPE_BLUE_ID, second.toNode().getSchema().getMinLength().getType().getBlueId());
            assertSame(second, repeated);
        }
    }
}
