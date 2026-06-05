import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbActiveModal } from '@ng-bootstrap/ng-bootstrap';
import { PatientPaymentDto, PaymentControllerService, RecordPaymentRequest } from '../../../api/generated';
import { extractProblem } from '../../../core/error/problem-detail';
import { formatMoney } from '../../../core/billing/format-money';

export interface RecordPaymentDialogData {
  billUids: string[];
  total: number;
  currency: string;
  billSummaries: { description: string; amount: number }[];
}

@Component({
  selector: 'app-record-payment-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
  ],
  template: `
    <div class="modal-header">
      <h5 class="modal-title" id="record-payment-dialog-title">Record Payment</h5>
      <button
        type="button"
        class="btn-close"
        aria-label="Close"
        (click)="activeModal.dismiss('cancel')"
      ></button>
    </div>

    <div class="modal-body" aria-describedby="bills-summary-desc">
      <p id="bills-summary-desc" class="bills-label">Bills being paid:</p>
      <ul class="list-group bills-list">
        @for (bill of data.billSummaries; track bill.description) {
          <li class="list-group-item">
            <span class="d-block">{{ bill.description }}</span>
            <span class="d-block text-muted small">{{ formatMoney(bill.amount, data.currency) }}</span>
          </li>
        }
      </ul>

      <p class="total-line">
        Total: <strong>{{ formatMoney(data.total, data.currency) }}</strong>
      </p>

      <form [formGroup]="form" novalidate id="payment-form">
        <div class="mb-3">
          <label class="form-label" for="tendered-amount">Tendered Amount</label>
          <input
            id="tendered-amount"
            class="form-control"
            type="number"
            step="0.01"
            formControlName="tenderedAmount"
            aria-label="Tendered amount"
            [attr.aria-required]="true"
            [class.is-invalid]="form.controls.tenderedAmount.invalid && form.controls.tenderedAmount.touched"
          />
          <div class="form-text">Enter amount in {{ data.currency }}</div>
          @if (form.controls.tenderedAmount.invalid && form.controls.tenderedAmount.touched) {
            <div class="invalid-feedback">Enter a valid amount (minimum 0.01).</div>
          }
        </div>

        <div class="mb-3">
          <label class="form-label" for="payment-mode">Payment Mode</label>
          <select
            id="payment-mode"
            class="form-select"
            formControlName="paymentMode"
            aria-label="Payment mode"
            [class.is-invalid]="form.controls.paymentMode.invalid && form.controls.paymentMode.touched"
          >
            <option value="CASH">Cash</option>
            <option value="INSURANCE">Insurance</option>
          </select>
          @if (form.controls.paymentMode.invalid && form.controls.paymentMode.touched) {
            <div class="invalid-feedback">Payment mode is required.</div>
          }
        </div>
      </form>

      @if (apiError()) {
        <p class="error-msg" role="alert">{{ apiError() }}</p>
      }
    </div>

    <div class="modal-footer">
      <button
        class="btn btn-link"
        type="button"
        (click)="cancel()"
        [disabled]="submitting()"
        aria-label="Cancel payment"
      >
        Cancel
      </button>
      <button
        class="btn btn-primary"
        type="button"
        (click)="confirm()"
        [disabled]="submitting() || form.invalid"
        [attr.aria-busy]="submitting()"
        aria-label="Confirm payment"
      >
        @if (submitting()) {
          <span class="spinner-border spinner-border-sm btn-spinner" role="status" aria-hidden="true"></span>
          Processing…
        } @else {
          Confirm Payment
        }
      </button>
    </div>
  `,
  styles: [`
    .bills-label  { margin: 0 0 0.25rem; font-weight: 500; }
    .bills-list   { margin-bottom: 1rem; }
    .total-line   { font-size: 1rem; margin: 0.75rem 0 1rem; }
    .error-msg    { color: #c62828; font-size: 0.875rem; margin-top: 0.5rem; }
    .btn-spinner  { margin-right: 0.5rem; }
  `],
})
export class RecordPaymentDialogComponent {
  private readonly fb             = inject(NonNullableFormBuilder);
  protected readonly activeModal  = inject(NgbActiveModal);
  private readonly paymentService = inject(PaymentControllerService);

  data!: RecordPaymentDialogData;

  readonly formatMoney = formatMoney;

  readonly form = this.fb.group({
    tenderedAmount: [
      0,
      [Validators.required, Validators.min(0.01)],
    ],
    paymentMode: [
      'CASH' as RecordPaymentRequest.PaymentModeEnum,
      Validators.required,
    ],
  });

  readonly submitting = signal(false);
  readonly apiError   = signal<string | null>(null);

  cancel(): void {
    this.activeModal.dismiss('cancel');
  }

  confirm(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    this.apiError.set(null);

    const { tenderedAmount, paymentMode } = this.form.getRawValue();

    this.paymentService
      .recordPayment({
        recordPaymentRequest: {
          billUids: this.data.billUids,
          tenderedTotal: {
            amount: tenderedAmount,
            currency: this.data.currency,
          },
          paymentMode,
        },
      })
      .subscribe({
        next: (result: PatientPaymentDto) => {
          this.submitting.set(false);
          this.activeModal.close(result);
        },
        error: (err: unknown) => {
          this.submitting.set(false);
          this.apiError.set(this.mapError(err));
        },
      });
  }

  private mapError(err: unknown): string {
    const problem = extractProblem(err);
    if (problem.type === 'urn:hmis:error:payment-amount-mismatch') {
      return `Tendered amount must equal the total (${formatMoney(this.data.total, this.data.currency)}).`;
    }
    if (problem.type === 'urn:hmis:error:bill-not-payable') {
      return 'One or more selected bills can no longer be paid.';
    }
    if (problem.status === 403) {
      return 'You do not have permission to perform this action.';
    }
    if (problem.status === 404) {
      return 'The requested record was not found.';
    }
    if (problem.status === 409) {
      return 'A conflict occurred. Reload and try again.';
    }
    if (problem.status === 503 || problem.status === 0) {
      return 'Service unavailable. Please try again shortly.';
    }
    return 'An unexpected error occurred. Please try again.';
  }
}
