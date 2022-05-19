package jdwp;

import java.lang.annotation.*;

/**
 * Specifies the entry class of a list field, this information is used
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface EntryClass {
    Class<? extends Value> klass();
}
