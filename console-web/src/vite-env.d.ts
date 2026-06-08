/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** URL de l'instance Superset (« Analyses avancées »). Vide → menu masqué. */
  readonly VITE_SUPERSET_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

/** Métadonnées de build (injectées par vite.config `define`). */
declare const __APP_VERSION__: string;
declare const __APP_COMMIT__: string;
declare const __APP_BUILD_DATE__: string;
