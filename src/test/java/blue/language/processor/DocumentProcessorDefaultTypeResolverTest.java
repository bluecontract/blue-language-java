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
                     new DocumentProcessor(emptyRegistry);
             DocumentProcessor second =
                     new DocumentProcessor(
                             ContractProcessorRegistryBuilder
                                     .create()
                                     .build())) {
            Map<String, Class<?>> firstMappings =
                    new TreeMap<>(
                            first.getContractTypeResolver()
                                    .getBlueIdMap());
            Map<String, Class<?>> secondMappings =
                    new TreeMap<>(
                            second.getContractTypeResolver()
                                    .getBlueIdMap());
            TypeClassResolver detachedFirstResolver =
                    first.getContractTypeResolver();
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
                    first.getContractTypeResolver()
                            .resolveClass(
                                    ISOLATED_TEST_BLUE_ID));
            assertFalse(
                    second.getContractTypeResolver()
                            .getBlueIdMap()
                            .containsKey(
                                    ISOLATED_TEST_BLUE_ID));
        }
    }
}
