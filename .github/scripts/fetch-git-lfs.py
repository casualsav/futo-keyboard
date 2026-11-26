#!/usr/bin/env python3
"""Downloads a checksum-verified git-lfs into DEST and prints the directory holding the binary.

The self-hosted runner has no git-lfs (nor curl), and FUTO's swipe models sit in LFS, so each
job fetches this one static binary into its temp directory. Nothing is installed system-wide.
Usage: fetch-git-lfs.py DEST
"""
import hashlib
import os
import platform
import sys
import tarfile
import urllib.request

VERSION = "v3.8.1"


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def main():
    dest = sys.argv[1]
    arch = {"aarch64": "arm64", "arm64": "arm64", "x86_64": "amd64"}[platform.machine()]
    name = f"git-lfs-linux-{arch}-{VERSION}.tar.gz"
    base = f"https://github.com/git-lfs/git-lfs/releases/download/{VERSION}/"

    data = fetch(base + name)
    sums = fetch(base + "sha256sums.asc").decode()
    expected = next(
        line.split()[0] for line in sums.splitlines()
        if len(line.split()) == 2 and line.split()[1].lstrip("*") == name
    )
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        sys.exit(f"git-lfs checksum mismatch: {actual} != {expected}")

    os.makedirs(dest, exist_ok=True)
    archive = os.path.join(dest, name)
    with open(archive, "wb") as f:
        f.write(data)
    with tarfile.open(archive) as t:
        t.extractall(dest, filter="data")

    for root, _, files in os.walk(dest):
        if "git-lfs" in files:
            print(root)
            return
    sys.exit("git-lfs binary not found in the archive")


if __name__ == "__main__":
    main()
