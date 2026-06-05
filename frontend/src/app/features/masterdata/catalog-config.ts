import { Observable } from 'rxjs';

/** A field rendered in the catalog table + add/edit form. */
export interface FieldDef {
  /** Property key on the DTO / request object. */
  readonly key: string;
  /** Column + form label. */
  readonly label: string;
  /** Input type. */
  readonly type: 'text' | 'textarea' | 'number' | 'checkbox' | 'select';
  /** Required in the form. */
  readonly required?: boolean;
  /** Show this field as a column in the list table (default true). */
  readonly inList?: boolean;
  /** Options for a 'select' field (static) — or omit and provide optionsFrom. */
  readonly options?: readonly { value: string; label: string }[];
  /** For a select backed by another catalog: a loader returning {value,label} options. */
  readonly optionsFrom?: () => Observable<{ value: string; label: string }[]>;
}

/** A catalog row — any DTO; the generic CRUD reads fields dynamically by key. */
export type CatalogRow = Record<string, unknown>;

/**
 * Config for one masterdata catalog. Holds CLOSURES over the generated API service methods
 * (method names are operationId-numbered and vary per catalog, so we bind by reference). The
 * list/create/update return concrete DTO observables in the registry; the generic component only
 * ever reads them as untyped rows, so the closures are typed loosely here (Observable<unknown…>).
 */
export interface CatalogConfig {
  /** URL slug (route param) e.g. 'ward-categories'. */
  readonly slug: string;
  /** Display title e.g. 'Ward Categories'. */
  readonly title: string;
  /** Bootstrap icon class for the catalog index card. */
  readonly icon: string;
  /** Short description for the catalog index card. */
  readonly blurb: string;
  /** Field definitions (drive the table columns + the form). */
  readonly fields: readonly FieldDef[];
  /** List all rows. */
  readonly list: () => Observable<readonly unknown[]>;
  /** Create a row from the form value (already shaped to the request). */
  readonly create: (body: Record<string, unknown>) => Observable<unknown>;
  /** Update a row by uid. */
  readonly update: (uid: string, body: Record<string, unknown>) => Observable<unknown>;
}

/** Common field sets reused across catalogs. */
export const F = {
  code: { key: 'code', label: 'Code', type: 'text', required: true } as FieldDef,
  name: { key: 'name', label: 'Name', type: 'text', required: true } as FieldDef,
  description: { key: 'description', label: 'Description', type: 'textarea', inList: false } as FieldDef,
  active: { key: 'active', label: 'Active', type: 'checkbox' } as FieldDef,
  price: { key: 'price', label: 'Price', type: 'number' } as FieldDef,
};
