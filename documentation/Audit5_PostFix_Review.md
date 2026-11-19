# Audit5 — Targeted post-fix acceptance and coverage review

**Review:** November 19, 2026 (America/Phoenix).  
**New source:** `RiskBased_OptSocSec_v2(2).java`, header Wednesday Nov 18 2026 5:47 PM MST, SHA256 `10e4d12868f82621aab84fa831e111d5c31dfe67f25afc3f106a04da9725a783`.  
**Pre-fix source:** `RiskBased_OptSocSec_v2(1).java`, header Nov 18 2026 9:34 AM MST, SHA256 `136e864b7d0925023567dc94c3453e5340892e1015c937a0919b6bc35507626f`.  
**Scenario basis:** supplied Nov 16 1.9M plan (31 years) and a derivative that explicitly activates the 2027 base tax set. 

## Decision

**AUDIT5 POST-FIX CHANGE SET ACCEPTED WITH DOCUMENTED LIMITATIONS.** The former scenario-dependent 2027 long-term capital-gain band defect is corrected in the declared TaxEngine rules and reproduced at both 0%/15% and 15%/20% transitions, MFJ/Single, with stacking and inflation indexing. The separate UI issue (older scenario silently using 2026 thresholds in a 2027-start plan) is mitigated with visible warning and result marker. No confirmed financial regression in the reviewed source change was found. 

**Statutory values verification:** The new source declares 2027 LTCG thresholds MFJ $102,100 / $633,600 and Single $51,050 / $563,200. These were validated *against source-defined values* in the test and across actual engine operations. The source itself says they are drawn from three secondary sources. The official IRS tables were not independently consulted in this **uploaded-documents-only** review, and independent statutory authentication remains **[Unverified]**.

## Change-set diff and financial basis

Q44 adds `ltcg0TopFor(int year, FilingStatus)` and `ltcg15TopFor(...)`, with first-year tax figures controlled by `ACTIVE_TAX_YEAR`. `fedTaxPref` now uses `infl(ltcg0TopFor(ACTIVE_TAX_YEAR, fs), fedFactor)` and the corresponding 15% cap. Q44 adds `;ltcg=2027` to `thresholdSignature` only when the active base is 2027, intentionally invalidating old optimizer results generated with 2026 band values. Q45 adds an active-tax-year line to the Scenario card and a results-header marker when the tax base and simulation start year do not agree; it stores the actually used tax year in `ProResults.taxYear`. Existing scenario formats and active default treatment are preserved.

## Executed acceptance evidence

| Test area | Checks passed | Evidence |
|---|---:|---|
| Independent gain-only and ordinary-stacking boundaries, MFJ/Single, 2026/2027/2028 fallback, inflation, known prior defect | 104 | `postfix_logs/ltcg_boundaries.log` |
| Q45 real Swing year display, mismatch warning, 2027 reset, 2028 fallback, results marker, tax-year tooltip and signature | 21 | `postfix_logs/postfix_gui.log` |
| Full-engine first-year sales, 0/15/20 allocation, July/December, loss, NIIT, Roth conversion tax paid by holding cash, two-year IRMAA, thread parity | 58 | `postfix_logs/sale_new.log` |
| Prior Audit5 controlled one/four-thread suite, repeated 300/160/17 | 9 | `postfix_logs/Audit5Parity.log` |
| Prior Audit5 2027 tax / fill ceiling / forced RMD accounting suite | 28 | `postfix_logs/Audit5TaxRmd.log` |
| Prior Audit5 real Swing full workflow | 47 | `postfix_logs/gui.log` |
| Prior Audit5 modal tax-year reset dialog | 15 | `postfix_logs/dialog.log` |
| Prior Audit5 actual eight-combination SS Optimizer and cache tests | 12 | `postfix_logs/optimizer.log` |
| High-fidelity new-build one/four-thread checks, early savings ON and OFF (1200/600/25; 31 years) | 6 | `postfix_logs/parity_full_post.log`, `postfix_logs/parity_full_off_post.log` |
| Stabilized independent full-fidelity new-build executions (two runs) | 2 | `postfix_logs/stable_post_1.log`, `stable_post_2.log` |
| **Total new-build asserted checks** | **302** | **0 assertion failures** |

Previous-build comparators were run separately but not double-counted. In the clean initialization-controlled comparison, both prior and new builds returned exactly **82.0% portfolio-only Actual PoS**, initial withdrawal **$146,625**, **31 annual rows**, and identical 600-path hash **`fb41c08bf021d062`**, twice each. The older unchecked test harness at one point returned a different standalone hash on one process. Its cause remains [Unverified]; the stabilized-loader results and same-process thread tests did **not** reproduce it.

**Dollar illustrations from matched fixed-seed 2027 taxable-sale test inputs** (not the user's zero-sale scenario):

| Scenario | Pre-fix displayed year-1 tax | New year-1 tax | Change |
|---|---:|---:|---:|
| January $700,000 proceeds / $20,000 basis ($680k gain) | $166,359 | $165,364 | −$995 |
| July $400,000 proceeds / $60,000 basis ($340k gain) | $59,887 | $59,407 | −$480 |
| December, same $340k gain | $44,928 | $44,448 | −$480 |
| July loss ($7,000 proceeds / $14,000 basis) | $1,619 | $1,619 | $0 |

A separate July sale plus $40,000 flat conversion case showed **$13,320 conversion tax** paid from the holding account, allowing **the whole $40,000 to enter the Roth**. The later 2029 IRMAA tier responded to the 2027 sale. The 2026 no-sale and 2027 no-sale control fan hashes matched the previous build at reduced fidelity; 2027 no-sale full-fidelity stable-loader comparison matched exactly as well.

## Scope and remaining limitations

The previously user-accepted simulator decisions (portfolio-only Actual PoS, external reserve rules, exact-year vs approximate bridge tax, Roth conversion tax deduction treatment, future annual reruns, discretionary surplus handling) were not reopened. The source's one-year brokerage sale model, no loss carryforward, and fixed tax tables until program update remain the documented modeling scope, not undisclosed regressions. A new future tax year (2028+) defaults to the most recent implemented set rather than representing new statutory tables; the program now warns about the mismatch.

**Final Audit5 status:** Closed for the November 18 5:47 PM change set as a *software implementation acceptance*, retaining the [Unverified] statutory numbers caveat and the preserved earlier standalone test-harness anomaly. Additional testing is needed if future code changes alter taxes, source values, threading or scenario loading.
