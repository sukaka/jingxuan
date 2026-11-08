package com.whale.crm.integration.wecom.api;

import com.whale.crm.integration.wecom.application.WecomCallbackCryptoService;
import com.whale.crm.integration.wecom.application.WecomCallbackService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/wecom/callback")
public class WecomCallbackController {
    private final WecomCallbackCryptoService crypto;
    private final WecomCallbackService service;

    public WecomCallbackController(WecomCallbackCryptoService crypto, WecomCallbackService service) {
        this.crypto = crypto;
        this.service = service;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@RequestParam("msg_signature") String signature,
            @RequestParam String timestamp, @RequestParam String nonce, @RequestParam String echostr) {
        return ResponseEntity.ok(crypto.verifyUrl(signature, timestamp, nonce, echostr));
    }

    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receive(@RequestParam("msg_signature") String signature,
            @RequestParam String timestamp, @RequestParam String nonce, @RequestBody String xml) {
        String plain = crypto.decrypt(signature, timestamp, nonce, crypto.encryptedElement(xml));
        service.accept(plain);
        return ResponseEntity.ok("success");
    }
}
