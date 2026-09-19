package com.campusdeal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PostWriteRequest {
    @Positive private Long shopId;
    @NotBlank @Size(max = 128) private String title;
    @NotBlank @Size(max = 10000) private String content;
    @Size(max = 2048) private String images;
}
