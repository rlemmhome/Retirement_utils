# Retirement Simulator — Design Decisions and Accepted Limitations Register

**Version:** 0.2 — provenance and regression cross-references  
**Prepared:** November 19, 2026 (America/Phoenix)  
**Subject:** `RiskBased_OptSocSec_v2` Java retirement-planning simulator  
**Latest source examined:** `RiskBased_OptSocSec_v2(1).java`, November 18, 2026, 9:34 AM MST  
**Audit context:** Audit3 closed (November 2); Audit4 closed (November 14); Audit5 (November 18) **remains open** for the capital-gains tax-year issue.

## Purpose and authority

This document preserves *why* the program deliberately works in ways that might initially surprise another developer, actuary, financial planner, or AI auditor. **It is a design-control document, not a claim that every implementation has been independently verified.** An accepted modeling limitation must be tested for correct implementation, rather than automatically rewritten as if it were a defect. Conversely, an independently identified defect is not excused by a general design philosophy.

**Evidence levels:** **A** = directly quoted from the attached Java source / its Assumptions & Methods text; **B** = directly quoted from a completed audit review; **C** = summarized in the attached dated developer handoff (not necessarily the user's exact words); **D** = relevant earlier-chat context recoverable in summary but not verified against an entire original transcript. The v0.2 traceability appendix adds exact test-log pointers and distinguishes **direct test**, **indirect test**, and **no matching executable evidence**. This does not upgrade evidence-grade D user recollections into verbatim citations. File/line references below refer to the uploads as available on November 18, 2026; they may shift in future revisions. Quotes are reproduced from those files (HTML-encoded punctuation may be normalized).

### Audit conversation coverage

Prior project/chat context identifies conversations using the word **Audit**, including **“Audit of methodology and code, Kitces Go-Go Multiplier”** (April 2026), **“Audit Retirement Planning Code”** (October 2026), **“Audit2 Retirement Planning Code”** (October 2026), and **“Audit3 Retirement Audit Continuation” / “Audit3 Retirement Planning Code”** (November 2026). Relevant prior-discussion decisions were recoverable, but **a complete, exact-text archive of every titled chat was not available for a line-by-line search**. Thus the dated source, audit reviews, and developer handoff—not presumed verbatim access to every old conversation—are the evidentiary foundation of this file. The v0.2 conversation catalog identifies discoverable titles/dates, but **verified ChatGPT conversation URLs/IDs were unavailable**; it does not manufacture hyperlinks to chats. The anchors below link to the *conversation catalog in this document*, not to ChatGPT.

## Audit acceptance history

| Audit | Reviewed baseline / scope | Documented disposition | What it does *not* mean |
|---|---|---|---|
| Audit3, Nov 2, 2026 | `RiskBased_OptSocSec_v2 (5).java`; partial-year sale tax, IRMAA, reserve/RMD/survivor, optimizer, GUI | **Accepted with documented limitations**; 316 checks, no failures | Not exhaustive testing or independent tax-law certification |
| Audit4, Nov 14, 2026 | November 14 source; split cash reserves, tax funding, already-claimed SS, conversion comparison, persistence | **Accepted with documented limitations**; 434 checks, no failures | Not a guarantee for every plan or every stochastic trajectory |
| Audit5, Nov 18, 2026 | November 18 source; 2027 tables, interface, optimizer, RMD comparison, cash interest | **Conditionally accepted for the uploaded zero-sale scenario; Audit5 remains open** | 2027 long-term capital-gain bands have an unresolved integration gap |

**Evidence:** `audit3/Audit3_Final_Coverage_Acceptance_Review.md`, “Decision”: “**ACCEPTED WITH DOCUMENTED LIMITATIONS**”; `Audit4_Final_Coverage_Acceptance_Review.md`, “Decision”: same; `audit5/Audit5_Nov18_Review.md`, “Disposition”: “**Conditional financial acceptance for the supplied zero-sale scenario; general Audit5 change-set is NOT fully closed.**”

## A. Planning objectives, withdrawals and probabilities of success

<a id="ddr-001"></a>
### DDR-001 — An annually updated plan, not a one-time immutable forecast
- **Status:** ACCEPTED DESIGN. **Evidence:** A.
- **Decision/rationale:** Re-enter current account balances and revise actual assumptions at least annually (and as needed); avoid pretending a multi-decade static forecast stays exact.
- **Source quote:** “**This simulator is designed for annual reassessment, not a permanently fixed retirement plan.**” — `RiskBased_OptSocSec_v2(1).java`, lines **4293–4303**, Assumptions §15.
- **Audit rule:** Do not demand automatic year-to-year accounting for off-model personal actions as a substitute for the chosen annual update process. Reopen if annual re-entry becomes impractical or silently loses financial state.
- **Provenance/test cross-reference (v0.2):** Conversations [C7](#conversation-provenance-catalog); tests [E4B](#regression-evidence-catalog), [E4H](#regression-evidence-catalog). **Coverage:** I: annual scenario/persistence exercised; annual user updates remain a documented policy.

<a id="ddr-002"></a>
### DDR-002 — Surplus is discretionary spending headroom, deliberately not reinvested
- **Status:** ACCEPTED DESIGN. **Evidence:** A.
- **Decision/rationale:** Green annual surplus pays for travel and unbudgeted discretionary spending. Any amount personally saved is re-entered in a later run; do not automatically accrue it in the model.
- **Source quote:** “**Surplus is intentionally available for travel and discretionary spending excluded from baseline living expenses. It is not automatically saved.**” — Java, lines **4243–4247**, Assumptions §15 item 1.
- **Audit rule:** A large lifetime sum of green surplus is *not* evidence of an omitted portfolio asset. Test that income minus spending equals surplus and that no surplus is secretly reinvested.
- **Provenance/test cross-reference (v0.2):** Conversations [C1](#conversation-provenance-catalog), [C7](#conversation-provenance-catalog); tests [E4A](#regression-evidence-catalog), [E4B](#regression-evidence-catalog). **Coverage:** I: cashflow/surplus reconciled; no dedicated assertion that every surplus is never reinvested.

<a id="ddr-003"></a>
### DDR-003 — The user accounts for medical and other expenses funded outside the model
- **Status:** ACCEPTED PLANNING RESPONSIBILITY. **Evidence:** A.
- **Decision/rationale:** Some transitional expenses are knowingly paid from external cash, outside simulated accounts; a yearly update is the correction mechanism.
- **Source quote:** “**The user is expected to recognize and account for these external funding needs.**” — Java, lines **4264–4271**, Assumptions §15 item 5.
- **Audit rule:** Verify the screen states the funding boundary. Do not infer absent expenses are automatically financed by the portfolio. Reopen if users reasonably mistake excluded expenses for modeled obligations.
- **Provenance/test cross-reference (v0.2):** Conversations [C7](#conversation-provenance-catalog); tests No dedicated matched log identified. **Coverage:** N: external medical bills are outside the model; source documents user responsibility.

<a id="ddr-004"></a>
### DDR-004 — The target PoS sets a *fixed-withdrawal solver* amount; outer paths re-solve annually
- **Status:** FOUNDATIONAL METHODOLOGY. **Evidence:** A.
- **Decision/rationale:** Candidate withdrawals face fixed, inflation-linked spending schedules within each inner solver trial. The outer simulation then re-solves each year using its current/median balances. The target (often 80%) is a constraint, not a claim that Actual PoS must equal 80%.
- **Source quote:** “**Probability of Success (PoS) is the input constraint; the maximum sustainable dollar withdrawal is the output. The engine re-solves the sustainable draw each year**” — Java, lines **4479–4485**, Assumptions §1; see methodological overview, lines **34–58**.
- **Audit rule:** Never substitute annually cut spending into the inner fixed-withdrawal test without deliberate redesign. Independently check target solver calibration and outer Actual PoS separately.
- **Provenance/test cross-reference (v0.2):** Conversations [C2](#conversation-provenance-catalog), [C3](#conversation-provenance-catalog), [C4](#conversation-provenance-catalog); tests [E3F](#regression-evidence-catalog), [E4E](#regression-evidence-catalog), [E5A](#regression-evidence-catalog). **Coverage:** D: optimizer/Pro and annual solve results; historical inner-solver Stage 8/9 details not in packaged archives.

<a id="ddr-005"></a>
### DDR-005 — Actual PoS measures the invested portfolio only, not external cash
- **Status:** EXPLICIT USER DESIGN; LOCKED UNTIL REOPENED. **Evidence:** A/B.
- **Decision/rationale:** External cash is not guaranteed to remain available or to be replenished, so the model should not count it as portfolio survival or use it to qualify SS Optimizer solutions.
- **Source quote:** “**Actual PoS answers one question: did the portfolio ever reach $0?**” — Java, lines **4500–4506**, Assumptions §1. Audit3 review, “Retained limitations”: “**No future discretionary user contributions are guaranteed or counted toward portfolio-only Actual PoS.**”
- **Audit rule:** Test portfolio-only PoS independently from cash-reserve-aided outcomes. Do not change the optimizer gate to reserve-inclusive survival without a new user decision.
- **Provenance/test cross-reference (v0.2):** Conversations [C3](#conversation-provenance-catalog), [C4](#conversation-provenance-catalog), [C7](#conversation-provenance-catalog); tests [E3F](#regression-evidence-catalog), [E4E](#regression-evidence-catalog), [E5H](#regression-evidence-catalog). **Coverage:** D: portfolio-only Actual PoS compared and independently recounted.

<a id="ddr-006"></a>
### DDR-006 — Cash-supported lifetime completion is an additional metric, not portfolio PoS
- **Status:** ACCEPTED INTERPRETATION. **Evidence:** A.
- **Decision/rationale:** The simulator may report that cash reserves carried additional depleted-portfolio paths to completion. This answers a different question from whether the investment portfolio stayed positive.
- **Source quote:** “**These metrics answer different questions. The 80% target governs the fixed-withdrawal solver, not a guarantee of reserve-supported success.**” — Java, lines **4259–4265**, Assumptions §15 item 4.
- **Audit rule:** Preserve both the portfolio-only and combined-resource numbers; independently recount each path. Do not equate “never ran out completely” with “no year had less income than planned spending.”
- **Provenance/test cross-reference (v0.2):** Conversations [C4](#conversation-provenance-catalog), [C7](#conversation-provenance-catalog); tests [E3C](#regression-evidence-catalog), [E4G](#regression-evidence-catalog), [E5H](#regression-evidence-catalog). **Coverage:** D: cash-rescued paths and terminal shortfall counts.

<a id="ddr-007"></a>
### DDR-007 — Short-year adequacy and lifetime survival remain separate metrics
- **Status:** ACCEPTED INTERPRETATION. **Evidence:** A.
- **Decision/rationale:** A simulation can retain positive portfolio assets but experience an unpaid spending gap due to withdrawal constraints. The shortfall count must not be silently replaced by a survival rate.
- **Source quote:** “**Actual PoS can be 100% while this count is not zero: the money lasted, but some years came up short.**” — Java, lines **2643–2656**, Actual PoS tooltip.
- **Audit rule:** Test the shortfall arrays separately from terminal portfolio balances; label both clearly.
- **Provenance/test cross-reference (v0.2):** Conversations [C4](#conversation-provenance-catalog), [C7](#conversation-provenance-catalog); tests [E3C](#regression-evidence-catalog), [E5H](#regression-evidence-catalog). **Coverage:** D: shortfalls and survival are separate; GUI count verification.

<a id="ddr-008"></a>
### DDR-008 — The final five years deliberately spend down remaining portfolio assets
- **Status:** ACCEPTED FINANCIAL METHOD. **Evidence:** A/B.
- **Decision/rationale:** In the final five plan years the program adds a top-up above the normally solved draw to close cash-flow gaps, exhausting the portfolio before drawing external cash if needed. This can *lower* portfolio-only Actual PoS even when it fulfills late spending needs.
- **Source quote:** “**In the last 5 plan years ... The extra comes first from the portfolio ... and, once the portfolio is empty, from the cash reserve**” — Java, lines **4546–4559**, Assumptions §1.
- **Audit rule:** Verify final-years cash transfers, tax gross-up, reserve ordering, exactly-zero depletion and no duplicated funding; do not equate planned late drawdown with an early simulation error.
- **Provenance/test cross-reference (v0.2):** Conversations [C1](#conversation-provenance-catalog), [C4](#conversation-provenance-catalog); tests [E3C](#regression-evidence-catalog), [E4G](#regression-evidence-catalog). **Coverage:** D: terminal five-year top-up, reserve ordering and exhaustion.

<a id="ddr-009"></a>
### DDR-009 — The 22% planned withdrawal cap is distinct from late-life top-ups
- **Status:** SOURCE-DEFINED BEHAVIOR. **Evidence:** A.
- **Decision/rationale:** The regular solver's maximum withdrawal still applies; a separately identifiable final-years top-up can exceed the solved amount.
- **Source quote:** “**The Pro PoS solver is unchanged: its ceiling (22% of the balance) still sizes every planned draw, and the top-up is added after it**” — Java, lines **4551–4557**, Assumptions §1.
- **Audit rule:** Do not report a final-year over-22% actual withdrawal as automatically violating the solver cap. Check that the extra amount is classified as top-up.
- **Provenance/test cross-reference (v0.2):** Conversations [C1](#conversation-provenance-catalog), [C4](#conversation-provenance-catalog); tests [E3C](#regression-evidence-catalog), [E4G](#regression-evidence-catalog). **Coverage:** I: late top-up and cap interactions; dedicated 22% exact-boundary log not separately identified.

<a id="ddr-010"></a>
### DDR-010 — Go-go / slow-go multipliers apply to portfolio draws, not total guaranteed income
- **Status:** EXPLICIT USER DESIGN. **Evidence:** A.
- **Decision/rationale:** A multiplier like 1.6 or 1.7 can be necessary for a desired 20–30% increase in total discretionary income, because Social Security and other fixed income are not multiplied.
- **Source quote:** “**The multiplier applies to portfolio withdrawals, not total household income. Earlier multipliers produced less than the intended 20%–30% increase in overall spendable income.**” — Java, lines **4288–4293**, Assumptions §15 item 9.
- **Audit rule:** Compare the resulting whole-household spending headroom, not just the multiplier's numeric change. Do not impose typical multiplier ranges as a defect finding.
- **Provenance/test cross-reference (v0.2):** Conversations [C1](#conversation-provenance-catalog), [C7](#conversation-provenance-catalog); tests [E4B](#regression-evidence-catalog), [E5A](#regression-evidence-catalog). **Coverage:** I: saved multiplier inputs and expected draws verified; no stand-alone test of household-spending interpretation.

<a id="ddr-011"></a>
### DDR-011 — Distinguish base sustainable withdrawal from *actual* first-year draw
- **Status:** APPROVED PRESENTATION PRINCIPLE. **Evidence:** A.
- **Decision/rationale:** Actual withdrawal also includes the go-go multiplier, Social Security bridge and eligible top-ups. A small base headline and much larger actual portfolio draw can both be correct.
- **Source quote:** “**The initial portfolio withdrawal substantially exceeds the headline base withdrawal rate**” and “**The additional withdrawal is an intentional trade-off for delaying Social Security**” — Java, lines **4251–4258**, Assumptions §15 item 3.
- **Audit rule:** Tie first-year Actual wd to the displayed components instead of treating a difference from the headline as double-counting.

## B. External cash reserves and account mechanics
- **Provenance/test cross-reference (v0.2):** Conversations [C7](#conversation-provenance-catalog); tests [E4B](#regression-evidence-catalog), [E5B](#regression-evidence-catalog). **Coverage:** D: base vs actual first-year withdrawal shown and checked.

<a id="ddr-012"></a>
### DDR-012 — The regular savings reserve primarily protects against sequence-of-returns risk
- **Status:** EXPLICIT USER DESIGN. **Evidence:** A.
- **Decision/rationale:** Keep a separate, off-portfolio savings/cash account as a financial backstop; do not presume it must pay ordinary Roth-conversion taxes.
- **Source quote:** “**The savings account primarily protects against sequence-of-returns risk. Users who wish to allocate cash to conversion taxes can explicitly use the separate holding account for brokerage sales.**” — Java, lines **4271–4279**, Assumptions §15 item 6.
- **Audit rule:** Preserve funding separation and priority. Do not automatically redirect the regular reserve into conversions.
- **Provenance/test cross-reference (v0.2):** Conversations [C4](#conversation-provenance-catalog), [C5](#conversation-provenance-catalog), [C7](#conversation-provenance-catalog); tests [E3C](#regression-evidence-catalog), [E4G](#regression-evidence-catalog). **Coverage:** D: protected regular account, funding restrictions and top-up.

<a id="ddr-013"></a>
### DDR-013 — Early savings coverage is optional; late five-year backstop is always available
- **Status:** ACCEPTED USER-CONTROLLED BEHAVIOR. **Evidence:** A.
- **Decision/rationale:** The early-shortfall checkbox can draw from cash during earlier retirement years; otherwise the program preserves it for the final-years backstop.
- **Source quote:** “**In the last 5 plan years the cash reserve always backs up the portfolio ... This box decides whether it also pays short years earlier**” — Java, lines **1273–1285**, early coverage tooltip.
- **Audit rule:** Test both checkbox states and full/partial reserve exhaustion; never infer the checkbox is on merely because the reserve exists.
- **Provenance/test cross-reference (v0.2):** Conversations [C4](#conversation-provenance-catalog), [C5](#conversation-provenance-catalog); tests [E3C](#regression-evidence-catalog), [E4G](#regression-evidence-catalog), [E5H](#regression-evidence-catalog). **Coverage:** D: early coverage on/off and last-five reserve behavior.

<a id="ddr-014"></a>
### DDR-014 — SS Optimizer **ignores** optional early savings coverage
- **Status:** EXPLICIT USER DESIGN; LOCKED. **Evidence:** A/C.
- **Decision/rationale:** Social Security claiming dates are irrevocable/long-lasting decisions and should not qualify on the expectation that users will spend cash reserves to hide early deficits.
- **Source quote:** “**The SS Optimizer ignores this box and scores every claim-date pair with it unchecked. A Social Security claim date is a one-time, lifelong decision**” — Java, lines **1301–1308**, early coverage tooltip; see developer handoff, §3, November 17 Q39.
- **Audit rule:** Check optimizer copy forces the flag off, its fingerprint stays stable across this UI toggle, and the original Pro simulation remains free to use the flag.
- **Provenance/test cross-reference (v0.2):** Conversations [C6](#conversation-provenance-catalog), [C8](#conversation-provenance-catalog); tests [E5E](#regression-evidence-catalog). **Coverage:** D: real optimizer batch forces early coverage off; source/handoff Q39.

<a id="ddr-015"></a>
### DDR-015 — Two cash pools are ordered and separately accounted for
- **Status:** ACCEPTED DESIGN. **Evidence:** A/C.
- **Decision/rationale:** Ordinary cash is spent first on eligible spending gaps; the brokerage holding pool is used second. Only the holding pool may be explicitly designated to fund conversion tax.
- **Source quote:** “**the savings account pays first; the holding account ... pays only once it is empty.**” — Java, lines **1289–1291**; “**The savings account is not used for this.**” — Java, lines **1333–1338**.
- **Audit rule:** Independently reconstruct both balances and ensure no dollar is spent twice; verify the displayed total equals their sum.
- **Provenance/test cross-reference (v0.2):** Conversations [C5](#conversation-provenance-catalog); tests [E4A](#regression-evidence-catalog), [E4G](#regression-evidence-catalog), [E4H](#regression-evidence-catalog). **Coverage:** D: dual-pool ledger, exhaustion and persistence.

<a id="ddr-016"></a>
### DDR-016 — RMD overages go into the outside cash account, not back into the IRA
- **Status:** SOURCE-DEFINED ACCOUNTING. **Evidence:** A.
- **Decision/rationale:** Mandatory Traditional distributions above planned spending are already taxed and become external savings.
- **Source quote:** “**the reserve starts at the user's prior-years carry-in ... then (b) adds that year's RMD overage**” — Java, lines **12263–12279**, median cash accounting; “**forced RMDs, which go to the savings account**” — Java, lines **4212–4216**.
- **Audit rule:** Test year-end cash additions and no duplicate withdrawal; do not leave tax-paid RMD overages in Traditional accounts.
- **Provenance/test cross-reference (v0.2):** Conversations [C3](#conversation-provenance-catalog), [C4](#conversation-provenance-catalog); tests [E3D](#regression-evidence-catalog), [E3C](#regression-evidence-catalog). **Coverage:** D: prewithdraw RMD overage and savings recurrence.

<a id="ddr-017"></a>
### DDR-017 — Cash reserve interest is modeled approximately as inflation, including year one
- **Status:** ACCEPTED SIMPLIFICATION; TIMING FIXED NOV 14. **Evidence:** A/C.
- **Decision/rationale:** Both pools are assumed to preserve roughly their real value, with annual nominal growth and taxable interest. Revised Q30 uses each *current* year's inflation rather than lagging a year.
- **Source quote:** “**Bob's assumption is money-market return ~= inflation, i.e. ~0% real**” — Java, lines **12267–12278**; handoff §3, November 14 Q30: “**both pools ... grow by each year's OWN inflation**.”
- **Audit rule:** Test first-year interest, later interest tax, and Real/Nominal balance displays; don't demand a separate variable cash yield unless requirements change.

## C. Social Security, survivor transitions, and year sequencing
- **Provenance/test cross-reference (v0.2):** Conversations [C5](#conversation-provenance-catalog), [C8](#conversation-provenance-catalog); tests [E5A](#regression-evidence-catalog), [E5H](#regression-evidence-catalog). **Coverage:** I: new full-engine reserve interest in 2027; handoff Q30 describes the direct implementation check.

<a id="ddr-018"></a>
### DDR-018 — The Social Security bridge deliberately funds delayed claiming
- **Status:** EXPLICIT USER TRADE-OFF. **Evidence:** A.
- **Decision/rationale:** Replacing not-yet-started Social Security with portfolio withdrawals is an intentional cost of selected optimized claiming dates, not phantom income.
- **Source quote:** “**The additional withdrawal is an intentional trade-off for delaying Social Security to optimized claiming dates. The bridge amount and its financial consequences are understood and accepted.**” — Java, lines **4253–4258**, Assumptions §15 item 3.
- **Audit rule:** Verify bridge start/stop dates per spouse, first-month proration, tax gross-up and the resulting funded portfolio draw.
- **Provenance/test cross-reference (v0.2):** Conversations [C2](#conversation-provenance-catalog), [C3](#conversation-provenance-catalog), [C7](#conversation-provenance-catalog); tests [E3A](#regression-evidence-catalog), [E4B](#regression-evidence-catalog). **Coverage:** D: first-year bridge and annual withdrawals (accepted tax gross-up is a separate limitation).

<a id="ddr-019"></a>
### DDR-019 — Bridge tax gross-up is an intentionally approximate, performance-conscious calculation
- **Status:** ACCEPTED LIMITATION; DO NOT AUTOMATICALLY REOPEN. **Evidence:** A/B.
- **Decision/rationale:** Bridge schedules are constructed before the full Monte Carlo path and withdrawal solver outcomes exist. Estimate the year's marginal tax position rather than performing a second full solve for exact bridge tax in every trial.
- **Source quote:** “**The schedule has to be built before the simulation runs ... so it cannot know the draw the PoS solver will actually pick.**” — Java, lines **5682–5693**, Assumptions §5; Audit3 final review: “**SS bridge tax gross-up is an intentionally accepted performance-conscious approximation.**”
- **Accepted boundary:** The tax calculation estimates standard/age-65/senior deductions and ordinary income, but excludes later-billed IRMAA; RMDs may not be known when the schedule is made. A difference from exact realized tax is not on its own a defect. Reopen if estimation error becomes material to financial decisions.
- **Provenance/test cross-reference (v0.2):** Conversations [C3](#conversation-provenance-catalog), [C4](#conversation-provenance-catalog); tests [E3A](#regression-evidence-catalog), [E4B](#regression-evidence-catalog). **Coverage:** I: engine and tax paths exercised; no comparison to an exact second-pass bridge calculation required.

<a id="ddr-020"></a>
### DDR-020 — Actual chronology: prior-Dec31 RMD base, January draw, returns on remainder, December conversion
- **Status:** FOUNDATIONAL REQUIREMENT. **Evidence:** A.
- **Decision/rationale:** Match real cash-transfer practice. RMDs are based on the preceding December 31 Traditional balance; planned withdrawal leaves in January; only remaining investments earn returns; conversion takes place late December after satisfying the year's RMD.
- **Source quote:** “**(1) the RMD is set from the prior December 31 Traditional balance; (2) in January ... withdrawal ... leaves the portfolio ... (4) in late December the Roth conversion is sized**” — Java, lines **4486–4495**, Assumptions §1.
- **Audit rule:** Do not move withdrawals to year-end, grow withdrawn dollars as investments, or convert RMD dollars. Re-test all event ordering after time-sequence changes.
- **Provenance/test cross-reference (v0.2):** Conversations [C2](#conversation-provenance-catalog), [C3](#conversation-provenance-catalog), [C4](#conversation-provenance-catalog); tests [E3C](#regression-evidence-catalog), [E3D](#regression-evidence-catalog), [E4G](#regression-evidence-catalog). **Coverage:** D: RMD prior-year base, pre-draw order and December conversion coexistence.

<a id="ddr-021"></a>
### DDR-021 — Already-claimed Social Security dates are held fixed by the optimizer
- **Status:** ACCEPTED OPTIMIZER BEHAVIOR. **Evidence:** A/C.
- **Decision/rationale:** Dates before the current month are historical facts, not adjustable choices; only the unclaimed household member's date should be optimized.
- **Source quote:** “**someone who has already claimed (SS start before this month) is held at that date**” — Java, lines **7681–7683**, SS Optimizer logic; developer handoff §3, Nov 14 Q27.
- **Audit rule:** Validate past claim date preservation, available spouse search, both-claimed disabled button, and fingerprint invalidation for locked dates.
- **Provenance/test cross-reference (v0.2):** Conversations [C5](#conversation-provenance-catalog); tests [E4F](#regression-evidence-catalog), [E5E](#regression-evidence-catalog). **Coverage:** D: held date, button behavior and candidate grid.

<a id="ddr-022"></a>
### DDR-022 — Survivor transition changes filing, SS, spending and RMDs
- **Status:** ACCEPTED SURVIVOR MODEL. **Evidence:** A/B.
- **Decision/rationale:** The death event should retain the surviving spouse's accounts and permitted larger SS benefit while changing taxation to Single at the defined transition year, with survivor-based spending and RMDs.
- **Source quote:** “**Death event (v6) -- mid-projection survivor transition**” — Java, lines **2025–2037**, Death event tooltip. Audit3 final review, “Survivor and RMD sequencing”: “**survivor-year timing, death-year filing change, conversions** ... tested.”
- **Audit rule:** Independently exercise early deaths and both identities, not just the default late-death scenario. Never treat the survivor's tax exposure as already handled by married filing status.

## D. Roth conversion goals and tax semantics
- **Provenance/test cross-reference (v0.2):** Conversations [C2](#conversation-provenance-catalog), [C3](#conversation-provenance-catalog), [C4](#conversation-provenance-catalog); tests [E3C](#regression-evidence-catalog), [E3F](#regression-evidence-catalog), [E4E](#regression-evidence-catalog). **Coverage:** D: survivor year, tax filing, RMD, SS and optimizer interactions.

<a id="ddr-023"></a>
### DDR-023 — Conversion goal is lower surviving-spouse and beneficiary tax, not necessarily lowest lifetime tax
- **Status:** USER-PRIORITY WITH SOURCE SUPPORT. **Evidence:** A/C; related explicit user priorities were also recovered from earlier Audit discussions.
- **Decision/rationale:** Prepay some tax at controlled ceilings to reduce later RMDs, survivor Single-filer brackets, and inherited Traditional balances; the portfolio need not be maximized for inheritance if doing so conflicts with retirement spending.
- **Source quote:** “**Converting pays tax now, at your current bracket, to lower two taxes the lifetime figure cannot fully see**” — Java, lines **4221–4231**, Assumptions §7d.
- **Audit rule:** Do not call a conversion strategy defective solely because it pays more taxes in an early year. Compare survivor-year taxes, Traditional balances and available spending; do not invent heir-specific rates.
- **Provenance/test cross-reference (v0.2):** Conversations [C3](#conversation-provenance-catalog), [C7](#conversation-provenance-catalog), [C8](#conversation-provenance-catalog); tests [E4C](#regression-evidence-catalog), [E5B](#regression-evidence-catalog). **Coverage:** I: lifetime, survivor, ending-Traditional comparisons; user's objective is not a solved NPV optimization.

<a id="ddr-024"></a>
### DDR-024 — Conversions use user-selected IRMAA/bracket ceilings; $40,000 is not a fixed strategy limit
- **Status:** ACCEPTED CURRENT DESIGN. **Evidence:** A/C.
- **Decision/rationale:** Fill-to-target may optimize conversions against an IRMAA tier and marginal bracket, optionally subject to a user-entered cap; newest UI permits explicit no-ceiling choices. A previously used $40,000 gross cap is not a global constraint.
- **Source quote:** “**Tier 5 (no IRMAA limit) together with 37% (no bracket limit) removes the fill ceiling ... limited only by the Fill cap, if set**” — handoff, §3 November 17 Q37; same warning quoted in Java, lines **1950–1985**.
- **Audit rule:** Check cap=0's meaning (uncapped) separately from conversions OFF; test every ceiling and verify optimizer reflects actual selected controls.
- **Provenance/test cross-reference (v0.2):** Conversations [C3](#conversation-provenance-catalog), [C8](#conversation-provenance-catalog); tests [E5C](#regression-evidence-catalog), [E5D](#regression-evidence-catalog). **Coverage:** D: fill ceiling, IRMAA/bracket boundaries, no-limit, cap and tax-year controls.

<a id="ddr-025"></a>
### DDR-025 — Conversion's marginal tax assumes living income has consumed the standard deduction
- **Status:** EXPLICITLY ACCEPTED MODEL LIMITATION. **Evidence:** A/B.
- **Decision/rationale:** Compute incremental conversion tax at stacked marginal rates without allowing a second standard deduction, even though this can overstate taxes when non-conversion income is too small to use the full deduction.
- **Source quote:** “**It does not get its own standard deduction (already consumed by spending income)**” — Java, lines **5327–5335**, Assumptions §7; Audit3 final review: “**Roth-conversion incremental tax retains the previously accepted standard-deduction-consumption assumption.**”
- **Audit rule:** Document the possible low-income overstatement; don't silently repair this accepted approximation. Escalate if a specific plan's conversion decision materially changes because of it.
- **Provenance/test cross-reference (v0.2):** Conversations [C3](#conversation-provenance-catalog), [C4](#conversation-provenance-catalog); tests [E3B](#regression-evidence-catalog), [E4C](#regression-evidence-catalog). **Coverage:** I: conversion tax path tested under accepted assumption; no independent exact low-income deduction reoptimization.

<a id="ddr-026"></a>
### DDR-026 — Conversion tax is paid out of gross conversion by default; brokerage holding pool is opt-in
- **Status:** ACCEPTED FUNDING POLICY. **Evidence:** A.
- **Decision/rationale:** Default conversion accounting moves gross from Traditional, pays tax out of that transfer, and deposits net Roth. Opt-in external funding decreases the brokerage holding pool, allowing more of the same gross conversion to reach Roth; the regular savings account stays protected.
- **Source quote:** “**By default, this program pays every conversion's tax out of the conversion itself.**” — Java, lines **4091–4097**; “**The savings account is not used for this.**” — lines **1333–1338**.
- **Audit rule:** Reconcile gross conversion = net Roth + tax funded from conversion; track externally paid tax only once; don't confuse extra Roth assets with a free conversion.
- **Provenance/test cross-reference (v0.2):** Conversations [C4](#conversation-provenance-catalog), [C5](#conversation-provenance-catalog), [C7](#conversation-provenance-catalog); tests [E4A](#regression-evidence-catalog), [E4G](#regression-evidence-catalog), [E4H](#regression-evidence-catalog). **Coverage:** D: source/financial ledger proves funding order and external tax payment.

<a id="ddr-027"></a>
### DDR-027 — Roth tax comparison is cumulative modeled tax, **not** overall NPV or after-tax inheritance value
- **Status:** ACCEPTED INTERPRETIVE LIMITATION. **Evidence:** A.
- **Decision/rationale:** Run matched simulations with conversions on and off (same seed), then compare sums of displayed Tax (est) + Conv Tax during withdrawal years. Inflation-adjusted Today's dollars are not discounted to present value; beneficiary tax after death/end of plan is not projected.
- **Source quote:** “**It is a comparison of total taxes paid during the simulated years, not a calculation of the overall financial benefit of converting.**” — Java, lines **4192–4208**, Assumptions §7d.
- **Audit rule:** Keep a full-plan tax difference while also showing ending Traditional and survivor-year taxes. Don't label the difference “net wealth gained.”
- **Provenance/test cross-reference (v0.2):** Conversations [C5](#conversation-provenance-catalog), [C7](#conversation-provenance-catalog); tests [E4C](#regression-evidence-catalog), [E5B](#regression-evidence-catalog). **Coverage:** D: same-seed lifetime tax and displayed comparisons; not a present-value test.

<a id="ddr-028"></a>
### DDR-028 — Pre-withdrawal display omits tax, while MAGI continues for IRMAA lookback
- **Status:** ACCEPTED DISPLAY DESIGN. **Evidence:** B/A.
- **Decision/rationale:** Avoid presenting taxes as part of the spending table before portfolio withdrawals start; still track relevant income/MAGI for subsequent IRMAA.
- **Source quote:** Audit3 final review, “Retained limitations”: “**No estimated tax display before portfolio withdrawals begin; pre-withdrawal MAGI is retained for two-year IRMAA history.**” See Java, lines **4180–4185**, brokerage assumption §7c.
- **Audit rule:** Do not classify blank early tax cells as missing calculation when the model intentionally leaves them blank; independently check MAGI history.
- **Provenance/test cross-reference (v0.2):** Conversations [C3](#conversation-provenance-catalog), [C4](#conversation-provenance-catalog); tests [E3A](#regression-evidence-catalog), [E3B](#regression-evidence-catalog), [E3D](#regression-evidence-catalog). **Coverage:** D: prewithdraw MAGI/IRMAA behavior despite hidden tax display.

<a id="ddr-029"></a>
### DDR-029 — Actual prior-year MAGI takes precedence; missing prior MAGI uses an estimate
- **Status:** ACCEPTED FALLBACK SIMPLIFICATION. **Evidence:** B/A.
- **Decision/rationale:** IRMAA follows a two-year lookback; if a user leaves past MAGI unknown, the engine uses a stand-in, which must not treat this year's one-off brokerage sale as if it occurred two years before.
- **Source quote:** Audit3 final review, changed-source coverage #2: “**The estimate is not a substitute for real historical MAGI if available.**” Java, lines **1907–1920**, prior-MAGI tooltip.
- **Audit rule:** Test explicit values, zero/estimate behavior, sale-year stand-in, and future actual MAGI lookback separately.

## E. Brokerage, stochastic assumptions, and other accepted scope boundaries
- **Provenance/test cross-reference (v0.2):** Conversations [C4](#conversation-provenance-catalog); tests [E3B](#regression-evidence-catalog). **Coverage:** D: explicit MAGI, missing MAGI fallback and sale-free lookback.

<a id="ddr-030"></a>
### DDR-030 — Brokerage modeling is a one-year sale and cash transfer, not a full taxable-account ledger
- **Status:** ACCEPTED SCOPE LIMIT. **Evidence:** A/B.
- **Decision/rationale:** Only sale proceeds and cost basis are entered for the current plan's first year; all gains are assumed long-term, net losses have the limited modeled deduction, and proceeds are moved into the separate holding pool. The model does not simulate holdings, dividends, short-term gains or loss carryforwards.
- **Source quote:** Audit3 final review, “Retained limitations”: “**Brokerage integration represents a one-year sale assumed long-term. The code explicitly excludes short-term gains, dividends, loss carryforwards, and broader brokerage-account modeling.**” Also Java, lines **1252–1272**, taxable brokerage field tooltips.
- **Audit rule:** Verify sale-year capital gains, NIIT, MAGI and cash accounting; do not require a full brokerage tax-lot engine unless scope is changed.
- **Provenance/test cross-reference (v0.2):** Conversations [C4](#conversation-provenance-catalog), [C5](#conversation-provenance-catalog); tests [E3A](#regression-evidence-catalog), [E3B](#regression-evidence-catalog), [E4A](#regression-evidence-catalog). **Coverage:** D: one-year gain/loss cash transfer; NOT full taxable-account coverage.

<a id="ddr-031"></a>
### DDR-031 — A sale's full incremental tax survives partial first-year spending proration
- **Status:** ACCEPTED CORRECTION, CLOSED IN AUDIT3. **Evidence:** B.
- **Decision/rationale:** Partial-year withdrawals may prorate spending but a real sale in that year can incur a full-year incremental capital-gains tax. Preserve the independent sale tax increment in both median table and fan paths.
- **Source quote:** Audit3 final review, changed-source coverage #1: “**The model keeps `tax = (tax - saleTaxRow) * startPror + saleTaxRow`.**”
- **Audit rule:** Re-run January/July/December sale/no-sale, gain/loss, NIIT and fan-path accounting after sale or partial-year tax changes.
- **Provenance/test cross-reference (v0.2):** Conversations [C4](#conversation-provenance-catalog); tests [E3A](#regression-evidence-catalog), [E3B](#regression-evidence-catalog). **Coverage:** D: January/July/December sale tax, gain/loss and NIIT.

<a id="ddr-032"></a>
### DDR-032 — Inflation has a 0% floor with mean compensation, and return losses are capped at -100%
- **Status:** SOURCE-DEFINED STOCHASTIC CHOICES. **Evidence:** A.
- **Decision/rationale:** No negative inflation draws (consistent with SS COLA modeling); shift the underlying normal mean so truncation does not unintentionally raise the entered long-run inflation mean. Portfolio annual returns cannot be below -100%.
- **Source quote:** “**after the 0% floor, the draws average exactly the entered mean**” — Java, lines **15564–15580**; “**a portfolio cannot lose more than everything**” — lines **15557–15564**.
- **Audit rule:** Test statistical mean, nonnegative inflation and return lower bound. Do not replace with untruncated normal without user approval.
- **Provenance/test cross-reference (v0.2):** Conversations [C2](#conversation-provenance-catalog), [C3](#conversation-provenance-catalog); tests [E3A](#regression-evidence-catalog), [E4B](#regression-evidence-catalog). **Coverage:** I: stochastic runs exercised; distributional mean/floor tests cited in earlier audit context, original files not available.

<a id="ddr-033"></a>
### DDR-033 — Market forecast is a user-selected constant return and deviation, not a glide path
- **Status:** ACCEPTED SCOPE / INPUT CHOICE. **Evidence:** A/C.
- **Decision/rationale:** Use one net nominal return and volatility for the entire horizon; no asset-allocation glide path. Historical deviation is a reference point for a selected mix, not a guarantee of future results.
- **Source quote:** “**No asset allocation glide path (single return/stdDev for full horizon)**” — Java, lines **96–100**; handoff §3 November 14 Q28 describes the reference 60/40 historical deviation and user-selected net return.
- **Audit rule:** Ask if portfolio allocation actually changes before treating this as a material missing feature; do not insert automatic market forecasts not present in the scenario.
- **Provenance/test cross-reference (v0.2):** Conversations [C5](#conversation-provenance-catalog), [C8](#conversation-provenance-catalog); tests [E4B](#regression-evidence-catalog), [E5A](#regression-evidence-catalog). **Coverage:** I: input consistency/unchanged model; market forecast accuracy cannot be validated by regression.

<a id="ddr-034"></a>
### DDR-034 — Guyton–Klinger is illustrative; Pro PoS is authoritative
- **Status:** ACCEPTED SEPARATE-ENGINE LIMITATION. **Evidence:** B/A.
- **Decision/rationale:** The GK tab is a comparative illustration with intentionally different cash and conversion treatment, not the authoritative optimizer/Pro result.
- **Source quote:** Audit3 final review, “Retained limitations”: “**Guyton–Klinger is illustrative; Pro PoS remains authoritative.**” Java, lines **96–100**: “**Guyton-Klinger tab makes no Roth conversions and keeps no Money Mkt reserve**.”
- **Audit rule:** Do not demand exact Pro/GK parity or change GK's scope without a new requirement. Test GK regressions when its source actually changes.
- **Provenance/test cross-reference (v0.2):** Conversations [C2](#conversation-provenance-catalog), [C3](#conversation-provenance-catalog), [C4](#conversation-provenance-catalog); tests No dedicated matched log identified. **Coverage:** N: no directly matched executable GK regression in currently available ZIP packages; prior Stage 8 discussion is historical only.

<a id="ddr-035"></a>
### DDR-035 — SS Optimizer comparisons use a fixed seed and invalidate relevant saved results
- **Status:** ACCEPTED DETERMINISM / RESUME POLICY. **Evidence:** A/C.
- **Decision/rationale:** Claim-date candidates must face common market draws for a fair ranking. Inputs and scorer version determine whether saved results can be reused.
- **Source quote:** “**The batch is a COMPARISON, not a forecast. Its job is to judge thousands of claim dates against one another, which requires a future they all share**” — Java, lines **7622–7629**; handoff §3 November 14 Q30 records scorer version 25 after reserve interest correction.
- **Audit rule:** Compare optimizer and direct Pro with identical inputs/seeds; check that financially meaningful changes invalidate the cache while display-only changes don't cause financial differences.

## F. OPEN issues — **not** accepted limitations
- **Provenance/test cross-reference (v0.2):** Conversations [C4](#conversation-provenance-catalog), [C5](#conversation-provenance-catalog), [C6](#conversation-provenance-catalog); tests [E3F](#regression-evidence-catalog), [E4F](#regression-evidence-catalog), [E5E](#regression-evidence-catalog). **Coverage:** D: fixed-seed optimizer, fingerprint invalidation and resume.

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

## Outstanding evidence collection after v0.2

- Attach/export original complete conversations with “Audit” in their titles. The conversation context available to this initial pass did not supply all original transcripts; therefore some decisions are sourced to the program's embedded documentation or the handoff rather than an exact original user statement.
- This version maps available archive-member test logs to all 35 decision IDs. Keep the ZIPs and their baselines under version control; add **test-method or assertion names** within a log where feasible.
- Mark records **User confirmed (verbatim)** when the original message is recovered; until then, **A/B/C evidence establishes the documented behavior, not necessarily every nuance of the user's words**.
- Update `OPEN-001` and `OPEN-002` after the next developer makes and tests the tax-year changes.

---

## G. Record-by-record provenance and regression links

The **35 DDR records above** now carry direct links to this appendix. Each maps to a conversation reference and exact archive/member test evidence where available. **D** = direct exercised behavior; **I** = relevant but indirect coverage; **N** = no matching dedicated executable evidence identified in the available bundles. A passing suite is not proof of all of its associated design claims. The original source quotations in each DDR remain the authoritative design rationale.

### Conversation provenance catalog

**Chat-link limitation:** I can identify three historical chat titles from the project conversation record and date the later audit phases from their reviews, but **I cannot retrieve verified original ChatGPT chat URLs or IDs**. The table gives the exact known title or a clearly labeled descriptive phase. If share links are supplied, replace this catalog entry with the actual URL; do not infer `chatgpt.com/c/...` IDs.

| Ref | Date | Original chat title or audit phase | Verification limit |
|---|---|---|---|
| C1 | April 20, 2026 | Audit of methodology and code, Kitces Go-Go Multiplier | Title/date from project conversation history; exact permalink and full transcript not available here |
| C2 | October 12, 2026 | Audit Retirement Planning Code | Title/date from project conversation history; exact permalink and full transcript not available here |
| C3 | October 18, 2026 | Audit2 Retirement Planning Code | Title/date from project conversation history; exact permalink and full transcript not available here |
| C4 | November 2, 2026 | Audit3 acceptance discussion | Audit phase independently documented by Audit3 review; exact chat title/permalink unverified |
| C5 | November 14, 2026 | Audit4 acceptance and subsequent design discussions | Audit phase documented by Audit4 final review and current conversation; exact permalink unavailable |
| C6 | November 18, 2026 | Audit5 November 18 change-set review | Audit phase documented by Audit5 review and current conversation; exact permalink unavailable |
| C7 | November 14–19, 2026 | Current planning-clarifications discussion | Current conversation; exact permalink not available through tools |
| C8 | November 14–18, 2026 | Developer change rationale: Q26–Q43 | Not a confirmed conversation title; the dated handoff is the inspectable source |

**Finding the older chats:** Search ChatGPT history for the literal titles *Audit of methodology and code, Kitces Go-Go Multiplier*, *Audit Retirement Planning Code*, and *Audit2 Retirement Planning Code*. The later phase names Audit3, Audit4, and Audit5 are indexed by their saved audit-review reports; their original chat titles are not verified in the provided evidence. Source `riskbased-probofsuccess-handoff-2026-10-30.md` separately mentions a development conversation titled *Java class RiskBased_OptSocSec_v2*; that is a handoff assertion, not a verified audit-chat permalink.

### Regression evidence catalog

All entries below were validated on **November 19, 2026** by opening the exact named ZIP member and checking the quoted line. Archive links are relative to this register's directory, so they work when ZIP files retain the same folder structure. If viewing separately, use the exact archive filenames and internal paths.

| Ref | What was executed | Exact package → internal log | Verbatim outcome from log |
|---|---|---|---|
| E3A | Revised sale/partial-year and baseline full-engine | [audit3/Audit3_v2_2048_Acceptance_Evidence.zip](audit3/Audit3_v2_2048_Acceptance_Evidence.zip) → `package/logs/acceptance_115_checks.log` | `SUMMARY: 115 checks; 0 failures` |
| E3B | Prior MAGI / IRMAA / conversion boundary | [audit3/Audit3_v2_2048_Acceptance_Evidence.zip](audit3/Audit3_v2_2048_Acceptance_Evidence.zip) → `package/logs/irmaa_fallback_acceptance.log` | `SUMMARY 33 checks 0 failed` |
| E3C | Top-up, cash reserve, RMD and survivor | [audit3/Audit3_v2_2048_Acceptance_Evidence.zip](audit3/Audit3_v2_2048_Acceptance_Evidence.zip) → `package/logs/topup_rmd_regression.log` | `FINAL 87 passed 0 failed` |
| E3D | Pre-withdrawal RMD and reserve | [audit3/Audit3_v2_2048_Acceptance_Evidence.zip](audit3/Audit3_v2_2048_Acceptance_Evidence.zip) → `package/logs/predraw_rmd_regression.log` | `PRE-DRAW RMD FINAL 20 passed 0 failed` |
| E3E | Operational UI & scenario persistence | [audit3/Audit3_v2_2048_Acceptance_Evidence.zip](audit3/Audit3_v2_2048_Acceptance_Evidence.zip) → `package/logs/gui_op.log` | `ALL 10 GUI OPERATIONAL CHECKS PASSED` |
| E3F | Optimizer versus direct Pro | [audit3/Audit3_Optimizer_Integration_Final_Evidence.zip](audit3/Audit3_Optimizer_Integration_Final_Evidence.zip) → `optimizer_integration_package/evidence/Audit3_Optimizer_RevisedIntegration.log` | `AUDIT3 OPTIMIZER REVISED INTEGRATION: 51 passed, 0 failed` |
| E4A | New-reserve and cash-flow full-engine boundaries | [Audit4_Interim_Evidence.zip](Audit4_Interim_Evidence.zip) → `logs/new_extra.log` | `AUDIT4_TOTAL ↹ PASSED=72 ↹ FAILED=0` |
| E4B | Submitted scenario full fidelity | [Audit4_Interim_Evidence.zip](Audit4_Interim_Evidence.zip) → `logs/new_scenario_full.log` | `AUDIT4_TOTAL ↹ PASSED=12 ↹ FAILED=0` |
| E4C | Independent Roth conversion tax-effect total | [Audit4_Interim_Evidence.zip](Audit4_Interim_Evidence.zip) → `logs/conversion_effect_direct.log` | `AUDIT4_CONV_COMPARE ↹ PASS=28 ↹ FAIL=0` |
| E4D | Swing GUI conversion comparison | [Audit4_Interim_Evidence.zip](Audit4_Interim_Evidence.zip) → `logs/gui_smoke.log` | `AUDIT4_UI ↹ PASS=9 ↹ FAIL=0` |
| E4E | Optimizer/direct Pro integration | [Audit4_Optimizer_Integration_Evidence.zip](Audit4_Optimizer_Integration_Evidence.zip) → `package/evidence/optimizer_scoring.log` | `AUDIT4_OPTIMIZER_TOTAL ↹ PASSED=121 ↹ FAILED=0` |
| E4F | Claimed SS, optimizer cache and resume | [Audit4_Optimizer_Integration_Evidence.zip](Audit4_Optimizer_Integration_Evidence.zip) → `package/evidence/optimizer_operational.log` | `AUDIT4_OPT_OPS_TOTAL ↹ PASSED=27 ↹ FAILED=0` |
| E4G | Two reserve ledger, RMD/survivor/top-up boundaries | [Audit4_Reserve_Persistence_Evidence.zip](Audit4_Reserve_Persistence_Evidence.zip) → `evidence/reserve_boundary.log` | `AUDIT4_BOUNDARY_TOTAL ↹ PASSED=113 ↹ FAILED=0` |
| E4H | Scenario save/reload with split reserves | [Audit4_Reserve_Persistence_Evidence.zip](Audit4_Reserve_Persistence_Evidence.zip) → `evidence/persistence.log` | `AUDIT4_PERSISTENCE_TOTAL ↹ PASSED=29 ↹ FAILED=0` |
| E4I | Full-fidelity active reserve & thread parity | [Audit4_Reserve_Persistence_Evidence.zip](Audit4_Reserve_Persistence_Evidence.zip) → `evidence/scenario_active_full.log` | `AUDIT4_SCENARIO_LIVE_TOTAL ↹ PASSED=23 ↹ FAILED=0` |
| E5A | Baseline zero-reserve parity scenario | [Audit5_Nov18_ChangeSet_Evidence.zip](Audit5_Nov18_ChangeSet_Evidence.zip) → `package/logs/new_nomoney.log` | `AUDIT5_CORE_TOTAL PASSED=11 FAILED=0` |
| E5B | Updated Swing UI and Roth/RMD comparison | [Audit5_Nov18_ChangeSet_Evidence.zip](Audit5_Nov18_ChangeSet_Evidence.zip) → `package/logs/gui_test.log` | `AUDIT5_GUI_TOTAL PASSED=47 FAILED=0` |
| E5C | 2027 tax and RMD/conversion boundary | [Audit5_Nov18_ChangeSet_Evidence.zip](Audit5_Nov18_ChangeSet_Evidence.zip) → `package/logs/taxrmd.log` | `AUDIT5_TAX_RMD_TOTAL PASSED=28 FAILED=0` |
| E5D | 2027 tax reset, GUI dialog and persistence | [Audit5_Nov18_ChangeSet_Evidence.zip](Audit5_Nov18_ChangeSet_Evidence.zip) → `package/logs/dialog.log` | `AUDIT5_DIALOG_TOTAL PASSED=15 FAILED=0` |
| E5E | Real optimizer batch, early reserve ignored, fingerprints | [Audit5_Nov18_ChangeSet_Evidence.zip](Audit5_Nov18_ChangeSet_Evidence.zip) → `package/logs/optimizer.log` | `AUDIT5_OPTIMIZER_TOTAL PASSED=12 FAILED=0` |
| E5F | Full-fidelity one/four-thread parity | [Audit5_Nov18_ChangeSet_Evidence.zip](Audit5_Nov18_ChangeSet_Evidence.zip) → `package/logs/parity_full_same_process.log` | `AUDIT5_PARITY_TOTAL PASSED=3 FAILED=0` |
| E5G | Diagnostic: capital-gain band tax-year defect | [Audit5_Nov18_ChangeSet_Evidence.zip](Audit5_Nov18_ChangeSet_Evidence.zip) → `package/logs/ltcg_boundary.log` | `LTCG_2027 ↹ 0%=8900.00 ↹ 15%=1100.00` |
| E5H | 2027 active early reserve, survival rate and short years | [Audit5_Nov18_ChangeSet_Evidence.zip](Audit5_Nov18_ChangeSet_Evidence.zip) → `package/logs/new2027_withSplits_topoff_4_full.log` | `AUDIT5_CORE_TOTAL PASSED=14 FAILED=0` |

**Older Audit/Audit2 historical tests, not reverified:** The available prior-chat context names Stage 5's `stage5_v8_full_annual_tax/execution.log` (48/48 annual federal/Arizona tax reconciliations), Stage 8 `test_report.md` / `execution.log` (2,152/2,152), Stage 9 `stage9_final_acceptance/final_review.md` (103 scenarios; 15,501 checks), and version (9) SD1/BR1/SS Optimizer tests. Those original logs were **not** among the locally verifiable packages in this session. Therefore they are provenance leads, **not** silently promoted to verified regression evidence for any DDR.

### Record-by-record cross-reference matrix

| Decision | Conversation refs | Executed test refs | Scope/verification qualification |
|---|---|---|---|
| [DDR-001](#ddr-001) | C7 | E4B,E4H | I: annual scenario/persistence exercised; annual user updates remain a documented policy |
| [DDR-002](#ddr-002) | C1,C7 | E4A,E4B | I: cashflow/surplus reconciled; no dedicated assertion that every surplus is never reinvested |
| [DDR-003](#ddr-003) | C7 | — | N: external medical bills are outside the model; source documents user responsibility |
| [DDR-004](#ddr-004) | C2,C3,C4 | E3F,E4E,E5A | D: optimizer/Pro and annual solve results; historical inner-solver Stage 8/9 details not in packaged archives |
| [DDR-005](#ddr-005) | C3,C4,C7 | E3F,E4E,E5H | D: portfolio-only Actual PoS compared and independently recounted |
| [DDR-006](#ddr-006) | C4,C7 | E3C,E4G,E5H | D: cash-rescued paths and terminal shortfall counts |
| [DDR-007](#ddr-007) | C4,C7 | E3C,E5H | D: shortfalls and survival are separate; GUI count verification |
| [DDR-008](#ddr-008) | C1,C4 | E3C,E4G | D: terminal five-year top-up, reserve ordering and exhaustion |
| [DDR-009](#ddr-009) | C1,C4 | E3C,E4G | I: late top-up and cap interactions; dedicated 22% exact-boundary log not separately identified |
| [DDR-010](#ddr-010) | C1,C7 | E4B,E5A | I: saved multiplier inputs and expected draws verified; no stand-alone test of household-spending interpretation |
| [DDR-011](#ddr-011) | C7 | E4B,E5B | D: base vs actual first-year withdrawal shown and checked |
| [DDR-012](#ddr-012) | C4,C5,C7 | E3C,E4G | D: protected regular account, funding restrictions and top-up |
| [DDR-013](#ddr-013) | C4,C5 | E3C,E4G,E5H | D: early coverage on/off and last-five reserve behavior |
| [DDR-014](#ddr-014) | C6,C8 | E5E | D: real optimizer batch forces early coverage off; source/handoff Q39 |
| [DDR-015](#ddr-015) | C5 | E4A,E4G,E4H | D: dual-pool ledger, exhaustion and persistence |
| [DDR-016](#ddr-016) | C3,C4 | E3D,E3C | D: prewithdraw RMD overage and savings recurrence |
| [DDR-017](#ddr-017) | C5,C8 | E5A,E5H | I: new full-engine reserve interest in 2027; handoff Q30 describes the direct implementation check |
| [DDR-018](#ddr-018) | C2,C3,C7 | E3A,E4B | D: first-year bridge and annual withdrawals (accepted tax gross-up is a separate limitation) |
| [DDR-019](#ddr-019) | C3,C4 | E3A,E4B | I: engine and tax paths exercised; no comparison to an exact second-pass bridge calculation required |
| [DDR-020](#ddr-020) | C2,C3,C4 | E3C,E3D,E4G | D: RMD prior-year base, pre-draw order and December conversion coexistence |
| [DDR-021](#ddr-021) | C5 | E4F,E5E | D: held date, button behavior and candidate grid |
| [DDR-022](#ddr-022) | C2,C3,C4 | E3C,E3F,E4E | D: survivor year, tax filing, RMD, SS and optimizer interactions |
| [DDR-023](#ddr-023) | C3,C7,C8 | E4C,E5B | I: lifetime, survivor, ending-Traditional comparisons; user's objective is not a solved NPV optimization |
| [DDR-024](#ddr-024) | C3,C8 | E5C,E5D | D: fill ceiling, IRMAA/bracket boundaries, no-limit, cap and tax-year controls |
| [DDR-025](#ddr-025) | C3,C4 | E3B,E4C | I: conversion tax path tested under accepted assumption; no independent exact low-income deduction reoptimization |
| [DDR-026](#ddr-026) | C4,C5,C7 | E4A,E4G,E4H | D: source/financial ledger proves funding order and external tax payment |
| [DDR-027](#ddr-027) | C5,C7 | E4C,E5B | D: same-seed lifetime tax and displayed comparisons; not a present-value test |
| [DDR-028](#ddr-028) | C3,C4 | E3A,E3B,E3D | D: prewithdraw MAGI/IRMAA behavior despite hidden tax display |
| [DDR-029](#ddr-029) | C4 | E3B | D: explicit MAGI, missing MAGI fallback and sale-free lookback |
| [DDR-030](#ddr-030) | C4,C5 | E3A,E3B,E4A | D: one-year gain/loss cash transfer; NOT full taxable-account coverage |
| [DDR-031](#ddr-031) | C4 | E3A,E3B | D: January/July/December sale tax, gain/loss and NIIT |
| [DDR-032](#ddr-032) | C2,C3 | E3A,E4B | I: stochastic runs exercised; distributional mean/floor tests cited in earlier audit context, original files not available |
| [DDR-033](#ddr-033) | C5,C8 | E4B,E5A | I: input consistency/unchanged model; market forecast accuracy cannot be validated by regression |
| [DDR-034](#ddr-034) | C2,C3,C4 | — | N: no directly matched executable GK regression in currently available ZIP packages; prior Stage 8 discussion is historical only |
| [DDR-035](#ddr-035) | C4,C5,C6 | E3F,E4F,E5E | D: fixed-seed optimizer, fingerprint invalidation and resume |

### Open findings cross-reference (not design acceptances)

- **OPEN-001:** [C6](#conversation-provenance-catalog), [E5G](#regression-evidence-catalog). The 2027 capital-gain diagnostic reproduces a tax-year band-selection defect; it is **not a passing feature test**, and Audit5 remains open for this pathway. Exact finding: `Audit5_Nov18_ChangeSet_Evidence.zip → package/logs/ltcg_boundary.log`.
- **OPEN-002:** [C6](#conversation-provenance-catalog), [E5D](#regression-evidence-catalog) and [E5B](#regression-evidence-catalog). Old scenarios retaining 2026 tax values is intentional compatibility; visible active-tax-year disclosure is an outstanding user-experience concern.
- **INVESTIGATE-003:** [C6](#conversation-provenance-catalog), [E5F](#regression-evidence-catalog). Controlled 1-/4-thread parity passed; earlier outlier is not explained. Preserve `Audit5_Nov18_ChangeSet_Evidence.zip → package/logs/new2027_withSplits_topoff_1_full.log` as negative/unexplained evidence.
- **REVIEW-004:** Developer handoff §5 lists ideas and stale notes. No blanket acceptance or blanket defect classification follows from their presence; verify against the latest build.

### Reproducibility notes

1. An **archive-level result** is not necessarily a decision-specific assertion. The direct/indirect notation above is intentional and should be tightened to *method + scenario + assertion* as tests evolve.
2. Audit3's 316, Audit4's 434, and Audit5's 255 check counts are **separate versions/stages**, not a sum of wholly independent claims. This document does not multiply them by attaching one log to several DDRs.
3. To make the original chats genuinely clickable, copy the verified **Share** URL from each existing conversation into the corresponding `C#` catalog row. The references from 35 records will then resolve through one maintained location.
4. If a future audit finds a new defect in an accepted pathway, reopen the implementation **without retroactively rewriting the user's accepted objective or approximation**.

---

### Source index

- **[S1]** `RiskBased_OptSocSec_v2(1).java` — current uploaded source (19,284 raw lines; SHA-256 `136e864b7d0925023567dc94c3453e5340892e1015c937a0919b6bc35507626f`); cited by line number and/or embedded Assumptions & Methods section.
- **[S2]** `riskbased-probofsuccess-handoff-2026-10-30.md` — uploaded current handoff, as of November 18, 2026; dated implementation decisions and open findings (§3, §5).
- **[S3]** `audit3/Audit3_Final_Coverage_Acceptance_Review.md` — Audit3 decision, tested scope and retained limitations (November 2).
- **[S4]** `Audit4_Final_Coverage_Acceptance_Review.md` — Audit4 decision, 434 tests and retained limitations (November 14).
- **[S5]** `audit5/Audit5_Nov18_Review.md` — Audit5 conditional acceptance and open findings (November 18).

**Maintenance instruction:** Keep this file under version control beside the Java source. The status and the rationale are part of the specification; edits to the implementation do not automatically supersede them.
