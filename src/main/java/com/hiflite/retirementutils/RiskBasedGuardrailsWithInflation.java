package com.hiflite.retirementutils;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.random.RandomGenerator;

public class RiskBasedGuardrailsWithInflation {

    // Simulation parameters
    static final int NUM_SIMULATIONS = 100000;
    static final int RETIREMENT_YEARS = 30;
    static final double REAL_MEAN_RETURN = 0.067;      // real expected annual return
    static final double REAL_VOLATILITY = 0.15;       // real volatility
    static final double INFLATION_MEAN = 0.035;       // expected annual inflation
    static final double INFLATION_VOL = 0.015;        // inflation volatility (typical ~1-2%)
    static final double TARGET_POS = 0.90;
    static final double UPPER_POS = 0.99;
    static final double LOWER_POS = 0.82;

    static final NumberFormat numFmt = NumberFormat.getCurrencyInstance(Locale.US);

    public static void main(String[] args) {
        double initialPortfolio = 1_500_000;
        // Find initial real spending (in today's dollars) that hits target PoS
        double initialRealSpending = findRealSpendingForPoS(initialPortfolio, TARGET_POS);
        System.out.printf("Initial sustainable spending: $%.0f/year ; $%.0f/month ; pct of portfolio %.2f%%  (%.0f%% PoS)\n",
                initialRealSpending, initialRealSpending/12.0, initialRealSpending/initialPortfolio*100.0, TARGET_POS * 100);

        // Upper guardrail example
        double upperPortfolio = findPortfolioForPoS(initialRealSpending, UPPER_POS);
        double upperNewRealSpending = findRealSpendingForPoS(upperPortfolio, TARGET_POS);
        System.out.printf("Upper guardrail: If portfolio ≥ $%.0f → increase real spending to $%.0f/year ; $%.0f/month\n",
                upperPortfolio, upperNewRealSpending, upperNewRealSpending/12.0);

        // Lower guardrail example
        double lowerPortfolio = findPortfolioForPoS(initialRealSpending, LOWER_POS);
        double lowerNewRealSpending = findRealSpendingForPoS(lowerPortfolio, TARGET_POS);
        System.out.printf("Lower guardrail: If portfolio ≤ $%.0f → decrease real spending to $%.0f/year ; $%.0f/month\n",
                lowerPortfolio, lowerNewRealSpending, lowerNewRealSpending/12.0);

    }

    // Monte Carlo: Probability portfolio lasts RETIREMENT_YEARS years with inflation-adjusted withdrawals
    static double calculatePoS(double startPortfolio, double initialRealSpending) {
        int success = 0;
        RandomGenerator rand = RandomGenerator.getDefault();
        for (int sim = 0; sim < NUM_SIMULATIONS; sim++) {
            double portfolio = startPortfolio;                // nominal starting value
            double currentSpending = initialRealSpending;     // starts as real; we'll inflate it nominally
            boolean survived = true;

            for (int year = 0; year < RETIREMENT_YEARS; year++) {
                // Simulate real return
                double realReturn = Math.exp((REAL_MEAN_RETURN - REAL_VOLATILITY * REAL_VOLATILITY / 2)
                        + REAL_VOLATILITY * rand.nextGaussian()) - 1;

                // Simulate inflation this year
                double inflation = INFLATION_MEAN + INFLATION_VOL * rand.nextGaussian();  // normal dist (or use lognormal if preferred)

                // Nominal return ≈ real return + inflation (approx; exact: (1+real)*(1+inf)-1)
                double nominalReturn = (1 + realReturn) * (1 + inflation) - 1;

                // Grow portfolio nominally
                portfolio = portfolio * (1 + nominalReturn);

                // Inflate the spending amount for this year's withdrawal (to maintain purchasing power)
                if (year > 0) {
                    currentSpending *= (1 + inflation);
                }

                // Withdraw the inflation-adjusted (nominal) amount
                portfolio -= currentSpending;

                if (portfolio <= 0) {
                    survived = false;
                    break;
                }
            }
            if (survived) success++;
        }
        return (double) success / NUM_SIMULATIONS;
    }

    // Binary search: Find constant real initial spending that gives target PoS
    static double findRealSpendingForPoS(double portfolio, double targetPoS) {
        double low = 0;
        double high = portfolio * 0.10;
        for (int i = 0; i < 50; i++) {
            double mid = (low + high) / 2;
            if (calculatePoS(portfolio, mid) > targetPoS) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2;
    }

    // Binary search: Find portfolio value that gives target PoS at fixed initial real spending
    static double findPortfolioForPoS(double initialRealSpending, double targetPoS) {
        double low = 0;
        double high = initialRealSpending * 50;
        for (int i = 0; i < 50; i++) {
            double mid = (low + high) / 2;
            if (calculatePoS(mid, initialRealSpending) < targetPoS) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2;
    }

    // currently unused - should be called by another main driver
    //public static void ongoingAdjustments(String[] args)
    public static void ongoingAdjustments(double currentPortfolio, double currentRealSpending)
    {
        // Example ongoing check/adjust logic (run this each period with current data)
//        double currentPortfolio;       // update this every time
//        double currentRealSpending;    // your spending right now (today's $)

        // Re-compute guardrails for current spending
        double upperGuardrailPortfolio = findPortfolioForPoS(currentRealSpending, UPPER_POS);
        double lowerGuardrailPortfolio = findPortfolioForPoS(currentRealSpending, LOWER_POS);

        double newTargetSpending = findRealSpendingForPoS(currentPortfolio, TARGET_POS);
        System.out.printf("currentPortfolio: %s ; currentRealSpending: %s\n", numFmt.format(currentPortfolio), numFmt.format(currentRealSpending));

        if (currentPortfolio >= upperGuardrailPortfolio) {
            // Hit upper — increase partially
            double adjustmentFactor = 0.4;  // tune this (0.25–0.5 common)
            currentRealSpending += adjustmentFactor * (newTargetSpending - currentRealSpending);
            System.out.println("Upper guardrail hit → new spending: " + currentRealSpending);

        } else if (currentPortfolio <= lowerGuardrailPortfolio) {
            // Hit lower — decrease partially
            double adjustmentFactor = 0.4;
            currentRealSpending += adjustmentFactor * (newTargetSpending - currentRealSpending);
            System.out.println("Lower guardrail hit → new spending: " + currentRealSpending);

        } else {
            System.out.println("No adjustment — keep spending at: " + currentRealSpending);
        }
        System.out.printf("newTargetSpending: %s ; upperGuardrailPortfolio: %s ; lowerGuardrailPortfolio: %s\n"
                , numFmt.format(newTargetSpending), numFmt.format(upperGuardrailPortfolio), numFmt.format(lowerGuardrailPortfolio));

        // After any change, you would re-compute guardrails again for the NEXT period
        return;
    }
}