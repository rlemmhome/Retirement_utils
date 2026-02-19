package com.hiflite.modernguardrailsmodel_gemini;

import java.util.Random;

public class ModernGuardrailsModel {

    // --- Configurable Constants ---
    private static final int NUM_SIMULATIONS = 100000;
    private static final double TARGET_RISK = 0.15;       // 20% Target
    private static final double LOWER_GUARDRAIL = 0.20;   // 40% Trigger Cut
    private static final double UPPER_GUARDRAIL = 0.05;   // 10% Trigger Raise

    private static final double MEAN_RETURN = 0.039;       // 7% Nominal is Avg (what JPM gives is Nominal return,; we must adjust for inflation)
    private static final double STD_DEV = 0.1089;           // 12% Volatility
    private static final double AVG_INFLATION = 0.025;     // 3% Inflation

    private static final double INITIAL_PORTFOLIO = 1_500_000.0;
    private static final int RETIREMENT_LENGTH = 30;

    // --- Go-Go Years & SORR Config ---
    private static final int GO_GO_END_YEAR = 10;         // Extra spending ends year 10
    private static final double GO_GO_MULTIPLIER = 1.25;  // 25% extra for travel/health
    private static final int SORR_YEARS = 2;              // Market crash duration
    private static final double SORR_RETURN = -0.15;      // -15% return during crash

    private static final Random RANDOM = new Random(System.currentTimeMillis());

    public static void main(String[] args) {
        double portfolio = INITIAL_PORTFOLIO;

        // We start with the user's requested base income of $60,000.
        // The "Go-Go" multiplier will be applied on top of this.
        double baseAnnualIncome = 60000.0;
        double cumulativeInflation = 1.0;

        System.out.println("Scenario: $60k Base + Go-Go Multiplier + Early Market Crash");
        System.out.println("Year | Portfolio   | Real Spend  | Risk % | Action");
        System.out.println("-------------------------------------------------------");

        for (int year = 0; year < RETIREMENT_LENGTH; year++) {
            cumulativeInflation *= (1 + AVG_INFLATION);

            // Calculate current spending goal including Go-Go logic
            double currentMultiplier = (year < GO_GO_END_YEAR) ? GO_GO_MULTIPLIER : 1.0;
            double currentRequiredSpending = baseAnnualIncome * cumulativeInflation * currentMultiplier;

            // 1. Calculate Risk (Monte Carlo includes Go-Go logic in its projection)
            double currentRisk = estimateRisk(portfolio, baseAnnualIncome, year, cumulativeInflation);

            String action = "Steady";

            // 2. Guardrail Logic
            if (currentRisk >= LOWER_GUARDRAIL) {
                // If risk is too high, recalculate the BASE income to hit target risk
                baseAnnualIncome = solveForBaseIncome(portfolio, TARGET_RISK, year, cumulativeInflation);
                action = "CUT";
            } else if (currentRisk <= UPPER_GUARDRAIL) {
                baseAnnualIncome = solveForBaseIncome(portfolio, TARGET_RISK, year, cumulativeInflation);
                action = "RAISE";
            }

            // Recalculate spending after potential guardrail adjustment
            double finalSpend = baseAnnualIncome * cumulativeInflation * ((year < GO_GO_END_YEAR) ? GO_GO_MULTIPLIER : 1.0);

            System.out.printf("%4d | $%10.2f | $%10.2f | %4.1f%% | %s%n",
                    year, portfolio, finalSpend, currentRisk * 100, action);

            // 3. Actual Market Realization with SORR (The Crash)
            double actualReturn;
            if (year < SORR_YEARS) {
                actualReturn = SORR_RETURN; // Forced sequence of returns risk
            } else {
                actualReturn = MEAN_RETURN + (RANDOM.nextGaussian() * STD_DEV);
            }

            portfolio = (portfolio - finalSpend) * (1 + actualReturn);

            if (portfolio <= 0) {
                System.out.println("!!! Portfolio Exhausted - Plan Failed !!!");
                break;
            }
        }
    }

    /**
     * Estimates risk while accounting for the Go-Go years logic.
     */
    private static double estimateRisk(double balance, double baseSpending, int currentYear, double currentInflFactor) {
        int failures = 0;

        for (int i = 0; i < NUM_SIMULATIONS; i++) {
            double simBalance = balance;
            double simBaseSpending = baseSpending;
            double simInfl = currentInflFactor;

            for (int t = currentYear; t < RETIREMENT_LENGTH; t++) {
                // Apply Go-Go multiplier in the simulation if still in that window
                double multiplier = (t < GO_GO_END_YEAR) ? GO_GO_MULTIPLIER : 1.0;
                double totalSpend = simBaseSpending * simInfl * multiplier;

                double simReturn = MEAN_RETURN + (RANDOM.nextGaussian() * STD_DEV);
                simBalance = (simBalance - totalSpend) * (1 + simReturn);

                simInfl *= (1 + AVG_INFLATION);

                if (simBalance <= 0) {
                    failures++;
                    break;
                }
            }
        }
        return (double) failures / NUM_SIMULATIONS;
    }

    private static double solveForBaseIncome(double balance, double targetRisk, int currentYear, double infl) {
        double low = 0, high = balance * 0.3;
        double mid = 0;
        for (int i = 0; i < 15; i++) {
            mid = (low + high) / 2;
            if (estimateRisk(balance, mid, currentYear, infl) < targetRisk) low = mid;
            else high = mid;
        }
        return mid;
    }
}