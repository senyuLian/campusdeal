package com.campusdeal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginFormDTO {
    @NotBlank
    @Pattern(regexp = "1[3-9]\\d{9}")
    private String phone;

    @NotBlank
    @Pattern(regexp = "\\d{6}")
    private String code;

    @Size(max = 128)
    private String password;
}
