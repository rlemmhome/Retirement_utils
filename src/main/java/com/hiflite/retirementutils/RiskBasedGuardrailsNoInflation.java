package com.hiflite.retirementutils;


import java.util.random.RandomGenerator;

public class RiskBasedGuardrailsNoInflation {

    // Simulation parameters
    static final int NUM_SIMULATIONS = 100000;
    static final int RETIREMENT_YEARS = 30;
    static final double MEAN_RETURN = 0.067;      // real expected return
    static final double VOLATILITY = 0.15;       // real volatility
    static final double INFLATION = 0.035;       // for nominal if needed
    static final double TARGET_POS = 0.90;       // 90%
    static final double UPPER_POS = 0.99;
    static final double LOWER_POS = 0.70;

    public static void main(String[] args) {
        double portfolio = 1500000;
        double initialSpending = findSpendingForPoS(portfolio, TARGET_POS);
        System.out.printf("Initial sustainable spending: $%.0f/year ; $%.0f/month (%.0f%% PoS)\n", initialSpending, initialSpending/12.0, TARGET_POS * 100);

        // Upper guardrail
        double upperPortfolio = findPortfolioForPoS(initialSpending, UPPER_POS);
        double upperNewSpending = findSpendingForPoS(upperPortfolio, TARGET_POS);
        System.out.printf("Upper guardrail: If portfolio ≥ $%.0f → increase to $%.0f/year ; $%.0f/month\n", upperPortfolio, upperNewSpending, upperNewSpending/12.0);

        // Lower guardrail
        double lowerPortfolio = findPortfolioForPoS(initialSpending, LOWER_POS);
        double lowerNewSpending = findSpendingForPoS(lowerPortfolio, TARGET_POS);
        System.out.printf("Lower guardrail: If portfolio ≤ $%.0f → decrease to $%.0f/year ; $%.0f/month\n", lowerPortfolio, lowerNewSpending, lowerNewSpending/12.0);
    }

    // Monte Carlo: % of sims where portfolio lasts RETIREMENT_YEARS
    static double calculatePoS(double startPortfolio, double annualSpending) {
        int success = 0;
        RandomGenerator rand = RandomGenerator.getDefault();
        for (int sim = 0; sim < NUM_SIMULATIONS; sim++) {
            double p = startPortfolio;
            boolean survived = true;
            for (int year = 0; year < RETIREMENT_YEARS; year++) {
                double returnRate = Math.exp((MEAN_RETURN - VOLATILITY * VOLATILITY / 2) + VOLATILITY * rand.nextGaussian()) - 1;
                p = p * (1 + returnRate) - annualSpending;
                if (p <= 0) {
                    survived = false;
                    break;
                }
            }
            if (survived) success++;
        }
        return (double) success / NUM_SIMULATIONS;
    }

    // Binary search to find spending that gives target PoS
    static double findSpendingForPoS(double portfolio, double targetPoS) {
        double low = 0;
        double high = portfolio * 0.10; // rough upper bound
        for (int i = 0; i < 50; i++) { // precision
            double mid = (low + high) / 2;
            if (calculatePoS(portfolio, mid) > targetPoS) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2;
    }

    // Binary search to find portfolio that gives target PoS at fixed spending
    static double findPortfolioForPoS(double spending, double targetPoS) {
        double low = 0;
        double high = spending * 50; // rough
        for (int i = 0; i < 50; i++) {
            double mid = (low + high) / 2;
            if (calculatePoS(mid, spending) < targetPoS) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2;
    }
}
