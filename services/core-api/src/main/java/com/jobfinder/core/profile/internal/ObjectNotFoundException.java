package com.jobfinder.core.profile.internal;

/** The requested object is not in storage. */
class ObjectNotFoundException extends RuntimeException {

    ObjectNotFoundException(String key, Throwable cause) {
        super("No stored object under " + key, cause);
    }
}
