# Audit5 — November 18, 2026 Java retirement simulator change-set audit

**Build examined:** `RiskBased_OptSocSec_v2(1).java` (internal class `RiskBased_OptSocSec_v2`), November 18 2026, 09:34 MST, SHA-256 `136e864b7d0925023567dc94c3453e5340892e1015c937a0919b6bc35507626f`.

**Accepted financial baseline:** November 14, 2026 15:54 MST source, SHA-256 `24e80fe47109ff2903efe4bbf117dc834ed39359e939aab8c1a70a597568e186` (Audit4 accepted with documented limitations).

**User's inputs:** `2026-11-16_near_1_9M_mult_16_13_great_candidate.ilscen`, saved Nov 17, 2026 16:31, SHA-256 `5c5858dde24c947c92e5d1c84c33539a152cdab812725f81526c3a69978284b8`. All experiments were performed on copies; originals were not modified. The November 18 screenshot's early-reserve checkbox is ON, whereas this supplied scenario has `mm.coversEarly=false`; both were tested.

## Disposition

**Conditional financial acceptance for the supplied zero-sale scenario; general Audit5 change-set is NOT fully closed.** One actionable integration gap remains in 2027 capital-gain tax bands. A legacy-scenario 2026-versus-2027 tax-year selection hazard also warrants a prominent note or warning. Previously accepted simplifications (portfolio-only Actual PoS, external reserve treatment, approximate SS bridge/conversion tax, discretionary surplus nonaccumulation, pre-withdrawal tax display) were not reopened.

## Priority findings

### 1. 2027 capital-gain band set not implemented [open, scenario-dependent financial issue]

File `RiskBased_OptSocSec_v2(1).java` lines 16334–16340 define only `LTCG_0_TOP_*_2026` and `LTCG_15_TOP_*_2026`. Lines 16377–16379 still use `infl(single ? LTCG_0_TOP_SINGLE_2026 : LTCG_0_TOP_MFJ_2026, fedFactor)` for *all* selected tax years. Q38 explicitly selects 2027 brackets, deductions and IRMAA tables elsewhere. The handoff file itself marks this as an open finding (section 5, item 9).

**Executed source-level probe:** with $100,000 taxable income including $10,000 preferential gain and base-year factor 1, both the 2026 and 2027 configurations allocate $8,900 to the 0% capital-gain band and $1,100 to the 15% band. Ordinary-income tax does change (the total tax was $10,469 vs $10,453), demonstrating that the 2027 selection does not switch the capital-gain band table. See `logs/ltcg_boundary.log`.

**Effect:** None in the attached scenario because `sale.proceeds=0` and `sale.basis=0`; potentially material with 2027 taxable-brokerage sales near capital-gain thresholds. **Recommendation:** Add year-selected 2027 0%/15% LTCG upper bands for MFJ and Single after independently confirming the 2027 values against the appropriate official tax-year source; keep the same inflation/stacking methodology; add 0%/15%/20% boundary tests and save/load tests. Until then, disclose that the 2027 LTCG base is still the 2026 one. The attachments do not provide verified 2027 LTCG threshold values, so no substitute values have been invented.

### 2. Legacy 2027-start plan retains 2026 tax tables [expected compatibility, meaningful user-configuration risk]

The attached file does not contain `tax.thresholdYear`. `RiskBased_OptSocSec_v2(1).java` lines 11058–11068 explicitly say: `int fileTaxYear = 2026;` and `applyTaxYearSet(fileTaxYear);`. Thus this plan loads the *2026* set even though `sim.startYear=2027`. This is deliberate backward compatibility, not a regression. Its existing 2026 first MFJ IRMAA cliff is $218,000 and the saved Fill buffer is $10,000, yielding first-year MAGI target $208,000. Changing the copied scenario explicitly to the 2027 tax set raised that first-year MAGI target to $214,000 (2027 cliff $224,000 less the same $10,000 buffer).

**Matched full-fidelity, fixed-seed cases** (1,200 solver, 600 fan, 25 iterations): new 2026-set source -> `MAGI $208,000`, Roth conversion `$44,787`; new 2027-set source -> `MAGI $214,000`, conversion `$50,913` (both with the legacy off switch). Other assumptions and original file unchanged. See `logs/new2026_1_full.log` and `logs/new2027_4_full.log`. Because other values (the Roth-conversion tax and portfolio paths) also change, this is a materially different projection, not a mere formatting issue. **Recommendation:** In the GUI, prominently surface the *active base tax year* alongside the simulation start year, and consider a nonblocking warning when `sim.startYear>=2027` but the loaded base tax year is 2026. Do not silently change or overwrite an old scenario; users can use Edit base thresholds > Reset to 2027 and save a new version.

### 3. One standalone high-fidelity outlier [unverified, not a confirmed code defect]

The first standalone one-thread full-fidelity run of the 2027 set with early savings coverage ON returned a portfolio PoS of 79.00% and a different path hash, despite fixed seed 0 and identical first-year results. The controlled **same-JVM** full-fidelity 1-vs-4 comparison subsequently produced byte-identical path hashes, all 31 financial rows, and 82.1667% PoS at both thread counts. Two independent standalone repeats also reproduced 82.1667% exactly. The reduced-fidelity same-JVM 1/4 and repeated 1/4 comparisons were also exact. See `logs/new2027_withSplits_topoff_1_full.log`, `logs/parity_full_same_process.log`, `logs/new2027_topoff_separate1_repeat.log`, and `logs/parity_new_reduced.log`.

**Assessment:** [Unverified] origin of the single outlier. Current same-process evidence does not establish a threading defect. Keep its raw log rather than hiding it. A future rerun can use the independent snapshot test before attributing a difference to thread count.

## Executed test coverage

- Java 21 production-source builds, both old and new: success. New source has no compilation errors under `javac -Xlint:all` (15 warnings in this environment, mainly pre-existing Swing warnings and raw/serial types).
- 2026-set backward-compatible test with both Money Market pools zero: new and old sources produced exactly the same SHA-derived fan-path hash `87027f2ed1e5b993`, identical first-year withdrawal `$146,447`, identical first-year MAGI `$208,000`, and exact annual row details at 400/200/18, fixed seed.
- Full-fidelity original attached scenario (31 years, 1,200/600/25), old and new: validated year counts, PoS from terminal path balances, income-minus-spend-minus-surplus, cash nonnegative and accounts. Q30 intentionally grows the $100,000 reserve by year-one inflation, from $100,000 (old) to $101,973 (new) in the first row. New first-year 2026-set conversion was $44,787 vs old $46,760 (the added taxable reserve interest changes MAGI and the fill).
- Independent 2027 federal ordinary-bracket boundaries, standard and senior deductions, IRMAA threshold crossings, annual per-person IRMAA surcharge math; fill ceiling cap ($100,000), no-limit combination (first-year conversion `$1,722,145`), restrictive 10% bracket, and default 22%/IRMAA bound (MAGI `$214,000`).
- Forced RMD test using $4.6m Traditional and small planned spending, zero returns and inflation, no conversions: forced RMDs above planned withdrawals arose in five years; totaled `$367,540`; independently reported incremental tax `$60,605`, with federal/state/IRMAA component reconciliation and one-/four-thread path parity.
- Full-fidelity 2027 with savings top-off ON: new four-thread PoS 82.1667%, combined-resources survival 88.50%, no unpaid year in the first 26 years 92.17%, no unpaid year in the final five years 88.50%; split independently reconstructed from *all* `fanBalances` and `fanShort` arrays, not merely read from UI. The screenshot was produced with a different scenario random draw; do not expect identical percentages unless it is rerun with a fixed seed.
- Actual Swing application with Xvfb: 47 checks of scenario loading, new and legacy threshold values, all collapsible input cards starting closed, obsolete `panelstate.prefs` untouched, actual Save Scenario dialog, 2027 threshold/age-65-add-on persistence, actual Run Simulation & companion Roth tax run in both conversion-ON and conversion-OFF modes, top question/answer area, optimizer early-reserve switch behavior.
- Actual modal Edit base thresholds dialog: 15 checks of 2027 reset, Cancel, 2027 OK, 2026 reset/OK, year/surcharge tooltip. The 2026 reset visibility state was tested for November 2026, January 2027, and February 2027.
- Actual SS Optimizer batch (8 candidate combinations): 12 checks; with early reserve checkbox ON, batch copy forced it OFF and preserved optimizer fingerprint, held an already-claimed person's date, executed and resumed saved combination results, and changed the fingerprint when 2027 base tax year was switched back to 2026.
- JDK21 single/4-thread exact deterministic comparisons at 300/160/17 (four runs) and full 1200/600/25 in one JVM (two runs), plus an independent repeat.

All 255 explicitly asserted checks in the selected direct execution logs passed with no assertion failures (some are intentionally repeated controls). A clean-extraction quick reproduction also passed from the evidence archive: its independent old/new no-reserve hashes matched, and all GUI, modal, optimizer, tax/RMD and parity harnesses passed. The sale-band mismatch was identified by an independent diagnostic and is listed above as an open finding rather than incorrectly counted as a passing assertion.

## Evidence organization

- `source/` — accepted November 14 baseline, exact uploaded November 18 source, unified diff.
- `scenario/` — original uploaded November 16 file and a clearly labeled derivative *copy* selecting the 2027 base thresholds; the original is unchanged.
- `tests/` — the executable Java reflection/Swing harness source; it tests the real Java implementation rather than an independent reimplementation of the simulation.
- `logs/` — all observed raw test outputs, including the single high-fidelity outlier and the successful controlled reruns.
- `run_quick.sh` — Java 21 compile and targeted automated tests in a scratch home; `run_full.sh` — longer fixed-seed full-fidelity tests.

**Acceptance recommendation:** Safe to use the November 18 build for the supplied zero-sale retirement case, subject to deliberately selecting the base tax year you want. Do **not** sign off the entire 2027 brokerage-sale tax pathway until the capital-gain base-year gap has been resolved and boundary-tested. Do not reopen previously accepted financial modeling decisions without a new requirement.
