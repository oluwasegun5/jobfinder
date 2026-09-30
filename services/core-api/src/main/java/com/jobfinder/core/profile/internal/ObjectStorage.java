package com.jobfinder.core.profile.internal;

import java.net.URI;
import java.time.Duration;

/** Private object storage for user files. Nothing stored here is ever publicly readable. */
interface ObjectStorage {

    void put(String key, byte[] content, String contentType);

    /** A time-limited URL that downloads the object as an attachment named {@code filename}. */
    URI presignDownload(String key, String filename, Duration ttl);

    void delete(String key);

    /** Deletes every object whose key starts with {@code prefix}. */
    void deleteByPrefix(String prefix);
}
