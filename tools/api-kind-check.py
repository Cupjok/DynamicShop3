#!/usr/bin/env python3
"""Binary-compatibility check for one jar across several server API versions.

The plugin is compiled against the oldest supported API (1.21). A call compiled against a class
breaks at runtime (IncompatibleClassChangeError) if that type is an interface in a newer API, and
the other way round. Enum -> interface changes (Sound, Biome, ...) also break valueOf()/values()/
name()/ordinal(). This script lists every org.bukkit / io.papermc / net.kyori type the plugin
references through a method or field ref and reports types whose kind differs between the given
API jars, plus referenced methods/fields that no longer exist in a newer API.

usage: api-kind-check.py <plugin.jar> <api-1.21.jar> <api-newer.jar> [...]
exit code 1 when a problem is found.
"""
import struct
import sys
import zipfile

PREFIXES = ("org/bukkit/", "io/papermc/", "net/kyori/", "com/destroystokyo/paper/")


def parse(data):
    """Returns (access_flags, this_class, super, interfaces, refs, members) of a class file."""
    pos = 10
    count = struct.unpack(">H", data[8:10])[0]
    cp = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            ln = struct.unpack(">H", data[pos + 1:pos + 3])[0]
            cp[i] = ("utf8", data[pos + 3:pos + 3 + ln].decode("utf-8", "replace"))
            pos += 3 + ln
        elif tag in (3, 4):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag == 7:
            cp[i] = ("class", struct.unpack(">H", data[pos + 1:pos + 3])[0])
            pos += 3
        elif tag in (8, 16, 19, 20):
            pos += 3
        elif tag in (9, 10, 11):
            kind = {9: "field", 10: "method", 11: "imethod"}[tag]
            cp[i] = (kind,) + struct.unpack(">HH", data[pos + 1:pos + 5])
            pos += 5
        elif tag == 12:
            cp[i] = ("nat",) + struct.unpack(">HH", data[pos + 1:pos + 5])
            pos += 5
        elif tag in (15,):
            pos += 4
        elif tag in (17, 18):
            pos += 5
        else:
            raise ValueError("bad constant pool tag %d" % tag)
        i += 1

    def utf(idx):
        return cp[idx][1]

    def cls(idx):
        return utf(cp[idx][1])

    flags, this_idx, super_idx = struct.unpack(">HHH", data[pos:pos + 6])
    pos += 6
    icount = struct.unpack(">H", data[pos:pos + 2])[0]
    pos += 2
    interfaces = [cls(struct.unpack(">H", data[pos + 2 * k:pos + 2 * k + 2])[0]) for k in range(icount)]
    pos += 2 * icount

    members = set()
    for _ in range(2):
        mcount = struct.unpack(">H", data[pos:pos + 2])[0]
        pos += 2
        for _ in range(mcount):
            _, n, d, acount = struct.unpack(">HHHH", data[pos:pos + 8])
            pos += 8
            members.add((utf(n), utf(d)))
            for _ in range(acount):
                alen = struct.unpack(">I", data[pos + 2:pos + 6])[0]
                pos += 6 + alen

    refs = []
    for e in cp:
        if e and e[0] in ("field", "method", "imethod"):
            owner = cls(e[1])
            nat = cp[e[2]]
            refs.append((e[0], owner, utf(nat[1]), utf(nat[2])))
    return flags, cls(this_idx), (cls(super_idx) if super_idx else None), interfaces, refs, members


def load_api(path):
    types = {}
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.endswith(".class") and not n.endswith("module-info.class"):
                flags, name, sup, itf, _, members = parse(z.read(n))
                types[name] = (flags, sup, itf, members)
    return types


def kind(flags):
    if flags & 0x2000:
        return "annotation"
    if flags & 0x0200:
        return "interface"
    if flags & 0x4000:
        return "enum"
    return "class"


def has_member(types, owner, name, desc, seen=None):
    """Looks the member up in the type, its supertypes and interfaces (like JVM resolution)."""
    seen = seen or set()
    if owner in seen:
        return None
    seen.add(owner)
    t = types.get(owner)
    if t is None:
        return None  # not an API type (JDK or library): unknown, skip
    if (name, desc) in t[3]:
        return True
    unknown = False
    for parent in ([t[1]] if t[1] else []) + t[2]:
        r = has_member(types, parent, name, desc, seen)
        if r:
            return True
        if r is None:
            unknown = True  # e.g. java/lang/Enum or java/lang/Object: cannot tell, do not report
    if kind(t[0]) == "interface" and name in ("toString", "equals", "hashCode", "getClass"):
        return None  # Object methods are always callable through an interface ref
    return None if unknown else False


def main():
    plugin, apis = sys.argv[1], sys.argv[2:]
    refs = set()
    with zipfile.ZipFile(plugin) as z:
        for n in z.namelist():
            if n.endswith(".class") and n.startswith("me/sat7/dynamicshop/") and "/lib/" not in n:
                for r in parse(z.read(n))[4]:
                    if r[1].startswith(PREFIXES):
                        refs.add(r)

    loaded = [(a, load_api(a)) for a in apis]
    base_name, base = loaded[0]
    problems = 0

    owners = sorted({r[1] for r in refs})
    # Only method calls encode class-vs-interface (Methodref vs InterfaceMethodref). Field reads and the type
    # used in descriptors keep linking after an enum -> interface change, so those owners are not reported.
    called = {r[1] for r in refs if r[0] in ("method", "imethod")}
    for o in owners:
        kinds = [(a, kind(t[o][0]) if o in t else "missing") for a, t in loaded]
        if o in called and len({k for _, k in kinds}) > 1:
            problems += 1
            print("KIND  %s: %s" % (o, ", ".join("%s=%s" % (a.rsplit("/", 1)[-1], k) for a, k in kinds)))

    for ref in sorted(refs):
        _, owner, name, desc = ref
        for a, t in loaded[1:]:
            if has_member(t, owner, name, desc) is False:
                problems += 1
                print("GONE  %s.%s%s missing in %s" % (owner, name, desc, a.rsplit("/", 1)[-1]))

    print("%d references to %d API types checked, %d problem(s)" % (len(refs), len(owners), problems))
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
