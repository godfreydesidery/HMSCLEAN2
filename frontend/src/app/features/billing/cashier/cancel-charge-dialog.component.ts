import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { NgbActiveModal } from '@ng-bootstrap/ng-bootstrap';
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
    BillingStatusBadgeComponent,
  ],
  template: `
    <div class="modal-header">
      <h5 class="modal-title" id="cancel-charge-dialog-title">Cancel Charge</h5>
      <button
        type="button"
        class="btn-close"
        aria-label="Close"
        (click)="activeModal.dismiss('cancel')"
      ></button>
    </div>

    <div class="modal-body">
      <div class="warning-banner" role="alert">
        <i class="bi bi-exclamation-triangle warning-icon" aria-hidden="true"></i>
        <span>
          You are cancelling: <strong>{{ data.description }}</strong><br />
          Amount: <strong>{{ formatMoney(data.amount, data.currency) }}</strong><br />
          If this bill has been paid, a credit note will be issued automatically.
        </span>
      </div>

      <hr class="section-divider" />

      @if (!result()) {
        <form [formGroup]="form" novalidate>
          <div class="mb-3 full-width">
            <label class="form-label" for="cancel-reference">Reason (required)</label>
            <textarea
              id="cancel-reference"
              class="form-control"
              formControlName="reference"
              rows="4"
              maxlength="255"
              aria-label="Cancellation reason"
              aria-required="true"
              [attr.aria-describedby]="'cancel-hint cancel-error'"
              [class.is-invalid]="form.controls.reference.invalid && form.controls.reference.touched"
            ></textarea>
            <div class="form-text" id="cancel-hint">{{ charCount() }}/255</div>
            @if (form.controls.reference.invalid && form.controls.reference.touched) {
              @if (form.controls.reference.hasError('required') || form.controls.reference.hasError('minlength')) {
                <div class="invalid-feedback d-block" id="cancel-error">Cancellation reason is required.</div>
              } @else if (form.controls.reference.hasError('maxlength')) {
                <div class="invalid-feedback d-block" id="cancel-error">Reason cannot exceed 255 characters.</div>
              }
            }
          </div>
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
    </div>

    <div class="modal-footer">
      @if (!result()) {
        <button
          class="btn btn-link"
          type="button"
          (click)="cancel()"
          [disabled]="submitting()"
          aria-label="Close without cancelling"
        >
          Close
        </button>
        <button
          class="btn btn-danger"
          type="button"
          (click)="confirm()"
          [disabled]="submitting() || form.invalid"
          [attr.aria-busy]="submitting()"
          aria-label="Confirm charge cancellation"
        >
          @if (submitting()) {
            <span class="spinner-border spinner-border-sm btn-spinner" role="status" aria-hidden="true"></span>
            Processing…
          } @else {
            Confirm Cancellation
          }
        </button>
      } @else {
        <button
          class="btn btn-primary"
          type="button"
          (click)="close()"
          aria-label="Close cancellation dialog"
        >
          Close
        </button>
      }
    </div>
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
    .warning-icon { color: #f57f17; font-size: 1.25rem; }
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
    .modal-body { min-width: 360px; }
  `],
})
export class CancelChargeDialogComponent {
  private readonly fb             = inject(NonNullableFormBuilder);
  protected readonly activeModal  = inject(NgbActiveModal);
  private readonly creditNoteService = inject(CreditNoteControllerService);

  data!: CancelChargeDialogData;

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
    this.activeModal.dismiss('cancel');
  }

  close(): void {
    this.activeModal.close(this.result());
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
        billUid: this.data.billUid,
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
