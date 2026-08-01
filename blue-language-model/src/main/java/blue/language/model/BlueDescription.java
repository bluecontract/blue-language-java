package blue.language.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a Java field to the Blue {@code description} metadata of another
 * property named by {@link #value()}.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BlueDescription {

    /**
     * Selects the Java field whose Blue node receives the description.
     *
     * @return target Java field name
     */
    String value();
}
