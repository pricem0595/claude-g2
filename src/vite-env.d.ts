/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BRIDGE_URL?: string
  /** Dev only: a token to use when none is saved, e.g. the desktop bridge's ABCDE. */
  readonly VITE_BRIDGE_TOKEN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
