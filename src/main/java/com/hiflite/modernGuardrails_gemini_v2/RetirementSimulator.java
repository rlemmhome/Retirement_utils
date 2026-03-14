package com.hiflite.modernGuardrails_gemini_v2;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class RetirementSimulator {

    // --- Constants ---
    static final double INFLATION = 0.03;
    static final double RETURN_RATE = 0.065;
    static final double START_PORTFOLIO = 1_500_000.0;
    static final double MAN_SS_MO = 3788.0;
    static final double WOMAN_SS_MO = 3912.0;
    static final double ANNUITY_YR = 22599.0;
    static final double LIFESTYLE_2025 = 110000.0;
    static final double VACATION_2025 = 25000.0;
    static final double CAR_2025 = 55000.0;
    static final double STD_DEDUCTION_BASE = 15000.0;
    static final double SENIOR_ADDITION = 1550.0;

    public record YearlyResult(
            int year, int manAge, int womanAge, long tax, long totalSpending,
            long otherIncome, long withdrawal, String withdrawalPct,
            long withdrawal2026, long endBalance, long endBalance2026
    ) {}

    public static void main(String[] args) {
        double portfolio = START_PORTFOLIO;
        List<YearlyResult> results = new ArrayList<>();
        DecimalFormat pctFormat = new DecimalFormat("0.00");

        for (int year = 2026; year <= 2055; year++) {
            int manAge = year - 1961;
            int womanAge = year - 1962;

            double infV2025 = Math.pow(1 + INFLATION, year - 2025);
            double infV2026 = Math.pow(1 + INFLATION, year - 2026);

            // 1. Spending Calculation
            double lifestyleFactor = (manAge >= 76) ? 0.8 : 1.0;
            double netSpendingNeed = (LIFESTYLE_2025 * lifestyleFactor * infV2025) +
                    (VACATION_2025 * infV2025);

            if (List.of(2028, 2036, 2044, 2052).contains(year)) {
                netSpendingNeed += (CAR_2025 * infV2025);
            }

            // 2. Income Calculation
            double manSS = (year >= 2027) ? (MAN_SS_MO * 12) * Math.pow(1 + INFLATION, year - 2027) : 0;
            double womanSS = 0;
            if (year == 2027) womanSS = WOMAN_SS_MO;
            else if (year >= 2028) womanSS = (WOMAN_SS_MO * 12) * Math.pow(1 + INFLATION, year - 2028);

            double annuity = 0;
            if (year == 2028) annuity = ANNUITY_YR * 0.75;
            else if (year > 2028) annuity = ANNUITY_YR;

            double otherIncome = manSS + womanSS + annuity;

            // 3. Iterative Tax Calculation
            double stdDeduction = STD_DEDUCTION_BASE + (manAge >= 65 ? SENIOR_ADDITION : 0) + (womanAge >= 65 ? SENIOR_ADDITION : 0);
            double estWithdrawal = Math.max(0, netSpendingNeed - otherIncome);
            double estTax = 0;

            if (year > 2026) {
                for (int i = 0; i < 5; i++) {
                    double taxableIncome = (0.85 * (manSS + womanSS)) + annuity + estWithdrawal - stdDeduction;
                    estTax = calculateFederalTax(taxableIncome);
                    estWithdrawal = netSpendingNeed + estTax - otherIncome;
                }
            } else {
                estTax = calculateFederalTax(netSpendingNeed - stdDeduction);
                estWithdrawal = 0;
            }

            // 4. Portfolio Updates
            double startBal = portfolio;
            double remaining = portfolio - estWithdrawal;
            portfolio = remaining * (1 + RETURN_RATE);

            results.add(new YearlyResult(
                    year, manAge, womanAge, Math.round(estTax), Math.round(netSpendingNeed + estTax),
                    Math.round(otherIncome), Math.round(estWithdrawal),
                    pctFormat.format((estWithdrawal / startBal) * 100) + "%",
                    Math.round(estWithdrawal / infV2026), Math.round(portfolio), Math.round(portfolio / infV2026)
            ));
        }

        // 5. Output CSV
        System.out.println("Year,Man Age (approx),Woman Age (approx),Estimated Federal Tax,Total Spending,Other Income (SS + Annuity),Portfolio Withdrawal,pct of withdrawal from portfolio,Portfolio Withdrawal (2026 $),Portfolio End Balance,Portfolio End Balance (2026 $)");
        for (var r : results) {
            System.out.printf("%d,%d,%d,%d,%d,%d,%d,%s,%d,%d,%d%n",
                    r.year, r.manAge, r.womanAge, r.tax, r.totalSpending, r.otherIncome,
                    r.withdrawal, r.withdrawalPct, r.withdrawal2026, r.endBalance, r.endBalance2026);
        }
    }

    private static double calculateFederalTax(double income) {
        if (income <= 0) return 0;
        double tax = 0;
        if (income > 153100) { tax += (income - 153100) * 0.28; income = 153100; }
        if (income > 75900) { tax += (income - 75900) * 0.25; income = 75900; }
        if (income > 18650) { tax += (income - 18650) * 0.15; income = 18650; }
        tax += income * 0.10;
        return tax;
    }
}