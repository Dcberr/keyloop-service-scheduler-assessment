package com.keyloop.scheduler.shared;

/** A stable business failure; HTTP mapping is introduced with the API layer. */
public class BusinessException extends RuntimeException {
    private final String code;
    private final int status;

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() { return code; }
    public int status() { return status; }
}
