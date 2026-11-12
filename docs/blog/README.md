# Blog tooling

## Publishing on Medium

Medium has no Markdown tables and no Mermaid, and it imports rich content best when pasted from a browser page. `export_medium.py` handles both (standard library only; Mermaid rendering needs Node via `npx`).

```
python docs/blog/export_medium.py docs/blog/<post>.md
# optional: --out <dir>     (default docs/blog/assets/<post-stem>/medium)
# optional: --skip-mermaid  (reuse already-rendered diagram PNGs)
```

Output (under the gitignored `docs/blog/assets/`):

- `medium.html`: the post with every image embedded.
- `images/`: every image in document order (`01-...png`, `02-...png`).
- `upload-order.txt`: for each image, its number, file, caption and the paragraph it follows.

To publish: open `medium.html` in a browser, select all, copy, and paste into a new Medium story. If the images do not come through, upload them by hand from `images/` at the places listed in `upload-order.txt`.

Conversions: Mermaid diagrams are rendered to PNG (`@mermaid-js/mermaid-cli`, version pinned in the script); tables become bullet lists (`**first cell** — other cells`, header row dropped).
