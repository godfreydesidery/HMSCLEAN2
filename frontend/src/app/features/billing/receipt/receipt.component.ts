import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { BillingReportControllerService, ReceiptDto } from '../../../api/generated';
import { extractProblem } from '../../../core/error/problem-detail';
import { formatMoney } from '../../../core/billing/format-money';
import { BillingStatusBadgeComponent } from '../shared/billing-status-badge.component';

@Component({
  selector: 'app-receipt',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    BillingStatusBadgeComponent,
  ],
  template: `
    <div class="receipt-wrapper">

      <!-- Screen-only nav bar -->
      <div class="receipt-nav no-print">
        <button
          mat-icon-button
          routerLink="/billing/cashier"
          aria-label="Return to cashier workspace"
        >
          <mat-icon>arrow_back</mat-icon>
        </button>
        <span class="nav-title">Receipt</span>
        <button
          mat-raised-button
          color="primary"
          class="no-print"
          (click)="print()"
          aria-label="Print this receipt"
        >
          <mat-icon>print</mat-icon>
          Print Receipt
        </button>
      </div>

      <!-- Loading state -->
      @if (loading()) {
        <div class="state-container" role="status" aria-label="Loading receipt">
          <mat-spinner diameter="48"></mat-spinner>
          <p>Loading receipt…</p>
        </div>
      }

      <!-- Error state -->
      @if (error()) {
        <div class="error-container">
          <p class="error-msg" role="alert">{{ error() }}</p>
          <a mat-stroked-button routerLink="/billing/cashier" aria-label="Return to cashier workspace">
            Back to Cashier
          </a>
        </div>
      }

      <!-- Receipt card -->
      @if (!loading() && !error() && receipt()) {
        <mat-card
          class="receipt-card"
          id="receipt-card"
          role="region"
          [attr.aria-label]="'Payment receipt ' + (receipt()?.receiptNo ?? '')"
        >
          <!-- Print-only header -->
          <div class="receipt-header receipt-print-header">
            <h1 class="hospital-name">Zana HMIS Hospital</h1>
            <p class="hospital-subtitle">Official Payment Receipt</p>
          </div>

          <mat-card-header>
            <mat-card-title>Official Receipt</mat-card-title>
            <mat-card-subtitle>No. {{ receipt()?.receiptNo ?? '—' }}</mat-card-subtitle>
          </mat-card-header>

          <mat-card-content>
            <mat-divider></mat-divider>

            <dl class="receipt-dl">
              <dt>Date</dt>
              <dd>{{ formatDate(receipt()?.businessDate) }}</dd>

              <dt>Cashier</dt>
              <dd>{{ receipt()?.cashier ?? '—' }}</dd>

              <dt>Patient UID</dt>
              <dd>{{ receipt()?.patientUid ?? '—' }}</dd>

              <dt>Payment Mode</dt>
              <dd>{{ receipt()?.paymentMode ?? '—' }}</dd>

              <dt>Status</dt>
              <dd>
                <app-billing-status-badge [status]="receipt()?.status ?? ''"></app-billing-status-badge>
              </dd>
            </dl>

            <mat-divider class="amount-divider"></mat-divider>

            <div class="amount-row">
              <span class="amount-label">Amount</span>
              <span class="amount-value">{{ formatMoney(receipt()?.amount?.amount, receipt()?.amount?.currency) }}</span>
            </div>

            <mat-divider></mat-divider>

            <dl class="receipt-dl receipt-dl--small">
              <dt>Business Day</dt>
              <dd>{{ receipt()?.businessDayUid ?? '—' }}</dd>
            </dl>
          </mat-card-content>

          <mat-card-actions class="no-print">
            <button
              mat-raised-button
              color="primary"
              (click)="print()"
              aria-label="Print this receipt"
            >
              <mat-icon>print</mat-icon>
              Print Receipt
            </button>
          </mat-card-actions>
        </mat-card>
      }

    </div>
  `,
  styles: [`
    .receipt-wrapper {
      padding: 1.5rem;
      max-width: 640px;
      margin: 0 auto;
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    .receipt-nav {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }
    .nav-title {
      font-size: 1.1rem;
      font-weight: 600;
      flex: 1;
    }
    .state-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.75rem;
      padding: 3rem;
    }
    .error-container {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 0.5rem;
      padding: 1rem;
    }
    .error-msg { color: #c62828; font-size: 0.875rem; margin: 0; }
    .receipt-card { max-width: 100%; }
    .receipt-header { display: none; }
    .receipt-dl {
      display: grid;
      grid-template-columns: 160px 1fr;
      gap: 0.5rem 1rem;
      padding: 1rem 0;
    }
    .receipt-dl--small { padding: 0.5rem 0; }
    .receipt-dl dt {
      font-weight: 500;
      color: #555;
    }
    .receipt-dl dd { margin: 0; }
    .amount-divider { margin: 0.5rem 0; }
    .amount-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 1rem 0;
    }
    .amount-label { font-size: 1rem; font-weight: 500; }
    .amount-value { font-size: 1.4rem; font-weight: 700; }

    @media print {
      .no-print { display: none !important; }
      .receipt-wrapper { padding: 0; max-width: 100%; }
      .receipt-card {
        box-shadow: none !important;
        border: 1px solid #ccc;
        max-width: 100%;
        margin: 0;
        padding: 1rem;
      }
      .receipt-header {
        display: block !important;
        text-align: center;
        margin-bottom: 1rem;
      }
      .hospital-name { font-size: 1.4rem; margin: 0; }
      .hospital-subtitle { font-size: 0.9rem; margin: 0.25rem 0 0; color: #444; }
      .receipt-print-header { display: block !important; }
      mat-card-actions { display: none !important; }
      body { background: white; }
    }
  `],
})
export class ReceiptComponent implements OnInit {
  private readonly route                = inject(ActivatedRoute);
  private readonly billingReportService = inject(BillingReportControllerService);

  readonly formatMoney = formatMoney;

  readonly loading = signal(true);
  readonly error   = signal<string | null>(null);
  readonly receipt = signal<ReceiptDto | null>(null);

  ngOnInit(): void {
    const uid = this.route.snapshot.paramMap.get('uid') ?? '';
    this.loading.set(true);

    this.billingReportService.receipt({ uid }).subscribe({
      next: (data) => {
        this.receipt.set(data);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.error.set(this.mapError(err));
      },
    });
  }

  print(): void {
    window.print();
  }

  formatDate(iso: string | null | undefined): string {
    if (!iso) return '—';
    try {
      return new Date(iso).toLocaleDateString('en-GB', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
      });
    } catch {
      return iso;
    }
  }

  private mapError(err: unknown): string {
    const problem = extractProblem(err);
    if (problem.status === 403) {
      return 'You do not have permission to view this receipt.';
    }
    if (problem.status === 404) {
      return 'Receipt not found.';
    }
    if (problem.status === 503 || problem.status === 0) {
      return 'Service unavailable. Please try again shortly.';
    }
    return 'Failed to load receipt. Please try again.';
  }
}
