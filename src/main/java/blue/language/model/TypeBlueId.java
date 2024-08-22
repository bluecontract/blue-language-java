package blue.language.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TypeBlueId {
    String[] value() default {};
    String defaultValue() default "";
    String defaultValueRepositoryLocation() default "blue-preprocessed";
    String defaultValuePropertyFile() default "blue-ids.yaml";
    String defaultValueRepositoryDir() default "";
    String defaultValueRepositoryKey() default "";
}