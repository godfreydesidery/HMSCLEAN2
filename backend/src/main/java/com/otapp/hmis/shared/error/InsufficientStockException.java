package com.otapp.hmis.shared.error;

/**
 * Thrown when a stock-decrementing operation requests more quantity than is available
 * (inc-08a, AC-RX-DSP-07/20, AC-OTC-08). Maps to HTTP 422 via {@link ErrorCode#INSUFFICIENT_STOCK}.
 *
 * <p>Reproduces the legacy hard negative-stock REFUSAL — the verbatim exact-process gate
 * (PatientResource.java:3243-3250 clinical dispense; :6272-6273 OTC dispense; the transfer
 * goods-issue and GRN-approve paths share the same guard). The DB {@code CHECK(stock >= 0)} is a
 * net-new backstop; this app-layer 422 reject is the FROZEN parity element. The stable type URI
 * {@code urn:hmis:error:insufficient-stock} lets the Angular client react without string-matching.
 */
public class InsufficientStockException extends HmisException {

    /**
     * Construct with the verbatim legacy refusal message as the detail.
     *
     * @param detail the message string (legacy: "...less than the requested/transfer qty")
     */
    public InsufficientStockException(String detail) {
        super(ErrorCode.INSUFFICIENT_STOCK, detail);
    }
}
