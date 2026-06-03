import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatListModule } from '@angular/material/list';
import { DefaultService, PatientPaymentDto, RecordPaymentRequest } from '../../../api/generated';
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
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatListModule,
  ],
  template: `
    <h2 mat-dialog-title id="record-payment-dialog-title">Record Payment</h2>

    <mat-dialog-content aria-describedby="bills-summary-desc">
      <p id="bills-summary-desc" class="bills-label">Bills being paid:</p>
      <mat-list dense class="bills-list">
        @for (bill of dialogData.billSummaries; track bill.description) {
          <mat-list-item>
            <span matListItemTitle>{{ bill.description }}</span>
            <span matListItemLine>{{ formatMoney(bill.amount, dialogData.currency) }}</span>
          </mat-list-item>
        }
      </mat-list>

      <p class="total-line">
        Total: <strong>{{ formatMoney(dialogData.total, dialogData.currency) }}</strong>
      </p>

      <form [formGroup]="form" novalidate id="payment-form">
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Tendered Amount</mat-label>
          <input
            matInput
            type="number"
            step="0.01"
            formControlName="tenderedAmount"
            cdkFocusInitial
            aria-label="Tendered amount"
            [attr.aria-required]="true"
          />
          <mat-hint>Enter amount in {{ dialogData.currency }}</mat-hint>
          @if (form.controls.tenderedAmount.invalid && form.controls.tenderedAmount.touched) {
            <mat-error>Enter a valid amount (minimum 0.01).</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Payment Mode</mat-label>
          <mat-select formControlName="paymentMode" aria-label="Payment mode">
            <mat-option value="CASH">Cash</mat-option>
            <mat-option value="INSURANCE">Insurance</mat-option>
          </mat-select>
          @if (form.controls.paymentMode.invalid && form.controls.paymentMode.touched) {
            <mat-error>Payment mode is required.</mat-error>
          }
        </mat-form-field>
      </form>

      @if (apiError()) {
        <p class="error-msg" role="alert">{{ apiError() }}</p>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button
        mat-button
        type="button"
        (click)="cancel()"
        [disabled]="submitting()"
        aria-label="Cancel payment"
      >
        Cancel
      </button>
      <button
        mat-raised-button
        color="primary"
        type="button"
        (click)="confirm()"
        [disabled]="submitting() || form.invalid"
        [attr.aria-busy]="submitting()"
        aria-label="Confirm payment"
      >
        @if (submitting()) {
          <mat-spinner diameter="20" class="btn-spinner"></mat-spinner>
          Processing…
        } @else {
          Confirm Payment
        }
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    .bills-label  { margin: 0 0 0.25rem; font-weight: 500; }
    .bills-list   { margin-bottom: 1rem; }
    .total-line   { font-size: 1rem; margin: 0.75rem 0 1rem; }
    .full-width   { width: 100%; display: block; margin-bottom: 0.75rem; }
    .error-msg    { color: #c62828; font-size: 0.875rem; margin-top: 0.5rem; }
    .btn-spinner  { display: inline-block; margin-right: 0.5rem; }
    mat-dialog-content { min-width: 360px; }
  `],
})
export class RecordPaymentDialogComponent {
  private readonly fb             = inject(NonNullableFormBuilder);
  private readonly dialogRef      = inject(MatDialogRef<RecordPaymentDialogComponent>);
  private readonly defaultService = inject(DefaultService);
  readonly dialogData             = inject<RecordPaymentDialogData>(MAT_DIALOG_DATA);

  readonly formatMoney = formatMoney;

  readonly form = this.fb.group({
    tenderedAmount: [
      this.dialogData.total,
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
    this.dialogRef.close(null);
  }

  confirm(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    this.apiError.set(null);

    const { tenderedAmount, paymentMode } = this.form.getRawValue();

    this.defaultService
      .recordBillsPayment({
        recordPaymentRequest: {
          billUids: this.dialogData.billUids,
          tenderedTotal: {
            amount: tenderedAmount,
            currency: this.dialogData.currency,
          },
          paymentMode,
        },
      })
      .subscribe({
        next: (result: PatientPaymentDto) => {
          this.submitting.set(false);
          this.dialogRef.close(result);
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
      return `Tendered amount must equal the total (${formatMoney(this.dialogData.total, this.dialogData.currency)}).`;
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
