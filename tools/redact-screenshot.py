"""Find PII in the live UI tree and box it out of a screenshot.

Usage:  python3 tools/redact-screenshot.py raw.png out.png [box=x,y,w,h] [term ...]

`docs/screenshots/raw/` is gitignored and holds the unredacted
originals; only the boxed output belongs in `docs/`. This exists
because doing it by eye failed: a set of captures went into the repo
carrying a third party's first name next to their amateur callsign,
which the FCC licence database resolves to a legal name and a mailing
address, plus repeater positions to five decimal places and a
"distance away" that trilaterates the phone that took the shot.

Boxes come from uiautomator bounds, not from eyeballing pixels, so a
row that scrolls or a name that changes length still gets covered.
"""
import re, subprocess, sys

PATTERNS = [
    # Node public keys / key prefixes.
    (re.compile(r'^[0-9a-f]{8,}$'), "key"),
    (re.compile(r'\b[0-9a-f]{12,}\b'), "key"),
    # Latitude, longitude at any precision that is a point, not a region.
    (re.compile(r'-?\d{1,3}\.\d{3,}\s*,\s*-?\d{1,3}\.\d{3,}'), "coords"),
    # US amateur callsigns -> FCC ULS gives legal name + mailing address.
    (re.compile(r'\b[KNW][0-9][A-Z]{2,3}\b'), "callsign"),
    (re.compile(r'\b[KNW][A-Z][0-9][A-Z]{2,3}\b'), "callsign"),
    # Trilaterates the phone when paired with a node position.
    (re.compile(r'^\d+(\.\d+)?\s*(m|km)$'), "distance"),
]

def nodes():
    subprocess.run(["adb","shell","uiautomator","dump","/sdcard/ui.xml"],
                   capture_output=True, timeout=40)
    xml = subprocess.run(["adb","shell","cat","/sdcard/ui.xml"],
                         capture_output=True, timeout=40).stdout.decode("utf-8","replace")
    for m in re.finditer(r'<node[^>]*>', xml):
        n = m.group(0)
        t = re.search(r'text="([^"]*)"', n)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if t and b and t.group(1):
            yield t.group(1), tuple(map(int, b.groups()))

def boxes(extra_terms=()):
    out = []
    for text, (x1,y1,x2,y2) in nodes():
        why = None
        for pat, label in PATTERNS:
            if pat.search(text):
                why = label; break
        if not why:
            for term in extra_terms:
                if term and term.lower() in text.lower():
                    why = "manual"; break
        if why:
            out.append((x1,y1,x2,y2,why,text))
    return out

if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    # A modal sheet hides the content behind it from the accessibility
    # tree, so anything it occludes has to be named explicitly:
    #   box=x,y,w,h
    literal = [a[4:] for a in sys.argv[3:] if a.startswith("box=")]
    extra   = [a for a in sys.argv[3:] if not a.startswith("box=")]
    found = boxes(extra)
    for spec in literal:
        x, y, w, h = (int(v) for v in spec.split(","))
        found.append((x, y, x + w, y + h, "occluded", spec))
    if not found:
        subprocess.run(["cp", src, dst], check=True)
        print("no PII found"); sys.exit(0)
    filt = ",".join(
        f"drawbox=x={x1-4}:y={y1-2}:w={x2-x1+8}:h={y2-y1+4}:color=0x2A2A2A@1:t=fill"
        for (x1,y1,x2,y2,_,_) in found)
    subprocess.run(["ffmpeg","-v","error","-i",src,"-vf",filt,"-y",dst], check=True)
    for (_,_,_,_,why,text) in found:
        print(f"  redacted [{why}] {text[:60]}")
