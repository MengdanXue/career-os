package com.careeros;

import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,Object> invalid(IllegalArgumentException exception){return body("INVALID_REQUEST",exception.getMessage());}
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,Object> conflict(DataIntegrityViolationException exception){return body("DATA_CONFLICT","The operation violates a database constraint");}
    private Map<String,Object> body(String code,String message){return Map.of("timestamp",Instant.now().toString(),"code",code,"message",message==null?code:message);}
}
