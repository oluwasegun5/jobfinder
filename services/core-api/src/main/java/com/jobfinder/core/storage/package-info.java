/**
 * Private S3-compatible object storage (S3Mock locally, Cloudflare R2 in production) shared by the modules that keep
 * files: CVs ({@code profile}) and rendered documents ({@code rendering}). Only the {@link
 * com.jobfinder.core.storage.ObjectStorage} port is public; the S3 client, the presigner and their configuration
 * ({@code app.storage.*}, docs/adr/0015-cv-upload-and-storage.md) are internal.
 */
package com.jobfinder.core.storage;
