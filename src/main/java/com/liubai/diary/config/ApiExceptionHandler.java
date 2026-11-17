package com.liubai.diary.config;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> apiError(ApiException error) {
        Map<String, Object> response = body(error.getStatus(), error.getCode(), error.getMessage());
        response.putAll(error.getDetails());
        return ResponseEntity.status(error.getStatus()).body(response);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<Map<String, Object>> validation(Exception error) {
        String message = "请检查填写内容。";
        if (error instanceof MethodArgumentNotValidException invalid && invalid.getBindingResult().getFieldError() != null) {
            message = invalid.getBindingResult().getFieldError().getDefaultMessage();
        }
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, "invalid_request", message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestValueException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> malformedRequest() {
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, "invalid_request", "请求参数缺失或格式不正确。"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(body(HttpStatus.PAYLOAD_TOO_LARGE, "upload_too_large", "上传内容超过大小限制。"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, Object>> duplicate() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(HttpStatus.CONFLICT, "already_exists", "邮箱、用户名或操作身份已存在。"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(HttpStatus.FORBIDDEN, "forbidden", "你没有执行此操作的权限。"));
    }

    private Map<String, Object> body(HttpStatus status, String code, String message) {
        return new java.util.HashMap<>(Map.of("status", status.value(), "code", code, "message", message, "time", Instant.now().toString()));
    }
}
