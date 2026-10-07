package com.jobfinder.core.identity;

import java.util.UUID;

/**
 * Implemented by every module that stores data about a user, the counterpart of listening to
 * {@link UserDeletionRequested}: the data export asks each exporter for the caller's data only. A failing exporter fails
 * the whole export (an export that silently misses a module would be a false statement of what is stored).
 */
public interface UserDataExporter {

    /** The module's folder name in the zip, for example {@code profile}. */
    String module();

    void export(UUID userId, UserDataBundle bundle);
}
