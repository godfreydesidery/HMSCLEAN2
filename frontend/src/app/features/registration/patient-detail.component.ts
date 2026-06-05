import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PatientControllerService, PatientDto } from '../../api/generated';

@Component({
  selector: 'app-patient-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, DatePipe],
  template: `
    <div class="container py-2" style="max-width: 820px;">
      <nav class="mb-3">
        <a routerLink="/registration/patients" class="text-decoration-none">
          <i class="bi bi-arrow-left"></i> Patients
        </a>
      </nav>

      @if (loading()) {
        <div class="text-center py-5"><div class="spinner-border text-primary" role="status"></div></div>
      } @else if (error()) {
        <div class="alert alert-danger">{{ error() }}</div>
      }

      @if (!loading() && !error() && patient(); as p) {
        <div class="card shadow-sm">
          <div class="card-header bg-white d-flex justify-content-between align-items-center">
            <div>
              <h1 class="h5 mb-0">{{ fullName(p) }}</h1>
              <span class="text-muted small">Reg. No {{ p.no || '—' }}</span>
            </div>
            <a class="btn btn-outline-primary btn-sm" [routerLink]="['/registration/patients', p.uid, 'edit']">
              <i class="bi bi-pencil me-1"></i> Edit
            </a>
          </div>
          <div class="card-body">
            <dl class="row mb-0">
              <dt class="col-sm-3">Date of Birth</dt><dd class="col-sm-9">{{ p.dateOfBirth ? (p.dateOfBirth | date: 'longDate') : '—' }}</dd>
              <dt class="col-sm-3">Gender</dt><dd class="col-sm-9">{{ p.gender || '—' }}</dd>
              <dt class="col-sm-3">Type</dt><dd class="col-sm-9">{{ p.type || '—' }}</dd>
              <dt class="col-sm-3">Payment</dt>
              <dd class="col-sm-9">
                {{ p.paymentType }}
                @if (p.paymentType === 'INSURANCE' && p.membershipNo) { <span class="text-muted">· {{ p.membershipNo }}</span> }
              </dd>
              <dt class="col-sm-3">Phone</dt><dd class="col-sm-9">{{ p.phoneNo || '—' }}</dd>
              @if (p.email) { <dt class="col-sm-3">Email</dt><dd class="col-sm-9">{{ p.email }}</dd> }
              @if (p.address) { <dt class="col-sm-3">Address</dt><dd class="col-sm-9">{{ p.address }}</dd> }
              @if (p.kinFullName) {
                <dt class="col-sm-3">Next of Kin</dt>
                <dd class="col-sm-9">{{ p.kinFullName }}<span class="text-muted"> · {{ p.kinRelationship }} · {{ p.kinPhoneNo }}</span></dd>
              }
              <dt class="col-sm-3">Last Visit</dt><dd class="col-sm-9">{{ p.lastVisitAt ? (p.lastVisitAt | date: 'medium') : 'Never' }}</dd>
            </dl>
          </div>
        </div>
      }
    </div>
  `,
})
export class PatientDetailComponent implements OnInit {
  private readonly api   = inject(PatientControllerService);
  private readonly route = inject(ActivatedRoute);

  protected readonly loading = signal(true);
  protected readonly error   = signal<string | null>(null);
  protected readonly patient = signal<PatientDto | null>(null);

  ngOnInit(): void {
    const uid = this.route.snapshot.paramMap.get('uid') ?? '';
    this.api.getByUid1({ uid }).subscribe({
      next: (p) => { this.patient.set(p); this.loading.set(false); },
      error: () => { this.loading.set(false); this.error.set('Patient not found.'); },
    });
  }

  protected fullName(p: PatientDto): string {
    return [p.firstName, p.middleName, p.lastName].filter((s) => !!s).join(' ').trim() || '—';
  }
}
