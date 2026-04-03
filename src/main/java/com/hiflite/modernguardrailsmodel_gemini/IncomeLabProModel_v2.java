package com.hiflite.modernguardrailsmodel_gemini;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

/**
 * Modern Guardrails Model: Real-Term Monte Carlo Simulation
 * Includes: Stochastic Inflation, Go-Go Years, Social Security Bridging,
 * and Annuity Step-up Logic.
 */
public class IncomeLabProModel_v2 {

    // --- Core Settings ---
    private static final int MONTE_CARLO_RUNS = 10_000;

    // Investment return: mean=6.70% (Nominal) -> ~3.9% Real
    private static final double REAL_MEAN_RETURN = 0.039;
    private static final double REAL_STD_DEV = 0.1089;

    // Inflation: mean=3.0% stddev=0.5%
    private static final double INFLATION_MEAN = 0.03;
    private static final double INFLATION_STD_DEV = 0.005;

    private static final double TARGET_RISK = 0.20;
    private static final double LOWER_GUARDRAIL_RISK = 0.28;
    private static final double UPPER_GUARDRAIL_RISK = 0.10;

    private static final double INITIAL_PORTFOLIO = 1_500_000.0;
    private static final int RETIREMENT_LENGTH = 30;
    private static final int START_YEAR = 2026;

    // --- User Specifics ---
    private static final double MAN_SS_ANNUAL = 3367.0 * 12; // $40,404
    private static final double WOMAN_SS_ANNUAL = 3377.0 * 12; // $40,524
    private static final double ANNUITY_NOMINAL = 22599.0;

    private static final double GO_GO_MULTIPLIER = 1.125;
    private static final int GO_GO_YEARS = 10;

    private static final RandomGenerator RANDOM = RandomGenerator.getDefault();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    public static final String TIMESTAMP = LocalDateTime.now().format(FORMATTER);

    // Adjust this path as needed for your local environment
    static final String OUTPUT_FILE = "/home/bob/Documents/java_results/incomeLabProResults_v2" + TIMESTAMP + ".csv";

    public static void main(String[] args) throws IOException {
        double currentPortfolio = INITIAL_PORTFOLIO;

        // 1. Initial Solvers: Determine 2027 starting point based on today's portfolio
        // We solve for the income starting in 2027 (Year 1)
        double realBaseIncome = solveForRealIncome(currentPortfolio, TARGET_RISK, 0);

        // 2. Guardrail Dashboard: Calculate current trigger points
        double lowerPortfolioTrigger = solveForPortfolioAtRisk(realBaseIncome, LOWER_GUARDRAIL_RISK, 0);
        double upperPortfolioTrigger = solveForPortfolioAtRisk(realBaseIncome, UPPER_GUARDRAIL_RISK, 0);
        double incomeAfterCut = solveForRealIncome(lowerPortfolioTrigger, TARGET_RISK, 0);
        double incomeAfterRaise = solveForRealIncome(upperPortfolioTrigger, TARGET_RISK, 0);

        printDashboard(realBaseIncome, lowerPortfolioTrigger, upperPortfolioTrigger, incomeAfterCut, incomeAfterRaise);

        // 3. Main Simulation Loop
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(OUTPUT_FILE)))) {
            pw.println("Year,Portfolio_End_Real,Total_Spend_Real,SS_Annuity_Real,Port_Withdrawal_Real,Draw_Pct,Risk_Pct,Note");

            System.out.println("Year | Port. End (Real) | Total Spend (Real) | Port. Draw (Real) | Risk % | Note");
            System.out.println("----------------------------------------------------------------------------------");

            for (int year = 0; year <= RETIREMENT_LENGTH; year++) {
                int calYear = START_YEAR + year;

                double ssReal = getSSForYear(year);
                // Simulate a nominal return to check for annuity step-up
                double simNominalReturn = REAL_MEAN_RETURN + INFLATION_MEAN + (RANDOM.nextGaussian() * REAL_STD_DEV);
                double annuityReal = getAnnuityForYearReal(year, simNominalReturn);

                double totalSpendReal = 0;
                double portDrawReal = 0;
                String note = "Steady";

                if (calYear == 2026) {
                    note = "Woman Working";
                } else {
                    double multiplier = (year <= GO_GO_YEARS) ? GO_GO_MULTIPLIER : 1.0;
                    totalSpendReal = realBaseIncome * multiplier;
                    portDrawReal = Math.max(0, totalSpendReal - ssReal - annuityReal);

                    // Yearly Risk Check & Adjustment
                    double currentRisk = estimateRisk(currentPortfolio, realBaseIncome, year);
                    if (currentRisk >= LOWER_GUARDRAIL_RISK) {
                        realBaseIncome = solveForRealIncome(currentPortfolio, TARGET_RISK, year);
                        note = "CUT";
                    } else if (currentRisk <= UPPER_GUARDRAIL_RISK) {
                        realBaseIncome = solveForRealIncome(currentPortfolio, TARGET_RISK, year);
                        note = "RAISE";
                    }
                }

                double finalRisk = estimateRisk(currentPortfolio, realBaseIncome, year);
                double drawPct = (currentPortfolio > 0) ? (portDrawReal / currentPortfolio * 100) : 0;

                // Logging
                pw.printf("%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s\n",
                        calYear, currentPortfolio, totalSpendReal, (ssReal + annuityReal), portDrawReal, drawPct, finalRisk * 100, note);

                System.out.printf("%4d | $%,16.2f | $%,18.2f | $%,17.2f | %6.1f%% | %s\n",
                        calYear, currentPortfolio, totalSpendReal, portDrawReal, finalRisk * 100, note);

                // Market Impact for the year
                double actualRealReturn = REAL_MEAN_RETURN + (RANDOM.nextGaussian() * REAL_STD_DEV);
                currentPortfolio = (currentPortfolio - portDrawReal) * (1 + actualRealReturn);

                if (currentPortfolio <= 0) {
                    pw.println(calYear + ",0,0,0,0,0,100.0,EXHAUSTED");
                    break;
                }
            }
        }
        System.out.println("\nCSV results written to: " + OUTPUT_FILE);
    }

    // --- Core Logic Methods ---

    private static double estimateRisk(double balance, double baseIncome, int startYear) {
        int failures = 0;
        for (int i = 0; i < MONTE_CARLO_RUNS; i++) {
            double simBalance = balance;
            for (int t = startYear; t <= RETIREMENT_LENGTH; t++) {
                int simCalYear = START_YEAR + t;

                // No draw in 2026
                double draw = 0;
                double simRealRet = REAL_MEAN_RETURN + (RANDOM.nextGaussian() * REAL_STD_DEV);

                if (simCalYear > 2026) {
                    double ss = getSSForYear(t);
                    double simInflation = INFLATION_MEAN + (RANDOM.nextGaussian() * INFLATION_STD_DEV);
                    double annuity = getAnnuityForYearReal(t, simRealRet + simInflation);
                    double multiplier = (t <= GO_GO_YEARS) ? GO_GO_MULTIPLIER : 1.0;
                    draw = (baseIncome * multiplier) - ss - annuity;
                }

                simBalance = (simBalance - Math.max(0, draw)) * (1 + simRealRet);
                if (simBalance <= 0) {
                    failures++;
                    break;
                }
            }
        }
        return (double) failures / MONTE_CARLO_RUNS;
    }

    private static double solveForRealIncome(double balance, double targetRisk, int year) {
        double low = 20000, high = 400000;
        for (int i = 0; i < 20; i++) {
            double mid = (low + high) / 2;
            if (estimateRisk(balance, mid, year) < targetRisk) low = mid;
            else high = mid;
        }
        return (low + high) / 2;
    }

    private static double solveForPortfolioAtRisk(double baseIncome, double triggerRisk, int year) {
        double low = 0, high = INITIAL_PORTFOLIO * 5.0;
        for (int i = 0; i < 20; i++) {
            double mid = (low + high) / 2;
            if (estimateRisk(mid, baseIncome, year) > triggerRisk) low = mid;
            else high = mid;
        }
        return (low + high) / 2;
    }

    private static double getSSForYear(int yearOffset) {
        int calYear = START_YEAR + yearOffset;
        if (calYear <= 2026) return 0;
        double total = 0;
        // Man starts Jan 2027
        if (calYear >= 2027) total += MAN_SS_ANNUAL;
        // Woman claims Dec 2027: 1 month in 2027, full after
        if (calYear == 2027) total += (WOMAN_SS_ANNUAL / 12);
        else if (calYear > 2027) total += WOMAN_SS_ANNUAL;
        return total;
    }

    private static double getAnnuityForYearReal(int yearOffset, double nominalReturn) {
        int calYear = START_YEAR + yearOffset;
        if (calYear < 2028) return 0;

        double amount = (calYear == 2028) ? ANNUITY_NOMINAL * 0.75 : ANNUITY_NOMINAL;
        double realVal = amount;

        // Compound Real Decay (Inflation) vs Step-up (Market > 7%)
        for (int i = 2028; i < calYear; i++) {
            double stepUp = (nominalReturn > 0.07) ? (nominalReturn - 0.07) : 0;
            realVal = (realVal * (1 + stepUp)) / (1 + INFLATION_MEAN);
        }
        return realVal;
    }

    private static void printDashboard(double base, double lowTrigger, double highTrigger, double cut, double raise) {
        System.out.println("=========================================================");
        System.out.println("        INCOME LAB: MODERN GUARDRAILS DASHBOARD          ");
        System.out.println("=========================================================");
        System.out.printf("Current Portfolio:       $%,.2f\n", INITIAL_PORTFOLIO);
        System.out.printf("Base Living Standard:    $%,.2f (Today's Dollars)\n", base);
        System.out.printf("Go-Go Total Spending:    $%,.2f (Until Year 10)\n", base * GO_GO_MULTIPLIER);
        System.out.println("---------------------------------------------------------");
        System.out.printf("Capital Preservation (CUT)   if Portfolio < $%,.2f\n", lowTrigger);
        System.out.printf("Prosperity Rail (RAISE)      if Portfolio > $%,.2f\n", highTrigger);
        System.out.println("---------------------------------------------------------");
        System.out.printf("Simulated Cut Result:   $%,.2f base income\n", cut);
        System.out.printf("Simulated Raise Result: $%,.2f base income\n", raise);
        System.out.println("=========================================================\n");
    }
}