package com.hiflite.modernGuardrails_gemini_v2;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

public class RetirementGuardrailSim {

    record Config(
            double portfolioStart, double manPIA, double womanPIA,
            double annuityBase, double targetSpend2025, double carCost2025,
            int carFreq, double avgRet, double stdRet,
            double avgInf, double stdInf, int simHorizon,
            double upperGuardrail, double lowerGuardrail, double adjustmentFactor
    ) {}

    private static final Config SETTINGS = new Config(
            1_500_000.0, 3_788.0, 3_912.0, 22_599.0,
            154_204.10, 55_000.0, 8, 0.07, 0.10,
            0.025, 0.01, 30,
            0.92, 0.78, 0.10 // 92% raise trigger, 78% cut trigger, 10% move
    );

    private static final RandomGenerator RNG = RandomGenerator.of("L64X128MixRandom");

    public static void main(String[] args) {
        var results = runGuardrailSimulation();

        System.out.println("""
            Year;Man Age;Woman Age;Adjustment;Total Spending;Other Income;\
            Portfolio Withdrawal;pct of withdrawal;Portfolio End Balance;\
            Portfolio End Balance (2026 $);Current PoS""");

        results.forEach(System.out::println);
    }

    private static List<String> runGuardrailSimulation() {
        List<String> rows = new ArrayList<>();
        double portfolio = SETTINGS.portfolioStart();
        double infIndex = 1.025;
        double currentSpendingBase = SETTINGS.targetSpend2025();

        double manSS = SETTINGS.manPIA() * 12 * (1 - 0.1111);
        double womanSS = SETTINGS.womanPIA() * 12 * (1 - 0.1333);

        for (int yearIdx = 0; yearIdx < SETTINGS.simHorizon(); yearIdx++) {
            int year = 2026 + yearIdx;
            int mAge = year - 1961;
            int wAge = year - 1962;

            // 1. Calculate PoS for the REMAINING years
            double currentPoS = calculatePoS(yearIdx, portfolio, infIndex, currentSpendingBase, manSS, womanSS);

            // 2. Guardrail Logic
            String adjustmentMsg = "None";
            if (currentPoS >= SETTINGS.upperGuardrail()) {
                currentSpendingBase *= (1 + SETTINGS.adjustmentFactor());
                adjustmentMsg = "RAISE (Market High)";
            } else if (currentPoS <= SETTINGS.lowerGuardrail() && yearIdx > 0) {
                currentSpendingBase *= (1 - SETTINGS.adjustmentFactor());
                adjustmentMsg = "CUT (Safety Protocol)";
            }

            // 3. Execution (Standard Year Logic)
            double lifestyleFactor = (mAge >= 85) ? 0.75 : (mAge >= 75) ? 0.85 : 1.0;
            double baseSpend = currentSpendingBase * (infIndex / 1.025) * lifestyleFactor;
            double carSpend = (yearIdx % SETTINGS.carFreq() == 0) ? (SETTINGS.carCost2025() * (infIndex / 1.025)) : 0;

            double otherInc = calculateOtherIncome(year, infIndex, manSS, womanSS);

            double withdrawal = 0, fedTax = 0;
            if (year > 2026) {
                double target = baseSpend + carSpend;
                double wdGuess = Math.max(0, target - otherInc);
                for (int i = 0; i < 2; i++) {
                    fedTax = estimateFedTax(otherInc + wdGuess, otherInc - (year >= 2028 ? SETTINGS.annuityBase() : 0));
                    wdGuess = Math.max(0, target + fedTax - otherInc);
                }
                withdrawal = wdGuess;
            }

            double portfolioEnd = (portfolio - withdrawal) * (1 + SETTINGS.avgRet());

            rows.add(String.format("%d;%d;%d;%s;%.2f;%.2f;%.2f;%.2f;%.2f;%.2f;%.1f%%",
                    year, mAge, wAge, adjustmentMsg, baseSpend + carSpend + fedTax, otherInc, withdrawal,
                    (portfolio > 0 ? (withdrawal/portfolio)*100 : 0), portfolioEnd, portfolioEnd / infIndex, currentPoS * 100));

            portfolio = portfolioEnd;
            infIndex *= (1 + SETTINGS.avgInf());
            if (portfolio <= 0) break;
        }
        return rows;
    }

    private static double calculatePoS(int currentYearIdx, double pStart, double infStart, double spendBase, double mSS, double wSS) {
        int successes = 0;
        for (int t = 0; t < 1000; t++) {
            double p = pStart;
            double idx = infStart;
            boolean failed = false;
            for (int y = currentYearIdx; y < SETTINGS.simHorizon(); y++) {
                int yr = 2026 + y;
                double lf = ((yr - 1961) >= 85) ? 0.75 : ((yr - 1961) >= 75 ? 0.85 : 1.0);
                double spend = (spendBase * (idx/1.025) * lf) + ((y % SETTINGS.carFreq() == 0) ? (SETTINGS.carCost2025() * (idx/1.025)) : 0);
                double inc = calculateOtherIncome(yr, idx, mSS, wSS);
                p = (p - Math.max(0, spend - inc)) * (1 + RNG.nextGaussian(SETTINGS.avgRet(), SETTINGS.stdRet()));
                idx *= (1 + RNG.nextGaussian(SETTINGS.avgInf(), SETTINGS.stdInf()));
                if (p <= 0) { failed = true; break; }
            }
            if (!failed) successes++;
        }
        return (double) successes / 1000;
    }

    private static double calculateOtherIncome(int year, double idx, double mSS, double wSS) {
        double inc = (year >= 2027 ? mSS * (idx/1.025) : 0);
        if (year > 2027) inc += wSS * (idx/1.025);
        else if (year == 2027) inc += (wSS/12) * (idx/1.025);
        if (year >= 2028) inc += (year == 2028 ? SETTINGS.annuityBase() * 0.75 : SETTINGS.annuityBase());
        return inc;
    }

    private static double estimateFedTax(double totalInc, double ssInc) {
        double combined = (totalInc - ssInc) + (0.5 * ssInc);
        double taxableSS = (combined > 44000) ? Math.min(0.85 * ssInc, 0.85 * (combined - 44000) + 6000) : 0;
        double taxableInc = Math.max(0, (totalInc - ssInc) + taxableSS - 32000);
        if (taxableInc > 95000) return 12000 + (taxableInc - 95000) * 0.22;
        return (taxableInc > 25000) ? 2500 + (taxableInc - 25000) * 0.12 : taxableInc * 0.10;
    }
}