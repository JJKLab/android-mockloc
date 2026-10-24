"""Dump method signatures from a .class file. No JDK needed, just struct parsing."""

import struct
import sys


def parse(data):
    pos = 10  # skip magic/minor/major + count
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            n = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            pool[i] = data[pos:pos + n].decode("utf-8", "replace")
            pos += n
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = ("ref", struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[i] = ("pair", struct.unpack_from(">HH", data, pos))
            pos += 4
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"unknown constant tag {tag} at {pos}")
        i += 1

    def utf(idx):
        v = pool[idx]
        return v if isinstance(v, str) else None

    pos += 6  # access_flags, this_class, super_class
    ifc_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2 + ifc_count * 2
    fields_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(fields_count):
        pos += 6
        attrs = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        for _ in range(attrs):
            # attribute_info = u2 name_index + u4 length, then `length` bytes of info
            length = struct.unpack_from(">I", data, pos + 2)[0]
            pos += 6 + length
    methods_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    out = []
    for _ in range(methods_count):
        flags, name_i, desc_i = struct.unpack_from(">HHH", data, pos)
        pos += 6
        out.append((flags, utf(name_i), utf(desc_i)))
        attrs = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        for _ in range(attrs):
            length = struct.unpack_from(">I", data, pos + 2)[0]
            pos += 6 + length
    return out


def pretty(desc):
    if desc is None:
        return "?"
    return desc


def main(path, filter_text):
    with open(path, "rb") as fh:
        data = fh.read()
    for flags, name, desc in parse(data):
        if filter_text and filter_text.lower() not in (name or "").lower():
            continue
        static = "static " if flags & 0x0008 else ""
        pub = "public " if flags & 0x0001 else ("protected " if flags & 0x0004 else "")
        print(f"  {pub}{static}{name}{desc}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "")
