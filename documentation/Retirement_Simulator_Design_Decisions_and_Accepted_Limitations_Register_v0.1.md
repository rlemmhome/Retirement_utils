# Retirement Simulator — Design Decisions and Accepted Limitations Register

**Version:** 0.1 — initial, evidence-backed register  
**Prepared:** November 18, 2026 (America/Phoenix)  
**Subject:** `RiskBased_OptSocSec_v2` Java retirement-planning simulator  
**Latest source examined:** `RiskBased_OptSocSec_v2(1).java`, November 18, 2026, 9:34 AM MST  
**Audit context:** Audit3 closed (November 2); Audit4 closed (November 14); Audit5 (November 18) **remains open** for the capital-gains tax-year issue.

## Purpose and authority

This document preserves *why* the program deliberately works in ways that might initially surprise another developer, actuary, financial planner, or AI auditor. **It is a design-control document, not a claim that every implementation has been independently verified.** An accepted modeling limitation must be tested for correct implementation, rather than automatically rewritten as if it were a defect. Conversely, an independently identified defect is not excused by a general design philosophy.

**Evidence levels:** **A** = directly quoted from the attached Java source / its Assumptions & Methods text; **B** = directly quoted from a completed audit review; **C** = summarized in the attached dated developer handoff (not necessarily the user's exact words); **D** = relevant earlier-chat context recoverable in summary but not verified against an entire original transcript. This initial register prioritizes A/B/C. File/line references below refer to the uploads as available on November 18, 2026; they may shift in future revisions. Quotes are reproduced from those files (HTML-encoded punctuation may be normalized).

### Audit conversation coverage

Prior project/chat context identifies conversations using the word **Audit**, including **“Audit of methodology and code, Kitces Go-Go Multiplier”** (April 2026), **“Audit Retirement Planning Code”** (October 2026), **“Audit2 Retirement Planning Code”** (October 2026), and **“Audit3 Retirement Audit Continuation” / “Audit3 Retirement Planning Code”** (November 2026). Relevant prior-discussion decisions were recoverable, but **a complete, exact-text archive of every titled chat was not available for a line-by-line search**. Thus the dated source, audit reviews, and developer handoff—not presumed verbatim access to every old conversation—are the evidentiary foundation of this file. Add links/exports of the original audit chats as they become available.

## Audit acceptance history

| Audit | Reviewed baseline / scope | Documented disposition | What it does *not* mean |
|---|---|---|---|
| Audit3, Nov 2, 2026 | `RiskBased_OptSocSec_v2 (5).java`; partial-year sale tax, IRMAA, reserve/RMD/survivor, optimizer, GUI | **Accepted with documented limitations**; 316 checks, no failures | Not exhaustive testing or independent tax-law certification |
| Audit4, Nov 14, 2026 | November 14 source; split cash reserves, tax funding, already-claimed SS, conversion comparison, persistence | **Accepted with documented limitations**; 434 checks, no failures | Not a guarantee for every plan or every stochastic trajectory |
| Audit5, Nov 18, 2026 | November 18 source; 2027 tables, interface, optimizer, RMD comparison, cash interest | **Conditionally accepted for the uploaded zero-sale scenario; Audit5 remains open** | 2027 long-term capital-gain bands have an unresolved integration gap |

**Evidence:** `audit3/Audit3_Final_Coverage_Acceptance_Review.md`, “Decision”: “**ACCEPTED WITH DOCUMENTED LIMITATIONS**”; `Audit4_Final_Coverage_Acceptance_Review.md`, “Decision”: same; `audit5/Audit5_Nov18_Review.md`, “Disposition”: “**Conditional financial acceptance for the supplied zero-sale scenario; general Audit5 change-set is NOT fully closed.**”

## A. Planning objectives, withdrawals and probabilities of success

### DDR-001 — An annually updated plan, not a one-time immutable forecast
- **Status:** ACCEPTED DESIGN. **Evidence:** A.
- **Decision/rationale:** Re-enter current account balances and revise actual assumptions at least annually (and as needed); avoid pretending a multi-decade static forecast stays exact.
- **Source quote:** “**This simulator is designed for annual reassessment, not a permanently fixed retirement plan.**” — `RiskBased_OptSocSec_v2(1).java`, lines **4293–4303**, Assumptions §15.
- **Audit rule:** Do not demand automatic year-to-year accounting for off-model personal actions as a substitute for the chosen annual update process. Reopen if annual re-entry becomes impractical or silently loses financial state.

### DDR-002 — Surplus is discretionary spending headroom, deliberately not reinvested
- **Status:** ACCEPTED DESIGN. **Evidence:** A.
- **Decision/rationale:** Green annual surplus pays for travel and unbudgeted discretionary spending. Any amount personally saved is re-entered in a later run; do not automatically accrue it in the model.
- **Source quote:** “**Surplus is intentionally available for travel and discretionary spending excluded from baseline living expenses. It is not automatically saved.**” — Java, lines **4243–4247**, Assumptions §15 item 1.
- **Audit rule:** A large lifetime sum of green surplus is *not* evidence of an omitted portfolio asset. Test that income minus spending equals surplus and that no surplus is secretly reinvested.

### DDR-003 — The user accounts for medical and other expenses funded outside the model
- **Status:** ACCEPTED PLANNING RESPONSIBILITY. **Evidence:** A.
- **Decision/rationale:** Some transitional expenses are knowingly paid from external cash, outside simulated accounts; a yearly update is the correction mechanism.
- **Source quote:** “**The user is expected to recognize and account for these external funding needs.**” — Java, lines **4264–4271**, Assumptions §15 item 5.
- **Audit rule:** Verify the screen states the funding boundary. Do not infer absent expenses are automatically financed by the portfolio. Reopen if users reasonably mistake excluded expenses for modeled obligations.

### DDR-004 — The target PoS sets a *fixed-withdrawal solver* amount; outer paths re-solve annually
- **Status:** FOUNDATIONAL METHODOLOGY. **Evidence:** A.
- **Decision/rationale:** Candidate withdrawals face fixed, inflation-linked spending schedules within each inner solver trial. The outer simulation then re-solves each year using its current/median balances. The target (often 80%) is a constraint, not a claim that Actual PoS must equal 80%.
- **Source quote:** “**Probability of Success (PoS) is the input constraint; the maximum sustainable dollar withdrawal is the output. The engine re-solves the sustainable draw each year**” — Java, lines **4479–4485**, Assumptions §1; see methodological overview, lines **34–58**.
- **Audit rule:** Never substitute annually cut spending into the inner fixed-withdrawal test without deliberate redesign. Independently check target solver calibration and outer Actual PoS separately.

### DDR-005 — Actual PoS measures the invested portfolio only, not external cash
- **Status:** EXPLICIT USER DESIGN; LOCKED UNTIL REOPENED. **Evidence:** A/B.
- **Decision/rationale:** External cash is not guaranteed to remain available or to be replenished, so the model should not count it as portfolio survival or use it to qualify SS Optimizer solutions.
- **Source quote:** “**Actual PoS answers one question: did the portfolio ever reach $0?**” — Java, lines **4500–4506**, Assumptions §1. Audit3 review, “Retained limitations”: “**No future discretionary user contributions are guaranteed or counted toward portfolio-only Actual PoS.**”
- **Audit rule:** Test portfolio-only PoS independently from cash-reserve-aided outcomes. Do not change the optimizer gate to reserve-inclusive survival without a new user decision.

### DDR-006 — Cash-supported lifetime completion is an additional metric, not portfolio PoS
- **Status:** ACCEPTED INTERPRETATION. **Evidence:** A.
- **Decision/rationale:** The simulator may report that cash reserves carried additional depleted-portfolio paths to completion. This answers a different question from whether the investment portfolio stayed positive.
- **Source quote:** “**These metrics answer different questions. The 80% target governs the fixed-withdrawal solver, not a guarantee of reserve-supported success.**” — Java, lines **4259–4265**, Assumptions §15 item 4.
- **Audit rule:** Preserve both the portfolio-only and combined-resource numbers; independently recount each path. Do not equate “never ran out completely” with “no year had less income than planned spending.”

### DDR-007 — Short-year adequacy and lifetime survival remain separate metrics
- **Status:** ACCEPTED INTERPRETATION. **Evidence:** A.
- **Decision/rationale:** A simulation can retain positive portfolio assets but experience an unpaid spending gap due to withdrawal constraints. The shortfall count must not be silently replaced by a survival rate.
- **Source quote:** “**Actual PoS can be 100% while this count is not zero: the money lasted, but some years came up short.**” — Java, lines **2643–2656**, Actual PoS tooltip.
- **Audit rule:** Test the shortfall arrays separately from terminal portfolio balances; label both clearly.

### DDR-008 — The final five years deliberately spend down remaining portfolio assets
- **Status:** ACCEPTED FINANCIAL METHOD. **Evidence:** A/B.
- **Decision/rationale:** In the final five plan years the program adds a top-up above the normally solved draw to close cash-flow gaps, exhausting the portfolio before drawing external cash if needed. This can *lower* portfolio-only Actual PoS even when it fulfills late spending needs.
- **Source quote:** “**In the last 5 plan years ... The extra comes first from the portfolio ... and, once the portfolio is empty, from the cash reserve**” — Java, lines **4546–4559**, Assumptions §1.
- **Audit rule:** Verify final-years cash transfers, tax gross-up, reserve ordering, exactly-zero depletion and no duplicated funding; do not equate planned late drawdown with an early simulation error.

### DDR-009 — The 22% planned withdrawal cap is distinct from late-life top-ups
- **Status:** SOURCE-DEFINED BEHAVIOR. **Evidence:** A.
- **Decision/rationale:** The regular solver's maximum withdrawal still applies; a separately identifiable final-years top-up can exceed the solved amount.
- **Source quote:** “**The Pro PoS solver is unchanged: its ceiling (22% of the balance) still sizes every planned draw, and the top-up is added after it**” — Java, lines **4551–4557**, Assumptions §1.
- **Audit rule:** Do not report a final-year over-22% actual withdrawal as automatically violating the solver cap. Check that the extra amount is classified as top-up.

### DDR-010 — Go-go / slow-go multipliers apply to portfolio draws, not total guaranteed income
- **Status:** EXPLICIT USER DESIGN. **Evidence:** A.
- **Decision/rationale:** A multiplier like 1.6 or 1.7 can be necessary for a desired 20–30% increase in total discretionary income, because Social Security and other fixed income are not multiplied.
- **Source quote:** “**The multiplier applies to portfolio withdrawals, not total household income. Earlier multipliers produced less than the intended 20%–30% increase in overall spendable income.**” — Java, lines **4288–4293**, Assumptions §15 item 9.
- **Audit rule:** Compare the resulting whole-household spending headroom, not just the multiplier's numeric change. Do not impose typical multiplier ranges as a defect finding.

### DDR-011 — Distinguish base sustainable withdrawal from *actual* first-year draw
- **Status:** APPROVED PRESENTATION PRINCIPLE. **Evidence:** A.
- **Decision/rationale:** Actual withdrawal also includes the go-go multiplier, Social Security bridge and eligible top-ups. A small base headline and much larger actual portfolio draw can both be correct.
- **Source quote:** “**The initial portfolio withdrawal substantially exceeds the headline base withdrawal rate**” and “**The additional withdrawal is an intentional trade-off for delaying Social Security**” — Java, lines **4251–4258**, Assumptions §15 item 3.
- **Audit rule:** Tie first-year Actual wd to the displayed components instead of treating a difference from the headline as double-counting.

## B. External cash reserves and account mechanics

### DDR-012 — The regular savings reserve primarily protects against sequence-of-returns risk
- **Status:** EXPLICIT USER DESIGN. **Evidence:** A.
- **Decision/rationale:** Keep a separate, off-portfolio savings/cash account as a financial backstop; do not presume it must pay ordinary Roth-conversion taxes.
- **Source quote:** “**The savings account primarily protects against sequence-of-returns risk. Users who wish to allocate cash to conversion taxes can explicitly use the separate holding account for brokerage sales.**” — Java, lines **4271–4279**, Assumptions §15 item 6.
- **Audit rule:** Preserve funding separation and priority. Do not automatically redirect the regular reserve into conversions.

### DDR-013 — Early savings coverage is optional; late five-year backstop is always available
- **Status:** ACCEPTED USER-CONTROLLED BEHAVIOR. **Evidence:** A.
- **Decision/rationale:** The early-shortfall checkbox can draw from cash during earlier retirement years; otherwise the program preserves it for the final-years backstop.
- **Source quote:** “**In the last 5 plan years the cash reserve always backs up the portfolio ... This box decides whether it also pays short years earlier**” — Java, lines **1273–1285**, early coverage tooltip.
- **Audit rule:** Test both checkbox states and full/partial reserve exhaustion; never infer the checkbox is on merely because the reserve exists.

### DDR-014 — SS Optimizer **ignores** optional early savings coverage
- **Status:** EXPLICIT USER DESIGN; LOCKED. **Evidence:** A/C.
- **Decision/rationale:** Social Security claiming dates are irrevocable/long-lasting decisions and should not qualify on the expectation that users will spend cash reserves to hide early deficits.
- **Source quote:** “**The SS Optimizer ignores this box and scores every claim-date pair with it unchecked. A Social Security claim date is a one-time, lifelong decision**” — Java, lines **1301–1308**, early coverage tooltip; see developer handoff, §3, November 17 Q39.
- **Audit rule:** Check optimizer copy forces the flag off, its fingerprint stays stable across this UI toggle, and the original Pro simulation remains free to use the flag.

### DDR-015 — Two cash pools are ordered and separately accounted for
- **Status:** ACCEPTED DESIGN. **Evidence:** A/C.
- **Decision/rationale:** Ordinary cash is spent first on eligible spending gaps; the brokerage holding pool is used second. Only the holding pool may be explicitly designated to fund conversion tax.
- **Source quote:** “**the savings account pays first; the holding account ... pays only once it is empty.**” — Java, lines **1289–1291**; “**The savings account is not used for this.**” — Java, lines **1333–1338**.
- **Audit rule:** Independently reconstruct both balances and ensure no dollar is spent twice; verify the displayed total equals their sum.

### DDR-016 — RMD overages go into the outside cash account, not back into the IRA
- **Status:** SOURCE-DEFINED ACCOUNTING. **Evidence:** A.
- **Decision/rationale:** Mandatory Traditional distributions above planned spending are already taxed and become external savings.
- **Source quote:** “**the reserve starts at the user's prior-years carry-in ... then (b) adds that year's RMD overage**” — Java, lines **12263–12279**, median cash accounting; “**forced RMDs, which go to the savings account**” — Java, lines **4212–4216**.
- **Audit rule:** Test year-end cash additions and no duplicate withdrawal; do not leave tax-paid RMD overages in Traditional accounts.

### DDR-017 — Cash reserve interest is modeled approximately as inflation, including year one
- **Status:** ACCEPTED SIMPLIFICATION; TIMING FIXED NOV 14. **Evidence:** A/C.
- **Decision/rationale:** Both pools are assumed to preserve roughly their real value, with annual nominal growth and taxable interest. Revised Q30 uses each *current* year's inflation rather than lagging a year.
- **Source quote:** “**Bob's assumption is money-market return ~= inflation, i.e. ~0% real**” — Java, lines **12267–12278**; handoff §3, November 14 Q30: “**both pools ... grow by each year's OWN inflation**.”
- **Audit rule:** Test first-year interest, later interest tax, and Real/Nominal balance displays; don't demand a separate variable cash yield unless requirements change.

## C. Social Security, survivor transitions, and year sequencing

### DDR-018 — The Social Security bridge deliberately funds delayed claiming
- **Status:** EXPLICIT USER TRADE-OFF. **Evidence:** A.
- **Decision/rationale:** Replacing not-yet-started Social Security with portfolio withdrawals is an intentional cost of selected optimized claiming dates, not phantom income.
- **Source quote:** “**The additional withdrawal is an intentional trade-off for delaying Social Security to optimized claiming dates. The bridge amount and its financial consequences are understood and accepted.**” — Java, lines **4253–4258**, Assumptions §15 item 3.
- **Audit rule:** Verify bridge start/stop dates per spouse, first-month proration, tax gross-up and the resulting funded portfolio draw.

### DDR-019 — Bridge tax gross-up is an intentionally approximate, performance-conscious calculation
- **Status:** ACCEPTED LIMITATION; DO NOT AUTOMATICALLY REOPEN. **Evidence:** A/B.
- **Decision/rationale:** Bridge schedules are constructed before the full Monte Carlo path and withdrawal solver outcomes exist. Estimate the year's marginal tax position rather than performing a second full solve for exact bridge tax in every trial.
- **Source quote:** “**The schedule has to be built before the simulation runs ... so it cannot know the draw the PoS solver will actually pick.**” — Java, lines **5682–5693**, Assumptions §5; Audit3 final review: “**SS bridge tax gross-up is an intentionally accepted performance-conscious approximation.**”
- **Accepted boundary:** The tax calculation estimates standard/age-65/senior deductions and ordinary income, but excludes later-billed IRMAA; RMDs may not be known when the schedule is made. A difference from exact realized tax is not on its own a defect. Reopen if estimation error becomes material to financial decisions.

### DDR-020 — Actual chronology: prior-Dec31 RMD base, January draw, returns on remainder, December conversion
- **Status:** FOUNDATIONAL REQUIREMENT. **Evidence:** A.
- **Decision/rationale:** Match real cash-transfer practice. RMDs are based on the preceding December 31 Traditional balance; planned withdrawal leaves in January; only remaining investments earn returns; conversion takes place late December after satisfying the year's RMD.
- **Source quote:** “**(1) the RMD is set from the prior December 31 Traditional balance; (2) in January ... withdrawal ... leaves the portfolio ... (4) in late December the Roth conversion is sized**” — Java, lines **4486–4495**, Assumptions §1.
- **Audit rule:** Do not move withdrawals to year-end, grow withdrawn dollars as investments, or convert RMD dollars. Re-test all event ordering after time-sequence changes.

### DDR-021 — Already-claimed Social Security dates are held fixed by the optimizer
- **Status:** ACCEPTED OPTIMIZER BEHAVIOR. **Evidence:** A/C.
- **Decision/rationale:** Dates before the current month are historical facts, not adjustable choices; only the unclaimed household member's date should be optimized.
- **Source quote:** “**someone who has already claimed (SS start before this month) is held at that date**” — Java, lines **7681–7683**, SS Optimizer logic; developer handoff §3, Nov 14 Q27.
- **Audit rule:** Validate past claim date preservation, available spouse search, both-claimed disabled button, and fingerprint invalidation for locked dates.

### DDR-022 — Survivor transition changes filing, SS, spending and RMDs
- **Status:** ACCEPTED SURVIVOR MODEL. **Evidence:** A/B.
- **Decision/rationale:** The death event should retain the surviving spouse's accounts and permitted larger SS benefit while changing taxation to Single at the defined transition year, with survivor-based spending and RMDs.
- **Source quote:** “**Death event (v6) -- mid-projection survivor transition**” — Java, lines **2025–2037**, Death event tooltip. Audit3 final review, “Survivor and RMD sequencing”: “**survivor-year timing, death-year filing change, conversions** ... tested.”
- **Audit rule:** Independently exercise early deaths and both identities, not just the default late-death scenario. Never treat the survivor's tax exposure as already handled by married filing status.

## D. Roth conversion goals and tax semantics

### DDR-023 — Conversion goal is lower surviving-spouse and beneficiary tax, not necessarily lowest lifetime tax
- **Status:** USER-PRIORITY WITH SOURCE SUPPORT. **Evidence:** A/C; related explicit user priorities were also recovered from earlier Audit discussions.
- **Decision/rationale:** Prepay some tax at controlled ceilings to reduce later RMDs, survivor Single-filer brackets, and inherited Traditional balances; the portfolio need not be maximized for inheritance if doing so conflicts with retirement spending.
- **Source quote:** “**Converting pays tax now, at your current bracket, to lower two taxes the lifetime figure cannot fully see**” — Java, lines **4221–4231**, Assumptions §7d.
- **Audit rule:** Do not call a conversion strategy defective solely because it pays more taxes in an early year. Compare survivor-year taxes, Traditional balances and available spending; do not invent heir-specific rates.

### DDR-024 — Conversions use user-selected IRMAA/bracket ceilings; $40,000 is not a fixed strategy limit
- **Status:** ACCEPTED CURRENT DESIGN. **Evidence:** A/C.
- **Decision/rationale:** Fill-to-target may optimize conversions against an IRMAA tier and marginal bracket, optionally subject to a user-entered cap; newest UI permits explicit no-ceiling choices. A previously used $40,000 gross cap is not a global constraint.
- **Source quote:** “**Tier 5 (no IRMAA limit) together with 37% (no bracket limit) removes the fill ceiling ... limited only by the Fill cap, if set**” — handoff, §3 November 17 Q37; same warning quoted in Java, lines **1950–1985**.
- **Audit rule:** Check cap=0's meaning (uncapped) separately from conversions OFF; test every ceiling and verify optimizer reflects actual selected controls.

### DDR-025 — Conversion's marginal tax assumes living income has consumed the standard deduction
- **Status:** EXPLICITLY ACCEPTED MODEL LIMITATION. **Evidence:** A/B.
- **Decision/rationale:** Compute incremental conversion tax at stacked marginal rates without allowing a second standard deduction, even though this can overstate taxes when non-conversion income is too small to use the full deduction.
- **Source quote:** “**It does not get its own standard deduction (already consumed by spending income)**” — Java, lines **5327–5335**, Assumptions §7; Audit3 final review: “**Roth-conversion incremental tax retains the previously accepted standard-deduction-consumption assumption.**”
- **Audit rule:** Document the possible low-income overstatement; don't silently repair this accepted approximation. Escalate if a specific plan's conversion decision materially changes because of it.

### DDR-026 — Conversion tax is paid out of gross conversion by default; brokerage holding pool is opt-in
- **Status:** ACCEPTED FUNDING POLICY. **Evidence:** A.
- **Decision/rationale:** Default conversion accounting moves gross from Traditional, pays tax out of that transfer, and deposits net Roth. Opt-in external funding decreases the brokerage holding pool, allowing more of the same gross conversion to reach Roth; the regular savings account stays protected.
- **Source quote:** “**By default, this program pays every conversion's tax out of the conversion itself.**” — Java, lines **4091–4097**; “**The savings account is not used for this.**” — lines **1333–1338**.
- **Audit rule:** Reconcile gross conversion = net Roth + tax funded from conversion; track externally paid tax only once; don't confuse extra Roth assets with a free conversion.

### DDR-027 — Roth tax comparison is cumulative modeled tax, **not** overall NPV or after-tax inheritance value
- **Status:** ACCEPTED INTERPRETIVE LIMITATION. **Evidence:** A.
- **Decision/rationale:** Run matched simulations with conversions on and off (same seed), then compare sums of displayed Tax (est) + Conv Tax during withdrawal years. Inflation-adjusted Today's dollars are not discounted to present value; beneficiary tax after death/end of plan is not projected.
- **Source quote:** “**It is a comparison of total taxes paid during the simulated years, not a calculation of the overall financial benefit of converting.**” — Java, lines **4192–4208**, Assumptions §7d.
- **Audit rule:** Keep a full-plan tax difference while also showing ending Traditional and survivor-year taxes. Don't label the difference “net wealth gained.”

### DDR-028 — Pre-withdrawal display omits tax, while MAGI continues for IRMAA lookback
- **Status:** ACCEPTED DISPLAY DESIGN. **Evidence:** B/A.
- **Decision/rationale:** Avoid presenting taxes as part of the spending table before portfolio withdrawals start; still track relevant income/MAGI for subsequent IRMAA.
- **Source quote:** Audit3 final review, “Retained limitations”: “**No estimated tax display before portfolio withdrawals begin; pre-withdrawal MAGI is retained for two-year IRMAA history.**” See Java, lines **4180–4185**, brokerage assumption §7c.
- **Audit rule:** Do not classify blank early tax cells as missing calculation when the model intentionally leaves them blank; independently check MAGI history.

### DDR-029 — Actual prior-year MAGI takes precedence; missing prior MAGI uses an estimate
- **Status:** ACCEPTED FALLBACK SIMPLIFICATION. **Evidence:** B/A.
- **Decision/rationale:** IRMAA follows a two-year lookback; if a user leaves past MAGI unknown, the engine uses a stand-in, which must not treat this year's one-off brokerage sale as if it occurred two years before.
- **Source quote:** Audit3 final review, changed-source coverage #2: “**The estimate is not a substitute for real historical MAGI if available.**” Java, lines **1907–1920**, prior-MAGI tooltip.
- **Audit rule:** Test explicit values, zero/estimate behavior, sale-year stand-in, and future actual MAGI lookback separately.

## E. Brokerage, stochastic assumptions, and other accepted scope boundaries

### DDR-030 — Brokerage modeling is a one-year sale and cash transfer, not a full taxable-account ledger
- **Status:** ACCEPTED SCOPE LIMIT. **Evidence:** A/B.
- **Decision/rationale:** Only sale proceeds and cost basis are entered for the current plan's first year; all gains are assumed long-term, net losses have the limited modeled deduction, and proceeds are moved into the separate holding pool. The model does not simulate holdings, dividends, short-term gains or loss carryforwards.
- **Source quote:** Audit3 final review, “Retained limitations”: “**Brokerage integration represents a one-year sale assumed long-term. The code explicitly excludes short-term gains, dividends, loss carryforwards, and broader brokerage-account modeling.**” Also Java, lines **1252–1272**, taxable brokerage field tooltips.
- **Audit rule:** Verify sale-year capital gains, NIIT, MAGI and cash accounting; do not require a full brokerage tax-lot engine unless scope is changed.

### DDR-031 — A sale's full incremental tax survives partial first-year spending proration
- **Status:** ACCEPTED CORRECTION, CLOSED IN AUDIT3. **Evidence:** B.
- **Decision/rationale:** Partial-year withdrawals may prorate spending but a real sale in that year can incur a full-year incremental capital-gains tax. Preserve the independent sale tax increment in both median table and fan paths.
- **Source quote:** Audit3 final review, changed-source coverage #1: “**The model keeps `tax = (tax - saleTaxRow) * startPror + saleTaxRow`.**”
- **Audit rule:** Re-run January/July/December sale/no-sale, gain/loss, NIIT and fan-path accounting after sale or partial-year tax changes.

### DDR-032 — Inflation has a 0% floor with mean compensation, and return losses are capped at -100%
- **Status:** SOURCE-DEFINED STOCHASTIC CHOICES. **Evidence:** A.
- **Decision/rationale:** No negative inflation draws (consistent with SS COLA modeling); shift the underlying normal mean so truncation does not unintentionally raise the entered long-run inflation mean. Portfolio annual returns cannot be below -100%.
- **Source quote:** “**after the 0% floor, the draws average exactly the entered mean**” — Java, lines **15564–15580**; “**a portfolio cannot lose more than everything**” — lines **15557–15564**.
- **Audit rule:** Test statistical mean, nonnegative inflation and return lower bound. Do not replace with untruncated normal without user approval.

### DDR-033 — Market forecast is a user-selected constant return and deviation, not a glide path
- **Status:** ACCEPTED SCOPE / INPUT CHOICE. **Evidence:** A/C.
- **Decision/rationale:** Use one net nominal return and volatility for the entire horizon; no asset-allocation glide path. Historical deviation is a reference point for a selected mix, not a guarantee of future results.
- **Source quote:** “**No asset allocation glide path (single return/stdDev for full horizon)**” — Java, lines **96–100**; handoff §3 November 14 Q28 describes the reference 60/40 historical deviation and user-selected net return.
- **Audit rule:** Ask if portfolio allocation actually changes before treating this as a material missing feature; do not insert automatic market forecasts not present in the scenario.

### DDR-034 — Guyton–Klinger is illustrative; Pro PoS is authoritative
- **Status:** ACCEPTED SEPARATE-ENGINE LIMITATION. **Evidence:** B/A.
- **Decision/rationale:** The GK tab is a comparative illustration with intentionally different cash and conversion treatment, not the authoritative optimizer/Pro result.
- **Source quote:** Audit3 final review, “Retained limitations”: “**Guyton–Klinger is illustrative; Pro PoS remains authoritative.**” Java, lines **96–100**: “**Guyton-Klinger tab makes no Roth conversions and keeps no Money Mkt reserve**.”
- **Audit rule:** Do not demand exact Pro/GK parity or change GK's scope without a new requirement. Test GK regressions when its source actually changes.

### DDR-035 — SS Optimizer comparisons use a fixed seed and invalidate relevant saved results
- **Status:** ACCEPTED DETERMINISM / RESUME POLICY. **Evidence:** A/C.
- **Decision/rationale:** Claim-date candidates must face common market draws for a fair ranking. Inputs and scorer version determine whether saved results can be reused.
- **Source quote:** “**The batch is a COMPARISON, not a forecast. Its job is to judge thousands of claim dates against one another, which requires a future they all share**” — Java, lines **7622–7629**; handoff §3 November 14 Q30 records scorer version 25 after reserve interest correction.
- **Audit rule:** Compare optimizer and direct Pro with identical inputs/seeds; check that financially meaningful changes invalidate the cache while display-only changes don't cause financial differences.

## F. OPEN issues — **not** accepted limitations

### OPEN-001 — 2027 capital-gain brackets not selected with 2027 tax table
- **Status:** **OPEN FINANCIAL DEFECT (scenario-dependent)**, Audit5, November 18.
- **Evidence and quote:** `audit5/Audit5_Nov18_Review.md`, priority finding #1: “**2027 capital-gain band set not implemented [open, scenario-dependent financial issue]**”; Java, lines **16334–16381**, uses `LTCG_0_TOP_*_2026` / `LTCG_15_TOP_*_2026` even for the selected 2027 tax set.
- **Scope:** The supplied November 16 scenario has no taxable brokerage sale, so that specific output was not affected. **Action:** Verify and add correct tax-year LTCG thresholds, then rerun 0%/15%/20% sale boundaries, tax-year selection, partial-year tax and persistence regressions.

### OPEN-002 — Old scenario can silently retain 2026 tax base in a 2027-start plan
- **Status:** **ACCEPTED BACKWARD-COMPATIBILITY BEHAVIOR; OPEN USER-WARNING/CONFIGURATION RISK**, Audit5.
- **Evidence and quote:** Java, lines **11058–11069**: “**A file saved before Q38 has no year ... so it loads as 2026 and its results are unchanged**.” Audit5 review #2 calls it “**expected compatibility, meaningful user-configuration risk**.”
- **Action:** Show active tax-base year clearly and consider a nonblocking mismatch warning; do not silently overwrite users' saved thresholds.

### INVESTIGATE-003 — Single unreproduced full-fidelity cross-process outlier
- **Status:** **[Unverified]**, not an accepted defect and not formally explained.
- **Evidence and quote:** Audit5 review #3: “**One standalone high-fidelity outlier [unverified, not a confirmed code defect]**.” Controlled same-JVM full-fidelity 1-vs-4 and later standalone repeats matched exactly.
- **Action:** Preserve raw log and repeat controlled, isolated-process tests if the symptom reappears; do not claim a threading race without reproduction.

### REVIEW-004 — Other source-documented ideas not yet accepted for implementation
- **Status:** **NOT ACCEPTED AS DEFECTS** merely by appearing in a queue.
- **Evidence:** `riskbased-probofsuccess-handoff-2026-10-30.md`, §5, “Open findings (flagged, not queued),” lists additional follow-ups (e.g., GK extreme bridge/unfunded draw, Medicare transition timing, and UI grammar). Some entries in that dated list were subsequently fixed, so it **cannot be treated as a current all-open defect inventory** without checking later source/builds.
- **Action:** If revisiting, revalidate against the *latest* code and obtain a user decision before changing accepted behavior.

## Required protocol for future comprehensive audits

1. **Identify exact source version, scenarios, tax-base year, random seeds, fidelity and thread counts** before testing. Record hashes.
2. **Read this register first**; distinguish the behavior the user deliberately chose from implementation errors and merely unmodeled domains.
3. **Verify math independently where feasible**, especially solver target vs Actual PoS, two-reserve ledger, SS timing, taxes, RMDs, survivor and conversion path accounting.
4. **Execute baseline parity when features are off**, plus targeted boundary tests when features are on, and GUI save/reload, optimizer cache and threading checks as affected.
5. **Use a three-way disposition:** confirmed defect; accepted limitation/design (with evidence); or unverified/open question. Never silently rewrite an accepted approximation.
6. **Reopen a locked choice only on explicit user request, a new requirement, a material independently demonstrated failure, or a change in governing rules**. Present alternatives and the impact before modifying the design.
7. **Update this register when a decision is made**: add the date, exact user rationale if available, source and test references, and status change. Do not replace historical rationales with a shorter current assumption.

## Outstanding evidence collection for v0.2

- Attach/export original complete conversations with “Audit” in their titles. The conversation context available to this initial pass did not supply all original transcripts; therefore some decisions are sourced to the program's embedded documentation or the handoff rather than an exact original user statement.
- Preserve the original accepted baselines and audit evidence ZIP files with their hashes, and link precise dynamic test cases to decision IDs.
- Mark records **User confirmed (verbatim)** when the original message is recovered; until then, **A/B/C evidence establishes the documented behavior, not necessarily every nuance of the user's words**.
- Update `OPEN-001` and `OPEN-002` after the next developer makes and tests the tax-year changes.

---

### Source index

- **[S1]** `RiskBased_OptSocSec_v2(1).java` — current uploaded source (19,284 raw lines; SHA-256 `136e864b7d0925023567dc94c3453e5340892e1015c937a0919b6bc35507626f`); cited by line number and/or embedded Assumptions & Methods section.
- **[S2]** `riskbased-probofsuccess-handoff-2026-10-30.md` — uploaded current handoff, as of November 18, 2026; dated implementation decisions and open findings (§3, §5).
- **[S3]** `audit3/Audit3_Final_Coverage_Acceptance_Review.md` — Audit3 decision, tested scope and retained limitations (November 2).
- **[S4]** `Audit4_Final_Coverage_Acceptance_Review.md` — Audit4 decision, 434 tests and retained limitations (November 14).
- **[S5]** `audit5/Audit5_Nov18_Review.md` — Audit5 conditional acceptance and open findings (November 18).

**Maintenance instruction:** Keep this file under version control beside the Java source. The status and the rationale are part of the specification; edits to the implementation do not automatically supersede them.
