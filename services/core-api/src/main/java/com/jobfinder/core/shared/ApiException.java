package com.jobfinder.core.shared;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * A failure that maps directly to an RFC 7807 response. {@code code} is a stable,
 * machine-readable identifier clients can branch on; {@code detail} is for humans.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final HttpHeaders headers = new HttpHeaders();

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public HttpHeaders headers() {
        return headers;
    }

    public ApiException withHeader(String name, String value) {
        headers.add(name, value);
        return this;
    }
}
