package com.jobfinder.core.storage;

/** The requested object is not in storage. */
public class ObjectNotFoundException extends RuntimeException {

    public ObjectNotFoundException(String key, Throwable cause) {
        super("No stored object under " + key, cause);
    }
}
