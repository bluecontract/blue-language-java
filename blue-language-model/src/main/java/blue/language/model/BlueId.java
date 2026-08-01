package blue.language.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a Java field to or from a pure BlueId reference for the named property.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BlueId {

    /**
     * Selects the target property name.
     *
     * @return target property name; empty uses the annotated field
     */
    String value() default "";
}
