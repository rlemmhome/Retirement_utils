package com.hiflite.montecarlo.claudeai;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Guyton-Klinger Withdrawal Rate Guardrails Monte Carlo Simulation
 *
 * Implements the Guyton-Klinger decision rules:
 *  - If withdrawal rate > upper guardrail → cut spending by GUARDRAIL_ADJUSTMENT
 *  - If withdrawal rate < lower guardrail → increase spending by GUARDRAIL_ADJUSTMENT
 *  - Portfolio value must be > 0 at end of simulation to be "successful"
 */
public class GuytonKlinger {

    // =========================================================================
    //  USER-CONFIGURABLE CONSTANTS  (edit these before running)
    // =========================================================================

    /** Number of years in the retirement horizon */
    static final int NUM_YEARS = 30;

    /** Starting portfolio balance (dollars) */
    static final double INITIAL_PORTFOLIO = 1_500_000.0;

    /** Calendar year retirement begins */
    static final int START_YEAR = 2026;

    /** Initial withdrawal rate (as decimal, e.g. 0.05 = 5%) */
    static final double INITIAL_WITHDRAWAL_RATE = 0.05;

    /**
     * Upper guardrail: if current withdrawal rate rises this far ABOVE
     * the initial rate, cut spending. (e.g. 0.20 = upper guardrail triggers
     * when rate exceeds initial rate * (1 + 0.20))
     */
    static final double UPPER_GUARDRAIL_BAND = 0.20;   // +20% above initial rate

    /**
     * Lower guardrail: if current withdrawal rate falls this far BELOW
     * the initial rate, increase spending.
     */
    static final double LOWER_GUARDRAIL_BAND = 0.20;   // −20% below initial rate

    /**
     * How much to adjust the annual withdrawal when a guardrail is hit.
     * (e.g. 0.10 = cut or increase the dollar withdrawal amount by 10%)
     */
    static final double GUARDRAIL_ADJUSTMENT = 0.10;

    /**
     * Annual pre-guardrail inflation adjustment applied to the withdrawal
     * each year BEFORE checking guardrails.
     * Set to 0 if you want no automatic inflation step-up (use random inflation instead).
     * Set to a positive value (e.g. 0.03) for a fixed annual raise.
     * NOTE: if USE_RANDOM_INFLATION = true this constant is ignored for the
     *       random component but the base raise still applies.
     */
    static final double ANNUAL_WITHDRAWAL_RAISE = 0.0; // 0 = rely entirely on random inflation

    // --- Inflation parameters (Gaussian) ---
    static final double INFLATION_MEAN   = 0.0379;   // 3% average annual inflation
    static final double INFLATION_STDDEV = 0.0273;  // 1.5% standard deviation

    // --- Investment return parameters (Gaussian) ---
    static final double RETURN_MEAN   = 0.067;   // 7% average annual return
    static final double RETURN_STDDEV = 0.1089;   // 12% standard deviation

    // --- Monte Carlo ---
    static final int MONTE_CARLO_RUNS = 10_000;

    /** Output CSV file name */
    static final String OUTPUT_FILE = "/home/bob/Documents/java_results/guyton_klinger_simulation.csv";

    /** Random seed (use -1 for a different result each run) */
    static final long RANDOM_SEED = -1L;

    // =========================================================================
    //  INTERNAL CONSTANTS  (do not normally need to change)
    // =========================================================================

    static final ZoneId PHOENIX_TZ = ZoneId.of("America/Phoenix");
    static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(PHOENIX_TZ);

    // =========================================================================
    //  MAIN
    // =========================================================================

    public static void main(String[] args) throws IOException {

        String runTimestamp = TIMESTAMP_FMT.format(Instant.now());
        System.out.println("Run started: " + runTimestamp);
        System.out.printf("Guyton-Klinger Monte Carlo  |  Runs: %,d  |  Years: %d%n",
                MONTE_CARLO_RUNS, NUM_YEARS);

        Random rng = (RANDOM_SEED >= 0) ? new Random(RANDOM_SEED) : new Random();

        // Storage for per-run summary data
        int successCount = 0;
        double[] finalBalances = new double[MONTE_CARLO_RUNS];

        // We'll collect every year-row for every run to write to CSV
        List<String[]> csvRows = new ArrayList<>();

        // CSV header — null sentinel used for blank separator rows
        csvRows.add(new String[]{
                "Run",
                "Year_Number",
                "Calendar_Year",
                "Start_Portfolio",
                "Pre_Guardrail_Withdrawal_$",
                "Pre_Guardrail_Withdrawal_%",
                "Guardrail_Triggered",
                "Post_Guardrail_Withdrawal_$",
                "Post_Guardrail_Withdrawal_%",
                "This_Year_Inflation_Rate",
                "Cumulative_Inflation_Factor",
                "Withdrawal_In_Initial_Year_$",
                "Investment_Return",
                "Portfolio_Growth_$",
                "End_Portfolio_Before_Guardrail_Adj",
                "End_Portfolio_Final",
                "Final_Year_End_Portfolio_In_Initial_Year_$",
                "Portfolio_Depleted"
        });

        // ---- Monte Carlo loop -----------------------------------------------
        for (int run = 1; run <= MONTE_CARLO_RUNS; run++) {

            double portfolio        = INITIAL_PORTFOLIO;
            double annualWithdrawal = INITIAL_PORTFOLIO * INITIAL_WITHDRAWAL_RATE;
            boolean depleted        = false;

            // Cumulative inflation factor: starts at 1.0 in year 1.
            // Each year it is multiplied by (1 + thisYearInflation).
            // To convert a future-dollar value to initial-year dollars, divide by this factor.
            double cumulativeInflation = 1.0;

            // We need the year's inflation rate available for both the withdrawal
            // step-up AND for recording in the row, so we draw it once per year.
            double thisYearInflation = 0.0; // year 1 has no step-up

            for (int yr = 1; yr <= NUM_YEARS; yr++) {

                int calYear = START_YEAR + (yr - 1);
                double startPortfolio = portfolio;

                // --- 1. Draw this year's inflation and apply to withdrawal ---
                if (yr == 1) {
                    // Year 1: draw inflation for record-keeping / cumulative factor,
                    // but do NOT step up the withdrawal (it's already set at initial rate).
                    thisYearInflation = rng.nextGaussian() * INFLATION_STDDEV + INFLATION_MEAN;
                } else {
                    thisYearInflation = rng.nextGaussian() * INFLATION_STDDEV + INFLATION_MEAN;
                    double totalRaise = thisYearInflation + ANNUAL_WITHDRAWAL_RAISE;
                    annualWithdrawal *= (1.0 + totalRaise);
                }

                // Update cumulative inflation factor
                cumulativeInflation *= (1.0 + thisYearInflation);

                // --- 2. Pre-guardrail withdrawal rate ---
                double preGuardrailWithdrawal = annualWithdrawal;
                double preGuardrailRate = (startPortfolio > 0)
                        ? annualWithdrawal / startPortfolio : 0.0;

                // --- 3. Check guardrails ---
                double upperGuardrailRate = INITIAL_WITHDRAWAL_RATE * (1.0 + UPPER_GUARDRAIL_BAND);
                double lowerGuardrailRate = INITIAL_WITHDRAWAL_RATE * (1.0 - LOWER_GUARDRAIL_BAND);

                String guardrailTriggered = "None";
                double postGuardrailWithdrawal = annualWithdrawal;

                if (preGuardrailRate > upperGuardrailRate && startPortfolio > 0) {
                    postGuardrailWithdrawal = annualWithdrawal * (1.0 - GUARDRAIL_ADJUSTMENT);
                    guardrailTriggered = "Upper-Cut";
                    annualWithdrawal = postGuardrailWithdrawal;
                } else if (preGuardrailRate < lowerGuardrailRate && startPortfolio > 0) {
                    postGuardrailWithdrawal = annualWithdrawal * (1.0 + GUARDRAIL_ADJUSTMENT);
                    guardrailTriggered = "Lower-Raise";
                    annualWithdrawal = postGuardrailWithdrawal;
                }

                double postGuardrailRate = (startPortfolio > 0)
                        ? postGuardrailWithdrawal / startPortfolio : 0.0;

                // Inflation-adjusted (initial-year $) withdrawal
                double withdrawalInInitialDollars = postGuardrailWithdrawal / cumulativeInflation;

                // --- 4. Deduct withdrawal ---
                double portfolioAfterWithdrawal = startPortfolio - postGuardrailWithdrawal;
                if (portfolioAfterWithdrawal < 0) portfolioAfterWithdrawal = 0;

                // --- 5. Investment return ---
                double investReturn = rng.nextGaussian() * RETURN_STDDEV + RETURN_MEAN;
                double growth       = portfolioAfterWithdrawal * investReturn;
                double endPortfolio = portfolioAfterWithdrawal + growth;
                if (endPortfolio < 0) endPortfolio = 0;

                // End portfolio before guardrail adjustment
                double endBeforeGuardrailAdj;
                if (!guardrailTriggered.equals("None")) {
                    double pgNoAdj = startPortfolio - preGuardrailWithdrawal;
                    if (pgNoAdj < 0) pgNoAdj = 0;
                    endBeforeGuardrailAdj = pgNoAdj + pgNoAdj * investReturn;
                    if (endBeforeGuardrailAdj < 0) endBeforeGuardrailAdj = 0;
                } else {
                    endBeforeGuardrailAdj = endPortfolio;
                }

                portfolio = endPortfolio;

                if (portfolio <= 0 && !depleted) {
                    depleted = true;
                }

                // End portfolio in initial-year dollars (every year)
                String finalYearEndPortfolioReal = fmt(endPortfolio / cumulativeInflation);

                // --- 6. Record row ---
                csvRows.add(new String[]{
                        String.valueOf(run),
                        String.valueOf(yr),
                        String.valueOf(calYear),
                        fmt(startPortfolio),
                        fmt(preGuardrailWithdrawal),
                        pct(preGuardrailRate),
                        guardrailTriggered,
                        fmt(postGuardrailWithdrawal),
                        pct(postGuardrailRate),
                        pct(thisYearInflation),
                        fmt4(cumulativeInflation),
                        fmt(withdrawalInInitialDollars),
                        pct(investReturn),
                        fmt(growth),
                        fmt(endBeforeGuardrailAdj),
                        fmt(endPortfolio),
                        finalYearEndPortfolioReal,
                        depleted ? "YES" : "NO"
                });
            } // end year loop

            // Blank separator row between Monte Carlo runs
            csvRows.add(null);

            finalBalances[run - 1] = portfolio;
            if (!depleted) successCount++;

        } // end Monte Carlo loop

        // ---- Summary stats --------------------------------------------------
        double successRate = 100.0 * successCount / MONTE_CARLO_RUNS;
        DoubleSummaryStatistics stats = Arrays.stream(finalBalances)
                .filter(b -> b > 0)
                .summaryStatistics();

        // ---- Write CSV ------------------------------------------------------
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(OUTPUT_FILE)))) {

            // Metadata block at top — NO commas anywhere in these lines (would split CSV columns)
            pw.println("# Guyton-Klinger Withdrawal Rate Guardrails - Monte Carlo Simulation");
            pw.println("# Run timestamp (Phoenix time): " + runTimestamp);
            pw.printf("# Monte Carlo runs: %d%n",       MONTE_CARLO_RUNS);
            pw.printf("# Simulation years: %d%n",        NUM_YEARS);
            pw.printf("# Start calendar year: %d%n",     START_YEAR);
            pw.printf("# Initial portfolio: $%.2f%n",    INITIAL_PORTFOLIO);
            pw.printf("# Initial withdrawal rate: %.2f%%%n", INITIAL_WITHDRAWAL_RATE * 100);
            pw.printf("# Upper guardrail band: +%.0f%% above initial rate (triggers cut of %.0f%%)%n",
                    UPPER_GUARDRAIL_BAND * 100, GUARDRAIL_ADJUSTMENT * 100);
            pw.printf("# Lower guardrail band: -%.0f%% below initial rate (triggers raise of %.0f%%)%n",
                    LOWER_GUARDRAIL_BAND * 100, GUARDRAIL_ADJUSTMENT * 100);
            pw.printf("# Inflation: mean=%.2f%% stddev=%.2f%%%n",
                    INFLATION_MEAN * 100, INFLATION_STDDEV * 100);
            pw.printf("# Investment return: mean=%.2f%% stddev=%.2f%%%n",
                    RETURN_MEAN * 100, RETURN_STDDEV * 100);
            pw.println("#");
            pw.printf("# SUCCESS RATE: %.2f%% (%d of %d runs survived all %d years)%n",
                    successRate, successCount, MONTE_CARLO_RUNS, NUM_YEARS);
            if (stats.getCount() > 0) {
                pw.printf("# Median final balance (surviving runs): $%.2f%n",
                        medianOf(finalBalances));
                pw.printf("# Avg final balance (surviving runs): $%.2f%n",    stats.getAverage());
                pw.printf("# Min final balance (surviving runs): $%.2f%n",    stats.getMin());
                pw.printf("# Max final balance (surviving runs): $%.2f%n",    stats.getMax());
            }
            pw.println("#");

            // Data rows  (null sentinel = blank separator between runs)
            for (String[] row : csvRows) {
                if (row == null) {
                    pw.println();
                } else {
                    pw.println(String.join(",", row));
                }
            }
        }

        // ---- Console summary ------------------------------------------------
        System.out.println("─".repeat(60));
        System.out.printf("SUCCESS RATE      : %.2f%% (%,d / %,d runs)%n",
                successRate, successCount, MONTE_CARLO_RUNS);
        System.out.printf("Median final bal  : $%,.2f%n", medianOf(finalBalances));
        System.out.printf("Avg final bal     : $%,.2f%n", stats.getAverage());
        System.out.println("─".repeat(60));
        System.out.println("CSV written to   : " + OUTPUT_FILE);
        System.out.println("Completed at     : " + TIMESTAMP_FMT.format(Instant.now()));
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    /** Format dollars with 2 decimal places */
    static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /** Format a multiplier/factor with 6 decimal places */
    static String fmt4(double v) {
        return String.format("%.6f", v);
    }

    /** Format as percentage string with 4 decimal places */
    static String pct(double v) {
        return String.format("%.4f%%", v * 100);
    }

    /** Median of a double array (includes zeros/negatives) */
    static double medianOf(double[] arr) {
        double[] sorted = Arrays.copyOf(arr, arr.length);
        Arrays.sort(sorted);
        int n = sorted.length;
        return (n % 2 == 0)
                ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
                : sorted[n / 2];
    }
}
