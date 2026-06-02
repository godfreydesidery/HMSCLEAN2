package com.otapp.hmis.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The SINGLE {@code @RestControllerAdvice} for the application (ADR-0014 §6).
 *
 * <p>All errors are rendered as RFC 7807 {@link ProblemDetail} ({@code application/problem+json}).
 * The {@code type} URI is the {@link ErrorCode}'s stable URN. No custom {@code ApiError} body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HmisException.class)
    public ResponseEntity<ProblemDetail> handleHmis(HmisException ex, HttpServletRequest request) {
        return toResponse(ex.errorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        return toResponse(ErrorCode.INVALID_CREDENTIALS, ex.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return toResponse(ErrorCode.UNAUTHENTICATED, ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return toResponse(ErrorCode.FORBIDDEN, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ResponseEntity<ProblemDetail> response = toResponse(ErrorCode.VALIDATION, "Validation failed", request);
        ProblemDetail body = response.getBody();
        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.add(Map.of(
                    "field", fieldError.getField(),
                    "code", fieldError.getCode() != null ? fieldError.getCode() : "invalid",
                    "message", fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "invalid"));
        }
        if (body != null) {
            body.setProperty("errors", errors);
        }
        return response;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        return toResponse(ErrorCode.INTERNAL, ex.getMessage(), request);
    }

    private ResponseEntity<ProblemDetail> toResponse(ErrorCode code, String detail, HttpServletRequest request) {
        HttpStatus status = code.status();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail != null ? detail : code.title());
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        if (request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        return ResponseEntity.status(status).body(problem);
    }
}
