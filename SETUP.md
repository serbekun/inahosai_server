# Setting up a fork

This site is driven by one config file. To adapt it for a different school and
festival you should not need to edit any Java, HTML, CSS or JavaScript.

## 1. Create your config

Copy the bundled default and edit the copy:

```sh
cp src/main/resources/config.default.yaml config.yaml
```

The server looks for a config in this order:

1. the file named by the `-Dinahosai.config` system property
2. the file named by the `INAHOSAI_CONFIG` environment variable
3. `./config.yaml` in the working directory
4. the bundled `config.default.yaml` from inside the jar

It logs which one it used at startup.

The bundled default carries `is_setup_readed_and_config_edited: false`, and while it is
false the server **refuses to start** with a message asking you to check the config. Read
every value, make it fit your school, then set that key to `true` in your `config.yaml`.
This is what stops a fork from going live still showing the default school.

**A typo is a startup error, not a silent default.** Unknown keys are rejected, so
`nmae_ja` stops the server with a message naming the key rather than quietly leaving
the school name blank.

Nothing in this file is secret. Do not put tokens or connection strings in it.

## 2. Required values

Three keys must be set for the site to be meaningful:

| Key | What it is |
| --- | --- |
| `school.name_ja` | School name in Japanese |
| `festival.name` | Festival name |
| `festival.start_date` | First day of the festival |

Until all three are set, every page shows a banner reading
`このサイトはまだ設定されていません — SETUP.md を参照してください`, and the server logs a
warning at startup listing what is missing.

Run with `INAHOSAI_ENV=dev` and open `/setup` for a checklist of every key, whether it
is set, and what it controls. That page prints key names and status only — never
values — and the route does not exist unless `INAHOSAI_ENV=dev`.

### About `festival.start_date`

The Japanese era shown on every page (`令和8年`) is derived from this date, not from
the calendar year. Eras change mid-year — 令和 began on 1 May 2019 — so a date is the
only thing that gives the right answer. Set the real first day of your festival.

The default config ships `2026-09-18` as an obvious placeholder. Change it.

Note that Java renders the first year of an era as `令和1年`, not `令和元年`.

## 3. Values worth setting

- `school.name_short` — shown in the header mark.
- `school.name_latin` — shown under the hero title; its first character is also the
  favicon glyph (here `K`). Omit it and the line disappears and the icon falls back to
  the short name.
- `festival.slogan` — the one-word theme, used in the hero and the header.
- `festival.concept_lead` — the CONCEPT paragraph, one entry per line.
- `festival.about` — extra paragraphs for the visible ABOUT section on the home page.
  A factual sentence is generated from the school, festival, era, dates and slogan, so
  this is only for anything that sentence does not already say. Leave it empty (`[]`)
  to show only the generated text.
- `site.base_url` — your public origin. **Without it, `og:url`, `og:image` and the
  canonical link are omitted**, and `/sitemap.xml` is not served, because all of those
  need absolute URLs. Set it if the site will be shared in LINE or found by a search
  engine. With it set, the server also serves `/sitemap.xml` and a `/robots.txt` whose
  `Sitemap:` line points at it.
- `site.repo_url` — the repository the footer links to. It ships pointing at the
  upstream project; a fork should point it at its own. The footer also states that the
  code is MIT-licensed, which is a property of the code rather than of your school, so
  that text is not configurable.
- `site.author` / `site.author_url` — the credit at the end of the footer and where it
  links.
- `site.school_url` — the school's own website. The footer's school name links to it.
- `pages` — the pages, their routes and their nav labels. The shared header is
  generated from this list, so the navigation is identical on every page.
- `graph` — the theme words on the home, 場所 and 世界 pages.
- `works.items` — the works listed on the 学び page. Each item has a `title`, a
  `description`, a lowercase `key` and a list of `files` (`{name, url}`). One file
  downloads straight from the button; two or more open a chooser page at `/works/{key}`.
  The chooser is not part of `pages`, so it never appears in the nav or the sitemap.

### Live stream

Pick the platform with `stream.type` (`"youtube"` or `"zoom"`) and fill the matching
payload:

- `stream.youtube_id` — the 11 characters after `v=` in a YouTube URL, **not** the URL.
- `stream.zoom_url` — the full `https://...` Zoom meeting link.

Leave `stream.type` blank to infer it from whichever payload is set, so a config that
only fills `youtube_id` keeps working. A selected platform with no usable payload counts
as no stream.

`GET /api/v0/live/type/` reports the effective platform, e.g. `{"type":"youtube"}`
(also `"zoom"` or `"none"`). The backend supports both platforms; the bundled frontend
still only renders the YouTube embed.

## 4. Anything unset hides its element

An unconfigured fork must never show a dead link or a broken image. So:

- no `works[].files` → the 作品一覧を見る button is not rendered at all
- a work with one file → the button downloads that file directly
- a work with several files → the button opens `/works/{key}`, a chooser page
- a work with files but no URL-safe `key` → no button, and a warning at startup
- no `hero.photo` → no `<img>`; the hero composition already works without one
- no `site.gate_url` → the extra header link is absent entirely
- no `site.repo_url` → the footer shows the licence note without a source link
- no `site.author` → the footer credits the school alone
- no `site.author_url` or `site.school_url` → that name stays plain text, not a link
- no `site.apple_touch_icon` → the generated `/apple-touch-icon.png` is linked instead
  of a 404
- a malformed `stream.youtube_id` or `stream.zoom_url` → treated as no stream, and logged

## 5. The theme graph

`graph.center` and `graph.branches` drive the canvas on the home, 場所 and 世界 pages.

Every `at` and `leaf_at` value is a normalised `[x, y]` pair in the range 0..1. **These
positions are hand-tuned, not automatically laid out** — they are placed to avoid
collisions and balance the composition. A fork with different words will need to retune
them, which is exactly why they live in the config and not in `graph.js`.

They are only used on wide viewports. The narrow layout stacks nodes on a grid and
ignores them.

Each branch carries its own `url`, so a branch and its destination cannot drift apart.

## 6. Images

On first run the server unpacks its bundled templates into `data/static/` and serves
everything from there; the jar is only the template source and is never read at request
time afterwards. So images live in `data/static/images/` and are referenced by
**filename only**, not by path — `school.png`, not `images/school.png`. A name containing
a slash or `..` is rejected and the image is treated as absent.

Replace `school.png` with your own building photo. Note that the shipped file is about
5 MB and is loaded on every page; you probably want to compress yours.

To add a PDF (a programme, a map), drop it in `data/static/pdf/` and link to it as
`/static/v0/pdf/yourfile.pdf`. The directory ships with a placeholder `sample.pdf` that
is unpacked on first run, so a download works out of the box; replace it with your own.

PDFs can be put behind a token. Set `pdf.require_auth: true` and a non-empty
`pdf.token` in the config. A password form then appears on the 学び page; the visitor
submits the token once, the server answers with an HttpOnly cookie, and every later PDF
request (and the PDF directory listing) is authorized by that cookie. A missing or wrong
token returns `401`. The token is never accepted from a URL, so it cannot leak through
access logs, browser history or a `Referer` header.

Gated or not, PDF replies are sent with `Cache-Control: private, no-store` and
`X-Robots-Tag: noindex, nofollow`, and `/static/v0/pdf/` is disallowed in `robots.txt`,
so documents stay out of shared caches and search indexes. With `require_auth: false`,
or with an empty token, PDFs stay public but keep those headers.

The `data/` directory is git-ignored and must never be committed: it is where private
files and operator edits live.

## 7. Running

```sh
./gradlew run                    # production mode: /setup is not registered
INAHOSAI_ENV=dev ./gradlew run   # development mode: /setup is available
```

On the first run the server copies its bundled templates (CSS, JS, page templates and
images) into `data/static/` and writes a marker there recording the version it unpacked.
Later runs skip the copy, and an upgraded build only fills in templates that are new in
that version — files already on disk are never overwritten, so your edits survive. After
that, the running server reads `data/static/` and nothing else.

The server listens on the top-level `port` from `config.yaml` (default `2323`).
Pages are served at `/`, `/jikan`, `/manabi`, `/basho` and `/sedai`; static assets
under `/static/v0/`.

Every page is rendered once at startup and held in memory. Editing a template or the
config therefore needs a restart — or a call to `/api/v0/admin/reload`, which is only
registered when `INAHOSAI_ADMIN_TOKEN` is set.

## 8. Licence

The **code** is MIT licensed — see `LICENSE`.

The **content** is not covered by that licence and is not yours to reuse. Before
publishing a fork, replace:

- the school and festival names, and the slogan
- `data/static/images/school.png` and any other photograph
- the CONCEPT text and the theme words
- the work titles and descriptions in `works.items`

Photographs of a school building, and of students, carry obligations that a software
licence does not address. Check what your school permits before publishing anything
showing identifiable people.

## 9. Third-party requests

The site currently makes **no third-party requests at all** — no CDN, no web fonts, no
analytics. The favicon is generated by the server itself and served from `/favicon.ico`
and `/favicon.png`, with an inline SVG for modern tabs. This is deliberate: it keeps
visitor data from leaving your server, which matters more than usual when the visitors
are school children. Please keep it that way.
