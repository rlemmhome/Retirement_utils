package com.hiflite.riskbasedguardrailsgrok;

/*

Practical Ongoing Workflow (Most Realistic for DIY)

Once a year (or quarterly):
- Input current portfolio value
- Input remaining horizon (or keep fixed at "to age 95")
- Re-run Monte Carlo → get:
  -- Current sustainable real spending at 90% PoS
  -- Upper guardrail portfolio level (for your current spending)
  -- Lower guardrail portfolio level (for your current spending)


Monitor portfolio throughout the year
- If portfolio ever ≤ lower guardrail → trigger downward adjustment (partial reset toward new target)
- If portfolio ever ≥ upper guardrail → trigger upward adjustment (partial reset toward new target)

After any adjustment:
- Set the new current spending
- Re-compute fresh guardrail levels based on the new spending amount
- Continue monitoring

 */

public class RiskBasedGuardrails_OngoingAdjustments {

    public static void main(String[] args) {
        // Example ongoing check/adjust logic (run this each period with current data)
        double currentPortfolio = 1500000;   // update this every time
//        double currentRealSpending = 62000;    // your spending right now (today's $)

        RiskBasedGuardrailsWithInflation theDriver = new RiskBasedGuardrailsWithInflation();

        System.out.println("\ncalculating sustainable spending and guardrails...\n");
        double upperNewRealSpending = theDriver.driver(currentPortfolio);

        System.out.println("\ncalculating ongoing adjustments...\n");

        // use the inactive line below to calc using real current spending.
        // RiskBasedGuardrailsWithInflation.ongoingAdjustments(currentPortfolio, currentRealSpending);
        RiskBasedGuardrailsWithInflation.ongoingAdjustments(currentPortfolio, upperNewRealSpending);
    }
}
