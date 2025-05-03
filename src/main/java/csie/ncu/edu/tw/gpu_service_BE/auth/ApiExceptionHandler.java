package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleAllExceptions(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(500).body(Map.of(
            "errorCode", "INTERNAL_ERROR",
            "message", ex.getMessage()
        ));
    }
}