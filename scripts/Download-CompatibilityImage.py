"""Download the official, checksum-verified AOSP API 26 x86 compatibility image.

Uses only Python's standard library; temporary files stay in ignored test-artifacts.
The x86 image also exercises the app's 32-bit native-library packaging.
"""
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path
import time
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET
from zipfile import ZipFile

base = Path(__file__).resolve().parent.parent / "test-artifacts" / "api26-aosp"
base.mkdir(parents=True, exist_ok=True)
repository = "https://dl.google.com/android/repository/sys-img/android/"
with urlopen(repository + "sys-img2-3.xml", timeout=45) as response:
    metadata = ET.fromstring(response.read())
package = next(p for p in metadata.findall("remotePackage") if p.attrib.get("path") == "system-images;android-26;default;x86")
complete = package.find("archives/archive/complete")
size = int(complete.findtext("size"))
checksum = complete.findtext("checksum")
url = repository + complete.findtext("url")
segment = 32 * 1024 * 1024
parts = [(base / f"part-{i:02d}", start, min(size, start + segment) - 1) for i, start in enumerate(range(0, size, segment))]


def download(part):
    path, start, end = part
    for attempt in range(4):
        have = path.stat().st_size if path.exists() else 0
        if have == end - start + 1:
            return
        try:
            request = Request(url, headers={"Range": f"bytes={start + have}-{end}"})
            with urlopen(request, timeout=45) as source:
                if source.status != 206 or not source.headers.get("Content-Range", "").startswith(f"bytes {start + have}-"):
                    raise RuntimeError("Unexpected range response")
                with path.open("ab") as output:
                    while block := source.read(1024 * 1024):
                        output.write(block)
            if path.stat().st_size != end - start + 1:
                raise RuntimeError("Incomplete segment")
            print(f"Downloaded {path.name}", flush=True)
            return
        except Exception:
            if attempt == 3:
                raise
            time.sleep(2)


if not (base / "x86" / "system.img").exists():
    with ThreadPoolExecutor(max_workers=8) as executor:
        list(executor.map(download, parts))
    archive = base / "system-image.zip"
    digest = hashlib.sha1()
    with archive.open("wb") as output:
        for path, _, _ in parts:
            with path.open("rb") as source:
                while block := source.read(1024 * 1024):
                    digest.update(block)
                    output.write(block)
    if digest.hexdigest() != checksum:
        raise RuntimeError("Official SDK checksum mismatch")
    with ZipFile(archive) as zipped:
        if any(not (base / item.filename).resolve().is_relative_to(base.resolve()) for item in zipped.infolist()):
            raise RuntimeError("Unexpected system-image ZIP path")
        zipped.extractall(base)
print(f"Verified API 26 image: {base / 'x86'}", flush=True)
