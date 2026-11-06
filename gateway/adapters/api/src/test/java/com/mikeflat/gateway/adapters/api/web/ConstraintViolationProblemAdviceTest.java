package com.mikeflat.gateway.adapters.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ConstraintViolationProblemAdviceTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @SuppressWarnings("unused")
    static final class Paging {
        void page(@Min(0) Integer page, @Min(1) @Max(100) Integer size) {
        }
    }

    @Test
    void violated_parameter_is_bad_request_naming_the_parameter() throws NoSuchMethodException {
        Method page = Paging.class.getDeclaredMethod("page", Integer.class, Integer.class);
        var violations = VALIDATOR.forExecutables().validateParameters(new Paging(), page, new Object[] {-1, 101});

        ProblemDetail problem = new ConstraintViolationProblemAdvice()
                .invalidParameter(new ConstraintViolationException(violations));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail())
                .contains("page: must be greater than or equal to 0")
                .contains("size: must be less than or equal to 100");
    }
}
