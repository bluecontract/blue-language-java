package blue.lang.graph.feature;

import blue.lang.graph.Feature;
import blue.lang.graph.Node;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class BlueprintFeature implements Feature {
    private Map<String, Object> features;
}