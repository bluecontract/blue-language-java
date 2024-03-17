package blue.lang.feature;

import blue.lang.Feature;

import java.util.Map;

public class SupportedTypesFeature implements Feature {
    private Map<String, String> typeToHash;

    public SupportedTypesFeature(Map<String, String> typeToHash) {
        this.typeToHash = typeToHash;
    }

    public Map<String, String> getTypeToHash() {
        return typeToHash;
    }

    public void setTypeToHash(Map<String, String> typeToHash) {
        this.typeToHash = typeToHash;
    }
}
