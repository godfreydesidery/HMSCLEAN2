import {
  ChangeDetectionStrategy,
  Component,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import {
  InpatientNursingChartsService,
  NursingChartView,
} from '../../api/generated';

/**
 * Nursing observation charts panel (inc-07 07b) — lists the nursing charts recorded for an
 * admission. Read-only for now; the record path can be added later following the MAR panel
 * pattern.
 */
@Component({
  selector: 'app-nursing-charts-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  template: `
    <div class="card">
      <div class="card-header bg-white d-flex justify-content-between align-items-center">
        <span class="fw-semibold">Nursing Charts</span>
        <button class="btn btn-sm btn-outline-secondary" (click)="reload()" [disabled]="loading()">
          <i class="bi bi-arrow-clockwise"></i>
        </button>
      </div>
      @if (loading()) {
        <div class="card-body text-center py-4">
          <div class="spinner-border spinner-border-sm text-primary" role="status"></div>
        </div>
      } @else if (charts().length === 0) {
        <div class="card-body text-muted text-center py-4">
          No nursing charts recorded yet.
        </div>
      } @else {
        <div class="table-responsive">
          <table class="table table-sm mb-0 align-middle">
            <thead class="table-light">
              <tr>
                <th>Recorded</th><th>Feeding</th><th>Position</th><th>Bed Bath</th>
                <th>RBS</th><th>Fluid In</th><th>Urine Out</th>
              </tr>
            </thead>
            <tbody>
              @for (c of charts(); track c.uid) {
                <tr>
                  <td>{{ c.createdAt ? (c.createdAt | date: 'short') : '—' }}</td>
                  <td>{{ c.feeding || '—' }}</td>
                  <td>{{ c.changingPosition || '—' }}</td>
                  <td>{{ c.bedBathing || '—' }}</td>
                  <td>{{ c.randomBloodSugar || '—' }}</td>
                  <td>{{ c.fluidIntake || '—' }}</td>
                  <td>{{ c.urineOutput || '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  `,
})
export class NursingChartsPanelComponent implements OnInit {
  readonly admissionUid = input.required<string>();
  readonly active = input<boolean>(false);

  private readonly api = inject(InpatientNursingChartsService);

  protected readonly charts  = signal<NursingChartView[]>([]);
  protected readonly loading = signal(true);

  ngOnInit(): void {
    this.reload();
  }

  protected reload(): void {
    this.loading.set(true);
    this.api.listNursingCharts({ admissionUid: this.admissionUid() }).subscribe({
      next: (list) => { this.charts.set(list ?? []); this.loading.set(false); },
      error: () => { this.charts.set([]); this.loading.set(false); },
    });
  }
}
