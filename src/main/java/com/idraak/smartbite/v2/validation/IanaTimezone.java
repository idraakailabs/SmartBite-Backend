package com.idraak.smartbite.v2.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A region-based IANA timezone id such as {@code Asia/Karachi}. Fixed offsets like {@code +05:00} are rejected. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = IanaTimezone.Validator.class)
public @interface IanaTimezone {

    String message() default "is not a known IANA timezone";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<IanaTimezone, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) return true;
            return parse(value) != null;
        }

        public static ZoneId parse(String value) {
            if (value == null || value.length() < 3 || value.length() > 64) return null;
            try {
                ZoneId zone = ZoneId.of(value);
                return zone instanceof ZoneOffset ? null : zone;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

}
