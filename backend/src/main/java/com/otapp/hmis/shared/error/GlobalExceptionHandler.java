package com.otapp.hmis.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The SINGLE {@code @RestControllerAdvice} for the application (ADR-0014 §6).
 *
 * <p>All errors are rendered as RFC 7807 {@link ProblemDetail} ({@code application/problem+json}).
 * The {@code type} URI is the {@link ErrorCode}'s stable URN. No custom {@code ApiError} body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    /**
     * No route / static resource matched the request — Spring throws
     * {@link NoResourceFoundException} (or {@link NoHandlerFoundException}). Render a clean 404
     * rather than letting the catch-all below map it to a 500 (the latter is misleading and
     * leaks the framework exception detail).
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ProblemDetail> handleNoHandler(Exception ex, HttpServletRequest request) {
        return toResponse(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.title(), request);
    }

    /**
     * Wrong HTTP method for an existing route → 405 (not a 500). Detail is the framework message
     * which contains only method names, no PHI.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED, "Request method not supported");
        problem.setType(URI.create("urn:hmis:error:method-not-allowed"));
        problem.setTitle("Method not allowed");
        problem.setProperty("code", "METHOD_NOT_ALLOWED");
        if (request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(problem);
    }

    /**
     * Catch-all for any unhandled exception. Returns a generic 500 with the {@link ErrorCode}
     * title — the raw {@code ex.getMessage()} is NOT echoed to the client (it can leak internal
     * or financial/PHI detail, e.g. a UNIQUE-constraint violation message); it is logged
     * server-side instead.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        String path = request != null ? request.getRequestURI() : "?";
        log.error("Unexpected error handling {}: {}", path, ex.toString(), ex);
        return toResponse(ErrorCode.INTERNAL, ErrorCode.INTERNAL.title(), request);
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
