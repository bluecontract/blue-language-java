package blue.language.processor;

import blue.language.mapping.TypeClassResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DocumentProcessorDefaultTypeResolverTest {

    private static final String DEFAULT_CONTRACT_MODEL_PACKAGE =
            "blue.language.processor.model";
    private static final String ISOLATED_TEST_BLUE_ID =
            "document-processor-default-resolver-isolation";

    @Test
    void shouldReturnDetachedDefaultResolverViews() {
        // given
        Map<String, Class<?>> expected =
                new TreeMap<>(
                        new TypeClassResolver(
                                DEFAULT_CONTRACT_MODEL_PACKAGE)
                                .getBlueIdMap());
        ContractProcessorRegistry emptyRegistry =
                ContractProcessorRegistryBuilder.create()
                        .build();

        // when
        try (DocumentProcessor first =
                     DocumentProcessor.builder()
                             .runtimeRegistry(emptyRegistry)
                             .build();
             DocumentProcessor second =
                     DocumentProcessor.builder()
                             .runtimeRegistry(
                                     ContractProcessorRegistryBuilder
                                             .create()
                                             .build())
                             .build()) {
            Map<String, Class<?>> firstMappings =
                    new TreeMap<>(
                            first.administration().contractTypeResolver()
                                    .getBlueIdMap());
            Map<String, Class<?>> secondMappings =
                    new TreeMap<>(
                            second.administration().contractTypeResolver()
                                    .getBlueIdMap());
            TypeClassResolver detachedFirstResolver =
                    first.administration().contractTypeResolver();
            detachedFirstResolver.register(
                    ISOLATED_TEST_BLUE_ID,
                    String.class);

            // then
            assertFalse(expected.isEmpty());
            assertEquals(expected, firstMappings);
            assertEquals(expected, secondMappings);
            assertSame(
                    String.class,
                    detachedFirstResolver
                            .resolveClass(
                                    ISOLATED_TEST_BLUE_ID));
            assertNull(
                    first.administration().contractTypeResolver()
                            .resolveClass(
                                    ISOLATED_TEST_BLUE_ID));
            assertFalse(
                    second.administration().contractTypeResolver()
                            .getBlueIdMap()
                            .containsKey(
                                    ISOLATED_TEST_BLUE_ID));
        }
    }
}
