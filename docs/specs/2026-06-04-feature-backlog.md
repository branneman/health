# Feature Backlog

Stack-ranked product backlog for the health app. Derived from `docs/ux/`.

## Approach

Continuous delivery in vertical slices — each story is independently shippable
end-to-end (server + local DB + app UI). Stories are ordered so every release gives
the user something immediately usable, however thin. No waterfall gate.

---

## Backlog

| #  | Story                                                                                                                                                       | What it enables                                               |
|----|-------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| 1  | **Walking skeleton** — app installs, bottom nav skeleton, server reachable                                                                                  | CI/CD pipeline proven; every later story ships on top of this |
| 2  | **Login** — sign-in screen calls existing auth endpoint, token stored securely                                                                              | You can open the app                                          |
| 3  | **Onboarding** — biometrics + activity level + target deficit + Connect Polar step (skippable)                                                              | You have a daily calorie budget                               |
| 4  | **Dashboard — daily zone** — estimated calories-out + budget remaining                                                                                      | You can glance at your budget                                 |
| 5  | **Quick-add logging** — log anything as kcal + optional label, offline-first, budget updates                                                                | You can log any food today                                    |
| 6  | **Weight logging** — inline weigh-in field on dashboard                                                                                                     | Morning ritual works                                          |
| 7  | **One-tap meal buttons** — configure usual breakfast / lunch / late snack (kcal-based setup) + one-tap use                                                  | Breakfast and lunch cost one tap                              |
| 8  | **Drink shortcuts** — configure + use one-tap drink buttons on log screen                                                                                   | Drinks logged in one tap                                      |
| 9  | **Polar sync** — OAuth callback + cron pull → real calories-out replaces estimate                                                                           | Budget is real, not guessed                                   |
| 10 | **Log from template** — browse saved templates + portion adjuster (Lighter / Normal / Heavier)                                                              | Dinner costs 2–3 taps                                         |
| 11 | **OFD import pipeline** — weekly sync of NL subset (EU fallback) into `product` table; full-text index; `/food/search` and `/food/barcode` server endpoints | Fast food search with no OFD rate-limit exposure              |
| 12 | **Build from scratch** — ingredient builder UI, calls server food search, barcode scan, save as template into `food_item`                                   | Any new dish can be logged precisely                          |
| 13 | **Past-day view** — browse history, swipe between days, delete entries                                                                                      | Corrections and history visible                               |
| 14 | **Weekly verdict** — on-track / behind / dropping-fast, driven by smoothed weight trend                                                                     | The honest weekly signal                                      |
| 15 | **Weight trend chart** — 7-day smoothed line on dashboard                                                                                                   | Visual progress                                               |
| 16 | **Calorie balance bars** — weekly in/out bars on dashboard                                                                                                  | Over/under pattern visible at a glance                        |
| 17 | **Widget (small)** — verdict dot + budget remaining on homescreen                                                                                           | One-glance status without opening the app                     |
| 18 | **Widget (medium)** — adds weight sparkline + drink shortcuts row to widget                                                                                 | One-tap drinks from homescreen                                |
| 19 | **End-of-day notification** — gentle local reminder when dinner unlogged (~21:00)                                                                           | Forgetting-to-log caught without guilt                        |
| 20 | **Insights** — late-snack frequency, drink clusters, missing-log coverage, calorie-vs-weight drift note                                                     | Behavioural patterns visible without moralising               |
| 21 | **Vacation mode** — pause tracking, exclude period from verdicts and pattern calculations                                                                   | Holidays don't skew the trend                                 |
| 22 | **Maintenance mode** — stability verdict after goal reached, budget switches to balance                                                                     | Success state has a proper mode                               |

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

Stories 1–10 are self-contained. Quick-add is a kcal number; one-tap button setup
is a kcal field; drink shortcuts are emoji + label + kcal. OFD first appears in
story 11 (import pipeline), used by story 12 (build from scratch).

**Polar is early but not first**

Story 9 comes after the basic logging loop (1–8) is usable. The dashboard degrades
gracefully with estimated calories-out (BMR × activity multiplier from onboarding)
until Polar is connected.

**One-tap button templates vs full ingredient templates**

Story 7 sets up usual breakfast/lunch/snack as simple kcal totals — no ingredients,
no OFD. Once story 12 exists, the user can rebuild any template from ingredients for
macro detail. The one-tap button mechanism is the same either way.

**Vertical slices throughout**

Each story ships server + Room/local + app UI together. No separate server-track or
app-track stories. The one exception is story 11 (OFD pipeline) which is pure server
infrastructure, but it directly unblocks story 12 and has no app component.
