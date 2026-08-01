package blue.language.identity;

import blue.language.utils.Base58Sha256Provider;

import java.util.function.Function;

/**
 * Hashes RFC 8785 canonical JSON bytes with SHA-256 and encodes the digest in
 * canonical Base58 form.
 *
 * <p>Identity input construction is deliberately outside this class. It sees
 * only an already normalized JSON-compatible value.</p>
 */
public final class CanonicalJsonHasher implements Function<Object, String> {

    private final Base58Sha256Provider provider;

    /** Creates the stateless canonical JSON hasher. */
    public CanonicalJsonHasher() {
        this.provider = new Base58Sha256Provider();
    }

    /**
     * Hashes one canonical JSON-compatible value.
     *
     * @param canonicalValue normalized identity value
     * @return canonical Base58 SHA-256 digest
     */
    public String hash(Object canonicalValue) {
        return provider.applyCanonicalValue(canonicalValue);
    }

    @Override
    public String apply(Object canonicalValue) {
        return hash(canonicalValue);
    }
}
