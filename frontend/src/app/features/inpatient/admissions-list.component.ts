import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import {
  AdmissionDto,
  InpatientService,
  PatientControllerService,
} from '../../api/generated';
import { extractProblem } from '../../core/error/problem-detail';
import { AdmissionStatusBadgeComponent } from './admission-status-badge.component';

/** A status filter option (db-value sent to the API; '' = all). */
interface StatusFilter {
  readonly label: string;
  readonly value: string;
}

const STATUS_FILTERS: readonly StatusFilter[] = [
  { label: 'All',        value: '' },
  { label: 'Pending',    value: 'PENDING' },
  { label: 'In Process', value: 'IN-PROCESS' },
  { label: 'Stopped',    value: 'STOPPED' },
  { label: 'Held',       value: 'HELD' },
  { label: 'Signed Out', value: 'SIGNED-OUT' },
];

/** An admission row enriched with the resolved patient name for display. */
interface AdmissionRow extends AdmissionDto {
  patientName: string;
}

@Component({
  selector: 'app-admissions-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, DatePipe, AdmissionStatusBadgeComponent],
  template: `
    <div class="container-fluid py-2">
      <div class="d-flex justify-content-between align-items-center mb-3">
        <h1 class="h4 mb-0">Admissions</h1>
        <a class="btn btn-primary" routerLink="/inpatient/admissions/new">
          <i class="bi bi-plus-lg me-1"></i> Admit Patient
        </a>
      </div>

      <!-- Status filter pills -->
      <ul class="nav nav-pills mb-3 gap-1">
        @for (f of filters; track f.value) {
          <li class="nav-item">
            <button
              type="button"
              class="nav-link"
              [class.active]="activeFilter() === f.value"
              (click)="setFilter(f.value)"
            >{{ f.label }}</button>
          </li>
        }
      </ul>

      @if (loading()) {
        <div class="d-flex justify-content-center py-5">
          <div class="spinner-border text-primary" role="status">
            <span class="visually-hidden">Loading…</span>
          </div>
        </div>
      } @else if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      } @else if (rows().length === 0) {
        <div class="text-center text-muted py-5">
          <i class="bi bi-hospital fs-1 d-block mb-2"></i>
          No admissions{{ activeFilter() ? ' with this status' : '' }}.
        </div>
      } @else {
        <div class="card">
          <div class="table-responsive">
            <table class="table table-hover align-middle mb-0">
              <thead class="table-light">
                <tr>
                  <th scope="col">Patient</th>
                  <th scope="col">Payment</th>
                  <th scope="col">Status</th>
                  <th scope="col">Admitted</th>
                  <th scope="col" class="text-end">Action</th>
                </tr>
              </thead>
              <tbody>
                @for (a of rows(); track a.uid) {
                  <tr>
                    <td>{{ a.patientName }}</td>
                    <td>{{ a.paymentType }}</td>
                    <td><app-admission-status-badge [status]="a.status ?? ''" /></td>
                    <td>{{ a.admittedAt ? (a.admittedAt | date: 'medium') : '—' }}</td>
                    <td class="text-end">
                      <a class="btn btn-sm btn-outline-secondary"
                         [routerLink]="['/inpatient/admissions', a.uid]">
                        Open
                      </a>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
    </div>
  `,
})
export class AdmissionsListComponent implements OnInit {
  private readonly inpatient = inject(InpatientService);
  private readonly patients  = inject(PatientControllerService);

  protected readonly filters = STATUS_FILTERS;
  protected readonly activeFilter = signal<string>('');
  protected readonly loading = signal(true);
  protected readonly error   = signal<string | null>(null);
  protected readonly rows    = signal<AdmissionRow[]>([]);

  /** patientUid -> display name cache (avoids re-fetching the same patient across rows). */
  private readonly nameCache = new Map<string, string>();

  ngOnInit(): void {
    this.load();
  }

  protected setFilter(value: string): void {
    if (value === this.activeFilter()) return;
    this.activeFilter.set(value);
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    const status = this.activeFilter();
    this.inpatient
      .listAdmissions(status ? { status } : {})
      .subscribe({
        next: (admissions) => this.resolveNames(admissions ?? []),
        error: (err: unknown) => {
          this.loading.set(false);
          this.error.set(this.mapError(err));
        },
      });
  }

  /** Resolve each admission's patient name (cached), then publish the rows. */
  private resolveNames(admissions: AdmissionDto[]): void {
    if (admissions.length === 0) {
      this.rows.set([]);
      this.loading.set(false);
      return;
    }
    const lookups = admissions.map((a) => {
      const uid = a.patientUid ?? '';
      const cached = this.nameCache.get(uid);
      if (cached !== undefined) {
        return of({ ...a, patientName: cached } as AdmissionRow);
      }
      return this.patients.getByUid1({ uid }).pipe(
        map((p) => {
          const name = [p.firstName, p.middleName, p.lastName]
            .filter((s) => !!s).join(' ').trim() || (p.no ?? uid);
          this.nameCache.set(uid, name);
          return { ...a, patientName: name } as AdmissionRow;
        }),
        catchError(() => of({ ...a, patientName: uid } as AdmissionRow)),
      );
    });
    forkJoin(lookups).subscribe((rows) => {
      this.rows.set(rows);
      this.loading.set(false);
    });
  }

  private mapError(err: unknown): string {
    const p = extractProblem(err);
    if (p.status === 403) return 'You do not have permission to view admissions.';
    return 'Failed to load admissions. Please try again.';
  }
}
