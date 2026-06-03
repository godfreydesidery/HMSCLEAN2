import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import {
  CancellationResultDto,
  CreditNoteDto,
  DefaultService,
  PatientBillDto,
  PatientPaymentDto,
} from '../../../api/generated';
import { extractProblem } from '../../../core/error/problem-detail';
import { formatMoney } from '../../../core/billing/format-money';
import { HmisMoneyPipe } from '../../../core/billing/hmis-money.pipe';
import { BillingStatusBadgeComponent } from '../shared/billing-status-badge.component';
import { PatientContextComponent } from '../shared/patient-context.component';
import {
  RecordPaymentDialogComponent,
  RecordPaymentDialogData,
} from './record-payment-dialog.component';
import {
  CancelChargeDialogComponent,
  CancelChargeDialogData,
} from './cancel-charge-dialog.component';

/** A flat bill row derived from PatientBillDto */
export interface BillRow {
  uid: string;
  description: string;
  billItem: string;
  kind: string;
  qty: number;
  amount: number;
  currency: string;
  status: string;
  paymentType: string;
  membershipNo: string;
  selectable: boolean;
  isPaid: boolean;
}

function isBillSelectable(status: string | undefined): boolean {
  return status === 'UNPAID' || status === 'VERIFIED';
}

function toBillRow(b: PatientBillDto): BillRow {
  return {
    uid:         b.uid         ?? '',
    description: b.description ?? b.billItem ?? '—',
    billItem:    b.billItem    ?? '—',
    kind:        b.kind        ?? '',
    qty:         b.qty         ?? 1,
    amount:      b.amount?.amount   ?? 0,
    currency:    b.amount?.currency ?? 'TZS',
    status:      b.status      ?? 'NONE',
    paymentType: b.paymentType ?? '',
    membershipNo: b.membershipNo ?? '',
    selectable:  isBillSelectable(b.status),
    isPaid:      b.status === 'PAID',
  };
}

const BILL_COLUMNS = ['select', 'description', 'qty', 'amount', 'status', 'actions'];

@Component({
  selector: 'app-cashier-workspace',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatCardModule,
    MatTableModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatDividerModule,
    MatSnackBarModule,
    MatExpansionModule,
    MatDialogModule,
    HmisMoneyPipe,
    BillingStatusBadgeComponent,
    PatientContextComponent,
  ],
  template: `
    <div class="billing-cashier-wrapper">

      <!-- Patient context card -->
      <mat-card class="patient-context-card">
        <mat-card-header>
          <mat-card-title>Patient Bills</mat-card-title>
          <mat-card-subtitle>Enter a patient UID to load their bills</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <app-patient-context
            [loading]="loading()"
            (patientUidConfirmed)="loadBills($event)"
          ></app-patient-context>
        </mat-card-content>
      </mat-card>

      <!-- Loading state -->
      @if (loading()) {
        <div class="state-container" role="status" aria-live="polite" aria-label="Loading patient bills">
          <mat-spinner diameter="40"></mat-spinner>
          <p>Loading bills…</p>
        </div>
      }

      <!-- Error state -->
      @if (loadError()) {
        <div class="error-container">
          <p class="error-msg" role="alert">{{ loadError() }}</p>
          <button
            mat-stroked-button
            (click)="retryLoad()"
            aria-label="Retry loading bills"
          >
            Retry
          </button>
        </div>
      }

      <!-- Bills card -->
      @if (!loading() && !loadError() && billRows().length > 0) {
        <mat-card class="bills-card">
          <mat-card-header>
            <mat-card-title>
              Bills for patient: {{ truncateUid(patientUid()) }}
            </mat-card-title>
            <mat-card-subtitle>{{ billRows().length }} bill(s) loaded</mat-card-subtitle>
          </mat-card-header>

          <mat-card-content class="table-content">
            <div class="table-scroll-wrapper" role="region" aria-label="Bills table">
              <table
                mat-table
                [dataSource]="billRows()"
                class="bills-table"
                [attr.aria-label]="tableAriaLabel()"
                role="grid"
                [attr.aria-rowcount]="billRows().length + 1"
              >

                <!-- Select column -->
                <ng-container matColumnDef="select">
                  <th mat-header-cell *matHeaderCellDef class="col-select">
                    <mat-checkbox
                      [checked]="allSelectableChecked()"
                      [indeterminate]="someSelectableChecked()"
                      (change)="toggleAll($event.checked)"
                      aria-label="Select all payable bills"
                    ></mat-checkbox>
                  </th>
                  <td mat-cell *matCellDef="let row" class="col-select" role="gridcell">
                    @if (row.selectable) {
                      <mat-checkbox
                        [checked]="isSelected(row.uid)"
                        (change)="toggleRow(row.uid, $event.checked)"
                        [attr.aria-label]="'Select bill: ' + row.description"
                      ></mat-checkbox>
                    } @else {
                      <mat-checkbox
                        [disabled]="true"
                        [attr.aria-disabled]="true"
                        [attr.aria-label]="'Bill not payable: ' + row.description"
                        [matTooltip]="disabledReason(row.status)"
                      ></mat-checkbox>
                    }
                  </td>
                </ng-container>

                <!-- Description column -->
                <ng-container matColumnDef="description">
                  <th mat-header-cell *matHeaderCellDef>Description</th>
                  <td mat-cell *matCellDef="let row" role="gridcell">
                    <span
                      [matTooltip]="row.description.length > 40 ? row.description : ''"
                    >{{ truncateDesc(row.description) }}</span>
                  </td>
                </ng-container>

                <!-- Qty column -->
                <ng-container matColumnDef="qty">
                  <th mat-header-cell *matHeaderCellDef class="col-right">Qty</th>
                  <td mat-cell *matCellDef="let row" class="col-right" role="gridcell">{{ row.qty }}</td>
                </ng-container>

                <!-- Amount column -->
                <ng-container matColumnDef="amount">
                  <th mat-header-cell *matHeaderCellDef class="col-right">Amount</th>
                  <td mat-cell *matCellDef="let row" class="col-right" role="gridcell">
                    {{ formatMoney(row.amount, row.currency) }}
                  </td>
                </ng-container>

                <!-- Status column -->
                <ng-container matColumnDef="status">
                  <th mat-header-cell *matHeaderCellDef class="col-center">Status</th>
                  <td mat-cell *matCellDef="let row" class="col-center" role="gridcell">
                    <app-billing-status-badge [status]="row.status"></app-billing-status-badge>
                  </td>
                </ng-container>

                <!-- Actions column -->
                <ng-container matColumnDef="actions">
                  <th mat-header-cell *matHeaderCellDef></th>
                  <td mat-cell *matCellDef="let row" class="col-center" role="gridcell">
                    @if (row.isPaid) {
                      <button
                        mat-icon-button
                        class="large-icon-btn"
                        (click)="openCancelDialog(row)"
                        [attr.aria-label]="'Cancel charge: ' + row.description"
                        matTooltip="Cancel / Refund"
                      >
                        <mat-icon>cancel</mat-icon>
                      </button>
                    }
                  </td>
                </ng-container>

                <tr
                  mat-header-row
                  *matHeaderRowDef="displayedColumns; sticky: true"
                  class="sticky-header"
                ></tr>
                <tr
                  mat-row
                  *matRowDef="let row; columns: displayedColumns;"
                  [class.row-disabled]="!row.selectable"
                ></tr>

              </table>
            </div>
          </mat-card-content>

          <mat-divider></mat-divider>

          <mat-card-actions class="card-actions">
            <div class="selection-info" aria-live="polite" aria-label="Selection summary">
              <span>Selected: <strong>{{ selectedCount() }}</strong> bill(s)</span>
              <span class="total-amount">Total: <strong>{{ selectedTotalFormatted() }}</strong></span>
            </div>
            <div class="action-buttons">
              <button
                mat-stroked-button
                [disabled]="selectedCount() === 0"
                (click)="clearSelection()"
                aria-label="Clear bill selection"
              >
                Clear Selection
              </button>
              <button
                mat-raised-button
                color="primary"
                [disabled]="selectedCount() === 0 || paymentPending()"
                (click)="openPaymentDialog()"
                aria-label="Record payment for selected bills"
              >
                <mat-icon>payments</mat-icon>
                Record Payment
              </button>
            </div>
          </mat-card-actions>
        </mat-card>
      }

      <!-- Empty state -->
      @if (!loading() && !loadError() && hasLoaded() && billRows().length === 0) {
        <div class="empty-state" role="status">
          <mat-icon aria-hidden="true">receipt_long</mat-icon>
          <p>No bills found for this patient.</p>
        </div>
      }

      <!-- Credit notes expansion panel -->
      @if (!loading() && patientUid()) {
        <mat-expansion-panel class="credit-notes-panel">
          <mat-expansion-panel-header>
            <mat-panel-title>Credit Notes</mat-panel-title>
            <mat-panel-description>
              {{ creditNotes().length }} note(s)
            </mat-panel-description>
          </mat-expansion-panel-header>

          @if (creditNotesLoading()) {
            <mat-spinner diameter="32" aria-label="Loading credit notes"></mat-spinner>
          } @else if (creditNotesError()) {
            <p class="error-msg" role="alert">{{ creditNotesError() }}</p>
          } @else if (creditNotes().length === 0) {
            <p class="muted-text">No credit notes found.</p>
          } @else {
            <table
              mat-table
              [dataSource]="creditNotes()"
              class="credit-notes-table"
              aria-label="Credit notes"
            >
              <ng-container matColumnDef="no">
                <th mat-header-cell *matHeaderCellDef>No.</th>
                <td mat-cell *matCellDef="let cn">PCN {{ cn.no ?? '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="amount">
                <th mat-header-cell *matHeaderCellDef class="col-right">Amount</th>
                <td mat-cell *matCellDef="let cn" class="col-right">{{ cn.amount | hmisMoney }}</td>
              </ng-container>
              <ng-container matColumnDef="status">
                <th mat-header-cell *matHeaderCellDef class="col-center">Status</th>
                <td mat-cell *matCellDef="let cn" class="col-center">
                  <app-billing-status-badge [status]="cn.status ?? ''"></app-billing-status-badge>
                </td>
              </ng-container>
              <ng-container matColumnDef="reference">
                <th mat-header-cell *matHeaderCellDef>Reference</th>
                <td mat-cell *matCellDef="let cn">{{ cn.reference ?? '—' }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="creditNoteColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: creditNoteColumns;"></tr>
            </table>
          }
        </mat-expansion-panel>
      }

    </div>
  `,
  styles: [`
    .billing-cashier-wrapper {
      padding: 1.5rem;
      max-width: 1200px;
      margin: 0 auto;
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
    }
    .patient-context-card mat-card-content { padding-top: 0.5rem; }
    .state-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.75rem;
      padding: 2rem;
    }
    .error-container {
      padding: 1rem;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 0.5rem;
    }
    .error-msg { color: #c62828; font-size: 0.875rem; margin: 0; }
    .table-content { padding: 0; }
    .table-scroll-wrapper { overflow-x: auto; }
    .bills-table { width: 100%; }
    .sticky-header { position: sticky; top: 0; z-index: 2; background: white; }
    .col-right  { text-align: right; }
    .col-center { text-align: center; }
    .col-select { width: 48px; padding: 0 8px; }
    .row-disabled { opacity: 0.6; }
    .card-actions {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0.75rem 1rem;
      flex-wrap: wrap;
      gap: 0.5rem;
    }
    .selection-info {
      display: flex;
      gap: 1.5rem;
      align-items: center;
      font-size: 0.9rem;
    }
    .total-amount strong { font-size: 1rem; font-weight: 600; }
    .action-buttons {
      display: flex;
      gap: 0.5rem;
    }
    .large-icon-btn {
      min-width: 44px;
      min-height: 44px;
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
    .credit-notes-panel { margin-top: 0; }
    .credit-notes-table { width: 100%; }
    .muted-text { color: #757575; font-style: italic; padding: 0.5rem 0; }
    @media (max-width: 767px) {
      .billing-cashier-wrapper { padding: 0.75rem; }
    }
  `],
})
export class CashierWorkspaceComponent {
  private readonly defaultService = inject(DefaultService);
  private readonly dialog         = inject(MatDialog);
  private readonly snackBar       = inject(MatSnackBar);
  private readonly router         = inject(Router);

  readonly formatMoney    = formatMoney;
  readonly displayedColumns = BILL_COLUMNS;
  readonly creditNoteColumns = ['no', 'amount', 'status', 'reference'];

  // Patient context
  readonly patientUid = signal<string | null>(null);
  readonly hasLoaded  = signal(false);

  // Load state
  readonly loading   = signal(false);
  readonly loadError = signal<string | null>(null);

  // Bills data (flat list from listBills)
  private readonly bills = signal<PatientBillDto[]>([]);

  // Derived bill rows
  readonly billRows = computed<BillRow[]>(() => this.bills().map(toBillRow));

  // Selection state
  private readonly selectedUids = signal<Set<string>>(new Set());

  readonly selectedCount = computed(() => this.selectedUids().size);

  readonly selectedTotal = computed(() => {
    const uids = this.selectedUids();
    return this.billRows()
      .filter(r => uids.has(r.uid))
      .reduce((sum, r) => sum + r.amount, 0);
  });

  readonly selectedCurrency = computed(() => {
    const uids = this.selectedUids();
    const first = this.billRows().find(r => uids.has(r.uid));
    return first?.currency ?? 'TZS';
  });

  readonly selectedTotalFormatted = computed(() =>
    formatMoney(this.selectedTotal(), this.selectedCurrency()),
  );

  // Select-all checkbox states
  readonly selectableRows = computed(() => this.billRows().filter(r => r.selectable));
  readonly allSelectableChecked = computed(() => {
    const selectable = this.selectableRows();
    if (selectable.length === 0) return false;
    const uids = this.selectedUids();
    return selectable.every(r => uids.has(r.uid));
  });
  readonly someSelectableChecked = computed(() => {
    if (this.allSelectableChecked()) return false;
    const uids = this.selectedUids();
    return this.selectableRows().some(r => uids.has(r.uid));
  });

  readonly tableAriaLabel = computed(
    () => `Patient bills table. ${this.billRows().length} rows.`,
  );

  // Payment pending flag
  readonly paymentPending = signal(false);

  // Credit notes
  readonly creditNotes        = signal<CreditNoteDto[]>([]);
  readonly creditNotesLoading = signal(false);
  readonly creditNotesError   = signal<string | null>(null);

  loadBills(uid: string): void {
    this.patientUid.set(uid);
    this.loading.set(true);
    this.loadError.set(null);
    this.bills.set([]);
    this.selectedUids.set(new Set());
    this.hasLoaded.set(false);

    this.defaultService.listBills({ patientUid: uid }).subscribe({
      next: (data) => {
        this.bills.set(data);
        this.loading.set(false);
        this.hasLoaded.set(true);
        this.loadCreditNotes(uid);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.loadError.set(this.mapError(err));
      },
    });
  }

  retryLoad(): void {
    const uid = this.patientUid();
    if (uid) {
      this.loadBills(uid);
    }
  }

  private loadCreditNotes(uid: string): void {
    this.creditNotesLoading.set(true);
    this.creditNotesError.set(null);
    this.creditNotes.set([]);

    this.defaultService.listCreditNotes({ patientUid: uid }).subscribe({
      next: (data) => {
        this.creditNotes.set(data);
        this.creditNotesLoading.set(false);
      },
      error: (err: unknown) => {
        this.creditNotesLoading.set(false);
        this.creditNotesError.set(this.mapError(err));
      },
    });
  }

  isSelected(uid: string): boolean {
    return this.selectedUids().has(uid);
  }

  toggleRow(uid: string, checked: boolean): void {
    const next = new Set(this.selectedUids());
    if (checked) {
      next.add(uid);
    } else {
      next.delete(uid);
    }
    this.selectedUids.set(next);
  }

  toggleAll(checked: boolean): void {
    if (checked) {
      const next = new Set(this.selectedUids());
      this.selectableRows().forEach(r => next.add(r.uid));
      this.selectedUids.set(next);
    } else {
      const next = new Set(this.selectedUids());
      this.selectableRows().forEach(r => next.delete(r.uid));
      this.selectedUids.set(next);
    }
  }

  clearSelection(): void {
    this.selectedUids.set(new Set());
  }

  openPaymentDialog(): void {
    const uids = this.selectedUids();
    const selected = this.billRows().filter(r => uids.has(r.uid));
    const currency = this.selectedCurrency();

    const data: RecordPaymentDialogData = {
      billUids: selected.map(r => r.uid),
      total: this.selectedTotal(),
      currency,
      billSummaries: selected.map(r => ({
        description: r.description,
        amount: r.amount,
      })),
    };

    this.paymentPending.set(true);

    const ref = this.dialog.open(RecordPaymentDialogComponent, {
      data,
      ariaLabelledBy: 'record-payment-dialog-title',
      disableClose: false,
    });

    ref.afterClosed().subscribe((result: PatientPaymentDto | null | undefined) => {
      this.paymentPending.set(false);
      if (result?.uid) {
        this.snackBar.open('Payment recorded successfully.', 'Dismiss', { duration: 3000 });
        void this.router.navigate(['/billing/receipt', result.uid]);
      }
    });
  }

  openCancelDialog(row: BillRow): void {
    const data: CancelChargeDialogData = {
      billUid: row.uid,
      description: row.description,
      amount: row.amount,
      currency: row.currency,
    };

    const ref = this.dialog.open(CancelChargeDialogComponent, {
      data,
      ariaLabelledBy: 'cancel-charge-dialog-title',
    });

    ref.afterClosed().subscribe((result: CancellationResultDto | null | undefined) => {
      if (result) {
        const uid = this.patientUid();
        if (uid) {
          this.loadBills(uid);
        }
      }
    });
  }

  truncateUid(uid: string | null): string {
    if (!uid) return '—';
    return uid.length > 8 ? `${uid.slice(0, 8)}…` : uid;
  }

  truncateDesc(desc: string): string {
    return desc.length > 40 ? `${desc.slice(0, 40)}…` : desc;
  }

  disabledReason(status: string): string {
    switch (status) {
      case 'PAID':     return 'Already paid';
      case 'COVERED':  return 'Covered by insurance';
      case 'CANCELED': return 'This charge has been cancelled';
      case 'NONE':     return 'Not billable';
      default:         return 'Not payable';
    }
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
