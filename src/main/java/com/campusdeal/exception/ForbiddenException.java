package com.campusdeal.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends ApiException {
    public ForbiddenException() {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有权限执行该操作");
    }
}
