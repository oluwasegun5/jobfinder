package com.jobfinder.core.shared;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private URI type;

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

    /** Extra members of the problem document, next to {@code code} (for example a reset time). */
    public Map<String, Object> properties() {
        return properties;
    }

    public ApiException withHeader(String name, String value) {
        headers.add(name, value);
        return this;
    }

    /** The problem {@code type} member (RFC 7807 URI) for errors a client recognises by type; null leaves the default. */
    public URI type() {
        return type;
    }

    public ApiException withType(URI type) {
        this.type = type;
        return this;
    }

    public ApiException withProperty(String name, Object value) {
        properties.put(name, value);
        return this;
    }
}
