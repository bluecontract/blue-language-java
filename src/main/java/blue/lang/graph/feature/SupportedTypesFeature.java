package blue.lang.graph.feature;

import blue.lang.graph.Feature;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class SupportedTypesFeature implements Feature {
    private Map<String, String> typeToHash;
}
