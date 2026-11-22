# Targeted SS Optimizer M2 regression — final
**Date:** October 19, 2026 (America/Phoenix)
**Disposition:** **PASS — targeted scorer acceptance.** A complete multi-thousand-row batch, GUI, and on-disk append/resume have **not** been executed.

## Objective
Check user hypothesis that the SS Optimizer uses the Pro PoS simulation's updated simulation-start-year Medical logic, and verify any downstream effects on its metrics, year-level floor values, ranking, feasibility, and batch cache fingerprint. Do **not** just trust the direct simulation call: the optimizer separately scores output rows.

## Sources
- **Corrected** uploaded `RiskBased_OptSocSec_v1 (2).java`, SHA256 `abd39c082e9b14b1dc39655f05083f38123a62bd9187f34b4c1d7a327789c69d`
- **Prior** uploaded `RiskBased_OptSocSec_v1 (1).java`, SHA256 `f1c9e8f0d3eb3476c8256950f6ef4074634f41928056fe4a742e52d608ded95f`
- Both SHA256-identical copies compiled from scratch with Java 21; original uploads unchanged.

## Exact source quotations
- `(2).java`, lines 6680–6698: "Clones the base inputs, sets this combination's SS start dates, runs simulatePro ONCE at full Pro-tab fidelity, and reads the verdict straight off the resulting Surplus/gap column".
- `(2).java`, lines 6741–6749: `pr = simulatePro(inp, seed, solvePaths, fanPaths, binIters, /*withGk=*/false);`.
- `(2).java`, lines 6813–6832: `double spendable = (row.totalIncome - row.tax - row.medical) / d;` and `double sReal = row.surplus / d;` (source lines may vary by a few).
- `(2).java`, lines 10165–10175: "Indexed from the SIMULATION-start year, like living" and `double medical = drawing ? inp.medical * Math.pow(1 + inp.medInflation, y) : 0;`.
- `(2).java`, lines 5010–5016: "rows move when the simulation starts before withdrawals do; every batch re-runs" and `SCORER_VERSION = 19`.
- `(2).java`, lines 6448–6458: `SCORER_VERSION` is appended to the batch fingerprint so prior cached rows do not match.

## Executed method
The headless regression harness directly invoked the **original, unmodified** private `scoreCombinationPro()` for each tested SS claim-date candidate and separately called the original `simulatePro()` with the same cloned inputs, calculated claim-specific SS benefits, seed, and full regression fidelity. The harness reconstructed annual medical expense independently and compared, per drawing year:
- Correct Medical indexing (earlier: withdrawal-start; corrected: simulation-start), including surviving spouse's medical factor;
- Pro PoS total spending and surplus identity;
- Optimizer saved calendar years, year indices, go/slow/no-go phases, deflated per-year surplus and balances;
- Life, early, no-go, couple and survivor spendable metrics, go/slow minima, raw minimum surplus, and final-five-year withdrawals;
- Five ranking metric getters plus a separate two-candidate sorting check across all five ranking modes;
- Re-scoring identical simulated rows with alternative green/go/slow floors;
- SS Optimizer versus Pro PoS actual PoS under identical seeds and fidelity;
- Deterministic controlled inputs, no market-return or inflation volatility, 20 solver paths, 5 fan paths, 8 binary iterations, seed 0. Input return 3.5%, general inflation 2% (nonzero but deterministic), horizon 14 years. These are **synthetic test settings**, not recommended planning values.

### Executed result totals
- Corrected: `SUMMARY build=new scenarios=7 candidates=14 yearChecks=160 survivorYearChecks=14 delayedYearChecks=132 assertions=1680 PASS`.
- Previous: `SUMMARY build=old scenarios=7 candidates=14 yearChecks=160 survivorYearChecks=14 delayedYearChecks=132 assertions=1680 PASS`.
- Old/new cross-build comparisons: **157 PASS**, covering 14 matched candidates: 6 unchanged controls, 8 delayed-start changes. No unexpected change to first-year portfolio balance, actual withdrawal, or computed PoS. Cross-build compares first-year Medical, Total Spend, Surplus/Gap and whole-horizon optimizer spendable metrics.
- Both previous/current builds passed controlled reconciliation; **the earlier build's pass is not an endorsement of its inflation base**, just proof its optimizer consistently used the older Pro PoS arithmetic.

### Numeric evidence: corrected Medical reaches optimizer scoring
| Case | Prior first-draw Medical | Current first-draw Medical | Total Spend change | Surplus/Gap change |
|---|---:|---:|---:|---:|
| Jan 2026 immediate, $10k/4% | $10,000 | $10,000 | $0 | $0 |
| Jan 2028, $10k/4% | $10,000 | **$10,816** | +$816 | −$816 |
| Jul 2030, $10k/4% | $10,000 | **$11,698** | +$1,698 | −$1,698 |
| Jan 2028 with survivor event, $16k/5% | $16,000 | **$17,640** | +$1,640 | −$1,640 |
| Jan 2028, $10k/0% | $10,000 | $10,000 | $0 | $0 |
| Jul 2030, $0/4% | $0 | $0 | $0 | $0 |
| Jan 2030 single, $20k/6% | $20,000 | **$25,249** | +$5,249 | −$5,249 |

For one early claimant, the corrected Jan 2028 scenario reduced the optimizer lifetime spendable metric by $10,497 compared with the older build; the Jul 2030 scenario by $17,150.74. These are synthetic model test cases, not real-plan forecasts.

### Feasibility boundary (direct, user-visible behavioral proof)
Using identical Jan 2028 inputs and the identical SS claim pair, with the optimizer's go-go surplus floor deliberately set to **$82,650**:
- Earlier build: minimum go-go surplus **$83,029** → **PASS** (shortfall $0).
- Corrected build: minimum go-go surplus **$82,244** → **FAIL** (shortfall **$406**).

This is the **correct sensitivity** to an increase in medical spending. It directly proves the optimizer's feasibility decision follows the new Pro PoS medical arithmetic, rather than silently retaining the old value.

### Cache fingerprint
For identical simulated inputs:
- Previous source fingerprint: `bf859802773f38ed` (SCORER_VERSION 18).
- Corrected source fingerprint: `bf223d7c4233bb38` (SCORER_VERSION 19).

The test also verified that changing Medical inflation changes the fingerprint, while modifying candidate claim dates/derived annual SS does not fragment a single batch. This checks the **fingerprint function**, not filesystem resume of a batch.

## Boundaries and recommendation
- **PASS:** No independent optimizer medical-spending defect was found. The source does invoke corrected Pro PoS, and its downstream metrics, annual floor inputs, five ranking modes, feasibility rule and scorer-version cache key responded correctly in these deterministic cases.
- **Not tested:** Full GUI-initiated SS Optimizer batch, threaded worker execution, CSV append/resume, or distributions with nonzero stochastic variance. No user-specific scenario was loaded.
- **Recommendation:** No new audit stage or source change required for M2. Full optimizer batch/persistence testing remains an optional separate coverage goal.

## Test artifacts
`current.log`, `previous.log`, `cross_build_comparison.log`, `source_integrity.log`, `newcompile.log`, `oldcompile.log`, and `newsrc/com/hiflite/riskbased_optsocsec/Stage9SsOptMedicalRegression.java` (same harness for previous build).
