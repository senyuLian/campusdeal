package com.campusdeal.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponWriteRequest {
    @NotNull private Long shopId;
    @NotBlank @Size(max = 128) private String title;
    @Size(max = 255) private String subTitle;
    @Size(max = 2000) private String rules;
    @NotNull @PositiveOrZero private Long payValue;
    @NotNull @PositiveOrZero private Long actualValue;
    @NotNull @Min(0) @Max(1) private Integer type;
    /** Publication state is server controlled; retained only for wire compatibility. */
    @PositiveOrZero private Integer status;
    @PositiveOrZero @Max(1_000_000)
    private Integer stock;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;

    @AssertTrue(message = "秒杀库存和有效期不合法")
    public boolean isFlashWindowValid() {
        if (type == null || type != 1) return true;
        return stock != null && stock > 0 && beginTime != null && endTime != null
                && endTime.isAfter(beginTime);
    }
}
