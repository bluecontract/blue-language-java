package blue.language.preprocess;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class StandardBluePreprocessingTest {

    @Test
    void shouldApplyMandatoryBaselineWithoutMutatingSource() {
        // given
        Node source = YAML_MAPPER.readValue(
                "type: Text\nvalue: hello", Node.class);
        BluePreprocessing preprocessing =
                new StandardBluePreprocessing();

        // when
        Node preprocessed = preprocessing.preprocess(source);

        // then
        assertNotSame(source, preprocessed);
        assertEquals("Text", source.getType().getValue());
        assertEquals(TEXT_TYPE_BLUE_ID,
                preprocessed.getType().getBlueId());
    }

    @Test
    void shouldExposeStableBaselineEnvironmentIdentity() {
        // given
        BluePreprocessing first = new StandardBluePreprocessing();
        BluePreprocessing second = new StandardBluePreprocessing();

        // when
        String firstIdentity = first.environmentIdentity();
        String secondIdentity = second.environmentIdentity();

        // then
        assertEquals(
                StandardBluePreprocessing.BASELINE_ENVIRONMENT_IDENTITY,
                firstIdentity);
        assertEquals(firstIdentity, secondIdentity);
    }
}
