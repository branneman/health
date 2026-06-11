# Feature Backlog

Stack-ranked product backlog for the health app. Derived from `docs/ux/`.

Cross-cutting
specs: [API design](api-design.md) · [Math model](math-model.md) · [Security](security.md) (
applies to every story)

## Approach

Continuous delivery in vertical slices — each story is independently shippable
end-to-end (server + local DB + app UI). Stories are ordered so every release gives
the user something immediately usable, however thin. No waterfall gate.

---

## Backlog

| ✓ | #  | Story                                                                                                                                                                           | What it enables                                                      | Spec                                                |
|---|----|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|-----------------------------------------------------|
| ✓ | 1  | **Walking skeleton** — app installs, bottom nav skeleton, server reachable                                                                                                      | CI/CD pipeline proven; every later story ships on top of this        |                                                     |
| ✓ | 2  | **Login** — sign-in screen calls existing auth endpoint, token stored securely                                                                                                  | You can open the app                                                 | [login-design](login-design.md)                     |
| ✓ | 3  | **Persist rate-limit state** — `login_attempts` Postgres table; `RateLimiter` writes through on failure/reset, loads on startup                                                 | Brute-force lockouts survive container restarts                      | [security §known-risks](security.md)                |
| ✓ | 4  | **Multi-user** — V4/V5 DB migrations add `user_id` to all data tables; all API queries scoped per session; Android SyncWorker + login sync; Ansible provisions users from vault | Multiple users; data isolated at DB layer; history restores on login |                                                     |
| ✓ | 5  | **Onboarding** — biometrics + activity level + target deficit + Connect Polar step (skippable)                                                                                  | You have a daily calorie budget                                      |                                                     |
| ✓ | 6  | **Dashboard — daily zone** — estimated calories-out + budget remaining                                                                                                          | You can glance at your budget                                        |                                                     |
|   | 7  | **Quick-add logging** — log anything as kcal + optional label, offline-first, budget updates                                                                                    | You can log any food today                                           |                                                     |
|   | 8  | **Weight logging** — inline weigh-in field on dashboard                                                                                                                         | Morning ritual works                                                 |                                                     |
|   | 9  | **One-tap meal buttons** — configure usual breakfast / lunch / late snack (kcal-based setup) + one-tap use                                                                      | Breakfast and lunch cost one tap                                     |                                                     |
|   | 10 | **Drink shortcuts** — configure + use one-tap drink buttons on log screen                                                                                                       | Drinks logged in one tap                                             |                                                     |
|   | 11 | **Polar sync** — OAuth callback + cron pull → real calories-out replaces estimate                                                                                               | Budget is real, not guessed                                          |                                                     |
|   | 12 | **Log from template** — browse saved templates + portion adjuster (Lighter / Normal / Heavier)                                                                                  | Dinner costs 2–3 taps                                                |                                                     |
|   | 13 | **OFD import pipeline** — weekly sync of NL subset (EU fallback) into `product` table; full-text index; `/food/search` and `/food/barcode` server endpoints                     | Fast food search with no OFD rate-limit exposure                     |                                                     |
|   | 14 | **Build from scratch** — ingredient builder UI, calls server food search, barcode scan, save as template into `food_item`                                                       | Any new dish can be logged precisely                                 |                                                     |
|   | 15 | **Past-day view** — browse history, swipe between days, delete entries                                                                                                          | Corrections and history visible                                      |                                                     |
|   | 16 | **Weekly verdict** — on-track / behind / dropping-fast, driven by smoothed weight trend                                                                                         | The honest weekly signal                                             |                                                     |
|   | 17 | **Weight trend chart** — 7-day smoothed line on dashboard                                                                                                                       | Visual progress                                                      |                                                     |
|   | 18 | **Calorie balance bars** — weekly in/out bars on dashboard                                                                                                                      | Over/under pattern visible at a glance                               |                                                     |
|   | 19 | **Widget (small)** — verdict dot + budget remaining on homescreen                                                                                                               | One-glance status without opening the app                            |                                                     |
|   | 20 | **Widget (medium)** — adds weight sparkline + drink shortcuts row to widget                                                                                                     | One-tap drinks from homescreen                                       |                                                     |
|   | 21 | **Auto update** — server serves version manifest + APK; app checks on launch, downloads via DownloadManager, installs via system sheet; deploy script                           | Ship a new build and the app updates itself next launch              | [auto-update](auto-update.md)                       |
|   | 22 | **End-of-day notification** — gentle local reminder when dinner unlogged (~21:00)                                                                                               | Forgetting-to-log caught without guilt                               |                                                     |
|   | 23 | **Insights** — late-snack frequency, drink clusters, missing-log coverage, calorie-vs-weight drift note                                                                         | Behavioural patterns visible without moralising                      |                                                     |
|   | 24 | **Vacation mode** — pause tracking, exclude period from verdicts and pattern calculations                                                                                       | Holidays don't skew the trend                                        |                                                     |
|   | 25 | **Maintenance mode** — stability verdict after goal reached, budget switches to balance                                                                                         | Success state has a proper mode                                      |                                                     |

---

## Key decisions

**Food data — two tables, two jobs**

- `product` — OFD mirror. Large, server-only. Weekly sync: NL subset primary, EU
  as fallback. Queried for search and barcode lookup via server endpoints. Users
  never directly own these rows.
- `food_item` — user's personal food catalog. Items actually eaten: picked from OFD
  search, scanned, or created manually. Small, synced to device. Referenced by
  templates; nutrition values snapshotted into `log_entry_item` at log time.

OFD is queried via the server's own endpoints, never directly from the app — no
per-search rate-limit exposure and fast autocomplete via Postgres full-text index.

**Core loop needs no OFD**

Stories 1–11 are self-contained. Quick-add is a kcal number; one-tap button setup
is a kcal field; drink shortcuts are emoji + label + kcal. OFD first appears in
story 13 (import pipeline), used by story 14 (build from scratch).

**Polar is early but not first**

Story 11 comes after the basic logging loop (1–10) is usable. The dashboard degrades
gracefully with estimated calories-out (BMR × activity multiplier from onboarding)
until Polar is connected.

**One-tap button templates vs full ingredient templates**

Story 9 sets up usual breakfast/lunch/snack as simple kcal totals — no ingredients,
no OFD. Once story 13 exists, the user can rebuild any template from ingredients for
macro detail. The one-tap button mechanism is the same either way.

**Multi-user before onboarding**

Story 4 (multi-user) comes before story 5 (onboarding) because onboarding creates
`user_profile`, which depends on the `user_id` FK introduced in the V4/V5 migrations.
Building onboarding without multi-user in place would require an immediate backfill.
Story 3 (rate-limit persistence) comes first: it is server-only, quick, and becomes
more important once multiple users exist.

**Vertical slices throughout**

Each story ships server + Room/local + app UI together. No separate server-track or
app-track stories. The one exception is story 13 (OFD pipeline) which is pure server
infrastructure, but it directly unblocks story 14 and has no app component.
