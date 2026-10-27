package cn.review.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class Errors {
    private static final Logger LOG = LoggerFactory.getLogger(Errors.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Api<Void>> application(ApiException error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).body(new Api<>(error.code(), error.getMessage(), null, Api.requestId(request)));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Api<Void>> invalid(Exception error, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new Api<>("VALIDATION_FAILED", "请求格式或必要参数不正确", null, Api.requestId(request)));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Api<Void>> unexpected(Exception error, HttpServletRequest request) {
        LOG.error("Request {} failed: {}", Api.requestId(request), error.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new Api<>("INTERNAL_ERROR", "暂时无法完成操作，请稍后重试", null, Api.requestId(request)));
    }
}
