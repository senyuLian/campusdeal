package com.campusdeal.exception;

/**
 * 业务异常：携带面向用户的错误信息，由全局异常处理器统一转成 Result.fail()
 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
