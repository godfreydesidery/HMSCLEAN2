import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { PatientControllerService, PatientDto } from '../../api/generated';
import { extractProblem } from '../../core/error/problem-detail';

@Component({
  selector: 'app-patient-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, DatePipe],
  template: `
    <div class="container-fluid py-2">
      <div class="d-flex justify-content-between align-items-center mb-3">
        <h1 class="h4 mb-0">Patients</h1>
        <a class="btn btn-primary" routerLink="/registration/patients/new">
          <i class="bi bi-person-plus me-1"></i> Register Patient
        </a>
      </div>

      <div class="input-group mb-3" style="max-width: 520px;">
        <span class="input-group-text"><i class="bi bi-search"></i></span>
        <input
          type="text"
          class="form-control"
          [formControl]="query"
          placeholder="Search by name, registration no, or phone…"
          aria-label="Search patients"
        />
      </div>

      @if (loading()) {
        <div class="d-flex justify-content-center py-5">
          <div class="spinner-border text-primary" role="status">
            <span class="visually-hidden">Loading…</span>
          </div>
        </div>
      } @else if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      } @else if (patients().length === 0) {
        <div class="text-center text-muted py-5">
          <i class="bi bi-people fs-1 d-block mb-2"></i>
          {{ query.value ? 'No patients match your search.' : 'No patients yet — register one to begin.' }}
        </div>
      } @else {
        <div class="card">
          <div class="table-responsive">
            <table class="table table-hover align-middle mb-0">
              <thead class="table-light">
                <tr>
                  <th>Reg. No</th><th>Name</th><th>Gender</th><th>Payment</th>
                  <th>Phone</th><th>Last Visit</th><th class="text-end">Action</th>
                </tr>
              </thead>
              <tbody>
                @for (p of patients(); track p.uid) {
                  <tr>
                    <td>{{ p.no || '—' }}</td>
                    <td>{{ fullName(p) }}</td>
                    <td>{{ p.gender || '—' }}</td>
                    <td>
                      {{ p.paymentType }}
                      @if (p.paymentType === 'INSURANCE' && p.membershipNo) {
                        <span class="text-muted small">({{ p.membershipNo }})</span>
                      }
                    </td>
                    <td>{{ p.phoneNo || '—' }}</td>
                    <td>{{ p.lastVisitAt ? (p.lastVisitAt | date: 'shortDate') : '—' }}</td>
                    <td class="text-end">
                      <a class="btn btn-sm btn-outline-secondary"
                         [routerLink]="['/registration/patients', p.uid]">Open</a>
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
export class PatientListComponent implements OnInit {
  private readonly api = inject(PatientControllerService);

  protected readonly query    = new FormControl('', { nonNullable: true });
  protected readonly loading  = signal(true);
  protected readonly error    = signal<string | null>(null);
  protected readonly patients = signal<PatientDto[]>([]);

  ngOnInit(): void {
    this.query.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged())
      .subscribe(() => this.load());
    this.load();
  }

  protected fullName(p: PatientDto): string {
    return [p.firstName, p.middleName, p.lastName].filter((s) => !!s).join(' ').trim() || '—';
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    const q = this.query.value.trim();
    this.api.search({ query: q || undefined, page: 0, size: 25 }).subscribe({
      next: (res) => {
        this.patients.set(res.content ?? []);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        const p = extractProblem(err);
        this.error.set(p.status === 403
          ? 'You do not have permission to view patients.'
          : 'Failed to load patients. Please try again.');
      },
    });
  }
}
