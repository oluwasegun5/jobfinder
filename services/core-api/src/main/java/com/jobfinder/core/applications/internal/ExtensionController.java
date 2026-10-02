package com.jobfinder.core.applications.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.applications.internal.ExtensionDtos.ApplyContext;
import com.jobfinder.core.identity.CurrentUser;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The one endpoint the Chrome extension needs beyond the existing API (docs/adr/0035-chrome-extension.md). */
@RestController
class ExtensionController {

    private final ApplyContextService context;

    ExtensionController(ApplyContextService context) {
        this.context = context;
    }

    /**
     * The job the application form at {@code url} belongs to, with the caller's own application and pack for it when
     * they have them. Read-only. The URL is normalised server-side (fragment and tracking parameters dropped, ATS ids
     * kept) and matched against the apply links of known jobs. 400 {@code invalid_url}, 404 {@code job_not_found}.
     */
    @GetMapping("/extension/apply-context")
    ApplyContext applyContext(@RequestParam @NotBlank @Size(max = ApplyUrls.MAX_LENGTH) String url) {
        return context.find(CurrentUser.require().id(), url);
    }
}
