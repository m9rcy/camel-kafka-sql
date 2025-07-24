package com.example;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.Objects;

@Builder
@Data
@EqualsAndHashCode
public class OrderEntity {
    private int id;
    private String name;
    private String description;
    private LocalDate effectiveDate;
    private String status;

    // Custom method to check if business fields have changed
    public boolean hasBusinessFieldsChanged(OrderEntity other) {
        if (other == null) return true;

        return !Objects.equals(this.name, other.name) ||
                !Objects.equals(this.description, other.description) ||
                !Objects.equals(this.effectiveDate, other.effectiveDate) ||
                !Objects.equals(this.status, other.status);
    }
}
