import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  AdmissionDto,
  InpatientService,
  PatientControllerService,
} from '../../api/generated';
import { extractProblem } from '../../core/error/problem-detail';
import { AdmissionStatusBadgeComponent } from './admission-status-badge.component';
import { MarPanelComponent } from './mar-panel.component';
import { NursingChartsPanelComponent } from './nursing-charts-panel.component';

type TabId = 'overview' | 'mar' | 'nursing';

@Component({
  selector: 'app-admission-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    DatePipe,
    AdmissionStatusBadgeComponent,
    MarPanelComponent,
    NursingChartsPanelComponent,
  ],
  template: `
    <div class="container-fluid py-2">
      <nav class="mb-3">
        <a routerLink="/inpatient/admissions" class="text-decoration-none">
          <i class="bi bi-arrow-left"></i> Admissions
        </a>
      </nav>

      @if (loading()) {
        <div class="d-flex justify-content-center py-5">
          <div class="spinner-border text-primary" role="status">
            <span class="visually-hidden">Loading…</span>
          </div>
        </div>
      } @else if (error()) {
        <div class="alert alert-danger" role="alert">{{ error() }}</div>
      }

      @if (!loading() && !error() && admission(); as adm) {
        <!-- Header -->
        <div class="d-flex justify-content-between align-items-center mb-3">
          <div>
            <h1 class="h4 mb-1">{{ patientName() }}</h1>
            <div class="text-muted small">
              <app-admission-status-badge [status]="adm.status ?? ''" />
              <span class="ms-2">{{ adm.paymentType }}</span>
              @if (adm.admittedAt) {
                <span class="ms-2">· Admitted {{ adm.admittedAt | date: 'medium' }}</span>
              }
            </div>
          </div>
        </div>

        <!-- Tabs -->
        <ul class="nav nav-tabs mb-3">
          <li class="nav-item">
            <button class="nav-link" [class.active]="tab() === 'overview'"
                    (click)="tab.set('overview')" type="button">Overview</button>
          </li>
          <li class="nav-item">
            <button class="nav-link" [class.active]="tab() === 'mar'"
                    (click)="tab.set('mar')" type="button">
              <i class="bi bi-capsule me-1"></i>Medication (MAR)
            </button>
          </li>
          <li class="nav-item">
            <button class="nav-link" [class.active]="tab() === 'nursing'"
                    (click)="tab.set('nursing')" type="button">
              <i class="bi bi-clipboard-pulse me-1"></i>Nursing Charts
            </button>
          </li>
        </ul>

        <!-- Tab content -->
        @switch (tab()) {
          @case ('overview') {
            <div class="card">
              <div class="card-body">
                <dl class="row mb-0">
                  <dt class="col-sm-3">Status</dt>
                  <dd class="col-sm-9"><app-admission-status-badge [status]="adm.status ?? ''" /></dd>
                  <dt class="col-sm-3">Payment Type</dt>
                  <dd class="col-sm-9">{{ adm.paymentType }}</dd>
                  @if (adm.membershipNo) {
                    <dt class="col-sm-3">Membership No.</dt>
                    <dd class="col-sm-9">{{ adm.membershipNo }}</dd>
                  }
                  <dt class="col-sm-3">Admitted At</dt>
                  <dd class="col-sm-9">{{ adm.admittedAt ? (adm.admittedAt | date: 'medium') : '—' }}</dd>
                  @if (adm.dischargedAt) {
                    <dt class="col-sm-3">Discharged At</dt>
                    <dd class="col-sm-9">{{ adm.dischargedAt | date: 'medium' }}</dd>
                  }
                </dl>
                @if (adm.status !== 'IN-PROCESS') {
                  <div class="alert alert-info mt-3 mb-0 py-2 small">
                    <i class="bi bi-info-circle me-1"></i>
                    Charting (nursing, MAR, consumables) is only available while the admission is
                    <strong>In Process</strong> (activated after payment). This admission is
                    <strong>{{ adm.status }}</strong>.
                  </div>
                }
              </div>
            </div>
          }
          @case ('mar') {
            <app-mar-panel [admissionUid]="adm.uid ?? ''" [active]="adm.status === 'IN-PROCESS'" />
          }
          @case ('nursing') {
            <app-nursing-charts-panel [admissionUid]="adm.uid ?? ''" [active]="adm.status === 'IN-PROCESS'" />
          }
        }
      }
    </div>
  `,
})
export class AdmissionDetailComponent implements OnInit {
  private readonly route     = inject(ActivatedRoute);
  private readonly inpatient = inject(InpatientService);
  private readonly patients  = inject(PatientControllerService);

  protected readonly loading     = signal(true);
  protected readonly error       = signal<string | null>(null);
  protected readonly admission   = signal<AdmissionDto | null>(null);
  protected readonly patientName = signal<string>('');
  protected readonly tab         = signal<TabId>('overview');

  ngOnInit(): void {
    const uid = this.route.snapshot.paramMap.get('uid') ?? '';
    this.inpatient.getAdmission({ uid }).subscribe({
      next: (adm) => {
        this.admission.set(adm);
        this.resolvePatient(adm.patientUid ?? '');
      },
      error: (err: unknown) => {
        this.loading.set(false);
        const p = extractProblem(err);
        this.error.set(p.status === 404 ? 'Admission not found.' : 'Failed to load the admission.');
      },
    });
  }

  private resolvePatient(uid: string): void {
    if (!uid) {
      this.loading.set(false);
      return;
    }
    this.patients.getByUid1({ uid }).pipe(catchError(() => of(null))).subscribe((p) => {
      if (p) {
        const name = [p.firstName, p.middleName, p.lastName]
          .filter((s) => !!s).join(' ').trim();
        this.patientName.set(name || (p.no ?? uid));
      } else {
        this.patientName.set(uid);
      }
      this.loading.set(false);
    });
  }
}
