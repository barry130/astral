package com.astral.sequence.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Optional editable fields; omitted values stay unchanged and blank text clears nullable columns. */
@Data
public class SequenceConfigUpdate {
    @Size(max = 64)
    private String bizKey;

    @Size(max = 32)
    private String sequenceType;

    private Integer step;

    @Size(max = 32)
    private String dateFormat;

    @Size(max = 32)
    private String prefix;

    @Size(max = 32)
    private String suffix;

    private Long minValue;
    private Boolean enabled;

    @Size(max = 256)
    private String description;
}
