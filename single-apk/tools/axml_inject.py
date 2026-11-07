#!/usr/bin/env python3
"""Inject an extra <activity> (MAIN/LAUNCHER) into a compiled AndroidManifest.xml.

The engine APK ships a binary (AXML) manifest.  Rebuilding it with apktool/aapt2
is risky (the manifest references dozens of app resources), so this tool edits the
binary chunks in place:

  * appends the new strings (activity class, MAIN action, LAUNCHER category) to the
    existing string pool, keeping every existing index stable (the resource map of
    the manifest indexes attribute-name strings by position);
  * inserts the <activity><intent-filter>...</intent-filter></activity> chunks as
    the first child of <application>;
  * fixes the outer XML chunk size.

Everything else (resource map, namespaces, the rest of the manifest) is copied
byte for byte.

Usage:
    python axml_inject.py --in AndroidManifest.xml --out patched.xml \
        --activity com.example.ProbeActivity
    python axml_inject.py --in AndroidManifest.xml --inspect
"""

import argparse
import struct
import sys

RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103

UTF8_FLAG = 0x100
SORTED_FLAG = 0x1

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_BOOL = 0x12
NO_ENTRY = 0xFFFFFFFF

ANDROID_NS = "http://schemas.android.com/apk/res/android"


def rd16(buf, off):
    return struct.unpack_from("<H", buf, off)[0]


def rd32(buf, off):
    return struct.unpack_from("<I", buf, off)[0]


class StringPool:
    def __init__(self, data):
        self.raw = data
        if rd16(data, 0) != RES_STRING_POOL_TYPE:
            raise ValueError("not a string pool chunk")
        self.header_size = rd16(data, 2)
        self.size = rd32(data, 4)
        self.count = rd32(data, 8)
        self.style_count = rd32(data, 12)
        self.flags = rd32(data, 16)
        self.strings_start = rd32(data, 20)
        self.styles_start = rd32(data, 24)
        self.utf8 = bool(self.flags & UTF8_FLAG)
        self.offsets = [rd32(data, self.header_size + 4 * i) for i in range(self.count)]
        self.strings = [self._read(s) for s in self.offsets]

    def _read(self, rel):
        p = self.strings_start + rel
        data = self.raw
        if self.utf8:
            n16, p = self._len8(p)
            n8, p = self._len8(p)
            return data[p:p + n8].decode("utf-8", "replace")
        n, p = self._len16(p)
        return data[p:p + 2 * n].decode("utf-16-le", "replace")

    def _len8(self, p):
        b = self.raw[p]
        if b & 0x80:
            return ((b & 0x7F) << 8) | self.raw[p + 1], p + 2
        return b, p + 1

    def _len16(self, p):
        v = rd16(self.raw, p)
        if v & 0x8000:
            return ((v & 0x7FFF) << 16) | rd16(self.raw, p + 2), p + 4
        return v, p + 2

    def index_of(self, s):
        try:
            return self.strings.index(s)
        except ValueError:
            return -1

    def add(self, s):
        idx = self.index_of(s)
        if idx >= 0:
            return idx
        self.strings.append(s)
        self.count += 1
        return self.count - 1

    def build(self, force_unsorted=True):
        """Re-encode the pool.  String indices are preserved (append only).

        When new strings are appended the SORTED flag must be dropped: the flag
        tells the platform the pool is sorted so it may binary-search it.
        """
        if self.style_count:
            raise NotImplementedError("styled string pools are not supported")
        flags = self.flags
        if force_unsorted and (flags & SORTED_FLAG):
            flags &= ~SORTED_FLAG

        offsets = []
        blob = bytearray()
        for s in self.strings:
            offsets.append(len(blob))
            if self.utf8:
                enc = s.encode("utf-8")
                n16 = len(s.encode("utf-16-le")) // 2
                blob += _enc_len8(n16) + _enc_len8(len(enc)) + enc + b"\x00"
            else:
                enc = s.encode("utf-16-le")
                blob += _enc_len16(len(enc) // 2) + enc + b"\x00\x00"
                while len(blob) % 4:
                    blob += b"\x00"

        header_size = 28
        strings_start = header_size + 4 * self.count
        offsets_blob = b"".join(struct.pack("<I", o) for o in offsets)
        size = strings_start + len(blob)
        chunk = struct.pack("<HHIIIIII", RES_STRING_POOL_TYPE, header_size, size,
                            self.count, 0, flags, strings_start, 0) + offsets_blob + bytes(blob)
        assert len(chunk) == size
        return chunk


def _enc_len8(n):
    if n > 0x7FFF:
        raise ValueError("string too long for utf8 pool")
    if n > 0x7F:
        return bytes([((n >> 8) | 0x80) & 0xFF, n & 0xFF])
    return bytes([n])


def _enc_len16(n):
    if n > 0x7FFF:
        return struct.pack("<HH", (n >> 16) | 0x8000, n & 0xFFFF)
    return struct.pack("<H", n)


def iter_chunks(data):
    pos = 8
    out = []
    while pos + 8 <= len(data):
        ctype = rd16(data, pos)
        header_size = rd16(data, pos + 2)
        size = rd32(data, pos + 4)
        if size < 8 or pos + size > len(data):
            raise ValueError("bad chunk at %d: type=0x%04x size=%d" % (pos, ctype, size))
        out.append((pos, ctype, header_size, size))
        pos += size
    return out


def start_element(name_idx, attrs, line=0):
    """attrs: list of (ns_idx, name_idx, raw_idx, data_type, data)."""
    body = struct.pack("<IIHHHHHH", NO_ENTRY, name_idx, 20, 20, len(attrs), 0, 0, 0)
    for ns, name, raw, dtype, data in attrs:
        body += struct.pack("<IIIHBBI", ns, name, raw, 8, 0, dtype, data)
    size = 16 + len(body)
    return struct.pack("<HHIII", RES_XML_START_ELEMENT_TYPE, 16, size, line, NO_ENTRY) + body


def end_element(name_idx, line=0):
    return struct.pack("<HHIIIII", RES_XML_END_ELEMENT_TYPE, 16, 24, line, NO_ENTRY,
                       NO_ENTRY, name_idx)


def inject(data, activity_class, label=None, theme_res_id=None):
    if rd16(data, 0) != RES_XML_TYPE:
        raise ValueError("not a binary XML file")
    header_size = rd16(data, 2)
    file_size = rd32(data, 4)
    if file_size != len(data):
        raise ValueError("file size mismatch: header=%d actual=%d" % (file_size, len(data)))

    chunks = iter_chunks(data)
    if not chunks or chunks[0][1] != RES_STRING_POOL_TYPE:
        raise ValueError("first chunk is not a string pool")
    pool_pos, _, pool_hdr, pool_size = chunks[0]
    pool = StringPool(data[pool_pos:pool_pos + pool_size])

    # The application element must exist and be a direct child of <manifest>.
    app_idx = None
    for i, (pos, ctype, hdr, size) in enumerate(chunks):
        if ctype != RES_XML_START_ELEMENT_TYPE:
            continue
        name_idx = rd32(data, pos + 20)
        if pool.strings[name_idx] == "application":
            app_idx = i
            break
    if app_idx is None:
        raise ValueError("<application> not found")

    ns_idx = pool.index_of(ANDROID_NS)
    if ns_idx < 0:
        raise ValueError("android namespace uri missing from the string pool")
    name_attr = pool.index_of("name")
    exported_attr = pool.index_of("exported")
    if name_attr < 0 or exported_attr < 0:
        raise ValueError("android:name/android:exported attribute names missing from the pool")
    for element in ("activity", "intent-filter", "action", "category"):
        if pool.index_of(element) < 0:
            raise ValueError("element name %r missing from the pool" % element)

    act_name = pool.add(activity_class)
    main_action = pool.add("android.intent.action.MAIN")
    launcher_cat = pool.add("android.intent.category.LAUNCHER")

    attrs = [
        (ns_idx, name_attr, act_name, TYPE_STRING, act_name),
        (ns_idx, exported_attr, NO_ENTRY, TYPE_BOOL, 0xFFFFFFFF),
    ]
    if label is not None:
        label_attr = pool.index_of("label")
        if label_attr >= 0:
            label_value = pool.add(label)
            attrs.append((ns_idx, label_attr, label_value, TYPE_STRING, label_value))
    if theme_res_id is not None:
        # Framework style reference (e.g. @android:style/Theme.Material.NoActionBar).
        # "theme" is already in the engine's manifest pool (resource map -> 0x01010000).
        theme_attr = pool.index_of("theme")
        if theme_attr < 0:
            raise ValueError("android:theme attribute name missing from the pool")
        attrs.append((ns_idx, theme_attr, NO_ENTRY, TYPE_REFERENCE, theme_res_id))

    insert = b"".join([
        start_element(pool.index_of("activity"), attrs),
        start_element(pool.index_of("intent-filter"), []),
        start_element(pool.index_of("action"), [(ns_idx, name_attr, main_action, TYPE_STRING, main_action)]),
        end_element(pool.index_of("action")),
        start_element(pool.index_of("category"), [(ns_idx, name_attr, launcher_cat, TYPE_STRING, launcher_cat)]),
        end_element(pool.index_of("category")),
        end_element(pool.index_of("intent-filter")),
        end_element(pool.index_of("activity")),
    ])

    pool_end = pool_pos + pool_size
    app_end = chunks[app_idx][0] + chunks[app_idx][3]
    pool_chunk = pool.build()
    out = data[:pool_pos] + pool_chunk + data[pool_end:app_end] + insert + data[app_end:]
    out = out[:4] + struct.pack("<I", len(out)) + out[8:]
    return out, pool


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="inp", required=True)
    ap.add_argument("--out", dest="out")
    ap.add_argument("--activity", default="com.anderson.applemusic.probe.ProbeActivity")
    ap.add_argument("--label", default=None)
    ap.add_argument("--theme", default=None,
                    help="framework style resource id for android:theme (e.g. 0x0103022e)")
    ap.add_argument("--inspect", action="store_true")
    args = ap.parse_args()

    data = open(args.inp, "rb").read()
    if args.inspect:
        chunks = iter_chunks(data)
        pool = StringPool(data[8:8 + chunks[0][3]])
        print("chunks=%d utf8=%s sorted=%s strings=%d styles=%d" % (
            len(chunks), pool.utf8, bool(pool.flags & SORTED_FLAG), pool.count, pool.style_count))
        acts = 0
        for pos, ctype, hdr, size in chunks:
            if ctype == RES_XML_START_ELEMENT_TYPE:
                n = pool.strings[rd32(data, pos + 20)]
                if n == "activity":
                    acts += 1
                    if acts <= 3:
                        print("activity", pool.strings[rd32(data, pos + 24 + 4)])
        print("activities=%d" % acts)
        return 0

    if not args.out:
        ap.error("--out is required unless --inspect is used")
    theme = int(args.theme, 0) if args.theme else None
    out, pool = inject(data, args.activity, args.label, theme)
    open(args.out, "wb").write(out)
    print("wrote %s (%d bytes, pool strings %d)" % (args.out, len(out), pool.count))
    return 0


if __name__ == "__main__":
    sys.exit(main())
