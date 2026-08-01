package blue.language.mapping;

import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

/** Mapping-owned Jackson configuration for arbitrary annotated Java objects. */
final class MappingObjectMapper extends UncheckedObjectMapper {

    /** Shared immutable-process configuration used only by object mapping. */
    static final MappingObjectMapper JSON_MAPPER =
            new MappingObjectMapper();

    private MappingObjectMapper() {
        super(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
        SimpleModule module = new SimpleModule();
        module.setSerializerModifier(
                new BlueAnnotationsBeanSerializerModifier());
        registerModule(module);
    }
}
