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

/**
 * Modern Guardrails Retirement Model v11
 * Incorporates:
 * - GUI inputs for 19 parameters.
 * - Legacy Target preservation logic.
 * - Portfolio Floor / Point of No Return tracking.
 * - Inflation-eroded nominal annuity logic.
 */
public class IncomeLabPro_v10 {

    // --- Core Parameters (Mutable via GUI) ---
    public static int MONTE_CARLO_RUNS = 10_000;
    public static double REAL_MEAN_RETURN = 0.037;
    public static double REAL_STD_DEV = 0.1089;
    public static double INFLATION_MEAN = 0.027;
    public static double INFLATION_STD_DEV = 0.012;

    public static double TARGET_RISK = 0.20;
    public static double LOWER_GUARDRAIL_RISK = 0.28;
    public static double UPPER_GUARDRAIL_RISK = 0.10;

    public static double LEGACY_TARGET = 0.0;
    public static double PORTFOLIO_FLOOR = 500_000.0;

    public static double INITIAL_PORTFOLIO = 1_500_000.0;
    public static int RETIREMENT_LENGTH = 30;
    public static int START_YEAR = 2026;

    public static double MAN_SS_ANNUAL = 3367.0 * 12;
    public static double WOMAN_SS_ANNUAL = 3377.0 * 12;
    public static double ANNUITY_NOMINAL_START = 22599.0;
    public static double GO_GO_MULTIPLIER = 1.125;
    public static int GO_GO_YEARS = 10;

    private static final RandomGenerator RANDOM = RandomGenerator.getDefault();
    private static String RESULTS_FOLDER = "/home/bob/Documents/java_results/";

    private static String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    public static String outputFileName = RESULTS_FOLDER + "IncomeLabProResults_v10_"+TARGET_RISK+"_" + ts + ".csv";

    public static void main(String[] args) {
        SwingUtilities.invokeLater(IncomeLabPro_v10::showGUI);
    }

    private static void showGUI() {
        JFrame frame = new JFrame("Modern Guardrails Retirement Model v10");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // GUI Input Fields
        JTextField fieldMC = new JTextField(String.valueOf(MONTE_CARLO_RUNS));
        JTextField fieldPortfolio = new JTextField(String.valueOf(INITIAL_PORTFOLIO));
        JTextField fieldRetLength = new JTextField(String.valueOf(RETIREMENT_LENGTH));
        JTextField fieldStartYear = new JTextField(String.valueOf(START_YEAR));
        JTextField fieldRetMean = new JTextField(String.valueOf(REAL_MEAN_RETURN));
        JTextField fieldRetStd = new JTextField(String.valueOf(REAL_STD_DEV));
        JTextField fieldInfMean = new JTextField(String.valueOf(INFLATION_MEAN));
        JTextField fieldInfStd = new JTextField(String.valueOf(INFLATION_STD_DEV));
        JTextField fieldManSS = new JTextField(String.valueOf(MAN_SS_ANNUAL));
        JTextField fieldWomanSS = new JTextField(String.valueOf(WOMAN_SS_ANNUAL));
        JTextField fieldAnnuity = new JTextField(String.valueOf(ANNUITY_NOMINAL_START));
        JTextField fieldGoGoMult = new JTextField(String.valueOf(GO_GO_MULTIPLIER));
        JTextField fieldGoGoYrs = new JTextField(String.valueOf(GO_GO_YEARS));
        JTextField fieldTargetRisk = new JTextField(String.valueOf(TARGET_RISK));
        JTextField fieldLowerRisk = new JTextField(String.valueOf(LOWER_GUARDRAIL_RISK));
        JTextField fieldUpperRisk = new JTextField(String.valueOf(UPPER_GUARDRAIL_RISK));
        JTextField fieldLegacy = new JTextField(String.valueOf(LEGACY_TARGET));
        JTextField fieldFloor = new JTextField(String.valueOf(PORTFOLIO_FLOOR));
        JTextField fieldResultsFolder = new JTextField(String.valueOf(RESULTS_FOLDER));

        panel.add(new JLabel("Monte Carlo Runs:")); panel.add(fieldMC);
        panel.add(new JLabel("Portfolio Initial ($):")); panel.add(fieldPortfolio);
        panel.add(new JLabel("Retirement Length (Yrs):")); panel.add(fieldRetLength);
        panel.add(new JLabel("Start Year (YYYY):")); panel.add(fieldStartYear);
        panel.add(new JLabel("Real Mean Return:")); panel.add(fieldRetMean);
        panel.add(new JLabel("Real Return Std Dev:")); panel.add(fieldRetStd);
        panel.add(new JLabel("Inflation Mean:")); panel.add(fieldInfMean);
        panel.add(new JLabel("Inflation Std Dev:")); panel.add(fieldInfStd);
        panel.add(new JLabel("Man SS Annual ($):")); panel.add(fieldManSS);
        panel.add(new JLabel("Woman SS Annual ($):")); panel.add(fieldWomanSS);
        panel.add(new JLabel("Annuity Start (Nominal $):")); panel.add(fieldAnnuity);
        panel.add(new JLabel("Go-Go Multiplier:")); panel.add(fieldGoGoMult);
        panel.add(new JLabel("Go-Go Years:")); panel.add(fieldGoGoYrs);
        panel.add(new JLabel("Target Risk:")); panel.add(fieldTargetRisk);
        panel.add(new JLabel("Lower Guardrail Risk (Cut):")); panel.add(fieldLowerRisk);
        panel.add(new JLabel("Upper Guardrail Risk (Raise):")); panel.add(fieldUpperRisk);
        panel.add(new JLabel("Legacy Target ($):")); panel.add(fieldLegacy);
        panel.add(new JLabel("Portfolio Floor Warning ($):")); panel.add(fieldFloor);
        panel.add(new JLabel("Results Folder:")); panel.add(fieldResultsFolder);

        JButton runBtn = new JButton("Run Simulation");
        runBtn.addActionListener(e -> {
            try {
                MONTE_CARLO_RUNS = Integer.parseInt(fieldMC.getText());
                REAL_MEAN_RETURN = Double.parseDouble(fieldRetMean.getText());
                REAL_STD_DEV = Double.parseDouble(fieldRetStd.getText());
                INFLATION_MEAN = Double.parseDouble(fieldInfMean.getText());
                INFLATION_STD_DEV = Double.parseDouble(fieldInfStd.getText());
                INITIAL_PORTFOLIO = Double.parseDouble(fieldPortfolio.getText());
                RETIREMENT_LENGTH = Integer.parseInt(fieldRetLength.getText());
                START_YEAR = Integer.parseInt(fieldStartYear.getText());
                MAN_SS_ANNUAL = Double.parseDouble(fieldManSS.getText());
                WOMAN_SS_ANNUAL = Double.parseDouble(fieldWomanSS.getText());
                ANNUITY_NOMINAL_START = Double.parseDouble(fieldAnnuity.getText());
                GO_GO_MULTIPLIER = Double.parseDouble(fieldGoGoMult.getText());
                GO_GO_YEARS = Integer.parseInt(fieldGoGoYrs.getText());
                TARGET_RISK = Double.parseDouble(fieldTargetRisk.getText());
                LOWER_GUARDRAIL_RISK = Double.parseDouble(fieldLowerRisk.getText());
                UPPER_GUARDRAIL_RISK = Double.parseDouble(fieldUpperRisk.getText());
                LEGACY_TARGET = Double.parseDouble(fieldLegacy.getText());
                PORTFOLIO_FLOOR = Double.parseDouble(fieldFloor.getText());
                RESULTS_FOLDER = fieldResultsFolder.getText();

                frame.dispose();
                new Thread(IncomeLabPro_v10::runSimulation).start();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame, "Invalid input.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        frame.add(new JScrollPane(panel), BorderLayout.CENTER);
        frame.add(runBtn, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static void runSimulation() {

        double currentPortfolio = INITIAL_PORTFOLIO;
        double currentAnnuityReal = 0;
        int cuts = 0, raises = 0;
        double totalSpent = 0, maxRisk = 0;
        double[] history = new double[RETIREMENT_LENGTH + 1];

        outputFileName = RESULTS_FOLDER + "IncomeLabProResults_v10_"+TARGET_RISK+"_" + ts + ".csv";
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(outputFileName)))) {
            double realBaseIncome = solveForRealIncome(currentPortfolio, 0, TARGET_RISK, 0);
            double initialBase = realBaseIncome;

            pw.println("Year,Portfolio_End_Real,Total_Spend_Real,SS_Annuity_Real,Port_Withdrawal_Real,Draw_Pct,Risk_Pct,Note");

            for (int year = 0; year <= RETIREMENT_LENGTH; year++) {
                int calYear = START_YEAR + year;
                double startPort = currentPortfolio;
                double simInf = INFLATION_MEAN + (RANDOM.nextGaussian() * INFLATION_STD_DEV);
                double simRet = REAL_MEAN_RETURN + (RANDOM.nextGaussian() * REAL_STD_DEV);

                double ssReal = getSSForYear(year);
                currentAnnuityReal = updateAnnuityState(currentAnnuityReal, calYear, simInf);

                double mult = (year <= GO_GO_YEARS) ? GO_GO_MULTIPLIER : 1.0;
                double spendReal = 0, drawReal = 0;
                String note = (year <= GO_GO_YEARS) ? "Go-Go" : "Slow-Go";

                if (calYear != 2026) {
                    spendReal = realBaseIncome * mult;
                    drawReal = Math.max(0, spendReal - ssReal - currentAnnuityReal);
                    if (calYear == 2027) drawReal *= 0.5;

                    double risk = estimateRisk(currentPortfolio, currentAnnuityReal, realBaseIncome, year);
                    if (risk >= LOWER_GUARDRAIL_RISK) {
                        realBaseIncome = solveForRealIncome(currentPortfolio, currentAnnuityReal, TARGET_RISK, year);
                        note += "/CUT"; cuts++;
                    } else if (risk <= UPPER_GUARDRAIL_RISK && year < RETIREMENT_LENGTH - 2) {
                        realBaseIncome = solveForRealIncome(currentPortfolio, currentAnnuityReal, TARGET_RISK, year);
                        note += "/RAISE"; raises++;
                    }
                } else { note = "Salary Phase"; }

                currentPortfolio = (currentPortfolio - drawReal) * (1 + simRet);
                history[year] = currentPortfolio;
                totalSpent += spendReal;

                double fRisk = estimateRisk(currentPortfolio, currentAnnuityReal, realBaseIncome, year);
                maxRisk = Math.max(maxRisk, fRisk);

                pw.printf("%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s\n",
                        calYear, Math.max(0, currentPortfolio), spendReal, (ssReal + currentAnnuityReal),
                        drawReal, (startPort > 0 ? (drawReal/startPort)*100 : 0), fRisk*100, note);

                if (currentPortfolio <= 0) break;
            }
            printDashboard(initialBase, realBaseIncome, currentPortfolio, totalSpent, cuts, raises, maxRisk, calculatePointOfNoReturn(history));
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static int calculatePointOfNoReturn(double[] history) {
        int lastAbove = -1;
        for (int i = 0; i < history.length; i++) {
            if (history[i] >= PORTFOLIO_FLOOR) lastAbove = i;
        }
        if (lastAbove == -1) return START_YEAR;
        if (lastAbove == history.length - 1) return -1;
        return START_YEAR + lastAbove + 1;
    }

    private static double estimateRisk(double bal, double annReal, double base, int startY) {
        if (bal < LEGACY_TARGET && startY < RETIREMENT_LENGTH) return 1.0;
        int fails = 0;
        for (int i = 0; i < MONTE_CARLO_RUNS; i++) {
            double sBal = bal, sAnn = annReal;
            for (int t = startY; t <= RETIREMENT_LENGTH; t++) {
                int sY = START_YEAR + t;
                double sInf = INFLATION_MEAN + (RANDOM.nextGaussian() * INFLATION_STD_DEV);
                double sRet = REAL_MEAN_RETURN + (RANDOM.nextGaussian() * REAL_STD_DEV);
                sAnn = updateAnnuityState(sAnn, sY, sInf);
                double draw = (sY <= 2026) ? 0 : (base * (t <= GO_GO_YEARS ? GO_GO_MULTIPLIER : 1.0)) - getSSForYear(t) - sAnn;
                if (sY == 2027) draw *= 0.5;
                sBal = (sBal - Math.max(0, draw)) * (1 + sRet);
                if (sBal < LEGACY_TARGET) { fails++; break; }
            }
        }
        return (double) fails / MONTE_CARLO_RUNS;
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

    private static double updateAnnuityState(double current, int calYear, double inf) {
        if (calYear < 2028) return 0;
        // Nominal value remains fixed, eroded by inflation in real terms.
        if (calYear == 2028) return ANNUITY_NOMINAL_START / (1.0 + inf);
        return current / (1.0 + inf);
    }

    private static double getSSForYear(int yr) {
        int calYear = START_YEAR + yr;
        if (calYear <= 2026) return 0;
        double total = MAN_SS_ANNUAL;
        if (calYear == 2027) total += (WOMAN_SS_ANNUAL / 12);
        else if (calYear > 2027) total += WOMAN_SS_ANNUAL;
        return total;
    }

    private static void printDashboard(double start, double end, double port, double spent, int c, int r, double risk, int pnr) {
        System.out.println("\n========================================================");
        System.out.println("           MODERN GUARDRAILS V10 DASHBOARD              ");
        System.out.println("========================================================");
        System.out.printf("Initial Base Budget (Real):      $%,.2f\n", start);
        System.out.printf("Final Base Budget (Real):        $%,.2f\n", end);
        System.out.printf("Total Lifetime Spending (Real):  $%,.2f\n", spent);
        System.out.printf("Final Portfolio (Real):          $%,.2f\n", Math.max(0, port));
        System.out.printf("Legacy Target Set:               $%,.2f\n", LEGACY_TARGET);
        System.out.println("--------------------------------------------------------");
        System.out.printf("Warning of Low Portfolio (<$%,.0f):    %s\n", PORTFOLIO_FLOOR, (pnr == -1 ? "N/A" : String.valueOf(pnr)));
        System.out.printf("Guardrail Events:                %d Cuts / %d Raises\n", c, r);
        System.out.printf("Max Risk Level Encountered:      %.1f%%\n", risk * 100);
        System.out.println("========================================================\n");
        System.out.println("");
        System.out.println("file is in: " + outputFileName);
    }
}