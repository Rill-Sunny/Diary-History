/** Older profiles and backups store olive; render them with the current default. */
export function normalizeAccent(accent: string | null | undefined): 'blue' | 'rose' {
  return accent === 'rose' ? 'rose' : 'blue'
}
