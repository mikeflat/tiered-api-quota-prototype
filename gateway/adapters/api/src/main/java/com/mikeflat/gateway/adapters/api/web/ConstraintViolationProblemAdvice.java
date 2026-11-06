package com.mikeflat.gateway.adapters.api.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminController.class)
public class ConstraintViolationProblemAdvice {

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail invalidParameter(ConstraintViolationException violations) {
        String detail = violations.getConstraintViolations().stream()
                .map(ConstraintViolationProblemAdvice::describe)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    private static String describe(ConstraintViolation<?> violation) {
        return parameterName(violation.getPropertyPath()) + ": " + violation.getMessage();
    }

    private static String parameterName(Path path) {
        return StreamSupport.stream(path.spliterator(), false)
                .reduce((first, second) -> second)
                .map(Path.Node::getName)
                .orElse("parameter");
    }
}
