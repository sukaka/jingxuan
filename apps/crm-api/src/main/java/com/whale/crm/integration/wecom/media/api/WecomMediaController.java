package com.whale.crm.integration.wecom.media.api;

import com.whale.crm.integration.wecom.media.application.WecomMediaContent;
import com.whale.crm.integration.wecom.media.application.WecomMediaService;
import com.whale.crm.platform.security.SecuritySupport;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/integrations/wecom/media")
public class WecomMediaController {
    private final WecomMediaService service;

    public WecomMediaController(WecomMediaService service) {
        this.service = service;
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("hasAuthority('wecom:read')")
    public ResponseEntity<InputStreamResource> content(@PathVariable long id) {
        WecomMediaContent content = service.read(SecuritySupport.requireCurrentUser(), id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(content.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes()).header("Content-Disposition", disposition.toString())
                .body(new InputStreamResource(content.input()));
    }
}
