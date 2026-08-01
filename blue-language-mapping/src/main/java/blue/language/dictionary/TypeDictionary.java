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

    /**
     * Returns the stable registry name used in {@link ExportContext}.
     *
     * @return nonblank dictionary name
     */
    String name();

    /**
     * Returns all dictionary packages this implementation can target.
     *
     * @return nonnull set of supported dictionary package BlueIds
     */
    Set<String> dictionaryBlueIds();

    /**
     * Normalizes a historical or current type identity.
     *
     * @param blueId type identity to resolve
     * @return current type BlueId, or an empty optional when unrecognized
     */
    Optional<String> currentBlueId(String blueId);

    /**
     * Translates a current type identity to a target dictionary package.
     *
     * @param currentBlueId normalized current type BlueId
     * @param dictionaryBlueId target dictionary package BlueId
     * @return equivalent target type BlueId, or an empty optional when the
     *         target package cannot represent the type
     */
    Optional<String> typeBlueIdFor(String currentBlueId, String dictionaryBlueId);

    /**
     * Returns the canonical current definition used for inline fallback.
     *
     * <p>The exporter clones a returned definition before transforming it.</p>
     *
     * @param currentBlueId normalized current type BlueId
     * @return current definition, or an empty optional when none is available
     */
    Optional<Node> definition(String currentBlueId);

    /**
     * Tests whether this dictionary can target a package identity.
     *
     * @param dictionaryBlueId dictionary package BlueId
     * @return whether the identity is included in {@link #dictionaryBlueIds()}
     */
    default boolean supportsDictionaryBlueId(String dictionaryBlueId) {
        return dictionaryBlueIds().contains(dictionaryBlueId);
    }
}
