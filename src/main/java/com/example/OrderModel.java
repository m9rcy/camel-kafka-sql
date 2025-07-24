package com.example;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import org.springframework.boot.convert.DataSizeUnit;
import org.wildfly.common.annotation.NotNull;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Jacksonized
@Builder
@Data
public class OrderModel {

    @NotNull
    private int id;

    @NotNull
    private int version;

    @NotNull
    private String name;

    @NotNull
    private String description;

    private OffsetDateTime effectiveDate;

    @NotNull
    private StatusEnum status;

}
