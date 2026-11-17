package com.liubai.diary.config;

import org.springframework.http.HttpStatus;
import java.util.Map;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }

    public static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", message); }
    public static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "not_found", "未找到此内容，或你没有访问权限。"); }
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "conflict", message); }
    public static ApiException conflict(String message, Map<String, Object> details) { return new ApiException(HttpStatus.CONFLICT, "conflict", message, details); }
    public static ApiException unavailable(String message) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", message); }
}
