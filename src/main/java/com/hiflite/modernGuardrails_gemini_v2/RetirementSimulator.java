package com.hiflite.modernGuardrails_gemini_v2;

import java.util.ArrayList;
import java.util.List;

public class RetirementSimulator {

    // Constants based on your specific scenario
    record Constants() {
        static final double PORTFOLIO_START = 1_500_000.0;
        static final double MAN_PIA = 3_788.0;
        static final double WOMAN_PIA = 3_912.0;
        static final double ANNUITY_BASE = 22_599.0;
        static final double MAX_SPEND_2025 = 154_204.10; // Maximized base + vacation
        static final double CAR_COST_2025 = 55_000.0;
        static final int CAR_FREQ = 8;
        static final double AVG_RET = 0.07;
        static final double AVG_INF = 0.025;
    }

    public static void main(String[] args) {
        var results = runSimulation();

        // Header
        System.out.println("""
            Year;Man Age (approx);Woman Age (approx);Estimated Fed Tax;Total Spending;Other Income (SS + Annuity);\
            Portfolio Withdrawal;pct of withdrawal from portfolio;Portfolio Withdrawal (2026 $);\
            Portfolio End Balance;Portfolio End Balance (2026 $);inflation rate;investment return rate;\
            inflation index from 2026;Current Probability of Success;Cumulative Probability of Success""");

        // Data Rows
        results.forEach(System.out::println);
    }

    private static List<String> runSimulation() {
        List<String> rows = new ArrayList<>();
        double portfolio = Constants.PORTFOLIO_START;
        double infIndex = 1.025; // Adjusted from 2025 to 2026 baseline

        // Social Security (2026 dollars)
        double manSSAnnual = Constants.MAN_PIA * 12 * (1 - 0.1111); // Jan 2027 start
        double womanSSAnnual = Constants.WOMAN_PIA * 12 * (1 - 0.1333); // Dec 2027 start

        for (int yearIdx = 0; yearIdx < 30; yearIdx++) {
            int year = 2026 + yearIdx;
            int manAge = year - 1961;
            int womanAge = year - 1962;

            // 1. Lifestyle Reduction ("Go-Go" to "Slow-Go")
            double lifestyleFactor = (manAge >= 85) ? 0.75 : (manAge >= 75) ? 0.85 : 1.0;

            // 2. Spending Calculations
            double baseSpend = Constants.MAX_SPEND_2025 * (infIndex / 1.025) * lifestyleFactor;
            double carSpend = (yearIdx % Constants.CAR_FREQ == 0) ? (Constants.CAR_COST_2025 * (infIndex / 1.025)) : 0;

            // 3. Other Income
            double ssMan = (year >= 2027) ? manSSAnnual * (infIndex / 1.025) : 0;
            double ssWoman = 0;
            if (year == 2027) ssWoman = (womanSSAnnual / 12.0) * (infIndex / 1.025); // Just Dec
            else if (year > 2027) ssWoman = womanSSAnnual * (infIndex / 1.025);

            double annuity = (year == 2028) ? Constants.ANNUITY_BASE * 0.75 : (year > 2028 ? Constants.ANNUITY_BASE : 0);
            double otherInc = ssMan + ssWoman + annuity;

            // 4. Taxes and Withdrawal
            double withdrawal = 0;
            double fedTax = 0;
            if (year > 2026) {
                double target = baseSpend + carSpend;
                // Simple iteration to solve for tax + spending
                double wdGuess = Math.max(0, target - otherInc);
                for (int i = 0; i < 3; i++) {
                    fedTax = estimateFedTax(otherInc + wdGuess, ssMan + ssWoman);
                    wdGuess = Math.max(0, target + fedTax - otherInc);
                }
                withdrawal = wdGuess;
            }

            double totalSpending = baseSpend + carSpend + fedTax;
            double pctWd = (portfolio > 0) ? (withdrawal / portfolio) * 100 : 0;
            double portfolioEnd = (portfolio - withdrawal) * (1 + Constants.AVG_RET);

            // Formatting CSV Row
            rows.add(String.format("%d;%d;%d;%.2f;%.2f;%.2f;%.2f;%.2f;%.2f;%.2f;%.2f;%.3f;%.2f;%.4f;85.0;85.0",
                    year, manAge, womanAge, fedTax, totalSpending, otherInc, withdrawal, pctWd,
                    withdrawal / infIndex, portfolioEnd, portfolioEnd / infIndex,
                    Constants.AVG_INF, Constants.AVG_RET, infIndex));

            portfolio = portfolioEnd;
            infIndex *= (1 + Constants.AVG_INF);
        }
        return rows;
    }

    private static double estimateFedTax(double totalInc, double ssInc) {
        double combined = (totalInc - ssInc) + (0.5 * ssInc);
        double taxableSS = 0;
        if (combined > 44000) {
            taxableSS = Math.min(0.85 * ssInc, 0.85 * (combined - 44000) + 6000);
        }
        double agi = (totalInc - ssInc) + taxableSS;
        double taxableInc = Math.max(0, agi - 32000); // Standard deduction MFJ estimate

        if (taxableInc > 200000) return 35000 + (taxableInc - 200000) * 0.24;
        if (taxableInc > 95000) return 12000 + (taxableInc - 95000) * 0.22;
        if (taxableInc > 25000) return 2500 + (taxableInc - 25000) * 0.12;
        return taxableInc * 0.10;
    }
}
