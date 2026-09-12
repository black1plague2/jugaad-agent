# Public dataset survey — offline pretraining source (C0)

Date: 2026-09-12. Written for plan `plans/2026-09-12-sensors-strategies-datasets.md`,
Decisions item 6. Target classes: `[Healthy, Rotor Imbalance, Airflow Obstruction]`.

Selection rule (from the plan): real labels mapping onto our three classes, microphone
channel present, permissive licence, total download <= 3 GB, direct URLs that work
without login if at all possible.

All URLs below were HEAD/GET-verified on 2026-09-12 (dates in output are server clock,
which reads 2026 on this network — see raw responses); "size" is the verified
`Content-Length`, not a page-quoted figure, except where noted as unverified.

## Recommendation

**(a) Primary pick: MAFAULDA `normal` + `imbalance` subsets.**

| subset | maps to | URL | verified size | login |
|---|---|---|---|---|
| normal.zip | Healthy | `https://www02.smt.ufrj.br/~offshore/mfs/database/mafaulda/normal.zip` | 325,307,137 B (0.30 GB) | none |
| imbalance.zip | Rotor Imbalance | `https://www02.smt.ufrj.br/~offshore/mfs/database/mafaulda/imbalance.zip` | 2,212,894,986 B (2.06 GiB / 2.54 GB decimal) | none |
| **total** | | | **~2.36 GiB** | fits the 3 GB budget |

Expected file counts: 49 sequences for Healthy, 333 sequences for Rotor Imbalance (6-35 g
imbalance masses). Each sequence is 5 s of synchronized microphone + two triaxial
accelerometers + tachometer at 50 kHz — resample to 44.1 kHz mono for the mic channel
and feed the accelerometer channels through the phone's accel-index algorithm (dataset
has real accelerometer data, so this is not synthetic). Landing page:
`https://www02.smt.ufrj.br/~offshore/mfs/page_01.html`.

**(b) No source gives per-file "clogging"/airflow labels within budget.** MIMII's fan
machine type does induce clogging as one of several fault conditions ("unbalanced,
voltage change, clogging, wing damage" per the original paper), but every fault type for
a given machine ID is pooled into one `abnormal/` folder with no per-file fault-subtype
label — confirmed against the MIMII paper (arXiv:1909.09347), the MIMII DG paper
(arXiv:2205.13879), and the dataset's own directory-structure documentation
(`<dB>_dB_<machine>/<machine>/id_XX/{normal,abnormal}/*.wav`). There is no way to isolate
"clogging-caused abnormal" clips from "unbalance-caused abnormal" or "voltage-caused
abnormal" clips without manual re-labelling by ear. Per the plan's fallback rule,
**Airflow Obstruction stays synthetic-only**; say so in `ml/data/README.md`.

A 2026 Data-in-Brief paper, "Multi-session, multi-device acoustic dataset for progressive
tool degradation monitoring" (PMC: `https://pmc.ncbi.nlm.nih.gov/articles/PMC13264106/`,
verified 200 OK; ScienceDirect DOI page returned 403 to bots but the PMC mirror confirms
it exists), describes a vacuum-cleaner subset with **discrete per-file air-filter-clogging
labels (0/30/50/70/100% occlusion)**, mono 48 kHz, smartphone-recorded — a much closer
per-file airflow-obstruction match than MIMII in principle. I could not locate or verify
the actual data-repository URL (Mendeley Data / Zenodo landing page) in this session — the
article pages only describe the dataset, and search did not surface the underlying DOI.
**Do not treat this as a verified download; it is a lead for a follow-up session**, not
part of the recommended <=3 GB pull.

**(c) Fallback if the MAFAULDA URLs go dead:** the same files are mirrored (per multiple
sources) as a Kaggle dataset search-named "Machinery Fault Dataset" — requires a Kaggle
login/API key, which is why it's the fallback and not the primary. If the UFRJ host is
down, retry `https://www02.smt.ufrj.br/~offshore/mfs/page_01.html` first (single small
apache/nginx host, no CDN) before falling back to Kaggle.

## Dataset-by-dataset survey

### MAFAULDA (Machinery Fault Database, UFRJ) — PRIMARY PICK
- Machines/faults: SpectraQuest MFS-ABVT rig; 6 states — normal (49), imbalance (333,
  6-35 g), horizontal misalignment (197), vertical misalignment (301), underhang bearing
  (558, outer/ball/cage), overhang bearing (513, outer/ball/cage). 1,951 sequences total.
- Per-file fault label: yes, one label per sequence (folder = class), unambiguous.
- Channels: microphone (1 ch) + 2x triaxial accelerometer (underhang + overhang bearings,
  6 ch) + tachometer (1 ch) = 8 channels, all sampled together at 50 kHz for 5 s
  (250,000 samples/channel/file).
- Licence: no explicit licence text on the official page or elsewhere I could find;
  long-standing (since ~2007) free academic dataset, no paywall, no login, contact email
  given (felipe.ribeiro@smt.ufrj.br) for questions. Treat as "free to use, cite the
  associated papers" rather than a named OSI licence — flag this to the founder if strict
  licence text is a hard requirement for the shipped product.
- Total size: 13.0 GB for all 6 classes (verified `full.zip` listed at that size on the
  landing page, not independently HEAD-checked since we don't need it); normal+imbalance
  verified at 2.36 GiB combined (see table above).
- URLs: landing `https://www02.smt.ufrj.br/~offshore/mfs/page_01.html` (verified 200 OK);
  direct zips verified above. No login.
- Mapping to our classes: excellent for Healthy and Rotor Imbalance (exact semantic
  match, real accelerometer data too). No airflow/clogging class in this dataset at all.

### MIMII (Hitachi, Zenodo record 3384388)
- Machines/faults: fan, pump, slider (slide rail), valve; 4 product IDs each. Fan
  abnormal causes include unbalance, voltage change, clogging, wing damage (per paper);
  pump/valve/slider have their own fault sets (contamination, leakage, rail damage, etc).
- Per-file fault label: **binary only** (normal vs abnormal per file); fault subtype is
  not resolvable per file — see (b) above. This is the reason MIMII fan is NOT usable as
  an Airflow Obstruction source despite "clogging" appearing in the machine's fault list.
- Channels: 8-channel microphone array, 16 kHz, 16-bit, ~10 s clips (channel 0 commonly
  used as the single-mic reference). No accelerometer/IMU channel.
- Licence: CC BY-SA 4.0 (permissive, commercial use OK with attribution + share-alike).
- Total size: ~100+ GB across 12 zips (4 machines x 3 SNR levels: -6/0/6 dB); a single
  machine/SNR zip alone already exceeds the 3 GB budget (verified `-6_dB_fan.zip` =
  10,878,096,548 B = 10.9 GB).
- URLs: landing `https://zenodo.org/records/3384388` (verified 200 OK); file example
  `https://zenodo.org/records/3384388/files/-6_dB_fan.zip?download=1` (verified 200 OK,
  size above). No login.
- Mapping: fan/pump plausible "abnormal ~ some fault" but not per-fault-type, and every
  file exceeds our whole budget by itself — excluded on both label-granularity and size
  grounds.

### MIMII DUE / MIMII DG (later, domain-shift variants)
- MIMII DG (Zenodo 6529888): bearing, fan, gearbox, slider, valve; ~4.4 GB total across 5
  machine zips (fan alone 928.5 MB per page). Licence CC BY-NC-SA 4.0 (non-commercial —
  fails the permissive-licence rule). Same binary normal/abnormal granularity as MIMII;
  the "domain shift" attributes encoded in filenames are recording conditions (RPM,
  environment), not fault subtype. Not usable for per-file clogging labels either.
- MIMII DUE (Zenodo 4740355): similar structure/licence, not separately re-verified since
  it fails the same licence + labelling criteria as MIMII DG.

### DCASE Task 2 development sets (2020-2023)
- 2020 (Zenodo 3678171, verified 200 OK): ToyCar, ToyConveyor (from ToyADMOS), fan, pump,
  slider, valve (from MIMII). 16 kHz, single channel, ~10 s clips. Verified sizes: total
  ~8.1 GB across 6 zips; `dev_data_fan.zip` = 1,354,774,772 B (1.35 GB) verified.
- 2021 (Zenodo 4562016, verified 200 OK/title-checked): fan, gearbox, pump, slider,
  ToyCar, ToyTrain, valve; ~7.5 GB across 7 zips.
- 2022 (Zenodo 6355122, verified 200 OK/title-checked): fan, gearbox, bearing, slider,
  ToyCar, ToyTrain, valve; ~6.2 GB across 7 zips (0.77-0.98 GB each).
- 2023 (Zenodo 7690148, verified 200 OK/title-checked): same 7 machine types; page-quoted
  total size looked implausible (parsed as hundreds of GB) and is **not** reported here —
  treat the 2021/2022 per-machine sizes (~0.7-1.4 GB per zip) as the reliable estimate for
  2023 too rather than trusting that figure.
- Licence (all years): CC BY-NC-SA 4.0 — **non-commercial**, fails the plan's
  permissive-licence rule regardless of label quality.
- Label granularity: same binary normal/abnormal as MIMII/ToyADMOS underneath (DCASE adds
  no new fault-subtype labels). No per-file clogging/imbalance-specific label beyond what
  MIMII/MAFAULDA already provide.
- Mapping: poor — toy car/conveyor/train have no imbalance/airflow semantics; fan/valve
  inherit MIMII's label-granularity problem; licence excludes commercial use outright.
  Excluded.

### ToyADMOS / ToyADMOS2
- ToyADMOS2 (Zenodo 4580270, GitHub `nttcslab/ToyADMOS2-dataset`): miniature toy-car and
  toy-train fault diagnosis under domain shift. Licence stated only as "see LICENSE.pdf"
  in the repo — not independently confirmed permissive.
- Mapping: toy-car/toy-train faults (wheel wear, gear damage) do not correspond to rotor
  imbalance or airflow obstruction on a real machine housing. Deprioritized — not
  investigated further given the poor semantic fit even before checking licence/size.

### CWRU bearing dataset (Case Western Reserve University)
- Machines/faults: single induction motor, inner race / outer race / ball bearing faults
  at 7/14/21/28 mil fault diameters, multiple loads and speeds.
- Channels: **accelerometer only** (drive-end and fan-end), 12 kHz and 48 kHz sampling.
  **No microphone channel** — fails the plan's hard requirement on its own.
- Licence: public, no explicit named licence; typically cited as free for research.
- URL: `https://engineering.case.edu/bearingdatacenter/download-data-file` (verified 200
  OK, no login for the page itself; individual file downloads not further checked since
  the dataset is excluded on the no-microphone rule).
- Mapping: bearing faults are not one of our three classes at all (Healthy / Rotor
  Imbalance / Airflow Obstruction) and there's no mic channel. Excluded.

### IDMT-ISA-Electric-Engine (Fraunhofer IDMT)
- Machines/faults: 3 units of a small brushless DC electric engine; states "good",
  "heavy load", "broken" (774 / 815 / 789 recordings respectively, 2,378 files total,
  42.32 min combined).
- Per-file label: yes, one state label per file.
- Channels: **microphone only, mono, 44.1 kHz, 32-bit** — the only surveyed dataset that
  already matches the phone's native sample rate exactly. No accelerometer/IMU channel.
- Licence: **CC BY-NC-ND 4.0** — non-commercial AND no-derivatives. This is the most
  restrictive licence in the survey: no-derivatives arguably blocks training a derived
  model on it at all, not just commercial use. Fails the permissive-licence rule twice
  over; do not use for the shipped product without contacting Fraunhofer IDMT for a
  separate licence.
- Total size: verified 1,527,246,272 B (1.53 GB), single zip, no login.
- URL: `https://zenodo.org/records/7551261` (verified 200 OK); file
  `https://zenodo.org/records/7551261/files/IDMT-ISA-ELECTRIC-ENGINE.zip?download=1`
  (verified 200 OK, size above).
- Mapping: "good/heavy load/broken" isn't rotor imbalance or airflow obstruction either —
  and the licence rules it out regardless. Excluded.

## Verified URLs (HEAD/GET, 2026-09-12)

| URL | result |
|---|---|
| `https://www02.smt.ufrj.br/~offshore/mfs/page_01.html` | 200 OK |
| `https://www02.smt.ufrj.br/~offshore/mfs/database/mafaulda/normal.zip` | 200 OK, 325,307,137 B |
| `https://www02.smt.ufrj.br/~offshore/mfs/database/mafaulda/imbalance.zip` | 200 OK, 2,212,894,986 B |
| `https://zenodo.org/records/3384388` | 200 OK |
| `https://zenodo.org/records/3384388/files/-6_dB_fan.zip?download=1` | 200 OK, 10,878,096,548 B |
| `https://zenodo.org/records/3678171` (DCASE 2020) | 200 OK |
| `https://zenodo.org/records/3678171/files/dev_data_fan.zip?download=1` | 200 OK, 1,354,774,772 B |
| `https://zenodo.org/records/4562016` (DCASE 2021) | 200 OK |
| `https://zenodo.org/records/6355122` (DCASE 2022) | 200 OK |
| `https://zenodo.org/records/7690148` (DCASE 2023) | 200 OK |
| `https://zenodo.org/records/6529888` (MIMII DG) | 200 OK |
| `https://zenodo.org/records/7551261` (IDMT-ISA-Electric-Engine) | 200 OK |
| `https://zenodo.org/records/7551261/files/IDMT-ISA-ELECTRIC-ENGINE.zip?download=1` | 200 OK, 1,527,246,272 B |
| `https://engineering.case.edu/bearingdatacenter/download-data-file` (CWRU) | 200 OK |
| `https://pmc.ncbi.nlm.nih.gov/articles/PMC13264106/` (tool-degradation paper) | 200 OK (bot-blocked ScienceDirect mirror: 403) |

## Caveat log

- MAFAULDA has no explicit machine-readable licence text; treated as free academic-use
  data by long-standing convention, not a confirmed permissive OSI licence.
- No dataset in this survey provides a verified per-file Airflow Obstruction / clogging
  label inside the 3 GB budget. Airflow Obstruction training data stays synthetic per the
  plan's fallback; the tool-degradation paper in (b) is a lead, not a resolved source.
- MAFAULDA sample rate (50 kHz) and clip length (5 s) both need conversion in the pipeline
  (resample to 44.1 kHz mono, cut to 3 s) before feature extraction.
