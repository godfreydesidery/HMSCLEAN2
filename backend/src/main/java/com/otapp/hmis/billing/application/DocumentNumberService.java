package com.otapp.hmis.billing.application;

/**
 * Issues unique, monotonic document numbers (build-spec §4.3, CR-09).
 *
 * <p>Reproduces the legacy document-number <strong>format</strong> exactly while replacing the
 * legacy {@code MAX(id)+1} mechanism with a single atomic {@code nextval(seq_*_no)}. The legacy
 * mechanism computed the next number before insert and so two concurrent cancellations could read
 * the same {@code MAX(id)} and produce duplicate numbers, tripping the {@code UNIQUE(no)} constraint
 * on the second insert (04-extract-creditnote-numbering-refund.md §4). The sequence-backed
 * implementation is collision-free; the emitted string is byte-identical in shape to the legacy one.
 *
 * <p>Format: {@code prefix + yyyyMMdd + "-" + seq} — e.g. {@code PCN20260603-7}. The suffix is the
 * raw sequence value with no zero-padding (legacy {@code Formater.formatWithCurrentDate} never pads
 * document-number suffixes). The date component is rendered in <strong>EAT</strong>
 * ({@code Africa/Dar_es_Salaam}) per spec — a deliberate, ratified UTC→EAT deviation from legacy
 * (legacy forced the JVM zone to UTC, so 00:00–03:00 EAT events carried the previous UTC date).
 */
public interface DocumentNumberService {

    /**
     * Allocate and format the next document number for the given type.
     *
     * @param type the document type (selects the prefix + its per-type sequence)
     * @return the formatted document number, e.g. {@code PCN20260603-7}
     */
    String next(DocumentType type);
}
