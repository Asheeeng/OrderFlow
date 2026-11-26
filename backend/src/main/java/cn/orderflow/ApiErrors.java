package cn.orderflow;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class ApiException extends RuntimeException {
    final HttpStatus status;

    ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    static ApiException missing(String resource) {
        return new ApiException(HttpStatus.NOT_FOUND, resource + "不存在或不可访问");
    }

    static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> known(ApiException error) {
        return ResponseEntity.status(error.status).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalid(Exception error) {
        String message = "请求参数无效";
        if (error instanceof MethodArgumentNotValidException invalid && invalid.getFieldError() != null) {
            message += "：" + invalid.getFieldError().getField() + " " + invalid.getFieldError().getDefaultMessage();
        }
        return known(new ApiException(HttpStatus.BAD_REQUEST, message));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> integrity() {
        return known(ApiException.conflict("数据与现有记录冲突，或未满足数据库约束"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> database(DataAccessException error) {
        LoggerFactory.getLogger(ApiErrors.class).warn("Database request failed ({})", error.getClass().getSimpleName());
        return known(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "数据库操作暂时无法完成"));
    }
}
