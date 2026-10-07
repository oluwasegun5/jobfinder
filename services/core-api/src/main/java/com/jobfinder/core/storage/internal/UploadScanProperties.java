package com.jobfinder.core.storage.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Upload scanning, under {@code app.upload.scanner}. Off by default ({@code type: none}); {@code clamav} streams every
 * upload to a clamd daemon over TCP. {@code onError} is what happens when the scanner cannot answer: {@code closed}
 * (the default, and the only sensible production value) refuses the upload, {@code open} logs and accepts it.
 */
@ConfigurationProperties("app.upload.scanner")
record UploadScanProperties(
        @DefaultValue("none") Type type,
        @DefaultValue("localhost") @NotBlank String host,
        @DefaultValue("3310") @Min(1) @Max(65535) int port,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("closed") OnError onError) {

    enum Type { none, clamav }

    enum OnError { closed, open }
}
