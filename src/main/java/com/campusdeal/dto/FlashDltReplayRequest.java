package com.campusdeal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Operator supplied identity and payload for replaying one flash-order DLT event. */
@Data
public class FlashDltReplayRequest {

    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9._:-]+")
    private String eventId;

    @NotBlank
    @Size(max = 4096)
    private String payload;
}
