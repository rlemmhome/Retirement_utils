package com.hiflite.incomelabs_riskbased;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.List;

/**
 * IncomeLabStyle_PoSDriven.java
 *
 * Income Lab-Style Probability-of-Success-Driven Withdrawal Simulator.
 *
 * Compile:  javac IncomeLabStyle_PoSDriven.java
 * Run:      java IncomeLabStyle_PoSDriven
 * Requires Java 11+. No external dependencies.
 */
public class IncomeLabStyle_PoSDriven extends JFrame {

    private static final int BASE_YEAR      = 2026;
    private static final int RMD_START_AGE  = 75; // SECURE 2.0: born 1960 or later
    // MC parameters — driven by spinners at runtime
    private int mcSolvePaths = 800;
    private int mcFanPaths   = 400;
    private int binaryIters  = 22;

    // IRS Uniform Lifetime Table (age -> distribution period)
    private static final Map<Integer, Double> ULT = new HashMap<>();
    static {
        ULT.put(75, 24.6); ULT.put(76, 23.7); ULT.put(77, 22.9);
        ULT.put(78, 22.0); ULT.put(79, 21.1); ULT.put(80, 20.2);
        ULT.put(81, 19.4); ULT.put(82, 18.5); ULT.put(83, 17.7);
        ULT.put(84, 16.8); ULT.put(85, 16.0); ULT.put(86, 15.2);
        ULT.put(87, 14.4); ULT.put(88, 13.7); ULT.put(89, 12.9);
        ULT.put(90, 12.2); ULT.put(91, 11.5); ULT.put(92, 10.8);
        ULT.put(93, 10.1); ULT.put(94,  9.5); ULT.put(95,  8.9);
        ULT.put(96,  8.4); ULT.put(97,  7.8); ULT.put(98,  7.3);
        ULT.put(99,  6.8); ULT.put(100, 6.4);
    }

    // ── Column indices ───────────────────────────────────────────────────────
    // 0=ManAge 1=CalYr 2=PortBal 3=PoSWithdrawal 4=ActualWd 5=WdPct
    // 6=Guardrail(hidden)
    // 7=ManSS 8=WomanSS 9=Annuity 10=Guaranteed
    // 11=Living 12=Medical 13=Tax 14=TotalSpend 15=TotalIncome 16=SurplusGap
    // 17=InflFactor 18=ReturnUsed(hidden) 19=InflUsed(hidden)
    // 20=ManRMD 21=WomanRMD 22=CombinedRMD 23=RMDOverage(->Roth/MM)
    private static final int COL_GUARDRAIL   = 6;
    private static final int COL_SURPLUS     = 16;
    private static final int COL_MAN_RMD     = 20;
    private static final int COL_WOM_RMD     = 21;
    private static final int COL_CMB_RMD     = 22;
    private static final int COL_RMD_OVERAGE = 23;
    private static final int COL_WD          = 3;

    // ── Input spinners ───────────────────────────────────────────────────────
    private JSpinner spPortfolio, spHorizon, spTargetPoS;
    private JSpinner spWithdrawStartYear, spWithdrawStartMonth;
    private JSpinner spManBirthYear, spManBirthMonth;
    private JSpinner spWomanBirthYear, spWomanBirthMonth;
    private JSpinner spManSSAmount, spManSSStartYear, spManSSStartMonth;
    private JSpinner spWomanSSAmount, spWomanSSStartYear, spWomanSSStartMonth;
    private JSpinner spSSCola;
    private JSpinner spAnnuity, spAnnuityStartYear, spAnnuityStartMonth;
    private JSpinner spNomReturn, spStdDev, spInflation, spInflationStdDev;
    private JSpinner spLivingExp, spMedical, spMedInflation;
    private JSpinner spBaseTax, spTaxInflation;
    private JSpinner spGoGo;         // go-go years spending multiplier
    private JSpinner spGoGoDuration; // number of years from withdrawal start the multiplier applies
    private JSpinner spUpperGuardrail, spLowerGuardrail;
    // Account balance spinners
    private JSpinner spManTradIRA;      // man's traditional IRA (RMD at 75)
    private JSpinner spManRothIRA;      // man's Roth IRA (no RMD)
    private JSpinner spManTrad401K;     // man's traditional 401K (RMD at 75)
    private JSpinner spWomanRoth401K;   // woman's Roth 401K (no RMD)
    private JSpinner spWomanTradIRA;    // woman's traditional IRA (RMD at 75)
    private JSpinner spWomanTrad401K;   // woman's traditional 401K (RMD at 75)
    private JLabel   lblAccountTotal;   // live sum of all accounts
    // Randomization
    private JCheckBox chkRandomize;
    private long      runSeedOffset = 0L;
    // MC accuracy spinners
    private JSpinner  spMcSolvePaths, spBinaryIters, spMcFanPaths;

    // ── Output ───────────────────────────────────────────────────────────────
    private JLabel            lblYear1Answer, lblYear1Sub, lblYear1Detail;
    private JLabel            lblManAge, lblWomanAge;
    private JTable            tblResults;
    private DefaultTableModel tblModel;
    private JLabel            lblActualPoS, lblMedianFinal, lblYr10Wd, lblInitRate;
    private JToggleButton     tglDollars;
    private ChartPanel        chartPanel;
    private JComboBox<String> cmbChartType;
    private JTextArea         txaSummary;
    private JButton           btnRun;
    private JProgressBar      progressBar;

    private SimResults lastResults     = null;
    private boolean    showRealDollars = false;
    private volatile long simCount     = 0;  // running simulation counter
    private          long simTotal     = 0;  // grand total for this run
    private volatile java.util.function.LongConsumer simProgressCallback = null;

    private static final NumberFormat CURRENCY = NumberFormat.getCurrencyInstance(Locale.US);
    static { CURRENCY.setMaximumFractionDigits(0); }

    // ════════════════════════════════════════════════════════════════════════
    //  Constructor
    // ════════════════════════════════════════════════════════════════════════
    public IncomeLabStyle_PoSDriven() {
        super("Income Lab-Style Probability-of-Success-Driven Withdrawal Simulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(new Color(245, 245, 242));

        ToolTipManager.sharedInstance().setDismissDelay(15_000);
        ToolTipManager.sharedInstance().setInitialDelay(400);

        add(buildInputPanel(),  BorderLayout.WEST);
        add(buildOutputPanel(), BorderLayout.CENTER);
        add(buildStatusBar(),   BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(1300, 760));
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLocationRelativeTo(null);
        setVisible(true);
        // No auto-run — user adjusts inputs then clicks Run Simulation
    }

    // ════════════════════════════════════════════════════════════════════════
    //  INPUT PANEL
    // ════════════════════════════════════════════════════════════════════════
    private JPanel buildInputPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(new Color(240, 240, 237));
        outer.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(200, 198, 193)));
        outer.setPreferredSize(new Dimension(420, 0));
        outer.setMinimumSize(new Dimension(380, 0));

        // ScrollablePanel forces inner panel to match viewport width — fixes hidden spinner values
        JPanel inner = new ScrollablePanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBackground(new Color(240, 240, 237));
        inner.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Portfolio & Simulation
        spPortfolio          = spinI(1_500_000, 10_000, 20_000_000, 10_000, "#,###");
        spHorizon            = spinI(30, 10, 50, 1, "#");
        spTargetPoS          = spinI(80, 60, 99, 1, "#");
        spWithdrawStartYear  = spinI(2027, 2025, 2040, 1, "#");
        spWithdrawStartMonth = spinI(1, 1, 12, 1, "#");

        chkRandomize = new JCheckBox("Re-randomize each run (uncheck for reproducible results)");
        chkRandomize.setFont(new Font("SansSerif", Font.PLAIN, 12));
        chkRandomize.setForeground(new Color(75, 75, 75));
        chkRandomize.setOpaque(false);
        chkRandomize.setAlignmentX(LEFT_ALIGNMENT);
        chkRandomize.setToolTipText("<html><b>Re-randomize each run</b><br>"
                + "Checked: each run uses a different random seed — natural MC variance.<br>"
                + "Unchecked: same seed every run — identical results for same inputs.<br>"
                + "Use unchecked for scenario comparison; checked to explore uncertainty.</html>");

        spMcSolvePaths = spinI(800, 50, 5000, 50, "#,###");
        spMcSolvePaths.setToolTipText("<html><b>Monte Carlo solve paths</b><br>"
                + "Number of simulation paths used in each binary-search iteration<br>"
                + "to find the 80% PoS withdrawal amount.<br><br>"
                + "<b>Default: 800</b> — high accuracy.<br>"
                + "200 = ~4x faster, slightly noisier withdrawal estimates (~$500 variance).<br>"
                + "100 = ~8x faster, moderate noise (~$1,000 variance).<br>"
                + "This is the biggest driver of total runtime.</html>");

        spBinaryIters = spinI(22, 8, 30, 1, "#");
        spBinaryIters.setToolTipText("<html><b>Binary search iterations</b><br>"
                + "Number of iterations to narrow down the withdrawal amount<br>"
                + "that achieves the target probability of success.<br><br>"
                + "<b>Default: 22</b> — converges to within ~$1.<br>"
                + "16 = ~1.4x faster, converges to within ~$50.<br>"
                + "12 = ~1.8x faster, converges to within ~$500.<br>"
                + "Smallest impact on runtime of the three parameters.</html>");

        spMcFanPaths = spinI(400, 20, 2000, 20, "#,###");
        spMcFanPaths.setToolTipText("<html><b>Fan chart paths</b><br>"
                + "Number of full simulation paths used to draw the fan chart<br>"
                + "and compute the actual PoS metric at the top.<br><br>"
                + "<b>Default: 400</b> — smooth fan chart, stable PoS reading.<br>"
                + "100 = ~4x faster, fan chart is noisier but percentile lines are still meaningful.<br>"
                + "50 = ~8x faster, fan chart is rough but usable for quick checks.<br>"
                + "Each fan path re-solves withdrawal every year — very expensive.</html>");

        inner.add(card("Portfolio & Simulation", new Object[]{
                "Starting portfolio ($)",        spPortfolio,
                "Retirement horizon (years)",    spHorizon,
                "Target success rate (%)",       spTargetPoS,
                "Withdrawal start year",         spWithdrawStartYear,
                "Withdrawal start month (1-12)", spWithdrawStartMonth,
                null, chkRandomize,
                "MC solve paths (accuracy vs speed)", spMcSolvePaths,
                "Binary search iterations",           spBinaryIters,
                "Fan chart paths (chart quality)",    spMcFanPaths,
        }));

        // Wire complexity tooltip to update whenever any MC parameter or horizon changes
        ChangeListener complexityWatcher = e -> updateRunTooltip();
        for (JSpinner s : new JSpinner[]{spMcSolvePaths, spBinaryIters, spMcFanPaths, spHorizon})
            s.addChangeListener(complexityWatcher);

        // People
        spManBirthYear    = spinI(1961, 1920, 2000, 1, "#");
        spManBirthMonth   = spinI(9,    1,    12,   1, "#");
        spWomanBirthYear  = spinI(1962, 1920, 2000, 1, "#");
        spWomanBirthMonth = spinI(12,   1,    12,   1, "#");

        JPanel ageDisplay = new JPanel(new GridLayout(1, 2, 8, 0));
        ageDisplay.setOpaque(false);
        ageDisplay.setAlignmentX(LEFT_ALIGNMENT);
        ageDisplay.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        lblManAge   = new JLabel("Man age: —");
        lblWomanAge = new JLabel("Woman age: —");
        for (JLabel l : new JLabel[]{lblManAge, lblWomanAge}) {
            l.setFont(new Font("SansSerif", Font.BOLD, 11));
            l.setForeground(new Color(24, 95, 165));
        }
        ageDisplay.add(lblManAge); ageDisplay.add(lblWomanAge);

        inner.add(card("People — Birth Dates", new Object[]{
                "Man — birth year",           spManBirthYear,
                "Man — birth month (1-12)",   spManBirthMonth,
                "Woman — birth year",         spWomanBirthYear,
                "Woman — birth month (1-12)", spWomanBirthMonth,
                null, ageDisplay,
        }));

        ChangeListener ageWatcher = e -> updateAgeLabels();
        for (JSpinner s : new JSpinner[]{
                spManBirthYear, spManBirthMonth, spWomanBirthYear, spWomanBirthMonth})
            s.addChangeListener(ageWatcher);
        updateAgeLabels();

        // Social Security
        spManSSAmount       = spinI(40_400, 0,    200_000, 100, "#,###");
        spManSSStartYear    = spinI(2027,   2024, 2045,    1,   "#");
        spManSSStartMonth   = spinI(1,      1,    12,      1,   "#");
        spWomanSSAmount     = spinI(40_520, 0,    200_000, 100, "#,###");
        spWomanSSStartYear  = spinI(2027,   2024, 2045,    1,   "#");
        spWomanSSStartMonth = spinI(12,     1,    12,      1,   "#");
        spSSCola            = spinD(2.3,    0.0,  6.0,     0.1, "0.0#");
        inner.add(card("Social Security", new Object[]{
                "Man SS amount ($/yr)",        spManSSAmount,
                "Man SS start year",           spManSSStartYear,
                "Man SS start month (1-12)",   spManSSStartMonth,
                "Woman SS amount ($/yr)",      spWomanSSAmount,
                "Woman SS start year",         spWomanSSStartYear,
                "Woman SS start month (1-12)", spWomanSSStartMonth,
                "SS COLA rate (%/yr)",         spSSCola,
        }));

        // Annuity
        spAnnuity          = spinI(22_599, 0, 500_000, 500, "#,###");
        spAnnuityStartYear = spinI(2028, 2024, 2050, 1, "#");
        spAnnuityStartMonth= spinI(4, 1, 12, 1, "#");
        inner.add(card("Annuity (Non-COLA)", new Object[]{
                "Annuity amount ($/yr)",         spAnnuity,
                "Annuity start year",            spAnnuityStartYear,
                "Annuity start month (1-12)",    spAnnuityStartMonth,
        }));

        // RMD assumptions
        // Account balances
        spManTradIRA    = spinI(900_000, 0, 10_000_000, 10_000, "#,###");
        spManRothIRA    = spinI(10_000,  0, 10_000_000, 10_000, "#,###");
        spManTrad401K   = spinI(0,       0, 10_000_000, 10_000, "#,###");
        spWomanRoth401K = spinI(33_000,  0, 10_000_000, 10_000, "#,###");
        spWomanTradIRA  = spinI(286_000, 0, 10_000_000, 10_000, "#,###");
        spWomanTrad401K = spinI(320_000, 0, 10_000_000, 10_000, "#,###");

        lblAccountTotal = new JLabel("Account total: $1,549,000");
        lblAccountTotal.setFont(new Font("SansSerif", Font.BOLD, 11));
        lblAccountTotal.setForeground(new Color(24, 95, 165));
        lblAccountTotal.setAlignmentX(LEFT_ALIGNMENT);

        ChangeListener acctWatcher = e -> updateAccountTotal();
        for (JSpinner s : new JSpinner[]{
                spManTradIRA, spManRothIRA, spManTrad401K,
                spWomanRoth401K, spWomanTradIRA, spWomanTrad401K})
            s.addChangeListener(acctWatcher);
        updateAccountTotal();

        inner.add(card("Account Balances (SECURE 2.0 RMD — age 75)", new Object[]{
                "Man — traditional IRA ($)  [RMD age 75]",   spManTradIRA,
                "Man — Roth IRA ($)  [no RMD]",              spManRothIRA,
                "Man — traditional 401K ($)  [RMD age 75]",  spManTrad401K,
                "Woman — Roth 401K ($)  [no RMD]",           spWomanRoth401K,
                "Woman — traditional IRA ($)  [RMD age 75]", spWomanTradIRA,
                "Woman — traditional 401K ($)  [RMD age 75]",spWomanTrad401K,
                null, lblAccountTotal,
        }));

        // Market
        spNomReturn       = spinD(6.70,  1.0,  15.0, 0.1,  "0.00");
        spStdDev          = spinD(10.79, 2.0,  30.0, 0.1,  "0.00");
        spInflation       = spinD(3.79,  0.5,  10.0, 0.01, "0.00");
        spInflationStdDev = spinD(2.73,  0.0,  8.0,  0.01, "0.00");
        inner.add(card("Market Assumptions (1961-2024 historical defaults)", new Object[]{
                "Expected nominal return (%)", spNomReturn,
                "Return std deviation (%)",    spStdDev,
                "Mean inflation (%/yr)",       spInflation,
                "Inflation std deviation (%)", spInflationStdDev,
        }));

        // Spending
        spLivingExp    = spinI(105_000, 10_000, 1_000_000, 1_000, "#,###");
        spMedical      = spinI(12_000,  0,      200_000,   500,   "#,###");
        spMedInflation = spinD(4.5,     1.0,    10.0,      0.1,   "0.0#");
        spBaseTax      = spinI(15_000,  0,      100_000,   500,   "#,###");
        spTaxInflation = spinD(3.79,    0.5,    10.0,      0.1,   "0.0#");
        spGoGo         = spinD(1.0,  1.0, 2.0, 0.005, "0.000");
        spGoGoDuration = spinI(10,   1,   30,  1,     "#");
        spGoGo.setToolTipText("<html><b>Go-go years multiplier</b><br>"
                + "Applies to portfolio withdrawals for the number of years set in<br>"
                + "'Go-go duration'. Early retirement typically features higher spending<br>"
                + "on travel and experiences while health and energy are at their peak.<br><br>"
                + "<b>Rational default: 1.125</b> — based on research by Michael Kitces<br>"
                + "and others showing ~10-15% higher real spending in early retirement.<br><br>"
                + "A value of 1.0 means no adjustment (flat spending throughout).<br>"
                + "The multiplier is applied inside the Monte Carlo solver so the<br>"
                + "80% PoS target is genuinely maintained during go-go years.</html>");
        spGoGoDuration.setToolTipText("<html><b>Go-go years duration</b><br>"
                + "Number of years from the withdrawal start date that the go-go<br>"
                + "spending multiplier applies.<br><br>"
                + "<b>Default: 10 years</b> — roughly covers ages 65-75 for a typical<br>"
                + "early retiree, but works generically regardless of age or who is<br>"
                + "running the simulation.<br><br>"
                + "Set to 0 to disable the go-go multiplier entirely.</html>");
        inner.add(card("Annual Spending (2027 base $)", new Object[]{
                "Living expenses ($/yr)",                spLivingExp,
                "Medical ($/yr)",                        spMedical,
                "Medical inflation (%/yr)",              spMedInflation,
                "Base tax — yr 1 ($/yr)",                spBaseTax,
                "Tax inflation (%/yr)",                  spTaxInflation,
                "Go-go years multiplier",                spGoGo,
                "Go-go years duration (from wd start)",  spGoGoDuration,
        }));

        // Guardrails
        spUpperGuardrail = spinD(20.0, 5.0, 60.0, 1.0, "0.0#");
        spLowerGuardrail = spinD(25.0, 5.0, 60.0, 1.0, "0.0#");
        inner.add(card("Guardrail Alerts (display only)", new Object[]{
                "Upper guardrail — raise alert (%)", spUpperGuardrail,
                "Lower guardrail — cut alert (%)",   spLowerGuardrail,
        }));

        // Run button
        btnRun = new JButton("▶  Run Simulation");
        btnRun.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnRun.setBackground(new Color(55, 138, 221));
        btnRun.setForeground(Color.WHITE);
        btnRun.setFocusPainted(false);
        btnRun.setBorder(BorderFactory.createEmptyBorder(10, 28, 10, 28));
        btnRun.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnRun.setOpaque(true);
        btnRun.addActionListener(e -> runSimulation());
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        btnPanel.setBackground(new Color(240, 240, 237));
        btnPanel.add(btnRun);
        inner.add(btnPanel);

        JScrollPane scroll = new JScrollPane(inner,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(240, 240, 237));
        outer.add(scroll, BorderLayout.CENTER);

        // Set initial Run button tooltip (will update whenever spinners change)
        SwingUtilities.invokeLater(this::updateRunTooltip);
        return outer;
    }

    private void updateAgeLabels() {
        try {
            lblManAge.setText("Man age: " + computeAge(iv(spManBirthYear), iv(spManBirthMonth)));
            lblWomanAge.setText("Woman age: " + computeAge(iv(spWomanBirthYear), iv(spWomanBirthMonth)));
        } catch (Exception ignored) {}
    }

    private void updateAccountTotal() {
        try {
            long total = (long)iv(spManTradIRA) + iv(spManRothIRA) + iv(spManTrad401K)
                    + iv(spWomanRoth401K) + iv(spWomanTradIRA) + iv(spWomanTrad401K);
            lblAccountTotal.setText("Account total: " + CURRENCY.format(total));
            spPortfolio.setValue((int) Math.min(total, 20_000_000));
        } catch (Exception ignored) {}
    }

    private String updateRunTooltip() {
        try {
            int horizon    = iv(spHorizon);
            int solvePaths = iv(spMcSolvePaths);
            int fanPaths   = iv(spMcFanPaths);
            int binIters   = iv(spBinaryIters);

            long medianSims = (long) horizon * binIters * solvePaths * (horizon + 1) / 2;
            long fanSims    = (long) fanPaths * horizon * binIters * solvePaths;
            long totalSims  = medianSims + fanSims;
            long totalM     = totalSims / 1_000_000;

            String complexity = String.format(
                    "<html><b>Estimated computation at current settings:</b><br>"
                            + "Fan paths: %,d paths × %d yrs × %d iters × %,d solve paths"
                            + " = <b>%,dM simulations</b><br>"
                            + "Median path: %d yrs × %d iters × %,d solve paths × avg %d remaining yrs"
                            + " = <b>%,dM simulations</b><br>"
                            + "Grand total: <b>~%,dM simulations performed</b><br><br>"
                            + "Tip: reduce MC spinners above for faster runs.<br>"
                            + "200 solve paths + 100 fan paths ≈ 16× faster with modest accuracy loss.</html>",
                    fanPaths, horizon, binIters, solvePaths, fanSims / 1_000_000,
                    horizon, binIters, solvePaths, (horizon + 1) / 2, medianSims / 1_000_000,
                    totalM);

            String statusStr = String.format(
                    "Running — 0 / ~%,dM simulations performed…", totalM);

            if (btnRun != null) btnRun.setToolTipText(complexity);
            return statusStr;
        } catch (Exception e) {
            return "Running Monte Carlo…";
        }
    }

    private int computeAge(int birthYear, int birthMonth) {
        LocalDate today = LocalDate.now();
        LocalDate birth = LocalDate.of(
                Math.max(1900, Math.min(2100, birthYear)),
                Math.max(1,    Math.min(12,   birthMonth)), 1);
        return (int) java.time.temporal.ChronoUnit.YEARS.between(birth, today);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  OUTPUT PANEL
    // ════════════════════════════════════════════════════════════════════════
    private JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(new Color(245, 245, 242));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Answer box
        JPanel answerBox = new JPanel(new BorderLayout(4, 4));
        answerBox.setBackground(new Color(232, 240, 250));
        answerBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(181, 212, 244), 1),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));

        lblYear1Answer = new JLabel("—");
        lblYear1Answer.setFont(new Font("SansSerif", Font.BOLD, 30));
        lblYear1Answer.setForeground(new Color(24, 95, 165));
        lblYear1Sub    = new JLabel(" ");
        lblYear1Sub.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblYear1Sub.setForeground(new Color(90, 90, 90));
        lblYear1Detail = new JLabel(" ");
        lblYear1Detail.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblYear1Detail.setForeground(new Color(110, 110, 110));

        tglDollars = new JToggleButton("Showing: Future $ (nominal)");
        tglDollars.setFont(new Font("SansSerif", Font.PLAIN, 11));
        tglDollars.setFocusPainted(false);
        tglDollars.addActionListener(e -> {
            showRealDollars = tglDollars.isSelected();
            tglDollars.setText(showRealDollars
                    ? "Showing: Today's $ (2026 real)" : "Showing: Future $ (nominal)");
            if (lastResults != null) updateUI(lastResults);
        });

        JPanel aNorth = new JPanel(new BorderLayout()); aNorth.setOpaque(false);
        JLabel aTitle = new JLabel("Year 1 portfolio withdrawal at target PoS");
        aTitle.setFont(new Font("SansSerif", Font.PLAIN, 11));
        aTitle.setForeground(new Color(100, 100, 100));
        aNorth.add(aTitle, BorderLayout.WEST);
        aNorth.add(tglDollars, BorderLayout.EAST);

        JPanel aMid = new JPanel(new BorderLayout(2, 2)); aMid.setOpaque(false);
        aMid.add(lblYear1Answer, BorderLayout.CENTER);
        aMid.add(lblYear1Sub,    BorderLayout.SOUTH);

        answerBox.add(aNorth,         BorderLayout.NORTH);
        answerBox.add(aMid,           BorderLayout.CENTER);
        answerBox.add(lblYear1Detail, BorderLayout.SOUTH);

        // Metrics row
        JPanel metricsRow = new JPanel(new GridLayout(1, 4, 8, 0));
        metricsRow.setBackground(new Color(245, 245, 242));
        lblActualPoS   = mkMetricLabel();
        lblMedianFinal = mkMetricLabel();
        lblYr10Wd      = mkMetricLabel();
        lblInitRate    = mkMetricLabel();
        metricsRow.add(wrapMetric(lblActualPoS,   "Actual PoS",               "from fan simulation"));
        metricsRow.add(wrapMetric(lblMedianFinal, "Median final balance",      "end of horizon"));
        metricsRow.add(wrapMetric(lblYr10Wd,      "Yr 10 withdrawal (median)", "see dollar toggle"));
        metricsRow.add(wrapMetric(lblInitRate,    "Initial withdrawal rate",   "% of portfolio"));

        JPanel topSection = new JPanel(new BorderLayout(0, 8));
        topSection.setBackground(new Color(245, 245, 242));
        topSection.add(answerBox,  BorderLayout.NORTH);
        topSection.add(metricsRow, BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.PLAIN, 12));
        tabs.addTab("Year-by-year table", buildTablePanel());
        tabs.addTab("Simulation chart",   buildChartPanel());
        tabs.addTab("Summary",            buildSummaryPanel());

        panel.add(topSection, BorderLayout.NORTH);
        panel.add(tabs,       BorderLayout.CENTER);
        return panel;
    }

    // ── Table ────────────────────────────────────────────────────────────────
    private JScrollPane buildTablePanel() {
        String[] cols = {
                "Man age", "Cal yr", "Portfolio bal",                       // 0 1 2
                "80% PoS withdrawal", "Actual wd", "Wd %",                 // 3 4 5
                "Guardrail",                                                 // 6 hidden
                "Man SS", "Woman SS", "Annuity", "Guaranteed",              // 7 8 9 10
                "Living", "Medical", "Tax (est)",                           // 11 12 13
                "Total spend", "Total income", "Surplus/gap",               // 14 15 16
                "Infl factor", "Return used", "Infl used",                  // 17 18h 19h
                "Man RMD", "Woman RMD", "Combined RMD", "→ Roth/MM",        // 20 21 22 23
                "Bal Δ"                                                       // 24 balance change
        };
        tblModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        tblResults = new JTable(tblModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int col = columnAtPoint(e.getPoint());
                int row = rowAtPoint(e.getPoint());
                if (row < 0) return null;
                // Wd% col (5) — show guardrail status
                if (col == 5) {
                    Object gv = tblModel.getValueAt(row, COL_GUARDRAIL);
                    String g  = gv == null ? "" : gv.toString();
                    if (g.contains("▲"))
                        return "<html><b>▲ Raise alert</b><br>"
                                + "Re-solved withdrawal rose above upper guardrail threshold.<br>"
                                + "Portfolio has grown — sustainable to spend more.<br>"
                                + "No action required; 80% PoS re-solve already reflects this.</html>";
                    if (g.contains("▼"))
                        return "<html><b>▼ Cut alert</b><br>"
                                + "Re-solved withdrawal fell below lower guardrail threshold.<br>"
                                + "Consider reducing discretionary spending this year.</html>";
                    return "<html><b>Within guardrail range</b><br>"
                            + "Withdrawal is within the guardrail bands. No adjustment indicated.</html>";
                }
                // Actual wd col (4) tooltip
                if (col == 4 && row < lastResults.medianRows.size()) {
                    boolean goGoRow = lastResults.medianRows.get(row).goGoActive;
                    double mult = goGoRow ? lastResults.inp.goGoMultiplier : 1.0;
                    return "<html><b>Actual withdrawal (spending)</b><br>"
                            + "= 80% PoS withdrawal × go-go multiplier (" + String.format("%.3f", mult) + ").<br>"
                            + "This is the amount spent and deducted from the portfolio.<br>"
                            + "Any RMD overage above this goes to Roth/MM — not spent.</html>";
                }
                // → Roth/MM col (9) tooltip
                if (col == COL_RMD_OVERAGE && row < lastResults.medianRows.size()) {
                    int overage = lastResults.medianRows.get(row).rmdOverage;
                    if (overage <= 0) return null;
                    return "<html><b>RMD overage → Roth/MM</b><br>"
                            + "The combined RMD (" + CURRENCY.format(lastResults.medianRows.get(row).combRmd) + ")<br>"
                            + "exceeds the planned spending withdrawal.<br>"
                            + "This overage (" + CURRENCY.format(overage) + ") must be taken from your<br>"
                            + "traditional accounts but is redirected to a Roth IRA or<br>"
                            + "money market — NOT spent. Your net worth is preserved.<br>"
                            + "This is an involuntary Roth conversion opportunity.</html>";
                }
                // RMD columns tooltip
                if (col == COL_MAN_RMD || col == COL_WOM_RMD || col == COL_CMB_RMD) {
                    Object rmdVal = tblModel.getValueAt(row, col);
                    if (rmdVal == null || "—".equals(rmdVal.toString())) return null;
                    boolean exceeds = col == COL_CMB_RMD
                            ? isRmdExceedsWd(row)
                            : isIndivRmdExceedsWd(row, col);
                    return "<html><b>RMD (IRS Uniform Lifetime Table)</b><br>"
                            + "SECURE 2.0: begins at age 75 (born after 1960).<br>"
                            + "Roth accounts (man $" + CURRENCY.format(iv(spManRothIRA)).replace("$","")
                            + " + woman $" + CURRENCY.format(iv(spWomanRoth401K)).replace("$","")
                            + ") excluded — no RMD applies.<br>"
                            + (exceeds
                            ? "<b style='color:orange'>⚠ This RMD exceeds the 80% PoS withdrawal amount.</b><br>"
                              + "You may be required to withdraw more than the simulation recommends."
                            : "RMD is within the 80% PoS withdrawal amount.")
                            + "</html>";
                }
                // Bal Δ col (24) — detailed breakdown tooltip
                if (col == 24 && row < lastResults.medianRows.size()) {
                    MedianRow mr = lastResults.medianRows.get(row);
                    double d = showRealDollars ? mr.inflFactor : 1.0;
                    int balChange = mr.balDelta;
                    int growth    = mr.investmentGrowth;
                    int spend     = mr.wdActual;
                    double pctChg = mr.balance > 0
                            ? balChange / (double) mr.balance * 100.0 : 0.0;
                    StringBuilder sb = new StringBuilder("<html><b>Portfolio change: ");
                    sb.append(balChange >= 0 ? "+" : "")
                            .append(CURRENCY.format((long)(balChange / d)))
                            .append(String.format(" (%+.1f%%)", pctChg))
                            .append("</b><br>");
                    sb.append(String.format("&nbsp;&nbsp;Market growth (%.2f%%):&nbsp;&nbsp;&nbsp;+%s<br>",
                            lastResults.inp.nomReturn * 100,
                            CURRENCY.format((long)(growth / d))));
                    sb.append(String.format("&nbsp;&nbsp;Withdrawal (spending):&nbsp;&nbsp;&nbsp;&minus;%s<br>",
                            CURRENCY.format((long)(spend / d))));
                    sb.append(String.format("&nbsp;&nbsp;<b>Net change:</b>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;%s%s",
                            balChange >= 0 ? "+" : "",
                            CURRENCY.format((long)(Math.abs(balChange) / d))));
                    if (mr.rmdOverage > 0) {
                        sb.append("<br><br><i>Note: Combined RMD of ")
                                .append(CURRENCY.format((long)(mr.combRmd / d)))
                                .append(" exceeds planned withdrawal of ")
                                .append(CURRENCY.format((long)(spend / d)))
                                .append(".<br>")
                                .append("The overage of ")
                                .append(CURRENCY.format((long)(mr.rmdOverage / d)))
                                .append(" is withdrawn from traditional IRA/401K accounts<br>")
                                .append("(as required by law) and assumed to be reinvested<br>")
                                .append("in a Roth IRA or Money Market account.<br>")
                                .append("This does <b>not</b> affect the portfolio balance shown above.</i>");
                    }
                    sb.append("</html>");
                    return sb.toString();
                }
                return super.getToolTipText(e);
            }
        };

        tblResults.setFont(new Font("SansSerif", Font.PLAIN, 11));
        tblResults.setRowHeight(20);
        tblResults.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 10));
        tblResults.setGridColor(new Color(220, 220, 215));
        tblResults.setShowGrid(true);
        tblResults.setSelectionBackground(new Color(210, 230, 250));
        tblResults.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // col widths: hidden cols get 0
        int[] w = {
                55, 55, 105, 115, 105, 68,         // 0-5
                0,                                   // 6 guardrail hidden
                75, 80, 72, 90,                      // 7-10 SS + guaranteed
                72, 72, 78,                          // 11-13 living/medical/tax
                88, 95, 85,                          // 14-16 totals + surplus
                72, 0, 0,                            // 17, 18h, 19h
                80, 85, 90, 85,                      // 20-23 RMDs
                90                                   // 24 Bal Δ
        };
        for (int i = 0; i < w.length && i < tblResults.getColumnCount(); i++) {
            TableColumn tc = tblResults.getColumnModel().getColumn(i);
            tc.setPreferredWidth(w[i]);
            if (w[i] == 0) { tc.setMinWidth(0); tc.setMaxWidth(0); }
        }

        // Header tooltips
        JTableHeader header = tblResults.getTableHeader();
        header.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int col = header.columnAtPoint(e.getPoint());
                switch (col) {
                    case 4 -> header.setToolTipText(
                            "<html><b>Actual wd — spending withdrawal</b><br>"
                                    + "= 80% PoS withdrawal × go-go multiplier (if applicable).<br>"
                                    + "This is what is spent and deducted from the portfolio.<br>"
                                    + "RMD overage above this goes to Roth/MM, not spent.</html>");
                    case 5 -> header.setToolTipText(
                            "<html><b>Wd % — effective withdrawal rate</b><br>"
                                    + "= actual withdrawal ÷ portfolio balance.<br>"
                                    + "Hover cells for guardrail status.</html>");
                    case 20 -> header.setToolTipText(
                            "<html><b>Man RMD</b><br>"
                                    + "Required Minimum Distribution from man's traditional IRA.<br>"
                                    + "Begins age 75 (SECURE 2.0, born after 1960).</html>");
                    case 21 -> header.setToolTipText(
                            "<html><b>Woman RMD</b><br>"
                                    + "Required Minimum Distribution from woman's traditional IRA + 401K.<br>"
                                    + "Begins age 75 (SECURE 2.0, born after 1960).</html>");
                    case 22 -> header.setToolTipText(
                            "<html><b>Combined RMD</b><br>"
                                    + "Sum of man + woman RMDs.<br>"
                                    + "Orange = RMD exceeds planned withdrawal; overage → Roth/MM.</html>");
                    case 23 -> header.setToolTipText(
                            "<html><b>→ Roth/MM — RMD overage redirected</b><br>"
                                    + "= max(0, Combined RMD − Actual wd).<br>"
                                    + "When RMD exceeds the planned spending withdrawal, the excess<br>"
                                    + "must be taken from the traditional account but is redirected<br>"
                                    + "to a Roth IRA or money market — NOT spent. Net worth preserved.<br>"
                                    + "This is effectively an involuntary Roth conversion opportunity.</html>");
                    case 24 -> header.setToolTipText(
                            "<html><b>Bal Δ — portfolio balance change</b><br>"
                                    + "= end-of-year balance − start-of-year balance.<br>"
                                    + "= market growth − spending withdrawal.<br>"
                                    + "Green = portfolio grew · Red = portfolio shrank.<br>"
                                    + "Hover individual cells for full year breakdown.</html>");
                    default -> header.setToolTipText(null);
                }
            }
        });

        // Cell renderer
        tblResults.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private final Color AMBER_BG  = new Color(255, 220, 100);
            private final Color AMBER_FG  = new Color(130, 80, 0);
            private final Color GOGO_BG   = new Color(232, 248, 240);
            private final Color GOGO_WD_BG= new Color(180, 230, 205);

            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel) {
                    // Check if this is a go-go year row
                    boolean goGo = row < lastResults.medianRows.size()
                            && lastResults.medianRows.get(row).goGoActive;

                    Color defaultBg = goGo ? GOGO_BG
                            : (row % 2 == 0 ? Color.WHITE : new Color(248, 248, 245));
                    c.setBackground(defaultBg);
                    c.setForeground(Color.BLACK);
                    String s = v == null ? "" : v.toString();

                    if (col == 3 && goGo) {
                        c.setBackground(GOGO_WD_BG); c.setForeground(new Color(0,90,50));
                    } else if (col == 4 && goGo) {
                        c.setBackground(GOGO_WD_BG); c.setForeground(new Color(0,90,50));
                    } else if (col == 5) {
                        // Wd% — color by guardrail
                        Object gv = tblModel.getValueAt(row, COL_GUARDRAIL);
                        String g  = gv == null ? "" : gv.toString();
                        c.setForeground(g.contains("▲") ? new Color(59,109,17)
                                : g.contains("▼") ? new Color(163,45,45) : Color.BLACK);
                    } else if ((col == COL_CMB_RMD || col == COL_RMD_OVERAGE)
                            && row < lastResults.medianRows.size()
                            && lastResults.medianRows.get(row).rmdOverage > 0) {
                        // Orange: RMD exceeds planned withdrawal, overage goes to Roth/MM
                        c.setBackground(new Color(255, 200, 120));
                        c.setForeground(new Color(140, 60, 0));
                    } else if (col == COL_MAN_RMD && isIndivRmdExceedsWd(row, col)) {
                        c.setBackground(AMBER_BG); c.setForeground(AMBER_FG);
                    } else if (col == COL_WOM_RMD && isIndivRmdExceedsWd(row, col)) {
                        c.setBackground(AMBER_BG); c.setForeground(AMBER_FG);
                    } else if (col == COL_CMB_RMD && isRmdExceedsWd(row)) {
                        c.setBackground(AMBER_BG); c.setForeground(AMBER_FG);
                    } else if (col == COL_SURPLUS) {
                        c.setForeground(s.startsWith("-") ? new Color(180,30,30)
                                : new Color(59,109,17));
                    } else if (col == 24) {
                        // Bal Δ — green if portfolio grew, red if shrank
                        c.setForeground(s.startsWith("-") ? new Color(180,30,30)
                                : new Color(59,109,17));
                    }
                }
                ((JLabel) c).setHorizontalAlignment(col <= 1 ? LEFT : RIGHT);
                return c;
            }
        });

        JScrollPane scroll = new JScrollPane(tblResults);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return scroll;
    }

    /** True if combined RMD (col 7) > 80% PoS withdrawal (col 3) in given row. */
    private boolean isRmdExceedsWd(int row) {
        return rmdExceedsWdCheck(row, COL_CMB_RMD);
    }

    /** True if individual RMD column value > 80% PoS withdrawal in given row. */
    private boolean isIndivRmdExceedsWd(int row, int rmdCol) {
        return rmdExceedsWdCheck(row, rmdCol);
    }

    private boolean rmdExceedsWdCheck(int row, int rmdCol) {
        if (row < 0 || row >= tblModel.getRowCount()) return false;
        Object rmdObj = tblModel.getValueAt(row, rmdCol);
        Object wdObj  = tblModel.getValueAt(row, COL_WD);
        if (rmdObj == null || wdObj == null) return false;
        String rmdStr = rmdObj.toString();
        String wdStr  = wdObj.toString();
        if ("—".equals(rmdStr) || "—".equals(wdStr)) return false;
        try {
            // Strip currency formatting for comparison
            double rmd = Double.parseDouble(rmdStr.replaceAll("[^0-9.]", ""));
            double wd  = Double.parseDouble(wdStr.replaceAll("[^0-9.]", ""));
            return rmd > wd;
        } catch (NumberFormatException e) { return false; }
    }

    // ── Chart ─────────────────────────────────────────────────────────────────
    private JPanel buildChartPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(0, 4));
        wrapper.setBackground(new Color(245, 245, 242));
        wrapper.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        ctrl.setBackground(new Color(245, 245, 242));
        ctrl.add(new JLabel("Chart type:"));
        cmbChartType = new JComboBox<>(new String[]{
                "Withdrawal $ — fan + percentiles",
                "Portfolio balance — fan + percentiles",
                "Final balance histogram",
                "Income vs. spending (median)"
        });
        cmbChartType.setFont(new Font("SansSerif", Font.PLAIN, 12));
        cmbChartType.addActionListener(e -> { if (lastResults != null) refreshChart(); });
        ctrl.add(cmbChartType);
        chartPanel = new ChartPanel();
        wrapper.add(ctrl,       BorderLayout.NORTH);
        wrapper.add(chartPanel, BorderLayout.CENTER);
        return wrapper;
    }

    private JScrollPane buildSummaryPanel() {
        txaSummary = new JTextArea();
        txaSummary.setFont(new Font("Monospaced", Font.PLAIN, 12));
        txaSummary.setLineWrap(true);
        txaSummary.setWrapStyleWord(true);
        txaSummary.setEditable(false);
        txaSummary.setBackground(new Color(250, 250, 248));
        txaSummary.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JScrollPane sp = new JScrollPane(txaSummary);
        sp.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return sp;
    }

    private JPanel buildStatusBar() {
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        progressBar.setPreferredSize(new Dimension(0, 22));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        p.setBackground(new Color(245, 245, 242));
        p.add(progressBar);
        return p;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SIMULATION
    // ════════════════════════════════════════════════════════════════════════
    private void runSimulation() {
        btnRun.setEnabled(false);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        // Compute grand total and show immediately
        String complexityMsg = updateRunTooltip();
        progressBar.setString(complexityMsg);
        // Capture parameters on EDT
        runSeedOffset = chkRandomize.isSelected() ? System.nanoTime() : 0L;
        final long seedForThisRun = runSeedOffset;
        final int solvePaths  = iv(spMcSolvePaths);
        final int fanPaths    = iv(spMcFanPaths);
        final int binIters    = iv(spBinaryIters);
        final int horizon     = iv(spHorizon);
        // Compute grand total for progress bar
        simTotal = (long) fanPaths * horizon * binIters * solvePaths
                + (long) horizon * binIters * solvePaths * (horizon + 1) / 2;
        simCount = 0;
        final long grandTotal = simTotal;
        final long grandTotalM = Math.max(1, grandTotal / 1_000_000);

        SwingWorker<SimResults, Long> worker = new SwingWorker<>() {
            @Override protected SimResults doInBackground() {
                // Target ~100 progress updates across the full run (1% per step)
                final long publishInterval = Math.max(solvePaths, grandTotal / 100);
                simProgressCallback = running -> {
                    if (running % publishInterval < solvePaths) publish(running);
                };
                SimResults r = simulate(readInputs(), seedForThisRun, solvePaths, fanPaths, binIters);
                simProgressCallback = null;
                return r;
            }
            @Override protected void process(java.util.List<Long> chunks) {
                // chunks contains running totals published from simulation thread
                if (chunks.isEmpty()) return;
                long latest = chunks.get(chunks.size() - 1);
                long pct    = Math.min(100, latest * 100 / grandTotal);
                progressBar.setValue((int) pct);
                long latestM = latest / 1_000_000;
                progressBar.setString(String.format(
                        "%,dM / ~%,dM simulations performed…", latestM, grandTotalM));
            }
            @Override protected void done() {
                try {
                    lastResults = get();
                    updateUI(lastResults);
                    progressBar.setValue(100);
                    progressBar.setString(String.format(
                            "Complete — ~%,dM simulations · %,d fan paths · %,d solve paths · %d iters",
                            grandTotalM, fanPaths, solvePaths, binIters));
                } catch (Exception ex) {
                    progressBar.setString("Error: " + ex.getMessage());
                    ex.printStackTrace();
                }
                btnRun.setEnabled(true);
            }
        };
        worker.execute();
    }

    private SimInputs readInputs() {
        SimInputs i = new SimInputs();
        i.portfolio          = iv(spPortfolio);
        i.horizon            = iv(spHorizon);
        i.targetPoS          = iv(spTargetPoS) / 100.0;
        i.withdrawStartYear  = iv(spWithdrawStartYear);
        i.withdrawStartMonth = iv(spWithdrawStartMonth);
        i.manBirthYear       = iv(spManBirthYear);
        i.manBirthMonth      = iv(spManBirthMonth);
        i.womanBirthYear     = iv(spWomanBirthYear);
        i.womanBirthMonth    = iv(spWomanBirthMonth);
        i.manSSAmount        = iv(spManSSAmount);
        i.manSSStartYear     = iv(spManSSStartYear);
        i.manSSStartMonth    = iv(spManSSStartMonth);
        i.womanSSAmount      = iv(spWomanSSAmount);
        i.womanSSStartYear   = iv(spWomanSSStartYear);
        i.womanSSStartMonth  = iv(spWomanSSStartMonth);
        i.ssCola             = dv(spSSCola)          / 100.0;
        i.annuity            = iv(spAnnuity);
        i.annuityStartYear   = iv(spAnnuityStartYear);
        i.annuityStartMonth  = iv(spAnnuityStartMonth);
        i.manTradIRA     = iv(spManTradIRA);
        i.manRothIRA     = iv(spManRothIRA);
        i.manTrad401K    = iv(spManTrad401K);
        i.womanRoth401K  = iv(spWomanRoth401K);
        i.womanTradIRA   = iv(spWomanTradIRA);
        i.womanTrad401K  = iv(spWomanTrad401K);
        // Portfolio = sum of all accounts
        i.portfolio      = i.manTradIRA + i.manRothIRA + i.manTrad401K
                + i.womanRoth401K + i.womanTradIRA + i.womanTrad401K;
        i.nomReturn          = dv(spNomReturn)        / 100.0;
        i.stdDev             = dv(spStdDev)           / 100.0;
        i.inflation          = dv(spInflation)        / 100.0;
        i.inflationStdDev    = dv(spInflationStdDev)  / 100.0;
        i.livingExp          = iv(spLivingExp);
        i.medical            = iv(spMedical);
        i.medInflation       = dv(spMedInflation)     / 100.0;
        i.baseTax            = iv(spBaseTax);
        i.taxInflation       = dv(spTaxInflation)     / 100.0;
        i.goGoMultiplier     = dv(spGoGo);
        i.goGoDuration       = iv(spGoGoDuration);
        i.upperGuardrail     = dv(spUpperGuardrail)   / 100.0;
        i.lowerGuardrail     = dv(spLowerGuardrail)   / 100.0;
        i.manAge             = computeAge(i.manBirthYear,   i.manBirthMonth);
        i.womanAge           = computeAge(i.womanBirthYear, i.womanBirthMonth);
        i.currentAge         = i.manAge;
        return i;
    }

    private int    iv(JSpinner s) { return ((Number) s.getValue()).intValue(); }
    private double dv(JSpinner s) { return ((Number) s.getValue()).doubleValue(); }

    /** RMD for a person given their current traditional balance and age. */
    private double calcRmd(double tradBalance, int age) {
        if (age < RMD_START_AGE) return 0;
        Double factor = ULT.get(Math.min(age, 100));
        if (factor == null) factor = 6.4; // age 100+
        return tradBalance / factor;
    }

    private double manSSThisYear(SimInputs inp, int y) {
        int calYear = BASE_YEAR + y;
        if (calYear < inp.manSSStartYear) return 0;
        if (calYear == inp.manSSStartYear)
            return inp.manSSAmount * (13.0 - inp.manSSStartMonth) / 12.0;
        return inp.manSSAmount * Math.pow(1 + inp.ssCola, calYear - inp.manSSStartYear);
    }

    private double womanSSThisYear(SimInputs inp, int y) {
        int calYear = BASE_YEAR + y;
        if (calYear < inp.womanSSStartYear) return 0;
        if (calYear == inp.womanSSStartYear)
            return inp.womanSSAmount * (13.0 - inp.womanSSStartMonth) / 12.0;
        return inp.womanSSAmount * Math.pow(1 + inp.ssCola, calYear - inp.womanSSStartYear);
    }

    private double annuityThisYear(SimInputs inp, int y) {
        int calYear = BASE_YEAR + y;
        if (calYear < inp.annuityStartYear) return 0;
        if (calYear == inp.annuityStartYear)
            return inp.annuity * (13.0 - inp.annuityStartMonth) / 12.0;
        return inp.annuity;
    }

    private double taxThisYear(SimInputs inp, int y) {
        int calYear = BASE_YEAR + y;
        if (calYear < inp.withdrawStartYear) return 0;
        return inp.baseTax * Math.pow(1 + inp.taxInflation, calYear - inp.withdrawStartYear);
    }

    private SimResults simulate(SimInputs inp, long seed, int solvePaths, int fanPaths, int binIters) {
        SimResults res = new SimResults();
        res.inp = inp;
        res.medianRows = new ArrayList<>();

        int startY      = inp.withdrawStartYear - BASE_YEAR; // y-offset when draws begin
        int goGoAtStart = inp.goGoDuration; // full duration remaining at start
        int yr1Wd = solveWithdrawal(inp.portfolio, inp.horizon, inp, 999 + seed,
                solvePaths, binIters, goGoAtStart);
        res.yr1Withdrawal = yr1Wd;

        double bal          = inp.portfolio;
        double manTradIRA   = inp.manTradIRA;
        double manTrad401K  = inp.manTrad401K;
        double womanTradIRA = inp.womanTradIRA;
        double womanTrad401K= inp.womanTrad401K;

        for (int y = 0; y < inp.horizon; y++) {
            int calYear   = BASE_YEAR + y;
            // Age reached during this calendar year (standard retirement planning convention)
            int manAge    = calYear - inp.manBirthYear;
            int womanAge  = calYear - inp.womanBirthYear;
            int remaining = inp.horizon - y;
            boolean drawing = calYear >= inp.withdrawStartYear;

            // Go-go years remaining: duration minus years elapsed since withdrawal start
            int goGoRemaining = Math.max(0, inp.goGoDuration - Math.max(0, y - startY));
            int wd = drawing && bal > 0
                    ? solveWithdrawal((int) Math.max(0, bal), remaining, inp,
                    999 + y * 37 + seed, solvePaths, binIters, goGoRemaining) : 0;

            // RMD: man from traditional IRA + traditional 401K; woman from traditional IRA + traditional 401K
            double manRmd    = calcRmd(manTradIRA, manAge) + calcRmd(manTrad401K, manAge);
            double womanRmd  = calcRmd(womanTradIRA, womanAge) + calcRmd(womanTrad401K, womanAge);
            double combRmd   = manRmd + womanRmd;

            // wdActual = spending withdrawal only (go-go multiplied PoS amount)
            // RMDs are separate — overage goes to Roth/MM, not spent
            double goGoMult   = (goGoRemaining > 0) ? inp.goGoMultiplier : 1.0;
            int    wdActual   = drawing ? (int)(wd * goGoMult) : 0;
            // RMD overage: amount by which RMD exceeds planned withdrawal → redirect to Roth/MM
            int    rmdOverage = drawing ? Math.max(0, (int)combRmd - wdActual) : 0;

            double wdPct  = (drawing && bal > 0) ? wdActual / (double) bal * 100.0 : 0.0;
            double vsYr1  = (yr1Wd > 0 && drawing)
                    ? (wdActual - (int)(yr1Wd * goGoMult)) / (double)(yr1Wd * goGoMult) : 0.0;
            double inflFactor = Math.pow(1 + inp.inflation, y);

            double manSS      = manSSThisYear(inp, y);
            double womanSS    = womanSSThisYear(inp, y);
            double ann        = annuityThisYear(inp, y);
            double guaranteed = manSS + womanSS + ann;
            double totalIncome = guaranteed + wdActual;
            double tax        = taxThisYear(inp, y);
            double living     = drawing ? inp.livingExp * Math.pow(1 + inp.inflation, y) : 0;
            double medical    = drawing ? inp.medical   * Math.pow(1 + inp.medInflation, y) : 0;
            double totalSpend = drawing ? living + medical + tax : 0;
            double surplus    = totalIncome - totalSpend;

            String alert = "—";
            if (drawing) {
                if      (vsYr1 >= inp.upperGuardrail)      alert = "▲ raise alert";
                else if (vsYr1 <= -inp.lowerGuardrail)     alert = "▼ cut alert";
            }

            MedianRow row = new MedianRow();
            row.calYear        = calYear;
            row.manAge           = manAge;
            row.womanAge         = womanAge;
            row.balance          = (int) Math.max(0, bal);         // start-of-year balance (displayed)
            row.investmentGrowth = (int)(bal * inp.nomReturn);     // dollar gain from mean return
            row.withdrawal  = wd;       // pure 80% PoS amount — display only
            row.wdActual    = wdActual; // actual amount taken
            row.wdPct       = wdPct;
            row.vsYr1       = vsYr1;
            row.alert       = alert;
            row.manRmd      = (int) manRmd;
            row.womanRmd    = (int) womanRmd;
            row.combRmd     = (int) combRmd;
            row.manSS       = (int) manSS;
            row.womanSS     = (int) womanSS;
            row.annuity     = (int) ann;
            row.guaranteed  = (int) guaranteed;
            row.living      = (int) living;
            row.medical     = (int) medical;
            row.tax         = (int) tax;
            row.totalSpend  = (int) totalSpend;
            row.totalIncome = (int) totalIncome;
            row.surplus     = (int) surplus;
            row.inflFactor  = inflFactor;
            row.returnUsed  = inp.nomReturn * 100.0;
            row.inflUsed    = inp.inflation * 100.0;
            row.drawing     = drawing;
            row.goGoActive  = goGoRemaining > 0;
            row.rmdOverage  = rmdOverage;
            res.medianRows.add(row);

            // Advance: compute next year's starting balance
            double nextBal = Math.max(0, bal * (1 + inp.nomReturn) - wdActual);
            // Bal Δ = nextBal − bal (growth minus withdrawal)
            row.balDelta    = (int)(nextBal - bal);
            bal          = nextBal;
            manTradIRA   = manTradIRA   * (1 + inp.nomReturn) - calcRmd(manTradIRA,   manAge);
            manTrad401K  = manTrad401K  * (1 + inp.nomReturn) - calcRmd(manTrad401K,  manAge);
            womanTradIRA = womanTradIRA * (1 + inp.nomReturn) - calcRmd(womanTradIRA, womanAge);
            womanTrad401K= womanTrad401K* (1 + inp.nomReturn) - calcRmd(womanTrad401K,womanAge);
            if (manTradIRA < 0)   manTradIRA   = 0;
            if (manTrad401K < 0)  manTrad401K  = 0;
            if (womanTradIRA < 0) womanTradIRA = 0;
            if (womanTrad401K< 0) womanTrad401K= 0;
        }

        // Fan paths
        res.fanBalances    = new double[fanPaths][inp.horizon + 1];
        res.fanWithdrawals = new double[fanPaths][inp.horizon];
        res.fanInflFactors = new double[fanPaths][inp.horizon + 1];
        int survived = 0;

        for (int p = 0; p < fanPaths; p++) {
            SeededRng rng = new SeededRng(p * 13 + 7 + seed);
            double b            = inp.portfolio;
            double fpManTrad    = inp.manTradIRA;
            double fpManT401K   = inp.manTrad401K;
            double fpWomanTrad  = inp.womanTradIRA;
            double fpWomanT401K = inp.womanTrad401K;
            res.fanBalances[p][0]    = b;
            res.fanInflFactors[p][0] = 1.0;

            for (int y = 0; y < inp.horizon; y++) {
                int calYear = BASE_YEAR + y;
                boolean drawing = calYear >= inp.withdrawStartYear;

                double infl = Math.max(0,
                        inp.inflation + inp.inflationStdDev * rng.nextGaussian());
                res.fanInflFactors[p][y + 1] = res.fanInflFactors[p][y] * (1 + infl);

                double ret = inp.nomReturn + inp.stdDev * rng.nextGaussian();

                int fanStartY        = inp.withdrawStartYear - BASE_YEAR;
                int fanGoGoRemaining = Math.max(0, inp.goGoDuration - Math.max(0, y - fanStartY));
                int fanManAge        = calYear - inp.manBirthYear;
                int fanWomanAge      = calYear - inp.womanBirthYear;

                // RMD from per-path tracked balances — correct, not inflated approximation
                double fanManRmd   = calcRmd(fpManTrad,    fanManAge)
                        + calcRmd(fpManT401K,   fanManAge);
                double fanWomanRmd = calcRmd(fpWomanTrad,  fanWomanAge)
                        + calcRmd(fpWomanT401K, fanWomanAge);
                double fanCombRmd  = fanManRmd + fanWomanRmd;

                int wd = 0;
                if (drawing && b > 0)
                    wd = solveWithdrawal((int) b, inp.horizon - y, inp,
                            p * 1000 + y * 37 + seed, solvePaths, binIters, fanGoGoRemaining);
                double fanGoGoMult  = (fanGoGoRemaining > 0) ? inp.goGoMultiplier : 1.0;
                int wdFanGoGo       = (int)(wd * fanGoGoMult);
                int wdFanActual     = drawing ? wdFanGoGo : 0; // spending only, RMD overage → Roth/MM
                res.fanWithdrawals[p][y] = wdFanActual;

                // Advance portfolio and all traditional accounts with same random return
                b            = b            * (1 + ret) - wdFanActual;
                fpManTrad    = fpManTrad    * (1 + ret) - calcRmd(fpManTrad,   fanManAge);
                fpManT401K   = fpManT401K   * (1 + ret) - calcRmd(fpManT401K,  fanManAge);
                fpWomanTrad  = fpWomanTrad  * (1 + ret) - calcRmd(fpWomanTrad,  fanWomanAge);
                fpWomanT401K = fpWomanT401K * (1 + ret) - calcRmd(fpWomanT401K, fanWomanAge);
                if (b            < 0) b            = 0;
                if (fpManTrad    < 0) fpManTrad    = 0;
                if (fpManT401K   < 0) fpManT401K   = 0;
                if (fpWomanTrad  < 0) fpWomanTrad  = 0;
                if (fpWomanT401K < 0) fpWomanT401K = 0;
                res.fanBalances[p][y + 1] = b;
            }
            if (res.fanBalances[p][inp.horizon] > 0) survived++;
        }
        res.actualPoS = survived / (double) fanPaths;
        res.fanPathCount = fanPaths;

        double[] finals = new double[fanPaths];
        for (int p = 0; p < fanPaths; p++) finals[p] = res.fanBalances[p][inp.horizon];
        Arrays.sort(finals);
        res.medianFinalBalance = (int) finals[fanPaths / 2];
        return res;
    }

    private int solveWithdrawal(int balance, int years, SimInputs inp, long seed,
                                int solvePaths, int binIters, int goGoYearsRemaining) {
        if (balance <= 0 || years <= 0) return 0;
        double lo = 0, hi = balance * 0.22;
        for (int i = 0; i < binIters; i++) {
            double mid = (lo + hi) / 2.0;
            if (survivalRate(balance, years, mid, inp, seed, solvePaths, goGoYearsRemaining) > inp.targetPoS)
                lo = mid; else hi = mid;
        }
        return (int) ((lo + hi) / 2.0);
    }

    private double survivalRate(int balance, int years, double wd, SimInputs inp,
                                long seed, int solvePaths, int goGoYearsRemaining) {
        int ok = 0;
        for (int i = 0; i < solvePaths; i++) {
            SeededRng rng = new SeededRng(seed * 1000L + i * 7 + 3);
            double b = balance; boolean alive = true;
            for (int y = 0; y < years; y++) {
                double infl   = Math.max(0, inp.inflation + inp.inflationStdDev * rng.nextGaussian());
                double ret    = inp.nomReturn + inp.stdDev * rng.nextGaussian();
                double mult   = (y < goGoYearsRemaining) ? inp.goGoMultiplier : 1.0;
                b = b * (1 + ret) - wd * mult * Math.pow(1 + infl, y);
                if (b <= 0) { alive = false; break; }
            }
            if (alive) ok++;
        }
        // Increment running counter and notify progress callback (throttled inside callback)
        long running = (simCount += solvePaths);
        java.util.function.LongConsumer cb = simProgressCallback;
        if (cb != null) cb.accept(running);
        return ok / (double) solvePaths;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI UPDATE
    // ════════════════════════════════════════════════════════════════════════
    private void updateUI(SimResults res) {
        SimInputs inp = res.inp;
        int yr1  = res.yr1Withdrawal;
        double rate = yr1 / (double) inp.portfolio * 100.0;

        lblYear1Answer.setText(CURRENCY.format(yr1) + " / yr");
        lblYear1Sub.setText(String.format(
                "  %.2f%% of portfolio  ·  %.0f%% PoS target  ·  %d-year horizon",
                rate, inp.targetPoS * 100, inp.horizon));
        lblYear1Detail.setText(String.format(
                "Man (Sep %d, age %d) · Woman (Dec %d, age %d) · Draws begin %02d/%d · "
                        + "%.2f%% nom return / %.2f%% inflation",
                inp.manBirthYear, inp.manAge, inp.womanBirthYear, inp.womanAge,
                inp.withdrawStartMonth, inp.withdrawStartYear,
                inp.nomReturn * 100, inp.inflation * 100));

        int yr10wd  = res.medianRows.size() >= 10 ? res.medianRows.get(9).withdrawal : 0;
        double d10  = res.medianRows.size() >= 10 ? res.medianRows.get(9).inflFactor : 1.0;
        double dEnd = !res.medianRows.isEmpty()
                ? res.medianRows.get(res.medianRows.size() - 1).inflFactor : 1.0;

        lblActualPoS.setText(String.format("%.1f%%", res.actualPoS * 100));
        lblMedianFinal.setText(showRealDollars
                ? formatMoney((long)(res.medianFinalBalance / dEnd)) + " (2026$)"
                : formatMoney(res.medianFinalBalance) + " (nom.)");
        lblYr10Wd.setText(showRealDollars
                ? CURRENCY.format((long)(yr10wd / d10)) + " (2026$)"
                : CURRENCY.format(yr10wd) + " (nom.)");
        lblInitRate.setText(String.format("%.2f%%", rate));

        tblModel.setRowCount(0);
        for (MedianRow r : res.medianRows) {
            double d = showRealDollars ? r.inflFactor : 1.0;
            tblModel.addRow(new Object[]{
                    r.manAge,                                                              // 0
                    r.calYear,                                                             // 1
                    CURRENCY.format((long)(r.balance / d)),                                // 2
                    r.drawing ? CURRENCY.format((long)(r.withdrawal / d)) : "—",          // 3
                    r.drawing ? CURRENCY.format((long)(r.wdActual   / d)) : "—",          // 4
                    r.drawing ? String.format("%.2f%%", r.wdPct) : "—",                   // 5
                    r.alert,                                                               // 6 hidden
                    r.manSS   > 0 ? CURRENCY.format((long)(r.manSS   / d)) : "—",         // 7
                    r.womanSS > 0 ? CURRENCY.format((long)(r.womanSS / d)) : "—",         // 8
                    r.annuity > 0 ? CURRENCY.format((long)(r.annuity / d)) : "—",         // 9
                    r.guaranteed > 0 ? CURRENCY.format((long)(r.guaranteed / d)) : "—",  // 10
                    r.drawing ? CURRENCY.format((long)(r.living     / d)) : "—",          // 11
                    r.drawing ? CURRENCY.format((long)(r.medical    / d)) : "—",          // 12
                    r.tax > 0  ? CURRENCY.format((long)(r.tax       / d)) : "—",          // 13
                    r.drawing ? CURRENCY.format((long)(r.totalSpend / d)) : "—",          // 14
                    CURRENCY.format((long)(r.totalIncome / d)),                            // 15
                    r.drawing
                            ? (r.surplus >= 0 ? "+" : "-")
                              + CURRENCY.format((long)(Math.abs(r.surplus) / d))
                            : "—",                                                             // 16
                    String.format("%.3f", r.inflFactor),                                  // 17
                    String.format("%.2f%%", r.returnUsed),                                // 18 hidden
                    String.format("%.2f%%", r.inflUsed),                                  // 19 hidden
                    r.manRmd   > 0 ? CURRENCY.format((long)(r.manRmd   / d)) : "—",       // 20
                    r.womanRmd > 0 ? CURRENCY.format((long)(r.womanRmd / d)) : "—",       // 21
                    r.combRmd  > 0 ? CURRENCY.format((long)(r.combRmd  / d)) : "—",       // 22
                    r.rmdOverage > 0 ? CURRENCY.format((long)(r.rmdOverage / d)) : "—",   // 23
                    formatBalDelta(r, d),                                                  // 24
            });
        }

        refreshChart();
        txaSummary.setText(buildSummary(res));
        txaSummary.setCaretPosition(0);
    }

    private void refreshChart() {
        if (lastResults == null) return;
        chartPanel.setData(lastResults, cmbChartType.getSelectedIndex(), showRealDollars);
        chartPanel.repaint();
    }

    private String buildSummary(SimResults res) {
        SimInputs inp = res.inp;
        int yr1 = res.yr1Withdrawal;
        MedianRow r1  = res.medianRows.stream().filter(r -> r.drawing).findFirst().orElse(null);
        int guar1 = r1 != null ? r1.guaranteed : 0;
        int spd1  = r1 != null ? r1.totalSpend : 0;
        int inc1  = yr1 + guar1;
        int sur1  = inc1 - spd1;
        int fullSSYear = Math.max(inp.manSSStartYear, inp.womanSSStartYear);
        int manRmdYear   = inp.manBirthYear   + RMD_START_AGE;
        int womanRmdYear = inp.womanBirthYear + RMD_START_AGE;

        return String.format(
                "══ YEAR 2026 ══\n"
                        + "  No portfolio draws. Woman's salary covers living expenses.\n"
                        + "  Man (Sep %d, age %d) · Woman (Dec %d, age %d)\n\n"
                        + "══ FIRST WITHDRAWAL YEAR (%d) ══\n"
                        + "  Portfolio withdrawal:  %s/yr  (%.2f%% of $%,.0f)\n"
                        + "  + Guaranteed income:   %s\n"
                        + "  = Total income:        %s\n"
                        + "  − Total spending:      %s\n"
                        + "  → %s of %s\n\n"
                        + "══ SOCIAL SECURITY ══\n"
                        + "  Man: %s/yr from %02d/%d (age %d) · Woman: %s/yr from %02d/%d (age %d)\n"
                        + "  Both fully active from %d · COLA %.1f%%/yr\n\n"
                        + "══ ANNUITY ══\n"
                        + "  %s/yr from %d (non-COLA) · Loses ~%.0f%% real value by %d at %.1f%% inflation\n\n"
                        + "══ RMD SCHEDULE (SECURE 2.0 — age 75) ══\n"
                        + "  Man's traditional IRA: %s · RMDs begin %d (age 75)\n"
                        + "  Woman's traditional IRA + 401K: %s · RMDs begin %d (age 75)\n"
                        + "  Roth accounts (no RMD): %s\n"
                        + "  When RMD > planned withdrawal, overage → Roth/MM (net worth preserved).\n"
                        + "  Orange in table = RMD overage year. '→ Roth/MM' column shows redirected amount.\n\n"
                        + "══ TAX / SPENDING ══\n"
                        + "  Base tax %s in %d, at %.1f%%/yr · Medical %s at %.1f%%/yr\n"
                        + "  Go-go multiplier: %.3f× for first %d years of withdrawals (through %d) · Teal rows = go-go\n\n"
                        + "══ MARKET (1961-2024 historical) ══\n"
                        + "  Return: %.2f%% / %.2f%% std dev · Inflation: %.2f%% / %.2f%% std dev\n\n"
                        + "══ MEDIAN SCENARIO ══\n"
                        + "  Portfolio %s at year %d · Actual PoS: %.1f%% across %d paths",
                inp.manBirthYear, inp.manAge, inp.womanBirthYear, inp.womanAge,
                inp.withdrawStartYear,
                CURRENCY.format(yr1), yr1/(double)inp.portfolio*100, (double)inp.portfolio,
                CURRENCY.format(guar1), CURRENCY.format(inc1),
                CURRENCY.format(spd1),
                sur1 >= 0 ? "SURPLUS" : "GAP", CURRENCY.format(Math.abs(sur1)),
                CURRENCY.format(inp.manSSAmount), inp.manSSStartMonth, inp.manSSStartYear,
                inp.manAge + (inp.manSSStartYear - BASE_YEAR),
                CURRENCY.format(inp.womanSSAmount), inp.womanSSStartMonth, inp.womanSSStartYear,
                inp.womanAge + (inp.womanSSStartYear - BASE_YEAR),
                fullSSYear, inp.ssCola * 100,
                CURRENCY.format(inp.annuity), inp.annuityStartYear,
                (1 - 1.0/Math.pow(1+inp.inflation, inp.horizon))*100,
                BASE_YEAR + inp.horizon, inp.inflation * 100,
                CURRENCY.format(inp.manTradIRA),    manRmdYear,
                CURRENCY.format(inp.womanTradIRA) + " IRA + " + CURRENCY.format(inp.womanTrad401K) + " 401K", womanRmdYear,
                CURRENCY.format(inp.manRothIRA) + " (man) + " + CURRENCY.format(inp.womanRoth401K) + " (woman)",
                CURRENCY.format(inp.baseTax), inp.withdrawStartYear, inp.taxInflation*100,
                CURRENCY.format(inp.medical), inp.medInflation*100,
                inp.goGoMultiplier, inp.goGoDuration,
                inp.withdrawStartYear + inp.goGoDuration - 1,
                inp.nomReturn*100, inp.stdDev*100, inp.inflation*100, inp.inflationStdDev*100,
                formatMoney(res.medianFinalBalance), inp.horizon,
                res.actualPoS*100, res.fanPathCount
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CHART PANEL
    // ════════════════════════════════════════════════════════════════════════
    static class ChartPanel extends JPanel {
        private SimResults data;
        private int     chartType   = 0;
        private boolean realDollars = false;
        private static final Color[] PCTC = {
                new Color(99,153,34), new Color(55,138,221), new Color(186,117,23)
        };

        ChartPanel() {
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(210,210,205)));
        }

        void setData(SimResults d, int type, boolean real) {
            this.data = d; this.chartType = type; this.realDollars = real;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (data == null) return;
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            switch (chartType) {
                case 0 -> drawFan(g, false);
                case 1 -> drawFan(g, true);
                case 2 -> drawHist(g);
                case 3 -> drawIncome(g);
            }
        }

        private double[][] deflatedFan(double[][] raw, double[][] inflF) {
            if (!realDollars) return raw;
            int paths = raw.length, pts = raw[0].length;
            double[][] out = new double[paths][pts];
            for (int p = 0; p < paths; p++)
                for (int y = 0; y < pts; y++) {
                    double f = (inflF != null && y < inflF[p].length) ? inflF[p][y] : 1.0;
                    out[p][y] = f > 0 ? raw[p][y] / f : 0;
                }
            return out;
        }

        private double[] deflatedMedian(double[] vals, List<MedianRow> rows) {
            if (!realDollars) return vals;
            double[] out = new double[vals.length];
            for (int i = 0; i < vals.length; i++) {
                double f = i < rows.size() ? rows.get(i).inflFactor : 1.0;
                out[i] = f > 0 ? vals[i] / f : 0;
            }
            return out;
        }

        private Rectangle2D pa() {
            return new Rectangle2D.Double(74,22,getWidth()-84,getHeight()-60);
        }
        private double toY(Rectangle2D pa,double v,double mn,double mx){
            return pa.getMaxY()-(v-mn)/(mx-mn)*pa.getHeight();}
        private double toX(Rectangle2D pa,int yr,int tot){
            return pa.getX()+yr/(double)tot*pa.getWidth();}

        private void drawPath(Graphics2D g,Rectangle2D pa,double[]vals,
                              int pts,double mn,double mx,int tot){
            Path2D path=new Path2D.Double(); boolean st=false;
            for(int i=0;i<pts;i++){
                double x=toX(pa,i,tot),y=toY(pa,Math.max(mn,Math.min(mx,vals[i])),mn,mx);
                if(!st){path.moveTo(x,y);st=true;}else path.lineTo(x,y);}
            g.draw(path);}

        private void grid(Graphics2D g,Rectangle2D pa){
            g.setColor(new Color(220,220,215)); g.setStroke(new BasicStroke(0.5f));
            for(int i=0;i<=6;i++){double y=pa.getY()+i/6.0*pa.getHeight();
                g.draw(new Line2D.Double(pa.getX(),y,pa.getMaxX(),y));}
            g.setColor(new Color(180,180,175)); g.setStroke(new BasicStroke(1f)); g.draw(pa);}

        private void axes(Graphics2D g,Rectangle2D pa,double mn,double mx,int xs,String yLbl){
            g.setFont(new Font("SansSerif",Font.PLAIN,10)); g.setColor(new Color(90,90,90));
            for(int i=0;i<=6;i++){
                double val=mn+(mx-mn)*(1-i/6.0),y=pa.getY()+i/6.0*pa.getHeight();
                String l=formatMoney((long)val); FontMetrics fm=g.getFontMetrics();
                g.drawString(l,(float)(pa.getX()-fm.stringWidth(l)-3),(float)(y+4));}
            for(int y=0;y<=xs;y+=5){double x=toX(pa,y,xs);
                g.drawString("Yr"+y,(float)(x-8),(float)(pa.getMaxY()+14));}
            String suffix=realDollars?" (2026 $)":" (nominal)";
            Graphics2D g2=(Graphics2D)g.create();
            g2.rotate(-Math.PI/2,11,pa.getCenterY());
            g2.setFont(new Font("SansSerif",Font.PLAIN,10)); g2.setColor(new Color(100,100,100));
            String full=yLbl+suffix; FontMetrics fm=g2.getFontMetrics();
            g2.drawString(full,(float)(11-fm.stringWidth(full)/2.0),(float)pa.getCenterY());
            g2.dispose();}

        private void drawFan(Graphics2D g,boolean balMode){
            int yrs=data.inp.horizon;
            double[][]rawSer=balMode?data.fanBalances:data.fanWithdrawals;
            double[][]ser=deflatedFan(rawSer,data.fanInflFactors);
            int pts=balMode?yrs+1:yrs;
            int nPaths=data.fanPathCount;
            double maxV=0;
            for(double[]p:ser)for(double v:p)maxV=Math.max(maxV,v);
            maxV=Math.ceil(maxV/100_000.0)*100_000;
            Rectangle2D pa=pa(); grid(g,pa);
            int step=Math.max(1,nPaths/60);
            for(int p=0;p<nPaths;p+=step){
                boolean sv=data.fanBalances[p][yrs]>0;
                g.setColor(sv?new Color(55,138,221,28):new Color(226,75,74,18));
                g.setStroke(new BasicStroke(0.7f));
                drawPath(g,pa,ser[p],pts,0,maxV,yrs);}
            String[]lbls={"75th pct","Median","25th pct"};
            double[]pcts={0.75,0.50,0.25};
            for(int pi=0;pi<3;pi++){
                double[]pl=new double[pts];
                for(int y=0;y<pts;y++){
                    double[]vs=new double[nPaths];
                    for(int p=0;p<nPaths;p++)vs[p]=ser[p][Math.min(y,ser[p].length-1)];
                    Arrays.sort(vs); pl[y]=vs[(int)(pcts[pi]*(nPaths-1))];}
                g.setColor(PCTC[pi]); g.setStroke(new BasicStroke(2.2f));
                drawPath(g,pa,pl,pts,0,maxV,yrs);
                g.setFont(new Font("SansSerif",Font.PLAIN,10));
                g.drawString(lbls[pi],(float)(toX(pa,pts-1,yrs)+2),(float)toY(pa,pl[pts-1],0,maxV));}
            axes(g,pa,0,maxV,yrs,balMode?"Portfolio balance":"Annual withdrawal");}

        private void drawHist(Graphics2D g){
            int yrs=data.inp.horizon;
            int nPaths=data.fanPathCount;
            double[]fn=new double[nPaths];
            for(int p=0;p<nPaths;p++){
                double raw=data.fanBalances[p][yrs];
                double f=realDollars?data.fanInflFactors[p][yrs]:1.0;
                fn[p]=f>0?raw/f:0;}
            Arrays.sort(fn);
            double maxV=fn[nPaths-1];
            int BINS=16; double bw=Math.max(1,maxV/BINS);
            int[]cnt=new int[BINS]; int fail=0;
            for(double v:fn){if(v<=0){fail++;continue;}cnt[Math.min(BINS-1,(int)(v/bw))]++;}
            int maxC=Arrays.stream(cnt).max().orElse(1);
            Rectangle2D pa=pa(); grid(g,pa);
            double bwPx=pa.getWidth()/BINS*0.85;
            for(int b=0;b<BINS;b++){
                double x=pa.getX()+b/(double)BINS*pa.getWidth();
                double h=cnt[b]/(double)maxC*pa.getHeight();
                g.setColor(new Color(55,138,221,160));
                g.fill(new Rectangle2D.Double(x,pa.getMaxY()-h,bwPx,h));
                g.setColor(new Color(24,95,165)); g.setStroke(new BasicStroke(0.8f));
                g.draw(new Rectangle2D.Double(x,pa.getMaxY()-h,bwPx,h));}
            g.setColor(new Color(163,45,45)); g.setFont(new Font("SansSerif",Font.BOLD,11));
            g.drawString(fail+" of "+nPaths+" failed ("+
                            String.format("%.0f%%",fail*100.0/nPaths)+")",
                    (float)(pa.getX()+4),(float)(pa.getY()+14));

            // Y axis: # of paths (0 to maxC)
            g.setFont(new Font("SansSerif",Font.PLAIN,10));
            g.setColor(new Color(90,90,90));
            for(int i=0;i<=6;i++){
                double val=maxC*(1-i/6.0);
                double y=pa.getY()+i/6.0*pa.getHeight();
                String lbl=String.format("%,d",(int)val);
                FontMetrics fm=g.getFontMetrics();
                g.drawString(lbl,(float)(pa.getX()-fm.stringWidth(lbl)-3),(float)(y+4));}

            // X axis: dollar bin labels (every 4 bins to avoid crowding)
            for(int b=0;b<=BINS;b+=4){
                double x=pa.getX()+b/(double)BINS*pa.getWidth();
                String lbl=formatMoney((long)(b*bw));
                FontMetrics fm=g.getFontMetrics();
                g.drawString(lbl,(float)(x-fm.stringWidth(lbl)/2.0),(float)(pa.getMaxY()+14));}

            // Y axis rotated label
            Graphics2D g2=(Graphics2D)g.create();
            g2.rotate(-Math.PI/2,11,pa.getCenterY());
            g2.setFont(new Font("SansSerif",Font.PLAIN,10));
            g2.setColor(new Color(100,100,100));
            String yLbl="# of paths";
            FontMetrics fm2=g2.getFontMetrics();
            g2.drawString(yLbl,(float)(11-fm2.stringWidth(yLbl)/2.0),(float)pa.getCenterY());
            g2.dispose();

            // X axis title
            g.setFont(new Font("SansSerif",Font.PLAIN,10));
            g.setColor(new Color(100,100,100));
            String xLbl="Final portfolio balance" + (realDollars?" (2026 $)":" (nominal)");
            FontMetrics fmx=g.getFontMetrics();
            g.drawString(xLbl,(float)(pa.getCenterX()-fmx.stringWidth(xLbl)/2.0),
                    (float)(pa.getMaxY()+28));
        }

        private void drawIncome(Graphics2D g){
            List<MedianRow>rows=data.medianRows; if(rows.isEmpty())return;
            int yrs=rows.size();
            double[]inc=deflatedMedian(rows.stream().mapToDouble(r->r.totalIncome).toArray(),rows);
            double[]spd=deflatedMedian(rows.stream().mapToDouble(r->r.totalSpend).toArray(),rows);
            double[]wd =deflatedMedian(rows.stream().mapToDouble(r->r.wdActual).toArray(),rows);
            double[]gu =deflatedMedian(rows.stream().mapToDouble(r->r.guaranteed).toArray(),rows);
            double maxV=0;
            for(int i=0;i<yrs;i++)maxV=Math.max(maxV,Math.max(inc[i],spd[i]));
            maxV=Math.ceil(maxV/50_000.0)*50_000;
            Rectangle2D pa=pa(); grid(g,pa);
            Color[]cols={new Color(99,153,34),new Color(226,75,74,200),
                    new Color(55,138,221),new Color(29,158,117)};
            String[]lbls={"Total income","Total spending","Portfolio withdrawal","Guaranteed income"};
            double[][]lines={inc,spd,wd,gu};
            for(int li=0;li<lines.length;li++){
                g.setColor(cols[li]);
                g.setStroke(li<2?new BasicStroke(2.5f):
                        new BasicStroke(1.8f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,1f,new float[]{5,3},0));
                drawPath(g,pa,lines[li],yrs,0,maxV,yrs);}
            g.setFont(new Font("SansSerif",Font.PLAIN,10));
            for(int li=0;li<lbls.length;li++){
                g.setColor(cols[li]);
                g.fillRect((int)(pa.getX()+4+li*126),(int)(pa.getY()+4),10,10);
                g.setColor(new Color(50,50,50));
                g.drawString(lbls[li],(int)(pa.getX()+17+li*126),(int)(pa.getY()+13));}
            axes(g,pa,0,maxV,yrs,"Annual dollars");}
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SCROLLABLE PANEL — tracks viewport width so spinners fill correctly
    // ════════════════════════════════════════════════════════════════════════
    static class ScrollablePanel extends JPanel implements javax.swing.Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r,int o,int d){ return 20; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r,int o,int d){ return 60; }
        @Override public boolean getScrollableTracksViewportWidth()  { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  RNG
    // ════════════════════════════════════════════════════════════════════════
    static class SeededRng {
        private long state;
        SeededRng(long seed){this.state=seed^0x6c62272e07bb0142L;}
        private double nextUniform(){
            state=state*6364136223846793005L+1442695040888963407L;
            long bits=(state>>>33)^state;
            return(bits>>>1)/(double)Long.MAX_VALUE;}
        double nextGaussian(){
            double u=Math.max(1e-12,nextUniform()),v=nextUniform();
            return Math.sqrt(-2*Math.log(u))*Math.cos(2*Math.PI*v);}
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DATA CLASSES
    // ════════════════════════════════════════════════════════════════════════
    static class SimInputs {
        int portfolio, horizon; double targetPoS;
        int withdrawStartYear, withdrawStartMonth;
        int manBirthYear, manBirthMonth, manAge;
        int womanBirthYear, womanBirthMonth, womanAge, currentAge;
        int manSSAmount, manSSStartYear, manSSStartMonth;
        int womanSSAmount, womanSSStartYear, womanSSStartMonth;
        double ssCola;
        int annuity, annuityStartYear, annuityStartMonth;
        // Account balances
        int manTradIRA;      // traditional IRA — RMD at 75
        int manRothIRA;      // Roth IRA — no RMD
        int manTrad401K;     // traditional 401K — RMD at 75
        int womanRoth401K;   // Roth 401K — no RMD
        int womanTradIRA;    // traditional IRA — RMD at 75
        int womanTrad401K;   // traditional 401K — RMD at 75
        double nomReturn, stdDev, inflation, inflationStdDev;
        int livingExp, medical; double medInflation;
        int baseTax; double taxInflation;
        double goGoMultiplier; // spending multiplier during go-go period
        int    goGoDuration;   // number of years from withdrawal start the multiplier applies
        double upperGuardrail, lowerGuardrail;
    }

    static class MedianRow {
        int calYear, manAge, womanAge, balance, withdrawal, wdActual;
        int investmentGrowth; // dollar gain from market return this year
        int balDelta;         // net portfolio change this year (growth − wdActual)
        int manRmd, womanRmd, combRmd;
        int manSS, womanSS, annuity, guaranteed;
        int living, medical, tax, totalSpend, totalIncome, surplus;
        double vsYr1, wdPct, inflFactor, returnUsed, inflUsed;
        String alert; boolean drawing;
        boolean goGoActive;   // true during go-go duration years
        int     rmdOverage;   // amount by which combRmd exceeds wdActual (→ Roth/MM)
    }

    static class SimResults {
        SimInputs inp; int yr1Withdrawal;
        List<MedianRow> medianRows;
        double[][] fanBalances, fanWithdrawals, fanInflFactors;
        double actualPoS; int medianFinalBalance;
        int fanPathCount; // actual number of fan paths used
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI HELPERS
    // ════════════════════════════════════════════════════════════════════════
    private JPanel card(String title, Object[] items) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0,0,6,0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(208,206,200),1),
                        BorderFactory.createEmptyBorder(8,10,8,10))));
        JLabel titleLbl = new JLabel(title.toUpperCase());
        titleLbl.setFont(new Font("SansSerif",Font.BOLD,10));
        titleLbl.setForeground(new Color(110,105,95));
        titleLbl.setBorder(BorderFactory.createEmptyBorder(0,0,6,0));
        titleLbl.setAlignmentX(LEFT_ALIGNMENT);
        card.add(titleLbl);
        for(int i=0;i<items.length;i+=2){
            Object labelObj=items[i]; Object comp=items[i+1];
            if(labelObj!=null){
                JLabel lbl=new JLabel((String)labelObj);
                lbl.setFont(new Font("SansSerif",Font.PLAIN,12));
                lbl.setForeground(new Color(75,75,75));
                lbl.setBorder(BorderFactory.createEmptyBorder(5,0,1,0));
                lbl.setAlignmentX(LEFT_ALIGNMENT); card.add(lbl);}
            JComponent row=(comp instanceof JSpinner sp)?wrapSpinner(sp):(JComponent)comp;
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE,30));
            card.add(row);}
        return card;
    }

    private JPanel wrapSpinner(JSpinner sp){
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE,28));
        sp.setPreferredSize(new Dimension(200,28));
        sp.setMinimumSize(new Dimension(100,28));
        JPanel p=new JPanel(new BorderLayout()); p.setOpaque(false);
        p.add(sp,BorderLayout.CENTER); return p;}

    private JSpinner spinI(int val,int min,int max,int step,String fmt){
        JSpinner s=new JSpinner(new SpinnerNumberModel(val,min,max,step));
        s.setEditor(new JSpinner.NumberEditor(s,fmt));
        s.setFont(new Font("SansSerif",Font.PLAIN,12)); return s;}

    private JSpinner spinD(double val,double min,double max,double step,String fmt){
        JSpinner s=new JSpinner(new SpinnerNumberModel(val,min,max,step));
        s.setEditor(new JSpinner.NumberEditor(s,fmt));
        s.setFont(new Font("SansSerif",Font.PLAIN,12)); return s;}

    private JLabel mkMetricLabel(){
        JLabel l=new JLabel("—");
        l.setFont(new Font("SansSerif",Font.BOLD,17));
        l.setForeground(new Color(30,30,30)); return l;}

    private JPanel wrapMetric(JLabel val,String title,String sub){
        JPanel p=new JPanel(new BorderLayout(2,2));
        p.setBackground(new Color(240,240,237));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210,208,203),1),
                BorderFactory.createEmptyBorder(8,10,8,10)));
        JLabel t=new JLabel(title); t.setFont(new Font("SansSerif",Font.PLAIN,10));
        t.setForeground(new Color(110,110,110));
        JLabel s=new JLabel(sub); s.setFont(new Font("SansSerif",Font.PLAIN,10));
        s.setForeground(new Color(140,140,140));
        p.add(t,BorderLayout.NORTH); p.add(val,BorderLayout.CENTER);
        p.add(s,BorderLayout.SOUTH); return p;}

    private String formatBalDelta(MedianRow r, double deflator) {
        int delta = r.balDelta;
        return (delta >= 0 ? "+" : "-")
                + CURRENCY.format((long)(Math.abs(delta) / deflator));
    }

    private static String formatMoney(long n){
        if(Math.abs(n)>=1_000_000)return String.format("$%.2fM",n/1_000_000.0);
        if(Math.abs(n)>=1_000)    return String.format("$%,dK", n/1_000);
        return "$"+n;}

    public static void main(String[] args){
        SwingUtilities.invokeLater(()->{
            try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}
            catch(Exception ignored){}
            new IncomeLabStyle_PoSDriven();});
    }
}
