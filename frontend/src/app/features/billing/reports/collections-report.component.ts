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
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { CollectionReportRow, DefaultService } from '../../../api/generated';
import { extractProblem } from '../../../core/error/problem-detail';
import { formatMoney } from '../../../core/billing/format-money';

/** Cross-field validator: 'to' must be >= 'from' */
const dateRangeValidator: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
  const from = group.get('from')?.value as Date | null;
  const to   = group.get('to')?.value   as Date | null;
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
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatDatepickerModule,
    MatNativeDateModule,
    MatTableModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    MatIconModule,
    MatDividerModule,
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
      <mat-card class="report-filter-card print-hidden">
        <mat-card-header>
          <mat-card-title>
            <mat-icon aria-hidden="true">table_chart</mat-icon>
            Collections Report
          </mat-card-title>
        </mat-card-header>

        <mat-card-content>
          <form [formGroup]="form" novalidate (ngSubmit)="runReport()" class="filter-form">

            <mat-form-field appearance="outline">
              <mat-label>From date</mat-label>
              <input
                matInput
                [matDatepicker]="fromPicker"
                formControlName="from"
                [max]="today()"
                aria-label="Start date (from)"
                aria-required="true"
                readonly
              />
              <mat-datepicker-toggle matIconSuffix [for]="fromPicker"></mat-datepicker-toggle>
              <mat-datepicker #fromPicker></mat-datepicker>
              @if (form.controls.from.invalid && form.controls.from.touched) {
                <mat-error>Start date is required.</mat-error>
              }
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>To date</mat-label>
              <input
                matInput
                [matDatepicker]="toPicker"
                formControlName="to"
                [max]="today()"
                aria-label="End date (to)"
                aria-required="true"
                [attr.aria-describedby]="'date-range-error'"
                readonly
              />
              <mat-datepicker-toggle matIconSuffix [for]="toPicker"></mat-datepicker-toggle>
              <mat-datepicker #toPicker></mat-datepicker>
              @if (form.controls.to.invalid && form.controls.to.touched) {
                <mat-error>End date is required.</mat-error>
              }
              @if (form.errors?.['dateRangeInvalid'] && form.controls.to.touched) {
                <mat-error id="date-range-error">End date must be on or after the start date.</mat-error>
              }
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Cashier (optional)</mat-label>
              <input
                matInput
                formControlName="cashier"
                maxlength="100"
                aria-label="Cashier username filter (optional)"
                placeholder="Leave blank for all cashiers"
              />
            </mat-form-field>

            <button
              mat-raised-button
              color="primary"
              type="submit"
              [disabled]="loading()"
              [attr.aria-busy]="loading()"
              aria-label="Run collections report"
            >
              @if (loading()) {
                <mat-spinner diameter="20" class="btn-spinner"></mat-spinner>
              } @else {
                <mat-icon>table_chart</mat-icon>
              }
              {{ loading() ? 'Running…' : 'Run Report' }}
            </button>

          </form>

          @if (!hasRun()) {
            <p class="hint-text">Select a date range and click Run Report.</p>
          }
        </mat-card-content>
      </mat-card>

      <mat-divider class="print-hidden"></mat-divider>

      <!-- Error -->
      @if (runError()) {
        <p class="error-msg" role="alert">{{ runError() }}</p>
      }

      <!-- Loading -->
      @if (loading()) {
        <div class="state-container" role="status" aria-live="polite" aria-label="Running report">
          <mat-spinner diameter="40"></mat-spinner>
          <p>Loading report…</p>
        </div>
      }

      <!-- Empty state after run -->
      @if (hasRun() && !loading() && reportRows().length === 0) {
        <div class="empty-state" role="status">
          <mat-icon aria-hidden="true">table_chart</mat-icon>
          <p>No collections found for this period.</p>
        </div>
      }

      <!-- Results card -->
      @if (hasRun() && reportRows().length > 0) {
        <mat-card class="report-results-card">
          <mat-card-header>
            <mat-card-title>
              Results: {{ lastFrom() }} – {{ lastTo() }}
            </mat-card-title>
            <mat-card-actions>
              <button
                mat-stroked-button
                class="print-hidden"
                (click)="print()"
                aria-label="Print this report"
              >
                <mat-icon>print</mat-icon>
                Print
              </button>
            </mat-card-actions>
          </mat-card-header>

          <mat-card-content class="table-content">
            <table
              mat-table
              [dataSource]="reportRows()"
              class="report-table"
              [attr.aria-label]="tableAriaLabel()"
            >
              <!-- Item Name column -->
              <ng-container matColumnDef="itemName">
                <th mat-header-cell *matHeaderCellDef>Item</th>
                <td mat-cell *matCellDef="let row">{{ row.itemName ?? '—' }}</td>
                <td mat-footer-cell *matFooterCellDef><strong>TOTAL</strong></td>
              </ng-container>

              <!-- Channel column -->
              <ng-container matColumnDef="channel">
                <th mat-header-cell *matHeaderCellDef>Payment Channel</th>
                <td mat-cell *matCellDef="let row">{{ row.paymentChannel ?? '—' }}</td>
                <td mat-footer-cell *matFooterCellDef>—</td>
              </ng-container>

              <!-- Amount column -->
              <ng-container matColumnDef="amount">
                <th mat-header-cell *matHeaderCellDef class="col-right">Amount</th>
                <td mat-cell *matCellDef="let row" class="col-right">
                  {{ formatMoney(row.amount, 'TZS') }}
                </td>
                <td
                  mat-footer-cell
                  *matFooterCellDef
                  class="col-right total-amount-cell"
                  aria-live="polite"
                  [attr.aria-label]="'Grand total: ' + formatMoney(grandTotal(), 'TZS')"
                >
                  <strong>{{ formatMoney(grandTotal(), 'TZS') }}</strong>
                </td>
              </ng-container>

              <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
              <tr
                mat-footer-row
                *matFooterRowDef="displayedColumns"
                class="total-row"
                role="row"
                [attr.aria-label]="'Grand total: ' + formatMoney(grandTotal(), 'TZS')"
              ></tr>
            </table>
          </mat-card-content>
        </mat-card>
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
    .filter-form mat-form-field { min-width: 200px; }
    .filter-form button { margin-top: 4px; min-height: 44px; }
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
    .empty-state mat-icon { font-size: 3rem; width: 3rem; height: 3rem; }
    .table-content { padding: 0; }
    .report-table { width: 100%; }
    .col-right { text-align: right; }
    .total-row {
      background-color: var(--mat-sys-surface-variant, #e7e0ec);
      border-top: 2px solid var(--mat-sys-outline, #79747e);
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
  private readonly fb             = inject(NonNullableFormBuilder);
  private readonly defaultService = inject(DefaultService);

  readonly formatMoney     = formatMoney;
  readonly displayedColumns = REPORT_COLUMNS;

  readonly today = signal<Date>(new Date());

  readonly form = this.fb.group(
    {
      from:    new FormControl<Date | null>(null, { validators: Validators.required }),
      to:      new FormControl<Date | null>(null, { validators: Validators.required }),
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

    const fromIso = toIsoDate(from);
    const toIso   = toIsoDate(to);

    this.loading.set(true);
    this.runError.set(null);
    this.reportRows.set([]);
    this.hasRun.set(false);

    this.defaultService
      .getCollectionsReport({
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
