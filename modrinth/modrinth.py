#!/usr/bin/env python3
"""Manages the Modrinth project through the API, so nothing has to be done by hand on the website.

  modrinth.py draft                       create the project as a private draft, or update its page/icon
  modrinth.py gallery                     upload screenshots from modrinth/gallery that aren't there yet
  modrinth.py version <jar> <version>     upload a jar as a new version (changelog from CHANGELOG.md)
  modrinth.py submit                      submit the draft for Modrinth's review (makes it public once approved)

Needs MODRINTH_TOKEN (scopes: Create projects, Write projects, Create versions). Standard library only.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

API = "https://api.modrinth.com/v2"
ROOT = Path(__file__).resolve().parent.parent
HERE = ROOT / "modrinth"
SLUG = "speech-to-chat"
UA = "splice11/speech-to-chat (GitHub Actions publisher)"

PROJECT = {
    "slug": SLUG,
    "title": "Speech to Chat",
    "description": "Hold a key, talk, and your words are typed into chat. Live preview, runs locally on any GPU or CPU.",
    "categories": ["social", "utility"],
    "client_side": "required",
    "server_side": "unsupported",
    "license_id": "GPL-3.0-only",
    "source_url": "https://github.com/splice11/speech-to-chat",
    "issues_url": "https://github.com/splice11/speech-to-chat/issues",
}
DEPENDENCIES = [
    {"project_id": "P7dR8mSH", "dependency_type": "required"},  # Fabric API
    {"project_id": "mOgUt4GM", "dependency_type": "optional"},  # Mod Menu
    {"project_id": "1eAoo2KR", "dependency_type": "optional"},  # YACL
]


def token():
    t = os.environ.get("MODRINTH_TOKEN", "").strip()
    if not t:
        sys.exit("MODRINTH_TOKEN is not set")
    return t


def call(method, path, body=None, content_type=None, ok=(200, 201, 204)):
    req = urllib.request.Request(API + path, data=body, method=method)
    req.add_header("Authorization", token())
    req.add_header("User-Agent", UA)
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req) as res:
            raw = res.read()
            return res.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        if e.code in ok:
            return e.code, None
        if e.code == 404 and method == "GET":
            return 404, None
        hint = " (the token is missing a scope)" if e.code in (401, 403) else ""
        sys.exit(f"{method} {path} -> HTTP {e.code}{hint}: {detail}")


def multipart(parts):
    """parts: list of (name, filename or None, content_type, bytes)."""
    boundary = uuid.uuid4().hex
    out = bytearray()
    for name, filename, ctype, data in parts:
        out += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"".encode()
        if filename:
            out += f"; filename=\"{filename}\"".encode()
        out += f"\r\nContent-Type: {ctype}\r\n\r\n".encode() + data + b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return bytes(out), "multipart/form-data; boundary=" + boundary


def project():
    status, p = call("GET", f"/project/{SLUG}")
    return p if status == 200 else None


def cmd_draft():
    body_md = (HERE / "description.md").read_text()
    icon = (HERE / "icon.png").read_bytes()
    p = project()
    if p is None:
        data = dict(PROJECT, body=body_md, project_type="mod", is_draft=True, initial_versions=[])
        payload, ctype = multipart([
            ("data", None, "application/json", json.dumps(data).encode()),
            ("icon", "icon.png", "image/png", icon),
        ])
        _, p = call("POST", "/project", payload, ctype)
        print(f"Created draft project {p['slug']} ({p['id']}), private until submitted for review")
    else:
        fields = {k: v for k, v in PROJECT.items() if k not in ("slug",)}
        fields["body"] = body_md
        call("PATCH", f"/project/{p['id']}", json.dumps(fields).encode(), "application/json")
        call("PATCH", f"/project/{p['id']}/icon?ext=png", icon, "image/png")
        print(f"Updated project {p['slug']} ({p['id']}), status: {p['status']}")


def cmd_gallery():
    p = project() or sys.exit("no project yet, run draft first")
    have = {g.get("title") for g in p.get("gallery", [])}
    for i, img in enumerate(sorted((HERE / "gallery").glob("*.png"))):
        title = re.sub(r"^\d+-", "", img.stem).replace("-", " ").capitalize()
        if title in have:
            continue
        q = urllib.parse.urlencode({"ext": "png", "featured": str(i == 0).lower(), "title": title})
        call("POST", f"/project/{p['id']}/gallery?{q}", img.read_bytes(), "image/png")
        print("gallery +", title)


def cmd_version(jar, version):
    p = project() or sys.exit("no project yet, run draft first")
    jar = Path(jar)
    changelog = ""
    cl = ROOT / "CHANGELOG.md"
    if cl.exists():
        m = re.search(rf"^## {re.escape(version.lstrip('v'))}\b.*?\n(.*?)(?=^## |\Z)", cl.read_text(), re.S | re.M)
        changelog = m.group(1).strip() if m else ""
    mc = re.search(r"\+(\d[\d.]*)", version)
    data = {
        "project_id": p["id"],
        "name": f"Speech to Chat {version.lstrip('v')}",
        "version_number": version.lstrip("v"),
        "changelog": changelog,
        "dependencies": DEPENDENCIES,
        "game_versions": [mc.group(1)] if mc else ["26.3"],
        "version_type": "beta" if re.search(r"(alpha|beta|rc)", version) else "release",
        "loaders": ["fabric"],
        "featured": True,
        "file_parts": ["jar"],
        "primary_file": "jar",
    }
    payload, ctype = multipart([
        ("data", None, "application/json", json.dumps(data).encode()),
        ("jar", jar.name, "application/java-archive", jar.read_bytes()),
    ])
    _, v = call("POST", "/version", payload, ctype)
    print(f"Uploaded version {v['version_number']} ({v['id']})")


def cmd_submit():
    p = project() or sys.exit("no project yet, run draft first")
    if p["status"] != "draft":
        sys.exit(f"project status is {p['status']}, not draft; nothing to submit")
    call("PATCH", f"/project/{p['id']}", json.dumps({"status": "processing"}).encode(), "application/json")
    print("Submitted for review. Modrinth staff usually review within a few days.")


if __name__ == "__main__":
    import urllib.parse  # noqa: E402  (used by gallery)
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    {"draft": cmd_draft, "gallery": cmd_gallery, "version": cmd_version, "submit": cmd_submit}[args[0]](*args[1:])
