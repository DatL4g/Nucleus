/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

/**
 * The passwordless update helper installed next to a signed Linux app.
 *
 * It is invoked through `pkexec` (scoped by a polkit policy to this exact path) and:
 * 1. verifies the downloaded package's detached `<pkg>.asc` signature against the public key
 *    bundled in the app (`resources/nucleus-update.pub.asc`), in a throwaway keyring;
 * 2. ensures the package upgrades THIS app only — the new package name must match the package
 *    that owns the helper file — so `allow_active=yes` cannot install arbitrary packages;
 * 3. installs via `dpkg -i` / `rpm -U`.
 *
 * The script is fully self-contained (resolves its own location via `readlink`) and uses no
 * electron-builder macros, so it can be embedded verbatim in the afterInstall heredoc and exercised
 * directly in tests. Exit codes: 2 usage, 3 package mismatch, 4 missing key/signature, gpg's own
 * non-zero on a failed signature.
 */
internal object LinuxUpdateHelper {
    val SCRIPT: String =
        $$"""
        #!/usr/bin/env bash
        # Installed by Nucleus. Verifies a signed update against the bundled public key and,
        # if valid and for this same app, installs it. Invoked via pkexec (see polkit policy).
        set -eu
        if [ "$#" -lt 1 ]; then echo "usage: nucleus-update-helper <package>" >&2; exit 2; fi
        PKG="$1"
        [ -f "$PKG" ] || { echo "package not found: $PKG" >&2; exit 2; }
        SELF="$(readlink -f "$0")"
        APPDIR="$(dirname "$SELF")"
        PUBKEY="$APPDIR/resources/nucleus-update.pub.asc"
        SIG="$PKG.asc"
        [ -f "$PUBKEY" ] || { echo "missing public key: $PUBKEY" >&2; exit 4; }
        [ -f "$SIG" ]    || { echo "missing signature: $SIG" >&2; exit 4; }

        # Verify the detached signature against the bundled key in a throwaway keyring.
        KR="$(mktemp -d)"; trap 'rm -rf "$KR"' EXIT; chmod 700 "$KR"
        gpg --homedir "$KR" --batch --quiet --import "$PUBKEY"
        gpg --homedir "$KR" --batch --verify "$SIG" "$PKG"

        # Only upgrade THIS app: the new package's name must match the package that owns the helper.
        case "$PKG" in
          *.deb)
            OWNER="$(dpkg -S "$SELF" 2>/dev/null | cut -d: -f1 | head -n1)"
            NEW="$(dpkg-deb -f "$PKG" Package)"
            [ -n "$OWNER" ] && [ "$NEW" = "$OWNER" ] || { echo "package mismatch: $NEW != $OWNER" >&2; exit 3; }
            exec dpkg -i "$PKG"
            ;;
          *.rpm)
            OWNER="$(rpm -qf --qf '%{NAME}' "$SELF" 2>/dev/null)"
            NEW="$(rpm -qp --qf '%{NAME}' "$PKG")"
            [ -n "$OWNER" ] && [ "$NEW" = "$OWNER" ] || { echo "package mismatch: $NEW != $OWNER" >&2; exit 3; }
            exec rpm -U "$PKG"
            ;;
          *) echo "unsupported package: $PKG" >&2; exit 2 ;;
        esac
        """.trimIndent()
}
