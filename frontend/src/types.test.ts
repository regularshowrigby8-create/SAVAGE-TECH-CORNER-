/**
 * Navigation Policy Suite (Vitest Client Verification)
 */

import { describe, it, expect } from "vitest";
import { validateNavigationUrl, DEFAULT_SECURITY_POLICY } from "./types.js";

describe("Companion PWA Navigation Invariants", () => {
  it("permits allowed HTTPS hosts", () => {
    expect(
      validateNavigationUrl("https://savage-tech-corner.io/home", DEFAULT_SECURITY_POLICY),
    ).toBe(true);
    expect(
      validateNavigationUrl("https://app.savage-tech-corner.io/dashboard", DEFAULT_SECURITY_POLICY),
    ).toBe(true);
  });

  it("rejects unauthorized hosts", () => {
    expect(validateNavigationUrl("https://evil.com/phishing", DEFAULT_SECURITY_POLICY)).toBe(false);
    expect(
      validateNavigationUrl("https://sub.savage-tech-corner.io", DEFAULT_SECURITY_POLICY),
    ).toBe(false);
  });

  it("rejects cleartext HTTP and custom protocols", () => {
    expect(validateNavigationUrl("http://savage-tech-corner.io", DEFAULT_SECURITY_POLICY)).toBe(
      false,
    );
    expect(validateNavigationUrl("javascript:alert(1)", DEFAULT_SECURITY_POLICY)).toBe(false);
    expect(validateNavigationUrl("file:///etc/passwd", DEFAULT_SECURITY_POLICY)).toBe(false);
  });
});
