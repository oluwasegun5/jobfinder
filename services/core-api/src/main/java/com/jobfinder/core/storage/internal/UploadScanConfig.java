package com.jobfinder.core.storage.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jobfinder.core.storage.UploadScanner;
import com.jobfinder.core.storage.UploadScans;

/** Chooses the scanner adapter from {@code app.upload.scanner.type} and wraps it in the failure policy. */
@Configuration
@EnableConfigurationProperties(UploadScanProperties.class)
class UploadScanConfig {

    @Bean
    UploadScanner uploadScanner(UploadScanProperties properties) {
        return switch (properties.type()) {
            case none -> new NoOpUploadScanner();
            case clamav -> new ClamAvUploadScanner(properties.host(), properties.port(),
                    (int) properties.connectTimeout().toMillis(), (int) properties.readTimeout().toMillis());
        };
    }

    @Bean
    UploadScans uploadScans(UploadScanner scanner, UploadScanProperties properties) {
        return new DefaultUploadScans(scanner, properties.onError());
    }
}
