import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AdministrationRouteControllerService,
  ClinicControllerService,
  DiagnosisTypeControllerService,
  ItemControllerService,
  InsuranceProviderControllerService,
  LabTestTypeControllerService,
  MedicineControllerService,
  PharmacyControllerService,
  ProcedureTypeControllerService,
  RadiologyTypeControllerService,
  StoreControllerService,
  SupplierControllerService,
  WardCategoryControllerService,
  WardTypeControllerService,
} from '../../api/generated';
import { CatalogConfig, F, FieldDef } from './catalog-config';

/** Standard code/name/description/active field set used by most simple catalogs. */
const SIMPLE: readonly FieldDef[] = [F.code, F.name, F.description, F.active];

/**
 * Registry of masterdata catalog configs, keyed by URL slug. Each config binds the generated
 * API service's (operationId-numbered) list/create/update methods as typed closures so the
 * generic {@link MasterdataCrudComponent} can drive them.
 *
 * First batch = the standard code/name catalogs + a few typed ones. Catalogs with special shapes
 * (service-prices = upsert/resolve; insurance-plans = createUnderProvider) are deferred.
 */
@Injectable({ providedIn: 'root' })
export class CatalogRegistry {
  private readonly wardCat   = inject(WardCategoryControllerService);
  private readonly wardType  = inject(WardTypeControllerService);
  private readonly medicine  = inject(MedicineControllerService);
  private readonly item      = inject(ItemControllerService);
  private readonly supplier  = inject(SupplierControllerService);
  private readonly insProv   = inject(InsuranceProviderControllerService);
  private readonly diagType  = inject(DiagnosisTypeControllerService);
  private readonly labType   = inject(LabTestTypeControllerService);
  private readonly radType   = inject(RadiologyTypeControllerService);
  private readonly procType  = inject(ProcedureTypeControllerService);
  private readonly route     = inject(AdministrationRouteControllerService);
  private readonly clinic    = inject(ClinicControllerService);
  private readonly pharmacy  = inject(PharmacyControllerService);
  private readonly store     = inject(StoreControllerService);

  private readonly map: Record<string, CatalogConfig> = {
    'ward-categories': {
      slug: 'ward-categories', title: 'Ward Categories', icon: 'bi-diagram-3',
      blurb: 'Groupings of wards (e.g. medical, surgical).',
      fields: SIMPLE,
      list: () => this.wardCat.list2(),
      create: (b) => this.wardCat.create3({ wardCategoryRequest: b as never }),
      update: (uid, b) => this.wardCat.update2({ uid, wardCategoryRequest: b as never }),
    },
    'ward-types': {
      slug: 'ward-types', title: 'Ward Types', icon: 'bi-hospital',
      blurb: 'Ward classes with a per-stay bed price.',
      fields: [F.code, F.name, F.description, F.price, F.active],
      list: () => this.wardType.list1(),
      create: (b) => this.wardType.create2({ wardTypeRequest: b as never }),
      update: (uid, b) => this.wardType.update1({ uid, wardTypeRequest: b as never }),
    },
    'medicines': {
      slug: 'medicines', title: 'Medicines', icon: 'bi-capsule',
      blurb: 'Drug catalog (price, unit of measure).',
      fields: [
        F.code, F.name, F.description,
        { key: 'uom', label: 'Unit', type: 'text' },
        F.price, F.active,
      ],
      list: () => this.medicine.list11(),
      create: (b) => this.medicine.create11({ medicineRequest: b as never }),
      update: (uid, b) => this.medicine.update10({ uid, medicineRequest: b as never }),
    },
    'items': {
      slug: 'items', title: 'Items', icon: 'bi-box-seam',
      blurb: 'Generic inventory / consumable items.',
      fields: [
        F.code, F.name, F.description,
        { key: 'uom', label: 'Unit', type: 'text' },
        F.active,
      ],
      list: () => this.item.list13(),
      create: (b) => this.item.create13({ itemRequest: b as never }),
      update: (uid, b) => this.item.update12({ uid, itemRequest: b as never }),
    },
    'suppliers': {
      slug: 'suppliers', title: 'Suppliers', icon: 'bi-truck',
      blurb: 'Procurement suppliers.',
      fields: [
        F.code, F.name,
        { key: 'address', label: 'Address', type: 'text', inList: false },
        { key: 'telephone', label: 'Telephone', type: 'text' },
        { key: 'email', label: 'Email', type: 'text', inList: false },
        F.active,
      ],
      list: () => this.supplier.list4(),
      create: (b) => this.supplier.create5({ supplierRequest: b as never }),
      update: (uid, b) => this.supplier.update4({ uid, supplierRequest: b as never }),
    },
    'insurance-providers': {
      slug: 'insurance-providers', title: 'Insurance Providers', icon: 'bi-shield-plus',
      blurb: 'Insurers (NHIF, private).',
      fields: [
        F.code, F.name,
        { key: 'telephone', label: 'Telephone', type: 'text' },
        { key: 'email', label: 'Email', type: 'text', inList: false },
        { key: 'website', label: 'Website', type: 'text', inList: false },
        F.active,
      ],
      list: () => this.insProv.list16(),
      create: (b) => this.insProv.create16({ insuranceProviderRequest: b as never }),
      update: (uid, b) => this.insProv.update15({ uid, insuranceProviderRequest: b as never }),
    },
    'diagnosis-types': {
      slug: 'diagnosis-types', title: 'Diagnosis Types', icon: 'bi-clipboard2-pulse',
      blurb: 'ICD diagnosis catalog.',
      fields: SIMPLE,
      list: () => this.diagType.list19(),
      create: (b) => this.diagType.create17({ diagnosisTypeRequest: b as never }),
      update: (uid, b) => this.diagType.update17({ uid, diagnosisTypeRequest: b as never }),
    },
    'lab-test-types': {
      slug: 'lab-test-types', title: 'Lab Test Types', icon: 'bi-eyedropper',
      blurb: 'Laboratory test catalog.',
      fields: SIMPLE,
      list: () => this.labType.list12(),
      create: (b) => this.labType.create12({ labTestTypeRequest: b as never }),
      update: (uid, b) => this.labType.update11({ uid, labTestTypeRequest: b as never }),
    },
    'radiology-types': {
      slug: 'radiology-types', title: 'Radiology Types', icon: 'bi-radioactive',
      blurb: 'Imaging modality catalog.',
      fields: SIMPLE,
      list: () => this.radType.list8(),
      create: (b) => this.radType.create8({ radiologyTypeRequest: b as never }),
      update: (uid, b) => this.radType.update7({ uid, radiologyTypeRequest: b as never }),
    },
    'procedure-types': {
      slug: 'procedure-types', title: 'Procedure Types', icon: 'bi-scissors',
      blurb: 'Procedure / minor-op catalog.',
      fields: SIMPLE,
      list: () => this.procType.list9(),
      create: (b) => this.procType.create9({ procedureTypeRequest: b as never }),
      update: (uid, b) => this.procType.update8({ uid, procedureTypeRequest: b as never }),
    },
    'administration-routes': {
      slug: 'administration-routes', title: 'Administration Routes', icon: 'bi-signpost-split',
      blurb: 'Medication routes for MAR (IV, PO, IM…).',
      fields: SIMPLE,
      list: () => this.route.list23(),
      create: (b) => this.route.create22({ administrationRouteRequest: b as never }),
      update: (uid, b) => this.route.update21({ uid, administrationRouteRequest: b as never }),
    },
    'clinics': {
      slug: 'clinics', title: 'Clinics', icon: 'bi-building',
      blurb: 'Clinic units.',
      fields: SIMPLE,
      list: () => this.clinic.list21(),
      create: (b) => this.clinic.create20({ clinicRequest: b as never }),
      update: (uid, b) => this.clinic.update19({ uid, clinicRequest: b as never }),
    },
    'pharmacies': {
      slug: 'pharmacies', title: 'Pharmacies', icon: 'bi-prescription2',
      blurb: 'Pharmacy units.',
      fields: SIMPLE,
      list: () => this.pharmacy.list10(),
      create: (b) => this.pharmacy.create10({ pharmacyRequest: b as never }),
      update: (uid, b) => this.pharmacy.update9({ uid, pharmacyRequest: b as never }),
    },
    'stores': {
      slug: 'stores', title: 'Stores', icon: 'bi-shop',
      blurb: 'Inventory stores / warehouses.',
      fields: SIMPLE,
      list: () => this.store.list6(),
      create: (b) => this.store.create7({ storeRequest: b as never }),
      update: (uid, b) => this.store.update6({ uid, storeRequest: b as never }),
    },
  };

  /** All catalogs (for the index grid). */
  all(): CatalogConfig[] {
    return Object.values(this.map);
  }

  /** A single catalog by slug (for the CRUD route). */
  get(slug: string): CatalogConfig | undefined {
    return this.map[slug];
  }
}
