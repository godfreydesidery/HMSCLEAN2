import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import {
  CancellationResultDto,
  CreditNoteControllerService,
} from '../../../api/generated';
import { extractProblem } from '../../../core/error/problem-detail';
import { formatMoney } from '../../../core/billing/format-money';
import { BillingStatusBadgeComponent } from '../shared/billing-status-badge.component';

export interface CancelChargeDialogData {
  billUid: string;
  description: string;
  amount: number;
  currency: string;
}

@Component({
  selector: 'app-cancel-charge-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatIconModule,
    MatDividerModule,
    BillingStatusBadgeComponent,
  ],
  template: `
    <h2 mat-dialog-title id="cancel-charge-dialog-title">Cancel Charge</h2>

    <mat-dialog-content>
      <div class="warning-banner" role="alert">
        <mat-icon aria-hidden="true" class="warning-icon">warning_amber</mat-icon>
        <span>
          You are cancelling: <strong>{{ dialogData.description }}</strong><br />
          Amount: <strong>{{ formatMoney(dialogData.amount, dialogData.currency) }}</strong><br />
          If this bill has been paid, a credit note will be issued automatically.
        </span>
      </div>

      <mat-divider class="section-divider"></mat-divider>

      @if (!result()) {
        <form [formGroup]="form" novalidate>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Reason (required)</mat-label>
            <textarea
              matInput
              formControlName="reference"
              rows="4"
              maxlength="255"
              cdkFocusInitial
              aria-label="Cancellation reason"
              aria-required="true"
              [attr.aria-describedby]="'cancel-hint cancel-error'"
            ></textarea>
            <mat-hint id="cancel-hint">{{ charCount() }}/255</mat-hint>
            @if (form.controls.reference.invalid && form.controls.reference.touched) {
              @if (form.controls.reference.hasError('required') || form.controls.reference.hasError('minlength')) {
                <mat-error id="cancel-error">Cancellation reason is required.</mat-error>
              } @else if (form.controls.reference.hasError('maxlength')) {
                <mat-error id="cancel-error">Reason cannot exceed 255 characters.</mat-error>
              }
            }
          </mat-form-field>
        </form>
      }

      @if (apiError()) {
        <p class="error-msg" role="alert">{{ apiError() }}</p>
      }

      @if (result()) {
        <div class="result-panel" aria-live="polite" role="status">
          <p class="result-title">Charge cancelled.</p>
          @if (result()?.creditNote) {
            <dl class="result-dl">
              <dt>Credit Note</dt>
              <dd>PCN {{ result()?.creditNote?.no ?? '—' }}</dd>
              <dt>Amount</dt>
              <dd>{{ formatMoney(result()?.creditNote?.amount?.amount, result()?.creditNote?.amount?.currency) }}</dd>
              <dt>Status</dt>
              <dd><app-billing-status-badge status="PENDING"></app-billing-status-badge></dd>
            </dl>
          } @else {
            <p class="no-refund-msg">No refund due (bill was not previously paid).</p>
          }
        </div>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      @if (!result()) {
        <button
          mat-button
          type="button"
          (click)="cancel()"
          [disabled]="submitting()"
          aria-label="Close without cancelling"
        >
          Close
        </button>
        <button
          mat-raised-button
          color="warn"
          type="button"
          (click)="confirm()"
          [disabled]="submitting() || form.invalid"
          [attr.aria-busy]="submitting()"
          aria-label="Confirm charge cancellation"
        >
          @if (submitting()) {
            <mat-spinner diameter="20" class="btn-spinner"></mat-spinner>
            Processing…
          } @else {
            Confirm Cancellation
          }
        </button>
      } @else {
        <button
          mat-raised-button
          color="primary"
          type="button"
          (click)="close()"
          aria-label="Close cancellation dialog"
        >
          Close
        </button>
      }
    </mat-dialog-actions>
  `,
  styles: [`
    .warning-banner {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
      background: #fff8e1;
      border: 1px solid #ffe082;
      border-radius: 4px;
      padding: 0.75rem 1rem;
      margin-bottom: 1rem;
      font-size: 0.9rem;
    }
    .warning-icon { color: #f57f17; }
    .section-divider { margin: 0.5rem 0 1rem; }
    .full-width { width: 100%; display: block; }
    .error-msg { color: #c62828; font-size: 0.875rem; margin-top: 0.5rem; }
    .btn-spinner { display: inline-block; margin-right: 0.5rem; }
    .result-panel { margin-top: 1rem; }
    .result-title { font-weight: 600; margin-bottom: 0.5rem; color: #2e7d32; }
    .result-dl { display: grid; grid-template-columns: auto 1fr; gap: 0.25rem 1rem; }
    .result-dl dt { font-weight: 500; color: #555; }
    .result-dl dd { margin: 0; }
    .no-refund-msg { color: #616161; font-style: italic; }
    mat-dialog-content { min-width: 360px; }
  `],
})
export class CancelChargeDialogComponent {
  private readonly fb             = inject(NonNullableFormBuilder);
  private readonly dialogRef      = inject(MatDialogRef<CancelChargeDialogComponent>);
  private readonly creditNoteService = inject(CreditNoteControllerService);
  readonly dialogData             = inject<CancelChargeDialogData>(MAT_DIALOG_DATA);

  readonly formatMoney = formatMoney;

  readonly form = this.fb.group({
    reference: [
      '',
      [Validators.required, Validators.minLength(1), Validators.maxLength(255)],
    ],
  });

  private readonly referenceValue = toSignal(this.form.controls.reference.valueChanges, {
    initialValue: '',
  });

  readonly charCount = computed(() => this.referenceValue().length);

  readonly submitting = signal(false);
  readonly apiError   = signal<string | null>(null);
  readonly result     = signal<CancellationResultDto | null>(null);

  cancel(): void {
    this.dialogRef.close(null);
  }

  close(): void {
    this.dialogRef.close(this.result());
  }

  confirm(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    this.apiError.set(null);

    this.creditNoteService
      .cancelCharge({
        billUid: this.dialogData.billUid,
        cancelChargeRequest: { reference: this.form.controls.reference.value },
      })
      .subscribe({
        next: (res: CancellationResultDto) => {
          this.submitting.set(false);
          this.result.set(res);
        },
        error: (err: unknown) => {
          this.submitting.set(false);
          this.apiError.set(this.mapError(err));
        },
      });
  }

  private mapError(err: unknown): string {
    const problem = extractProblem(err);
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
