package com.hiflite.modernguardrailsmodel_gemini;

import java.util.Random;

public class ModernGuardrailsWithTweaks {

        // --- Configuration ---
        private static final int NUM_SIMULATIONS = 100000;
        private static final double REAL_MEAN_RETURN = 0.039;   // (JPM gives nominal, we need to subtract inflation)
        private static final double REAL_STD_DEV = 0.1089;        // orig gives 0.12 ; historical since 1955 is 0.1089

        private static final double TARGET_RISK = 0.15;       // Target (Reset point)
        private static final double LOWER_GUARDRAIL_RISK = 0.20; // Preservation Trigger
        private static final double UPPER_GUARDRAIL_RISK = 0.05; // Prosperity Trigger

        private static final double INITIAL_PORTFOLIO = 1_500_000.0;

        private static final int RETIREMENT_LENGTH = 30;       // years of retirement
        private static final double GO_GO_MULTIPLIER = 1.25;   //spend 25% more in the go-go years
        private static final int GO_GO_YEARS = 10;             // 10 years in the gogo period

        private static final Random RANDOM = new Random(System.currentTimeMillis());

        public static void main(String[] args) {
            // 1. Initial Calculation
            double initialBaseIncome = solveForRealIncome(INITIAL_PORTFOLIO, TARGET_RISK, 0);
            double totalInitialSpend = initialBaseIncome * GO_GO_MULTIPLIER;
            double withdrawalRatePct = (totalInitialSpend / INITIAL_PORTFOLIO) * 100;

            // 2. Portfolio Triggers (At what balance does current spend hit the triggers?)
            double lowerPortfolioTrigger = solveForPortfolioAtRisk(initialBaseIncome, LOWER_GUARDRAIL_RISK, 0);
            double upperPortfolioTrigger = solveForPortfolioAtRisk(initialBaseIncome, UPPER_GUARDRAIL_RISK, 0);

            // 3. New Income Levels (When triggered, we reset to Target Risk)
            double incomeAfterCut = solveForRealIncome(lowerPortfolioTrigger, TARGET_RISK, 0);
            double incomeAfterRaise = solveForRealIncome(upperPortfolioTrigger, TARGET_RISK, 0);

            // --- DASHBOARD OUTPUT ---
            System.out.println("=========================================================");
            System.out.println("        INCOME LAB: MODERN GUARDRAILS DASHBOARD          ");
            System.out.println("=========================================================");
            System.out.printf("Current Portfolio:      $%,.2f\n", INITIAL_PORTFOLIO);
            System.out.printf("Initial Base Income:    $%,.2f (Real terms)\n", initialBaseIncome);
            System.out.printf("Go-Go Spending (Y1-10): $%,.2f (Real terms)\n", totalInitialSpend);
            System.out.printf("Initial Withdrawal %%:   %.2f%%\n", withdrawalRatePct);
            System.out.println("---------------------------------------------------------");

            System.out.println("CAPITAL PRESERVATION GUARDRAIL (THE CUT)");
            System.out.printf("  Trigger Portfolio:    $%,.2f (Risk hits %.0f%%)\n", lowerPortfolioTrigger, LOWER_GUARDRAIL_RISK * 100);
            System.out.printf("  New Base Income:      $%,.2f (Reset to %.0f%% risk)\n", incomeAfterCut, TARGET_RISK * 100);
            System.out.printf("  Total Go-Go Spend:    $%,.2f\n", incomeAfterCut * GO_GO_MULTIPLIER);

            System.out.println("\nPROSPERITY GUARDRAIL (THE RAISE)");
            System.out.printf("  Trigger Portfolio:    $%,.2f (Risk hits %.0f%%)\n", upperPortfolioTrigger, UPPER_GUARDRAIL_RISK * 100);
            System.out.printf("  New Base Income:      $%,.2f (Reset to %.0f%% risk)\n", incomeAfterRaise, TARGET_RISK * 100);
            System.out.printf("  Total Go-Go Spend:    $%,.2f\n", incomeAfterRaise * GO_GO_MULTIPLIER);
            System.out.println("=========================================================");
        }

        /**
         * Estimates "Risk of Overspending" (Probability of Failure) in Real Terms.
         */
        private static double estimateRealRisk(double balance, double baseSpending, int currentYear) {
            int failures = 0;
            for (int i = 0; i < NUM_SIMULATIONS; i++) {
                double simBalance = balance;
                for (int t = currentYear; t < RETIREMENT_LENGTH; t++) {
                    double multiplier = (t < GO_GO_YEARS) ? GO_GO_MULTIPLIER : 1.0;
                    double simReturn = REAL_MEAN_RETURN + (RANDOM.nextGaussian() * REAL_STD_DEV);
                    simBalance = (simBalance - (baseSpending * multiplier)) * (1 + simReturn);
                    if (simBalance <= 0) {
                        failures++;
                        break;
                    }
                }
            }
            double v = (double) failures / NUM_SIMULATIONS;
            return v;
        }

        /**
         * Finds the base income level that results in a specific risk level.
         */
        private static double solveForRealIncome(double balance, double targetRisk, int currentYear) {
            double low = 0, high = balance * 0.3; // Up to 30% WR search range
            for (int i = 0; i < 20; i++) {
                double mid = (low + high) / 2;
                if (estimateRealRisk(balance, mid, currentYear) < targetRisk) low = mid;
                else high = mid;
            }
            double v = (low + high) / 2;
            return v;
        }

        /**
         * Finds the portfolio balance that causes a specific income to hit a risk trigger.
         */
        private static double solveForPortfolioAtRisk(double baseIncome, double triggerRisk, int currentYear) {
            double low = 0, high = INITIAL_PORTFOLIO * 5.0; // Search up to 5x initial
            for (int i = 0; i < 20; i++) {
                double mid = (low + high) / 2;
                // Higher portfolio = Lower risk
                if (estimateRealRisk(mid, baseIncome, currentYear) > triggerRisk) low = mid;
                else high = mid;
            }
            double v = (low + high) / 2;
            return v;
        }
    }