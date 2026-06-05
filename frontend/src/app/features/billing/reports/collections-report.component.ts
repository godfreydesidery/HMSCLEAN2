import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  AbstractControl,
  FormControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { BillingReportControllerService, CollectionReportRow } from '../../../api/generated';
import { extractProblem } from '../../../core/error/problem-detail';
import { formatMoney } from '../../../core/billing/format-money';

/** Cross-field validator: 'to' must be >= 'from' (ISO yyyy-MM-dd strings compare lexically) */
const dateRangeValidator: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
  const from = group.get('from')?.value as string | null;
  const to   = group.get('to')?.value   as string | null;
  if (from && to && to < from) {
    return { dateRangeInvalid: true };
  }
  return null;
};

/** Convert a Date to ISO yyyy-MM-dd string */
function toIsoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

const REPORT_COLUMNS = ['itemName', 'channel', 'amount'];

@Component({
  selector: 'app-collections-report',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
  ],
  template: `
    <div class="report-wrapper">

      <!-- Print-only header -->
      <div class="report-print-header print-only">
        <h2>Zana HMIS Hospital</h2>
        <h3>Collections Report</h3>
        @if (lastFrom() && lastTo()) {
          <p>Period: {{ lastFrom() }} to {{ lastTo() }}</p>
        }
        <p>Printed on: {{ printedOn }}</p>
      </div>

      <!-- Filter card -->
      <div class="card report-filter-card print-hidden">
        <div class="card-header">
          <h2 class="h5 mb-0">
            <i class="bi bi-table" aria-hidden="true"></i>
            Collections Report
          </h2>
        </div>

        <div class="card-body">
          <form [formGroup]="form" novalidate (ngSubmit)="runReport()" class="filter-form">

            <div class="mb-3 filter-field">
              <label class="form-label" for="from-date">From date</label>
              <input
                id="from-date"
                type="date"
                class="form-control"
                formControlName="from"
                [max]="todayIso()"
                aria-label="Start date (from)"
                aria-required="true"
                [class.is-invalid]="form.controls.from.invalid && form.controls.from.touched"
              />
              @if (form.controls.from.invalid && form.controls.from.touched) {
                <div class="invalid-feedback">Start date is required.</div>
              }
            </div>

            <div class="mb-3 filter-field">
              <label class="form-label" for="to-date">To date</label>
              <input
                id="to-date"
                type="date"
                class="form-control"
                formControlName="to"
                [max]="todayIso()"
                aria-label="End date (to)"
                aria-required="true"
                [attr.aria-describedby]="'date-range-error'"
                [class.is-invalid]="(form.controls.to.invalid || form.errors?.['dateRangeInvalid']) && form.controls.to.touched"
              />
              @if (form.controls.to.invalid && form.controls.to.touched) {
                <div class="invalid-feedback">End date is required.</div>
              }
              @if (form.errors?.['dateRangeInvalid'] && form.controls.to.touched) {
                <div class="invalid-feedback d-block" id="date-range-error">End date must be on or after the start date.</div>
              }
            </div>

            <div class="mb-3 filter-field">
              <label class="form-label" for="cashier">Cashier (optional)</label>
              <input
                id="cashier"
                type="text"
                class="form-control"
                formControlName="cashier"
                maxlength="100"
                aria-label="Cashier username filter (optional)"
                placeholder="Leave blank for all cashiers"
              />
            </div>

            <button
              class="btn btn-primary"
              type="submit"
              [disabled]="loading()"
              [attr.aria-busy]="loading()"
              aria-label="Run collections report"
            >
              @if (loading()) {
                <span class="spinner-border spinner-border-sm btn-spinner" role="status" aria-hidden="true"></span>
              } @else {
                <i class="bi bi-table"></i>
              }
              {{ loading() ? 'Running…' : 'Run Report' }}
            </button>

          </form>

          @if (!hasRun()) {
            <p class="hint-text">Select a date range and click Run Report.</p>
          }
        </div>
      </div>

      <hr class="my-2 print-hidden">

      <!-- Error -->
      @if (runError()) {
        <p class="error-msg" role="alert">{{ runError() }}</p>
      }

      <!-- Loading -->
      @if (loading()) {
        <div class="state-container" role="status" aria-live="polite" aria-label="Running report">
          <div class="spinner-border text-primary" role="status">
            <span class="visually-hidden">Loading…</span>
          </div>
          <p>Loading report…</p>
        </div>
      }

      <!-- Empty state after run -->
      @if (hasRun() && !loading() && reportRows().length === 0) {
        <div class="empty-state" role="status">
          <i class="bi bi-table" aria-hidden="true"></i>
          <p>No collections found for this period.</p>
        </div>
      }

      <!-- Results card -->
      @if (hasRun() && reportRows().length > 0) {
        <div class="card report-results-card">
          <div class="card-header d-flex justify-content-between align-items-center">
            <h2 class="h5 mb-0">
              Results: {{ lastFrom() }} – {{ lastTo() }}
            </h2>
            <div class="card-actions">
              <button
                class="btn btn-outline-secondary print-hidden"
                type="button"
                (click)="print()"
                aria-label="Print this report"
              >
                <i class="bi bi-printer"></i>
                Print
              </button>
            </div>
          </div>

          <div class="card-body table-content">
            <table
              class="table table-sm align-middle report-table"
              [attr.aria-label]="tableAriaLabel()"
            >
              <thead>
                <tr>
                  <th scope="col">Item</th>
                  <th scope="col">Payment Channel</th>
                  <th scope="col" class="col-right">Amount</th>
                </tr>
              </thead>
              <tbody>
                @for (row of reportRows(); track $index) {
                  <tr>
                    <td>{{ row.itemName ?? '—' }}</td>
                    <td>{{ row.paymentChannel ?? '—' }}</td>
                    <td class="col-right">{{ formatMoney(row.amount, 'TZS') }}</td>
                  </tr>
                }
              </tbody>
              <tfoot>
                <tr
                  class="total-row"
                  role="row"
                  [attr.aria-label]="'Grand total: ' + formatMoney(grandTotal(), 'TZS')"
                >
                  <td><strong>TOTAL</strong></td>
                  <td>—</td>
                  <td
                    class="col-right total-amount-cell"
                    aria-live="polite"
                    [attr.aria-label]="'Grand total: ' + formatMoney(grandTotal(), 'TZS')"
                  >
                    <strong>{{ formatMoney(grandTotal(), 'TZS') }}</strong>
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>
        </div>
      }

    </div>
  `,
  styles: [`
    .report-wrapper {
      padding: 1.5rem;
      max-width: 900px;
      margin: 0 auto;
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
    }
    .filter-form {
      display: flex;
      flex-wrap: wrap;
      gap: 1rem;
      align-items: flex-start;
    }
    .filter-form .filter-field { min-width: 200px; }
    .filter-form button { margin-top: 1.75rem; min-height: 44px; }
    .btn-spinner { display: inline-block; margin-right: 0.5rem; }
    .hint-text { color: #757575; font-size: 0.875rem; margin: 0.5rem 0 0; }
    .error-msg { color: #c62828; font-size: 0.875rem; margin: 0; }
    .state-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.75rem;
      padding: 2rem;
    }
    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.5rem;
      padding: 2rem;
      color: #757575;
    }
    .empty-state i { font-size: 3rem; }
    .table-content { padding: 0; }
    .report-table { width: 100%; margin-bottom: 0; }
    .col-right { text-align: right; }
    .total-row {
      background-color: #e7e0ec;
      border-top: 2px solid #79747e;
    }
    .total-amount-cell { font-size: 1rem; font-weight: 700; }
    .report-print-header { display: none; }

    @media print {
      .print-hidden { display: none !important; }
      .print-only { display: block !important; }
      .report-wrapper { padding: 0; max-width: 100%; }
      .report-results-card {
        box-shadow: none !important;
        border: 1px solid #ddd;
        padding: 0.5rem;
        margin: 0;
      }
      .report-print-header {
        display: block !important;
        text-align: center;
        margin-bottom: 1rem;
      }
      body { background: white; font-size: 11pt; }
    }
  `],
})
export class CollectionsReportComponent {
  private readonly fb                   = inject(NonNullableFormBuilder);
  private readonly billingReportService = inject(BillingReportControllerService);

  readonly formatMoney     = formatMoney;
  readonly displayedColumns = REPORT_COLUMNS;

  readonly today = signal<Date>(new Date());

  /** Today as ISO yyyy-MM-dd for the native date input's [max] */
  readonly todayIso = computed(() => toIsoDate(this.today()));

  readonly form = this.fb.group(
    {
      from:    new FormControl<string | null>(null, { validators: Validators.required }),
      to:      new FormControl<string | null>(null, { validators: Validators.required }),
      cashier: ['', Validators.maxLength(100)],
    },
    { validators: dateRangeValidator },
  );

  readonly loading    = signal(false);
  readonly runError   = signal<string | null>(null);
  readonly reportRows = signal<CollectionReportRow[]>([]);
  readonly hasRun     = signal(false);

  readonly grandTotal = computed(() =>
    this.reportRows().reduce((sum, row) => sum + (row.amount ?? 0), 0),
  );

  readonly lastFrom = signal<string | null>(null);
  readonly lastTo   = signal<string | null>(null);

  readonly tableAriaLabel = computed(() => {
    if (!this.hasRun()) return 'Collections report results';
    return `Collections report: ${this.lastFrom() ?? ''} to ${this.lastTo() ?? ''}`;
  });

  readonly printedOn = new Date().toLocaleString('en-GB');

  runReport(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const { from, to, cashier } = this.form.getRawValue();
    if (!from || !to) return;

    const fromIso = from;
    const toIso   = to;

    this.loading.set(true);
    this.runError.set(null);
    this.reportRows.set([]);
    this.hasRun.set(false);

    this.billingReportService
      .collectionsReport({
        from: fromIso,
        to:   toIso,
        cashier: cashier.trim() || undefined,
      })
      .subscribe({
        next: (data) => {
          this.reportRows.set(data);
          this.hasRun.set(true);
          this.loading.set(false);
          this.lastFrom.set(fromIso);
          this.lastTo.set(toIso);
        },
        error: (err: unknown) => {
          this.loading.set(false);
          this.runError.set(this.mapError(err));
        },
      });
  }

  print(): void {
    window.print();
  }

  private mapError(err: unknown): string {
    const problem = extractProblem(err);
    if (problem.status === 403) {
      return 'You do not have permission to run this report.';
    }
    if (problem.status === 503 || problem.status === 0) {
      return 'Service unavailable. Please try again shortly.';
    }
    return 'Failed to load report. Please try again.';
  }
}
