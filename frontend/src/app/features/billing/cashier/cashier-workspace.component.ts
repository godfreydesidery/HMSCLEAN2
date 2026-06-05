import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { NgbModal } from '@ng-bootstrap/ng-bootstrap';
import {
  CancellationResultDto,
  CreditNoteControllerService,
  CreditNoteDto,
  PatientBillControllerService,
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

/** Transient inline alert message rendered as a Bootstrap dismissible alert */
interface FlashMessage {
  kind: 'success' | 'danger';
  text: string;
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
    HmisMoneyPipe,
    BillingStatusBadgeComponent,
    PatientContextComponent,
  ],
  template: `
    <div class="billing-cashier-wrapper">

      <!-- Transient flash message -->
      @if (flash(); as msg) {
        <div
          class="alert alert-dismissible fade show"
          [class.alert-success]="msg.kind === 'success'"
          [class.alert-danger]="msg.kind === 'danger'"
          role="alert"
        >
          {{ msg.text }}
          <button
            type="button"
            class="btn-close"
            aria-label="Dismiss"
            (click)="flash.set(null)"
          ></button>
        </div>
      }

      <!-- Patient context card -->
      <div class="card patient-context-card">
        <div class="card-header">
          <h2 class="h5 mb-0">Patient Bills</h2>
          <small class="text-muted">Enter a patient UID to load their bills</small>
        </div>
        <div class="card-body">
          <app-patient-context
            [loading]="loading()"
            (patientUidConfirmed)="loadBills($event)"
          ></app-patient-context>
        </div>
      </div>

      <!-- Loading state -->
      @if (loading()) {
        <div class="state-container" role="status" aria-live="polite" aria-label="Loading patient bills">
          <div class="spinner-border text-primary" role="status">
            <span class="visually-hidden">Loading…</span>
          </div>
          <p>Loading bills…</p>
        </div>
      }

      <!-- Error state -->
      @if (loadError()) {
        <div class="error-container">
          <p class="error-msg" role="alert">{{ loadError() }}</p>
          <button
            type="button"
            class="btn btn-outline-secondary"
            (click)="retryLoad()"
            aria-label="Retry loading bills"
          >
            Retry
          </button>
        </div>
      }

      <!-- Bills card -->
      @if (!loading() && !loadError() && billRows().length > 0) {
        <div class="card bills-card">
          <div class="card-header">
            <h3 class="h5 mb-0">
              Bills for patient: {{ truncateUid(patientUid()) }}
            </h3>
            <small class="text-muted">{{ billRows().length }} bill(s) loaded</small>
          </div>

          <div class="card-body table-content">
            <div class="table-scroll-wrapper" role="region" aria-label="Bills table">
              <table
                class="table table-sm align-middle bills-table"
                [attr.aria-label]="tableAriaLabel()"
                role="grid"
                [attr.aria-rowcount]="billRows().length + 1"
              >
                <thead>
                  <tr class="sticky-header">
                    <th scope="col" class="col-select">
                      <div class="form-check">
                        <input
                          type="checkbox"
                          class="form-check-input"
                          [checked]="allSelectableChecked()"
                          [indeterminate]="someSelectableChecked()"
                          (change)="toggleAll($any($event.target).checked)"
                          aria-label="Select all payable bills"
                        />
                      </div>
                    </th>
                    <th scope="col">Description</th>
                    <th scope="col" class="col-right">Qty</th>
                    <th scope="col" class="col-right">Amount</th>
                    <th scope="col" class="col-center">Status</th>
                    <th scope="col"></th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of billRows(); track row.uid) {
                    <tr [class.row-disabled]="!row.selectable">
                      <!-- Select column -->
                      <td class="col-select" role="gridcell">
                        @if (row.selectable) {
                          <div class="form-check">
                            <input
                              type="checkbox"
                              class="form-check-input"
                              [checked]="isSelected(row.uid)"
                              (change)="toggleRow(row.uid, $any($event.target).checked)"
                              [attr.aria-label]="'Select bill: ' + row.description"
                            />
                          </div>
                        } @else {
                          <div class="form-check" [title]="disabledReason(row.status)">
                            <input
                              type="checkbox"
                              class="form-check-input"
                              [disabled]="true"
                              [attr.aria-disabled]="true"
                              [attr.aria-label]="'Bill not payable: ' + row.description"
                            />
                          </div>
                        }
                      </td>

                      <!-- Description column -->
                      <td role="gridcell">
                        <span
                          [title]="row.description.length > 40 ? row.description : ''"
                        >{{ truncateDesc(row.description) }}</span>
                      </td>

                      <!-- Qty column -->
                      <td class="col-right" role="gridcell">{{ row.qty }}</td>

                      <!-- Amount column -->
                      <td class="col-right" role="gridcell">
                        {{ formatMoney(row.amount, row.currency) }}
                      </td>

                      <!-- Status column -->
                      <td class="col-center" role="gridcell">
                        <app-billing-status-badge [status]="row.status"></app-billing-status-badge>
                      </td>

                      <!-- Actions column -->
                      <td class="col-center" role="gridcell">
                        @if (row.isPaid) {
                          <button
                            type="button"
                            class="btn btn-link p-1 large-icon-btn"
                            (click)="openCancelDialog(row)"
                            [attr.aria-label]="'Cancel charge: ' + row.description"
                            title="Cancel / Refund"
                          >
                            <i class="bi bi-x-circle" aria-hidden="true"></i>
                          </button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>

          <hr class="my-2" />

          <div class="card-body card-actions">
            <div class="selection-info" aria-live="polite" aria-label="Selection summary">
              <span>Selected: <strong>{{ selectedCount() }}</strong> bill(s)</span>
              <span class="total-amount">Total: <strong>{{ selectedTotalFormatted() }}</strong></span>
            </div>
            <div class="action-buttons">
              <button
                type="button"
                class="btn btn-outline-secondary"
                [disabled]="selectedCount() === 0"
                (click)="clearSelection()"
                aria-label="Clear bill selection"
              >
                Clear Selection
              </button>
              <button
                type="button"
                class="btn btn-primary"
                [disabled]="selectedCount() === 0 || paymentPending()"
                (click)="openPaymentDialog()"
                aria-label="Record payment for selected bills"
              >
                <i class="bi bi-cash-coin me-1" aria-hidden="true"></i>
                Record Payment
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Empty state -->
      @if (!loading() && !loadError() && hasLoaded() && billRows().length === 0) {
        <div class="empty-state" role="status">
          <i class="bi bi-receipt empty-icon" aria-hidden="true"></i>
          <p>No bills found for this patient.</p>
        </div>
      }

      <!-- Credit notes accordion -->
      @if (!loading() && patientUid()) {
        <div class="accordion credit-notes-panel" id="credit-notes-accordion">
          <div class="accordion-item">
            <h2 class="accordion-header" id="credit-notes-heading">
              <button
                type="button"
                class="accordion-button collapsed"
                (click)="creditNotesExpanded.set(!creditNotesExpanded())"
                [class.collapsed]="!creditNotesExpanded()"
                [attr.aria-expanded]="creditNotesExpanded()"
                aria-controls="credit-notes-collapse"
              >
                <span class="me-2">Credit Notes</span>
                <span class="text-muted small">{{ creditNotes().length }} note(s)</span>
              </button>
            </h2>
            <div
              id="credit-notes-collapse"
              class="accordion-collapse collapse"
              [class.show]="creditNotesExpanded()"
              aria-labelledby="credit-notes-heading"
            >
              <div class="accordion-body">
                @if (creditNotesLoading()) {
                  <div class="spinner-border spinner-border-sm text-primary" role="status" aria-label="Loading credit notes">
                    <span class="visually-hidden">Loading…</span>
                  </div>
                } @else if (creditNotesError()) {
                  <p class="error-msg" role="alert">{{ creditNotesError() }}</p>
                } @else if (creditNotes().length === 0) {
                  <p class="muted-text">No credit notes found.</p>
                } @else {
                  <table class="table table-sm align-middle credit-notes-table" aria-label="Credit notes">
                    <thead>
                      <tr>
                        <th scope="col">No.</th>
                        <th scope="col" class="col-right">Amount</th>
                        <th scope="col" class="col-center">Status</th>
                        <th scope="col">Reference</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (cn of creditNotes(); track cn.uid) {
                        <tr>
                          <td>PCN {{ cn.no ?? '—' }}</td>
                          <td class="col-right">{{ cn.amount | hmisMoney }}</td>
                          <td class="col-center">
                            <app-billing-status-badge [status]="cn.status ?? ''"></app-billing-status-badge>
                          </td>
                          <td>{{ cn.reference ?? '—' }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                }
              </div>
            </div>
          </div>
        </div>
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
    .bills-table { width: 100%; margin-bottom: 0; }
    .sticky-header th { position: sticky; top: 0; z-index: 2; background: #ffffff; }
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
    .empty-state .empty-icon { font-size: 3rem; line-height: 1; }
    .credit-notes-panel { margin-top: 0; }
    .credit-notes-table { width: 100%; margin-bottom: 0; }
    .muted-text { color: #757575; font-style: italic; padding: 0.5rem 0; }
    @media (max-width: 767px) {
      .billing-cashier-wrapper { padding: 0.75rem; }
    }
  `],
})
export class CashierWorkspaceComponent {
  private readonly billService       = inject(PatientBillControllerService);
  private readonly creditNoteService = inject(CreditNoteControllerService);
  private readonly modal          = inject(NgbModal);
  private readonly router         = inject(Router);

  readonly formatMoney    = formatMoney;
  readonly displayedColumns = BILL_COLUMNS;
  readonly creditNoteColumns = ['no', 'amount', 'status', 'reference'];

  // Transient inline flash message (replaces MatSnackBar)
  readonly flash = signal<FlashMessage | null>(null);

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
  readonly creditNotesExpanded = signal(false);

  loadBills(uid: string): void {
    this.patientUid.set(uid);
    this.loading.set(true);
    this.loadError.set(null);
    this.bills.set([]);
    this.selectedUids.set(new Set());
    this.hasLoaded.set(false);

    this.billService.listBills({ patientUid: uid }).subscribe({
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

    this.creditNoteService.listCreditNotes({ patientUid: uid }).subscribe({
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

    const ref = this.modal.open(RecordPaymentDialogComponent, { size: 'lg' });
    ref.componentInstance.data = data;

    ref.result
      .then((result: PatientPaymentDto) => {
        this.paymentPending.set(false);
        if (result?.uid) {
          this.flash.set({ kind: 'success', text: 'Payment recorded successfully.' });
          void this.router.navigate(['/billing/receipt', result.uid]);
        }
      })
      .catch(() => {
        // dismissed: do nothing
        this.paymentPending.set(false);
      });
  }

  openCancelDialog(row: BillRow): void {
    const data: CancelChargeDialogData = {
      billUid: row.uid,
      description: row.description,
      amount: row.amount,
      currency: row.currency,
    };

    const ref = this.modal.open(CancelChargeDialogComponent, { size: 'lg' });
    ref.componentInstance.data = data;

    ref.result
      .then((result: CancellationResultDto) => {
        if (result) {
          const uid = this.patientUid();
          if (uid) {
            this.loadBills(uid);
          }
        }
      })
      .catch(() => {
        // dismissed: do nothing
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
