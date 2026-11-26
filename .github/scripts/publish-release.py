#!/usr/bin/env python3
"""Publishes a "personal-N" GitHub release with one APK attached, like the earlier hand-made ones.

With RELEASE_PREFIX=live it publishes "live-N" instead (the live voice typing test app), as a
pre-release that is never marked as the latest release.

Usage:
  publish-release.py next-number            prints N, one more than the highest personal-N tag
  publish-release.py publish N APK BODY     creates release personal-N on GITHUB_SHA, uploads APK

Needs GITHUB_TOKEN (contents: write), GITHUB_REPOSITORY and GITHUB_SHA from the workflow.
Uses only the standard library, since the runner has no gh or curl.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
REPO = os.environ["GITHUB_REPOSITORY"]
TOKEN = os.environ["GITHUB_TOKEN"]
PREFIX = os.environ.get("RELEASE_PREFIX", "personal")
if PREFIX not in ("personal", "live"):
    sys.exit(f"Unknown RELEASE_PREFIX {PREFIX!r}")


def call(method, url, data=None, content_type="application/json"):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {url} failed: {e.code} {e.read().decode(errors='replace')}")


def next_number():
    highest, page = 0, 1
    while True:
        tags = call("GET", f"{API}/repos/{REPO}/tags?per_page=100&page={page}")
        releases = call("GET", f"{API}/repos/{REPO}/releases?per_page=100&page={page}")
        names = [t["name"] for t in tags] + [r["tag_name"] for r in releases]
        for name in names:
            m = re.fullmatch(re.escape(PREFIX) + r"-(\d+)", name)
            if m:
                highest = max(highest, int(m.group(1)))
        if len(tags) < 100 and len(releases) < 100:
            return highest + 1
        page += 1


def publish(n, apk, body):
    fields = {
        "tag_name": f"{PREFIX}-{n}",
        "target_commitish": os.environ["GITHUB_SHA"],
        "name": f"Personal build {n}" if PREFIX == "personal" else f"Live typing test build {n}",
        "body": body,
    }
    if PREFIX == "live":
        fields["prerelease"] = True
        fields["make_latest"] = "false"
    release = call("POST", f"{API}/repos/{REPO}/releases", json.dumps(fields).encode())
    name = f"futo-keyboard-{PREFIX}-{n}.apk"
    upload = release["upload_url"].split("{")[0] + "?" + urllib.parse.urlencode({"name": name})
    with open(apk, "rb") as f:
        asset = call("POST", upload, f.read(), "application/vnd.android.package-archive")
    print(release["html_url"])
    print(asset["browser_download_url"])


if __name__ == "__main__":
    if sys.argv[1] == "next-number":
        print(next_number())
    elif sys.argv[1] == "publish":
        publish(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        sys.exit(__doc__)
