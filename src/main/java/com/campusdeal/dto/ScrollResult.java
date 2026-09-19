package com.campusdeal.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
    /** Opaque score/member cursor for stable feed pagination. */
    private String cursor;
    /** Snapshot upper bound captured when the cursor was created. */
    private Long snapshotMaxTime;
}
