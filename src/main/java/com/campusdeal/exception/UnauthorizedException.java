package com.campusdeal.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends ApiException {
    public UnauthorizedException() {
        super(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
    }
}
