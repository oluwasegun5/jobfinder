package com.jobfinder.core.storage;

import java.net.URI;
import java.time.Duration;

/** Private object storage for user files (CVs, rendered documents). Nothing stored here is ever publicly readable. */
public interface ObjectStorage {

    void put(String key, byte[] content, String contentType);

    /** Reads a whole object. Throws {@link ObjectNotFoundException} if it does not exist. */
    byte[] get(String key);

    /** Whether an object exists under {@code key}. */
    boolean exists(String key);

    /** A time-limited URL that downloads the object as an attachment named {@code filename}. */
    URI presignDownload(String key, String filename, Duration ttl);

    void delete(String key);

    /** Deletes every object whose key starts with {@code prefix}. */
    void deleteByPrefix(String prefix);
}
