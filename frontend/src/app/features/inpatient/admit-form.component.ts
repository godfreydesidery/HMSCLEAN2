import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import {
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  InpatientService,
  InsurancePlanControllerService,
  InsurancePlanDto,
} from '../../api/generated';
import { extractProblem } from '../../core/error/problem-detail';
import { EntityPickerComponent } from '../../shared/entity-picker/entity-picker.component';
import { PatientSearch } from '../../shared/entity-picker/patient-search';
import { WardBedSearch } from '../../shared/entity-picker/ward-bed-search';

@Component({
  selector: 'app-admit-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, EntityPickerComponent],
  template: `
    <div class="container py-2" style="max-width: 720px;">
      <nav class="mb-3" aria-label="breadcrumb">
        <a routerLink="/inpatient/admissions" class="text-decoration-none">
          <i class="bi bi-arrow-left"></i> Admissions
        </a>
      </nav>

      <div class="card shadow-sm">
        <div class="card-header bg-white">
          <h1 class="h5 mb-0">Admit Patient</h1>
        </div>
        <div class="card-body">
          <form [formGroup]="form" (ngSubmit)="submit()" novalidate>

            <!-- Patient -->
            <div class="mb-3">
              <label class="form-label" for="patient">Patient</label>
              <app-entity-picker
                id="patient"
                formControlName="patientUid"
                placeholder="Search patient by name, reg-no, or phone…"
                ariaLabel="Search for a patient to admit"
                [searchFn]="patientSearch.searchFn"
                [invalid]="isInvalid('patientUid')"
              />
              @if (isInvalid('patientUid')) {
                <div class="invalid-feedback d-block">Select a patient.</div>
              }
            </div>

            <!-- Ward bed -->
            <div class="mb-3">
              <label class="form-label" for="bed">Ward Bed</label>
              <app-entity-picker
                id="bed"
                formControlName="wardBedUid"
                placeholder="Search an available bed by number or ward…"
                ariaLabel="Search for an available ward bed"
                [searchFn]="wardBedSearch.searchFn"
                [invalid]="isInvalid('wardBedUid')"
              />
              @if (isInvalid('wardBedUid')) {
                <div class="invalid-feedback d-block">Select an available bed.</div>
              } @else {
                <div class="form-text">Only EMPTY beds are listed.</div>
              }
            </div>

            <!-- Payment type -->
            <div class="mb-3">
              <label class="form-label" for="paymentType">Payment Type</label>
              <select id="paymentType" class="form-select" formControlName="paymentType">
                <option value="CASH">Cash</option>
                <option value="INSURANCE">Insurance</option>
              </select>
            </div>

            <!-- Insurance details (only when INSURANCE) -->
            @if (form.controls.paymentType.value === 'INSURANCE') {
              <div class="mb-3">
                <label class="form-label" for="plan">Insurance Plan</label>
                <select id="plan" class="form-select" formControlName="insurancePlanUid"
                        [class.is-invalid]="isInvalid('insurancePlanUid')">
                  <option value="">— select plan —</option>
                  @for (p of plans(); track p.uid) {
                    <option [value]="p.uid">{{ p.name }}</option>
                  }
                </select>
                @if (isInvalid('insurancePlanUid')) {
                  <div class="invalid-feedback">Select an insurance plan.</div>
                }
              </div>
              <div class="mb-3">
                <label class="form-label" for="membershipNo">Membership No.</label>
                <input id="membershipNo" type="text" class="form-control"
                       formControlName="membershipNo"
                       [class.is-invalid]="isInvalid('membershipNo')" />
                @if (isInvalid('membershipNo')) {
                  <div class="invalid-feedback">Membership number is required for insurance.</div>
                }
              </div>
            }

            @if (apiError()) {
              <div class="alert alert-danger py-2" role="alert">{{ apiError() }}</div>
            }

            <div class="d-flex gap-2 justify-content-end">
              <a class="btn btn-link" routerLink="/inpatient/admissions">Cancel</a>
              <button type="submit" class="btn btn-primary"
                      [disabled]="submitting() || form.invalid">
                @if (submitting()) {
                  <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
                  Admitting…
                } @else {
                  Admit
                }
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  `,
})
export class AdmitFormComponent implements OnInit {
  private readonly fb        = inject(NonNullableFormBuilder);
  private readonly inpatient = inject(InpatientService);
  private readonly insurance = inject(InsurancePlanControllerService);
  private readonly router    = inject(Router);
  protected readonly patientSearch = inject(PatientSearch);
  protected readonly wardBedSearch = inject(WardBedSearch);

  protected readonly plans      = signal<InsurancePlanDto[]>([]);
  protected readonly submitting = signal(false);
  protected readonly apiError   = signal<string | null>(null);

  readonly form = this.fb.group({
    patientUid: ['', [Validators.required]],
    wardBedUid: ['', [Validators.required]],
    paymentType: ['CASH', [Validators.required]],
    insurancePlanUid: [''],
    membershipNo: [''],
  });

  ngOnInit(): void {
    // Toggle insurance-field validators with the payment type.
    this.form.controls.paymentType.valueChanges.subscribe((pt) => {
      const planCtrl = this.form.controls.insurancePlanUid;
      const memberCtrl = this.form.controls.membershipNo;
      if (pt === 'INSURANCE') {
        planCtrl.setValidators([Validators.required]);
        memberCtrl.setValidators([Validators.required]);
        if (this.plans().length === 0) this.loadPlans();
      } else {
        planCtrl.clearValidators();
        memberCtrl.clearValidators();
        planCtrl.setValue('');
        memberCtrl.setValue('');
      }
      planCtrl.updateValueAndValidity();
      memberCtrl.updateValueAndValidity();
    });
  }

  protected isInvalid(name: keyof typeof this.form.controls): boolean {
    const c = this.form.controls[name];
    return c.invalid && c.touched;
  }

  private loadPlans(): void {
    this.insurance.list17().subscribe({
      next: (list) => this.plans.set((list ?? []).filter((p) => p.active !== false)),
      error: () => this.plans.set([]),
    });
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.apiError.set(null);

    const v = this.form.getRawValue();
    this.inpatient
      .doAdmission({
        admissionRequest: {
          patientUid: v.patientUid,
          wardBedUid: v.wardBedUid,
          paymentType: v.paymentType,
          insurancePlanUid: v.paymentType === 'INSURANCE' ? v.insurancePlanUid : undefined,
          membershipNo: v.paymentType === 'INSURANCE' ? v.membershipNo : undefined,
        },
      })
      .subscribe({
        next: (adm) => {
          this.submitting.set(false);
          void this.router.navigate(['/inpatient/admissions', adm.uid]);
        },
        error: (err: unknown) => {
          this.submitting.set(false);
          this.apiError.set(this.mapError(err));
        },
      });
  }

  private mapError(err: unknown): string {
    const p = extractProblem(err);
    if (p.type === 'urn:hmis:error:patient-deceased') return 'This patient is deceased and cannot be admitted.';
    if (p.status === 409) return 'That bed is no longer available. Pick another bed.';
    if (p.status === 422) return 'Could not admit: ' + (p.title ?? 'business rule violation') + '.';
    if (p.title) return p.title;
    return 'Failed to admit the patient. Please try again.';
  }
}
