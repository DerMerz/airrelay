# Additional permission under GNU GPL version 3 section 7

AirRelay is licensed under the GNU General Public License version 3 or (at your option) any
later version (see [`LICENSE`](LICENSE)). For the parts written by Kevin Merz, the following
additional permission applies.

> Copyright (C) 2026 Kevin Merz
>
> Additional permission under GNU GPL version 3 section 7
>
> If you modify this Program, or any covered work, by linking or combining it with the
> Amazon Appstore SDK (com.amazon.device:amazon-appstore-sdk, Amazon In-App Purchasing), or a
> modified version of that library, containing parts covered by the terms of the Amazon
> Program Materials License Agreement, the licensors of this Program grant you additional
> permission to convey the resulting work.

The SPDX form used in the file headers is
`GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception`.

## Which files it covers

A copyright holder can only give this permission for code they hold the rights to. It covers:

- all Kotlin sources under `receiver/src/` (main, debug and test),
- `receiver/src/main/cpp/crypto/` (AirRelay's own cryptography helpers, written in 2026 to
  replace UxPlay's `lib/crypto.c`, which is GPL-3.0 only and no longer compiled),
- `receiver/src/main/cpp/playfair/playfair_pipe.*` and `playfair_bridge.c` (the process call
  to the playfair helper),
- the build scripts, tools, tests and the changes in `patches/UxPlay/0007` and `0008`.

Each covered file says so in its header. It does **not** cover code by other authors, which
keeps its own license (see [`THIRD-PARTY.md`](THIRD-PARTY.md)):

- UxPlay `lib/` (LGPL-2.1-or-later, llhttp MIT): may be combined with proprietary code under
  the LGPL anyway.
- playfair (`lib/playfair/`, GPL-3.0): not linked into the app. It runs as a separate
  program, `libairrelay_playfair.so` (an executable in the app's native library directory),
  that receives 236 bytes on standard input and returns 16 bytes on standard output, once
  per mirroring session. `playfair/playfair_helper.c` (the `main` of that program) is
  GPL-3.0-or-later without this permission, because the program contains playfair.
- The files based on [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)
  (GPL-3.0): `native_bridge.cpp`, `android_raop_callbacks.*`, `android_dnssd_shim.*`,
  `audio_*.h`, `audio_engine.*`, `timeline_buffer.h`, `log_sink.h` and the patches 0001 to
  0006. Kevin Merz's own changes in these files are covered; the upstream parts are not,
  unless their authors grant the same permission.

This file is not legal advice and states the intent of the copyright holder.
