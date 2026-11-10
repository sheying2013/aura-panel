package com.admin.common.exception;

import com.admin.common.lang.R;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    //

    // 实体校验异常捕获
    //@ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public R MethodArgumentNotValidException(MethodArgumentNotValidException e) {
        BindingResult result = e.getBindingResult();
        ObjectError objectError = result.getAllErrors().stream().findFirst().get();
        log.info("实体校验异常：----------------{}", objectError.getDefaultMessage());
        return R.err(500, objectError.getDefaultMessage());
    }

    // 未授权异常捕获
    @ExceptionHandler(value = UnauthorizedException.class)
    public R handleUnauthorizedException(UnauthorizedException e) {
        log.info("未授权异常：----------------{}", e.getMessage());
        return R.err(401, e.getMessage());
    }

    @ExceptionHandler(value = Exception.class)
    public R Exception(Exception e){
        log.info("异常：----------------{}", e.getMessage());
        // e.getMessage() 可能为 null（NPE、部分 JDK 异常），原样返回会让前端显示 null，
        // 兜底给类名 + 明确提示，前端至少能看到是哪种异常。
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = e.getClass().getSimpleName() + "（无异常信息）";
        }
        return R.err(-2, message);
    }

}