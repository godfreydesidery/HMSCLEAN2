package com.otapp.hmis.billing.application;

/**
 * Catalogue of document-number types issued by {@link DocumentNumberService}.
 *
 * <p>Each type carries its own monotonic DB sequence — counters are keyed per document
 * <em>type</em>, never per prefix string (CR-09: the legacy "SPT" prefix was reused for two
 * distinct transfer-order types, so a prefix-keyed counter would collide). The sequence itself
 * is selected in {@link DocumentNumberService#next(DocumentType)} (one {@code nextval(seq_*_no)}
 * per type), so adding a new type means adding its enum constant + its own sequence.
 *
 * <p>Only {@link #PCN} is in billing scope (Increment 04). The legacy idiom emitted the same
 * {@code prefix + yyyyMMdd + "-" + suffix} shape for every document type
 * (04-extract-creditnote-numbering-refund.md §5); other prefixes (GRN/LPO/PRL/…) are owned by
 * their respective inventory/HR increments.
 */
public enum DocumentType {

    /**
     * Patient Credit Note. Format {@code PCN{yyyyMMdd}-{nextval(seq_pcn_no)}}
     * (accessories/Formater.java:14-17). Counter: {@code seq_pcn_no} (V13).
     */
    PCN("PCN");

    private final String prefix;

    DocumentType(String prefix) {
        this.prefix = prefix;
    }

    /** The literal prefix that opens the document number (e.g. {@code "PCN"}). */
    public String prefix() {
        return prefix;
    }
}
