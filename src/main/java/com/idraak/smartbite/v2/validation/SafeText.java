package com.idraak.smartbite.v2.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Text without control characters or {@code < > { } [ ]}. User strings are embedded in prompts as JSON data; this
 * removes the characters most useful for breaking out of that structure. Combine with {@code @Size}.
 */
@Pattern(regexp = "^[^\\p{Cntrl}<>{}\\[\\]]*$")
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeText {

    String message() default "contains characters that aren't allowed";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
