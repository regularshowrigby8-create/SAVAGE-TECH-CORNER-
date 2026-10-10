# Security Policy & Disclosure Guidelines

## 1. Security Architecture & Invariants

Savage Browser and Savage Tech Corner enforce strict defense-in-depth principles:

- **WebView TLS Policy**: Fail-closed enforcement on all SSL/TLS handshakes (`handler.cancel()`). No certificates or warnings may be ignored.
- **Scheme Filtering**: Explicit blocking of insecure cleartext HTTP schemes across WebViews and network clients.
- **Privileged Bridge Isolation**: Any `@JavascriptInterface` exposed to WebViews must enforce domain-origin validation against `savage-tech-corner.io` and perform strict input sanitization.
- **Anti-Fabrication & Credential Invariants**: All commits and pipelines are continuously audited with Shannon Entropy secret detection ($H > 3.5$) and regex token scanning for AWS, Slack, GitHub, and private keys.

---

## 2. Reporting a Vulnerability

If you discover a potential security vulnerability in Savage Browser or the Factory pipeline, please report it responsibly:

1. **Email**: Send detailed vulnerability reports to `regularshowrigby8@gmail.com`.
2. **Details to Include**:
   - Component affected (`shell`, `frontend`, `backend`, `factories`, `pipeline`).
   - Steps to reproduce or proof-of-concept payload.
   - Impact assessment (e.g., unauthorized origin bridge access, token disclosure).
3. **Response Timeline**:
   - Initial acknowledgment within 24 hours.
   - Status update with remediation plan within 72 hours.
   - Coordinated public disclosure after release verification.
