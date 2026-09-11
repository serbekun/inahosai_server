# Inahosai Server

[日本語](README.md) | [English](README_en.md)

A small HTTP server that runs a Japanese school-festival homepage.

It was written for **茎崎中学校 稲穂祭** (theme **つなぐ**). The code is MIT. The school names, copy, photos and PDFs are not. A fork should become a different school by editing one YAML file, not by hunting strings through Java, HTML, CSS and JavaScript.

Deploy example: [https://inahosai.serbekun.com/](https://inahosai.serbekun.com/)

> **Do not commit school PDFs, しおり, or photographs of students.**
> They almost always contain personal data of minors.
> Put them in `data/` (gitignored) or they will end up on GitHub and inside the JAR.

## What this is

A self-hosted homepage for one 文化祭:

| Route | Role |
| --- | --- |
| `/` | Home — theme, concept, graph |
| `/jikan` | 時間 — timetable / past·present·future |
| `/manabi` | 学び — exhibited works |
| `/basho` | 場所 — venue / live |
| `/sedai` | 世代 — the “connect generations” page |

Static files (CSS, JS, images, PDFs) are served under `/static/v0/`. Pages are Mustache templates filled from YAML and **rendered once at process start**, then served as bytes with an ETag.

Stack: Java 21, Javalin 6, jmustache, Jackson YAML. No framework beyond that. Current version: `pre-alpha-2026-09-10`.

This release is a config-driven site. There is no user login, no 思い出 board, and **no database to install**. Those come later; the storage choice for them is already decided (see [Philosophy](#philosophy)).

## Why it is open source

Every year somebody at a school is asked to “make a website for the festival” on a deadline that does not move. Most of that work is not unique: a handful of pages, a programme PDF, a live-stream embed, a photo of the building, names and dates.

This repository exists so that person can fork it, change the config, drop in their own files, and ship. Change whatever you want. The MIT licence on the **code** is the permission to do that.

It is *not* permission to reuse 茎崎, 稲穂祭, the slogan, the photographs, or anyone’s しおり. Those belong to a school and to the people in the pictures.

## Philosophy

### Config, not code

`config.yaml` is the school. Names, dates, slogan, concept lines, page list, graph words and positions, stream id, work titles, footer links — all of it.

That is the point of the file. A fork should not need to edit Java, HTML, CSS or JavaScript to become a different festival. If you are about to change a school name in a template, put it in the config instead.

The bundled default is a working site for 茎崎 / 稲穂祭 because that is the school this server was written for. Serving those defaults under another school’s domain would be a silent mistake, so the server **refuses to start** until you set:

```yaml
is_setup_readed_and_config_edited: true
```

after you have actually read the file. A typo is a startup error, not a blank value: unknown keys are rejected by name. Unset optional keys hide their element (no dead `href="#"`, no 404 icon) instead of breaking the page. Missing *required* keys (`school.name_ja`, `festival.name`, `festival.start_date`) still start the process, but every page shows a banner pointing at `SETUP.md`.

Japanese era strings (`令和8年`) are computed from `festival.start_date`, not from the calendar year. Eras change mid-year.

Nothing secret belongs in this file. Tokens and connection strings go in the environment (`INAHOSAI_ADMIN_TOKEN`, and later a database URL).

### Why pages are rendered on the server, once

Not in the browser, and not on every request.

- Festival URLs are shared in LINE. The LINE crawler does not run JavaScript. `<title>` and `og:` tags have to already be in the HTML or the chat card is empty.
- The hero canvas rasterises `data-text` on the first frame. If the school name arrived later via `fetch`, the sign would assemble, twitch, and rebuild.
- Five pages render in milliseconds. Doing it per request buys a chance to fail under load and nothing else.
- With JavaScript off, the site still has layout, copy and navigation. Only the particle effect goes away.
- Mustache escapes by default. Filling the DOM with `innerHTML` is how a config value becomes XSS.

Scripts that need config (the theme graph) read a JSON island already in the page — no extra round trip. CSS and JS stay real static files with a long cache.

Change the config → restart, or `POST /api/v0/admin/reload` when `INAHOSAI_ADMIN_TOKEN` is set.

### Why PostgreSQL (and why it is not in this release)

This pre-alpha stores nothing that a process crash would lose: YAML, templates, images, PDFs. A database would be idle weight.

The next features that accept visitor input (a 思い出 board, idea submissions, anything a guest typed) will use **PostgreSQL**, not “keep it in RAM and flush a JSON file every few minutes.” That is a design choice, not a performance choice.

A 文化祭 is a few hundred to a few thousand visitors over two days. HashMap vs Postgres is invisible next to phone latency. What is not invisible:

- **A crash is data loss.** Periodic JSON flush leaves a window. If the process dies after thirty people wrote a 思い出 and the last flush was five minutes ago, those messages are gone and the authors have gone home. For a once-a-year event that is unacceptable.
- **Concurrent writes.** Read-modify-write of one JSON file is a classic way to corrupt it.
- **Moderation is queries** (“pending”, “this IP in the last hour”, soft-delete), not a full-file scan.
- **Forks.** The next school is likely larger than 茎崎. Switching storage later, at the bottom of the stack, is how you lose data. PostgreSQL from the moment there is user data means a fork does not inherit a toy store.

SQLite would be fine for a single school (one file, no daemon). Postgres is the default because this project is meant to be forked, including later by a 高校. The access pattern will sit behind a `Repository` interface either way.

Until that layer exists, **do not invent a JSON store for guest input.** Either wait for it or keep the feature off.

### Zero third-party requests

The site currently talks to no CDN, no webfonts, no analytics. The favicon is an inline data URI. Visitors of a school site are children; their browsers should not phone home to a third party just to render a page. A YouTube embed on the live page is the one exception, and only when a stream id is configured. Keep it that way.

## Do not commit school documents

This is the easiest way for a fork to cause real harm.

しおり, maps, work catalogues and class photos routinely contain **names, faces and other personal data of minors**. Putting them in git publishes them: the repo, every fork, every clone, the JAR on a GitHub Release, and the git history after you “delete” the file.

| Put here | Appears on GitHub? | Use for |
| --- | --- | --- |
| `data/static/pdf/` | No (`data/` is gitignored) | Real しおり, real work PDFs |
| `data/static/images/` | No | Real building photo, real exhibit photos |
| `src/main/resources/pdf/` | **Yes, and inside the JAR** | Only the placeholder `sample.pdf` |
| `src/main/resources/images/` | **Yes, and inside the JAR** | Only placeholders |

On first run the server copies bundled templates into `data/static/` and never overwrites files already on disk. After that it reads the disk, not the JAR. So:

1. Run once.
2. Replace `data/static/pdf/sample.pdf` and `data/static/images/school.png` with your files.
3. Never copy those replacements back into `src/main/resources/`.
4. Before `git push`, run `git status` and look for `.pdf` / student photos.

Optional gate: `pdf.require_auth: true` and a non-empty `pdf.token` make every PDF URL require `?token=...`. That is access control on the running server. It does **not** protect a file you committed.

Photographs of identifiable students are 肖像権. Permission is the school’s problem, not a software licence. If you do not have it, do not publish the picture — not in git, not on the site.

## Requirements

- JDK 21
- That is all for this release. No Postgres, no Redis, no Node.

## Quick start

```sh
git clone https://github.com/serbekun/inahosai_server.git
cd inahosai_server
cp src/main/resources/config.default.yaml config.yaml
```

Edit `config.yaml`. At minimum:

- `school.name_ja`, `festival.name`, `festival.start_date` — required, or every page shows a setup banner
- dates, slogan, concept, works, stream, `site.base_url` (needed for LINE `og:` tags,
  the canonical link and `/sitemap.xml`)
- `site.repo_url` — point it at *your* fork
- `is_setup_readed_and_config_edited: true` — or the process exits

Then:

```sh
./gradlew run                          # production: /setup is not registered
INAHOSAI_ENV=dev ./gradlew run         # development: /setup is a checklist
```

Open [http://127.0.0.1:2323/](http://127.0.0.1:2323/).

From a Release JAR:

```sh
java -jar inahosai_server-pre-alpha-2026-09-10-all.jar
```

From sources:

```sh
./gradlew shadowJar
java -jar build/libs/inahosai_server-pre-alpha-2026-09-10-all.jar
```

Config load order (logged at startup):

1. `-Dinahosai.config=...`
2. `$INAHOSAI_CONFIG`
3. `./config.yaml`
4. bundled `config.default.yaml`

`INAHOSAI_ENV=dev` registers `GET /setup` (key names and set/unset only — never values). `INAHOSAI_ADMIN_TOKEN` registers `POST /api/v0/admin/reload`.

Full key list, graph coordinates, stream ids, PDF tokens: **[`SETUP.md`](SETUP.md)**.

## Layout a fork actually touches

```
config.yaml                 # yours; not in git. copy from config.default.yaml
data/static/                # created on first run; gitignored
  css/ js/ html/            # unpacked templates; edits here survive upgrades
  images/                   # your photos
  pdf/                      # your しおり
src/main/resources/         # upstream placeholders only
  config.default.yaml
  html/ css/ js/ images/ pdf/
```

An upgrade only unpacks files that are *new* in that version. Files already on disk are left alone.

## Licence

- **Code** — MIT. See [`LICENSE`](LICENSE).
- **Content** — not MIT. School names, festival names, copy, photographs and PDFs stay with their owners. Replace them before you publish a fork.
- **Third-party requests** — none, on purpose. If you add a font file, attach its licence next to it (OFL for Noto, etc.).

The footer’s “MIT” note is about the code. It is not configurable, because a fork does not get to relicense the upstream.

---

If you are the person who was asked to make the site: change the config, put documents in `data/`, look at `/setup` in dev, and do not push anything a student would not want on the public internet.
```