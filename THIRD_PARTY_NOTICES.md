# Third-Party Notices

This project redistributes the following third-party components.

## ANGLE (libEGL.dll, libGLESv2.dll)

The Tao Windows backend (`decorated-window-tao`) ships the ANGLE runtime
libraries `libEGL.dll` and `libGLESv2.dll` to provide a Direct3D 11 render
path (OpenGL ES translated to D3D11, with a WARP software fallback for
RDP / VM / driverless environments).

- Project: The ANGLE Project — https://chromium.googlesource.com/angle/angle
- License: BSD 3-Clause
- Copyright 2018 The ANGLE Project Authors. All rights reserved.

The binaries are not committed to this repository; they are fetched at build
time from a pinned [Electron](https://github.com/electron/electron) release
(SHA-256 verified) by `decorated-window-tao/src/main/native/windows/fetch-angle.sh`.
The full BSD 3-Clause license text is reproduced in
`decorated-window-tao/src/main/native/vendor/angle-headers/LICENSE.angle`,
which also covers the vendored Khronos/ANGLE EGL headers used at build time.
