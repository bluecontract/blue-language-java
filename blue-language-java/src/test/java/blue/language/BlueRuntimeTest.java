package blue.language;

import blue.language.codec.BlueFormat;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.mapping.BlueMapper;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.registry.RuntimeTypeAliases;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlueRuntimeTest {

    @Test
    void shouldComposeLanguageContractsAndMappingServices() {
        // given
        Node providerContent = new Node().value("provider-content");
        String providerBlueId = DirectBlueIdCalculator.calculateBlueId(
                providerContent);

        // when
        try (BlueRuntime runtime = BlueRuntime.builder()
                .nodeProvider(blueId -> providerBlueId.equals(blueId)
                        ? Collections.singletonList(
                        providerContent.clone())
                        : null)
                .build()) {
            Node loaded = runtime.language().snapshots()
                    .load(providerBlueId).canonicalRoot();
            Node mapped = runtime.mapping().toNode("mapped");
            DocumentProcessingResult processed =
                    runtime.contracts().process(
                            new Node().value("root"),
                            new Node().value("event"));

            // then
            assertEquals("provider-content", loaded.getValue());
            assertEquals("mapped", mapped.getValue());
            assertNotNull(processed.status());
        }
    }

    @Test
    void shouldImportVerifiedContractsRuntimeAliases() {
        // given
        String sourceYaml = "entry:\n  type: Channel\n";

        // when
        Node preprocessed;
        try (BlueRuntime runtime = BlueRuntime.builder().build()) {
            Node source = runtime.language().codec().parseSource(
                    sourceYaml, BlueFormat.YAML);
            preprocessed = runtime.language().preprocessing()
                    .preprocess(source);
        }

        // then
        assertEquals(
                RuntimeTypeAliases.NAME_TO_BLUE_ID.get("Channel"),
                preprocessed.getProperties().get("entry")
                        .getType().getBlueId());
    }

    @Test
    void shouldCloseContractsBeforeLanguageAndRejectLaterAccess() {
        // given
        BlueRuntime runtime = BlueRuntime.builder().build();
        BlueContracts contracts = runtime.contracts();
        BlueLanguage language = runtime.language();
        BlueMapper mapping = runtime.mapping();

        // when
        runtime.close();
        runtime.close();

        // then
        assertTrue(runtime.isClosed());
        assertTrue(contracts.isClosed());
        assertTrue(language.isClosed());
        assertNotNull(mapping);
        assertThrows(IllegalStateException.class, runtime::contracts);
        assertThrows(IllegalStateException.class, runtime::mapping);
        assertThrows(IllegalStateException.class,
                () -> language.identity().directBlueId(
                        new Node().value("closed")));
    }
}
