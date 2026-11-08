package com.whale.crm.ai.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ConfirmAiJobRequest(@NotNull Long version,
                                  @Pattern(regexp = "CONFIRM|REJECT") String decision,
                                  @Size(max = 255) String title,
                                  @Size(max = 2000) String description,
                                  Instant dueAt,
                                  @Size(max = 1000) String reason) { }
