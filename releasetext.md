# Pre Alpha Release 2026-08-24

1. Add first version of frontend for Kukizaki scholl.
2. Add resources layer. ResourcesBasePath, ResourceLoader and ResourceCache read files from JAR or from disk, ResourcesService is internal API to resources for other services.
3. Add SiteConfig with YAML loading from config.default.yaml and fork-safe validation. Japanese era is derived from festival start date.
4. Render pages from mustache templates with shared header, footer and other partials. Pre-rendered pages are served at clean routes with ETag revalidation.
5. Add setup banner, /setup page and SETUP.md for forks.
6. Add authenticated config reload without restart on admin route.
7. Add links to scholl homepage and my github user and repo.

---

Build
```bash
./gradlew shadowjar
```

Run

If you just donwload JAR file from GitHub Release use.
```bash
java -jar bunkasai_server-pre-alpha-2026-08-24-all.jar
```
If you build from sources use.
```bash
java -jar builds/libs/bunkasai_server-pre-alpha-2026-08-24-all.jar
```
