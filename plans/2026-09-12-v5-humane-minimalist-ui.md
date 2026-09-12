# v5: Humane Minimalist Dark redesign (inspired by the Stitch export)

Date: 2026-09-12. Source of inspiration: `H:/IQOO Hackathon/stitch_minimalist_human_ui_redesign/
stitch_minimalist_human_ui_redesign/` (`humane_minimalist_dark/DESIGN.md` + four screens:
federated_network_topology, connected_devices_sync, sync_model_weights,
performance_training_analytics). Inspiration only: our information architecture, data, and the
spectrogram heatmap stay; every Stitch "Heatmap View Placeholder" becomes a real matrix from our
state. Binding for agents F1-F3.

## Design Read and dials

Reading this as: on-device industrial monitoring product UI for maintenance technicians and
hackathon judges, with a humane minimalist dark language (warm-neutral midnight canvas, tonal
layering instead of borders, a single crimson accent used sparingly, Work Sans), leaning toward
Material 3 Compose re-tokened to that system.

`DESIGN_VARIANCE 4`, `MOTION_INTENSITY 3` (state transitions; the existing spectrogram sweep on a
live reading and the sparkline draw-in stay; a donut ring animates in once; nothing else moves;
animator-scale 0 disables all of it), `VISUAL_DENSITY 4` (mini stat cards, generous vertical
rhythm, whitespace instead of dividers).

Transferred anti-tells: no pure black (#07080a canvas), no glows/shadows except the floating
sheet's ambient shadow, no em/en dashes in strings, dots only for real state (Online / Active /
event type), one accent (crimson) used for primary actions, active nav and critical notices only,
one radius rule (16 dp cards, 8 dp interactive, 6 dp tags), empty/loading/error states on every
list, one icon family (material-icons-extended), every animation motivated.

## Tokens (replace the Fiori Horizon values; keep the object and composable NAMES so nothing
## outside the theme/common packages has to change)

```
Canvas            #07080A     Raised surface        #111317     Floating sheet   #181B20
Input background  #0C0E12     Hairline              #FFFFFF @ 0.06   Row highlight  #FFFFFF @ 0.02
Text primary      #FFFFFF     Text secondary        #9CA3AF     Text muted       #64748B
Accent (primary)  #C30000     Accent pressed        #A60000     On accent        #FFFFFF
Positive          #48BB78     Critical (warning)    #E0A437     Negative         #FFB4AB (text) / #93000A (container)
Informative       #B8C4FF     Neutral chip          #FFFFFF @ 0.06 with text #D1D5DB
Status tint       colour @ 0.14 over the raised surface for chips
Radius            cards 16 dp, buttons/inputs 8 dp, tags/chips 6 dp
Type              Work Sans (bundled variable TTF, OFL), weights 400/500/600; headline 22-24 sp 600,
                  title 18 sp 500, body 15 sp 400, label 13-14 sp 500, mono numerals off
                  (tabular numerals stay on via FontFeatureSettings "tnum")
Spacing           screen margin 20 dp, card padding 20 dp, card gap 16 dp, section gap 28 dp
```

`FioriColors` keeps its name and members (`Background, Surface, SurfaceElevated, Hairline,
TextPrimary, TextSecondary, TextDisabled, Brand, Positive, Critical, Negative, Neutral, of()`)
with the new values (`Brand` = accent crimson, `SurfaceElevated` = floating sheet, `TextDisabled`
= text muted). Buttons: `PrimaryButton` (solid crimson, full width where the screen has one main
action), `GhostButton` (raised fill + hairline), text buttons for tertiary actions.

## Components (`ui/common/fiori/`, additive; existing composables re-skinned in place)

```kotlin
@Composable fun MiniStatCard(label: String, value: String, sub: String? = null, subSemantic: Semantic = NEUTRAL, modifier: Modifier = Modifier)   // the Stitch "Host Address / 192.168.49.1" cell
@Composable fun StatusChip(text: String, semantic: Semantic, dot: Boolean = true)                        // "Mesh Active"
@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null)
@Composable fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null)
@Composable fun SegmentBar(parts: List<Pair<Float, Semantic>>, modifier: Modifier = Modifier)           // 3-class distribution bar, crimson only for the Negative part
@Composable fun DonutRing(fraction: Float, label: String, value: String, modifier: Modifier = Modifier) // 83.3% Accuracy ring, animates once
@Composable fun MatrixHeatmap(rows: List<String>, cols: List<String>, values: List<List<Float?>>, modifier: Modifier = Modifier, valueFormat: (Float) -> String = { "%.0f".format(it * 100) }) // tonal cells: null = empty raised cell, else Positive tint scaled by value, Negative tint below 0.5; tabular labels
@Composable fun FeedRow(dotSemantic: Semantic, title: String, meta: String)                             // activity feed row
@Composable fun SectionTitle(title: String, trailing: String? = null)                                    // "Connected Devices   2 active devices"
@Composable fun BottomNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit)                 // 4 items, active = crimson icon+label
data class NavItem(val label: String, val icon: ImageVector)
```

Existing `FioriKpiTile`, `FioriObjectCell`, `FioriStatusLabel`, `FioriSectionHeader`,
`FioriKeyValueRow`, `FioriEmptyState`, `FioriBanner`, `HorizontalMeter`, `ConfusionGrid`,
`Sparkline`, `StatusPill`, `BackendIndicator`, `Widgets.kt` are re-skinned to the tokens with the
same signatures (KpiTile becomes a raised 16 dp card with label above value; ObjectCell loses its
hairline in favour of 16 dp raised cards with 12 dp gaps; meters use a crimson fill only for the
champion, neutral otherwise).

## Federated area: bottom-nav shell (matches the four Stitch screens)

Entered from the Equipment list (hub icon). `FederatedShell(onBack)` = Scaffold with a top bar
(back arrow, title, refresh icon) and `BottomNav` with **Network, Devices, Sync, Performance**;
one `NetworkViewModel` shared by all four (it already holds every state these screens need).
Content mapping (all real data, no placeholders):

- **Network** (topology): status card "Local mesh group" with `StatusChip` (Mesh active / Not
  connected), four `MiniStatCard`s (Owner address, Role, Nodes online, Round), "Auto-sync" switch
  (scheduler), `PrimaryButton` Discover peers, `GhostButton` Group settings; card **"Latest
  reading"** = the product's own spectrogram heatmap (`SpectrogramImage` of the newest diagnosis
  across all equipment, with equipment name, relative time and a `StatusChip`; empty state when no
  reading exists) — the founder's explicit replacement for the Stitch "Packet Transmission Matrix"
  placeholder, fed by a derived `NetworkViewModel.latestReading` that scans `assetRepository` +
  `diagnosisRepository.getLatest` and decodes the PNG the way `ResultViewModel` does; then, after
  the Connected devices section, card "Node by variant"
  = `MatrixHeatmap(rows = node names, cols = variant labels, values = held-out acc per node/variant
  where known; champion column first)`; section "Connected devices" listing `NodeCard`s as raised
  cards (name, mode + challenger, two mini stats: held-out accuracy, round; chip Owner / Client /
  Stale; footer line "Synced <relative>").
- **Devices** (connected): header card with `StatusChip` (Synchronized and ready / Syncing /
  Not connected), "N active devices", sync status progress = samples shared this session; peers
  list (WiFi Direct peers with Connect); "Dataset overview" `SegmentBar` of own labelled counts
  by class (Healthy positive, Rotor imbalance critical, Airflow obstruction negative) with counts;
  "Peer sample matrix" `MatrixHeatmap(rows = origins, cols = classes, values = counts normalised)`
  from `pool.countByOrigin()`; "Automatic background sync" switch; `PrimaryButton` Sync now
  (client) or Start sync service (owner).
- **Sync** (model weights): status banner (last sync message), "Active model" card: champion
  label, three mini stats (Held-out accuracy, Loss, Last trained), `PrimaryButton` Train now;
  "Local records" `SegmentBar` + percentages; "What the champion listens to" =
  `MatrixHeatmap(rows = ["mean", "std"], cols = band groups + sensors, values = importance share)`;
  "Pipeline controls": Auto-train switch, Auto-sync after training switch, Data sharing switch,
  `PrimaryButton` Sync now.
- **Performance** (training analytics): `DonutRing` champion held-out accuracy; four mini stats
  (Champion, Best challenger with delta, Training loss with delta vs previous round, Nodes online);
  "Model architectures" = ranking list rows: label, chip (Champion / Candidate n/2 / Trailing / No
  data), accuracy, `HorizontalMeter` (crimson only for champion), sub line description + loss;
  "Training activity matrix" = `MatrixHeatmap(rows = variants, cols = last 8 rounds, values =
  standings history)`; "Live activity feed" = last 10 events as `FeedRow` (PROMOTE crimson dot,
  RECOVER/FAILOVER critical, others neutral); "This device" confusion grid stays.
- **Group settings** (screen reached from Network's ghost button, floating sheet tone): identity
  (name, mode, challenger picker), Configuration (effective values with source, reset overrides),
  Resilience (role, failures, recovery report, Promote this node), Leave group.

## Equipment screens (re-skin only, same behaviour)

Equipment list: raised cards per equipment with name, machine type, last status `StatusChip`,
"New equipment" `PrimaryButton`; empty state. Create equipment: Stitch input styling, machine-type
search results as raised rows. Equipment detail: header (name, type), three mini stats (Last
reading, Thresholds T1/T2, Calibration), `PrimaryButton` Take reading, ghost buttons for
Reference measurement / Pre-check / History, calibration card with drift banner. Baseline and
Diagnose: the live waveform and the **spectrogram heatmap stay exactly as the visual centrepiece**
inside a 16 dp card; countdown and status in the new type. Result: status header with the big
status chip and score, the spectrogram heatmap card (kept), Likely issues as raised cards with
keyword chips, Confirm label chips, notification proposal card, share button ghost. History:
borderless rows with status chips and small heatmap thumbnails (kept).

## File ownership

| Agent | Owns |
|---|---|
| F1 | `ui/theme/**`, `ui/common/**` (all), `app/src/main/res/font/**` (new, Work Sans variable TTF from Google Fonts' GitHub `ofl/worksans/WorkSans[wght].ttf`, OFL licence file alongside; if the download fails, define the family with the system sans and report it), `MainActivity.kt` (permission gate styling only) |
| F2 | `ui/network/**` (becomes the shell + four screens + group settings; keep `NetworkViewModel`), `ui/nav/**` |
| F3 | `ui/assets/**`, `ui/createasset/**`, `ui/assetdetail/**`, `ui/baseline/**`, `ui/checklist/**`, `ui/diagnose/**`, `ui/result/**`, `ui/history/**` |

F2 and F3 code against the component contract above while F1 builds it; nobody edits `fl/**`,
`p2p/**`, `domain/**`, `ml/**`, `data/**`, `di/**`.

## Result (2026-09-12, 16:58)

F1-F3 landed (Work Sans 361 KB bundled; tokens; 11 new components; federated shell with four
tabs + Group settings; eight equipment screens re-skinned; the "Latest reading" spectrogram card
on Network). Build green, 0 compile errors after two opt-ins (`ExperimentalLayoutApi` for
`FlowRow`, `ExperimentalTextApi` for the variable font); 160/160 JVM tests; UI-string scan: 0
em/en dashes, 0 hard-coded colours outside the token files. APK sha256 prefix `e3da48de1e43ae48`
staged at `H:/IQOO Hackathon/jugaad-agent-debug.apk`; phone install and screenshot pass follow the
v4 on-device verification (which is using the phones now).

## Result, defect pass (2026-09-12, 18:2x)

E7 on-device pass (sha `267a572d3672292a`) found 13 defects; agent I1 fixed them in 12 files:
bench equipment hides the threshold/calibration card; Pre-check has one screen-level scroll
state; detail reloads on resume (`LifecycleResumeEffect`); status chips, nav labels and button
labels are single-line; `Format.syncStatus` gives Devices and Network one honest status
(Sync failed / Serving / Group formed, not serving / Synchronized / Connected / Not connected);
Sync tab uses `valAccTransition` and a full-width "Held-out accuracy" card; `MetricRow` values
are right-aligned with weight; Performance lists all eight variants with "No data" when the
standings are empty. Matrix heatmap already scrolled horizontally (no change). Build green,
165/165 JVM tests, APK sha `acef994390dc5ece`. On-device confirmation: E8 (see HANDOFF.md).

## Verification

Build green, unit tests unchanged; screenshots of all four federated screens, group settings,
equipment list, detail, result (with heatmap) and history on a phone in portrait and the
Performance screen in landscape; the transferred pre-flight items above checked by grep (dashes,
hard-coded colours outside theme/common) and by eye on the screenshots.
