package com.campusdeal.config;

import com.campusdeal.dto.Result;
import com.campusdeal.exception.ApiException;
import com.campusdeal.security.SensitiveLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Result> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(Result.fail(e.getErrorCode(), SensitiveLogSanitizer.redact(e.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail("INVALID_ARGUMENT", "请求参数不合法"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.debug("参数类型错误: {}", e.getName());
        return ResponseEntity.badRequest().body(Result.fail("INVALID_ARGUMENT", "参数类型错误"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result> handleNotReadable(HttpMessageNotReadableException e) {
        log.debug("请求参数不合法");
        return ResponseEntity.badRequest().body(Result.fail("INVALID_ARGUMENT", "请求参数不合法"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result> handleMissingParam(MissingServletRequestParameterException e) {
        log.debug("缺少必要参数: {}", e.getParameterName());
        return ResponseEntity.badRequest().body(Result.fail("INVALID_ARGUMENT", "缺少必要参数: " + e.getParameterName()));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result> handleMultipart(MultipartException e) {
        log.debug("请求格式错误（需 multipart）");
        return ResponseEntity.badRequest().body(Result.fail("INVALID_ARGUMENT", "请求格式错误"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result> handleConstraintViolation(ConstraintViolationException e) {
        return ResponseEntity.badRequest().body(Result.fail("INVALID_ARGUMENT", "请求参数不合法"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result> handleConflict(DataIntegrityViolationException e) {
        log.warn("数据冲突: {}", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.fail("CONFLICT", "资源已存在或状态冲突"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result> handleRuntimeException(RuntimeException e) {
        log.error("Unhandled request failure: {}", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("INTERNAL_ERROR", "服务器异常"));
    }
}
