# Local runtime verification status

The portable toolchain builds the application, but this Windows machine did not provide a usable Android runtime during the initial implementation session.

Observed environment:

- `adb devices -l` reports no attached physical phone.
- `emulator-check.exe accel` reports code 6: Android Emulator hypervisor driver is not installed. Windows reports no active hypervisor.
- Firmware virtualization is available, but no Windows virtualization or security settings were changed.

Attempts made without changing Windows settings:

1. Official current emulator 37.2.13, API 36 x86_64, software CPU/GPU rendering, headless.
2. API 28 x86 software emulation, reduced resolution and memory.
3. Whitespace-free SDK and AVD paths using a temporary `SUBST` drive mapping.
4. Official archived emulator 35.4.9, SHA-256 verified against the Android Developers archive, with both x86/API 28 and x86_64/API 36 images.
5. ARM64/API 26 fallback; Windows QEMU2 explicitly reported that ARM64 is unsupported.

The x86 software attempts failed before Android boot. An emulator 35.4.9 crash dump identified exception `0xC0000005` (null read) inside `qemu-system-x86_64-headless.exe`. No application instrumentation or device screenshot results can be claimed from these attempts. The temporary drive mapping and launched emulator processes were cleaned up.

To complete runtime verification, attach and authorize an Android phone, or use an Android emulator on a machine with working hardware acceleration. Then run:

```powershell
.\tools\build.ps1 -Tasks @('connectedDebugAndroidTest')
```

This environment limitation does not establish whether the AniDB website accepts or rejects the embedded workflow. That live, signed-in integration proof remains a separate verification step.
