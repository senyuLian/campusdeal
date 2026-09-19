package com.campusdeal.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Client-owned fields for merchant create/update; ownership is server derived. */
@Data
public class MerchantWriteRequest {
    private Long id;
    @NotBlank @Size(max = 128) private String name;
    @NotNull private Long typeId;
    @Size(max = 1024) private String images;
    @Size(max = 128) private String area;
    @Size(max = 255) private String address;
    @DecimalMin("-180.0") @DecimalMax("180.0") private Double x;
    @DecimalMin("-90.0") @DecimalMax("90.0") private Double y;
    @PositiveOrZero private Long avgPrice;
    @PositiveOrZero private Integer sold;
    @PositiveOrZero private Integer comments;
    @DecimalMin("0") @DecimalMax("50") private Integer score;
    @Size(max = 128) private String openHours;

    @AssertTrue(message = "经度和纬度必须同时提供")
    public boolean isCoordinatePairValid() {
        return (x == null) == (y == null);
    }
}
