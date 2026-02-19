package com.hiflite.riskbasedguardrailsgrok;

import java.util.random.RandomGenerator;


public class RiskBasedGuardrailsWithInflation_withdrawalHistory {

    // Simulation parameters
    static final int NUM_SIMULATIONS = 10000;
    static final int RETIREMENT_YEARS = 30;
    static final double REAL_MEAN_RETURN = 0.067;
    static final double REAL_VOLATILITY = 0.15;
    static final double INFLATION_MEAN = 0.035;
    static final double INFLATION_VOL = 0.015;
    static final double TARGET_POS = 0.90;
    static final double UPPER_POS = 0.95;
    static final double LOWER_POS = 0.85;

    public static void main(String[] args) {
        double initialPortfolio = 1_500_000;

        double initialRealSpending = findRealSpendingForPoS(initialPortfolio, TARGET_POS);
        System.out.printf("Initial sustainable real spending: $%.0f/year ; $%.0f/month ; [%.2f%%]    (%.0f%% PoS)\n",
                initialRealSpending, initialRealSpending/12.0, initialRealSpending/initialPortfolio*100.0, TARGET_POS * 100);

        // Guardrails (unchanged)
        double upperPortfolio = findPortfolioForPoS(initialRealSpending, UPPER_POS);
        double upperNewRealSpending = findRealSpendingForPoS(upperPortfolio, TARGET_POS);
        System.out.printf("Upper guardrail: If portfolio ≥ $%.0f → increase real spending to $%.0f/year\n",
                upperPortfolio, upperNewRealSpending);

        double lowerPortfolio = findPortfolioForPoS(initialRealSpending, LOWER_POS);
        double lowerNewRealSpending = findRealSpendingForPoS(lowerPortfolio, TARGET_POS);
        System.out.printf("Lower guardrail: If portfolio ≤ $%.0f → decrease real spending to $%.0f/year\n",
                lowerPortfolio, lowerNewRealSpending);

        // === NEW: Spending history tracking ===
        printSampleSpendingPath(initialPortfolio, initialRealSpending);
        printAverageSpendingTrajectory(initialPortfolio, initialRealSpending);
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

    // ──────────────────────────────────────────────────────────────
    // NEW: One concrete simulated spending history
    // ──────────────────────────────────────────────────────────────
    static void printSampleSpendingPath(double startPortfolio, double initialRealSpending) {
        RandomGenerator rand = RandomGenerator.getDefault();
        double portfolio = startPortfolio;
        double currentSpending = initialRealSpending;

        System.out.println("\n=== Sample Spending History (one possible future) ===");
        System.out.println("Year | Start Portfolio | Real Ret | Inflation | Spending (nominal) | End Portfolio");
        System.out.println("-----+---------------+----------+-----------+-------------------+---------------");

        for (int year = 0; year < RETIREMENT_YEARS; year++) {
            double realReturn = Math.exp((REAL_MEAN_RETURN - REAL_VOLATILITY * REAL_VOLATILITY / 2)
                    + REAL_VOLATILITY * rand.nextGaussian()) - 1;
            double inflation = INFLATION_MEAN + INFLATION_VOL * rand.nextGaussian();
            double nominalReturn = (1 + realReturn) * (1 + inflation) - 1;

            double startP = portfolio;
            portfolio *= (1 + nominalReturn);

            if (year > 0) currentSpending *= (1 + inflation);

            double spending = currentSpending;
            portfolio -= spending;

            System.out.printf("%4d | %15.0f | %7.2f%% | %8.2f%% | %17.0f | %13.0f\n",
                    year + 1, startP, realReturn * 100, inflation * 100, spending, Math.max(0, portfolio));

            if (portfolio <= 0) {
                System.out.println("   → Portfolio depleted");
                break;
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // NEW: Average nominal spending per year across all simulations
    // ──────────────────────────────────────────────────────────────
    static void printAverageSpendingTrajectory(double startPortfolio, double initialRealSpending) {
        double[] sumSpending = new double[RETIREMENT_YEARS];
        int[] simsReachedYear = new int[RETIREMENT_YEARS];
        int successes = 0;

        RandomGenerator rand = RandomGenerator.getDefault();

        for (int sim = 0; sim < NUM_SIMULATIONS; sim++) {
            double portfolio = startPortfolio;
            double currentSpending = initialRealSpending;
            boolean survived = true;

            for (int year = 0; year < RETIREMENT_YEARS; year++) {
                double realReturn = Math.exp((REAL_MEAN_RETURN - REAL_VOLATILITY * REAL_VOLATILITY / 2)
                        + REAL_VOLATILITY * rand.nextGaussian()) - 1;
                double inflation = INFLATION_MEAN + INFLATION_VOL * rand.nextGaussian();
                double nominalReturn = (1 + realReturn) * (1 + inflation) - 1;

                portfolio *= (1 + nominalReturn);

                if (year > 0) currentSpending *= (1 + inflation);

                double spending = currentSpending;
                portfolio -= spending;

                sumSpending[year] += spending;
                simsReachedYear[year]++;

                if (portfolio <= 0) {
                    survived = false;
                    break;
                }
            }
            if (survived) successes++;
        }

        System.out.println("\n=== Average Nominal Spending Trajectory ===");
        System.out.printf("Overall PoS: %.1f%%\n", (double) successes / NUM_SIMULATIONS * 100);
        System.out.println("Year | Average Spending (nominal $)");
        for (int y = 0; y < RETIREMENT_YEARS; y++) {
            if (simsReachedYear[y] > 0) {
                double avg = sumSpending[y] / simsReachedYear[y];
                System.out.printf("%4d | $%10.0f\n", y + 1, avg);
            }
        }
    }

    // (insert the original calculatePoS, findRealSpendingForPoS, findPortfolioForPoS methods here)
}