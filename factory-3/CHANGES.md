# Factory 3 Subsystem Changes

## Components Updated
- `tools/apk_builder.py`: Implemented multi-scheme preservation to keep Android v2/v3 Signing Blocks (`APK Sig Block 42`) alongside v1 (PKCS#7) signatures.
- `tools/android_vm_verifier.py`: Added 4-byte and 16KB boundary alignment verification, UTF-16LE AXML parsing, and Dalvik bytecode header integrity checks.
- `tools/browser_app_rules.py`: Hardened invariant checks enforcing `com.savage.soulbrowser` package identity, cleartext blocking, and zero foreign tokens.
