package blue.language.dictionary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class DictionaryRegistry {

    private final Map<String, TypeDictionary> dictionariesByName = new LinkedHashMap<>();

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

    public DictionaryRegistry registerAll(Collection<? extends TypeDictionary> dictionaries) {
        if (dictionaries == null) {
            return this;
        }
        for (TypeDictionary dictionary : dictionaries) {
            register(dictionary);
        }
        return this;
    }

    public Optional<TypeDictionary> dictionary(String name) {
        return Optional.ofNullable(dictionariesByName.get(name));
    }

    public Collection<TypeDictionary> dictionaries() {
        return Collections.unmodifiableList(new ArrayList<>(dictionariesByName.values()));
    }

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

    public boolean isEmpty() {
        return dictionariesByName.isEmpty();
    }

    public static final class OwnedType {
        private final TypeDictionary dictionary;
        private final String currentBlueId;

        private OwnedType(TypeDictionary dictionary, String currentBlueId) {
            this.dictionary = dictionary;
            this.currentBlueId = currentBlueId;
        }

        public TypeDictionary dictionary() {
            return dictionary;
        }

        public String currentBlueId() {
            return currentBlueId;
        }
    }
}
