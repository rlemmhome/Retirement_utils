package com.hiflite.riskbasedguardrails_grok;

import com.hiflite.utils.TimingUtils;

import java.text.DecimalFormat;
import java.util.random.RandomGenerator;

public class RiskBasedGuardrailsWithInflation {

    // Simulation parameters
    static final int NUM_SIMULATIONS = 100_000;
    static final int RETIREMENT_YEARS = 30;

    // see 20260218_GrokInflationDiscussion for means and std devs... my stddevs are calc'ed from 1966 through 2025
    // 1996-2025 mean return is 9.44, mean inflation is 0.0379
    static final double REAL_MEAN_RETURN = 0.039;      // real expected annual return (orig 0.067 ; new 0.064... JPM gives Nominal pct, but we need real (6.4%-inflation)
    static final double REAL_VOLATILITY = 0.1089;      // real volatility - Std Dev (orig 0.15 ; mine 0.1089) ... see
    static final double INFLATION_MEAN = 0.025;       // expected annual inflation (orig 0.025 ; mine 0.0379)
    static final double INFLATION_VOL = 0.015;        // inflation volatility - Std Dev(typical ~1-2%)  (orig 0.015 ; mine 0.0273)

    static final double TARGET_POS = 0.85;
    static final double UPPER_POS = 0.95;
    static final double LOWER_POS = 0.80;

    static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,##0");

    public static void main(String[] args) {
        double initialPortfolio = 1500000.0;

        RiskBasedGuardrailsWithInflation theDriver = new RiskBasedGuardrailsWithInflation();
        theDriver.driver(initialPortfolio);
    }

    public double driver(double initialPortfolio) {
        //initialPortfolio = 1500000;
        // Find initial real spending (in today's dollars) that hits target PoS

        TimingUtils timingUtils = new TimingUtils();
        timingUtils.timerStart();
        double initialRealSpending = findRealSpendingForPoS(initialPortfolio, TARGET_POS);

        System.out.printf("\nInitial sustainable spending: $%.0f/year ; $%.0f/month ; pct of portfolio %.3f%%  (%.0f%% PoS)\n",
                initialRealSpending, initialRealSpending / 12.0, initialRealSpending / initialPortfolio * 100.0, TARGET_POS * 100);

//        timingUtils.reportElapsedTime();
        System.out.println();

        // Upper guardrail example
        double upperPortfolio = findPortfolioForPoS(initialRealSpending, UPPER_POS);
        double upperNewRealSpending = findRealSpendingForPoS(upperPortfolio, TARGET_POS);
        System.out.printf("Upper guardrail: If portfolio ≥ $%.0f → increase real spending to $%.0f/year ; $%.0f/month  (%.0f%% PoS)\n",
                upperPortfolio, upperNewRealSpending, upperNewRealSpending / 12.0, UPPER_POS * 100);

//        timingUtils.reportElapsedTime();
        System.out.println();

        // Lower guardrail example
        double lowerPortfolio = findPortfolioForPoS(initialRealSpending, LOWER_POS);
        double lowerNewRealSpending = findRealSpendingForPoS(lowerPortfolio, TARGET_POS);
        System.out.printf("Lower guardrail: If portfolio ≤ $%.0f → decrease real spending to $%.0f/year ; $%.0f/month  (%.0f%% PoS)\n",
                lowerPortfolio, lowerNewRealSpending, lowerNewRealSpending / 12.0, LOWER_POS * 100);

//        timingUtils.reportElapsedTime();
        System.out.println();

        timingUtils.timerStop();
        timingUtils.reportTotalElapsedTime();

        return initialRealSpending;
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
            double calculatedPoS = calculatePoS(portfolio, mid);
            if (calculatedPoS > targetPoS) {
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
            double calculatedPoS = calculatePoS(mid, initialRealSpending);
            if (calculatedPoS < targetPoS) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2;
    }

    // currently unused - should be called by another main driver
    //public static void ongoingAdjustments(String[] args)
    public static void ongoingAdjustments(double currentPortfolio, double currentRealSpending) {
        // Example ongoing check/adjust logic (run this each period with current data)
//        double currentPortfolio;       // update this every time
//        double currentRealSpending;    // your spending right now (today's $)

        // Re-compute guardrails for current spending
        double upperGuardrailPortfolio = findPortfolioForPoS(currentRealSpending, UPPER_POS);
        double lowerGuardrailPortfolio = findPortfolioForPoS(currentRealSpending, LOWER_POS);

        double newTargetSpending = findRealSpendingForPoS(currentPortfolio, TARGET_POS);
        System.out.printf("currentPortfolio: %s ; currentRealSpending: %s\n", DECIMAL_FORMAT.format(currentPortfolio), DECIMAL_FORMAT.format(currentRealSpending));

        if (currentPortfolio >= upperGuardrailPortfolio) {
            // Hit upper — increase partially
            double adjustmentFactor = 0.4;  // tune this (0.25–0.5 common)
            currentRealSpending += adjustmentFactor * (newTargetSpending - currentRealSpending);
            System.out.println("Upper guardrail hit → new spending: " + DECIMAL_FORMAT.format(currentRealSpending));

        } else if (currentPortfolio <= lowerGuardrailPortfolio) {
            // Hit lower — decrease partially
            double adjustmentFactor = 0.4;
            currentRealSpending += adjustmentFactor * (newTargetSpending - currentRealSpending);
            System.out.println("Lower guardrail hit → new spending: " + DECIMAL_FORMAT.format(currentRealSpending));

        } else {
            System.out.println("No adjustment — keep spending at: " + DECIMAL_FORMAT.format(currentRealSpending));
        }

        System.out.println("\nWe pay attention to the next numbers only when we've had to make an adjustment due to hitting a guardrail.\n");
        System.out.println("At that time, enter in the new current 'initialPortfolio' in the driver(), change the number of years, and re-run.\n");
        System.out.printf("newTargetSpending: %s ; upperGuardrailPortfolio: %s ; lowerGuardrailPortfolio: %s\n"
                , DECIMAL_FORMAT.format(newTargetSpending), DECIMAL_FORMAT.format(upperGuardrailPortfolio)
                , DECIMAL_FORMAT.format(lowerGuardrailPortfolio));

        // After any change, you would re-compute guardrails again for the NEXT period
    }
}