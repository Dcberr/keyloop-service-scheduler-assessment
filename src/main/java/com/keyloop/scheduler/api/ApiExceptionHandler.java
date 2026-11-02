package com.keyloop.scheduler.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keyloop.scheduler.appointment.BookingFailureMapper;
import com.keyloop.scheduler.shared.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final BookingFailureMapper failures;
    public ApiExceptionHandler(BookingFailureMapper failures) {this.failures=failures;}

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> handle(Exception exception,HttpServletRequest request) {
        int status;
        String code;
        String detail;
        List<FieldError> errors=null;
        HttpHeaders headers=new HttpHeaders();
        if(exception instanceof BusinessException business) {
            status=business.status();code=business.code();detail=status>=500?safeDetail(status):business.getMessage();
        } else if(exception instanceof MethodArgumentNotValidException invalid) {
            status=400;code="INVALID_REQUEST";detail="Required request fields are missing or invalid";
            errors=invalid.getBindingResult().getFieldErrors().stream().map(e->new FieldError(e.getField(),"must be a nonblank string")).distinct().toList();
        } else if(exception instanceof HttpRequestMethodNotSupportedException method) {
            status=405;code="INVALID_REQUEST";detail="The HTTP method is not supported for this resource";
            if(method.getSupportedHttpMethods()!=null) headers.setAllow(method.getSupportedHttpMethods());
        } else if(exception instanceof HttpMediaTypeNotSupportedException) {
            status=415;code="INVALID_REQUEST";detail="The request content type is not supported";
        } else if(exception instanceof HttpMediaTypeNotAcceptableException) {
            status=406;code="INVALID_REQUEST";detail="The requested response content type is not supported";
        } else if(exception instanceof NoResourceFoundException || exception instanceof NoHandlerFoundException) {
            status=404;code="RESOURCE_NOT_FOUND";detail="The requested resource does not exist";
        } else if(exception instanceof HttpMessageNotReadableException || exception instanceof ServletRequestBindingException
                || exception instanceof TypeMismatchException || exception instanceof ConstraintViolationException) {
            status=400;code="INVALID_REQUEST";detail="The request is malformed or a required field is missing";
        } else if(exception instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            status=error.getStatusCode().value();code=status==404?"RESOURCE_NOT_FOUND":"INVALID_REQUEST";detail="The request could not be processed";
        } else {
            BusinessException mapped=failures.map(exception instanceof RuntimeException runtime?runtime:new RuntimeException(exception));
            status=mapped.status();code=mapped.code();detail=safeDetail(status);
            LOG.atError().addKeyValue("event", "api_failure").addKeyValue("code", code)
                    .addKeyValue("failureType", exception.getClass().getSimpleName())
                    .log("API request failed");
        }
        Problem body=new Problem("about:blank",HttpStatus.valueOf(status).getReasonPhrase(),status,detail,
                request.getRequestURI(),code,RequestIdFilter.get(request),errors);
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static String safeDetail(int status) {return status==503?"The service is temporarily unavailable":"An unexpected server error occurred";}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Problem(String type,String title,int status,String detail,String instance,String code,String requestId,List<FieldError> errors) { }
    record FieldError(String field,String message) { }
}
