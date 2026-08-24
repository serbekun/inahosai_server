# Setting up a fork

This site is driven by one config file. To adapt it for a different school and
festival you should not need to edit any Java, HTML, CSS or JavaScript.

## 1. Create your config

Copy the bundled default and edit the copy:

```sh
cp src/main/resources/config.default.yaml config.yaml
```

The server looks for a config in this order:

1. the file named by the `BUNKASAI_CONFIG` environment variable
2. `./config.yaml` in the working directory
3. the bundled `config.default.yaml` from inside the jar

It logs which one it used at startup.

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

Run with `BUNKASAI_ENV=dev` and open `/setup` for a checklist of every key, whether it
is set, and what it controls. That page prints key names and status only — never
values — and the route does not exist unless `BUNKASAI_ENV=dev`.

### About `festival.start_date`

The Japanese era shown on every page (`令和8年`) is derived from this date, not from
the calendar year. Eras change mid-year — 令和 began on 1 May 2019 — so a date is the
only thing that gives the right answer. Set the real first day of your festival.

The default config ships `2026-01-01` as an obvious placeholder. Change it.

Note that Java renders the first year of an era as `令和1年`, not `令和元年`.

## 3. Values worth setting

- `school.name_short` — also becomes the favicon glyph (its first character).
- `school.name_latin` — shown under the hero title; omit it and the line disappears.
- `festival.slogan` — the one-word theme, used in the hero and the header.
- `festival.concept_lead` — the CONCEPT paragraph, one entry per line.
- `site.base_url` — your public origin. **Without it, `og:url` and `og:image` are
  omitted**, because a relative image URL is useless to a link-preview crawler. Set it
  if the site will be shared in LINE.
- `pages` — the pages, their routes and their nav labels. The shared header is
  generated from this list, so the navigation is identical on every page.
- `graph` — the theme words on the home, 場所 and 世界 pages.

## 4. Anything unset hides its element

An unconfigured fork must never show a dead link or a broken image. So:

- no `works[].url` → the 作品一覧を見る button is not rendered at all
- no `hero.photo` → no `<img>`; the hero composition already works without one
- no `site.gate_url` → the extra header link is absent entirely
- no `site.apple_touch_icon` → the tag is omitted rather than pointing at a 404
- a malformed `stream.youtube_id` → treated as no stream, and logged

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

Images live in `src/main/resources/images/` and are referenced by **filename only**, not
by path — `school.png`, not `images/school.png`. A name containing a slash or `..` is
rejected and the image is treated as absent.

Replace `school.png` with your own building photo. Note that the shipped file is about
5 MB and is loaded on every page; you probably want to compress yours.

To add a PDF (a programme, a map), drop it in `src/main/resources/pdf/` and link to it
as `/static/v0/pdf/yourfile.pdf`.

## 7. Running

```sh
./gradlew run                    # production mode: /setup is not registered
BUNKASAI_ENV=dev ./gradlew run   # development mode: /setup is available
```

The server listens on port 2323. Pages are served at `/`, `/jikan`, `/manabi`,
`/basho` and `/sekai`; static assets under `/static/v0/`.

Every page is rendered once at startup and held in memory. Editing a template or the
config therefore needs a restart — or a call to `/api/v0/admin/reload`, which is only
registered when `BUNKASAI_ADMIN_TOKEN` is set.

## 8. Licence

The **code** is MIT licensed — see `LICENSE`.

The **content** is not covered by that licence and is not yours to reuse. Before
publishing a fork, replace:

- the school and festival names, and the slogan
- `images/school.png` and any other photograph
- the CONCEPT text and the theme words
- the work titles and descriptions in `works.items`

Photographs of a school building, and of students, carry obligations that a software
licence does not address. Check what your school permits before publishing anything
showing identifiable people.

## 9. Third-party requests

The site currently makes **no third-party requests at all** — no CDN, no web fonts, no
analytics. The favicon is an inline data URI. This is deliberate: it keeps visitor data
from leaving your server, which matters more than usual when the visitors are school
children. Please keep it that way.
