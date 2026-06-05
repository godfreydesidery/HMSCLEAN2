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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  InsurancePlanControllerService,
  InsurancePlanDto,
  PatientControllerService,
} from '../../api/generated';
import { extractProblem } from '../../core/error/problem-detail';

/**
 * Patient register / edit form. /new registers (POST), /:uid/edit updates demographics (PUT).
 * Payment type + insurance are only set on registration (the update endpoint is demographics-only,
 * matching the backend UpdatePatientRequest which has no payment fields).
 */
@Component({
  selector: 'app-patient-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="container py-2" style="max-width: 860px;">
      <nav class="mb-3">
        <a routerLink="/registration/patients" class="text-decoration-none">
          <i class="bi bi-arrow-left"></i> Patients
        </a>
      </nav>

      <div class="card shadow-sm">
        <div class="card-header bg-white">
          <h1 class="h5 mb-0">{{ editUid() ? 'Edit Patient' : 'Register Patient' }}</h1>
        </div>
        <div class="card-body">
          @if (loadingPatient()) {
            <div class="text-center py-4"><div class="spinner-border text-primary" role="status"></div></div>
          } @else {
          <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
            <div class="row g-3">
              <!-- Names -->
              <div class="col-md-4">
                <label class="form-label" for="firstName">First Name *</label>
                <input id="firstName" class="form-control" formControlName="firstName"
                       [class.is-invalid]="invalid('firstName')" />
                @if (invalid('firstName')) { <div class="invalid-feedback">First name is required.</div> }
              </div>
              <div class="col-md-4">
                <label class="form-label" for="middleName">Middle Name</label>
                <input id="middleName" class="form-control" formControlName="middleName" />
              </div>
              <div class="col-md-4">
                <label class="form-label" for="lastName">Last Name *</label>
                <input id="lastName" class="form-control" formControlName="lastName"
                       [class.is-invalid]="invalid('lastName')" />
                @if (invalid('lastName')) { <div class="invalid-feedback">Last name is required.</div> }
              </div>

              <!-- DOB / gender -->
              <div class="col-md-4">
                <label class="form-label" for="dob">Date of Birth *</label>
                <input id="dob" type="date" class="form-control" formControlName="dateOfBirth"
                       [max]="today" [class.is-invalid]="invalid('dateOfBirth')" />
                @if (invalid('dateOfBirth')) { <div class="invalid-feedback">Date of birth is required.</div> }
              </div>
              <div class="col-md-4">
                <label class="form-label" for="gender">Gender *</label>
                <select id="gender" class="form-select" formControlName="gender"
                        [class.is-invalid]="invalid('gender')">
                  <option value="">—</option>
                  <option value="Male">Male</option>
                  <option value="Female">Female</option>
                </select>
                @if (invalid('gender')) { <div class="invalid-feedback">Gender is required.</div> }
              </div>
              <div class="col-md-4">
                <label class="form-label" for="phoneNo">Phone</label>
                <input id="phoneNo" class="form-control" formControlName="phoneNo" />
              </div>

              <!-- Payment (register only) -->
              @if (!editUid()) {
                <div class="col-md-4">
                  <label class="form-label" for="paymentType">Payment Type *</label>
                  <select id="paymentType" class="form-select" formControlName="paymentType">
                    <option value="CASH">Cash</option>
                    <option value="INSURANCE">Insurance</option>
                  </select>
                </div>
                @if (form.controls.paymentType.value === 'INSURANCE') {
                  <div class="col-md-4">
                    <label class="form-label" for="plan">Insurance Plan *</label>
                    <select id="plan" class="form-select" formControlName="insurancePlanUid"
                            [class.is-invalid]="invalid('insurancePlanUid')">
                      <option value="">— select —</option>
                      @for (pl of plans(); track pl.uid) { <option [value]="pl.uid">{{ pl.name }}</option> }
                    </select>
                    @if (invalid('insurancePlanUid')) { <div class="invalid-feedback">Select a plan.</div> }
                  </div>
                  <div class="col-md-4">
                    <label class="form-label" for="memberNo">Membership No. *</label>
                    <input id="memberNo" class="form-control" formControlName="membershipNo"
                           [class.is-invalid]="invalid('membershipNo')" />
                    @if (invalid('membershipNo')) { <div class="invalid-feedback">Required for insurance.</div> }
                  </div>
                }
              }

              <!-- Contact extras -->
              <div class="col-md-6">
                <label class="form-label" for="address">Address</label>
                <input id="address" class="form-control" formControlName="address" />
              </div>
              <div class="col-md-6">
                <label class="form-label" for="email">Email</label>
                <input id="email" type="email" class="form-control" formControlName="email" />
              </div>
              <div class="col-md-4">
                <label class="form-label" for="nationalId">National ID</label>
                <input id="nationalId" class="form-control" formControlName="nationalId" />
              </div>

              <!-- Next of kin -->
              <div class="col-12"><hr class="my-1"><h2 class="h6 text-muted">Next of Kin</h2></div>
              <div class="col-md-4">
                <label class="form-label" for="kinName">Full Name</label>
                <input id="kinName" class="form-control" formControlName="kinFullName" />
              </div>
              <div class="col-md-4">
                <label class="form-label" for="kinRel">Relationship</label>
                <input id="kinRel" class="form-control" formControlName="kinRelationship" />
              </div>
              <div class="col-md-4">
                <label class="form-label" for="kinPhone">Phone</label>
                <input id="kinPhone" class="form-control" formControlName="kinPhoneNo" />
              </div>
            </div>

            @if (apiError()) {
              <div class="alert alert-danger py-2 mt-3" role="alert">{{ apiError() }}</div>
            }

            <div class="d-flex gap-2 justify-content-end mt-3">
              <a class="btn btn-link" routerLink="/registration/patients">Cancel</a>
              <button type="submit" class="btn btn-primary" [disabled]="submitting() || form.invalid">
                @if (submitting()) {
                  <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
                  Saving…
                } @else { {{ editUid() ? 'Save Changes' : 'Register' }} }
              </button>
            </div>
          </form>
          }
        </div>
      </div>
    </div>
  `,
})
export class PatientFormComponent implements OnInit {
  private readonly fb        = inject(NonNullableFormBuilder);
  private readonly api       = inject(PatientControllerService);
  private readonly insurance = inject(InsurancePlanControllerService);
  private readonly route     = inject(ActivatedRoute);
  private readonly router    = inject(Router);

  protected readonly today          = new Date().toISOString().slice(0, 10);
  protected readonly editUid        = signal<string | null>(null);
  protected readonly loadingPatient = signal(false);
  protected readonly plans          = signal<InsurancePlanDto[]>([]);
  protected readonly submitting     = signal(false);
  protected readonly apiError       = signal<string | null>(null);

  readonly form = this.fb.group({
    firstName: ['', [Validators.required]],
    middleName: [''],
    lastName: ['', [Validators.required]],
    dateOfBirth: ['', [Validators.required]],
    gender: ['', [Validators.required]],
    phoneNo: [''],
    paymentType: ['CASH'],
    insurancePlanUid: [''],
    membershipNo: [''],
    address: [''],
    email: [''],
    nationalId: [''],
    kinFullName: [''],
    kinRelationship: [''],
    kinPhoneNo: [''],
  });

  ngOnInit(): void {
    // Insurance validators toggle with payment type.
    this.form.controls.paymentType.valueChanges.subscribe((pt) => {
      const plan = this.form.controls.insurancePlanUid;
      const mem = this.form.controls.membershipNo;
      if (pt === 'INSURANCE') {
        plan.setValidators([Validators.required]);
        mem.setValidators([Validators.required]);
        if (this.plans().length === 0) this.loadPlans();
      } else {
        plan.clearValidators(); mem.clearValidators();
        plan.setValue(''); mem.setValue('');
      }
      plan.updateValueAndValidity(); mem.updateValueAndValidity();
    });

    const uid = this.route.snapshot.paramMap.get('uid');
    if (uid) {
      this.editUid.set(uid);
      this.loadPatient(uid);
    }
  }

  protected invalid(name: keyof typeof this.form.controls): boolean {
    const c = this.form.controls[name];
    return c.invalid && c.touched;
  }

  private loadPlans(): void {
    this.insurance.list17().subscribe({
      next: (l) => this.plans.set((l ?? []).filter((p) => p.active !== false)),
      error: () => this.plans.set([]),
    });
  }

  private loadPatient(uid: string): void {
    this.loadingPatient.set(true);
    this.api.getByUid1({ uid }).subscribe({
      next: (p) => {
        this.form.patchValue({
          firstName: p.firstName ?? '', middleName: p.middleName ?? '', lastName: p.lastName ?? '',
          dateOfBirth: p.dateOfBirth ?? '', gender: p.gender ?? '', phoneNo: p.phoneNo ?? '',
          address: p.address ?? '', email: p.email ?? '', nationalId: p.nationalId ?? '',
          kinFullName: p.kinFullName ?? '', kinRelationship: p.kinRelationship ?? '', kinPhoneNo: p.kinPhoneNo ?? '',
        });
        this.loadingPatient.set(false);
      },
      error: () => { this.loadingPatient.set(false); this.apiError.set('Failed to load the patient.'); },
    });
  }

  submit(): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    this.submitting.set(true);
    this.apiError.set(null);
    const v = this.form.getRawValue();
    const uid = this.editUid();

    if (uid) {
      this.api.updatePatient({ uid, updatePatientRequest: {
        firstName: v.firstName, middleName: v.middleName || undefined, lastName: v.lastName,
        dateOfBirth: v.dateOfBirth, gender: v.gender, phoneNo: v.phoneNo || undefined,
        address: v.address || undefined, email: v.email || undefined, nationalId: v.nationalId || undefined,
        kinFullName: v.kinFullName || undefined, kinRelationship: v.kinRelationship || undefined,
        kinPhoneNo: v.kinPhoneNo || undefined,
      }}).subscribe({
        next: () => this.router.navigate(['/registration/patients', uid]),
        error: (e) => this.fail(e),
      });
    } else {
      this.api.registerPatient({ registerPatientRequest: {
        firstName: v.firstName, middleName: v.middleName || undefined, lastName: v.lastName,
        dateOfBirth: v.dateOfBirth, gender: v.gender,
        paymentType: v.paymentType as 'CASH' | 'INSURANCE',
        phoneNo: v.phoneNo || undefined, address: v.address || undefined, email: v.email || undefined,
        nationalId: v.nationalId || undefined,
        insurancePlanUid: v.paymentType === 'INSURANCE' ? v.insurancePlanUid : undefined,
        membershipNo: v.paymentType === 'INSURANCE' ? v.membershipNo : undefined,
        kinFullName: v.kinFullName || undefined, kinRelationship: v.kinRelationship || undefined,
        kinPhoneNo: v.kinPhoneNo || undefined,
      }}).subscribe({
        next: (p) => this.router.navigate(['/registration/patients', p.uid]),
        error: (e) => this.fail(e),
      });
    }
  }

  private fail(err: unknown): void {
    this.submitting.set(false);
    const p = extractProblem(err);
    if (p.type === 'urn:hmis:error:service-price-not-found') {
      this.apiError.set('Registration fee is not configured (no REGISTRATION service price). Set it in Master Data first.');
    } else if (p.status === 422) {
      this.apiError.set(p.title ?? 'Validation failed.');
    } else {
      this.apiError.set('Failed to save the patient. Please try again.');
    }
  }
}
