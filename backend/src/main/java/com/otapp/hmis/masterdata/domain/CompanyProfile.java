package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Single-row company reference entity (increment-00 spec). The vertical slice that exercises the
 * full stack: Flyway DDL -&gt; {@code AuditableEntity} -&gt; repository -&gt; service -&gt; controller.
 * Accessors are Lombok-generated (DIRECTIVE 1).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "company_profiles")
public class CompanyProfile extends AuditableEntity {

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "address", length = 400)
    private String address;

    @Column(name = "phone", length = 40)
    private String phone;

    public CompanyProfile(String name, String address, String phone) {
        this.name = name;
        this.address = address;
        this.phone = phone;
    }

    public void update(String name, String address, String phone) {
        this.name = name;
        this.address = address;
        this.phone = phone;
    }
}
