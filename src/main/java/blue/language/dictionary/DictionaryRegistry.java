package blue.language.dictionary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mutable registration index for named {@link TypeDictionary} instances.
 *
 * <p>Names are unique. Read APIs return snapshots or optionals so callers
 * cannot mutate the registry's internal insertion order.</p>
 */
public final class DictionaryRegistry {

    private final Map<String, TypeDictionary> dictionariesByName = new LinkedHashMap<>();

    /** Creates an empty insertion-ordered dictionary registry. */
    public DictionaryRegistry() {
    }

    /**
     * Registers a dictionary or accepts the same instance idempotently.
     *
     * @param dictionary dictionary to register
     * @return this registry
     * @throws IllegalArgumentException for null, unnamed, or conflicting
     *                                  registrations
     */
    public DictionaryRegistry register(TypeDictionary dictionary) {
        if (dictionary == null) {
            throw new IllegalArgumentException("dictionary must not be null");
        }
        String name = dictionary.name();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("dictionary name must not be empty");
        }
        TypeDictionary existing = dictionariesByName.get(name);
        if (existing != null && existing != dictionary) {
            throw new IllegalArgumentException("Duplicate dictionary name: " + name);
        }
        dictionariesByName.put(name, dictionary);
        return this;
    }

    /**
     * Registers each dictionary in collection iteration order.
     *
     * <p>A {@code null} collection is a no-op. If a later registration fails,
     * registrations completed earlier in the iteration remain in this
     * registry.</p>
     *
     * @param dictionaries dictionaries to register, or {@code null}
     * @return this registry
     * @throws IllegalArgumentException when an element is null, unnamed, or
     *                                  conflicts with an existing registration
     */
    public DictionaryRegistry registerAll(Collection<? extends TypeDictionary> dictionaries) {
        if (dictionaries == null) {
            return this;
        }
        for (TypeDictionary dictionary : dictionaries) {
            register(dictionary);
        }
        return this;
    }

    /**
     * Looks up a dictionary by its exact registered name.
     *
     * @param name dictionary name; {@code null} produces an empty result
     * @return the registered dictionary, or an empty optional
     */
    public Optional<TypeDictionary> dictionary(String name) {
        return Optional.ofNullable(dictionariesByName.get(name));
    }

    /**
     * Returns an insertion-ordered snapshot of registered dictionaries.
     *
     * @return unmodifiable snapshot independent of later registrations
     */
    public Collection<TypeDictionary> dictionaries() {
        return Collections.unmodifiableList(new ArrayList<>(dictionariesByName.values()));
    }

    /**
     * Finds the first registered dictionary that recognizes a historical or
     * current type BlueId.
     *
     * @param blueId historical or current type identity
     * @return owning dictionary and normalized current identity, or an empty
     *         optional when the identity is null, empty, or unknown
     */
    public Optional<OwnedType> typeOwner(String blueId) {
        if (blueId == null || blueId.isEmpty()) {
            return Optional.empty();
        }
        for (TypeDictionary dictionary : dictionariesByName.values()) {
            Optional<String> currentBlueId = dictionary.currentBlueId(blueId);
            if (currentBlueId.isPresent()) {
                return Optional.of(new OwnedType(dictionary, currentBlueId.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Tests whether this registry has no dictionaries.
     *
     * @return whether the registry is empty
     */
    public boolean isEmpty() {
        return dictionariesByName.isEmpty();
    }

    /**
     * Dictionary ownership plus the dictionary's normalized current type
     * identity.
     */
    public static final class OwnedType {
        private final TypeDictionary dictionary;
        private final String currentBlueId;

        private OwnedType(TypeDictionary dictionary, String currentBlueId) {
            this.dictionary = dictionary;
            this.currentBlueId = currentBlueId;
        }

        /**
         * Returns the registered dictionary that owns the type.
         *
         * @return owning dictionary
         */
        public TypeDictionary dictionary() {
            return dictionary;
        }

        /**
         * Returns the dictionary's normalized current type identity.
         *
         * @return current type BlueId
         */
        public String currentBlueId() {
            return currentBlueId;
        }
    }
}
