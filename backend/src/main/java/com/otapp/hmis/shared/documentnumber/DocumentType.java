package com.otapp.hmis.shared.documentnumber;

/**
 * Catalogue of document-number types issued by {@link DocumentNumberService} (CR-09; ADR-0009 §5/§6).
 *
 * <p>Promoted to the shared kernel in increment 08 (Q11 ratified): document numbering is a
 * cross-cutting concern, so the service and this enum live in {@code shared} (an {@code OPEN}
 * module) and every bounded context depends on them without an {@code inventory→billing} or
 * {@code pharmacy→billing} edge. The {@code md_document_types} prefix <em>registry</em> stays in
 * masterdata (reference data); the numbering <em>mechanism</em> (allocation + format) lives here.
 *
 * <p>Each type carries its own monotonic DB sequence — counters are keyed per document
 * <em>type</em>, never per prefix string (CR-09: the legacy {@code "SPT"} prefix was reused for two
 * distinct transfer-order types, so a prefix-keyed counter would collide). The {@link #sequenceName()}
 * is an <strong>allow-list</strong>: {@link DocumentNumberService} issues {@code nextval(sequenceName)}
 * for the value declared here and never for a string built from caller input. Adding a new type means
 * adding its enum constant (with its V13-seeded sequence) here.
 *
 * <p>Sequence names are the <strong>as-built</strong> names seeded by
 * {@code V13__masterdata_document_sequences.sql} — note {@code seq_spto_no}/{@code seq_ppto_no}
 * (NOT the stale {@code seq_sto_no}/{@code seq_ptp_no} placeholders that ADR-0009's draft text once
 * carried; corrected 2026-06-05, inc-08 Q6).
 */
public enum DocumentType {

    /**
     * Patient Credit Note (billing, increment 04). Format {@code PCN{yyyyMMdd}-{nextval(seq_pcn_no)}}.
     * Counter: {@code seq_pcn_no} (V13).
     */
    PCN("PCN", "seq_pcn_no");

    private final String prefix;
    private final String sequenceName;

    DocumentType(String prefix, String sequenceName) {
        this.prefix = prefix;
        this.sequenceName = sequenceName;
    }

    /** The literal prefix that opens the document number (e.g. {@code "PCN"}). */
    public String prefix() {
        return prefix;
    }

    /**
     * The name of this type's dedicated PostgreSQL sequence (e.g. {@code "seq_pcn_no"}). This is the
     * sole source of the identifier interpolated into {@code nextval(...)} — an allow-list, never
     * caller-supplied input.
     */
    public String sequenceName() {
        return sequenceName;
    }
}
