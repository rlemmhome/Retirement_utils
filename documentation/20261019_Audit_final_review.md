# Stage 9 — Final Coverage & Acceptance Review

**Review date:** October 19, 2026 (America/Phoenix).  
**Disposition:** **ACCEPTED WITH DOCUMENTED LIMITATIONS — Stage 9 closed.**  
**Latest uploaded source:** `RiskBased_OptSocSec_v1 (2).java`, build stamp `Monday, October 19, 2026 at 02:03 PM MST (UTC-7)`. SHA-256 `abd39c082e9b14b1dc39655f05083f38123a62bd9187f34b4c1d7a327789c69d`.

## This review's new execution

1. Rechecked existing accepted reports **and final execution-log result lines** using `verify_evidence.py`. The evidence audit exited successfully: `FINAL REVIEW EVIDENCE PASS: primary checks 15501 scenarios 103 observer checks 980 before-after row comparisons 9` (see `evidence_review.log`).
2. Compiled a SHA-256-identical package-consistent copy of the **latest full uploaded source** using OpenJDK `javac 21.0.12.1`. Compile passed; 50 `.class` files generated (`compile.log`, `source_integrity.log`). No Java source changes made.
3. Inspected the actual diff between build `(1)` and `(2)`: the dated title/header; the optimizer scorer change **18 → 19** (and new comments); and **Pro PoS medical indexing changed from `yearsDrawing` to `y`** (with updated comment). There are no other diff hunks. This carries Area 1/2 accepted behavior on build `(1)` through to build `(2)` at the source-code level; it is **not** a fresh exhaustive rerun of all earlier numerical tests on build `(2)`.
4. No additional source-defined behavioral regression was warranted after crediting the targeted existing suites. This is a **final acceptance review**, not a new 15,501-assertion execution; the assertions were executed earlier in Stage 9 and their logs were reverified now.

## Executed and accepted coverage

| Area and evidence | Acceptance scope | Scenarios | Primary checks | Final status |
|---|---|---:|---:|---|
| 1: B1 `stage9_b1_verify/test_report.md`, `execution_original.log` | FIRST_CLAIM/SIM_START/NONE; before-withdrawal bridge clipping; RMD balance **and Money Market reserve**; January/July start; man/woman claims later | 26 | 1,844 | PASS |
| 1: `execution_instrumented.log` | Passive Money Market observer; 2,824 total checks **inclusive** of 1,844 originals; 980 additional independent reserve comparisons | same 26 | +980 observer checks | PASS |
| 2: `stage9_rmd_irmaa_verify/test_report.md`, `execution.log` | Pre-withdrawal RMD and taxable other income → MAGI → 2-year IRMAA; cash reserve interest; filing status; payment-year thresholds; enrollee count | 11 | 626 | PASS |
| 3: `stage9_gk_survivor_verify/test_report.md`, `stage9_m2_verify/gk_regression.log` | GK spending phases, PMR/CPR/PR guardrails, RMD, both survivor transitions, zero Traditional, July start, flat GK tax | 16 | 7,165 | PASS; successfully re-run on build `(2)` |
| 3: `stage9_m2_verify/test_report.md`, `execution.log` | Pro↔GK Medical simulation-start indexing, prior/after comparison, July and survivor cases, spending/surplus reconciliation | 8 | 1,198 | PASS |
| 4: `stage9_state_survivor_verify/test_report.md`, `execution_clean.log` | Colorado, Idaho, Oklahoma, Florida and Nevada survivor state-tax arithmetic, converted amounts, age-65 boundary, no-death controls | 30 | 3,852 | PASS |
| 4 supplement: `streams_execution_clean.log` | CO Social Security/pension deductions and OK qualified/nonqualified annuity, joint/survivor transitions | 12 | 816 | PASS |
| **Distinct primary suite entries** | Not necessarily disjoint financial concepts | **103** | **15,501** | **PASS** |

**Additional evidence (not double counted):** 980 passive observer checks, nine M2 before/after row comparisons, and a repeated GK 7,165-assertion pass on latest build. The Stage 8 FIRST_CLAIM regression passed **2,152 checks** in `test_report.md` (Stage 8, Area 3, Item 1, Library); it is acknowledged but **not counted** as Stage 9 execution.

### Source-defined invariants directly supported by the latest Java file

- `RiskBased_OptSocSec_v1 (2).java`, lines 11024–11036: **"the window starts no earlier than the withdrawal-start month"**; `gapY` and `gapM` clip FIRST_CLAIM to that start while `refY/refM` continue defining the earlier claim date.
- Lines 10368–10380: **"the IRMAA test two years later looks back to it"** and `magiHistory[y] = magiPre;` — pre-withdrawal RMD/streams/interest are accounted for in MAGI history.
- Lines 10166–10175: **"Indexed from the SIMULATION-start year, like living"** and `Math.pow(1 + inp.medInflation, y)` — M2 aligns Pro medical indexing with GK.
- Lines 11401–11417: `isSurvivorYear(inp, calYear) ? TaxEngine.FilingStatus.SINGLE : inp.filingStatus` and **"the filer is the SURVIVOR"** — survivor filing and age-65 deduction transition.
- Lines 5009–5016: **"Pro PoS Medical grows from the simulation-start year, like Living and the GK tab (M2)"** and `SCORER_VERSION = 19` — the scorer-version invalidation mechanism is updated in source.
- Lines 12191–12205: **"This tab always uses the flat tax model"** and **"It does not compute federal brackets, Arizona tax, Social Security taxability, IRMAA, or the Single-filer switch"** — deliberate GK limitation, not a test failure.

## Findings and resolution

1. **B1 defect: resolved.** The initial Stage 9 probe had reproduced FIRST_CLAIM bridge charging before withdrawals started. B1 now clips the bridge to withdrawal start. Actual compiled application tests verified portfolio and Money Market accounting, including overlapping bridge/RMD years.
2. **Medical inflation origin discrepancy: resolved in Pro PoS (M2).** The user confirmed Living/Medical input values are in **simulation-start-year dollars**. The original GK calculation was correct. The user-submitted revision changed the Pro PoS calculation; eight new scenarios and the complete 16-scenario GK regression passed.
3. **RMD/MAGI/IRMAA and five-state survivor tax interactions: accepted.** Independently authored expected-value oracles were used for the tested deterministic configurations, not just calls to the same tax routines as the application.
4. **No remaining blocking defect in Stage 9's four areas** was established by source review or executed regression. This statement is limited to the specifically described tests and does not assert universal software correctness.

## Documented limits and deferred topics

- Full UI click-throughs were **not** run. Tests invoked the actual numerical Java methods headlessly.
- The numerical tests relied on controlled synthetic inputs, often zero stochastic variance or 0% expected market return; they do **not** validate market distributions or probability calibration. Later source changes are not a guarantee against every untested parameter combination.
- Legal/statutory correctness of tax tables was **not independently tested** against external law. The source itself explicitly marks matters **unverified**: Colorado Roth-conversion pension subtraction (line 13227), Idaho 2026 zero bracket (13254), and Oklahoma Roth-conversion exclusion (13285). These should be handled separately from code-arithmetic acceptance.
- The SS Optimizer's scorer-version bump to 19 was reviewed in source, but a full optimizer batch/persistence/migration test was **not** executed in Stage 9. Batch-related behavior is consequently not certified by this acceptance.
- State-specific scenarios beyond Area 4's narrow matrix (such as Custom flat-state flags, live rule-table editing, and stochastic tax-year inflation indexing) were **not** fully integrated-tested in Stage 9. Not a failure of the four planned areas; possible future audit topics if they become relevant.
- GK remains a deliberately distinct, simpler calculation: flat tax and display-only Money Market/RMD-overage fields, **not** equivalent to Pro PoS tax and cash-reserve treatment.

## Acceptance decision

**Stage 9: CLOSED — ACCEPTED WITH DOCUMENTED LIMITATIONS.** All four areas have an accepted outcome. Do not add regression cases just to increase counts: each of the newly identified interactions has already been tested and the two identified corrections have been resolved. No automatic Stage 10 is proposed. If future implementation changes touch any of these paths, rerun the corresponding focused suite. Independent tax-law review and optimizer batch persistence testing can be scheduled as separate future work if desired.

**Source untouched.** This review created audit artifacts only.
