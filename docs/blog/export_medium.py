#!/usr/bin/env python3
"""Export a Proprio blog post (Markdown) to a Medium-pasteable HTML page.

Medium renders neither Mermaid nor Markdown tables, and imports rich content
best when it is pasted from a browser page. This tool:

* renders each ```mermaid block to a PNG (Mermaid CLI via npx),
* converts the Markdown to simple HTML (tables become bullet lists),
* embeds every image as a base64 data URI in ``medium.html``,
* copies the images in document order to ``images/`` and writes
  ``upload-order.txt`` as a manual fallback.

Usage:
    python docs/blog/export_medium.py docs/blog/<post>.md [--out DIR] [--skip-mermaid]

Standard library only; Mermaid rendering needs Node (``npx``).
"""

from __future__ import annotations

import argparse
import base64
import html
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

MERMAID_CLI = "@mermaid-js/mermaid-cli@11.17.0"

BULLET_RE = re.compile(r"^(\s*)([-*+]|\d+[.)])\s+(.*)$")
IMAGE_RE = re.compile(r"^!\[(.*?)\]\((.*?)\)\s*$")
CAPTION_RE = re.compile(r"^\*[^*\s].*[^*\s]\*$|^_[^_\s].*[^_\s]_$")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
HR_RE = re.compile(r"^\s*([-*_])(\s*\1){2,}\s*$")
FENCE_RE = re.compile(r"^\s*(```+|~~~+)\s*([\w+-]*)\s*$")
TABLE_SEP_RE = re.compile(r"^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$")


@dataclass
class ImageRef:
    order: int
    path: Path
    caption: str
    after: str


@dataclass
class Ctx:
    post_dir: Path
    diagrams: list[Path]
    images: list[ImageRef] = field(default_factory=list)
    last_text: str = ""
    tables: int = 0
    diagram_i: int = 0
    title: str = ""


# ---------------------------------------------------------------- inline


def plain(md: str) -> str:
    """Strip inline markdown for plain-text excerpts."""
    s = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", md)
    s = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", s)
    s = re.sub(r"[*`]", "", s)
    return re.sub(r"\s+", " ", s).strip()


def inline(text: str) -> str:
    stash: list[str] = []

    def keep(fragment: str) -> str:
        stash.append(fragment)
        return f"\x00{len(stash) - 1}\x00"

    # code spans first, so nothing inside them is interpreted
    text = re.sub(
        r"(`+)(.+?)\1",
        lambda m: keep(f"<code>{html.escape(m.group(2).strip())}</code>"),
        text,
    )
    # explicit links
    text = re.sub(
        r"\[([^\]]+)\]\(([^)\s]+)\)",
        lambda m: keep(
            f'<a href="{html.escape(m.group(2), quote=True)}">{_emph(html.escape(m.group(1)))}</a>'
        ),
        text,
    )

    # bare URLs
    def bare(m: re.Match) -> str:
        url = m.group(0)
        trail = ""
        while url and url[-1] in ".,;:)":
            trail = url[-1] + trail
            url = url[:-1]
        link = f'<a href="{html.escape(url, quote=True)}">{html.escape(url)}</a>'
        return keep(link) + html.escape(trail)

    text = re.sub(r"https?://[^\s<>]+", bare, text)
    # escape what is left (placeholders use \x00 and digits only)
    parts = re.split(r"(\x00\d+\x00)", text)
    out = "".join(p if p.startswith("\x00") else _emph(html.escape(p)) for p in parts)
    return re.sub(r"\x00(\d+)\x00", lambda m: stash[int(m.group(1))], out)


def _emph(s: str) -> str:
    s = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)
    s = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"<em>\1</em>", s)
    return s


# ---------------------------------------------------------------- blocks


def parse_blocks(lines: list[str], ctx: Ctx) -> str:
    out: list[str] = []
    i = 0
    n = len(lines)

    def next_nonblank(j: int) -> int:
        while j < n and not lines[j].strip():
            j += 1
        return j

    while i < n:
        line = lines[i]
        if not line.strip():
            i += 1
            continue

        fence = FENCE_RE.match(line)
        if fence:
            marker, lang = fence.group(1), fence.group(2)
            j = i + 1
            body = []
            while j < n and not lines[j].strip().startswith(marker[:3]):
                body.append(lines[j])
                j += 1
            i = j + 1
            if lang == "mermaid":
                png = ctx.diagrams[ctx.diagram_i]
                ctx.diagram_i += 1
                k = next_nonblank(i)
                caption = ""
                if k < n and CAPTION_RE.match(lines[k].strip()):
                    caption = lines[k].strip()
                    i = k + 1
                out.append(figure(ctx, png, f"Diagram {ctx.diagram_i}", caption))
            else:
                out.append("<pre><code>" + html.escape("\n".join(body)) + "</code></pre>")
            continue

        h = HEADING_RE.match(line)
        if h:
            level = len(h.group(1))
            if level == 1 and not ctx.title:
                ctx.title = plain(h.group(2))
            out.append(f"<h{level}>{inline(h.group(2))}</h{level}>")
            ctx.last_text = plain(h.group(2))
            i += 1
            continue

        if HR_RE.match(line):
            out.append("<hr>")
            i += 1
            continue

        img = IMAGE_RE.match(line.strip())
        if img:
            alt, src = img.group(1), img.group(2)
            i += 1
            k = next_nonblank(i)
            caption = ""
            if k < n and CAPTION_RE.match(lines[k].strip()):
                caption = lines[k].strip()
                i = k + 1
            out.append(figure(ctx, (ctx.post_dir / src).resolve(), alt, caption))
            continue

        if line.lstrip().startswith(">"):
            block = []
            while i < n and lines[i].lstrip().startswith(">"):
                block.append(re.sub(r"^\s*> ?", "", lines[i]))
                i += 1
            out.append(f"<blockquote>{parse_blocks(block, ctx)}</blockquote>")
            continue

        if line.lstrip().startswith("|") and i + 1 < n and TABLE_SEP_RE.match(lines[i + 1]):
            rows = []
            i += 2
            while i < n and lines[i].lstrip().startswith("|"):
                rows.append(lines[i])
                i += 1
            ctx.tables += 1
            out.append(table_to_list(rows))
            continue

        if BULLET_RE.match(line):
            html_list, i = parse_list(lines, i, ctx)
            out.append(html_list)
            continue

        # paragraph
        para = [line.strip()]
        i += 1
        while i < n and lines[i].strip():
            nxt = lines[i]
            if (
                FENCE_RE.match(nxt)
                or HEADING_RE.match(nxt)
                or BULLET_RE.match(nxt)
                or nxt.lstrip().startswith(">")
                or IMAGE_RE.match(nxt.strip())
            ):
                break
            para.append(nxt.strip())
            i += 1
        text = " ".join(para)
        out.append(f"<p>{inline(text)}</p>")
        ctx.last_text = plain(text)
    return "\n".join(out)


def parse_list(lines: list[str], i: int, ctx: Ctx) -> tuple[str, int]:
    n = len(lines)
    first = BULLET_RE.match(lines[i])
    base = len(first.group(1))
    ordered = first.group(2)[0].isdigit()
    start = int(first.group(2).rstrip(".)")) if ordered else 1
    items: list[str] = []
    while i < n:
        m = BULLET_RE.match(lines[i])
        if not m or len(m.group(1)) != base or m.group(2)[0].isdigit() != ordered:
            break
        content_indent = base + len(m.group(2)) + 1
        body = [m.group(3)]
        i += 1
        while i < n:
            ln = lines[i]
            if not ln.strip():
                j = i
                while j < n and not lines[j].strip():
                    j += 1
                if j < n and len(lines[j]) - len(lines[j].lstrip()) >= content_indent:
                    body.extend([""] * (j - i))
                    i = j
                    continue
                break
            indent = len(ln) - len(ln.lstrip())
            if indent >= content_indent:
                body.append(ln[content_indent:])
                i += 1
            elif indent > base and not BULLET_RE.match(ln):
                body.append(ln.strip())  # lazy continuation
                i += 1
            elif indent > base:
                body.append(ln[min(indent, content_indent):])
                i += 1
            else:
                break
        items.append(render_item(body, ctx))
        # skip blank lines between items of the same list
        j = i
        while j < n and not lines[j].strip():
            j += 1
        if j < n and j != i:
            m2 = BULLET_RE.match(lines[j])
            if m2 and len(m2.group(1)) == base and m2.group(2)[0].isdigit() == ordered:
                i = j
    tag = "ol" if ordered else "ul"
    attr = f' start="{start}"' if ordered and start != 1 else ""
    return f"<{tag}{attr}>\n" + "\n".join(items) + f"\n</{tag}>", i


def render_item(body: list[str], ctx: Ctx) -> str:
    # leading paragraph lines stay inline (tight list); the rest are blocks
    k = 0
    while k < len(body) and body[k].strip() and not BULLET_RE.match(body[k]) and not (
        FENCE_RE.match(body[k]) or body[k].lstrip().startswith(">")
    ):
        k += 1
    head = " ".join(s.strip() for s in body[:k])
    ctx.last_text = plain(head)
    rest = parse_blocks(body[k:], ctx) if k < len(body) else ""
    return f"<li>{inline(head)}{chr(10) + rest if rest else ''}</li>"


def split_row(row: str) -> list[str]:
    row = row.strip()
    row = row.removeprefix("|")
    row = row.removesuffix("|")
    return [c.strip() for c in re.split(r"(?<!\\)\|", row)]


def table_to_list(rows: list[str]) -> str:
    items = []
    for row in rows:
        cells = [c for c in split_row(row)]
        first, rest = cells[0], [c for c in cells[1:] if c]
        body = f"<strong>{inline(first)}</strong>"
        if rest:
            body += " — " + " — ".join(inline(c) for c in rest)
        items.append(f"<li>{body}</li>")
    return "<ul>\n" + "\n".join(items) + "\n</ul>"


def figure(ctx: Ctx, path: Path, alt: str, caption: str) -> str:
    if not path.is_file():
        sys.exit(f"error: image not found: {path}")
    cap_text = plain(caption)
    ctx.images.append(ImageRef(len(ctx.images) + 1, path, cap_text, ctx.last_text[:80]))
    mime = {"png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "gif": "image/gif",
            "svg": "image/svg+xml", "webp": "image/webp"}.get(path.suffix.lower().lstrip("."), "image/png")
    data = base64.b64encode(path.read_bytes()).decode("ascii")
    cap = f"\n<figcaption>{inline(caption.strip('*_ '))}</figcaption>" if caption else ""
    return (
        f'<figure><img src="data:{mime};base64,{data}" alt="{html.escape(alt, quote=True)}">'
        f"{cap}</figure>"
    )


# ---------------------------------------------------------------- mermaid


def extract_mermaid(md: str) -> list[str]:
    blocks, cur, inside = [], [], False
    for line in md.splitlines():
        f = FENCE_RE.match(line)
        if not inside and f and f.group(2) == "mermaid":
            inside, cur = True, []
        elif inside and line.strip().startswith("```"):
            blocks.append("\n".join(cur) + "\n")
            inside = False
        elif inside:
            cur.append(line)
    return blocks


def render_mermaid(blocks: list[str], out_dir: Path, skip: bool) -> list[Path]:
    work = out_dir / "mermaid"
    work.mkdir(parents=True, exist_ok=True)
    npx = "npx.cmd" if sys.platform == "win32" else "npx"
    pngs = []
    for idx, code in enumerate(blocks, 1):
        mmd = work / f"diagram-{idx:02d}.mmd"
        png = work / f"diagram-{idx:02d}.png"
        mmd.write_text(code, encoding="utf-8")
        pngs.append(png)
        if skip:
            if not png.is_file():
                sys.exit(f"error: --skip-mermaid but {png} does not exist")
            continue
        cmd = [npx, "-y", MERMAID_CLI, "-i", str(mmd), "-o", str(png),
               "-b", "white", "-s", "2", "-w", "1600"]
        print(f"rendering diagram {idx}/{len(blocks)} ...")
        res = subprocess.run(cmd, check=False, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if res.returncode != 0 or not png.is_file():
            sys.exit(
                f"error: mermaid rendering failed for diagram {idx} (exit {res.returncode}).\n"
                f"command: {' '.join(cmd)}\n{res.stdout}\n{res.stderr}"
            )
    return pngs


# ---------------------------------------------------------------- output

PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{title}</title>
<style>
body {{ font-family: -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  max-width: 720px; margin: 2rem auto; padding: 0 16px; line-height: 1.6; color: #222; }}
img {{ max-width: 100%; height: auto; }}
figure {{ margin: 1.5rem 0; }}
figcaption {{ font-size: .9em; color: #555; font-style: italic; text-align: center; }}
blockquote {{ border-left: 3px solid #bbb; margin-left: 0; padding-left: 1rem; color: #444; }}
pre {{ background: #f4f4f4; padding: 1rem; overflow-x: auto; }}
code {{ background: #f4f4f4; padding: 0 .2em; }}
pre code {{ padding: 0; }}
</style>
</head>
<body>
{body}
</body>
</html>
"""


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("post", type=Path)
    ap.add_argument("--out", type=Path, help="default: docs/blog/assets/<post-stem>/medium")
    ap.add_argument("--skip-mermaid", action="store_true", help="reuse existing diagram PNGs")
    args = ap.parse_args()

    post = args.post.resolve()
    if not post.is_file():
        sys.exit(f"error: {post} not found")
    out = (args.out or Path(__file__).resolve().parent / "assets" / post.stem / "medium").resolve()
    out.mkdir(parents=True, exist_ok=True)

    md = post.read_text(encoding="utf-8")
    diagrams = render_mermaid(extract_mermaid(md), out, args.skip_mermaid)

    ctx = Ctx(post_dir=post.parent, diagrams=diagrams)
    body = parse_blocks(md.splitlines(), ctx)
    page = PAGE.format(title=html.escape(ctx.title or post.stem), body=body)
    (out / "medium.html").write_text(page, encoding="utf-8")

    img_dir = out / "images"
    if img_dir.exists():
        shutil.rmtree(img_dir)
    img_dir.mkdir()
    lines = []
    for im in ctx.images:
        name = f"{im.order:02d}-{im.path.stem}{im.path.suffix}"
        shutil.copyfile(im.path, img_dir / name)
        lines.append(f"{im.order:02d} | images/{name} | caption: {im.caption or '(none)'} "
                     f"| after: {im.after or '(start)'}")
    (out / "upload-order.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"diagrams rendered: {len(diagrams)}{' (reused)' if args.skip_mermaid else ''}")
    print(f"images:            {len(ctx.images)}")
    print(f"tables converted:  {ctx.tables}")
    print(f"html:              {out / 'medium.html'}")
    print(f"images dir:        {img_dir}")
    print(f"upload order:      {out / 'upload-order.txt'}")


if __name__ == "__main__":
    main()
