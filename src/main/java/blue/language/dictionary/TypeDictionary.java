package blue.language.dictionary;

import blue.language.model.Node;

import java.util.Optional;
import java.util.Set;

/**
 * Describes a versioned collection of known Blue types.
 *
 * <p>The language core does not know any concrete external dictionary. Generated
 * catalogs can implement this interface to tell the exporter which type BlueIds
 * are known, which historical ids map to the current id, and how to inline the
 * current definition when a receiver does not support the dictionary.</p>
 */
public interface TypeDictionary {

    String name();

    Set<String> dictionaryBlueIds();

    Optional<String> currentBlueId(String blueId);

    Optional<String> typeBlueIdFor(String currentBlueId, String dictionaryBlueId);

    Optional<Node> definition(String currentBlueId);

    default boolean supportsDictionaryBlueId(String dictionaryBlueId) {
        return dictionaryBlueIds().contains(dictionaryBlueId);
    }
}
