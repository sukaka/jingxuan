package com.whale.crm.integration.wecom.api;

import com.whale.crm.integration.wecom.api.dto.WecomRoomSaveRequest;
import com.whale.crm.integration.wecom.api.dto.WecomRoomView;
import com.whale.crm.integration.wecom.application.WecomRoomService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/integrations/wecom/rooms")
public class WecomRoomController extends WecomBaseController {
    private final WecomRoomService service;

    public WecomRoomController(WecomRoomService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wecom:read')")
    public ApiResponse<List<WecomRoomView>> list(@RequestParam @NotBlank @Size(max = 96) String corpId,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.list(SecuritySupport.requireCurrentUser(), corpId), requestId(request));
    }

    @PutMapping("/{roomId}")
    @PreAuthorize("hasAuthority('wecom:monitor')")
    public ApiResponse<WecomRoomView> save(@PathVariable @NotBlank @Size(max = 192) String roomId,
                                           @Valid @RequestBody WecomRoomSaveRequest body,
                                           @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
                                           HttpServletRequest request) {
        return ApiResponse.ok(service.save(SecuritySupport.requireCurrentUser(), roomId, body, key), requestId(request));
    }
}
