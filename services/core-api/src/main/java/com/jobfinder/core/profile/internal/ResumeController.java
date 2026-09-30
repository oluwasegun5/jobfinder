package com.jobfinder.core.profile.internal;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.profile.internal.ResumeContentDtos.ResumeContent;
import com.jobfinder.core.profile.internal.ResumeContentDtos.ResumeContentResponse;
import com.jobfinder.core.profile.internal.ResumeDtos.DownloadUrlResponse;
import com.jobfinder.core.profile.internal.ResumeDtos.ResumeResponse;

import jakarta.validation.Valid;

/** The caller's own CVs. The user comes from the access token, never from the request. */
@RestController
class ResumeController {

    private final ResumeService service;
    private final ResumeContentService content;

    ResumeController(ResumeService service, ResumeContentService content) {
        this.service = service;
        this.content = content;
    }

    /** Uploads a PDF or DOCX (max 5 MB). The first CV becomes the primary one. */
    @PostMapping(path = "/resumes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ResumeResponse> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(name = "label", required = false) String label) throws IOException {
        ResumeResponse created = service.upload(CurrentUser.require().id(), file.getBytes(),
                file.getOriginalFilename(), label);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/resumes")
    List<ResumeResponse> list() {
        return service.list(CurrentUser.require().id());
    }

    /** A short-lived pre-signed link to download the original file. */
    @GetMapping("/resumes/{id}/download-url")
    DownloadUrlResponse downloadUrl(@PathVariable UUID id) {
        return service.downloadUrl(CurrentUser.require().id(), id);
    }

    /**
     * The latest structured content and the parse state. While {@code parseStatus} is PENDING the content is
     * usually still null: poll until it is PARSED or FAILED (or fill it in by hand).
     */
    @GetMapping("/resumes/{id}/content")
    ResumeContentResponse content(@PathVariable UUID id) {
        return content.get(CurrentUser.require().id(), id);
    }

    /** Saves the user's reviewed or hand-written content as an EDIT version, in any parse state. */
    @PutMapping("/resumes/{id}/content")
    ResumeContentResponse saveContent(@PathVariable UUID id, @Valid @RequestBody ResumeContent body) {
        return content.save(CurrentUser.require().id(), id, body);
    }

    @PutMapping("/resumes/{id}/primary")
    ResumeResponse setPrimary(@PathVariable UUID id) {
        return service.setPrimary(CurrentUser.require().id(), id);
    }

    @DeleteMapping("/resumes/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(CurrentUser.require().id(), id);
        return ResponseEntity.noContent().build();
    }
}
