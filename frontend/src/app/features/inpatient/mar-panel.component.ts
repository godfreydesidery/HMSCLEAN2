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
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import {
  AdministrationRouteControllerService,
  AdministrationRouteDto,
  InpatientNursingChartsService,
  MedicationAdministrationView,
} from '../../api/generated';
import { extractProblem } from '../../core/error/problem-detail';

/**
 * Medication Administration Record (MAR) panel — inc-07 07d / CR-07-MAR.
 *
 * Lists the closed-loop administrations recorded for an admission and lets a nurse record a new
 * one (route from the administration-routes masterdata, prescription, dose, response). The record
 * action is gated server-side by the MEDICATION-ADMINISTER privilege; a 403 is surfaced inline.
 */
@Component({
  selector: 'app-mar-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, DatePipe],
  template: `
    <div class="row g-3">
      <!-- Existing MAR entries -->
      <div class="col-12 col-lg-7">
        <div class="card h-100">
          <div class="card-header bg-white d-flex justify-content-between align-items-center">
            <span class="fw-semibold">Administrations</span>
            <button class="btn btn-sm btn-outline-secondary" (click)="reload()" [disabled]="loading()">
              <i class="bi bi-arrow-clockwise"></i>
            </button>
          </div>
          @if (loading()) {
            <div class="card-body text-center py-4">
              <div class="spinner-border spinner-border-sm text-primary" role="status"></div>
            </div>
          } @else if (entries().length === 0) {
            <div class="card-body text-muted text-center py-4">
              No medication administrations recorded yet.
            </div>
          } @else {
            <div class="table-responsive">
              <table class="table table-sm mb-0 align-middle">
                <thead class="table-light">
                  <tr><th>Administered</th><th>Route</th><th>Dose</th><th>Response</th></tr>
                </thead>
                <tbody>
                  @for (m of entries(); track m.uid) {
                    <tr>
                      <td>{{ m.administeredAt ? (m.administeredAt | date: 'short') : '—' }}</td>
                      <td>{{ routeName(m.routeUid) }}</td>
                      <td>{{ m.doseGiven || '—' }}</td>
                      <td>{{ m.patientResponse || '—' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
      </div>

      <!-- Record form -->
      <div class="col-12 col-lg-5">
        <div class="card h-100">
          <div class="card-header bg-white fw-semibold">Record Administration</div>
          <div class="card-body">
            @if (!active()) {
              <div class="alert alert-info py-2 small mb-0">
                Recording is only allowed while the admission is <strong>In Process</strong>.
              </div>
            } @else {
              <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
                <div class="mb-2">
                  <label class="form-label" for="route">Route</label>
                  <select id="route" class="form-select form-select-sm" formControlName="routeUid"
                          [class.is-invalid]="invalid('routeUid')">
                    <option value="">— select route —</option>
                    @for (r of routes(); track r.uid) {
                      <option [value]="r.uid">{{ r.name }}</option>
                    }
                  </select>
                </div>
                <div class="mb-2">
                  <label class="form-label" for="rx">Prescription UID</label>
                  <input id="rx" class="form-control form-control-sm" formControlName="prescriptionUid"
                         placeholder="GIVEN prescription uid"
                         [class.is-invalid]="invalid('prescriptionUid')" />
                </div>
                <div class="mb-2">
                  <label class="form-label" for="nurse">Nurse UID</label>
                  <input id="nurse" class="form-control form-control-sm" formControlName="nurseUid"
                         [class.is-invalid]="invalid('nurseUid')" />
                </div>
                <div class="mb-2">
                  <label class="form-label" for="dose">Dose Given</label>
                  <input id="dose" class="form-control form-control-sm" formControlName="doseGiven" />
                </div>
                <div class="mb-2">
                  <label class="form-label" for="resp">Patient Response</label>
                  <input id="resp" class="form-control form-control-sm" formControlName="patientResponse" />
                </div>

                @if (apiError()) {
                  <div class="alert alert-danger py-2 small" role="alert">{{ apiError() }}</div>
                }
                @if (savedOk()) {
                  <div class="alert alert-success py-2 small" role="alert">Administration recorded.</div>
                }

                <button type="submit" class="btn btn-primary btn-sm w-100"
                        [disabled]="saving() || form.invalid">
                  @if (saving()) {
                    <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                    Recording…
                  } @else { Record }
                </button>
              </form>
            }
          </div>
        </div>
      </div>
    </div>
  `,
})
export class MarPanelComponent implements OnInit {
  readonly admissionUid = input.required<string>();
  readonly active = input<boolean>(false);

  private readonly fb     = inject(NonNullableFormBuilder);
  private readonly mar    = inject(InpatientNursingChartsService);
  private readonly routesApi = inject(AdministrationRouteControllerService);

  protected readonly entries = signal<MedicationAdministrationView[]>([]);
  protected readonly routes  = signal<AdministrationRouteDto[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving  = signal(false);
  protected readonly apiError = signal<string | null>(null);
  protected readonly savedOk  = signal(false);

  private readonly routeNames = new Map<string, string>();

  readonly form = this.fb.group({
    routeUid: ['', [Validators.required]],
    prescriptionUid: ['', [Validators.required]],
    nurseUid: ['', [Validators.required]],
    administeredAt: [''],
    doseGiven: [''],
    patientResponse: [''],
  });

  ngOnInit(): void {
    this.routesApi.list23().subscribe({
      next: (rs) => {
        const active = (rs ?? []).filter((r) => r.active !== false);
        this.routes.set(active);
        active.forEach((r) => this.routeNames.set(r.uid ?? '', r.name ?? r.code ?? ''));
      },
      error: () => this.routes.set([]),
    });
    this.reload();
  }

  protected reload(): void {
    this.loading.set(true);
    this.mar.listMedicationAdministrations({ admissionUid: this.admissionUid() }).subscribe({
      next: (list) => { this.entries.set(list ?? []); this.loading.set(false); },
      error: () => { this.entries.set([]); this.loading.set(false); },
    });
  }

  protected routeName(uid: string | undefined): string {
    return (uid && this.routeNames.get(uid)) || uid || '—';
  }

  protected invalid(name: keyof typeof this.form.controls): boolean {
    const c = this.form.controls[name];
    return c.invalid && c.touched;
  }

  submit(): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    this.saving.set(true);
    this.apiError.set(null);
    this.savedOk.set(false);
    const v = this.form.getRawValue();
    this.mar
      .saveMedicationAdministration({
        admissionUid: this.admissionUid(),
        medicationAdministrationRequest: {
          routeUid: v.routeUid,
          prescriptionUid: v.prescriptionUid,
          nurseUid: v.nurseUid,
          administeredAt: new Date().toISOString(),
          doseGiven: v.doseGiven || undefined,
          patientResponse: v.patientResponse || undefined,
        },
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.savedOk.set(true);
          this.form.reset({ routeUid: '', prescriptionUid: '', nurseUid: '', administeredAt: '', doseGiven: '', patientResponse: '' });
          this.reload();
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.apiError.set(this.mapError(err));
        },
      });
  }

  private mapError(err: unknown): string {
    const p = extractProblem(err);
    if (p.status === 403) return 'You lack the MEDICATION-ADMINISTER privilege.';
    if (p.status === 422) return p.title ?? 'Business rule violation.';
    if (p.title) return p.title;
    return 'Failed to record the administration.';
  }
}
