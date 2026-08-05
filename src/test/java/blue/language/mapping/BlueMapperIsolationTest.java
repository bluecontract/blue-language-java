package blue.language.mapping;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves immutable mapper configuration and mapping-boundary behavior. */
final class BlueMapperIsolationTest {

    private static final String SHARED_TYPE_BLUE_ID =
            "Mapper-Isolation-Type";
    private static final String ROUND_TRIP_TYPE_BLUE_ID =
            "Mapper-Round-Trip-Type";
    private static final String LEFT_FACTORY = "left-factory";
    private static final String RIGHT_FACTORY = "right-factory";
    private static final String EXAMPLE_VALUE = "example";

    @Test
    void shouldKeepTypeMappingsIndependentBetweenMapperInstances() {
        // given
        BlueMapper left = BlueMapper.builder()
                .register(SHARED_TYPE_BLUE_ID, LeftMappedValue.class)
                .build();
        BlueMapper right = BlueMapper.builder()
                .register(SHARED_TYPE_BLUE_ID, RightMappedValue.class)
                .build();
        Node source = new Node()
                .type(new Node().blueId(SHARED_TYPE_BLUE_ID));

        // when
        Object leftValue = left.fromNode(source, Object.class);
        Object rightValue = right.fromNode(source, Object.class);

        // then
        assertTrue(leftValue instanceof LeftMappedValue);
        assertTrue(rightValue instanceof RightMappedValue);
        assertEquals(
                LeftMappedValue.class,
                left.mappedClass(source).orElse(null));
        assertEquals(
                RightMappedValue.class,
                right.mappedClass(source).orElse(null));
    }

    @Test
    void shouldKeepObjectFactoriesIndependentBetweenMapperInstances() {
        // given
        BlueMapper left = BlueMapper.builder()
                .register(
                        FactoryValue.class,
                        LeftFactoryValue::new)
                .build();
        BlueMapper right = BlueMapper.builder()
                .register(
                        FactoryValue.class,
                        RightFactoryValue::new)
                .build();
        Node source = new Node();

        // when
        FactoryValue leftValue = left.fromNode(
                source,
                FactoryValue.class);
        FactoryValue rightValue = right.fromNode(
                source,
                FactoryValue.class);

        // then
        assertEquals(LEFT_FACTORY, leftValue.origin());
        assertEquals(RIGHT_FACTORY, rightValue.origin());
    }

    @Test
    void shouldRoundTripAnnotatedObjectsThroughOneMapper() {
        // given
        BlueMapper mapper = BlueMapper.builder()
                .register(RoundTripValue.class)
                .build();
        RoundTripValue source = new RoundTripValue();
        source.message = EXAMPLE_VALUE;

        // when
        Node node = mapper.toNode(source);
        RoundTripValue converted = mapper.convert(
                node,
                RoundTripValue.class);

        // then
        assertEquals(
                ROUND_TRIP_TYPE_BLUE_ID,
                node.getType().getBlueId());
        assertEquals(EXAMPLE_VALUE, converted.message);
        assertEquals(
                RoundTripValue.class,
                mapper.mappedClass(ROUND_TRIP_TYPE_BLUE_ID)
                        .orElse(null));
        assertFalse(mapper.mappedClass("Unregistered-Type").isPresent());
    }

    /** First class used for a mapper-local Blue type mapping. */
    public static final class LeftMappedValue {
        /** Creates a value for reflective mapping. */
        public LeftMappedValue() {
        }
    }

    /** Second class used for the same BlueId in another mapper. */
    public static final class RightMappedValue {
        /** Creates a value for reflective mapping. */
        public RightMappedValue() {
        }
    }

    /** Value whose constructor is supplied by a mapper-owned factory. */
    public abstract static class FactoryValue {
        /** Returns the mapper-specific construction marker. */
        public abstract String origin();
    }

    /** Factory product used only by the left mapper. */
    private static final class LeftFactoryValue extends FactoryValue {
        @Override
        public String origin() {
            return LEFT_FACTORY;
        }
    }

    /** Factory product used only by the right mapper. */
    private static final class RightFactoryValue extends FactoryValue {
        @Override
        public String origin() {
            return RIGHT_FACTORY;
        }
    }

    /** Annotated object used to prove mapper serialization round trips. */
    @TypeBlueId(ROUND_TRIP_TYPE_BLUE_ID)
    public static final class RoundTripValue {
        private String message;

        /** Creates a value for reflective mapping. */
        public RoundTripValue() {
        }
    }
}
