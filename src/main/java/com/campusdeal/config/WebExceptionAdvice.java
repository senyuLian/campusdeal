package com.campusdeal.config;

import com.campusdeal.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: {}", e.getName());
        return Result.fail("参数类型错误");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求参数不合法: {}", e.getMessage());
        return Result.fail("请求参数不合法");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必要参数: {}", e.getParameterName());
        return Result.fail("缺少必要参数: " + e.getParameterName());
    }

    @ExceptionHandler(MultipartException.class)
    public Result handleMultipart(MultipartException e) {
        log.warn("请求格式错误（需 multipart）: {}", e.getMessage());
        return Result.fail("请求格式错误");
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
