import { getAccessToken } from '../auth';

/**
 * Tiny fetch wrapper. The Vite dev server proxies /api/** to the console-api
 * (see vite.config.ts), so a relative URL works in dev and in prod.
 *
 * Public endpoints (/api/v1/public/**) are called without a token; for every
 * other path we attach the bearer token from the OIDC user store.
 */
async function get<T>(path: string): Promise<T> {
  const headers: Record<string, string> = {};
  if (!path.startsWith('/api/v1/public/')) {
    const token = getAccessToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }
  const r = await fetch(path, { headers });
  if (!r.ok) throw new Error(`${r.status} ${r.statusText} on ${path}`);
  return r.json() as Promise<T>;
}

// --- Public KPIs (landing) -------------------------------------------------

export type MonthBucket = { month: string; count: number };

export type PublicKpis = {
  patientsActive: number;
  sitesWithData: number;
  viralSuppression: number | null;
  arvCoverage: number | null;
  activeFile: MonthBucket[];
};

export function fetchPublicKpis() {
  return get<PublicKpis>('/api/v1/public/kpis');
}

// --- Dashboard KPIs (authenticated) ---------------------------------------

export type SyncAlerts = {
  sitesNoSync7d: number;
  sitesNoSync24h: number;
  lastBatchAt: string | null;
};

export type DashboardKpis = {
  fileActive: number;
  txNewMonth: number;
  viralSuppression: number | null;
  sitesOnline: number;
  sitesTotalScope: number;
  activeFile: MonthBucket[];
  syncAlerts: SyncAlerts;
};

export function fetchDashboardKpis(scope: GeoScopeQ = {}) {
  const params = new URLSearchParams();
  appendScope(params, scope);
  const qs = params.toString();
  return get<DashboardKpis>(`/api/v1/dashboard/kpis${qs ? '?' + qs : ''}`);
}

export type RegionBucket = { regionId: number; regionName: string; count: number };

export function fetchFileActiveByRegion(scope: GeoScopeQ = {}) {
  const params = new URLSearchParams();
  appendScope(params, scope);
  const qs = params.toString();
  return get<RegionBucket[]>(`/api/v1/dashboard/file-active-by-region${qs ? '?' + qs : ''}`);
}

// --- Patients --------------------------------------------------------------

export type PatientRow = {
  id: number;
  sourceUuid: string;
  sex: string | null;
  birthDate: string | null;
  siteCode: string;
  siteName: string;
  codeArv: string | null;
  upid: string | null;
  arvInitDate: string | null;
  arvRegimenInitial: string | null;
  lastVisitDate: string | null;
  lastArvRegimen: string | null;
};

export type PatientPage = {
  content: PatientRow[];
  total: number;
  page: number;
  size: number;
};

export type PatientDetail = {
  id: number;
  sourceUuid: string;
  sex: string | null;
  birthDate: string | null;
  profession: string | null;
  educationLevel: string | null;
  maritalStatus: string | null;
  birthPlace: string | null;
  religion: string | null;
  siteCode: string;
  siteName: string;
  identifiers: string[];
};

export type Observation = { label: string; value: string };

export type DateBlock = {
  date: string;
  kind: 'visit' | 'initiation' | 'closure' | 'lab';
  label: string;
  observations: Observation[];
};

export type EncounterDay = {
  date: string;
  blocks: DateBlock[];
};

export function fetchPatients(opts: {
  q?: string;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  if (opts.q) params.set('q', opts.q);
  if (opts.regionId) params.set('regionId', String(opts.regionId));
  if (opts.districtId) params.set('districtId', String(opts.districtId));
  if (opts.siteId) params.set('siteId', String(opts.siteId));
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 25));
  return get<PatientPage>(`/api/v1/patients?${params}`);
}

export async function downloadPatientsCsv(opts: {
  q?: string;
  regionId?: number;
  districtId?: number;
  siteId?: number;
}): Promise<void> {
  const params = new URLSearchParams();
  if (opts.q) params.set('q', opts.q);
  if (opts.regionId) params.set('regionId', String(opts.regionId));
  if (opts.districtId) params.set('districtId', String(opts.districtId));
  if (opts.siteId) params.set('siteId', String(opts.siteId));
  const url = `/api/v1/patients/list.csv?${params}`;
  await downloadCsv(url, 'patients.csv');
}

export function fetchPatient(id: number) {
  return get<PatientDetail>(`/api/v1/patients/${id}`);
}

export function fetchPatientEncounters(id: number) {
  return get<EncounterDay[]>(`/api/v1/patients/${id}/encounters`);
}

// --- Sites -----------------------------------------------------------------

export type SiteStatus = 'all' | 'online' | 'late' | 'offline';

export type SiteRow = {
  id: number;
  code: string;
  name: string;
  facilityType: string | null;
  runsSigdep: boolean | null;
  lastSyncAt: string | null;
  districtId: number;
  districtName: string;
  regionId: number;
  regionName: string;
  patientCount: number;
};

export type SitePage = {
  content: SiteRow[];
  total: number;
  page: number;
  size: number;
};

export type RegionRef = { id: number; name: string };
export type DistrictRef = { id: number; regionId: number; name: string };
export type SiteShort = { id: number; code: string; name: string; districtId: number };

/** Geo scope filter shared across pages. */
export type GeoScopeQ = {
  regionId?: number;
  districtId?: number;
  siteId?: number;
};

/** Append region/district/site IDs to a URLSearchParams when set. */
export function appendScope(params: URLSearchParams, scope: GeoScopeQ): void {
  if (scope.regionId)   params.set('regionId',   String(scope.regionId));
  if (scope.districtId) params.set('districtId', String(scope.districtId));
  if (scope.siteId)     params.set('siteId',     String(scope.siteId));
}

/** Sort spec sent to listing endpoints. `null` means "use the server default". */
export type SortQ = { key: string; dir: 'asc' | 'desc' } | null;

export function appendSort(params: URLSearchParams, sort: SortQ): void {
  if (!sort) return;
  params.set('sort', sort.key);
  params.set('dir', sort.dir);
}

export function fetchSites(opts: {
  q?: string;
  status?: SiteStatus;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  if (opts.q) params.set('q', opts.q);
  if (opts.status && opts.status !== 'all') params.set('status', opts.status);
  if (opts.regionId) params.set('regionId', String(opts.regionId));
  if (opts.districtId) params.set('districtId', String(opts.districtId));
  if (opts.siteId) params.set('siteId', String(opts.siteId));
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<SitePage>(`/api/v1/sites?${params}`);
}

export function fetchRegions() {
  return get<RegionRef[]>('/api/v1/sites/regions');
}

export function fetchDistricts(regionId?: number) {
  const params = new URLSearchParams();
  if (regionId) params.set('regionId', String(regionId));
  return get<DistrictRef[]>(`/api/v1/sites/districts?${params}`);
}

export function fetchSitesOf(regionId?: number, districtId?: number) {
  const params = new URLSearchParams();
  if (regionId) params.set('regionId', String(regionId));
  if (districtId) params.set('districtId', String(districtId));
  return get<SiteShort[]>(`/api/v1/sites/list-of?${params}`);
}

// --- Biology ---------------------------------------------------------------

export type MonthlySuppression = {
  month: string;
  total: number;
  suppressed: number;
  pct: number | null;
};

export type Cd4Distribution = {
  lt200: number;
  b200_350: number;
  b350_500: number;
  ge500: number;
  total: number;
};

export type TopTest = { testName: string; count: number };

export type BiologySummary = {
  examsInPeriod: number;
  examsAllTime: number;
  lastExamDate: string | null;
  viralSuppressionPct: number | null;
  monthlySuppression: MonthlySuppression[];
  cd4Distribution: Cd4Distribution;
  topTests: TopTest[];
  periodMonths: number;
};

export type ExamRow = {
  id: number;
  examDate: string | null;
  testName: string;
  valueNumeric: number | null;
  valuePct: number | null;
  valueText: string | null;
  unit: string | null;
  siteCode: string;
  siteName: string;
  patientId: number;
  patientCode: string | null;
};

export function fetchBiologySummary(months: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  return get<BiologySummary>(`/api/v1/biology/summary?${params}`);
}

export type ExamFilter = 'vl' | 'cd4' | 'all';

export type ExamPage = {
  content: ExamRow[];
  total: number;
  page: number;
  size: number;
};

export function fetchBiologyExams(opts: {
  test: ExamFilter;
  months: number;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  if (opts.test !== 'all') params.set('test', opts.test);
  params.set('months', String(opts.months));
  appendScope(params, opts);
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<ExamPage>(`/api/v1/biology/exams?${params}`);
}

/**
 * Trigger a CSV download for the given filter, sending the bearer token
 * the same way as a normal API call so the backend can authorize the
 * request and stream the file.
 */
export async function downloadBiologyCsv(opts: {
  test: ExamFilter;
  months: number;
  regionId?: number;
  districtId?: number;
  siteId?: number;
}): Promise<void> {
  const params = new URLSearchParams();
  if (opts.test !== 'all') params.set('test', opts.test);
  params.set('months', String(opts.months));
  appendScope(params, opts);
  const url = `/api/v1/biology/exams.csv?${params}`;
  const filename = `biology-${opts.test}-${opts.months}m.csv`;
  await downloadCsv(url, filename);
}

async function downloadCsv(url: string, filename: string): Promise<void> {
  const headers: Record<string, string> = {};
  const token = getAccessToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const r = await fetch(url, { headers });
  if (!r.ok) throw new Error(`${r.status} ${r.statusText} on ${url}`);
  const blob = await r.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(a.href);
}

// --- PEPFAR ----------------------------------------------------------------

export type DisaggCell = { sex: string | null; ageBand: string; count: number };
export type Disaggregated = { total: number; cells: DisaggCell[] };
export type TxPvls = {
  denominator: Disaggregated;
  numerator: Disaggregated;
  pct: number | null;
};
export type Pair = {
  denominator: Disaggregated;
  numerator: Disaggregated;
  pct: number | null;
};
export type Hts = {
  tst: Disaggregated;
  pos: Disaggregated;
  positivityPct: number | null;
};
export type Pmtct = {
  stat: Pair;
  art: Pair;
  eid: Pair;
};
export type QuarterRange = {
  fiscalYear: number;
  quarter: number;
  start: string;
  end: string;
};
export type MsdBucket = { msd: string; count: number };
export type PepfarReport = {
  period: QuarterRange;
  txNew: Disaggregated;
  txCurr: Disaggregated;
  txPvls: TxPvls;
  hts: Hts;
  pmtct: Pmtct;
  tbPrev: Pair;
  txCurrByMsd: MsdBucket[];
};

export function fetchPepfarReport(fy: number, q: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('fy', String(fy));
  params.set('q', String(q));
  appendScope(params, scope);
  return get<PepfarReport>(`/api/v1/pepfar/report?${params}`);
}

export async function downloadPepfarCsv(fy: number, q: number, scope: GeoScopeQ): Promise<void> {
  const params = new URLSearchParams();
  params.set('fy', String(fy));
  params.set('q', String(q));
  appendScope(params, scope);
  const url = `/api/v1/pepfar/report.csv?${params}`;
  const filename = `pepfar-FY${fy}Q${q}.csv`;
  await downloadCsv(url, filename);
}

// --- TPT -------------------------------------------------------------------

export type YearBucket = { year: number; count: number };
export type Bucket = { label: string; count: number };

export type TptSummary = {
  totalAllTime: number;
  startedInPeriod: number;
  completedInPeriod: number;
  ongoing: number;
  completionPct: number | null;
  yearly: YearBucket[];
  outcomes: Bucket[];
  adherence: Bucket[];
  statuses: Bucket[];
  regimens: Bucket[];
  periodMonths: number;
};

export type TptRecord = {
  id: number;
  recordDate: string | null;
  followupDate: string | null;
  endDate: string | null;
  outcome: string | null;
  orderNumber: string | null;
  tptStatus: string | null;
  tptRegimen: string | null;
  adherence: string | null;
  weightKg: number | null;
  nextVisitDate: string | null;
  patientId: number;
  patientCode: string | null;
  siteCode: string;
  siteName: string;
};

export type TptRecordPage = {
  content: TptRecord[];
  total: number;
  page: number;
  size: number;
};

export function fetchTptSummary(months: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  return get<TptSummary>(`/api/v1/tpt/summary?${params}`);
}

export function fetchTptRecords(opts: {
  months: number;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  params.set('months', String(opts.months));
  appendScope(params, opts);
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<TptRecordPage>(`/api/v1/tpt/records?${params}`);
}

export async function downloadTptCsv(months: number, scope: GeoScopeQ): Promise<void> {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  const url = `/api/v1/tpt/records.csv?${params}`;
  await downloadCsv(url, `tpt-${months}m.csv`);
}

// --- Dépistage (HIV screening) --------------------------------------------

export type ScreeningSiteTypeStat = {
  label: string;
  screened: number;
  positive: number;
  negative: number;
  positivityPct: number | null;
};

export type ScreeningSummary = {
  totalAllTime: number;
  screenedInPeriod: number;
  positiveInPeriod: number;
  negativeInPeriod: number;
  positivityPct: number | null;
  yearly: YearBucket[];
  results: Bucket[];
  populations: Bucket[];
  reasons: Bucket[];
  genders: Bucket[];
  siteTypes: ScreeningSiteTypeStat[];
  periodMonths: number;
};

export type ScreeningRecord = {
  id: number;
  screeningCode: string | null;
  screeningDate: string | null;
  resultAnnouncingDate: string | null;
  gender: string | null;
  age: number | null;
  populationType: string | null;
  screeningReason: string | null;
  finalResult: string | null;
  retesting: boolean | null;
  screeningSiteType: string | null;
  screeningPost: string | null;
  siteCode: string;
  siteName: string;
};

export type ScreeningRecordPage = {
  content: ScreeningRecord[];
  total: number;
  page: number;
  size: number;
};

export function fetchScreeningSummary(months: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  return get<ScreeningSummary>(`/api/v1/screenings/summary?${params}`);
}

export function fetchScreeningRecords(opts: {
  months: number;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  params.set('months', String(opts.months));
  appendScope(params, opts);
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<ScreeningRecordPage>(`/api/v1/screenings/records?${params}`);
}

export async function downloadScreeningCsv(months: number, scope: GeoScopeQ): Promise<void> {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  const url = `/api/v1/screenings/records.csv?${params}`;
  await downloadCsv(url, `depistage-${months}m.csv`);
}

// --- PTME -----------------------------------------------------------------

export type PtmeMotherSummary = {
  totalAllTime: number;
  inPeriod: number;
  spousalScreened: number;
  spousalPositive: number;
  spousalCoveragePct: number | null;
  yearly: YearBucket[];
  outcomes: Bucket[];
  arvAtRegistering: Bucket[];
  periodMonths: number;
};

export type PtmeMotherRecord = {
  id: number;
  sourceUuid: string;
  pregnantNumber: string | null;
  hivCareNumber: string | null;
  screeningNumber: string | null;
  age: number | null;
  maritalStatus: string | null;
  startDate: string | null;
  endDate: string | null;
  estimatedDeliveryDate: string | null;
  arvStatusAtRegistering: string | null;
  pregnancyOutcome: string | null;
  spousalScreening: string | null;
  spousalScreeningResult: string | null;
  deliveryType: string | null;
  siteCode: string;
  siteName: string;
};

export type PtmeMotherPage = {
  content: PtmeMotherRecord[];
  total: number;
  page: number;
  size: number;
};

export type PtmeChildSummary = {
  totalAllTime: number;
  inPeriod: number;
  anyPositive: number;
  prophylaxisGiven: number;
  positivityPct: number | null;
  yearly: YearBucket[];
  followupResults: Bucket[];
  pcr1: Bucket[];
  periodMonths: number;
};

export type PtmeChildRecord = {
  id: number;
  sourceUuid: string;
  motherSourceUuid: string | null;
  childFollowupNumber: string | null;
  birthDate: string | null;
  gender: string | null;
  arvProphylaxisGiven: string | null;
  arvProphylaxisGivenDate: string | null;
  pcr1Result: string | null;
  pcr2Result: string | null;
  pcr3Result: string | null;
  hivSerology1Result: string | null;
  hivSerology2Result: string | null;
  followupResult: string | null;
  followupResultDate: string | null;
  siteCode: string;
  siteName: string;
};

export type PtmeChildPage = {
  content: PtmeChildRecord[];
  total: number;
  page: number;
  size: number;
};

export function fetchPtmeMotherSummary(months: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  return get<PtmeMotherSummary>(`/api/v1/ptme/mothers/summary?${params}`);
}

export function fetchPtmeMotherRecords(opts: {
  months: number;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  params.set('months', String(opts.months));
  appendScope(params, opts);
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<PtmeMotherPage>(`/api/v1/ptme/mothers/records?${params}`);
}

export async function downloadPtmeMotherCsv(months: number, scope: GeoScopeQ): Promise<void> {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  await downloadCsv(`/api/v1/ptme/mothers/records.csv?${params}`, `ptme-meres-${months}m.csv`);
}

export function fetchPtmeChildSummary(months: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  return get<PtmeChildSummary>(`/api/v1/ptme/children/summary?${params}`);
}

export function fetchPtmeChildRecords(opts: {
  months: number;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  params.set('months', String(opts.months));
  appendScope(params, opts);
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<PtmeChildPage>(`/api/v1/ptme/children/records?${params}`);
}

export async function downloadPtmeChildCsv(months: number, scope: GeoScopeQ): Promise<void> {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  await downloadCsv(`/api/v1/ptme/children/records.csv?${params}`, `ptme-enfants-${months}m.csv`);
}

// --- Clinic (suivi clinique) -----------------------------------------------

export type MonthlyCount = { month: string; count: number; dispensations: number; expected: number };

export type ClinicSummary = {
  visitsAllTime: number;
  visitsInPeriod: number;
  withTbScreening: number;
  withWhoStage: number;
  tbScreeningPct: number | null;
  whoStagePct: number | null;
  monthly: MonthlyCount[];
  whoStageDistribution: Bucket[];
  tbScreeningDistribution: Bucket[];
  arvRegimenDistribution: Bucket[];
  periodMonths: number;
};

export type VisitRow = {
  id: number;
  visitDate: string | null;
  nextVisitDate: string | null;
  sourceForm: string | null;
  arvRegimen: string | null;
  arvTreatmentDays: number | null;
  cotrimTreatmentDays: number | null;
  weightKg: number | null;
  heightCm: number | null;
  bmi: number | null;
  viralLoad: number | null;
  cd4Count: number | null;
  tptStatus: string | null;
  tptRegimen: string | null;
  whoStage: string | null;
  tbScreening: string | null;
  patientId: number;
  patientCode: string | null;
  siteCode: string;
  siteName: string;
};

export type VisitPage = {
  content: VisitRow[];
  total: number;
  page: number;
  size: number;
};

export function fetchClinicSummary(months: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  return get<ClinicSummary>(`/api/v1/clinic/summary?${params}`);
}

export function fetchClinicVisits(opts: {
  months: number;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  params.set('months', String(opts.months));
  appendScope(params, opts);
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<VisitPage>(`/api/v1/clinic/visits?${params}`);
}

export async function downloadClinicCsv(months: number, scope: GeoScopeQ): Promise<void> {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  const url = `/api/v1/clinic/visits.csv?${params}`;
  await downloadCsv(url, `clinique-${months}m.csv`);
}

// --- Initiations (fiche initiale) -----------------------------------------

export type InitiationSummary = {
  totalAllTime: number;
  inPeriod: number;
  pediatric: number;
  referred: number;
  pediatricPct: number | null;
  yearly: YearBucket[];
  entryPoints: Bucket[];
  regimens: Bucket[];
  whoStages: Bucket[];
  periodMonths: number;
};

export type InitiationRecord = {
  id: number;
  patientId: number;
  patientCode: string | null;
  arvInitDate: string | null;
  enrollmentDate: string | null;
  entryPoint: string | null;
  hivType: string | null;
  arvRegimenInitial: string | null;
  whoStageInitial: string | null;
  cdcStageInitial: string | null;
  weightInitialKg: number | null;
  karnofskyScore: number | null;
  referred: string | null;
  referredOrigin: string | null;
  siteCode: string;
  siteName: string;
};

export type InitiationRecordPage = {
  content: InitiationRecord[];
  total: number;
  page: number;
  size: number;
};

export function fetchInitiationSummary(months: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  return get<InitiationSummary>(`/api/v1/initiations/summary?${params}`);
}

export function fetchInitiationRecords(opts: {
  months: number;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  params.set('months', String(opts.months));
  appendScope(params, opts);
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<InitiationRecordPage>(`/api/v1/initiations/records?${params}`);
}

export async function downloadInitiationCsv(months: number, scope: GeoScopeQ): Promise<void> {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  await downloadCsv(`/api/v1/initiations/records.csv?${params}`, `initiations-${months}m.csv`);
}

// --- Closures (clôtures) ---------------------------------------------------

export type ClosureSummary = {
  totalAllTime: number;
  inPeriod: number;
  deaths: number;
  transfers: number;
  mortalityPct: number | null;
  yearly: YearBucket[];
  types: Bucket[];
  deathCauses: Bucket[];
  periodMonths: number;
};

export type ClosureRecord = {
  id: number;
  patientId: number;
  patientCode: string | null;
  closureDate: string | null;
  closureType: string | null;
  deathDate: string | null;
  actualDeathDate: string | null;
  deathCauseCode: string | null;
  deathCauseText: string | null;
  transferDate: string | null;
  transferDestination: string | null;
  transferReason: string | null;
  voluntaryStopDate: string | null;
  hivNegativeDate: string | null;
  siteCode: string;
  siteName: string;
};

export type ClosureRecordPage = {
  content: ClosureRecord[];
  total: number;
  page: number;
  size: number;
};

export function fetchClosureSummary(months: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  return get<ClosureSummary>(`/api/v1/closures/summary?${params}`);
}

export function fetchClosureRecords(opts: {
  months: number;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  params.set('months', String(opts.months));
  appendScope(params, opts);
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<ClosureRecordPage>(`/api/v1/closures/records?${params}`);
}

export async function downloadClosureCsv(months: number, scope: GeoScopeQ): Promise<void> {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  await downloadCsv(`/api/v1/closures/records.csv?${params}`, `clotures-${months}m.csv`);
}

// --- IVSA (sub-module de Clinique) -----------------------------------------

export type IvsaSummary = {
  totalAllTime: number;
  inPeriod: number;
  successConfirmed: number;
  withAlertSigns: number;
  successPct: number | null;
  msdDistribution: Bucket[];
  periodMonths: number;
};

export type IvsaRow = {
  id: number;
  patientId: number;
  patientCode: string | null;
  visitDate: string | null;
  nextVisitDate: string | null;
  msdCode: string | null;
  successConfirmationDate: string | null;
  alertSignsCount: number | null;
  neuroSignsCount: number | null;
  weightKg: number | null;
  temperatureC: number | null;
  siteCode: string;
  siteName: string;
};

export type IvsaPage = {
  content: IvsaRow[];
  total: number;
  page: number;
  size: number;
};

export function fetchIvsaSummary(months: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  return get<IvsaSummary>(`/api/v1/clinic/ivsa/summary?${params}`);
}

export function fetchIvsaVisits(opts: {
  months: number;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  params.set('months', String(opts.months));
  appendScope(params, opts);
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<IvsaPage>(`/api/v1/clinic/ivsa/visits?${params}`);
}

export async function downloadIvsaCsv(months: number, scope: GeoScopeQ): Promise<void> {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  await downloadCsv(`/api/v1/clinic/ivsa/visits.csv?${params}`, `ivsa-${months}m.csv`);
}

// --- Users (Keycloak admin) ------------------------------------------------

async function send<T>(method: 'POST' | 'PUT' | 'DELETE', path: string, body?: unknown): Promise<T | null> {
  const headers: Record<string, string> = {};
  const token = getAccessToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const r = await fetch(path, {
    method, headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!r.ok) {
    let detail = '';
    try { detail = await r.text(); } catch { /* ignore */ }
    throw new Error(`${r.status} ${r.statusText}${detail ? ` — ${detail}` : ''}`);
  }
  if (r.status === 204) return null;
  const text = await r.text();
  return text ? JSON.parse(text) as T : null;
}

export type UserRow = {
  id: string;
  username: string;
  firstName: string | null;
  lastName: string | null;
  email: string | null;
  enabled: boolean;
  emailVerified: boolean;
  createdAt: number | null;
  regionId: number | null;
  districtId: number | null;
  siteId: number | null;
};

export type UserPage = {
  content: UserRow[];
  total: number;
  page: number;
  size: number;
};

export type UserDetail = UserRow & { realmRoles: string[] };

export type CreateUserRequest = {
  username: string;
  email?: string;
  firstName?: string;
  lastName?: string;
  enabled?: boolean;
  emailVerified?: boolean;
  password?: string;
  passwordTemporary?: boolean;
  realmRoles?: string[];
  regionId?: number | null;
  districtId?: number | null;
  siteId?: number | null;
};

export type UpdateUserRequest = {
  email?: string;
  firstName?: string;
  lastName?: string;
  enabled?: boolean;
  emailVerified?: boolean;
  realmRoles?: string[];
  regionId?: number | null;
  districtId?: number | null;
  siteId?: number | null;
};

export function fetchUsers(opts: { q?: string; page?: number; size?: number }) {
  const params = new URLSearchParams();
  if (opts.q) params.set('q', opts.q);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<UserPage>(`/api/v1/users?${params}`);
}

export function fetchUserRoles() {
  return get<string[]>('/api/v1/users/roles');
}

export function fetchUser(id: string) {
  return get<UserDetail>(`/api/v1/users/${id}`);
}

export function createUser(req: CreateUserRequest) {
  return send<{ id: string }>('POST', '/api/v1/users', req);
}

export function updateUser(id: string, req: UpdateUserRequest) {
  return send<void>('PUT', `/api/v1/users/${id}`, req);
}

export function resetUserPassword(id: string, password: string, temporary: boolean) {
  return send<void>('POST', `/api/v1/users/${id}/password`, { password, temporary });
}

export function setUserEnabled(id: string, enabled: boolean) {
  return send<void>('POST', `/api/v1/users/${id}/${enabled ? 'enable' : 'disable'}`);
}

// --- Synchronisation -------------------------------------------------------

export type SyncSummary = {
  sitesTotal: number;
  sitesOnline: number;
  sitesLate: number;
  sitesOffline: number;
  lastBatchAt: string | null;
  batches24h: number;
  received24h: number;
  accepted24h: number;
  rejected24h: number;
};

export type DailyVolume = {
  day: string;
  batches: number;
  received: number;
  accepted: number;
  rejected: number;
};

export type BatchRow = {
  id: number;
  batchId: string | null;
  entityType: string;
  receivedCount: number;
  accepted: number;
  rejected: number;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  status: string;
  errorSample: string | null; // JSON string [{label, count}, …] or null
  siteCode: string | null;
  siteResolvedCode: string | null;
  siteName: string | null;
};

export type BatchPage = {
  content: BatchRow[];
  total: number;
  page: number;
  size: number;
};

export type LateSiteRow = {
  id: number;
  code: string;
  name: string;
  regionName: string;
  districtName: string;
  lastSyncAt: string | null;
  patientCount: number;
};

export type LateSitePage = {
  content: LateSiteRow[];
  total: number;
  page: number;
  size: number;
};

export type LateBucket = 'late' | 'offline' | 'never' | 'all';

export function fetchSyncSummary(scope: GeoScopeQ) {
  const params = new URLSearchParams();
  appendScope(params, scope);
  return get<SyncSummary>(`/api/v1/sync/summary?${params}`);
}

export function fetchSyncDaily(days: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('days', String(days));
  appendScope(params, scope);
  return get<DailyVolume[]>(`/api/v1/sync/daily?${params}`);
}

export function fetchSyncBatches(opts: {
  regionId?: number;
  districtId?: number;
  siteId?: number;
  entityType?: string;
  status?: string;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  if (opts.regionId)   params.set('regionId',   String(opts.regionId));
  if (opts.districtId) params.set('districtId', String(opts.districtId));
  if (opts.siteId)     params.set('siteId',     String(opts.siteId));
  if (opts.entityType) params.set('entityType', opts.entityType);
  if (opts.status)     params.set('status',     opts.status);
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<BatchPage>(`/api/v1/sync/batches?${params}`);
}

export function fetchSyncLateSites(opts: {
  bucket: LateBucket;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  if (opts.bucket && opts.bucket !== 'all') params.set('bucket', opts.bucket);
  if (opts.regionId)   params.set('regionId',   String(opts.regionId));
  if (opts.districtId) params.set('districtId', String(opts.districtId));
  if (opts.siteId)     params.set('siteId',     String(opts.siteId));
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<LateSitePage>(`/api/v1/sync/late-sites?${params}`);
}

// --- Pharmacy / ARV --------------------------------------------------------

export type RegimenBucket = { label: string; count: number; patients: number };

export type DurationBuckets = {
  d1_7: number;
  d8_30: number;
  d31_90: number;
  d90p: number;
  unknown: number;
  total: number;
};

export type PharmacySummary = {
  dispensationsAllTime: number;
  dispensationsInPeriod: number;
  patientsOnArvInPeriod: number;
  distinctRegimensInPeriod: number;
  shortDispensationPct: number | null;
  monthly: MonthlyCount[];
  regimens: RegimenBucket[];
  durations: DurationBuckets;
  periodMonths: number;
};

export type DispensationRow = {
  id: number;
  visitDate: string | null;
  nextVisitDate: string | null;
  arvRegimen: string | null;
  arvDays: number | null;
  cotrimDays: number | null;
  patientId: number;
  patientCode: string | null;
  siteCode: string;
  siteName: string;
};

export type DispensationPage = {
  content: DispensationRow[];
  total: number;
  page: number;
  size: number;
};

export function fetchPharmacySummary(months: number, scope: GeoScopeQ) {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  return get<PharmacySummary>(`/api/v1/pharmacy/summary?${params}`);
}

export function fetchPharmacyDispensations(opts: {
  months: number;
  regionId?: number;
  districtId?: number;
  siteId?: number;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  params.set('months', String(opts.months));
  appendScope(params, opts);
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<DispensationPage>(`/api/v1/pharmacy/dispensations?${params}`);
}

export async function downloadPharmacyCsv(months: number, scope: GeoScopeQ): Promise<void> {
  const params = new URLSearchParams();
  params.set('months', String(months));
  appendScope(params, scope);
  const url = `/api/v1/pharmacy/dispensations.csv?${params}`;
  await downloadCsv(url, `pharmacie-${months}m.csv`);
}

// --- Sync rejected records -------------------------------------------------

export type RejectBucket = 'open' | 'resolved' | 'all';

export type RejectRow = {
  id: number;
  batchId: number | null;
  entityType: string;
  sourceUuid: string;
  errorCode: string | null;
  errorMessage: string | null;
  rejectedAt: string | null;
  resolvedAt: string | null;
  resolvedBy: string | null;
  resolutionNote: string | null;
  siteCode: string | null;
  siteName: string | null;
};

export type RejectsPage = {
  content: RejectRow[];
  total: number;
  page: number;
  size: number;
};

export type RejectsEntityCount = { entityType: string; count: number };

export function fetchSyncRejects(opts: {
  regionId?: number;
  districtId?: number;
  siteId?: number;
  entityType?: string;
  errorCode?: string;
  bucket?: RejectBucket;
  sort?: SortQ;
  page?: number;
  size?: number;
}) {
  const params = new URLSearchParams();
  if (opts.regionId)   params.set('regionId',   String(opts.regionId));
  if (opts.districtId) params.set('districtId', String(opts.districtId));
  if (opts.siteId)     params.set('siteId',     String(opts.siteId));
  if (opts.entityType) params.set('entityType', opts.entityType);
  if (opts.errorCode)  params.set('errorCode',  opts.errorCode);
  params.set('bucket', opts.bucket ?? 'open');
  appendSort(params, opts.sort ?? null);
  params.set('page', String(opts.page ?? 0));
  params.set('size', String(opts.size ?? 50));
  return get<RejectsPage>(`/api/v1/sync/rejected?${params}`);
}

export function fetchSyncRejectsOpenCounts(scope: GeoScopeQ) {
  const params = new URLSearchParams();
  appendScope(params, scope);
  return get<RejectsEntityCount[]>(`/api/v1/sync/rejected/counts?${params}`);
}

export function resolveSyncReject(id: number, note?: string) {
  return send<void>('POST', `/api/v1/sync/rejected/${id}/resolve`, { note });
}
