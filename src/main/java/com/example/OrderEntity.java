package com.example;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Builder
@Data
public class OrderEntity {
    private int id;
    private String name;
    private String description;
    private LocalDate effectiveDate;
    private String status;
}
