package com.otapp.hmis.pharmacy.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Walk-in OTC customer (inc-08a chunk 4; legacy PharmacyCustomer.java:40-64). A standalone record —
 * NOT a clinical {@code Patient}. Only {@code no} + {@code name} are mandatory (legacy @NotBlank);
 * gender/phoneNo/address optional. {@code no} = {@code 'PCST/'+nextval(seq_pcst_no)} (CR-09-NUM1
 * sequence-backed provenance; the legacy raw-PK suffix is forbidden by ADR-0014 §1).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "pharmacy_customers")
public class PharmacyCustomer extends AuditableEntity {

    @NotBlank
    @Column(name = "no", length = 40, nullable = false, unique = true)
    private String no;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "gender", length = 20)
    private String gender;

    @Column(name = "phone_no", length = 50)
    private String phoneNo;

    @Column(name = "address", length = 500)
    private String address;

    /**
     * Register a walk-in customer.
     *
     * @param no       the sequence-backed customer number ('PCST/'+seq)
     * @param name     customer name (required)
     * @param gender   optional
     * @param phoneNo  optional
     * @param address  optional
     */
    public PharmacyCustomer(String no, String name, String gender, String phoneNo, String address) {
        this.no = no;
        this.name = name;
        this.gender = gender;
        this.phoneNo = phoneNo;
        this.address = address;
    }
}
