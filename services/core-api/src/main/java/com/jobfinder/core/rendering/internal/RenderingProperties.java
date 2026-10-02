package com.jobfinder.core.rendering.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.rendering.*} (docs/adr/0030-document-rendering.md). {@code downloadUrlTtl} is how long the pre-signed
 * download link of a rendered file works.
 */
@ConfigurationProperties("app.rendering")
record RenderingProperties(@DefaultValue("5m") Duration downloadUrlTtl) {
}
