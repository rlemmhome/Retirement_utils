package com.hiflite.modernguardrailsmodel_gemini;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

public class IncomeLabProModel_v9
{

    // --- Core Parameters (Mutable for GUI) ---
    public static int MONTE_CARLO_RUNS = 10_000;
    public static double REAL_MEAN_RETURN = 0.037;
    public static double REAL_STD_DEV = 0.1089;
    public static double INFLATION_MEAN = 0.027;
    public static double INFLATION_STD_DEV = 0.012;

    public static double TARGET_RISK = 0.20;
    public static double LOWER_GUARDRAIL_RISK = 0.28;
    public static double UPPER_GUARDRAIL_RISK = 0.10;

    public static double INITIAL_PORTFOLIO = 1_500_000.0;
    public static int RETIREMENT_LENGTH = 30;
    public static int START_YEAR = 2026;

    // --- Income Sources & Go-Go Logic ---
    public static double MAN_SS_ANNUAL = 3367.0 * 12;
    public static double WOMAN_SS_ANNUAL = 3377.0 * 12;
    public static double ANNUITY_NOMINAL_START = 22599.0;
    public static double GO_GO_MULTIPLIER = 1.125;
    public static int GO_GO_YEARS = 10;

    private static final RandomGenerator RANDOM = RandomGenerator.getDefault();
    private static String RESULTS_FOLDER = "/home/bob/Documents/java_results/";

    public static void main(String[] args) {
        // Launch the GUI first
        SwingUtilities.invokeLater(IncomeLabProModel_v9::showGUI);
    }

    private static void showGUI() {
        JFrame frame = new JFrame("Modern Guardrails Retirement Model");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Use a 2-column grid for labels and inputs
        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Create Text Fields seeded with current constant values
        JTextField fieldMC = new JTextField(String.valueOf(MONTE_CARLO_RUNS));
        JTextField fieldRetMean = new JTextField(String.valueOf(REAL_MEAN_RETURN));
        JTextField fieldRetStd = new JTextField(String.valueOf(REAL_STD_DEV));
        JTextField fieldInfMean = new JTextField(String.valueOf(INFLATION_MEAN));
        JTextField fieldInfStd = new JTextField(String.valueOf(INFLATION_STD_DEV));
        JTextField fieldPortfolio = new JTextField(String.valueOf(INITIAL_PORTFOLIO));
        JTextField fieldRetLength = new JTextField(String.valueOf(RETIREMENT_LENGTH));
        JTextField fieldStartYear = new JTextField(String.valueOf(START_YEAR));
        JTextField fieldManSS = new JTextField(String.valueOf(MAN_SS_ANNUAL));
        JTextField fieldWomanSS = new JTextField(String.valueOf(WOMAN_SS_ANNUAL));
        JTextField fieldAnnuity = new JTextField(String.valueOf(ANNUITY_NOMINAL_START));
        JTextField fieldGoGoMult = new JTextField(String.valueOf(GO_GO_MULTIPLIER));
        JTextField fieldGoGoYrs = new JTextField(String.valueOf(GO_GO_YEARS));
        JTextField fieldTargetRisk = new JTextField(String.valueOf(TARGET_RISK));
        JTextField fieldResultsFolder = new JTextField(String.valueOf(RESULTS_FOLDER));

        // Add to Panel
        panel.add(new JLabel("Monte Carlo Runs:")); panel.add(fieldMC);
        panel.add(new JLabel("Portfolio Initial ($):")); panel.add(fieldPortfolio);
        panel.add(new JLabel("Retirement Length (Yrs):")); panel.add(fieldRetLength);
        panel.add(new JLabel("Start Year (YYYY):")); panel.add(fieldStartYear);
        panel.add(new JLabel("Real Mean Return (0.037):")); panel.add(fieldRetMean);
        panel.add(new JLabel("Real Return Std Dev:")); panel.add(fieldRetStd);
        panel.add(new JLabel("Inflation Mean (0.027):")); panel.add(fieldInfMean);
        panel.add(new JLabel("Inflation Std Dev:")); panel.add(fieldInfStd);
        panel.add(new JLabel("Man SS Annual ($):")); panel.add(fieldManSS);
        panel.add(new JLabel("Woman SS Annual ($):")); panel.add(fieldWomanSS);
        panel.add(new JLabel("Annuity Start ($):")); panel.add(fieldAnnuity);
        panel.add(new JLabel("Go-Go Multiplier (1.125):")); panel.add(fieldGoGoMult);
        panel.add(new JLabel("Go-Go Years:")); panel.add(fieldGoGoYrs);
        panel.add(new JLabel("Target Risk (0.20):")); panel.add(fieldTargetRisk);
        panel.add(new JLabel("Results Folder:")); panel.add(fieldResultsFolder);

        JButton runBtn = new JButton("Run Simulation");
        runBtn.addActionListener(e -> {
            try {
                // Update the class variables with user input
                MONTE_CARLO_RUNS = Integer.parseInt(fieldMC.getText());
                INITIAL_PORTFOLIO = Double.parseDouble(fieldPortfolio.getText());
                RETIREMENT_LENGTH = Integer.parseInt(fieldRetLength.getText());
                START_YEAR = Integer.parseInt(fieldStartYear.getText());
                REAL_MEAN_RETURN = Double.parseDouble(fieldRetMean.getText());
                REAL_STD_DEV = Double.parseDouble(fieldRetStd.getText());
                INFLATION_MEAN = Double.parseDouble(fieldInfMean.getText());
                INFLATION_STD_DEV = Double.parseDouble(fieldInfStd.getText());
                MAN_SS_ANNUAL = Double.parseDouble(fieldManSS.getText());
                WOMAN_SS_ANNUAL = Double.parseDouble(fieldWomanSS.getText());
                ANNUITY_NOMINAL_START = Double.parseDouble(fieldAnnuity.getText());
                GO_GO_MULTIPLIER = Double.parseDouble(fieldGoGoMult.getText());
                GO_GO_YEARS = Integer.parseInt(fieldGoGoYrs.getText());
                TARGET_RISK = Double.parseDouble(fieldTargetRisk.getText());
                RESULTS_FOLDER = fieldResultsFolder.getText();

                frame.dispose(); // Close the GUI
                new Thread(IncomeLabProModel_v9::runSimulation).start(); // Run in background thread
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame, "Please check that all inputs are valid numbers.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        frame.add(new JScrollPane(panel), BorderLayout.CENTER);
        frame.add(runBtn, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static void runSimulation() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String outputFile = RESULTS_FOLDER + "IncomeLabProResults_v9_"+TARGET_RISK+"_" + timestamp + ".csv";

        double currentPortfolio = INITIAL_PORTFOLIO;
        double currentAnnuityReal = 0;
        int cutCount = 0, raiseCount = 0;
        double totalSpendReal = 0;
        double maxRiskSeen = 0;

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(outputFile)))) {
            double realBaseIncome = solveForRealIncome(currentPortfolio, 0, TARGET_RISK, 0);
            double initialBaseIncome = realBaseIncome;

            pw.println("Year,Portfolio_End_Real,Total_Spend_Real,SS_Annuity_Real,Port_Withdrawal_Real,Draw_Pct," +
                    "Risk_Pct,Yearly_Inf_Pct,Yearly_Ret_Pct," +
                    "Cut_Threshold_Port,Cut_Adj_Amt,Raise_Threshold_Port,Raise_Adj_Amt,Note");

            for (int year = 0; year <= RETIREMENT_LENGTH; year++) {
                int calYear = START_YEAR + year;
                double startPortfolioOfYear = currentPortfolio;

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

                    double currentRisk = estimateRisk(currentPortfolio, currentAnnuityReal, realBaseIncome, year);
                    if (currentRisk >= LOWER_GUARDRAIL_RISK) {
                        realBaseIncome = solveForRealIncome(currentPortfolio, currentAnnuityReal, TARGET_RISK, year);
                        note += "/CUT"; cutCount++;
                    } else if (currentRisk <= UPPER_GUARDRAIL_RISK && year < RETIREMENT_LENGTH - 2) {
                        realBaseIncome = solveForRealIncome(currentPortfolio, currentAnnuityReal, TARGET_RISK, year);
                        note += "/RAISE"; raiseCount++;
                    }
                }

                double cutThreshold = solveForPortfolioAtRisk(realBaseIncome, currentAnnuityReal, LOWER_GUARDRAIL_RISK, year);
                double raiseThreshold = solveForPortfolioAtRisk(realBaseIncome, currentAnnuityReal, UPPER_GUARDRAIL_RISK, year);
                double cutAdj = (solveForRealIncome(cutThreshold, currentAnnuityReal, TARGET_RISK, year) - realBaseIncome) * multiplier;
                double raiseAdj = (solveForRealIncome(raiseThreshold, currentAnnuityReal, TARGET_RISK, year) - realBaseIncome) * multiplier;

                currentPortfolio = (currentPortfolio - portDrawReal) * (1 + simRet);
                totalSpendReal += yearlySpendReal;

                double drawPct = (startPortfolioOfYear > 0) ? (portDrawReal / startPortfolioOfYear) * 100.0 : 0;
                double finalRisk = estimateRisk(currentPortfolio, currentAnnuityReal, realBaseIncome, year);
                maxRiskSeen = Math.max(maxRiskSeen, finalRisk);

                pw.printf("%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f,%.4f,%.2f,%.2f,%.2f,%.2f,%s\n",
                        calYear, Math.max(0, currentPortfolio), yearlySpendReal, (ssReal + currentAnnuityReal),
                        portDrawReal, drawPct, finalRisk * 100,
                        simInf * 100, simRet * 100, cutThreshold, cutAdj, raiseThreshold, raiseAdj, note);

                if (currentPortfolio <= 0) break;
            }
            printDashboard(initialBaseIncome, realBaseIncome, currentPortfolio, totalSpendReal, cutCount, raiseCount, maxRiskSeen);
            System.out.println("Simulation complete. File: " + outputFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- Helper Methods ---

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

    private static void printDashboard(double start, double end, double port, double spent, int cuts, int raises, double risk) {
        System.out.println("\n========================================================");
        System.out.println("           MODERN GUARDRAILS V9 DASHBOARD               ");
        System.out.println("========================================================");
        System.out.printf("Initial Base Budget (Real):      $%,.2f\n", start);
        System.out.printf("Final Base Budget (Real):        $%,.2f\n", end);
        System.out.printf("Total Lifetime Spending (Real):  $%,.2f\n", spent);
        System.out.printf("Final Portfolio (Real):          $%,.2f\n", Math.max(0, port));
        System.out.println("--------------------------------------------------------");
        System.out.printf("Guardrail Events:                %d Cuts / %d Raises\n", cuts, raises);
        System.out.printf("Max Risk Level Encountered:      %.1f%%\n", risk * 100);
        System.out.println("========================================================\n");
    }
}