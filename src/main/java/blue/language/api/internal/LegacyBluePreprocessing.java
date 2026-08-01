package blue.language.api.internal;

import blue.language.Blue;
import blue.language.identity.CanonicalJsonHasher;
import blue.language.model.Node;
import blue.language.preprocess.BluePreprocessing;
import blue.language.preprocess.StandardBluePreprocessing;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static blue.language.utils.Properties.OBJECT_BLUE;

/** Delegates preprocessing while exposing the builder-frozen environment. */
public final class LegacyBluePreprocessing implements BluePreprocessing {

    private final Blue blue;
    private final String environmentIdentity;

    /** Creates an adapter for one fully configured runtime. */
    public LegacyBluePreprocessing(
            Blue blue, Map<String, String> aliases) {
        this.blue = Objects.requireNonNull(blue, OBJECT_BLUE);
        this.environmentIdentity = environmentIdentity(aliases);
    }

    @Override
    public Node preprocess(Node source) {
        return blue.preprocess(Objects.requireNonNull(source, "source"));
    }

    @Override
    public String environmentIdentity() {
        return environmentIdentity;
    }

    private String environmentIdentity(Map<String, String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return StandardBluePreprocessing.BASELINE_ENVIRONMENT_IDENTITY;
        }
        return StandardBluePreprocessing.BASELINE_ENVIRONMENT_IDENTITY
                + "/" + new CanonicalJsonHasher().hash(
                new TreeMap<>(aliases));
    }
}
