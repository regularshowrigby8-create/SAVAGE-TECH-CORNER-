/**
 * Companion PWA Client Bridge Interfaces (TypeScript Strict Verification Target)
 */

export interface BrowserTab {
  id: string;
  url: string;
  title: string;
  loading: boolean;
  canGoBack: boolean;
  canGoForward: boolean;
}

export interface SecurityPolicy {
  httpsOnly: boolean;
  allowedHosts: readonly string[];
  blockCleartext: boolean;
}

export const DEFAULT_SECURITY_POLICY: SecurityPolicy = {
  httpsOnly: true,
  allowedHosts: ["savage-tech-corner.io", "app.savage-tech-corner.io"],
  blockCleartext: true,
};

export function validateNavigationUrl(url: string, policy: SecurityPolicy): boolean {
  try {
    const parsed = new URL(url);
    if (policy.httpsOnly && parsed.protocol !== "https:") {
      return false;
    }
    return policy.allowedHosts.includes(parsed.hostname.toLowerCase());
  } catch {
    return false;
  }
}
