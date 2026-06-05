/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** URL de l'instance Superset (« Analyses avancées »). Vide → menu masqué. */
  readonly VITE_SUPERSET_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
