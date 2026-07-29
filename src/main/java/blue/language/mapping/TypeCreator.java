package blue.language.mapping;

/**
 * Factory used when reflective no-argument construction is unavailable or
 * undesirable.
 *
 * @param <T> constructed Java type
 */
public interface TypeCreator<T> {

    /**
     * Creates a fresh instance.
     *
     * @return fresh instance
     */
    T create();
}
