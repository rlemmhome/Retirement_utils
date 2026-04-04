package com.hiflite.modernguardrailsmodel_gemini;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

public class IncomeLabProModel_v8 {

    // --- Core Parameters ---
    private static final int MONTE_CARLO_RUNS = 10_000;
    private static final double REAL_MEAN_RETURN = 0.037;
    private static final double REAL_STD_DEV = 0.1089;
    private static final double INFLATION_MEAN = 0.027;
    private static final double INFLATION_STD_DEV = 0.012;

    private static final double TARGET_RISK = 0.20;
    private static final double LOWER_GUARDRAIL_RISK = 0.28;
    private static final double UPPER_GUARDRAIL_RISK = 0.10;

    private static final double INITIAL_PORTFOLIO = 1_500_000.0;
    private static final int RETIREMENT_LENGTH = 30;
    private static final int START_YEAR = 2026;

    // --- Income Sources & Go-Go Logic ---
    private static final double MAN_SS_ANNUAL = 3367.0 * 12;
    private static final double WOMAN_SS_ANNUAL = 3377.0 * 12;
    private static final double ANNUITY_NOMINAL_START = 22599.0;
    private static final double GO_GO_MULTIPLIER = 1.125;
    private static final int GO_GO_YEARS = 10;

    private static final RandomGenerator RANDOM = RandomGenerator.getDefault();
    static final String TIMESTAMP = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

    private static final String RESULTS_FOLDER = "/home/bob/Documents/java_results/";
    static final String OUTPUT_FILE = RESULTS_FOLDER + "incomeLabProResults_v8_" + TIMESTAMP + ".csv";

    public static void main(String[] args) throws IOException {
        double currentPortfolio = INITIAL_PORTFOLIO;
        double currentAnnuityReal = 0;

        int cutCount = 0, raiseCount = 0;
        double totalSpendReal = 0;
        double maxRiskSeen = 0;

        // Initial solve (2026 dollars)
        double realBaseIncome = solveForRealIncome(currentPortfolio, 0, TARGET_RISK, 0);
        double initialBaseIncome = realBaseIncome;

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(OUTPUT_FILE)))) {
            // Full Column Set Requested
            pw.println("Year,Portfolio_End_Real,Total_Spend_Real,SS_Annuity_Real,Port_Withdrawal_Real,Draw_Pct," +
                    "Prob_Success_Pct,Risk_Pct,Yearly_Inf_Pct,Yearly_Ret_Pct," +
                    "Cut_Threshold_Port,Cut_Adj_Amt,Raise_Threshold_Port,Raise_Adj_Amt,Note");

            for (int year = 0; year <= RETIREMENT_LENGTH; year++) {
                int calYear = START_YEAR + year;
                double startPortfolioOfYear = currentPortfolio;

                // Simulate this year's environment
                double simInf = INFLATION_MEAN + (RANDOM.nextGaussian() * INFLATION_STD_DEV);
                double simRet = REAL_MEAN_RETURN + (RANDOM.nextGaussian() * REAL_STD_DEV);

                double ssReal = getSSForYear(year);
                currentAnnuityReal = updateAnnuityState(currentAnnuityReal, calYear, simRet + simInf, simInf);

                double multiplier = (year <= GO_GO_YEARS) ? GO_GO_MULTIPLIER : 1.0;
                double yearlySpendReal = 0;
                double portDrawReal = 0;
                String note = (year <= GO_GO_YEARS) ? "Go-Go" : "Slow-Go";

                if (calYear == 2026) {
                    note = "Salary Phase";
                } else {
                    yearlySpendReal = realBaseIncome * multiplier;
                    portDrawReal = Math.max(0, yearlySpendReal - ssReal - currentAnnuityReal);
                    if (calYear == 2027) portDrawReal *= 0.5;

                    // Guardrail check at start of year
                    double currentRisk = estimateRisk(currentPortfolio, currentAnnuityReal, realBaseIncome, year);
                    if (currentRisk >= LOWER_GUARDRAIL_RISK) {
                        realBaseIncome = solveForRealIncome(currentPortfolio, currentAnnuityReal, TARGET_RISK, year);
                        note += "/CUT"; cutCount++;
                    } else if (currentRisk <= UPPER_GUARDRAIL_RISK && year < RETIREMENT_LENGTH - 2) {
                        realBaseIncome = solveForRealIncome(currentPortfolio, currentAnnuityReal, TARGET_RISK, year);
                        note += "/RAISE"; raiseCount++;
                    }
                }

                // Threshold & Adjustment Calculations
                double cutThreshold = solveForPortfolioAtRisk(realBaseIncome, currentAnnuityReal, LOWER_GUARDRAIL_RISK, year);
                double raiseThreshold = solveForPortfolioAtRisk(realBaseIncome, currentAnnuityReal, UPPER_GUARDRAIL_RISK, year);
                double cutAdj = (solveForRealIncome(cutThreshold, currentAnnuityReal, TARGET_RISK, year) - realBaseIncome) * multiplier;
                double raiseAdj = (solveForRealIncome(raiseThreshold, currentAnnuityReal, TARGET_RISK, year) - realBaseIncome) * multiplier;

                // Process Cash Flow & Market Move
                currentPortfolio = (currentPortfolio - portDrawReal) * (1 + simRet);
                totalSpendReal += yearlySpendReal;

                double drawPct = (startPortfolioOfYear > 0) ? (portDrawReal / startPortfolioOfYear) * 100.0 : 0;
                double finalRisk = estimateRisk(currentPortfolio, currentAnnuityReal, realBaseIncome, year);
                maxRiskSeen = Math.max(maxRiskSeen, finalRisk);

                pw.printf("%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f,%.4f,%.2f,%.2f,%.2f,%.2f,%s\n",
                        calYear, Math.max(0, currentPortfolio), yearlySpendReal, (ssReal + currentAnnuityReal),
                        portDrawReal, drawPct, (1.0 - finalRisk) * 100.0, finalRisk * 100,
                        simInf * 100, simRet * 100, cutThreshold, cutAdj, raiseThreshold, raiseAdj, note);

                if (currentPortfolio <= 0) break;
            }
        }
        printDashboard(initialBaseIncome, realBaseIncome, currentPortfolio, totalSpendReal, cutCount, raiseCount, maxRiskSeen);
        System.out.println("V8 Simulation complete with Portfolio-At-Risk solving. File: " + OUTPUT_FILE);
    }

    private static void printDashboard(double start, double end, double port, double spent, int cuts, int raises, double risk) {
        System.out.println("\n========================================================");
        System.out.println("           MODERN GUARDRAILS V8 DASHBOARD               ");
        System.out.println("========================================================");
        System.out.printf("Initial Base Budget (Real):      $%,.2f\n", start);
        System.out.printf("Final Base Budget (Real):        $%,.2f\n", end);
        System.out.printf("Total Lifetime Spending (Real):  $%,.2f\n", spent);
        System.out.printf("Final Portfolio (Real):          $%,.2f\n", Math.max(0, port));
        System.out.println("--------------------------------------------------------");
        System.out.printf("Guardrail Events:                %d Cuts / %d Raises\n", cuts, raises);
        System.out.printf("Max Risk Level Encountered:      %.1f%%\n", risk * 100);
        System.out.println("Go-Go Multiplier Active:         YES (+12.5%)\n");
        System.out.println("========================================================\n");
    }

    private static double estimateRisk(double balance, double annuityReal, double baseInc, int startY) {
        if (balance <= 0 && startY < RETIREMENT_LENGTH) return 1.0;
        int failures = 0;
        for (int i = 0; i < MONTE_CARLO_RUNS; i++) {
            double sBal = balance;
            double sAnn = annuityReal;
            for (int t = startY; t <= RETIREMENT_LENGTH; t++) {
                int sYear = START_YEAR + t;
                double sInf = INFLATION_MEAN + (RANDOM.nextGaussian() * INFLATION_STD_DEV);
                double sRet = REAL_MEAN_RETURN + (RANDOM.nextGaussian() * REAL_STD_DEV);
                sAnn = updateAnnuityState(sAnn, sYear, sRet + sInf, sInf);
                double draw = (sYear <= 2026) ? 0 :
                        (baseInc * (t <= GO_GO_YEARS ? GO_GO_MULTIPLIER : 1.0)) - getSSForYear(t) - sAnn;
                if (sYear == 2027) draw *= 0.5;
                sBal = (sBal - Math.max(0, draw)) * (1 + sRet);
                if (sBal <= 0) { failures++; break; }
            }
            int ii=1;
        }
        return (double) failures / MONTE_CARLO_RUNS;
    }

    private static double solveForRealIncome(double bal, double ann, double target, int yr) {
        double low = 10000, high = 800000;
        for (int i = 0; i < 20; i++) {
            double mid = (low + high) / 2;
            if (estimateRisk(bal, ann, mid, yr) < target) low = mid;
            else high = mid;
        }
        return (low + high) / 2;
    }

    private static double solveForPortfolioAtRisk(double inc, double ann, double trigger, int yr) {
        double low = 0, high = INITIAL_PORTFOLIO * 10.0;
        for (int i = 0; i < 25; i++) {
            double mid = (low + high) / 2;
            if (estimateRisk(mid, ann, inc, yr) < trigger) high = mid;
            else low = mid;
        }
        return (low + high) / 2;
    }

    private static double updateAnnuityState(double current, int calYear, double nomRet, double inf) {
        if (calYear < 2028) return 0;
        if (calYear == 2028) return ANNUITY_NOMINAL_START * 0.75;
        double stepUp = (nomRet > 0.07) ? (nomRet - 0.07) : 0;
        return current * (1.0 + stepUp) / (1.0 + inf);
    }

    private static double getSSForYear(int yr) {
        int calYear = START_YEAR + yr;
        if (calYear <= 2026) return 0;
        double total = MAN_SS_ANNUAL;
        if (calYear == 2027) total += (WOMAN_SS_ANNUAL / 12);
        else if (calYear > 2027) total += WOMAN_SS_ANNUAL;
        return total;
    }
}