#!/usr/bin/env python3
"""Pack the patched manifest + an extra dex into the engine APK, byte-preserving the rest.

Only three things change versus the engine APK:
  * AndroidManifest.xml is replaced by the patched binary manifest;
  * a new classesN.dex (N = next free index) is appended after the last classes*.dex;
  * the UI assets (Inter TTFs + OFL) are added under assets/one/.

Every other entry keeps its original order, compression method and metadata.  Alignment
and signing are done afterwards by zipalign/apksigner (see build-one-apk.ps1).

Usage:
    python pack_single_apk.py --engine engine.apk --dex classes.dex \
        --manifest patched-manifest.bin --out packed-unsigned.apk
"""

import argparse
import os
import re
import sys
import zipfile


def next_dex_name(names):
    highest = 0
    for name in names:
        m = re.match(r"^classes(\d*)\.dex$", name)
        if not m:
            continue
        suffix = m.group(1)
        highest = max(highest, 1 if suffix == "" else int(suffix))
    return "classes%d.dex" % (highest + 1)


def collect_assets(assets_dir):
    """Returns [(apk_entry_name, bytes)] for every file under assets_dir as assets/one/."""
    entries = []
    if not assets_dir or not os.path.isdir(assets_dir):
        return entries
    for root, _dirs, files in os.walk(assets_dir):
        for name in sorted(files):
            full = os.path.join(root, name)
            rel = os.path.relpath(full, assets_dir).replace(os.sep, "/")
            with open(full, "rb") as handle:
                entries.append(("assets/one/" + rel, handle.read()))
    entries.sort(key=lambda item: item[0])
    return entries


def default_assets_dir():
    here = os.path.dirname(os.path.abspath(__file__))
    candidate = os.path.join(here, "..", "single-ui", "assets")
    return os.path.normpath(candidate)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", required=True)
    ap.add_argument("--dex", required=True)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--out", required=True)
    # UI assets are optional so older invocations (and the CI bash path) keep working;
    # the default finds single-apk/single-ui/assets in the checkout.
    ap.add_argument("--assets", default=default_assets_dir(),
                    help="directory copied into the APK as assets/one/...")
    args = ap.parse_args()

    dex_bytes = open(args.dex, "rb").read()
    manifest_bytes = open(args.manifest, "rb").read()
    assets = collect_assets(args.assets)

    src = zipfile.ZipFile(args.engine)
    infos = src.infolist()
    names = [i.filename for i in infos]
    dex_name = next_dex_name(names)
    last_dex = max(i for i, n in enumerate(names) if re.match(r"^classes(\d*)\.dex$", n))

    conflicts = [entry for entry, _data in assets if entry in names]
    if conflicts:
        raise SystemExit("FAILED: asset entries already exist: %s" % conflicts[:5])

    replaced_manifest = False
    inserted_dex = False
    with zipfile.ZipFile(args.out, "w", allowZip64=True) as dst:
        for idx, info in enumerate(infos):
            data = src.read(info.filename) if info.filename != "AndroidManifest.xml" else manifest_bytes
            if info.filename == "AndroidManifest.xml":
                replaced_manifest = True
            out_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out_info.compress_type = info.compress_type
            out_info.external_attr = info.external_attr
            out_info.create_system = info.create_system
            dst.writestr(out_info, data)
            if idx == last_dex:
                dex_info = zipfile.ZipInfo(dex_name, date_time=info.date_time)
                dex_info.compress_type = zipfile.ZIP_DEFLATED
                dst.writestr(dex_info, dex_bytes)
                inserted_dex = True
                for entry, payload in assets:
                    asset_info = zipfile.ZipInfo(entry, date_time=info.date_time)
                    asset_info.compress_type = zipfile.ZIP_STORED
                    dst.writestr(asset_info, payload)
        if not replaced_manifest or not inserted_dex:
            raise SystemExit("FAILED: manifest=%s dex=%s" % (replaced_manifest, inserted_dex))

    print("packed %s: manifest=%d B, %s=%d B, assets=%d file(s) from %s"
          % (args.out, len(manifest_bytes), dex_name, len(dex_bytes), len(assets),
             args.assets))
    return 0


if __name__ == "__main__":
    sys.exit(main())
