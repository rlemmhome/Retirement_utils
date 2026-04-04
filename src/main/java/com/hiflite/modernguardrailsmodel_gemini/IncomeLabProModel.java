package com.hiflite.modernguardrailsmodel_gemini;

import com.hiflite.utils.TimingUtils;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

public class IncomeLabProModel {

    // --- Core Settings ---
    private static final int MONTE_CARLO_RUNS = 10_000;

    // Inflation: mean=3.79% stddev=2.73%
    // Investment return: mean=6.70% stddev=10.89%

    private static final double REAL_MEAN_RETURN = 0.039;   // (JPM gives nominal, we need to subtract inflation)
    private static final double REAL_STD_DEV = 0.1089;        // orig gives 0.12 ; historical since 1955 is 0.1089
    private static final double INFLATION_RATE = 0.03;
    private static final double INFLATION_STD_DEV = 0.005;

    private static final double TARGET_RISK = 0.20;       // Target (Reset point)
    private static final double LOWER_GUARDRAIL_RISK = 0.28; // Preservation Trigger
    private static final double UPPER_GUARDRAIL_RISK = 0.10; // Prosperity Trigger

    private static final double INITIAL_PORTFOLIO = 1_500_000.0;
    private static final int RETIREMENT_LENGTH = 30; // Total plan length from 2026

    // --- User Specifics ---

    private static final double MAN_SS_ANNUAL = 3367.0 * 12; //
    private static final double WOMAN_SS_ANNUAL = 3377.0 * 12; //
    private static final double ANNUITY_NOMINAL = 22599.0;

    private static final double GO_GO_MULTIPLIER = 1.125;   //spend 25% more in the go-go years
    private static final int GO_GO_YEARS = 10;             // 10 years in the gogo period
    private static final int START_YEAR = 2026;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("'_'yyyyMMdd-HHmmss'_'");

    // The constant capturing the time the class was initialized
    public static final String STARTUP_TIMESTAMP = LocalDateTime.now().format(FORMATTER);

    /**
     * Output CSV file name
     */
    static final String OUTPUT_FILE = "/home/bob/Documents/java_results/incomeLabProModel" + STARTUP_TIMESTAMP + ".csv";


    private static final RandomGenerator RANDOM = RandomGenerator.getDefault();

    public static void main(String[] args) throws IOException {

        TimingUtils timingUtils = new TimingUtils();
        timingUtils.timerStart();

        double currentPortfolio = INITIAL_PORTFOLIO;

        // 1. Solve for the Standard of Living we can afford starting in 2027
        // We pass '1' because the spending doesn't start until Year 1 (2027)
        double realBaseIncome = solveForRealIncome(currentPortfolio, TARGET_RISK, 1);

        // 2. Calculate Dashboard Stats for the 2027 Launch
        double lowerPortfolioTrigger = solveForPortfolioAtRisk(realBaseIncome, LOWER_GUARDRAIL_RISK, 0);
        double upperPortfolioTrigger = solveForPortfolioAtRisk(realBaseIncome, UPPER_GUARDRAIL_RISK, 0);
        double incomeAfterCut = solveForRealIncome(lowerPortfolioTrigger, TARGET_RISK, 0);
        double incomeAfterRaise = solveForRealIncome(upperPortfolioTrigger, TARGET_RISK, 0);

        printDashboard(realBaseIncome, lowerPortfolioTrigger, upperPortfolioTrigger, incomeAfterCut, incomeAfterRaise);

        // 3. Run the Multi-Year Simulation
        // ---- Write CSV ------------------------------------------------------
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(OUTPUT_FILE)))) {

            // Metadata block at top — NO commas anywhere in these lines (would split CSV columns)
            pw.println("# Guyton-Klinger Withdrawal Rate Guardrails - Monte Carlo Simulation");
            pw.println("# Run timestamp (Phoenix time): " + STARTUP_TIMESTAMP);
            pw.printf("# Monte Carlo runs: %d%n", MONTE_CARLO_RUNS);
            pw.printf("# Simulation years: %d%n", RETIREMENT_LENGTH);
            pw.printf("# Start calendar year: %d%n", START_YEAR);
            pw.printf("# Initial portfolio: $%.2f%n", INITIAL_PORTFOLIO);
            //        pw.printf("# Initial withdrawal rate: %.2f%%%n", INITIAL_WITHDRAWAL_RATE * 100);
            //        pw.printf("# Upper guardrail band: +%.0f%% above initial rate (triggers cut of %.0f%%)%n",
            //                UPPER_GUARDRAIL_BAND * 100, GUARDRAIL_ADJUSTMENT * 100);
            //        pw.printf("# Lower guardrail band: -%.0f%% below initial rate (triggers raise of %.0f%%)%n",
            //                LOWER_GUARDRAIL_BAND * 100, GUARDRAIL_ADJUSTMENT * 100);
            pw.printf("# Inflation: mean=%.2f%% stddev=%.2f%%%n", INFLATION_RATE * 100, INFLATION_STD_DEV * 100);
            pw.printf("# Investment return: mean=%.2f%% stddev=%.2f%%%n", REAL_MEAN_RETURN * 100, REAL_STD_DEV * 100);
            pw.println("#");
            pw.println("Year | Portfolio  | Real Spend  | SS/Annuity  | Port. Draw  | Draw %| Risk %| Note");
            pw.println("-------------------------------------------------------------------------------");

            System.out.println("Year | Portfolio  | Real Spend  | SS/Annuity  | Port. Draw  | Draw %| Risk %| Note");
            System.out.println("-------------------------------------------------------------------------------");

            for (int year = 0; year <= RETIREMENT_LENGTH; year++) {
                int calYear = 2026 + year;
                double ss = getSSForYear(year);
                double annuity = getAnnuityForYear(year, REAL_MEAN_RETURN + INFLATION_RATE);

                double totalSpend = 0;
                double portDraw = 0;
                String note = "Steady";

                if (calYear == 2026) {
                    note = "Woman Working";
                    totalSpend = 0; // No portfolio dip
                    portDraw = 0;
                } else {
                    double multiplier = (year <= GO_GO_YEARS) ? GO_GO_MULTIPLIER : 1.0;
                    totalSpend = realBaseIncome * multiplier;
                    portDraw = Math.max(0, totalSpend - ss - annuity);

                    // Risk Check and Guardrail Triggering
                    double currentRisk = estimateRisk(currentPortfolio, realBaseIncome, year);
                    if (currentRisk >= LOWER_GUARDRAIL_RISK) {
                        realBaseIncome = solveForRealIncome(currentPortfolio, TARGET_RISK, year);
                        note = "CUT";
                    } else if (currentRisk <= UPPER_GUARDRAIL_RISK) {
                        realBaseIncome = solveForRealIncome(currentPortfolio, TARGET_RISK, year);
                        note = "RAISE";
                    }
                }

                double riskEstimate = estimateRisk(currentPortfolio, realBaseIncome, year);
                System.out.printf("%4d | $%,9.0f | $%,10.0f | $%,10.0f | $%,10.0f | %4.1f%% | %4.1f%% | %s\n",
                        calYear, currentPortfolio, totalSpend, (ss + annuity), portDraw, portDraw / currentPortfolio * 100.0, riskEstimate * 100, note);
                pw.printf("%4d | $%,9.0f | $%,10.0f | $%,10.0f | $%,10.0f | %4.1f%% | %4.1f%% | %s\n",
                        calYear, currentPortfolio, totalSpend, (ss + annuity), portDraw, portDraw / currentPortfolio * 100.0, riskEstimate * 100, note);

                // Market impact
                double actualReturn = REAL_MEAN_RETURN + (RANDOM.nextGaussian() * REAL_STD_DEV);
                currentPortfolio = (currentPortfolio - portDraw) * (1 + actualReturn);

                if (currentPortfolio <= 0) break;
            }

        }


        System.out.println("CSV written to   : " + OUTPUT_FILE);
        timingUtils.reportTotalElapsedTime();
    }

    private static void printDashboard(double base, double lowTrigger, double highTrigger, double cut, double raise) {
        System.out.println("=========================================================");
        System.out.println("        INCOME LAB: 2026 DEFERRED START MODEL            ");
        System.out.println("=========================================================");
        System.out.printf("Current Portfolio:       $%,.2f\n", INITIAL_PORTFOLIO);
        System.out.printf("Base Living Standard:    $%,.2f (Starts 2027)\n", base);
        System.out.printf("Go-Go Spend (Y1-10):     $%,.2f\n", base * GO_GO_MULTIPLIER);
        System.out.printf("Effective Withdrawal %%:  %.2f%% (Portfolio vs Spend)\n", ((base * GO_GO_MULTIPLIER) / INITIAL_PORTFOLIO) * 100);
        System.out.printf("Number of Sims per year: %,d\n", MONTE_CARLO_RUNS);
        System.out.println("---------------------------------------------------------");
        System.out.printf("Preservation Rail:      $%,.2f -> New Spend: $%,.2f\n", lowTrigger, cut);
        System.out.printf("Prosperity Rail:        $%,.2f -> New Spend: $%,.2f\n", highTrigger, raise);
        System.out.println("=========================================================\n");
    }

    // --- Helper Logic for Income Timeline ---
    private static double getSSForYear(int yearOffset) {
        int calYear = 2026 + yearOffset;
        if (calYear == 2026) return 0;
        double total = 0;
        if (calYear >= 2027) total += MAN_SS_ANNUAL;
        if (calYear >= 2028) total += WOMAN_SS_ANNUAL; // Claims Dec 2027, full in 2028
        else if (calYear == 2027) total += (WOMAN_SS_ANNUAL / 12); // Just December
        return total;
    }

    private static double getAnnuityForYear(int yearOffset, double returnNominal) {
        int calYear = 2026 + yearOffset;
        if (calYear < 2028) return 0;
        double amount = (calYear == 2028) ? ANNUITY_NOMINAL * 0.75 : ANNUITY_NOMINAL;
        double realVal = amount;
        for (int i = 2028; i < calYear; i++) {
            double stepUp = (returnNominal > 0.07) ? (returnNominal - 0.07) : 0;
            realVal = (realVal * (1 + stepUp)) / (1 + INFLATION_RATE);
        }
        return realVal;
    }

    private static double estimateRisk(double balance, double baseIncome, int startYear) {
        int failures = 0;
        for (int i = 0; i < MONTE_CARLO_RUNS; i++) {
            double simBalance = balance;
            for (int t = startYear; t <= RETIREMENT_LENGTH; t++) {
                if (2026 + t == 2026) {
                    simBalance *= (1 + (REAL_MEAN_RETURN + (RANDOM.nextGaussian() * REAL_STD_DEV)));
                    continue;
                }
                double ss = getSSForYear(t);
                double simRet = REAL_MEAN_RETURN + (RANDOM.nextGaussian() * REAL_STD_DEV);
                double annuity = getAnnuityForYear(t, simRet + INFLATION_RATE);
                double multiplier = (t <= GO_GO_YEARS) ? GO_GO_MULTIPLIER : 1.0;
                double draw = (baseIncome * multiplier) - ss - annuity;
                simBalance = (simBalance - Math.max(0, draw)) * (1 + simRet);
                if (simBalance <= 0) {
                    failures++;
                    break;
                }
            }
        }
        double v = (double) failures / MONTE_CARLO_RUNS;
        return v;
    }

    private static double solveForRealIncome(double balance, double targetRisk, int year) {
        double low = 20000, high = 300000;
        for (int i = 0; i < 20; i++) {
            double mid = (low + high) / 2;
            if (estimateRisk(balance, mid, year) < targetRisk) low = mid;
            else high = mid;
        }
        double v = (low + high) / 2;
        return v;
    }

    private static double solveForPortfolioAtRisk(double baseIncome, double triggerRisk, int year) {
        double low = 0, high = INITIAL_PORTFOLIO * 5.0;
        for (int i = 0; i < 20; i++) {
            double mid = (low + high) / 2;
            if (estimateRisk(mid, baseIncome, year) > triggerRisk) low = mid;
            else high = mid;
        }
        double v = (low + high) / 2;
        return v;
    }
}