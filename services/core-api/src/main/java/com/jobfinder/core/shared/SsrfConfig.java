package com.jobfinder.core.shared;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SsrfProperties.class)
class SsrfConfig {

    @Bean
    SsrfGuard ssrfGuard(SsrfProperties properties) {
        return new SsrfGuard(new SsrfGuard.Policy(properties.allowHttp(), properties.allowPrivateAddresses()));
    }
}
