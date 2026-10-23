package com.learningmaterial.system.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> api(ApiException ex, HttpServletRequest request) {
        return error(ex.status(), ex.code(), ex.getMessage(), request, null,
            ex.currentVersion(), ex.existingMaterialId());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "文件超过上传上限", request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(item ->
            fields.putIfAbsent(item.getField(), item.getDefaultMessage()));
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT", "请检查输入字段", request, fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> unreadable(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "BAD_JSON", "请求格式错误", request, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> badQuery(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "BAD_QUERY", "查询参数格式错误", request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected API failure: requestId={} type={}",
            RequestIdFilter.requestId(request), ex.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时无法处理请求", request, null);
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message,
                                    HttpServletRequest request, Map<String, String> fields) {
        return error(status, code, message, request, fields, null);
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message,
                                    HttpServletRequest request, Map<String, String> fields, Integer currentVersion) {
        return error(status, code, message, request, fields, currentVersion, null);
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message,
                                    HttpServletRequest request, Map<String, String> fields, Integer currentVersion,
                                    String existingMaterialId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        if (fields != null && !fields.isEmpty()) detail.put("fields", fields);
        if (currentVersion != null) detail.put("currentVersion", currentVersion);
        if (existingMaterialId != null) detail.put("existingMaterialId", existingMaterialId);
        return ResponseEntity.status(status).body(Map.of(
            "error", detail, "requestId", RequestIdFilter.requestId(request)));
    }
}
