package com.example.agentservice.pojo.base.response;

import java.io.Serializable;
import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.Validate;

/** 业务响应体。 */
@Data
@Accessors(chain = true)
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final Integer SUCCESS_CODE = 200;
    public static final Integer ERROR_CODE = 400;
    public static final Integer FAIL_CODE = 500;
    public static final String DEFAULT_SUCCESS_MSG = "success";
    public static final String DEFAULT_FAIL_MSG = "fail";

    private Integer code;
    private String message;
    private T data;

    private R(Integer code, String message) {
        Validate.notBlank(message, "消息内容不能为空");
        this.code = code;
        this.message = message;
    }

    private R(Integer code, String message, T data) {
        this(code, message);
        this.data = data;
    }

    public static <T> R<T> success(T data) {
        return new R<>(SUCCESS_CODE, DEFAULT_SUCCESS_MSG, data);
    }

    public static <T> R<T> success() {
        return new R<>(SUCCESS_CODE, DEFAULT_SUCCESS_MSG);
    }

    public static <T> R<T> success(String message) {
        return new R<>(SUCCESS_CODE, message);
    }

    public static <T> R<T> success(String message, T data) {
        return new R<>(SUCCESS_CODE, message, data);
    }

    public static <T> R<T> fail(T data) {
        return new R<>(FAIL_CODE, DEFAULT_FAIL_MSG, data);
    }

    public static <T> R<T> fail() {
        return new R<>(FAIL_CODE, DEFAULT_FAIL_MSG);
    }

    public static <T> R<T> fail(String message) {
        return new R<>(FAIL_CODE, message);
    }

    public static <T> R<T> fail(Integer errorCode, String message) {
        return new R<>(errorCode, message);
    }

    public static <T> R<T> error(Integer code, String message) {
        Validate.isTrue(!SUCCESS_CODE.equals(code), "错误状态码不能使用成功码");
        return new R<>(code, message);
    }

    public static <T> R<T> error(String message) {
        return new R<>(ERROR_CODE, message);
    }

    @Deprecated
    public R(String message, T data) {
        this(SUCCESS_CODE, message, data);
    }

    @Deprecated
    public R(T data) {
        this(SUCCESS_CODE, DEFAULT_SUCCESS_MSG, data);
    }

    @Deprecated
    public R(String message) {
        this(SUCCESS_CODE, message);
    }

    @Deprecated
    public R() {
        this(SUCCESS_CODE, DEFAULT_SUCCESS_MSG);
    }
}
