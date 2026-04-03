package com.hiflite.modernGuardrails_gemini_v2;

import java.util.List;
import java.util.Random;

public class MonteCarloSimulator_TooSimple {
    // Simulation Configuration
    static final int ITERATIONS = 10_000;
    static final double MEAN_RETURN = 0.07;
    static final double STD_DEV = 0.15;

    // Retirement Constants
    static final double START_PORTFOLIO = 1_500_000.0;
    static final double MAN_SS_MO = 3788.0;
    static final double WOMAN_SS_MO = 3912.0;
    static final double ANNUITY_YR = 22599.0;
    static final double LIFESTYLE_2025 = 110000.0;
    static final double VACATION_2025 = 25000.0;
    static final double CAR_2025 = 55000.0;

    public static void main(String[] args) {
        Random rand = new Random();
        int successCount = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            if (simulatePath(rand)) {
                successCount++;
            }
        }

        double probabilityOfSuccess = (double) successCount / ITERATIONS * 100;
        System.out.printf("After %d iterations, the probability of portfolio success is: %.2f%%%n",
                ITERATIONS, probabilityOfSuccess);
    }

    private static boolean simulatePath(Random rand) {
        double portfolio = START_PORTFOLIO;

        for (int year = 2026; year <= 2055; year++) {
            // Apply annual market volatility
            double annualReturn = MEAN_RETURN + (rand.nextGaussian() * STD_DEV);
            portfolio *= (1 + annualReturn);

            // Calculate annual need
            double netNeed = calculateNetNeed(year);

            // Apply withdrawal
            portfolio -= netNeed;

            if (portfolio <= 0) return false;
        }
        return true;
    }

    private static double calculateNetNeed(int year) {
        double inf = Math.pow(1.03, year - 2025);
        int manAge = year - 1961;

        double expenses = (LIFESTYLE_2025 * (manAge >= 76 ? 0.8 : 1.0) * inf) +
                (VACATION_2025 * inf);

        if (List.of(2028, 2036, 2044, 2052).contains(year)) {
            expenses += (CAR_2025 * inf);
        }

        double income = getIncome(year);
        return Math.max(0, expenses - income);
    }

    private static double getIncome(int year) {
        double manSS = (year >= 2027) ? (MAN_SS_MO * 12) * Math.pow(1.03, year - 2027) : 0;
        double womanSS = (year == 2027) ? WOMAN_SS_MO : (year >= 2028) ? (WOMAN_SS_MO * 12) * Math.pow(1.03, year - 2028) : 0;
        double annuity = (year == 2028) ? ANNUITY_YR * 0.75 : (year > 2028) ? ANNUITY_YR : 0;
        return manSS + womanSS + annuity;
    }
}