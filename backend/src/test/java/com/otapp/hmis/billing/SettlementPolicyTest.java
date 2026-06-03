package com.otapp.hmis.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.otapp.hmis.billing.api.PayBeforeServiceException;
import com.otapp.hmis.billing.api.SettlementPolicy;
import com.otapp.hmis.billing.domain.PaymentMode;
import org.junit.jupiter.api.Test;

/**
 * Truth-table unit test for the scoped pay-before-service rule (CR-05, build-spec §4.2).
 *
 * <p>Pure logic, no Spring/DB — the canonical "stub" verifying the gate is SCOPED (only CASH
 * outpatient/outsider is blocked; insurance/inpatient/emergency bypass), so a blanket gate cannot
 * regress in (HDE BLOCKER-CLINICAL-2).
 */
class SettlementPolicyTest {

    // ---- requiresPrepayment: the scoping truth table ----

    @Test
    void cashOutpatientNonEmergency_requiresPrepayment() {
        assertThat(SettlementPolicy.requiresPrepayment(PaymentMode.CASH, false, false)).isTrue();
    }

    @Test
    void insurance_neverRequiresPrepayment() {
        assertThat(SettlementPolicy.requiresPrepayment(PaymentMode.INSURANCE, false, false)).isFalse();
        assertThat(SettlementPolicy.requiresPrepayment(PaymentMode.INSURANCE, true, false)).isFalse();
        assertThat(SettlementPolicy.requiresPrepayment(PaymentMode.INSURANCE, false, true)).isFalse();
    }

    @Test
    void inpatient_doesNotRequirePrepayment_settleAtDischarge() {
        assertThat(SettlementPolicy.requiresPrepayment(PaymentMode.CASH, true, false)).isFalse();
    }

    @Test
    void emergency_doesNotRequirePrepayment_bypass() {
        assertThat(SettlementPolicy.requiresPrepayment(PaymentMode.CASH, false, true)).isFalse();
    }

    // ---- requireSettled: enforcement ----

    @Test
    void requireSettled_throwsForUnsettledCashOutpatient() {
        assertThatThrownBy(() ->
                SettlementPolicy.requireSettled(false, PaymentMode.CASH, false, false, "BILL-1"))
                .isInstanceOf(PayBeforeServiceException.class);
    }

    @Test
    void requireSettled_passesWhenCashOutpatientIsSettled() {
        assertThatCode(() ->
                SettlementPolicy.requireSettled(true, PaymentMode.CASH, false, false, "BILL-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireSettled_passesForInsurance_evenIfUnsettled() {
        assertThatCode(() ->
                SettlementPolicy.requireSettled(false, PaymentMode.INSURANCE, false, false, "BILL-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireSettled_passesForInpatient_evenIfUnsettled() {
        assertThatCode(() ->
                SettlementPolicy.requireSettled(false, PaymentMode.CASH, true, false, "BILL-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireSettled_passesForEmergency_evenIfUnsettled() {
        assertThatCode(() ->
                SettlementPolicy.requireSettled(false, PaymentMode.CASH, false, true, "BILL-1"))
                .doesNotThrowAnyException();
    }
}
