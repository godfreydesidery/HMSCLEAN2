package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Operating theatre master (legacy {@code com.orbix.api.domain.Theatre}, Theatre.java:30-45).
 *
 * <p>No price field, no relationships (Theatre.java:30-45).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "theatres")
public class Theatre extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    public Theatre(String code, String name, String description,
                   String location, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.location = location;
        this.active = active;
    }

    public void update(String code, String name, String description,
                       String location, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.location = location;
        this.active = active;
    }
}
