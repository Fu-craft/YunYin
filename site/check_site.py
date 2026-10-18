# -*- coding: utf-8 -*-
"""Validates the static site: structure, local references, a11y basics, no external assets.

Run: python check_site.py
Exits non-zero on any failure.
"""
import io
import os
import re
import sys
from html.parser import HTMLParser

SITE = os.path.dirname(os.path.abspath(__file__))
HTML = os.path.join(SITE, "index.html")

VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
        "param", "source", "track", "wbr"}
RAWTEXT = {"script", "style"}

problems = []
notes = []


def fail(msg):
    problems.append(msg)


class Checker(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.ids = {}
        self.refs = []          # (context, value, line)
        self.anchor_ids = []
        self.img_count = 0
        self.imgs_without_alt = []
        self.external_assets = []
        self.inline_scripts = 0
        self.lang = None
        self.title = ""
        self._in_title = False
        self._script_depth = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        line = self.getpos()[0]
        if tag == "html":
            self.lang = attrs.get("lang")
        if tag == "title":
            self._in_title = True
        if tag in VOID:
            if tag == "img":
                self.img_count += 1
                if "alt" not in attrs:
                    self.imgs_without_alt.append((line, attrs.get("src")))
                for key in ("src", "srcset"):
                    if key in attrs:
                        self.refs.append(("img@" + key, attrs[key], line))
            if tag == "link":
                self.refs.append(("link@" + attrs.get("rel", "?"), attrs.get("href", ""), line))
                if "stylesheet" in (attrs.get("rel") or "") and "http" in (attrs.get("href") or ""):
                    self.external_assets.append((line, attrs.get("href")))
            if tag == "meta" and attrs.get("property", "").startswith("og:"):
                self.refs.append(("meta@og", attrs.get("content", ""), line))
            return
        self.stack.append((tag, line))
        if tag == "a":
            if attrs.get("id"):
                self.ids.setdefault(attrs["id"], line)
            if attrs.get("href"):
                self.refs.append(("a@href", attrs["href"], line))
        else:
            if attrs.get("id"):
                if attrs["id"] in self.ids:
                    fail("duplicate id '%s' at line %d" % (attrs["id"], line))
                self.ids[attrs["id"]] = line
        if tag == "script":
            self._script_depth += 1
            if "src" in attrs:
                self.refs.append(("script@src", attrs["src"], line))
                if re.match(r"https?://", attrs["src"]):
                    self.external_assets.append((line, attrs["src"]))
            else:
                self.inline_scripts += 1
        if tag == "img":
            for key in ("src", "srcset"):
                if key in attrs:
                    self.refs.append(("img@" + key, attrs[key], line))

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        if tag == "title":
            self._in_title = False
        if tag == "script":
            self._script_depth = max(0, self._script_depth - 1)
        if not self.stack:
            fail("closing </%s> with nothing open" % tag)
            return
        open_tag, open_line = self.stack.pop()
        if open_tag != tag:
            fail("mismatched tags: <%s> (line %d) closed by </%s>" % (open_tag, open_line, tag))

    def handle_data(self, data):
        if self._in_title:
            self.title += data


def is_external(value):
    return bool(re.match(r"(https?:)?//", value.strip())) or value.strip().startswith("mailto:")


def strip_query(value):
    return value.split("?")[0].split("#")[0]


def main():
    raw = io.open(HTML, encoding="utf-8").read()
    parser = Checker()
    parser.feed(raw)
    parser.close()

    if parser.stack:
        fail("unclosed tags: %s" % ", ".join("<%s> line %d" % t for t in parser.stack))

    if parser.lang != "zh-CN":
        fail("html lang is %r, expected zh-CN" % parser.lang)
    if not parser.title.strip():
        fail("missing <title>")

    # Internal anchors must resolve.
    anchor_targets = set()
    for context, value, line in parser.refs:
        if context == "a@href" and value.startswith("#") and len(value) > 1:
            anchor_targets.add(value[1:])
    for target in sorted(anchor_targets):
        if target not in parser.ids:
            fail('anchor href="#%s" has no matching id' % target)

    # Every file reference must be local and exist.
    asset_refs = 0
    for context, value, line in parser.refs:
        value = value.strip()
        if not value or value.startswith("#"):
            continue
        if context == "a@href":
            if is_external(value):
                continue  # normal outbound link, not a loaded asset
            fail("relative non-asset link %r at line %d" % (value, line))
            continue
        if is_external(value):
            parser.external_assets.append((line, value))
            continue
        path = os.path.normpath(os.path.join(SITE, strip_query(value)))
        if not os.path.isfile(path):
            fail("missing local file for %s=%r (line %d)" % (context, value, line))
            continue
        asset_refs += 1
        if os.path.getsize(path) == 0:
            fail("empty asset: %s" % value)

    for line, value in parser.external_assets:
        fail("external asset reference at line %d: %s" % (line, value))

    for line, src in parser.imgs_without_alt:
        fail("img without alt at line %d (%s)" % (line, src))

    # CSS url() references must be local and exist.
    css = io.open(os.path.join(SITE, "styles.css"), encoding="utf-8").read()
    css_assets = 0
    for m in re.finditer(r"url\(\s*['\"]?([^'\")]+)['\"]?\s*\)", css):
        value = m.group(1).strip()
        if is_external(value):
            fail("external CSS asset: %s" % value)
            continue
        path = os.path.normpath(os.path.join(SITE, strip_query(value)))
        if not os.path.isfile(path):
            fail("missing CSS asset: %s" % value)
            continue
        css_assets += 1

    # No external network references anywhere in the deliverable text.
    for name in ("index.html", "styles.css", "main.js"):
        text = io.open(os.path.join(SITE, name), encoding="utf-8").read()
        for m in re.finditer(r"https?://[^\s\"'<>)]+", text):
            url = m.group(0)
            # Links a person clicks are allowed; anything the browser loads is not.
            if name == "index.html" and ("github.com" in url or "gnu.org" in url):
                continue
            fail("%s contains external URL: %s" % (name, url))

    # Basic copy/ARIA sanity.
    if parser.img_count != 6:
        notes.append("img elements: %d (expected 6: 5 screenshots + brand mark x2 uses)" % parser.img_count)
    if 'role="tablist"' not in raw or 'aria-selected' not in raw:
        fail("the tab demo lost its tablist roles")
    if "prefers-reduced-motion" not in css:
        fail("CSS does not honour prefers-reduced-motion")
    if "no-js" not in css:
        fail("CSS lacks a no-JS fallback")
    if "backdrop-filter" not in css:
        fail("CSS lost the glass material")

    total = 0
    files = []
    for name in sorted(os.listdir(SITE)):
        full = os.path.join(SITE, name)
        if os.path.isfile(full):
            files.append((name, os.path.getsize(full)))
            total += os.path.getsize(full)
    for name in sorted(os.listdir(os.path.join(SITE, "assets"))):
        full = os.path.join(SITE, "assets", name)
        files.append(("assets/" + name, os.path.getsize(full)))
        total += os.path.getsize(full)

    print("== files ==")
    for name, size in files:
        print("  %-28s %7.1f KB" % (name, size / 1024.0))
    print("  %-28s %7.1f KB" % ("TOTAL", total / 1024.0))
    print()
    print("html: %d img, %d inline script(s), %d local asset refs, %d css asset refs"
          % (parser.img_count, parser.inline_scripts, asset_refs, css_assets))
    for note in notes:
        print("NOTE: " + note)
    print()
    if problems:
        print("FAIL (%d):" % len(problems))
        for p in problems:
            print("  - " + p)
        return 1
    print("RESULT: site is self-contained, structurally valid, and every reference resolves")
    return 0


if __name__ == "__main__":
    sys.exit(main())
