## EXTRACTION 2 — Inventory Catalog (legacy `com.orbix.api`, read-only)

All paths below are absolute under the legacy root:
`D:\My_Works\HMS\ZANAHMIS-2-feature\Zana-HMIS-API\api\api\src\main\java\com\orbix\api`

### Early-discovery findings (mandatory, included per engagement rule)
- **Audit trail:** No `@Audited` annotation appears on any inventory-catalog entity (`Item`, `Medicine`, `ItemMedicineCoefficient`, `ItemSupplier`, `Supplier`, `SupplierItemPrice`). These entities carry only manual audit columns (`created_by_user_id`, `created_on_day_id`, `createdAt`). **No Hibernate Envers audit trail is effectively active in the legacy system — the dependency is present but no entity is annotated `@Audited`. Downstream agents must not assume an Envers audit baseline exists.** (Consistent with memory note `zana-legacy-phantom-features.md`.)
- **Device-fingerprint / device-binding:** Not relevant to these master-data entities; none observed in any inventory class. **No device-fingerprint or device-binding feature exists in the legacy system. Agents must not treat this as a feature to preserve or re-implement.**

### Q1 — Are Medicine and Item separate entities or one entity with a type discriminator?
**They are two completely separate JPA entities, separate tables, no inheritance, no discriminator.**
- `Item` → `@Table(name = "items")` — `domain\Item.java:34-35`
- `Medicine` → `@Table(name = "medicines")` — `domain\Medicine.java:37-38`

They are linked only via the cross-reference entity `ItemMedicineCoefficient` (`domain\ItemMedicineCoefficient.java`), which holds a `@OneToOne` to `Item` (`:42-45`) and a `@ManyToOne` to `Medicine` (`:50-53`). Semantically: an `Item` is the **purchased/stored SKU** (procurement/store side); a `Medicine` is the **dispensed/pharmacy SKU** (pharmacy/sale side). The coefficient converts store-SKU quantity into pharmacy-SKU quantity. There is no single unified product entity.

### Q2 — How does a medicine/item carry its CASH price?
**As a plain `double` field on the entity itself. There is NO separate cash-price table.** (Glob for `*ItemPrice/MedicinePrice/CashPrice/SellingPrice*` returns only `SupplierItemPrice`/`SupplierItemPriceList`, which are supplier/cost-side, not cash-sale prices.)

- **Medicine cash price:** `Medicine.price` (`double`, `@NotNull`) — `domain\Medicine.java:51-52`. This is the dispensing/cash unit price.
  - Confirmed consumption: cash patient bill = `medicine.getPrice() * qty` in `service\PatientServiceImpl.java:1534` and `:1536` (outpatient prescription), `:2308`/`:2310` (ward consumable chart), `:3422`/`:3424` (medicine sale, `billItem = "Medicine Sale"` at `:3426`).
  - Insurance overrides this with a per-plan price from `MedicineInsurancePlan.getPrice()` (`PatientServiceImpl.java:1549-1553`, `:2323-2327`) — the entity `price` is the cash/default; the plan price is the insured rate. (Insurance pricing tables are out-of-scope for this extraction but cited so the cash-vs-plan fork is unambiguous.)
- **Item prices:** `Item` carries TWO cash-side `double` fields — `costPriceVatIncl` (`Item.java:54`) and `sellingPriceVatIncl` (`Item.java:55`), plus `vat` (`:49`). Items are store/procurement objects; their `sellingPriceVatIncl` is not used in any patient-billing path (no `*Sale*` service reads `getSellingPriceVatIncl`; grep returned no patient-bill consumer). Item cost prices feed procurement (LPO/GRN), not point-of-care cash sale.
- **Supplier/cost prices (distinct from cash):** `ItemSupplier.costPriceVatIncl`/`costPriceVatExcl` (`domain\ItemSupplier.java:46-47`) and `SupplierItemPrice.price` (`domain\SupplierItemPrice.java:40`) are supplier-quoted purchase prices, not cash-sale prices.

### Q3 — Category: entity or enum? Unit-of-measure: which entity?
**Both are plain `String` fields — NO dedicated entity and NO Java enum exists for either.**
- **Category:**
  - `Item.category` is `private String category;` — `domain\Item.java:52` (free text, no default).
  - `Medicine.category` is `private String category = "MEDICINE";` — `domain\Medicine.java:56` (hard-coded default literal string, not an enum).
  - Glob for `*Category*` in `domain\` returns only `WardCategory.java`, which is part of the ward domain and **unrelated** to inventory categorization.
  - `Medicine.type` (`@NotBlank private String type; //ORAL, ETC` — `domain\Medicine.java:49-50`) is also a free-text String, not a discriminator and not an enum. The comment `//ORAL, ETC` documents intended values but there is no enforcement.
- **Unit of measure (UOM):**
  - `Item.uom` is `private String uom;` — `domain\Item.java:50` (free text).
  - `Medicine.uom` is `private String uom;` — `domain\Medicine.java:53` (free text).
  - No `Uom`/`UnitOfMeasure`/`Unit`/`Measure` entity or enum exists anywhere in `domain\` (Glob/Grep returned none). UOM is purely a label string; conversion between store/pharmacy units is carried numerically by `ItemMedicineCoefficient`, not by any UOM entity.

### Q4 — ItemMedicineCoefficient: exact fields, semantics, conversion math, precision
**Entity:** `domain\ItemMedicineCoefficient.java`, `@Table(name = "item_medicine_coefficients")` (`:34`).
Fields:
- `Long id` (`:36-38`, IDENTITY)
- `double coefficient = 0` (`:40`) — the **derived** multiplier
- `Item item` — `@OneToOne ... optional=false`, `item_id` not-null/not-updatable (`:42-45`)
- `double itemQty = 0` (`:47`) — store-side quantity of the conversion ratio
- `double medicineQty = 0` (`:48`) — pharmacy-side quantity of the conversion ratio
- `Medicine medicine` — `@ManyToOne ... optional=false`, `medicine_id` not-null/not-updatable (`:50-53`)
- manual audit columns `:55-59`

**Coefficient semantics / derivation (authoritative, from the save endpoint):** in `api\ConversionCoefficientResource.java`:
- Validation: `itemQty` and `medicineQty` must both be > 0, else `InvalidEntryException("Zero values are not allowed")` — `:87-89`.
- Uniqueness: one coefficient per (item, medicine) pair; duplicate save with a different id throws `InvalidOperationException("Coefficient already exist...")` — `:83-86`.
- **Formula:** `coefficient = medicineQty / itemQty` — set identically on both update (`:95`) and create (`:101`). So the coefficient is "number of pharmacy/dispensing units per one store/purchase unit."

**Conversion math (authoritative, live code):**
- `pharmacySKUQty = storeSKUQty * coefficient`:
  - `api\InternalOrderResource.java:595` — `batch.setPharmacySKUQty(batch.getStoreSKUQty() * coe.get().getCoefficient());` (LIVE; also guards qty>0 at `:592-594` and requires a coefficient to exist at `:589-591`).
  - `service\StoreToPharmacyTOServiceImpl.java:424` — `d.get().setTransferedPharmacySKUQty(detail.getTransferedStoreSKUQty() * imc.get().getCoefficient());` (LIVE; missing-coefficient guard at `:417-420`: `NotFoundException("Item to medicine conversion factor does not exist...")`).
- **Lookup key:** `itemMedicineCoefficientRepository.findByItemAndMedicine(item, medicine)` — `repositories\ItemMedicineCoefficientRepository.java:26`; reverse listing `findAllByMedicine(medicine)` (`:32`, used in `InternalOrderResource.java:660-665` to list candidate source items for a medicine).

**Commented-out / inactive paths (flag — do NOT treat as behaviour):**
- `service\StoreToPharmacyRNServiceImpl.java:319-321` — the conversion lines are **commented out**; the receiving-note path saves the detail without applying any coefficient.
- `service\PharmacyToPharmacyRNServiceImpl.java:308-310` — conversion lines **commented out**.
- `api\InternalOrderResource.java:1245-1251` — a second internal-order path has the coefficient lookup and `batch.setQty(... * coefficient)` **commented out**, applying raw `qty` instead.
This is a genuine inconsistency: transfer-order (TO) and one internal-order batch path apply the coefficient; the receiving-note (RN) paths and a second internal-order path do not. Logged in decisions[].

**Precision (confirm 19,6 intent):** every quantity/ratio field above (`coefficient`, `itemQty`, `medicineQty`, `packSize`, and the transferred-SKU qtys) is a Java `double` in legacy — no `@Column(precision/scale)` is declared anywhere. There is no legacy evidence prescribing 6 decimal places; `NUMERIC(19,6)` for qty/coefficient is a **target-side modernization decision** (per directives/MEMORY), not a legacy fact. Money-like fields (`Item.costPriceVatIncl`, `Item.sellingPriceVatIncl`, `Medicine.price`, `ItemSupplier.cost*`, `SupplierItemPrice.price`) are also `double` in legacy → map to `NUMERIC(19,2)` per directive. Confirm with data-architect that `coefficient = medicineQty/itemQty` (a division) does not lose required precision at scale 6.

### Q5 — ItemSupplier, Supplier, SupplierItemPrice, SupplierItemPriceList
- **Supplier** (`domain\Supplier.java`, `@Table(name="suppliers")`): mandatory `code` (`@NotBlank` unique, `:35-37`), `name` (`@NotBlank` unique, `:38-40`), `contactName` (`@NotBlank`, `:41-42`); `active` default true (`:43`); plus optional tax (`tin`, `vrn`), contact, and bank fields (`:44-59`); manual audit (`:61-65`).
- **ItemSupplier** (`domain\ItemSupplier.java`, `@Table(name="items_suppliers")`): join of `Item` (`@ManyToOne`, `:36-39`) and `Supplier` (`@ManyToOne`, `:41-44`); carries `costPriceVatIncl`/`costPriceVatExcl` doubles (`:46-47`) and `active` (`:49`). Note: NO manual audit columns here (unlike the other inventory entities).
- **SupplierItemPrice** (`domain\SupplierItemPrice.java`, `@Table(name="supplier_item_prices")`): `price` double default 0 (`:40`), `terms` String (`:41`), `active` (`:42`); `@ManyToOne` to `Supplier` (`:45-48`) and `Item` (`:50-53`); manual audit (`:55-59`).
- **SupplierItemPriceList** (`domain\SupplierItemPriceList.java`): **NOT a JPA entity** — a plain `@Data` POJO/DTO (no `@Entity`/`@Table`/`@Id`) holding one `Supplier` plus `List<SupplierItemPrice>` (`:15-19`). It is a response/aggregation wrapper only.

There is an apparent functional overlap between `ItemSupplier` (item↔supplier with cost prices) and `SupplierItemPrice` (supplier↔item with `price`+`terms`). Both model a supplier price for an item via different shapes. Flagged in decisions[].

### Cross-reference index (legacy artefact → topic)
- `Item` / `items` → catalog SKU, cost+selling cash prices, vat, packSize, uom/category strings.
- `Medicine` / `medicines` → pharmacy SKU, cash `price`, `type`/`category`/`uom` strings.
- `ItemMedicineCoefficient` / `item_medicine_coefficients` → store→pharmacy unit conversion (`coefficient = medicineQty/itemQty`).
- `Supplier`, `ItemSupplier`, `SupplierItemPrice` / `suppliers`, `items_suppliers`, `supplier_item_prices` → supplier master + supplier cost/price.
- `SupplierItemPriceList` → non-persistent DTO.
- `ConversionCoefficientResource` → coefficient CRUD + validation.
- `InternalOrderResource`, `StoreToPharmacyTOServiceImpl`, `StoreToPharmacyRNServiceImpl`, `PharmacyToPharmacyRNServiceImpl` → coefficient consumers (some live, some commented out).