package com.campusdeal.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class Result {
    private Boolean success;
    private String errorMsg;
    private Object data;
    private Long total;
    private String errorCode;

    public Result(Boolean success, String errorMsg, Object data, Long total) {
        this(success, errorMsg, data, total, null);
    }

    public Result(Boolean success, String errorMsg, Object data, Long total, String errorCode) {
        this.success = success;
        this.errorMsg = errorMsg;
        this.data = data;
        this.total = total;
        this.errorCode = errorCode;
    }

    public static Result ok(){
        return new Result(true, null, null, null);
    }
    public static Result ok(Object data){
        return new Result(true, null, data, null);
    }
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total);
    }
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null);
    }

    public static Result fail(String errorCode, String errorMsg) {
        return new Result(false, errorMsg, null, null, errorCode);
    }
}
