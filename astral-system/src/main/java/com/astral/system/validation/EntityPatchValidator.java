package com.astral.system.validation;

import com.astral.common.exception.BusinessException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.stereotype.Component;

/** Applies generated entity constraints only to non-null fields in a partial write's whitelist. */
@Component
@RequiredArgsConstructor
public class EntityPatchValidator {
    private final Validator validator;

    public void validate(Object patch, String... writableProperties) {
        var properties = PropertyAccessorFactory.forBeanPropertyAccess(patch);
        for (String property : writableProperties) {
            if (properties.getPropertyValue(property) == null) continue;
            var violations = validator.validateProperty(patch, property);
            if (!violations.isEmpty()) {
                String message = violations.stream()
                        .map(violation -> property + ": " + violation.getMessage())
                        .sorted()
                        .findFirst()
                        .orElseThrow();
                throw BusinessException.of(320, message);
            }
        }
    }
}
