package com.whale.crm.platform.web;

import com.whale.crm.platform.web.ApiResponse.ErrorDetail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一错误结构与 requestId 行为（不依赖 Spring 容器与数据库）。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StubController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void businessException_returnsStableCodeAndEchoesRequestId() throws Exception {
        mockMvc.perform(get("/stub/pending-rule").header(RequestIdFilter.HEADER, "01ARZ3NDEKTSV4RRFFQ69G5FAV"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PENDING_BUSINESS_RULE"))
                .andExpect(jsonPath("$.message").value("金额分摊规则未确认"))
                .andExpect(jsonPath("$.requestId").value("01ARZ3NDEKTSV4RRFFQ69G5FAV"))
                .andExpect(jsonPath("$.details[0].field").value("Q02"))
                .andExpect(header().string(RequestIdFilter.HEADER, "01ARZ3NDEKTSV4RRFFQ69G5FAV"));
    }

    @Test
    void validationError_returns400WithFieldDetails() throws Exception {
        mockMvc.perform(post("/stub/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("name"));
    }

    @Test
    void unexpectedError_returns500WithoutInternals() throws Exception {
        mockMvc.perform(get("/stub/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("系统内部错误"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void missingRequestId_generatesUlid() throws Exception {
        mockMvc.perform(get("/stub/pending-rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(header().exists(RequestIdFilter.HEADER));
    }

    @RestController
    static class StubController {

        @GetMapping("/stub/pending-rule")
        void pendingRule() {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "金额分摊规则未确认",
                    List.of(new ErrorDetail("Q02", "套餐子项目分摊口径待财务确认")));
        }

        @GetMapping("/stub/boom")
        void boom() {
            throw new IllegalStateException("simulated failure");
        }

        @PostMapping("/stub/validated")
        void validated(@Valid @RequestBody StubBody body) {
        }
    }

    record StubBody(@NotBlank(message = "不能为空") String name) {
    }
}
