package com.jobfinder.core.identity;

/**
 * What a module writes into the user's data export (docs/adr/0040). Names are relative to the module's own folder in
 * the zip. A module only ever receives the id of the user who asked, and must select by that id alone.
 */
public interface UserDataBundle {

    /** Adds {@code <module>/<name>.json} holding {@code json} (already serialised). */
    void json(String name, String json);

    /** Adds the file {@code <module>/<path>}. */
    void file(String path, byte[] content);

    /** Records a file that exists in the data but could not be included, so the export says so instead of hiding it. */
    void skipped(String path, String reason);
}
