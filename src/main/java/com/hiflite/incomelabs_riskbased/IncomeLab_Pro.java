package com.hiflite.incomelabs_riskbased;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
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
 * IncomeLab_Pro.java
 *
 * Standalone Income Lab Pro — PoS-Driven Withdrawal Simulator
 * (Enhanced stochastic engine; no Guyton-Klinger tab)
 *
 * ── METHODOLOGY ───────────────────────────────────────────────────────────────
 *
 *  1. TRUE STOCHASTIC MEDIAN PATH
 *     Runs all fan paths first (each path draws stochastic returns/inflation
 *     and re-solves withdrawal annually). The displayed table then reads the
 *     50th-percentile balance across all fan paths at each year — not the
 *     mean-return path used in simplified tools.
 *
 *  2. ANNUAL RE-SOLVE INSIDE SOLVE TRIALS
 *     Each trial path in the binary-search solver re-solves the locally-optimal
 *     withdrawal every year via a depth-8 inner binary search, capturing
 *     path-dependent sequence-of-returns adaptation — the true Income Lab engine.
 *
 *  3. COUPLE-AWARE SS / RMD
 *     Full SSA FRA schedule, early/delayed adjustments, SECURE 2.0 RMDs (age 75),
 *     seven-account decomposition (trad/Roth for both spouses).
 *
 *  Remaining gaps vs. full Income Lab spec:
 *    • No asset allocation glide path (single return/stdDev for full horizon)
 *    • No mortality weighting
 *    • No tax drag / account-type modeling
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Compile:  javac IncomeLab_Pro.java
 * Run:      java IncomeLab_Pro
 * Requires Java 11+. No external dependencies.
 */
public class IncomeLab_Pro extends JFrame {

    private static final int BASE_YEAR_DEFAULT = java.time.Year.now().getValue();
    private static int BASE_YEAR = BASE_YEAR_DEFAULT;
    private static final int RMD_START_AGE = 75;

    private int mcSolvePaths = 800;
    private int mcFanPaths   = 400;
    private int binaryIters  = 22;

    // IRS Uniform Lifetime Table
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

    // Column indices for the Pro table
    // 0=ManAge 1=CalYr 2=PortBal(50th%) 3=ProWd 4=ActualWd 5=WdPct
    // 6=Alert  7=ManSS 8=WomanSS 9=Annuity 10=Guaranteed
    // 11=Living 12=Medical 13=Tax 14=TotalSpend 15=TotalIncome 16=SurplusGap
    // 17=InflFactor 18=ManRMD 19=WomanRMD 20=CombRMD 21=RothMM 22=BalDelta
    private static final int COL_ALERT     = 6;
    private static final int COL_CMB_RMD   = 20;
    private static final int COL_ROTH_MM   = 21;
    private static final int COL_BAL_DELTA = 22;
    private static final int COL_SURPLUS   = 16;

    // ── Font sizing ──────────────────────────────────────────────────────────
    private static final int BASE_FONT_SIZE = 12;
    private int fontDelta = 2;
    private JSpinner spFontDelta;
    private javax.swing.Timer fontDebounceTimer;
    private static final String FONT_DELTA_KEY = "app.fontDelta";

    // ── Input spinners ───────────────────────────────────────────────────────
    private JSpinner spPortfolio, spHorizon, spTargetPoS;
    private JSpinner spSimStartYear;
    private JSpinner spWithdrawStartYear, spWithdrawStartMonth;
    private JSpinner spManBirthYear, spManBirthMonth;
    private JSpinner spWomanBirthYear, spWomanBirthMonth;
    private JSpinner spManPIA, spManSSStartYear, spManSSStartMonth;
    private JSpinner spWomanPIA, spWomanSSStartYear, spWomanSSStartMonth;
    private JLabel   lblSSBenefitNote;
    private JSpinner spSSCola;
    private JSpinner spAnnuity, spAnnuityStartYear, spAnnuityStartMonth;
    private JSpinner spNomReturn, spStdDev, spInflation, spInflationStdDev;
    private JSpinner spLivingExp, spMedical, spMedInflation;
    private JSpinner spBaseTax, spTaxInflation;
    private JSpinner spGoGo, spGoGoDuration;
    private JSpinner spUpperGuardrail, spLowerGuardrail;
    private JSpinner spManTradIRA, spManRothIRA, spManTrad401K, spManRoth401K;
    private JSpinner spWomanRoth401K, spWomanRothIRA, spWomanTradIRA, spWomanTrad401K;
    private JLabel   lblAccountTotal;
    private JSpinner spManPlanAge, spWomanPlanAge;
    private JLabel   lblHorizonNote;
    private JLabel   lblManAge, lblWomanAge;
    private JCheckBox chkRandomize;
    private long      runSeedOffset = 0L;
    private JSpinner  spMcSolvePaths, spBinaryIters, spMcFanPaths;

    // ── Output widgets ───────────────────────────────────────────────────────
    private JLabel            lblAnswer, lblSub, lblDetail;
    private JLabel            lblActualPoS, lblMedianFinal, lblYr10Wd, lblInitRate;
    private JTable            tblPro;
    private DefaultTableModel tblProModel;
    private ProChartPanel     chartPanel;
    private JComboBox<String> cmbChartType;
    private JTextArea         txaSummary;
    private JToggleButton     tglDollars;
    private JButton           btnRun;
    private JProgressBar      progressBar;

    private ProResults lastResults    = null;
    private boolean    showRealDollars = false;
    private final java.util.concurrent.atomic.AtomicLong simCount = new java.util.concurrent.atomic.AtomicLong(0);
    private          long simTotal    = 0;
    private volatile java.util.function.LongConsumer simProgressCallback = null;

    private static final NumberFormat CURRENCY = NumberFormat.getCurrencyInstance(Locale.US);
    static { CURRENCY.setMaximumFractionDigits(0); }

    // ════════════════════════════════════════════════════════════════════════
    //  Constructor
    // ════════════════════════════════════════════════════════════════════════
    public IncomeLab_Pro() {
        super("IncomeLab Pro — Enhanced PoS-Driven Withdrawal Simulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(new Color(245, 245, 242));

        ToolTipManager ttm = ToolTipManager.sharedInstance();
        ttm.setInitialDelay(750);
        ttm.setDismissDelay(15_000);
        ttm.setReshowDelay(500);

        add(buildInputPanel(),  BorderLayout.WEST);
        add(buildOutputPanel(), BorderLayout.CENTER);
        add(buildStatusBar(),   BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(1300, 760));
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLocationRelativeTo(null);
        setVisible(true);
        SwingUtilities.invokeLater(this::updateSSBenefitNote);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  INPUT PANEL  (identical to IncomeLab_Enhanced; GK spinner removed)
    // ════════════════════════════════════════════════════════════════════════
    private JPanel buildInputPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(new Color(240, 240, 237));
        outer.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(200, 198, 193)));
        outer.setPreferredSize(new Dimension(420, 0));
        outer.setMinimumSize(new Dimension(380, 0));

        JPanel inner = new ScrollablePanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBackground(new Color(240, 240, 237));
        inner.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // ── Appearance ────────────────────────────────────────────────────
        spFontDelta = new JSpinner(new SpinnerNumberModel(2, -4, 4, 1));
        spFontDelta.setFont(new Font("SansSerif", Font.PLAIN, BASE_FONT_SIZE + fontDelta));
        fontDebounceTimer = new javax.swing.Timer(1000, e -> {
            fontDelta = (Integer) spFontDelta.getValue();
            applyFonts(SwingUtilities.getWindowAncestor(spFontDelta));
        });
        fontDebounceTimer.setRepeats(false);
        spFontDelta.addChangeListener(e -> fontDebounceTimer.restart());
        inner.add(card("Appearance", new Object[]{ "Font size adjustment (pt)", spFontDelta }));
        inner.add(Box.createVerticalStrut(4));

        // ── Portfolio & Simulation ─────────────────────────────────────────
        int curYear = java.time.Year.now().getValue();
        spSimStartYear       = spinI(curYear, 2020, 2040, 1, "#");
        spPortfolio          = spinI(1_500_000, 10_000, 20_000_000, 10_000, "#,###");
        spPortfolio.addChangeListener(e -> distributePortfolioDelta());
        spHorizon            = spinI(30, 10, 50, 1, "#");
        spHorizon.setEnabled(false);
        ((JSpinner.DefaultEditor) spHorizon.getEditor()).getTextField().setEditable(false);
        ((JSpinner.DefaultEditor) spHorizon.getEditor()).getTextField().setBackground(new Color(225, 225, 220));
        ((JSpinner.DefaultEditor) spHorizon.getEditor()).getTextField().setForeground(new Color(80, 80, 80));
        spTargetPoS          = spinI(80, 60, 99, 1, "#");
        spWithdrawStartYear  = spinI(2027, 2025, 2040, 1, "#");
        spWithdrawStartMonth = spinI(1, 1, 12, 1, "#");

        chkRandomize = new JCheckBox("Re-randomize each run", true);
        chkRandomize.setFont(new Font("SansSerif", Font.PLAIN, 14));
        chkRandomize.setForeground(new Color(75, 75, 75));
        chkRandomize.setOpaque(false);
        chkRandomize.setAlignmentX(LEFT_ALIGNMENT);

        spMcSolvePaths = spinI(800, 50, 5000, 50, "#,###");
        spBinaryIters  = spinI(22, 8, 30, 1, "#");
        spMcFanPaths   = spinI(400, 20, 2000, 20, "#,###");

        ChangeListener refreshRunTooltip = e -> updateRunTooltip();
        spMcSolvePaths.addChangeListener(refreshRunTooltip);
        spBinaryIters.addChangeListener(refreshRunTooltip);
        spMcFanPaths.addChangeListener(refreshRunTooltip);
        spHorizon.addChangeListener(refreshRunTooltip);

        inner.add(card("Portfolio & Simulation", new Object[]{
                "Simulation start year",       spSimStartYear,
                "Target probability of success (%)", spTargetPoS,
                "Withdrawal start year",       spWithdrawStartYear,
                "Withdrawal start month",      spWithdrawStartMonth,
                null, chkRandomize,
                "MC solve paths",              spMcSolvePaths,
                "Binary search iterations",    spBinaryIters,
                "Fan chart paths",             spMcFanPaths,
        }));
        inner.add(Box.createVerticalStrut(4));

        // ── People ────────────────────────────────────────────────────────
        spManBirthYear   = spinI(1961, 1940, 2000, 1, "#");
        spManBirthMonth  = spinI(9,    1,    12,   1, "#");
        spWomanBirthYear = spinI(1962, 1940, 2000, 1, "#");
        spWomanBirthMonth= spinI(12,   1,    12,   1, "#");
        spManPlanAge     = spinI(90, 70, 110, 1, "#");
        spWomanPlanAge   = spinI(92, 70, 110, 1, "#");
        lblHorizonNote   = new JLabel(" ");
        lblHorizonNote.setFont(new Font("SansSerif", Font.ITALIC, 12));
        lblHorizonNote.setForeground(new Color(100, 100, 100));
        lblManAge        = new JLabel("Man age: —");
        lblManAge.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblWomanAge      = new JLabel("Woman age: —");
        lblWomanAge.setFont(new Font("SansSerif", Font.PLAIN, 12));

        ChangeListener peopleListener = e -> {
            updateAgeLabels();
            updateHorizonFromPlanAge();
        };
        spManBirthYear.addChangeListener(peopleListener);
        spManBirthMonth.addChangeListener(peopleListener);
        spWomanBirthYear.addChangeListener(peopleListener);
        spWomanBirthMonth.addChangeListener(peopleListener);
        spManPlanAge.addChangeListener(peopleListener);
        spWomanPlanAge.addChangeListener(peopleListener);

        JPanel ageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        ageRow.setOpaque(false); ageRow.setAlignmentX(LEFT_ALIGNMENT);
        ageRow.add(lblManAge); ageRow.add(lblWomanAge);

        inner.add(card("People — Birth Dates & Life Expectancy", new Object[]{
                "Man birth year",         spManBirthYear,
                "Man birth month",        spManBirthMonth,
                "Woman birth year",       spWomanBirthYear,
                "Woman birth month",      spWomanBirthMonth,
                "Man's life expectancy",  spManPlanAge,
                "Woman's life expectancy", spWomanPlanAge,
                null,                     ageRow,
                null,                     lblHorizonNote,
        }));
        inner.add(Box.createVerticalStrut(4));

        SwingUtilities.invokeLater(() -> {
            updateAgeLabels();
            updatePlanAgeDefaults();
            updateHorizonFromPlanAge();
        });

        // ── Account Balances ──────────────────────────────────────────────
        spManTradIRA     = spinI(880_000,  0, 10_000_000, 1_000, "#,###");
        spManRothIRA     = spinI( 10_000,  0, 10_000_000, 1_000, "#,###");
        spManTrad401K    = spinI(      0,  0, 10_000_000, 1_000, "#,###");
        spManRoth401K    = spinI(      0,  0, 10_000_000, 1_000, "#,###");
        spWomanRoth401K  = spinI( 30_000,  0, 10_000_000, 1_000, "#,###");
        spWomanRothIRA   = spinI(      0,  0, 10_000_000, 1_000, "#,###");
        spWomanTradIRA   = spinI(266_000,  0, 10_000_000, 1_000, "#,###");
        spWomanTrad401K  = spinI(314_000,  0, 10_000_000, 1_000, "#,###");
        lblAccountTotal  = new JLabel("Account total: —");
        lblAccountTotal.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblAccountTotal.setForeground(new Color(40, 80, 40));

        ChangeListener acctListener = e -> updateAccountTotal();
        for (JSpinner sp : new JSpinner[]{ spManTradIRA, spManRothIRA, spManTrad401K, spManRoth401K,
                spWomanRoth401K, spWomanRothIRA, spWomanTradIRA, spWomanTrad401K })
            sp.addChangeListener(acctListener);

        inner.add(card("Account Balances (SECURE 2.0 RMD — Age 75)", new Object[]{
                "Man — Traditional IRA ($)  [RMD age 75]",    spManTradIRA,
                "Man — Roth IRA ($)  [no RMD]",               spManRothIRA,
                "Man — Traditional 401K ($)  [RMD age 75]",   spManTrad401K,
                "Man — Roth 401K ($)  [no RMD]",              spManRoth401K,
                "Woman — Roth 401K ($)  [no RMD]",            spWomanRoth401K,
                "Woman — Roth IRA ($)  [no RMD]",             spWomanRothIRA,
                "Woman — Traditional IRA ($)  [RMD age 75]",  spWomanTradIRA,
                "Woman — Traditional 401K ($)  [RMD age 75]", spWomanTrad401K,
                null,                                          lblAccountTotal,
        }));
        inner.add(Box.createVerticalStrut(4));
        SwingUtilities.invokeLater(this::updateAccountTotal);

        // ── Social Security ───────────────────────────────────────────────
        spManPIA           = spinI(3_788, 0, 6_000, 50, "#,###");
        spManSSStartYear   = spinI(2027,  2020, 2040, 1, "#");
        spManSSStartMonth  = spinI(1,     1,    12,   1, "#");
        spWomanPIA         = spinI(3_897, 0, 6_000, 50, "#,###");
        spWomanSSStartYear = spinI(2027,  2020, 2040, 1, "#");
        spWomanSSStartMonth= spinI(12,    1,    12,   1, "#");
        spSSCola           = spinD(2.3,   0.0,  5.0,  0.1, "0.0#");
        lblSSBenefitNote   = new JLabel(" ");
        lblSSBenefitNote.setFont(new Font("SansSerif", Font.ITALIC, 12));
        lblSSBenefitNote.setForeground(new Color(80, 100, 60));

        ChangeListener ssListener = e -> updateSSBenefitNote();
        for (JSpinner sp : new JSpinner[]{
                spManPIA, spManBirthYear, spManBirthMonth, spManSSStartYear, spManSSStartMonth,
                spWomanPIA, spWomanBirthYear, spWomanBirthMonth, spWomanSSStartYear, spWomanSSStartMonth })
            sp.addChangeListener(ssListener);

        inner.add(card("Social Security", new Object[]{
                "Man PIA (monthly at FRA, $)",     spManPIA,
                "Man SS start year",               spManSSStartYear,
                "Man SS start month",              spManSSStartMonth,
                "Woman PIA (monthly at FRA, $)",   spWomanPIA,
                "Woman SS start year",             spWomanSSStartYear,
                "Woman SS start month",            spWomanSSStartMonth,
                "SS COLA (%/yr)",                  spSSCola,
                null,                              lblSSBenefitNote,
        }));
        inner.add(Box.createVerticalStrut(4));

        // ── Annuity ───────────────────────────────────────────────────────
        spAnnuity           = spinI(22_599, 0, 500_000, 500, "#,###");
        spAnnuityStartYear  = spinI(2028, 2020, 2040, 1, "#");
        spAnnuityStartMonth = spinI(4,    1,    12,   1, "#");
        inner.add(card("Annuity (non-COLA)", new Object[]{
                "Annual annuity income ($)",  spAnnuity,
                "Annuity start year",         spAnnuityStartYear,
                "Annuity start month",        spAnnuityStartMonth,
        }));
        inner.add(Box.createVerticalStrut(4));

        // ── Market Assumptions ────────────────────────────────────────────
        spNomReturn       = spinD(6.70, 0.0, 20.0, 0.01, "0.00#");
        spStdDev          = spinD(10.79, 0.0, 40.0, 0.01, "0.00#");
        spInflation       = spinD(3.79,  0.0, 15.0, 0.01, "0.00#");
        spInflationStdDev = spinD(2.73,  0.0, 10.0, 0.01, "0.00#");
        inner.add(card("Market Assumptions (1961-2024 Historical)", new Object[]{
                "Expected nominal return (%)",  spNomReturn,
                "Return std deviation (%)",     spStdDev,
                "Mean inflation (%/yr)",        spInflation,
                "Inflation std deviation (%)",  spInflationStdDev,
        }));
        inner.add(Box.createVerticalStrut(4));

        // ── Spending ──────────────────────────────────────────────────────
        spLivingExp    = spinI(105_000, 0, 500_000, 1_000, "#,###");
        spMedical      = spinI( 16_000, 0, 100_000,   500, "#,###");
        spMedInflation = spinD(4.5,     0.0, 15.0,   0.1,  "0.0#");
        spBaseTax      = spinI( 17_500, 0, 200_000, 1_000, "#,###");
        spTaxInflation = spinD(3.79,    0.0, 10.0,  0.01,  "0.00#");
        spGoGo         = spinD(1.300,   1.0,  2.0,  0.001, "0.000#");
        spGoGo.setToolTipText("<html><b>Common multiplier ranges:</b><br><br>"
                + "&nbsp;&nbsp;<b>1.2×&nbsp;(20% more)</b> — Conservative; suitable if you already have<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                + "an active lifestyle baked into your baseline<br><br>"
                + "&nbsp;&nbsp;<b>1.3×&nbsp;(30% more)</b> — The most commonly cited \"middle ground\"<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                + "in retirement planning literature<br><br>"
                + "&nbsp;&nbsp;<b>1.5×&nbsp;(50% more)</b> — Used for people expecting significant travel,<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                + "bucket-list spending, or major lifestyle upgrades</html>");
        spGoGoDuration = spinI(10,      0,    20,    1,     "#");
        inner.add(card("Annual Spending (2027 Base $)", new Object[]{
                "Living expenses ($/yr)",             spLivingExp,
                "Medical ($/yr)",                     spMedical,
                "Medical inflation (%/yr)",           spMedInflation,
                "Base tax — yr 1 ($/yr)",             spBaseTax,
                "Tax inflation (%/yr)",               spTaxInflation,
                "Go-go years multiplier",             spGoGo,
                "Go-go years duration (from wd start)", spGoGoDuration,
        }));
        inner.add(Box.createVerticalStrut(4));

        // ── Guardrails ────────────────────────────────────────────────────
        spUpperGuardrail = spinD(20.0, 5.0, 50.0, 1.0, "0.0#");
        spLowerGuardrail = spinD(20.0, 5.0, 50.0, 1.0, "0.0#");
        inner.add(card("Guardrails (advisory alerts)", new Object[]{
                "Upper guardrail (% above yr1, raise alert)", spUpperGuardrail,
                "Lower guardrail (% below yr1, cut alert)",   spLowerGuardrail,
        }));
        inner.add(Box.createVerticalStrut(4));

        // ── Run button ────────────────────────────────────────────────────
        btnRun = new JButton("▶  Run Simulation");
        btnRun.setFont(new Font("SansSerif", Font.BOLD, 16));
        btnRun.setBackground(new Color(24, 95, 165));
        btnRun.setForeground(Color.WHITE);
        btnRun.setFocusPainted(false);
        btnRun.setAlignmentX(LEFT_ALIGNMENT);
        btnRun.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        btnRun.addActionListener(e -> runSimulation());
        inner.add(Box.createVerticalStrut(8));
        inner.add(btnRun);

        JScrollPane scroll = new JScrollPane(inner,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        outer.add(scroll, BorderLayout.CENTER);
        return outer;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  OUTPUT PANEL
    //  Single-panel layout: answer box → metrics row → tabbed (table/chart/summary)
    // ════════════════════════════════════════════════════════════════════════
    private JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(new Color(245, 245, 242));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // ── Answer box ───────────────────────────────────────────────────
        JPanel answerBox = new JPanel(new BorderLayout(4, 4));
        answerBox.setBackground(new Color(230, 243, 255));
        answerBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(130, 190, 240), 1),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));

        lblAnswer = new JLabel("—");
        lblAnswer.setFont(new Font("SansSerif", Font.BOLD, 32));
        lblAnswer.setForeground(new Color(15, 80, 150));
        lblSub = new JLabel(" ");
        lblSub.setFont(new Font("SansSerif", Font.PLAIN, 15));
        lblSub.setForeground(new Color(80, 80, 80));
        lblDetail = new JLabel(" ");
        lblDetail.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblDetail.setForeground(new Color(100, 100, 100));

        tglDollars = new JToggleButton("Showing: Future $ (nominal)");
        tglDollars.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tglDollars.setFocusPainted(false);
        tglDollars.addActionListener(e -> {
            showRealDollars = tglDollars.isSelected();
            tglDollars.setText(showRealDollars ? "Showing: Today's $ (real)" : "Showing: Future $ (nominal)");
            if (lastResults != null) updateUI(lastResults);
        });

        JPanel aNorth = new JPanel(new BorderLayout()); aNorth.setOpaque(false);
        JLabel aTitle = new JLabel(
                "Year 1 portfolio withdrawal — true stochastic median, annual re-solve inside trial paths");
        aTitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
        aTitle.setForeground(new Color(90, 90, 90));
        aNorth.add(aTitle, BorderLayout.WEST);
        aNorth.add(tglDollars, BorderLayout.EAST);

        JPanel aMid = new JPanel(new BorderLayout(2, 2)); aMid.setOpaque(false);
        aMid.add(lblAnswer, BorderLayout.CENTER);
        aMid.add(lblSub,    BorderLayout.SOUTH);
        answerBox.add(aNorth,   BorderLayout.NORTH);
        answerBox.add(aMid,     BorderLayout.CENTER);
        answerBox.add(lblDetail,BorderLayout.SOUTH);

        // ── Metrics row ──────────────────────────────────────────────────
        JPanel metricsRow = new JPanel(new GridLayout(1, 4, 8, 0));
        metricsRow.setBackground(new Color(245, 245, 242));
        lblActualPoS   = mkMetricLabel();
        lblMedianFinal = mkMetricLabel();
        lblYr10Wd      = mkMetricLabel();
        lblInitRate    = mkMetricLabel();
        metricsRow.add(wrapMetric(lblActualPoS,   "Actual PoS",                "from fan simulation"));
        metricsRow.add(wrapMetric(lblMedianFinal, "True median final balance", "50th pct of fan paths"));
        metricsRow.add(wrapMetric(lblYr10Wd,      "Yr 10 withdrawal (median)", "see dollar toggle"));
        metricsRow.add(wrapMetric(lblInitRate,    "Initial withdrawal rate",   "% of portfolio"));

        // ── Method badge ─────────────────────────────────────────────────
        JPanel badge = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 3));
        badge.setBackground(new Color(240, 250, 235));
        badge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(160, 210, 130), 1),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        for (String c : new String[]{
                "✓ True stochastic median (50th pct of fan paths)",
                "✓ Annual re-solve inside trial paths (seq-of-returns adaptive)",
                "✓ SECURE 2.0 RMDs  ✓ Couple SS with FRA/early/delayed" }) {
            JLabel lbl = new JLabel(c);
            lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
            lbl.setForeground(new Color(40, 110, 30));
            badge.add(lbl);
        }

        JPanel topSection = new JPanel(new BorderLayout(0, 6));
        topSection.setBackground(new Color(245, 245, 242));
        topSection.add(answerBox,  BorderLayout.NORTH);
        topSection.add(metricsRow, BorderLayout.CENTER);
        topSection.add(badge,      BorderLayout.SOUTH);

        // ── Tabs: table / chart / summary ────────────────────────────────
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.PLAIN, 14));
        tabs.addTab("Pro PoS Table",      buildTablePanel());
        tabs.addTab("Simulation Chart",   buildChartPanel());
        tabs.addTab("Summary",            buildSummaryPanel());

        panel.add(topSection, BorderLayout.NORTH);
        panel.add(tabs,       BorderLayout.CENTER);
        return panel;
    }

    // ── Pro PoS Table ─────────────────────────────────────────────────────
    private JScrollPane buildTablePanel() {
        String[] cols = {
                "Man age", "Cal yr", "Portfolio bal (50th%)",           // 0 1 2
                "Pro PoS withdrawal", "Actual wd", "Wd %",              // 3 4 5
                "Alert",                                                  // 6
                "Man SS", "Woman SS", "Annuity", "Guaranteed",           // 7 8 9 10
                "Living", "Medical", "Tax (est)",                        // 11 12 13
                "Total spend", "Total income", "Surplus/gap",            // 14 15 16
                "Infl factor",                                           // 17
                "Man RMD", "Woman RMD", "Combined RMD", "→ Roth/MM",    // 18 19 20 21
                "Bal Δ"                                                   // 22
        };
        tblProModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        tblPro = new JTable(tblProModel) {
            @Override public String getToolTipText(MouseEvent e) {
                int col = columnAtPoint(e.getPoint());
                int row = rowAtPoint(e.getPoint());
                if (row < 0 || lastResults == null) return null;
                List<EnhRow> rows = lastResults.medianRows;
                if (row >= rows.size()) return null;
                EnhRow er = rows.get(row);
                switch (col) {
                    case 2 -> { return "<html><b>Portfolio balance — true 50th percentile</b><br>"
                            + "The median of all " + lastResults.fanPathCount
                            + " stochastic fan paths at this year.<br>"
                            + "More conservative and accurate than a mean-return path.</html>"; }
                    case 3 -> { return "<html><b>Pro PoS withdrawal</b><br>"
                            + "Binary-search solved where each trial path re-solves<br>"
                            + "the optimal withdrawal <i>every year</i> (not fixed + inflated).<br>"
                            + "Captures path-dependent sequence-of-returns adaptation.</html>"; }
                    case COL_ALERT -> {
                        if ("▲ raise alert".equals(er.alert))
                            return "<html><b>▲ Raise alert</b><br>"
                                    + "Re-solved withdrawal rose above upper guardrail threshold.<br>"
                                    + "Portfolio has grown; sustainable to spend more.</html>";
                        if ("▼ cut alert".equals(er.alert))
                            return "<html><b>▼ Cut alert</b><br>"
                                    + "Re-solved withdrawal fell below lower guardrail threshold.<br>"
                                    + "Consider reducing discretionary spending this year.</html>";
                        return null;
                    }
                    case COL_ROTH_MM -> {
                        if (er.rmdOverage <= 0) return null;
                        return "<html><b>RMD overage → Roth/MM</b><br>"
                                + "Combined RMD (" + CURRENCY.format(er.combRmd) + ")<br>"
                                + "exceeds planned spending withdrawal.<br>"
                                + "Overage (" + CURRENCY.format(er.rmdOverage) + ") → Roth/MM — not spent.<br>"
                                + "This is an involuntary Roth conversion opportunity.</html>";
                    }
                    case COL_BAL_DELTA -> {
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format("<html><b>Portfolio change: %s%s</b><br>"
                                        + "&nbsp;&nbsp;Market growth:&nbsp;&nbsp;+%s<br>"
                                        + "&nbsp;&nbsp;Withdrawal:&nbsp;&nbsp;&nbsp;−%s</html>",
                                er.balDelta >= 0 ? "+" : "",
                                CURRENCY.format((long)(er.balDelta / d)),
                                CURRENCY.format((long)(er.investmentGrowth / d)),
                                CURRENCY.format((long)(er.wdActual / d)));
                    }
                    default -> { return null; }
                }
            }
        };

        tblPro.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tblPro.setRowHeight(24);
        tblPro.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        tblPro.setGridColor(new Color(220, 220, 215));
        tblPro.setShowGrid(true);
        tblPro.setSelectionBackground(new Color(210, 230, 250));
        tblPro.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        int[] cw = {
                55, 55, 120, 125, 105, 68,  // 0-5
                90,                          // 6 alert
                75, 80, 72, 90,             // 7-10
                72, 72, 78,                 // 11-13
                88, 95, 85,                 // 14-16
                72,                         // 17
                80, 85, 90, 85,             // 18-21
                90                          // 22
        };
        for (int i = 0; i < cw.length && i < tblPro.getColumnCount(); i++)
            tblPro.getColumnModel().getColumn(i).setPreferredWidth(cw[i]);

        // Header tooltips
        JTableHeader hdr = tblPro.getTableHeader();
        hdr.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int col = hdr.columnAtPoint(e.getPoint());
                switch (col) {
                    case 18 -> hdr.setToolTipText("<html><b>Man RMD</b><br>Required Minimum Distribution from man's trad IRA + 401K.<br>Begins age 75 (SECURE 2.0).</html>");
                    case 19 -> hdr.setToolTipText("<html><b>Woman RMD</b><br>Required Minimum Distribution from woman's trad IRA + 401K.<br>Begins age 75 (SECURE 2.0).</html>");
                    case 20 -> hdr.setToolTipText("<html><b>Combined RMD</b><br>Sum of man + woman RMDs.<br>Orange = RMD exceeds planned withdrawal.</html>");
                    case 21 -> hdr.setToolTipText("<html><b>→ Roth/MM — RMD overage</b><br>= max(0, Combined RMD − Actual wd).<br>Excess RMD redirected to Roth/MM — not spent.</html>");
                    case 22 -> hdr.setToolTipText("<html><b>Bal Δ — portfolio change</b><br>= market growth − spending withdrawal.<br>Green = grew · Red = shrank.</html>");
                    default -> hdr.setToolTipText(null);
                }
            }
        });

        // Cell renderer
        tblPro.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private final Color GOGO_BG    = new Color(232, 248, 240);
            private final Color GOGO_WD_BG = new Color(180, 230, 205);
            private final Color AMBER_BG   = new Color(255, 220, 100);
            private final Color AMBER_FG   = new Color(130, 80, 0);
            private final Color ORANGE_BG  = new Color(255, 200, 120);
            private final Color ORANGE_FG  = new Color(140, 60, 0);

            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel && lastResults != null && row < lastResults.medianRows.size()) {
                    EnhRow er = lastResults.medianRows.get(row);
                    boolean goGo = er.goGoActive;
                    c.setBackground(goGo ? GOGO_BG : (row % 2 == 0 ? Color.WHITE : new Color(248, 248, 245)));
                    c.setForeground(Color.BLACK);
                    String s = v == null ? "" : v.toString();

                    if ((col == 3 || col == 4) && goGo) {
                        c.setBackground(GOGO_WD_BG); c.setForeground(new Color(0, 90, 50));
                    } else if (col == COL_ALERT) {
                        if ("▲ raise alert".equals(er.alert)) c.setForeground(new Color(59, 109, 17));
                        else if ("▼ cut alert".equals(er.alert)) c.setForeground(new Color(163, 45, 45));
                    } else if (col == COL_CMB_RMD || col == COL_ROTH_MM) {
                        if (er.rmdOverage > 0) { c.setBackground(ORANGE_BG); c.setForeground(ORANGE_FG); }
                    } else if ((col == 18 || col == 19) && er.rmdOverage > 0) {
                        c.setBackground(AMBER_BG); c.setForeground(AMBER_FG);
                    } else if (col == COL_SURPLUS) {
                        c.setForeground(s.startsWith("-") ? new Color(180, 30, 30) : new Color(59, 109, 17));
                    } else if (col == COL_BAL_DELTA) {
                        c.setForeground(s.startsWith("-") ? new Color(180, 30, 30) : new Color(59, 109, 17));
                    }
                }
                ((JLabel) c).setHorizontalAlignment(col <= 1 ? LEFT : RIGHT);
                return c;
            }
        });

        JScrollPane scroll = new JScrollPane(tblPro);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return scroll;
    }

    // ── Chart panel ──────────────────────────────────────────────────────
    private JPanel buildChartPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(0, 4));
        wrapper.setBackground(new Color(245, 245, 242));
        wrapper.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        ctrl.setBackground(new Color(245, 245, 242));
        ctrl.add(new JLabel("Chart type:"));
        cmbChartType = new JComboBox<>(new String[]{
                "Portfolio balance — fan + percentiles",
                "Withdrawal $ — fan + percentiles",
                "Final balance histogram"
        });
        cmbChartType.setFont(new Font("SansSerif", Font.PLAIN, 14));
        cmbChartType.addActionListener(e -> refreshChart());
        ctrl.add(cmbChartType);

        chartPanel = new ProChartPanel();
        wrapper.add(ctrl,       BorderLayout.NORTH);
        wrapper.add(chartPanel, BorderLayout.CENTER);
        return wrapper;
    }

    // ── Summary panel ────────────────────────────────────────────────────
    private JScrollPane buildSummaryPanel() {
        txaSummary = new JTextArea();
        txaSummary.setFont(new Font("Monospaced", Font.PLAIN, 14));
        txaSummary.setLineWrap(true);
        txaSummary.setWrapStyleWord(true);
        txaSummary.setEditable(false);
        txaSummary.setBackground(new Color(250, 253, 248));
        txaSummary.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JScrollPane sp = new JScrollPane(txaSummary);
        sp.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return sp;
    }

    private JPanel buildStatusBar() {
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Ready — click Run Simulation");
        progressBar.setPreferredSize(new Dimension(0, 22));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        p.setBackground(new Color(245, 245, 242));
        p.add(progressBar);
        return p;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SIMULATION  (dispatches Enhanced engine only; no GK)
    // ════════════════════════════════════════════════════════════════════════
    private void runSimulation() {
        btnRun.setEnabled(false);
        progressBar.setValue(0);
        String statusMsg = updateRunTooltip();
        progressBar.setString(statusMsg);

        runSeedOffset = chkRandomize.isSelected() ? System.nanoTime() : 0L;
        final long seed       = runSeedOffset;
        final int solvePaths  = iv(spMcSolvePaths);
        final int fanPaths    = iv(spMcFanPaths);
        final int binIters    = iv(spBinaryIters);
        final int horizon     = iv(spHorizon);

        simTotal = (long) fanPaths * horizon * binIters * solvePaths
                + (long) horizon * binIters * solvePaths * (horizon + 1) / 2;
        simCount.set(0);
        final long grandTotal  = simTotal;
        final long grandTotalM = Math.max(1, grandTotal / 1_000_000);

        SwingWorker<ProResults, Long> worker = new SwingWorker<>() {
            @Override protected ProResults doInBackground() {
                final long interval = Math.max(solvePaths, grandTotal / 100);
                simProgressCallback = running -> {
                    if (running % interval < solvePaths) publish(running);
                };
                ProResults r = simulatePro(readInputs(), seed, solvePaths, fanPaths, binIters);
                simProgressCallback = null;
                return r;
            }
            @Override protected void process(java.util.List<Long> chunks) {
                if (chunks.isEmpty()) return;
                long latest = chunks.get(chunks.size() - 1);
                long pct    = Math.min(100, latest * 100 / grandTotal);
                progressBar.setValue((int) pct);
                progressBar.setString(String.format(
                        "%,dM / ~%,dM simulations…", latest / 1_000_000, grandTotalM));
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
        i.baseYear           = iv(spSimStartYear);
        BASE_YEAR            = i.baseYear;
        i.portfolio          = iv(spPortfolio);
        i.horizon            = iv(spHorizon);
        i.targetPoS          = iv(spTargetPoS) / 100.0;
        i.withdrawStartYear  = iv(spWithdrawStartYear);
        i.withdrawStartMonth = iv(spWithdrawStartMonth);
        i.manBirthYear       = iv(spManBirthYear);
        i.manBirthMonth      = iv(spManBirthMonth);
        i.womanBirthYear     = iv(spWomanBirthYear);
        i.womanBirthMonth    = iv(spWomanBirthMonth);
        i.manSSStartYear     = iv(spManSSStartYear);
        i.manSSStartMonth    = iv(spManSSStartMonth);
        i.womanSSStartYear   = iv(spWomanSSStartYear);
        i.womanSSStartMonth  = iv(spWomanSSStartMonth);
        i.ssCola             = dv(spSSCola) / 100.0;
        i.manPIA             = iv(spManPIA);
        i.womanPIA           = iv(spWomanPIA);
        i.manSSMonthly       = calcSSMonthlyBenefit(i.manPIA, i.manBirthYear, i.manBirthMonth,
                i.manSSStartYear, i.manSSStartMonth);
        i.womanSSMonthly     = calcSSMonthlyBenefit(i.womanPIA, i.womanBirthYear, i.womanBirthMonth,
                i.womanSSStartYear, i.womanSSStartMonth);
        i.manSSAmount        = (int) Math.round(i.manSSMonthly   * 12);
        i.womanSSAmount      = (int) Math.round(i.womanSSMonthly * 12);
        i.annuity            = iv(spAnnuity);
        i.annuityStartYear   = iv(spAnnuityStartYear);
        i.annuityStartMonth  = iv(spAnnuityStartMonth);
        i.manTradIRA     = iv(spManTradIRA);
        i.manRothIRA     = iv(spManRothIRA);
        i.manTrad401K    = iv(spManTrad401K);
        i.manRoth401K    = iv(spManRoth401K);
        i.womanRoth401K  = iv(spWomanRoth401K);
        i.womanRothIRA   = iv(spWomanRothIRA);
        i.womanTradIRA   = iv(spWomanTradIRA);
        i.womanTrad401K  = iv(spWomanTrad401K);
        i.manPlanAge     = iv(spManPlanAge);
        i.womanPlanAge   = iv(spWomanPlanAge);
        i.portfolio      = i.manTradIRA + i.manRothIRA + i.manTrad401K + i.manRoth401K
                + i.womanRoth401K + i.womanRothIRA + i.womanTradIRA + i.womanTrad401K;
        i.nomReturn          = dv(spNomReturn)       / 100.0;
        i.stdDev             = dv(spStdDev)          / 100.0;
        i.inflation          = dv(spInflation)       / 100.0;
        i.inflationStdDev    = dv(spInflationStdDev) / 100.0;
        i.livingExp          = iv(spLivingExp);
        i.medical            = iv(spMedical);
        i.medInflation       = dv(spMedInflation)    / 100.0;
        i.baseTax            = iv(spBaseTax);
        i.taxInflation       = dv(spTaxInflation)    / 100.0;
        i.goGoMultiplier     = dv(spGoGo);
        i.goGoDuration       = iv(spGoGoDuration);
        i.upperGuardrail     = dv(spUpperGuardrail)  / 100.0;
        i.lowerGuardrail     = dv(spLowerGuardrail)  / 100.0;
        i.manAge             = computeAge(i.manBirthYear,   i.manBirthMonth);
        i.womanAge           = computeAge(i.womanBirthYear, i.womanBirthMonth);
        i.currentAge         = i.manAge;
        return i;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ENHANCED PRO SIMULATION ENGINE
    //  1. True stochastic median: runs fan paths first, reads 50th-pct balance
    //  2. Annual re-solve inside trial paths via depth-8 inner binary search
    // ════════════════════════════════════════════════════════════════════════
    private ProResults simulatePro(SimInputs inp, long seed,
                                   int solvePaths, int fanPaths, int binIters) {
        ProResults res   = new ProResults();
        res.inp          = inp;
        res.medianRows   = new ArrayList<>();

        int startY = inp.withdrawStartYear - inp.baseYear;

        // ── Step 1: Run all fan paths ─────────────────────────────────────
        res.fanBalances    = new double[fanPaths][inp.horizon + 1];
        res.fanWithdrawals = new double[fanPaths][inp.horizon];
        res.fanInflFactors = new double[fanPaths][inp.horizon + 1];

        double[][] fpManTrad    = new double[fanPaths][inp.horizon + 1];
        double[][] fpManT401K   = new double[fanPaths][inp.horizon + 1];
        double[][] fpWomanTrad  = new double[fanPaths][inp.horizon + 1];
        double[][] fpWomanT401K = new double[fanPaths][inp.horizon + 1];

        for (int p = 0; p < fanPaths; p++) {
            SeededRng rng = new SeededRng(p * 17 + 11 + seed);
            double b  = inp.portfolio;
            double mt = inp.manTradIRA,   m4 = inp.manTrad401K;
            double wt = inp.womanTradIRA, w4 = inp.womanTrad401K;

            res.fanBalances[p][0]    = b;
            res.fanInflFactors[p][0] = 1.0;
            fpManTrad[p][0] = mt; fpManT401K[p][0] = m4;
            fpWomanTrad[p][0] = wt; fpWomanT401K[p][0] = w4;

            for (int y = 0; y < inp.horizon; y++) {
                int calYear  = inp.baseYear + y;
                int manAge   = calYear - inp.manBirthYear;
                int womanAge = calYear - inp.womanBirthYear;
                boolean drawing = calYear >= inp.withdrawStartYear;

                double ret  = inp.nomReturn + inp.stdDev * rng.nextGaussian();
                double infl = Math.max(0, inp.inflation + inp.inflationStdDev * rng.nextGaussian());
                res.fanInflFactors[p][y + 1] = res.fanInflFactors[p][y] * (1 + infl);

                int goGoRem = Math.max(0, inp.goGoDuration - Math.max(0, y - startY));
                int wd = 0;
                if (drawing && b > 0) {
                    wd = solveWithdrawalPro((int) b, calYear, inp.horizon - y,
                            inp, p * 1000L + y * 37 + seed,
                            Math.max(20, solvePaths / 8), Math.min(binIters, 10), goGoRem);
                }
                double mult   = (goGoRem > 0) ? inp.goGoMultiplier : 1.0;
                int wdActual  = drawing ? (int)(wd * mult) : 0;
                res.fanWithdrawals[p][y] = wdActual;

                b  = Math.max(0, b  * (1 + ret) - wdActual);
                mt = Math.max(0, mt * (1 + ret) - calcRmd(mt, manAge));
                m4 = Math.max(0, m4 * (1 + ret) - calcRmd(m4, manAge));
                wt = Math.max(0, wt * (1 + ret) - calcRmd(wt, womanAge));
                w4 = Math.max(0, w4 * (1 + ret) - calcRmd(w4, womanAge));

                res.fanBalances[p][y + 1] = b;
                fpManTrad[p][y + 1] = mt; fpManT401K[p][y + 1] = m4;
                fpWomanTrad[p][y + 1] = wt; fpWomanT401K[p][y + 1] = w4;
            }
        }

        // ── Step 2: Actual PoS ────────────────────────────────────────────
        int survived = 0;
        for (int p = 0; p < fanPaths; p++)
            if (res.fanBalances[p][inp.horizon] > 0) survived++;
        res.actualPoS    = survived / (double) fanPaths;
        res.fanPathCount = fanPaths;

        double[] finals = new double[fanPaths];
        for (int p = 0; p < fanPaths; p++) finals[p] = res.fanBalances[p][inp.horizon];
        Arrays.sort(finals);
        res.medianFinalBalance = (int) finals[fanPaths / 2];

        // ── Step 3: Year-1 withdrawal ─────────────────────────────────────
        int yr1Wd = solveWithdrawalPro(inp.portfolio, inp.baseYear, inp.horizon,
                inp, 999L + seed, solvePaths, binIters, inp.goGoDuration);
        res.yr1Withdrawal = yr1Wd;

        // ── Step 4: True stochastic median path ───────────────────────────
        for (int y = 0; y < inp.horizon; y++) {
            int calYear  = inp.baseYear + y;
            int manAge   = calYear - inp.manBirthYear;
            int womanAge = calYear - inp.womanBirthYear;
            boolean drawing = calYear >= inp.withdrawStartYear;

            // 50th-percentile balance at this year
            double[] balArr = new double[fanPaths];
            for (int p = 0; p < fanPaths; p++) balArr[p] = res.fanBalances[p][y];
            Arrays.sort(balArr);
            int medBal = (int) balArr[fanPaths / 2];

            // 50th-percentile traditional account balances (for RMD)
            double[] mtArr = new double[fanPaths], m4Arr = new double[fanPaths];
            double[] wtArr = new double[fanPaths], w4Arr = new double[fanPaths];
            for (int p = 0; p < fanPaths; p++) {
                mtArr[p] = fpManTrad[p][y];   m4Arr[p] = fpManT401K[p][y];
                wtArr[p] = fpWomanTrad[p][y]; w4Arr[p] = fpWomanT401K[p][y];
            }
            Arrays.sort(mtArr); Arrays.sort(m4Arr);
            Arrays.sort(wtArr); Arrays.sort(w4Arr);
            double medMt = mtArr[fanPaths/2], medM4 = m4Arr[fanPaths/2];
            double medWt = wtArr[fanPaths/2], medW4 = w4Arr[fanPaths/2];

            int goGoRem = Math.max(0, inp.goGoDuration - Math.max(0, y - startY));
            int wd = drawing && medBal > 0
                    ? solveWithdrawalPro(medBal, calYear, inp.horizon - y,
                    inp, 999L + y * 37 + seed, solvePaths, binIters, goGoRem)
                    : 0;

            double manRmd   = calcRmd(medMt, manAge) + calcRmd(medM4, manAge);
            double womanRmd = calcRmd(medWt, womanAge) + calcRmd(medW4, womanAge);
            double combRmd  = manRmd + womanRmd;

            double goGoMult  = (goGoRem > 0) ? inp.goGoMultiplier : 1.0;
            double startPror = (drawing && calYear == inp.withdrawStartYear)
                    ? (13.0 - inp.withdrawStartMonth) / 12.0 : 1.0;
            int wdActual   = drawing ? (int)(wd * goGoMult * startPror) : 0;
            int rmdOverage = drawing ? Math.max(0, (int) combRmd - wdActual) : 0;

            // Use true 50th-percentile inflation factor across all fan paths
            // (consistent with the median balance — both are now genuine 50th percentiles)
            double[] inflArr = new double[fanPaths];
            for (int p = 0; p < fanPaths; p++) inflArr[p] = res.fanInflFactors[p][y];
            Arrays.sort(inflArr);
            double inflFactor = inflArr[fanPaths / 2];

            double[] nextBalArr = new double[fanPaths];
            for (int p = 0; p < fanPaths; p++) nextBalArr[p] = res.fanBalances[p][y + 1];
            Arrays.sort(nextBalArr);
            int nextMedBal = (int) nextBalArr[fanPaths / 2];

            double manSS      = manSSThisYear(inp, y);
            double womanSS    = womanSSThisYear(inp, y);
            double ann        = annuityThisYear(inp, y);
            double guaranteed = manSS + womanSS + ann;
            double living     = drawing ? inp.livingExp * Math.pow(1 + inp.inflation, y) : 0;
            double medical    = drawing ? inp.medical   * Math.pow(1 + inp.medInflation, y) : 0;
            double tax        = taxThisYear(inp, y);
            double totalSpend = drawing ? living + medical + tax : 0;
            double totalIncome= guaranteed + wdActual;
            double surplus    = totalIncome - totalSpend;
            double wdPct      = (drawing && medBal > 0) ? wdActual / (double) medBal * 100.0 : 0;

            String alert = "—";
            if (drawing && yr1Wd > 0) {
                double vsYr1 = (wdActual - (int)(yr1Wd * goGoMult)) / (double)(yr1Wd * goGoMult);
                if      (vsYr1 >= inp.upperGuardrail)  alert = "▲ raise alert";
                else if (vsYr1 <= -inp.lowerGuardrail) alert = "▼ cut alert";
            }

            EnhRow row = new EnhRow();
            row.calYear       = calYear;
            row.manAge        = manAge;
            row.womanAge      = womanAge;
            row.balance       = medBal;
            row.withdrawal    = wd;
            row.wdActual      = wdActual;
            row.wdPct         = wdPct;
            row.manRmd        = (int) manRmd;
            row.womanRmd      = (int) womanRmd;
            row.combRmd       = (int) combRmd;
            row.rmdOverage    = rmdOverage;
            row.manSS         = (int) manSS;
            row.womanSS       = (int) womanSS;
            row.annuity       = (int) ann;
            row.guaranteed    = (int) guaranteed;
            row.living        = (int) living;
            row.medical       = (int) medical;
            row.tax           = (int) tax;
            row.totalSpend    = (int) totalSpend;
            row.totalIncome   = (int) totalIncome;
            row.surplus       = (int) surplus;
            row.inflFactor    = inflFactor;
            row.drawing       = drawing;
            row.goGoActive    = goGoRem > 0;
            row.alert         = alert;
            row.balDelta      = nextMedBal - medBal;
            row.investmentGrowth = (int)(medBal * inp.nomReturn);
            res.medianRows.add(row);
        }
        return res;
    }

    /**
     * Binary-search withdrawal at target PoS.
     * Each trial path re-solves the optimal withdrawal annually (depth-8 inner search).
     */
    private int solveWithdrawalPro(int balance, int fromYear, int horizon,
                                   SimInputs inp, long seed,
                                   int solvePaths, int binIters, int goGoYearsRemaining) {
        if (balance <= 0 || horizon <= 0) return 0;
        double lo = 0, hi = balance * 0.22;
        for (int i = 0; i < binIters; i++) {
            double mid = (lo + hi) / 2.0;
            double rate = survivalRatePro(balance, horizon, mid,
                    inp, seed + i * 31L, solvePaths, goGoYearsRemaining);
            if (rate > inp.targetPoS) lo = mid; else hi = mid;
        }
        long added   = (long) binIters * solvePaths;
        long running = simCount.addAndGet(added);
        long prevM   = (running - added) / 1_000_000;
        long currM   = running / 1_000_000;
        if (currM > prevM) {
            java.util.function.LongConsumer cb = simProgressCallback;
            if (cb != null) cb.accept(running);
        }
        return (int)((lo + hi) / 2.0);
    }

    /**
     * Survival rate for Pro solve: each trial path re-solves the locally-optimal
     * withdrawal every year via a depth-8 inner binary search, capturing
     * path-dependent sequence-of-returns adaptation.
     */
    private double survivalRatePro(int balance, int horizon, double firstYrWd,
                                   SimInputs inp, long seed, int solvePaths, int goGoYearsRemaining) {
        int survived = 0;
        for (int i = 0; i < solvePaths; i++) {
            SeededRng rng = new SeededRng(seed * 1000L + i * 7 + 3);
            double b = balance;

            for (int y = 0; y < horizon; y++) {
                double ret  = inp.nomReturn + inp.stdDev * rng.nextGaussian();
                double infl = Math.max(0, inp.inflation + inp.inflationStdDev * rng.nextGaussian());
                int goGoRem = Math.max(0, goGoYearsRemaining - y);
                double mult = (goGoRem > 0) ? inp.goGoMultiplier : 1.0;
                double spend = (b > 0) ? firstYrWd * mult * Math.pow(1 + infl, y) : 0;
                b = b * (1 + ret) - spend;
                if (b <= 0) break;
            }
            if (b > 0) survived++;
        }
        return survived / (double) solvePaths;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI UPDATE
    // ════════════════════════════════════════════════════════════════════════
    private void updateUI(ProResults res) {
        SimInputs inp = res.inp;
        int yr1   = res.yr1Withdrawal;
        double rate = yr1 / (double) inp.portfolio * 100.0;

        lblAnswer.setText(CURRENCY.format(yr1) + " / yr");
        lblSub.setText(String.format(
                "  %.2f%% of portfolio  ·  %.0f%% PoS target  ·  %d-year horizon  ·  true stochastic median",
                rate, inp.targetPoS * 100, inp.horizon));
        lblDetail.setText(String.format(
                "Man (age %d) · Woman (age %d) · Draws begin %02d/%d · "
                        + "%.2f%% nom return / %.2f%% inflation",
                inp.manAge, inp.womanAge,
                inp.withdrawStartMonth, inp.withdrawStartYear,
                inp.nomReturn * 100, inp.inflation * 100));

        double dEnd = !res.medianRows.isEmpty()
                ? res.medianRows.get(res.medianRows.size() - 1).inflFactor : 1.0;
        int yr10wd  = res.medianRows.size() >= 10 ? res.medianRows.get(9).wdActual : 0;
        double d10  = res.medianRows.size() >= 10 ? res.medianRows.get(9).inflFactor : 1.0;

        lblActualPoS.setText(String.format("%.1f%%", res.actualPoS * 100));
        lblMedianFinal.setText(showRealDollars
                ? formatMoney((long)(res.medianFinalBalance / dEnd)) + " (real)"
                : formatMoney(res.medianFinalBalance) + " (nom.)");
        lblYr10Wd.setText(showRealDollars
                ? CURRENCY.format((long)(yr10wd / d10)) : CURRENCY.format(yr10wd));
        lblInitRate.setText(String.format("%.2f%%", rate));

        // Populate table
        tblProModel.setRowCount(0);
        for (EnhRow r : res.medianRows) {
            double d = showRealDollars ? r.inflFactor : 1.0;
            tblProModel.addRow(new Object[]{
                    r.manAge,                                                              // 0
                    r.calYear,                                                             // 1
                    CURRENCY.format((long)(r.balance / d)),                                // 2
                    r.drawing ? CURRENCY.format((long)(r.withdrawal / d)) : "—",          // 3
                    r.drawing ? CURRENCY.format((long)(r.wdActual   / d)) : "—",          // 4
                    r.drawing ? String.format("%.2f%%", r.wdPct) : "—",                   // 5
                    r.alert,                                                               // 6
                    r.manSS   > 0 ? CURRENCY.format((long)(r.manSS   / d)) : "—",         // 7
                    r.womanSS > 0 ? CURRENCY.format((long)(r.womanSS / d)) : "—",         // 8
                    r.annuity > 0 ? CURRENCY.format((long)(r.annuity / d)) : "—",         // 9
                    r.guaranteed > 0 ? CURRENCY.format((long)(r.guaranteed / d)) : "—",  // 10
                    r.drawing ? CURRENCY.format((long)(r.living    / d)) : "—",           // 11
                    r.drawing ? CURRENCY.format((long)(r.medical   / d)) : "—",           // 12
                    r.drawing ? CURRENCY.format((long)(r.tax       / d)) : "—",           // 13
                    r.drawing ? CURRENCY.format((long)(r.totalSpend/ d)) : "—",           // 14
                    CURRENCY.format((long)(r.totalIncome / d)),                            // 15
                    r.drawing
                            ? (r.surplus >= 0 ? "+" : "-")
                              + CURRENCY.format((long)(Math.abs(r.surplus) / d)) : "—",   // 16
                    String.format("%.3f", r.inflFactor),                                  // 17
                    r.manRmd   > 0 ? CURRENCY.format((long)(r.manRmd   / d)) : "—",       // 18
                    r.womanRmd > 0 ? CURRENCY.format((long)(r.womanRmd / d)) : "—",       // 19
                    r.combRmd  > 0 ? CURRENCY.format((long)(r.combRmd  / d)) : "—",       // 20
                    r.rmdOverage > 0 ? CURRENCY.format((long)(r.rmdOverage / d)) : "—",   // 21
                    (r.balDelta >= 0 ? "+" : "-")
                            + CURRENCY.format((long)(Math.abs(r.balDelta) / d)),           // 22
            });
        }

        refreshChart();
        txaSummary.setText(buildSummary(res));
        txaSummary.setCaretPosition(0);
    }

    private void refreshChart() {
        if (lastResults == null || chartPanel == null) return;
        chartPanel.setData(lastResults, cmbChartType.getSelectedIndex(), showRealDollars);
        chartPanel.repaint();
    }

    private String buildSummary(ProResults res) {
        SimInputs inp = res.inp;
        int yr1 = res.yr1Withdrawal;
        EnhRow r1 = res.medianRows.stream().filter(r -> r.drawing).findFirst().orElse(null);
        int guar1 = r1 != null ? r1.guaranteed : 0;
        int spd1  = r1 != null ? r1.totalSpend : 0;
        int inc1  = yr1 + guar1;
        int sur1  = inc1 - spd1;
        int manRmdYear   = inp.manBirthYear   + RMD_START_AGE;
        int womanRmdYear = inp.womanBirthYear + RMD_START_AGE;

        String preDrawSection = "";
        if (inp.baseYear < inp.withdrawStartYear) {
            preDrawSection = String.format(
                    "══ YEAR %d — PRE-DRAW ══\n"
                            + "  No portfolio draws.\n"
                            + "  Man (age %d) · Woman (age %d)\n\n",
                    inp.baseYear, inp.manAge, inp.womanAge);
        }

        return preDrawSection + String.format(
                "══ INCOME LAB PRO — FIRST WITHDRAWAL YEAR (%d) ══\n"
                        + "  Portfolio withdrawal:  %s/yr  (%.2f%% of $%,.0f)\n"
                        + "  Method: true stochastic median · annual re-solve inside trial paths\n"
                        + "  + Guaranteed income:   %s\n"
                        + "  = Total income:        %s\n"
                        + "  − Total spending:      %s\n"
                        + "  → %s of %s\n\n"
                        + "══ SOCIAL SECURITY ══\n"
                        + "  Man: %s/yr from %02d/%d (age %d) · Woman: %s/yr from %02d/%d (age %d)\n"
                        + "  COLA %.1f%%/yr\n\n"
                        + "══ ANNUITY ══\n"
                        + "  %s/yr from %d (non-COLA)\n\n"
                        + "══ RMD SCHEDULE (SECURE 2.0 — age 75) ══\n"
                        + "  Man's trad IRA + 401K: %s · RMDs begin %d\n"
                        + "  Woman's trad IRA + 401K: %s · RMDs begin %d\n"
                        + "  Roth accounts (no RMD): %s\n\n"
                        + "══ SPENDING ══\n"
                        + "  Base tax %s in %d · Medical %s at %.1f%%/yr\n"
                        + "  Go-go multiplier: %.3f× for first %d years (through %d)\n\n"
                        + "══ MARKET ASSUMPTIONS ══\n"
                        + "  Return: %.2f%% / %.2f%% std dev · Inflation: %.2f%% / %.2f%% std dev\n\n"
                        + "══ RESULTS ══\n"
                        + "  Actual PoS: %.1f%% across %,d fan paths\n"
                        + "  Median final balance: %s\n"
                        + "  Horizon: %d years (to %d)",
                inp.withdrawStartYear,
                CURRENCY.format(yr1), yr1 / (double) inp.portfolio * 100, (double) inp.portfolio,
                CURRENCY.format(guar1), CURRENCY.format(inc1), CURRENCY.format(spd1),
                sur1 >= 0 ? "SURPLUS" : "GAP", CURRENCY.format(Math.abs(sur1)),
                CURRENCY.format(inp.manSSAmount), inp.manSSStartMonth, inp.manSSStartYear,
                inp.manAge + (inp.manSSStartYear - inp.baseYear),
                CURRENCY.format(inp.womanSSAmount), inp.womanSSStartMonth, inp.womanSSStartYear,
                inp.womanAge + (inp.womanSSStartYear - inp.baseYear),
                inp.ssCola * 100,
                CURRENCY.format(inp.annuity), inp.annuityStartYear,
                CURRENCY.format(inp.manTradIRA) + " + " + CURRENCY.format(inp.manTrad401K), manRmdYear,
                CURRENCY.format(inp.womanTradIRA) + " + " + CURRENCY.format(inp.womanTrad401K), womanRmdYear,
                CURRENCY.format(inp.manRothIRA) + " (man Roth IRA) + "
                        + CURRENCY.format(inp.manRoth401K) + " (man Roth 401K) + "
                        + CURRENCY.format(inp.womanRoth401K) + " (woman Roth 401K) + "
                        + CURRENCY.format(inp.womanRothIRA) + " (woman Roth IRA)",
                CURRENCY.format(inp.baseTax), inp.withdrawStartYear,
                CURRENCY.format(inp.medical), inp.medInflation * 100,
                inp.goGoMultiplier, inp.goGoDuration, inp.withdrawStartYear + inp.goGoDuration - 1,
                inp.nomReturn * 100, inp.stdDev * 100, inp.inflation * 100, inp.inflationStdDev * 100,
                res.actualPoS * 100, res.fanPathCount,
                formatMoney(res.medianFinalBalance),
                inp.horizon, inp.baseYear + inp.horizon);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SS / RMD / INCOME HELPERS
    // ════════════════════════════════════════════════════════════════════════
    private static int fraMonths(int birthYear) {
        if (birthYear <= 1954) return 66 * 12;
        if (birthYear == 1955) return 66 * 12 + 2;
        if (birthYear == 1956) return 66 * 12 + 4;
        if (birthYear == 1957) return 66 * 12 + 6;
        if (birthYear == 1958) return 66 * 12 + 8;
        if (birthYear == 1959) return 66 * 12 + 10;
        return 67 * 12;
    }

    private static double calcSSMonthlyBenefit(int pia,
                                               int birthYear, int birthMonth,
                                               int claimYear, int claimMonth) {
        if (pia <= 0) return 0;
        int ageMonths = (claimYear - birthYear) * 12 + (claimMonth - birthMonth);
        int fra       = fraMonths(birthYear);
        if (ageMonths <= fra) {
            int monthsEarly = fra - ageMonths;
            double reduction = monthsEarly <= 36
                    ? monthsEarly * (5.0 / 900.0)
                    : 36 * (5.0 / 900.0) + (monthsEarly - 36) * (5.0 / 1200.0);
            return pia * (1.0 - reduction);
        } else {
            int monthsLate = Math.min(ageMonths - fra, 70 * 12 - fra);
            return pia * (1.0 + monthsLate * (8.0 / 1200.0));
        }
    }

    private void updateSSBenefitNote() {
        int manPIA   = iv(spManPIA);   int manBY  = iv(spManBirthYear);  int manBM  = iv(spManBirthMonth);
        int manSY    = iv(spManSSStartYear);  int manSM  = iv(spManSSStartMonth);
        double manM  = calcSSMonthlyBenefit(manPIA,   manBY, manBM, manSY, manSM);
        int womanPIA = iv(spWomanPIA); int womBY  = iv(spWomanBirthYear);int womBM  = iv(spWomanBirthMonth);
        int womSY    = iv(spWomanSSStartYear);int womSM  = iv(spWomanSSStartMonth);
        double womM  = calcSSMonthlyBenefit(womanPIA, womBY, womBM, womSY, womSM);
        int manFra   = fraMonths(manBY); int womFra = fraMonths(womBY);
        int manAgeM  = (manSY - manBY)*12+(manSM-manBM);
        int womAgeM  = (womSY - womBY)*12+(womSM-womBM);
        String manAdj = manAgeM < manFra ? String.format("%.1f%% early", (1.0-manM/manPIA)*100)
                : manAgeM > manFra ? String.format("+%.1f%% delayed", (manM/manPIA-1.0)*100) : "at FRA";
        String womAdj = womAgeM < womFra ? String.format("%.1f%% early", (1.0-womM/womanPIA)*100)
                : womAgeM > womFra ? String.format("+%.1f%% delayed", (womM/womanPIA-1.0)*100) : "at FRA";
        lblSSBenefitNote.setText(String.format(
                "<html><i>Computed monthly: Man $%,.0f (%s) · Woman $%,.0f (%s)</i></html>",
                manM, manAdj, womM, womAdj));
    }

    private double manSSThisYear(SimInputs inp, int y) {
        int calYear = inp.baseYear + y;
        if (calYear < inp.manSSStartYear) return 0;
        if (calYear == inp.manSSStartYear)
            return inp.manSSAmount * (13.0 - inp.manSSStartMonth) / 12.0;
        return inp.manSSAmount * Math.pow(1 + inp.ssCola, calYear - inp.manSSStartYear);
    }

    private double womanSSThisYear(SimInputs inp, int y) {
        int calYear = inp.baseYear + y;
        if (calYear < inp.womanSSStartYear) return 0;
        if (calYear == inp.womanSSStartYear)
            return inp.womanSSAmount * (13.0 - inp.womanSSStartMonth) / 12.0;
        return inp.womanSSAmount * Math.pow(1 + inp.ssCola, calYear - inp.womanSSStartYear);
    }

    private double annuityThisYear(SimInputs inp, int y) {
        int calYear = inp.baseYear + y;
        if (calYear < inp.annuityStartYear) return 0;
        if (calYear == inp.annuityStartYear)
            return inp.annuity * (13.0 - inp.annuityStartMonth) / 12.0;
        return inp.annuity;
    }

    private double taxThisYear(SimInputs inp, int y) {
        int calYear = inp.baseYear + y;
        if (calYear < inp.withdrawStartYear) return 0;
        return inp.baseTax * Math.pow(1 + inp.taxInflation, calYear - inp.withdrawStartYear);
    }

    private double calcRmd(double tradBalance, int age) {
        if (age < RMD_START_AGE) return 0;
        Double factor = ULT.get(Math.min(age, 100));
        if (factor == null) factor = 6.4;
        return tradBalance / factor;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CHART PANEL
    // ════════════════════════════════════════════════════════════════════════
    static class ProChartPanel extends JPanel {
        private ProResults  data;
        private int         chartType   = 0;
        private boolean     realDollars = false;
        private static final Color[] PCTC = {
                new Color(99,153,34), new Color(55,138,221), new Color(186,117,23)
        };

        ProChartPanel() {
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(210,210,205)));
        }

        void setData(ProResults d, int type, boolean real) {
            this.data = d; this.chartType = type; this.realDollars = real;
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (data == null) return;
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            switch (chartType) {
                case 0 -> drawFan(g, true);   // balances
                case 1 -> drawFan(g, false);  // withdrawals
                case 2 -> drawHist(g);
            }
        }

        private Rectangle2D pa() { return new Rectangle2D.Double(74,22,getWidth()-140,getHeight()-60); }
        private double toY(Rectangle2D pa,double v,double mn,double mx){ return pa.getMaxY()-(v-mn)/(mx-mn)*pa.getHeight(); }
        private double toX(Rectangle2D pa,int yr,int tot){ return pa.getX()+yr/(double)tot*pa.getWidth(); }

        private void drawPath(Graphics2D g,Rectangle2D pa,double[]vals,int pts,double mn,double mx,int tot){
            Path2D path=new Path2D.Double(); boolean st=false;
            for(int i=0;i<pts;i++){
                double x=toX(pa,i,tot),y=toY(pa,Math.max(mn,Math.min(mx,vals[i])),mn,mx);
                if(!st){path.moveTo(x,y);st=true;}else path.lineTo(x,y);}
            g.draw(path);
        }

        private void grid(Graphics2D g,Rectangle2D pa){
            g.setColor(new Color(220,220,215)); g.setStroke(new BasicStroke(0.5f));
            for(int i=0;i<=6;i++){double y=pa.getY()+i/6.0*pa.getHeight();
                g.draw(new Line2D.Double(pa.getX(),y,pa.getMaxX(),y));}
            g.setColor(new Color(180,180,175)); g.setStroke(new BasicStroke(1f)); g.draw(pa);
        }

        private void axes(Graphics2D g,Rectangle2D pa,double mn,double mx,int xs,String yLbl){
            g.setFont(new Font("SansSerif",Font.PLAIN,12)); g.setColor(new Color(90,90,90));
            for(int i=0;i<=6;i++){
                double val=mn+(mx-mn)*(1-i/6.0),y=pa.getY()+i/6.0*pa.getHeight();
                String l=formatMoney((long)val); FontMetrics fm=g.getFontMetrics();
                g.drawString(l,(float)(pa.getX()-fm.stringWidth(l)-3),(float)(y+4));}
            for(int y=0;y<=xs;y+=5){double x=toX(pa,y,xs);
                g.drawString("Yr"+y,(float)(x-8),(float)(pa.getMaxY()+14));}
            String suffix=realDollars?" (real $)":" (nominal)";
            Graphics2D g2=(Graphics2D)g.create();
            g2.rotate(-Math.PI/2,11,pa.getCenterY());
            g2.setFont(new Font("SansSerif",Font.PLAIN,12)); g2.setColor(new Color(100,100,100));
            String full=yLbl+suffix; FontMetrics fm=g2.getFontMetrics();
            g2.drawString(full,(float)(11-fm.stringWidth(full)/2.0),(float)pa.getCenterY());
            g2.dispose();
        }

        private void drawFan(Graphics2D g, boolean balMode) {
            int yrs = data.inp.horizon;
            double[][] raw = balMode ? data.fanBalances : data.fanWithdrawals;
            int pts = balMode ? yrs + 1 : yrs;
            int nPaths = data.fanPathCount;
            double maxV = 0;
            for (double[] p : raw) for (double v : p) {
                double vd = realDollars ? v / Math.max(1, data.fanInflFactors[0][Math.min((int)(v/1e6), pts-1)]) : v;
                maxV = Math.max(maxV, vd);
            }
            maxV = Math.ceil(maxV / 100_000.0) * 100_000;
            Rectangle2D pa = pa(); grid(g, pa);
            int step = Math.max(1, nPaths / 60);
            for (int p = 0; p < nPaths; p += step) {
                boolean sv = data.fanBalances[p][yrs] > 0;
                g.setColor(sv ? new Color(29,158,117,50) : new Color(226,75,74,35));
                g.setStroke(new BasicStroke(1.0f));
                double[] vals = new double[pts];
                for (int y = 0; y < pts; y++) {
                    double v = raw[p][Math.min(y, raw[p].length-1)];
                    double f = realDollars ? Math.max(1, data.fanInflFactors[p][Math.min(y, data.fanInflFactors[p].length-1)]) : 1.0;
                    vals[y] = v / f;
                }
                drawPath(g, pa, vals, pts, 0, maxV, yrs);
            }
            String[] lbls = {"75th pct","Median","25th pct"};
            double[] pcts = {0.75, 0.50, 0.25};
            for (int pi = 0; pi < 3; pi++) {
                double[] pl = new double[pts];
                for (int y = 0; y < pts; y++) {
                    double[] vs = new double[nPaths];
                    for (int p = 0; p < nPaths; p++) {
                        double v = raw[p][Math.min(y, raw[p].length-1)];
                        double f = realDollars ? Math.max(1, data.fanInflFactors[p][Math.min(y, data.fanInflFactors[p].length-1)]) : 1.0;
                        vs[p] = v / f;
                    }
                    Arrays.sort(vs); pl[y] = vs[(int)(pcts[pi] * (nPaths - 1))];
                }
                g.setColor(PCTC[pi]); g.setStroke(new BasicStroke(2.2f));
                drawPath(g, pa, pl, pts, 0, maxV, yrs);
                g.setFont(new Font("SansSerif",Font.PLAIN,12));
                FontMetrics fmLbl = g.getFontMetrics();
                float lblX = (float)(toX(pa,pts-1,yrs)+6);
                float lblY = (float)toY(pa,pl[pts-1],0,maxV);
                // clamp label so it never runs past the panel edge
                float maxLblX = getWidth() - fmLbl.stringWidth(lbls[pi]) - 4;
                if (lblX > maxLblX) lblX = maxLblX;
                // clamp label vertically so it stays inside the plot area
                float minLblY = (float)pa.getY() + fmLbl.getAscent();
                float maxLblY = (float)pa.getMaxY();
                if (lblY < minLblY) lblY = minLblY;
                if (lblY > maxLblY) lblY = maxLblY;
                g.drawString(lbls[pi], lblX, lblY);
            }
            axes(g, pa, 0, maxV, yrs, balMode ? "Portfolio balance" : "Annual withdrawal");
        }

        private void drawHist(Graphics2D g) {
            int yrs = data.inp.horizon;
            int nPaths = data.fanPathCount;
            double[] fn = new double[nPaths];
            for (int p = 0; p < nPaths; p++) {
                double raw = data.fanBalances[p][yrs];
                double f   = realDollars ? Math.max(1, data.fanInflFactors[p][yrs]) : 1.0;
                fn[p] = raw / f;
            }
            Arrays.sort(fn);
            double maxV = fn[nPaths-1];
            int BINS = 16; double bw = Math.max(1, maxV / BINS);
            int[] cnt = new int[BINS]; int fail = 0;
            for (double v : fn) { if (v <= 0) { fail++; continue; } cnt[Math.min(BINS-1,(int)(v/bw))]++; }
            int maxC = Arrays.stream(cnt).max().orElse(1);
            Rectangle2D pa = pa(); grid(g, pa);
            double bwPx = pa.getWidth() / BINS * 0.85;
            for (int b = 0; b < BINS; b++) {
                double x = pa.getX() + b / (double)BINS * pa.getWidth();
                double h = cnt[b] / (double)maxC * pa.getHeight();
                g.setColor(new Color(29,158,117,160));
                g.fill(new Rectangle2D.Double(x, pa.getMaxY()-h, bwPx, h));
                g.setColor(new Color(18,120,90)); g.setStroke(new BasicStroke(0.8f));
                g.draw(new Rectangle2D.Double(x, pa.getMaxY()-h, bwPx, h));
            }
            g.setColor(new Color(163,45,45)); g.setFont(new Font("SansSerif",Font.BOLD,13));
            g.drawString(fail+" of "+nPaths+" failed ("
                            +String.format("%.0f%%",fail*100.0/nPaths)+")",
                    (float)(pa.getX()+4),(float)(pa.getY()+14));
            g.setFont(new Font("SansSerif",Font.PLAIN,12)); g.setColor(new Color(90,90,90));
            for (int i = 0; i <= 6; i++) {
                double val = maxC*(1-i/6.0), y = pa.getY()+i/6.0*pa.getHeight();
                String lbl = String.format("%,d",(int)val); FontMetrics fm = g.getFontMetrics();
                g.drawString(lbl,(float)(pa.getX()-fm.stringWidth(lbl)-3),(float)(y+4));
            }
            for (int b = 0; b <= BINS; b += 4) {
                double x = pa.getX()+b/(double)BINS*pa.getWidth();
                String lbl = formatMoney((long)(b*bw)); FontMetrics fm = g.getFontMetrics();
                g.drawString(lbl,(float)(x-fm.stringWidth(lbl)/2.0),(float)(pa.getMaxY()+14));
            }
            String xLbl = "Final portfolio balance"+(realDollars?" (real $)":" (nominal)");
            FontMetrics fmx = g.getFontMetrics();
            g.drawString(xLbl,(float)(pa.getCenterX()-fmx.stringWidth(xLbl)/2.0),(float)(pa.getMaxY()+28));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  RNG
    // ════════════════════════════════════════════════════════════════════════
    static class SeededRng {
        private long state;
        SeededRng(long seed) { this.state = seed ^ 0x6c62272e07bb0142L; }
        private double nextUniform() {
            state = state * 6364136223846793005L + 1442695040888963407L;
            long bits = (state >>> 33) ^ state;
            return (bits >>> 1) / (double) Long.MAX_VALUE;
        }
        double nextGaussian() {
            double u = Math.max(1e-12, nextUniform()), v = nextUniform();
            return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DATA CLASSES
    // ════════════════════════════════════════════════════════════════════════
    static class SimInputs {
        int baseYear, portfolio, horizon; double targetPoS;
        int withdrawStartYear, withdrawStartMonth;
        int manBirthYear, manBirthMonth, manAge;
        int womanBirthYear, womanBirthMonth, womanAge, currentAge;
        int manPIA, womanPIA;
        double manSSMonthly, womanSSMonthly;
        int manSSAmount, manSSStartYear, manSSStartMonth;
        int womanSSAmount, womanSSStartYear, womanSSStartMonth;
        double ssCola;
        int annuity, annuityStartYear, annuityStartMonth;
        int manTradIRA, manRothIRA, manTrad401K, manRoth401K;
        int womanRoth401K, womanRothIRA, womanTradIRA, womanTrad401K;
        int manPlanAge, womanPlanAge;
        double nomReturn, stdDev, inflation, inflationStdDev;
        int livingExp, medical; double medInflation;
        int baseTax; double taxInflation;
        double goGoMultiplier; int goGoDuration;
        double upperGuardrail, lowerGuardrail;
    }

    static class EnhRow {
        int  calYear, manAge, womanAge;
        int  balance, withdrawal, wdActual;
        double wdPct;
        int  manRmd, womanRmd, combRmd, rmdOverage;
        int  manSS, womanSS, annuity, guaranteed;
        int  living, medical, tax, totalSpend, totalIncome, surplus;
        double inflFactor;
        boolean drawing, goGoActive;
        String alert;
        int  balDelta, investmentGrowth;
    }

    static class ProResults {
        SimInputs inp;
        int yr1Withdrawal;
        List<EnhRow> medianRows;
        double[][] fanBalances, fanWithdrawals, fanInflFactors;
        double actualPoS;
        int medianFinalBalance, fanPathCount;
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
        titleLbl.setFont(new Font("SansSerif",Font.BOLD,12));
        titleLbl.setForeground(new Color(110,105,95));
        titleLbl.setBorder(BorderFactory.createEmptyBorder(0,0,6,0));
        titleLbl.setAlignmentX(LEFT_ALIGNMENT);
        card.add(titleLbl);
        for (int i = 0; i < items.length; i += 2) {
            Object labelObj = items[i]; Object comp = items[i+1];
            if (labelObj != null) {
                JLabel lbl = new JLabel((String) labelObj);
                lbl.setFont(new Font("SansSerif",Font.PLAIN,14));
                lbl.setForeground(new Color(75,75,75));
                lbl.setBorder(BorderFactory.createEmptyBorder(5,0,1,0));
                lbl.setAlignmentX(LEFT_ALIGNMENT); card.add(lbl);
            }
            JComponent row = (comp instanceof JSpinner sp) ? wrapSpinner(sp) : (JComponent) comp;
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            card.add(row);
        }
        return card;
    }

    private JPanel wrapSpinner(JSpinner sp) {
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE,28));
        sp.setPreferredSize(new Dimension(200,28));
        sp.setMinimumSize(new Dimension(100,28));
        JPanel p = new JPanel(new BorderLayout()); p.setOpaque(false);
        p.add(sp, BorderLayout.CENTER); return p;
    }

    private JSpinner spinI(int val, int min, int max, int step, String fmt) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(val,min,max,step));
        s.setEditor(new JSpinner.NumberEditor(s,fmt));
        s.setFont(new Font("SansSerif",Font.PLAIN,14)); return s;
    }

    private JSpinner spinD(double val, double min, double max, double step, String fmt) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(val,min,max,step));
        s.setEditor(new JSpinner.NumberEditor(s,fmt));
        s.setFont(new Font("SansSerif",Font.PLAIN,14)); return s;
    }

    private JLabel mkMetricLabel() {
        JLabel l = new JLabel("—");
        l.setFont(new Font("SansSerif",Font.BOLD,19));
        l.setForeground(new Color(30,30,30)); return l;
    }

    private JPanel wrapMetric(JLabel val, String title, String sub) {
        JPanel p = new JPanel(new BorderLayout(2,2));
        p.setBackground(new Color(240,240,237));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210,208,203),1),
                BorderFactory.createEmptyBorder(8,10,8,10)));
        JLabel t = new JLabel(title); t.setFont(new Font("SansSerif",Font.PLAIN,12)); t.setForeground(new Color(110,110,110));
        JLabel s = new JLabel(sub);   s.setFont(new Font("SansSerif",Font.PLAIN,12)); s.setForeground(new Color(140,140,140));
        p.add(t,BorderLayout.NORTH); p.add(val,BorderLayout.CENTER); p.add(s,BorderLayout.SOUTH);
        return p;
    }

    private static String formatMoney(long n) {
        if (Math.abs(n) >= 1_000_000) return String.format("$%.2fM", n / 1_000_000.0);
        if (Math.abs(n) >= 1_000)     return String.format("$%,dK",  n / 1_000);
        return "$" + n;
    }

    private int    iv(JSpinner s) { return ((Number) s.getValue()).intValue(); }
    private double dv(JSpinner s) { return ((Number) s.getValue()).doubleValue(); }

    private int computeAge(int birthYear, int birthMonth) {
        LocalDate today = LocalDate.now();
        LocalDate birth = LocalDate.of(
                Math.max(1900, Math.min(2100, birthYear)),
                Math.max(1,    Math.min(12,   birthMonth)), 1);
        return (int) java.time.temporal.ChronoUnit.YEARS.between(birth, today);
    }

    private void updateAgeLabels() {
        try {
            lblManAge.setText("Man age: "   + computeAge(iv(spManBirthYear),   iv(spManBirthMonth)));
            lblWomanAge.setText("Woman age: " + computeAge(iv(spWomanBirthYear), iv(spWomanBirthMonth)));
        } catch (Exception ignored) {}
    }

    private static double irsLifeExpectancy(int age) {
        double[] le = {
                31.6, 30.6, 29.7, 28.7, 27.8,  // 55-59
                26.8, 25.9, 25.0, 24.1, 23.2,  // 60-64
                22.3, 21.4, 20.5, 19.6, 18.8,  // 65-69
                18.0, 17.1, 16.3, 15.5, 14.8,  // 70-74
                14.0, 13.3, 12.6, 11.9, 11.2,  // 75-79
                10.6, 10.0,  9.4,  8.8,  8.3,  // 80-84
                7.8,  7.3,  6.8,  6.4,  6.0,  // 85-89
                5.6,  5.2,  4.9,  4.6,  4.3,  // 90-94
                4.0,  3.8,  3.6,  3.4,  3.1, 2.9 // 95-100
        };
        return le[Math.max(0, Math.min(age - 55, le.length - 1))];
    }

    private boolean planAgeDefaultsSet = false;
    private void updatePlanAgeDefaults() {
        if (planAgeDefaultsSet) return;
        try {
            int manAge   = computeAge(iv(spManBirthYear),   iv(spManBirthMonth));
            int womanAge = computeAge(iv(spWomanBirthYear), iv(spWomanBirthMonth));
            spManPlanAge  .setValue(Math.max(70, Math.min(110, manAge   + (int) Math.round(irsLifeExpectancy(manAge)))));
            spWomanPlanAge.setValue(Math.max(70, Math.min(110, womanAge + (int) Math.round(irsLifeExpectancy(womanAge)))));
            planAgeDefaultsSet = true;
        } catch (Exception ignored) {}
    }

    private void updateHorizonFromPlanAge() {
        try {
            int manAge    = computeAge(iv(spManBirthYear), iv(spManBirthMonth));
            int manPlan   = iv(spManPlanAge);
            int womanPlan = iv(spWomanPlanAge);
            int horizon   = Math.max(10, Math.min(50, Math.max(manPlan, womanPlan) - manAge));
            spHorizon.setValue(horizon);
            String driver = (manPlan >= womanPlan) ? "man to " + manPlan : "woman to " + womanPlan;
            if (lblHorizonNote != null)
                lblHorizonNote.setText("<html><i>Horizon = "
                        + horizon + " yrs (" + driver + ", man age " + manAge + ")</i></html>");
        } catch (Exception ignored) {}
    }

    private boolean distributing = false;
    private void updateAccountTotal() {
        try {
            long total = (long)iv(spManTradIRA) + iv(spManRothIRA) + iv(spManTrad401K) + iv(spManRoth401K)
                    + iv(spWomanRoth401K) + iv(spWomanRothIRA) + iv(spWomanTradIRA) + iv(spWomanTrad401K);
            lblAccountTotal.setText("Account total: " + CURRENCY.format(total));
            if (!distributing) {
                distributing = true;
                spPortfolio.setValue((int) Math.min(total, 20_000_000));
                distributing = false;
            }
        } catch (Exception ignored) {}
    }

    private void distributePortfolioDelta() {
        if (distributing) return;
        try {
            long newTotal = iv(spPortfolio);
            long oldTotal = (long)iv(spManTradIRA) + iv(spManRothIRA) + iv(spManTrad401K) + iv(spManRoth401K)
                    + iv(spWomanRoth401K) + iv(spWomanRothIRA) + iv(spWomanTradIRA) + iv(spWomanTrad401K);
            long delta = newTotal - oldTotal;
            if (delta == 0 || oldTotal == 0) return;
            distributing = true;
            JSpinner[] accts = {spManTradIRA, spManRothIRA, spManTrad401K, spManRoth401K,
                    spWomanRoth401K, spWomanRothIRA, spWomanTradIRA, spWomanTrad401K};
            double[] shares = new double[accts.length];
            for (int k = 0; k < accts.length; k++)
                shares[k] = iv(accts[k]) / (double) oldTotal;
            long remaining = delta; int largest = 0;
            for (int k = 0; k < accts.length; k++) {
                long add = Math.round(delta * shares[k]);
                long cur = iv(accts[k]);
                long nv  = Math.max(0, cur + add);
                accts[k].setValue((int) Math.min(nv, 10_000_000));
                remaining -= (nv - cur);
                if (shares[k] > shares[largest]) largest = k;
            }
            if (remaining != 0) {
                long cur = iv(accts[largest]);
                accts[largest].setValue((int) Math.min(Math.max(0, cur + remaining), 10_000_000));
            }
            long finalTotal = 0;
            for (JSpinner s : accts) finalTotal += iv(s);
            lblAccountTotal.setText("Account total: " + CURRENCY.format(finalTotal));
            distributing = false;
        } catch (Exception ignored) { distributing = false; }
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
            if (btnRun != null) btnRun.setToolTipText(String.format(
                    "<html><b>Estimated computation at current settings:</b><br>"
                            + "Fan: %,d paths × %d yrs × %d iters × %,d paths = <b>%,dM sims</b><br>"
                            + "Median: %d yrs × %d iters × %,d paths × avg %d remaining = <b>%,dM sims</b><br>"
                            + "Grand total: <b>~%,dM simulations</b></html>",
                    fanPaths, horizon, binIters, solvePaths, fanSims / 1_000_000,
                    horizon, binIters, solvePaths, (horizon + 1) / 2, medianSims / 1_000_000, totalM));
            return String.format("Running — 0 / ~%,dM simulations performed…", totalM);
        } catch (Exception e) { return "Running Monte Carlo…"; }
    }

    private void applyFonts(java.awt.Component root) {
        if (root == null) return;
        applyFontRecursive(root, fontDelta);
        if (root instanceof java.awt.Window w) { w.pack(); w.revalidate(); }
        rescaleTableRowHeight(tblPro);
    }

    private void applyFontRecursive(java.awt.Component c, int newDelta) {
        Font f = c.getFont();
        if (f != null) {
            Integer prevDelta = (c instanceof javax.swing.JComponent jc)
                    ? (Integer) jc.getClientProperty(FONT_DELTA_KEY) : null;
            if (prevDelta == null) prevDelta = 2;
            int newSize = Math.max(8, f.getSize() - prevDelta + newDelta);
            c.setFont(f.deriveFont((float) newSize));
            if (c instanceof javax.swing.JComponent jc) jc.putClientProperty(FONT_DELTA_KEY, newDelta);
        }
        if (c instanceof java.awt.Container ct)
            for (java.awt.Component child : ct.getComponents()) applyFontRecursive(child, newDelta);
    }

    private void rescaleTableRowHeight(JTable tbl) {
        if (tbl == null) return;
        tbl.setRowHeight(Math.max(16, 22 + fontDelta));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SCROLLABLE PANEL
    // ════════════════════════════════════════════════════════════════════════
    static class ScrollablePanel extends JPanel implements javax.swing.Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r,int o,int d){ return 20; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r,int o,int d){ return 60; }
        @Override public boolean getScrollableTracksViewportWidth()  { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  MAIN
    // ════════════════════════════════════════════════════════════════════════
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new IncomeLab_Pro();
        });
    }
}
