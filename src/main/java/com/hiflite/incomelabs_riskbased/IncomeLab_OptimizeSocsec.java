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
 * IncomeLab_OptimizeSocsec.java
 *
 * Income Lab Pro + Guyton-Klinger Withdrawal Simulator
 * (Enhanced stochastic engine with Guyton-Klinger Option C tab)
 *
 * == METHODOLOGY ===============================================================
 *
 *  1. TRUE STOCHASTIC MEDIAN PATH
 *     Runs all fan paths first (each path draws stochastic returns/inflation
 *     and re-solves withdrawal annually). The displayed table then reads the
 *     50th-percentile balance across all fan paths at each year -- not the
 *     mean-return path used in simplified tools.
 *
 *  2. ANNUAL RE-SOLVE INSIDE SOLVE TRIALS
 *     Each trial path in the binary-search solver re-solves the locally-optimal
 *     withdrawal every year via a depth-8 inner binary search, capturing
 *     path-dependent sequence-of-returns adaptation -- the true Income Lab engine.
 *
 *  3. COUPLE-AWARE SS / RMD
 *     Full SSA FRA schedule, early/delayed adjustments, SECURE 2.0 RMDs (age 75),
 *     seven-account decomposition (trad/Roth for both spouses).
 *
 *  Remaining gaps vs. full Income Lab spec:
 *    ? No asset allocation glide path (single return/stdDev for full horizon)
 *    ? No mortality weighting
 *    ? No tax drag / account-type modeling
 *
 * =============================================================================
 * Compile:  javac IncomeLab_OptimizeSocsec.java
 * Run:      java com.hiflite.incomelabs_riskbased.IncomeLab_OptimizeSocsec
 * Requires Java 11+. No external dependencies.
 */
public class IncomeLab_OptimizeSocsec extends JFrame {

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

    // == Font sizing ==========================================================
    private static final int BASE_FONT_SIZE = 12;
    private int fontDelta = 2;
    private JSpinner spFontDelta;
    private javax.swing.Timer fontDebounceTimer;
    private static final String FONT_DELTA_KEY = "app.fontDelta";

    // == Input spinners =======================================================
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

    // == SS Optimizer fields ================================================
    private JCheckBox  chkOptimize;                  // true = scan, false = manual
    private JLabel     lblOptStatus;
    private JButton    btnRunOpt;
    private JTable     tblOpt;
    private javax.swing.table.DefaultTableModel tblOptModel;
    private volatile boolean optCancelRequested = false;
    private JButton    btnCancelOpt;

    // == Output widgets =======================================================
    private JTabbedPane       mainTabs;  // direct ref for tab switching
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
    // Cache for SS Optimizer results -- repopulated when real/nominal toggle fires
    private java.util.List<SsOptResult> lastOptResults = null;
    private int lastOptManBY, lastOptManBM, lastOptWomanBY, lastOptWomanBM;
    private int lastOptManPIA, lastOptWomanPIA;
    private boolean optResultsStale = false;  // true if inputs changed after optimizer ran
    private final java.util.concurrent.atomic.AtomicLong simCount = new java.util.concurrent.atomic.AtomicLong(0);
    private          long simTotal    = 0;
    private volatile java.util.function.LongConsumer simProgressCallback = null;

    private static final NumberFormat CURRENCY = NumberFormat.getCurrencyInstance(Locale.US);
    static { CURRENCY.setMaximumFractionDigits(0); }

    // ========================================================================
    //  Constructor
    // ========================================================================

    // == Historical stress scenario =============================================
    private JComboBox<String> cmbScenario;

    // == Guyton-Klinger fields =================================================
    private JSpinner          spGkPreRate;
    private JTable            tblGk;
    private DefaultTableModel tblGkModel;
    private JLabel            lblGkInitWd, lblGkInitRate, lblGkFinalBal;

    public IncomeLab_OptimizeSocsec() {
        super("IncomeLab Optimize Socsec -- PoS + GK + Historical + SS Optimizer");
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

    // ========================================================================
    //  INPUT PANEL  (includes GK initial rate and guardrail spinners)
    // ========================================================================
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

        // == Appearance ====================================================
        spFontDelta = new JSpinner(new SpinnerNumberModel(2, -6, 6, 1));
        spFontDelta.setToolTipText("<html><b>Font size adjustment (pt)</b><br>"
                + "Shifts all application fonts by this many points relative to<br>"
                + "the Swing default. Default = +2. Range: ?4 to +4.<br>"
                + "Font update applies ~1 second after you stop adjusting.</html>");
        spFontDelta.setFont(new Font("SansSerif", Font.PLAIN, BASE_FONT_SIZE + fontDelta));
        fontDebounceTimer = new javax.swing.Timer(1000, e -> {
            fontDelta = (Integer) spFontDelta.getValue();
            applyFonts(SwingUtilities.getWindowAncestor(spFontDelta));
        });
        fontDebounceTimer.setRepeats(false);
        spFontDelta.addChangeListener(e -> fontDebounceTimer.restart());
        inner.add(card("Appearance", new Object[]{ "Font size adjustment (pt)", spFontDelta }));
        inner.add(Box.createVerticalStrut(4));

        // -- Scenario save / load card
        JTextField tfScenDesc = new JTextField("", 30);
        tfScenDesc.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tfScenDesc.setToolTipText("Short description used in filename when saving");

        JButton btnSave = new JButton("Save Scenario");
        JButton btnLoad = new JButton("Load Scenario");
        JComboBox<String> cmbRecent = new JComboBox<>();
        cmbRecent.setFont(new Font("SansSerif", Font.PLAIN, 12));
        cmbRecent.setToolTipText("Recently saved/loaded scenario files -- select to load");
        cmbRecent.addItem("-- recent files --");

        btnSave.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnSave.setBackground(new Color(60, 100, 160));
        btnSave.setForeground(Color.WHITE);
        btnSave.setFocusPainted(false);
        btnLoad.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnLoad.setBackground(new Color(80, 130, 60));
        btnLoad.setForeground(Color.WHITE);
        btnLoad.setFocusPainted(false);

        btnSave.addActionListener(e -> saveScenario(tfScenDesc));
        btnLoad.addActionListener(e -> loadScenario(tfScenDesc, cmbRecent));
        cmbRecent.addActionListener(e -> {
            int rIdx = cmbRecent.getSelectedIndex();
            if (rIdx > 0) {
                String rPath = (String) cmbRecent.getSelectedItem();
                if (rPath != null && !rPath.startsWith("--"))
                    loadScenarioFromFile(new java.io.File(rPath), tfScenDesc, cmbRecent);
            }
        });

        JPanel scenBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scenBtnRow.setOpaque(false); scenBtnRow.setAlignmentX(LEFT_ALIGNMENT);
        scenBtnRow.add(btnSave); scenBtnRow.add(btnLoad);

        JPanel scenSaveCard = new JPanel();
        scenSaveCard.setLayout(new BoxLayout(scenSaveCard, BoxLayout.Y_AXIS));
        scenSaveCard.setBackground(Color.WHITE);
        scenSaveCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0,0,6,0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(208,206,200),1),
                        BorderFactory.createEmptyBorder(8,10,8,10))));
        JLabel scenSaveTitleLbl = new JLabel("SCENARIO");
        scenSaveTitleLbl.setFont(new Font("SansSerif",Font.BOLD,12));
        scenSaveTitleLbl.setForeground(new Color(110,105,95));
        scenSaveTitleLbl.setBorder(BorderFactory.createEmptyBorder(0,0,6,0));
        scenSaveTitleLbl.setAlignmentX(LEFT_ALIGNMENT);
        scenSaveCard.add(scenSaveTitleLbl);
        JLabel descInputLbl = new JLabel("Description (used in filename)");
        descInputLbl.setFont(new Font("SansSerif",Font.PLAIN,13));
        descInputLbl.setForeground(new Color(75,75,75));
        descInputLbl.setBorder(BorderFactory.createEmptyBorder(4,0,1,0));
        descInputLbl.setAlignmentX(LEFT_ALIGNMENT);
        scenSaveCard.add(descInputLbl);
        tfScenDesc.setMaximumSize(new Dimension(Integer.MAX_VALUE,28));
        tfScenDesc.setAlignmentX(LEFT_ALIGNMENT);
        scenSaveCard.add(tfScenDesc);
        scenBtnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,32));
        scenSaveCard.add(Box.createVerticalStrut(4));
        scenSaveCard.add(scenBtnRow);
        JLabel recentFilesLbl = new JLabel("Recent files");
        recentFilesLbl.setFont(new Font("SansSerif",Font.PLAIN,13));
        recentFilesLbl.setForeground(new Color(75,75,75));
        recentFilesLbl.setBorder(BorderFactory.createEmptyBorder(6,0,1,0));
        recentFilesLbl.setAlignmentX(LEFT_ALIGNMENT);
        scenSaveCard.add(recentFilesLbl);
        cmbRecent.setMaximumSize(new Dimension(Integer.MAX_VALUE,28));
        cmbRecent.setAlignmentX(LEFT_ALIGNMENT);
        scenSaveCard.add(cmbRecent);
        inner.add(scenSaveCard);
        loadRecentFiles(cmbRecent);

        inner.add(Box.createVerticalStrut(4));

        // == Portfolio & Simulation =========================================
        int curYear = java.time.Year.now().getValue();
        spSimStartYear       = spinI(curYear, 2020, 2040, 1, "#");
        spSimStartYear.setToolTipText("<html><b>Simulation start year</b><br>"
                + "The calendar year used as year 0 of the simulation.<br>"
                + "All other year-based inputs (withdrawal start, SS start,<br>"
                + "annuity start) are interpreted relative to this base year.<br>"
                + "Changing this shifts the entire simulation timeline forward or back.</html>");
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
        chkRandomize.setToolTipText("<html><b>Re-randomize each run</b><br>"
                + "Checked: each run uses a different random seed -- natural MC variance.<br>"
                + "Unchecked: same seed every run -- identical results for same inputs.<br>"
                + "Use unchecked for scenario comparison; checked to explore uncertainty.</html>");
        chkRandomize.setFont(new Font("SansSerif", Font.PLAIN, 14));
        chkRandomize.setForeground(new Color(75, 75, 75));
        chkRandomize.setOpaque(false);
        chkRandomize.setAlignmentX(LEFT_ALIGNMENT);

        spMcSolvePaths = spinI(1000, 50, 5000, 50, "#,###");
        spMcSolvePaths.setToolTipText("<html><b>Monte Carlo solve paths</b><br>"
                + "Number of simulation paths per binary-search iteration<br>"
                + "used to find the target PoS withdrawal amount.<br><br>"
                + "<b>Default: 800</b> -- high accuracy.<br>"
                + "200 = ~4? faster, ~$500 variance. 100 = ~8? faster, ~$1,000 variance.<br>"
                + "Biggest single driver of total runtime.</html>");
        spBinaryIters  = spinI(25, 8, 30, 1, "#");
        spBinaryIters.setToolTipText("<html><b>Binary search iterations</b><br>"
                + "Number of iterations to narrow the withdrawal amount that<br>"
                + "achieves the target probability of success.<br><br>"
                + "<b>Default: 22</b> -- converges to within ~$1.<br>"
                + "16 = within ~$50. 12 = within ~$500.<br>"
                + "Smallest runtime impact of the three parameters.</html>");
        spMcFanPaths   = spinI(500, 20, 2000, 20, "#,###");
        spMcFanPaths.setToolTipText("<html><b>Fan chart paths</b><br>"
                + "Full simulation paths used to draw the fan chart and<br>"
                + "compute the actual PoS metric shown at the top.<br><br>"
                + "<b>Default: 400</b> -- smooth fan chart, stable PoS reading.<br>"
                + "100 = ~4? faster but noisier. 50 = rough but usable for quick checks.<br>"
                + "Each fan path re-solves withdrawal every year -- most expensive per path.</html>");

        // Mark optimizer results stale whenever any input changes
        ChangeListener markStale = e -> {
            if (lastOptResults != null) {
                optResultsStale = true;
                if (lblOptStatus != null)
                    lblOptStatus.setText("[Results may be stale -- inputs changed. Re-run SS Optimizer.]");
            }
        };

        ChangeListener refreshRunTooltip = e -> { updateRunTooltip(); markStale.stateChanged(null); };
        spMcSolvePaths.addChangeListener(refreshRunTooltip);
        spBinaryIters.addChangeListener(refreshRunTooltip);
        spMcFanPaths.addChangeListener(refreshRunTooltip);
        spHorizon.addChangeListener(refreshRunTooltip);

        spGkPreRate = spinD(4.0, 1.0, 10.0, 0.1, "0.0#");
        spGkPreRate.setToolTipText("<html><b>GK only -- initial withdrawal rate (%)</b><br>"
                + "Used exclusively by the Guyton-Klinger tab.<br>"
                + "Has no effect on the Income Lab PoS tab.<br><br>"
                + "In the first withdrawal year, this % of the current portfolio<br>"
                + "balance sets the GK withdrawal (prorated by start month).<br>"
                + "From year 2 onward, CPR\u25bc / PR\u25b2 / PMR\u2070 guardrail rules engage,<br>"
                + "using this rate as the permanent benchmark for all comparisons.<br><br>"
                + "<b>Default: 4.0%</b></html>");

        inner.add(card("Portfolio & Simulation", new Object[]{
                "Simulation start year",       spSimStartYear,
                "Target probability of success (%)", spTargetPoS,
                "Withdrawal start year",       spWithdrawStartYear,
                "Withdrawal start month",      spWithdrawStartMonth,
                null, chkRandomize,
                "MC solve paths",              spMcSolvePaths,
                "Binary search iterations",    spBinaryIters,
                "Fan chart paths",             spMcFanPaths,
                "GK only -- initial wd rate (%)", spGkPreRate,
        }));
        inner.add(Box.createVerticalStrut(4));

        // == People ========================================================
        spManBirthYear   = spinI(1961, 1940, 2000, 1, "#");
        spManBirthMonth  = spinI(9,    1,    12,   1, "#");
        spWomanBirthYear = spinI(1962, 1940, 2000, 1, "#");
        spWomanBirthMonth= spinI(12,   1,    12,   1, "#");
        spManPlanAge     = spinI(90, 70, 110, 1, "#");
        spWomanPlanAge   = spinI(92, 70, 110, 1, "#");
        lblHorizonNote   = new JLabel(" ");
        lblHorizonNote.setFont(new Font("SansSerif", Font.ITALIC, 12));
        lblHorizonNote.setForeground(new Color(100, 100, 100));
        lblManAge        = new JLabel("Man age: --");
        lblManAge.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblWomanAge      = new JLabel("Woman age: --");
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

        inner.add(card("People -- Birth Dates & Life Expectancy", new Object[]{
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

        // == Account Balances ==============================================
        spManTradIRA     = spinI(880_000,  0, 10_000_000, 1_000, "#,###");
        spManRothIRA     = spinI( 10_000,  0, 10_000_000, 1_000, "#,###");
        spManTrad401K    = spinI(      0,  0, 10_000_000, 1_000, "#,###");
        spManRoth401K    = spinI(      0,  0, 10_000_000, 1_000, "#,###");
        spWomanRoth401K  = spinI( 30_000,  0, 10_000_000, 1_000, "#,###");
        spWomanRothIRA   = spinI(      0,  0, 10_000_000, 1_000, "#,###");
        spWomanTradIRA   = spinI(266_000,  0, 10_000_000, 1_000, "#,###");
        spWomanTrad401K  = spinI(314_000,  0, 10_000_000, 1_000, "#,###");
        lblAccountTotal  = new JLabel("Account total: --");
        lblAccountTotal.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblAccountTotal.setForeground(new Color(40, 80, 40));

        ChangeListener acctListener = e -> updateAccountTotal();
        for (JSpinner sp : new JSpinner[]{ spManTradIRA, spManRothIRA, spManTrad401K, spManRoth401K,
                spWomanRoth401K, spWomanRothIRA, spWomanTradIRA, spWomanTrad401K })
            sp.addChangeListener(acctListener);

        inner.add(card("Account Balances (SECURE 2.0 RMD -- Age 75)", new Object[]{
                "Man -- Traditional IRA ($)  [RMD age 75]",    spManTradIRA,
                "Man -- Roth IRA ($)  [no RMD]",               spManRothIRA,
                "Man -- Traditional 401K ($)  [RMD age 75]",   spManTrad401K,
                "Man -- Roth 401K ($)  [no RMD]",              spManRoth401K,
                "Woman -- Roth 401K ($)  [no RMD]",            spWomanRoth401K,
                "Woman -- Roth IRA ($)  [no RMD]",             spWomanRothIRA,
                "Woman -- Traditional IRA ($)  [RMD age 75]",  spWomanTradIRA,
                "Woman -- Traditional 401K ($)  [RMD age 75]", spWomanTrad401K,
                null,                                          lblAccountTotal,
        }));
        inner.add(Box.createVerticalStrut(4));
        SwingUtilities.invokeLater(this::updateAccountTotal);

        // == Social Security ===============================================
        spManPIA           = spinI(3_788, 0, 6_000, 50, "#,###");
        spManPIA.setToolTipText("<html><b>Man's Primary Insurance Amount (PIA)</b><br>"
                + "Monthly SS benefit payable at Full Retirement Age (FRA).<br>"
                + "Found on your SSA statement at ssa.gov/myaccount.<br><br>"
                + "Reduced if claiming before FRA; increased if after FRA.<br>"
                + "FRA = 67 for those born 1960 or later.</html>");
        spManSSStartYear   = spinI(2027,  2020, 2040, 1, "#");
        spManSSStartMonth  = spinI(1,     1,    12,   1, "#");
        spWomanPIA         = spinI(3_897, 0, 6_000, 50, "#,###");
        spWomanPIA.setToolTipText("<html><b>Woman's Primary Insurance Amount (PIA)</b><br>"
                + "Monthly SS benefit payable at Full Retirement Age (FRA).<br>"
                + "Found on your SSA statement at ssa.gov/myaccount.<br><br>"
                + "Reduced if claiming before FRA; increased if after FRA.<br>"
                + "FRA = 67 for those born 1960 or later.</html>");
        spWomanSSStartYear = spinI(2026,  2020, 2040, 1, "#");
        spWomanSSStartMonth= spinI(12,    1,    12,   1, "#");
        spSSCola           = spinD(2.4,   0.0,  5.0,  0.1, "0.0#");
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

        // == Annuity =======================================================
        spAnnuity           = spinI(22_599, 0, 500_000, 500, "#,###");
        spAnnuityStartYear  = spinI(2028, 2020, 2040, 1, "#");
        spAnnuityStartMonth = spinI(4,    1,    12,   1, "#");
        inner.add(card("Annuity (non-COLA)", new Object[]{
                "Annual annuity income ($)",  spAnnuity,
                "Annuity start year",         spAnnuityStartYear,
                "Annuity start month",        spAnnuityStartMonth,
        }));
        inner.add(Box.createVerticalStrut(4));

        // == Market Assumptions ============================================
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

        // == Spending ======================================================
        spLivingExp    = spinI(105_000, 0, 500_000, 1_000, "#,###");
        spMedical      = spinI( 16_000, 0, 100_000,   500, "#,###");
        spMedInflation = spinD(4.5,     0.0, 15.0,   0.1,  "0.0#");
        spBaseTax      = spinI( 17_500, 0, 200_000, 1_000, "#,###");
        spTaxInflation = spinD(3.79,    0.0, 10.0,  0.01,  "0.00#");
        spGoGo         = spinD(1.300,   1.0,  2.0,  0.001, "0.000#");
        spGoGo.setToolTipText("<html><b>Common multiplier ranges:</b><br><br>"
                + "&nbsp;&nbsp;<b>1.2?&nbsp;(20% more)</b> -- Conservative; suitable if you already have<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                + "an active lifestyle baked into your baseline<br><br>"
                + "&nbsp;&nbsp;<b>1.3?&nbsp;(30% more)</b> -- The most commonly cited \"middle ground\"<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                + "in retirement planning literature<br><br>"
                + "&nbsp;&nbsp;<b>1.5?&nbsp;(50% more)</b> -- Used for people expecting significant travel,<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                + "bucket-list spending, or major lifestyle upgrades</html>");
        spGoGoDuration = spinI(10,      0,    20,    1,     "#");
        spGoGoDuration.setToolTipText("<html><b>Go-go years duration</b><br>"
                + "Number of years from withdrawal start that the go-go<br>"
                + "spending multiplier applies.<br><br>"
                + "<b>Default: 10 years</b> -- roughly covers ages 65-75 for a typical<br>"
                + "early retiree. Set to 0 to disable the go-go multiplier entirely.</html>");
        inner.add(card("Annual Spending (2027 Base $)", new Object[]{
                "Living expenses ($/yr)",             spLivingExp,
                "Medical ($/yr)",                     spMedical,
                "Medical inflation (%/yr)",           spMedInflation,
                "Base tax -- yr 1 ($/yr)",             spBaseTax,
                "Tax inflation (%/yr)",               spTaxInflation,
                "Go-go years multiplier",             spGoGo,
                "Go-go years duration (from wd start)", spGoGoDuration,
        }));
        inner.add(Box.createVerticalStrut(4));

        // == Guardrails ====================================================
        spUpperGuardrail = spinD(20.0, 5.0, 50.0, 1.0, "0.0#");
        spLowerGuardrail = spinD(20.0, 5.0, 50.0, 1.0, "0.0#");
        inner.add(card("Guardrails (advisory alerts)", new Object[]{
                "Upper guardrail (% above yr1, raise alert)", spUpperGuardrail,
                "Lower guardrail (% below yr1, cut alert)",   spLowerGuardrail,
        }));
        inner.add(Box.createVerticalStrut(4));

        // == Historical Stress Scenario card =====================================
        cmbScenario = new JComboBox<>(HistoricalScenarios.SCENARIO_NAMES);
        cmbScenario.setToolTipText(HistoricalScenarios.getDescription(0));
        cmbScenario.addActionListener(e -> {
            int idx = cmbScenario.getSelectedIndex();
            cmbScenario.setToolTipText(HistoricalScenarios.getDescription(idx));
        });

        // Build scenario card manually so JComboBox is not height-clamped to 30px
        JPanel cardScenario = new JPanel();
        cardScenario.setLayout(new BoxLayout(cardScenario, BoxLayout.Y_AXIS));
        cardScenario.setBackground(Color.WHITE);
        cardScenario.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0,0,6,0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(208,206,200),1),
                        BorderFactory.createEmptyBorder(8,10,8,10))));

        JLabel scenTitleLbl = new JLabel("HISTORICAL STRESS SCENARIO");
        scenTitleLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
        scenTitleLbl.setForeground(new Color(110,105,95));
        scenTitleLbl.setBorder(BorderFactory.createEmptyBorder(0,0,6,0));
        scenTitleLbl.setAlignmentX(LEFT_ALIGNMENT);
        cardScenario.add(scenTitleLbl);

        JLabel scenRowLbl = new JLabel("Sequence of returns");
        scenRowLbl.setFont(new Font("SansSerif", Font.PLAIN, 14));
        scenRowLbl.setForeground(new Color(75,75,75));
        scenRowLbl.setBorder(BorderFactory.createEmptyBorder(5,0,1,0));
        scenRowLbl.setAlignmentX(LEFT_ALIGNMENT);
        cardScenario.add(scenRowLbl);

        cmbScenario.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        cmbScenario.setAlignmentX(LEFT_ALIGNMENT);
        cardScenario.add(cmbScenario);

        JLabel scenarioNote = new JLabel(
                "<html><i>Historical years replay actual S&P 500 returns + CPI.<br>"
                        + "After sequence ends, reverts to random distribution.</i></html>");
        scenarioNote.setFont(new Font("SansSerif", Font.ITALIC, 11));
        scenarioNote.setForeground(new Color(90, 70, 10));
        scenarioNote.setBorder(BorderFactory.createEmptyBorder(4,0,0,0));
        scenarioNote.setAlignmentX(LEFT_ALIGNMENT);
        cardScenario.add(scenarioNote);

        inner.add(cardScenario);
        inner.add(Box.createVerticalStrut(4));

        // == Guardrails card (GK tab) ======================================
        spUpperGuardrail = spinD(20.0, 5.0, 60.0, 1.0, "0.0#");
        spUpperGuardrail.setToolTipText("<html><b>Upper guardrail -- Prosperity Rule (PR[^])</b><br>"
                + "If the GK withdrawal rate falls more than this % below the initial rate,<br>"
                + "the withdrawal is raised 10%.<br><b>Default: 20%</b></html>");
        spLowerGuardrail = spinD(20.0, 5.0, 60.0, 1.0, "0.0#");
        spLowerGuardrail.setToolTipText("<html><b>Lower guardrail -- Capital Preservation Rule (CPR[v])</b><br>"
                + "If the GK withdrawal rate rises more than this % above the initial rate,<br>"
                + "the withdrawal is cut 10%.<br><b>Default: 25%</b></html>");
        inner.add(card("Guardrail Alerts (GK tab)", new Object[]{
                "Upper guardrail -- raise alert (%)", spUpperGuardrail,
                "Lower guardrail -- cut alert (%)",   spLowerGuardrail,
        }));
        inner.add(Box.createVerticalStrut(4));

        // == Run button ====================================================
        btnRun = new JButton("  Run Simulation");
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

    // ========================================================================
    //  OUTPUT PANEL
    //  Single-panel layout: answer box -> metrics row -> tabbed (table/chart/summary)
    // ========================================================================
    private JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(new Color(245, 245, 242));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // == Answer box ===================================================
        JPanel answerBox = new JPanel(new BorderLayout(4, 4));
        answerBox.setBackground(new Color(230, 243, 255));
        answerBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(130, 190, 240), 1),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));

        lblAnswer = new JLabel("--");
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
            // Also refresh optimizer table if results are cached
            if (lastOptResults != null) populateOptTable(lastOptResults,
                    lastOptManBY, lastOptManBM, lastOptWomanBY, lastOptWomanBM,
                    lastOptManPIA, lastOptWomanPIA);
        });

        JPanel aNorth = new JPanel(new BorderLayout()); aNorth.setOpaque(false);
        JLabel aTitle = new JLabel(
                "Year 1 portfolio withdrawal -- true stochastic median, annual re-solve inside trial paths");
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

        // == Metrics row ==================================================
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

        // == Method badge =================================================
        JPanel badge = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 3));
        badge.setBackground(new Color(240, 250, 235));
        badge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(160, 210, 130), 1),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        for (String c : new String[]{
                "OK True stochastic median (50th pct of fan paths)",
                "OK Annual re-solve inside trial paths (seq-of-returns adaptive)",
                "OK SECURE 2.0 RMDs  OK Couple SS with FRA/early/delayed" }) {
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

        // == Tabs: table / chart / summary ================================
        mainTabs = new JTabbedPane();
        JTabbedPane tabs = mainTabs;
        tabs.setFont(new Font("SansSerif", Font.PLAIN, 14));
        tabs.addTab("Pro PoS Table",                buildTablePanel());
        tabs.addTab("Guyton-Klinger (Option C)",     buildGkTablePanel());
        tabs.addTab("Simulation Chart",              buildChartPanel());
        tabs.addTab("Summary",                       buildSummaryPanel());
        tabs.addTab("SS Optimizer",                  buildSsOptimizerPanel());

        panel.add(topSection, BorderLayout.NORTH);
        panel.add(tabs,       BorderLayout.CENTER);
        return panel;
    }

    // == Pro PoS Table =====================================================
    private JScrollPane buildTablePanel() {
        String[] cols = {
                "Man age", "Cal yr", "Portfolio bal (50th%)",           // 0 1 2
                "Pro PoS withdrawal", "Actual wd", "Wd %",              // 3 4 5
                "Alert",                                                  // 6
                "Man SS", "Woman SS", "Annuity", "Fixed Inc",            // 7 8 9 10
                "Living Exp", "Medical", "Tax (est)",                    // 11 12 13
                "Total spend", "Total income", "Surplus/gap",            // 14 15 16
                "Infl factor",                                           // 17
                "Man RMD", "Woman RMD", "Combined RMD", "-> Roth/MM",    // 18 19 20 21
                "Bal Chg"                                                   // 22
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
                    case 2 -> { return "<html><b>Portfolio balance -- true 50th percentile</b><br>"
                            + "The median of all " + lastResults.fanPathCount
                            + " stochastic fan paths at this year.<br>"
                            + "More conservative and accurate than a mean-return path.</html>"; }
                    case 3 -> { return "<html><b>Pro PoS withdrawal</b><br>"
                            + "Binary-search solved where each trial path re-solves<br>"
                            + "the optimal withdrawal <i>every year</i> (not fixed + inflated).<br>"
                            + "Captures path-dependent sequence-of-returns adaptation.</html>"; }
                    case 9 -> {
                        // Annuity -- fixed nominal, erodes in real terms
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format(
                                "<html><b>Annuity / pension income</b><br>"
                                        + "This is a <b>fixed nominal amount</b> -- it does not adjust for inflation.<br><br>"
                                        + "Its purchasing power erodes every year. At a 3.79%% inflation rate,<br>"
                                        + "it retains only about <b>67%% of its starting-year buying power</b><br>"
                                        + "after 10 years -- and roughly 50%% after 18 years.<br><br>"
                                        + "Toggle the <b>Real $</b> button (top right) to see this erosion<br>"
                                        + "directly: the annuity column will visibly shrink year over year<br>"
                                        + "while SS and portfolio withdrawals (which are inflation-adjusted)<br>"
                                        + "remain relatively stable in real-dollar terms.<br><br>"
                                        + "This year's nominal value: %s</html>",
                                CURRENCY.format((long)(er.annuity / d)));
                    }
                    case 4 -> {
                        // Actual wd -- show the go-go breakdown if active
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        String wdStr     = CURRENCY.format((long)(er.wdActual  / d));
                        String posWdStr  = CURRENCY.format((long)(er.withdrawal / d));
                        if (er.goGoActive) {
                            return String.format(
                                    "<html><b>Actual wd -- go-go years active</b><br>"
                                            + "&nbsp;&nbsp;Pro PoS withdrawal: %s<br>"
                                            + "&nbsp;&nbsp;? go-go multiplier:&nbsp;&nbsp;%.3f?<br>"
                                            + "&nbsp;&nbsp;= Actual wd:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;%s<br><br>"
                                            + "Go-go multiplier increases spending during your<br>"
                                            + "active early-retirement travel years.</html>",
                                    posWdStr, er.goGoMult, wdStr);
                        } else {
                            return "<html><b>Actual wd</b><br>"
                                    + "Go-go years have ended -- multiplier = 1.0?.<br>"
                                    + "Actual wd = Pro PoS withdrawal.</html>";
                        }
                    }
                    case 17 -> {
                        // Infl factor -- show what it means for this row's dollars
                        return String.format(
                                "<html><b>Inflation factor: %.3f</b><br>"
                                        + "Prices have risen %.1f%% since the simulation start year.<br><br>"
                                        + "To convert this year's nominal dollars to today's purchasing power,<br>"
                                        + "divide by %.3f -- or toggle the 'Real $' button above the table.<br><br>"
                                        + "Example: $100,000 nominal = %s in today's dollars.</html>",
                                er.inflFactor,
                                (er.inflFactor - 1.0) * 100.0,
                                er.inflFactor,
                                CURRENCY.format((long)(100_000 / er.inflFactor)));
                    }
                    case COL_ALERT -> {
                        if ("[^] raise alert".equals(er.alert))
                            return "<html><b>[^] Raise alert</b><br>"
                                    + "Re-solved withdrawal rose above upper guardrail threshold.<br>"
                                    + "Portfolio has grown; sustainable to spend more.</html>";
                        if ("[v] cut alert".equals(er.alert))
                            return "<html><b>[v] Cut alert</b><br>"
                                    + "Re-solved withdrawal fell below lower guardrail threshold.<br>"
                                    + "Consider reducing discretionary spending this year.</html>";
                        return null;
                    }
                    case COL_ROTH_MM -> {
                        if (er.rmdOverage <= 0) return null;
                        return "<html><b>RMD overage -> Roth/MM</b><br>"
                                + "Combined RMD (" + CURRENCY.format(er.combRmd) + ")<br>"
                                + "exceeds planned spending withdrawal.<br>"
                                + "Overage (" + CURRENCY.format(er.rmdOverage) + ") -> Roth/MM -- not spent.<br>"
                                + "This is an involuntary Roth conversion opportunity.</html>";
                    }
                    case COL_BAL_DELTA -> {
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format("<html><b>Portfolio change: %s%s</b><br>"
                                        + "&nbsp;&nbsp;Market growth:&nbsp;&nbsp;+%s<br>"
                                        + "&nbsp;&nbsp;Withdrawal:&nbsp;&nbsp;&nbsp;?%s</html>",
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

        // Header tooltips -- override getToolTipText directly for reliable per-column display
        JTableHeader hdr = new JTableHeader(tblPro.getColumnModel()) {
            @Override public String getToolTipText(MouseEvent e) {
                int col = columnAtPoint(e.getPoint());
                return switch (col) {
                    case 4  -> "<html><b>Actual wd -- spending withdrawal</b><br>"
                            + "= 80% PoS withdrawal ? go-go multiplier (if applicable).<br>"
                            + "This is the amount spent and deducted from the portfolio each year.<br>"
                            + "During go-go years: Actual wd = Pro PoS wd ? go-go multiplier.<br>"
                            + "After go-go years: Actual wd = Pro PoS wd (multiplier = 1.0).<br>"
                            + "RMD overage above this goes to Roth/MM, not spent.</html>";
                    case 5  -> "<html><b>Wd % -- effective withdrawal rate</b><br>"
                            + "= Actual wd ? portfolio balance.<br>"
                            + "Shows what percentage of the portfolio is being spent this year.<br>"
                            + "Hover individual cells for guardrail status.</html>";
                    case 17 -> "<html><b>Infl factor -- cumulative inflation multiplier</b><br>"
                            + "= the factor by which prices have risen since the simulation start year.<br><br>"
                            + "To convert a <i>future</i> dollar amount to <i>today's</i> purchasing power,<br>"
                            + "divide by this number (or toggle the 'Real $' button to do it automatically).<br><br>"
                            + "To convert a <i>today's</i> dollar amount to <i>that year's</i> nominal dollars,<br>"
                            + "multiply by this number.<br><br>"
                            + "Example: Infl factor = 1.450 in Year 10 means $1.00 today<br>"
                            + "costs $1.45 in that year -- or a $100K withdrawal is worth only $69K today.</html>";
                    case 18 -> "<html><b>Man RMD</b><br>"
                            + "Required Minimum Distribution from man's traditional IRA + 401K.<br>"
                            + "Begins age 75 (SECURE 2.0, born after 1960).</html>";
                    case 19 -> "<html><b>Woman RMD</b><br>"
                            + "Required Minimum Distribution from woman's traditional IRA + 401K.<br>"
                            + "Begins age 75 (SECURE 2.0, born after 1960).</html>";
                    case 20 -> "<html><b>Combined RMD</b><br>"
                            + "Sum of man + woman RMDs.<br>"
                            + "Orange = RMD exceeds planned withdrawal; overage -> Roth/MM.</html>";
                    case 21 -> "<html><b>-> Roth/MM -- RMD overage redirected</b><br>"
                            + "= max(0, Combined RMD ? Actual wd).<br>"
                            + "When RMD exceeds the planned spending withdrawal, the excess must<br>"
                            + "still be taken but is redirected to Roth/MM -- not spent.<br>"
                            + "This is effectively an involuntary Roth conversion opportunity.</html>";
                    case 22 -> "<html><b>Bal ? -- portfolio balance change</b><br>"
                            + "= end-of-year balance ? start-of-year balance.<br>"
                            + "= market growth ? spending withdrawal.<br>"
                            + "Green = portfolio grew . Red = portfolio shrank.</html>";
                    default -> null;
                };
            }
        };
        tblPro.setTableHeader(hdr);

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
                        if ("[^] raise alert".equals(er.alert)) c.setForeground(new Color(59, 109, 17));
                        else if ("[v] cut alert".equals(er.alert)) c.setForeground(new Color(163, 45, 45));
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

    // == Chart panel ======================================================
    private JPanel buildChartPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(0, 4));
        wrapper.setBackground(new Color(245, 245, 242));
        wrapper.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        ctrl.setBackground(new Color(245, 245, 242));
        ctrl.add(new JLabel("Chart type:"));
        cmbChartType = new JComboBox<>(new String[]{
                "Portfolio balance -- fan + percentiles",
                "Withdrawal $ -- fan + percentiles",
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

    // == Summary panel ====================================================
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


    // =========================================================================
    //  SS OPTIMIZER TAB
    //  Deterministic scan of all Bob x Jo SS claiming-age combinations.
    //  Uses fixed returns (user's nomReturn / inflation) -- same as the React
    //  optimizer -- fast enough to score all ~5,000+ combos in seconds.
    //  Click any row -> writes SS start dates to IL spinners -> runs IL sim.
    // =========================================================================
    private JPanel buildSsOptimizerPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(new Color(245, 245, 242));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // == Mode selector ==================================================
        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        modeRow.setBackground(new Color(230, 240, 255));
        modeRow.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(150, 190, 240), 1),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        chkOptimize = new JCheckBox("Optimize SS start dates (scan all combinations)", false);
        chkOptimize.setFont(new Font("SansSerif", Font.BOLD, 14));
        chkOptimize.setForeground(new Color(20, 60, 140));
        chkOptimize.setOpaque(false);
        chkOptimize.setToolTipText("<html><b>Checked:</b> Run the SS optimizer to find the best claiming ages.<br>"
                + "<b>Unchecked:</b> Use the SS start dates entered manually in the input panel.<br><br>"
                + "When unchecked, click Run Simulation on the Pro PoS / GK tabs as usual.</html>");

        JLabel modeNote = new JLabel(
                "  Unchecked = use manual SS dates from input panel  |  "
                        + "Checked = scan all Bob x Jo claiming-age combinations");
        modeNote.setFont(new Font("SansSerif", Font.ITALIC, 12));
        modeNote.setForeground(new Color(80, 80, 80));

        modeRow.add(chkOptimize);
        modeRow.add(modeNote);

        // == Optimizer controls =============================================
        JPanel ctrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        ctrlRow.setBackground(new Color(245, 245, 242));

        btnRunOpt = new JButton("Run SS Optimizer");
        btnRunOpt.setFont(new Font("SansSerif", Font.BOLD, 14));
        btnRunOpt.setBackground(new Color(24, 130, 80));
        btnRunOpt.setForeground(Color.WHITE);
        btnRunOpt.setFocusPainted(false);
        btnRunOpt.setEnabled(false);
        btnRunOpt.addActionListener(e -> runSsOptimizer());

        btnCancelOpt = new JButton("Cancel");
        btnCancelOpt.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnCancelOpt.setBackground(new Color(180, 40, 40));
        btnCancelOpt.setForeground(Color.WHITE);
        btnCancelOpt.setFocusPainted(false);
        btnCancelOpt.setEnabled(false);
        btnCancelOpt.addActionListener(e -> optCancelRequested = true);

        lblOptStatus = new JLabel(
                "Select 'Optimize SS start dates' above, then click Run SS Optimizer.");
        lblOptStatus.setFont(new Font("Monospaced", Font.PLAIN, 12));
        lblOptStatus.setForeground(new Color(60, 60, 60));

        chkOptimize.addActionListener(e -> {
            boolean opt = chkOptimize.isSelected();
            btnRunOpt.setEnabled(opt);
            // Gray out IL SS spinners when in optimizer mode
            for (JSpinner sp : new JSpinner[]{
                    spManSSStartYear, spManSSStartMonth,
                    spWomanSSStartYear, spWomanSSStartMonth}) {
                sp.setEnabled(!opt);
            }
            lblOptStatus.setText(opt
                    ? "Ready to scan. Click Run SS Optimizer."
                    : "Manual mode: enter SS start dates in the input panel.");
        });

        ctrlRow.add(btnRunOpt);
        ctrlRow.add(btnCancelOpt);
        ctrlRow.add(lblOptStatus);

        // == Results table ==================================================
        String[] optCols = {
                "Rank", "Bob SS Start", "Bob Age", "Bob Mo. ($)",
                "Jo SS Start",  "Jo Age",  "Jo Mo. ($)",
                "Combined SS/yr", "Total Inc Yr1", "Port Wd Yr1",
                "Init Rate %", "Go-Go Guar", "Proj Final Bal"
        };
        tblOptModel = new javax.swing.table.DefaultTableModel(optCols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        tblOpt = new JTable(tblOptModel);
        tblOpt.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tblOpt.setRowHeight(24);
        tblOpt.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        tblOpt.setGridColor(new Color(220, 220, 215));
        tblOpt.setShowGrid(true);
        tblOpt.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        tblOpt.setSelectionBackground(new Color(190, 220, 255));

        int[] optWidths = {40, 90, 60, 90, 90, 60, 90, 105, 105, 105, 75, 110, 115};
        for (int i = 0; i < optWidths.length && i < tblOpt.getColumnCount(); i++)
            tblOpt.getColumnModel().getColumn(i).setPreferredWidth(optWidths[i]);

        // Row coloring: gold top1, silver top2, bronze top3
        javax.swing.table.DefaultTableCellRenderer optRend = new javax.swing.table.DefaultTableCellRenderer() {
            final Color GOLD   = new Color(255, 245, 150);
            final Color SILVER = new Color(232, 232, 232);
            final Color BRONZE = new Color(245, 225, 200);
            @Override public java.awt.Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                java.awt.Component c = super.getTableCellRendererComponent(t,v,sel,foc,row,col);
                if (!sel) {
                    Object ro = tblOptModel.getValueAt(row, 0);
                    int rank = ro instanceof Integer ? (Integer)ro : 9999;
                    if      (rank == 1) c.setBackground(GOLD);
                    else if (rank == 2) c.setBackground(SILVER);
                    else if (rank == 3) c.setBackground(BRONZE);
                    else                c.setBackground(row%2==0 ? Color.WHITE : new Color(248,248,245));
                    c.setForeground(Color.BLACK);
                }
                ((JLabel)c).setHorizontalAlignment((col==1||col==4)?LEFT:RIGHT);
                return c;
            }
        };
        tblOpt.setDefaultRenderer(Object.class, optRend);

        // Click row -> apply SS dates to IL spinners and run IL
        tblOpt.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int row = tblOpt.rowAtPoint(e.getPoint());
                if (row < 0) return;
                // Read SS dates from parallel list (indexed by model row)
                if (row >= optRowDates.size()) return;
                int[] dates = optRowDates.get(row);
                applyAndRun(dates[0], dates[1], dates[2], dates[3]);
            }
        });

        JLabel clickHint = new JLabel(
                "  Click any row to apply those SS start dates to the IL simulation and run automatically."
                        + "  |  Optimizer money columns respond to the Real/Nominal toggle (top right).");
        clickHint.setFont(new Font("SansSerif", Font.ITALIC, 12));
        clickHint.setForeground(new Color(0, 80, 150));

        JPanel north = new JPanel(new BorderLayout(0, 4));
        north.setBackground(new Color(245, 245, 242));
        north.add(modeRow, BorderLayout.NORTH);
        north.add(ctrlRow, BorderLayout.CENTER);
        north.add(clickHint, BorderLayout.SOUTH);

        p.add(north,                   BorderLayout.NORTH);
        p.add(new JScrollPane(tblOpt), BorderLayout.CENTER);
        return p;
    }

    // == Apply SS dates and run IL simulation ================================
    private void applyAndRun(int bobYear, int bobMonth, int joYear, int joMonth) {
        spManSSStartYear.setValue(bobYear);
        spManSSStartMonth.setValue(bobMonth);
        spWomanSSStartYear.setValue(joYear);
        spWomanSSStartMonth.setValue(joMonth);
        updateSSBenefitNote();
        if (mainTabs != null) mainTabs.setSelectedIndex(0);
        runSimulation();
    }


    // == SS Optimizer scan engine ============================================
    private void runSsOptimizer() {
        btnRunOpt.setEnabled(false);
        btnCancelOpt.setEnabled(true);
        optCancelRequested = false;
        optResultsStale = false;
        tblOptModel.setRowCount(0);
        lblOptStatus.setText("Building candidate grid...");

        // Snapshot inputs
        final int manBY    = iv(spManBirthYear),   manBM    = iv(spManBirthMonth);
        final int womanBY  = iv(spWomanBirthYear), womanBM  = iv(spWomanBirthMonth);
        final int manPIA   = iv(spManPIA),          womanPIA = iv(spWomanPIA);
        final double ssCola= dv(spSSCola) / 100.0;
        final int annuity  = iv(spAnnuity);
        final int annSY    = iv(spAnnuityStartYear), annSM = iv(spAnnuityStartMonth);
        final int baseYear = iv(spSimStartYear);
        final int wdYear   = iv(spWithdrawStartYear), wdMonth = iv(spWithdrawStartMonth);
        final int horizon  = iv(spHorizon);
        final int portfolio= iv(spPortfolio);
        final double ret   = dv(spNomReturn) / 100.0;
        final double infl  = dv(spInflation) / 100.0;
        final double living= iv(spLivingExp);
        final double med   = iv(spMedical);
        final double medI  = dv(spMedInflation) / 100.0;
        final double baseTax = iv(spBaseTax);
        final double taxI  = dv(spTaxInflation) / 100.0;
        final double goGo  = dv(spGoGo);
        final int goGoDur  = iv(spGoGoDuration);

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override protected Void doInBackground() {
                publish("Scanning SS combinations...");

                // Build month ranges: today to age 70
                java.time.LocalDate today = java.time.LocalDate.now();
                int sy = today.getYear(), sm = today.getMonthValue();

                java.util.List<int[]> bobMonths = buildSsRange(manBY, manBM, sy, sm);
                java.util.List<int[]> joMonths  = buildSsRange(womanBY, womanBM, sy, sm);

                int total = bobMonths.size() * joMonths.size();
                publish(String.format("Scanning %,d combinations...", total));

                java.util.List<SsOptResult> results = new java.util.ArrayList<>();
                int done = 0;
                for (int[] bob : bobMonths) {
                    for (int[] jo : joMonths) {
                        if (optCancelRequested) break;
                        SsOptResult r = scoreCombination(
                                bob[0], bob[1], jo[0], jo[1],
                                manBY, manBM, womanBY, womanBM,
                                manPIA, womanPIA, ssCola,
                                annuity, annSY, annSM,
                                baseYear, wdYear, wdMonth, horizon,
                                portfolio, ret, infl, living, med, medI,
                                baseTax, taxI, goGo, goGoDur);
                        results.add(r);
                        done++;
                        if (done % 200 == 0) {
                            final int d = done, t = total;
                            publish(String.format("Scanned %,d / %,d  (%.1f%%)",
                                    d, t, d * 100.0 / t));
                        }
                    }
                    if (optCancelRequested) break;
                }

                // Sort by totalIncomeYr1 desc (most total income in first drawing year)
                // Primary: maximize total income across all go-go years
                // Secondary: maximize projected final balance (legacy)
                results.sort((a, b) -> {
                    int cmp = Double.compare(b.goGoTotalIncome, a.goGoTotalIncome);
                    if (cmp != 0) return cmp;
                    return Double.compare(b.projFinalBal, a.projFinalBal);
                });

                // Publish to table
                final java.util.List<SsOptResult> finalResults = results;
                SwingUtilities.invokeLater(() -> populateOptTable(finalResults,
                        manBY, manBM, womanBY, womanBM, manPIA, womanPIA));
                return null;
            }
            @Override protected void process(java.util.List<String> msgs) {
                if (!msgs.isEmpty()) lblOptStatus.setText(msgs.get(msgs.size()-1));
            }
            @Override protected void done() {
                btnRunOpt.setEnabled(true);
                btnCancelOpt.setEnabled(false);
                if (!optCancelRequested)
                    lblOptStatus.setText("Scan complete. Click any row to apply and run IL.");
                else
                    lblOptStatus.setText("Cancelled. Partial results shown.");
            }
        };
        worker.execute();
    }

    private java.util.List<int[]> buildSsRange(int birthYear, int birthMonth,
                                               int startYear, int startMonth) {
        java.util.List<int[]> result = new java.util.ArrayList<>();
        int endY = birthYear + 70, endM = birthMonth;
        int y = startYear, m = startMonth;
        while (y < endY || (y == endY && m <= endM)) {
            result.add(new int[]{y, m});
            if (++m > 12) { m = 1; y++; }
        }
        return result;
    }

    /** Deterministic year-by-year scoring (React logic ported to Java) */
    private SsOptResult scoreCombination(
            int bobY, int bobM, int joY, int joM,
            int manBY, int manBM, int womanBY, int womanBM,
            int manPIA, int womanPIA, double ssCola,
            int annuity, int annSY, int annSM,
            int baseYear, int wdYear, int wdMonth, int horizon,
            int portfolio, double ret, double infl,
            double living, double med, double medI,
            double baseTax, double taxI,
            double goGo, int goGoDur) {

        double bobMonthly   = calcSSMonthlyBenefit(manPIA,   manBY, manBM, bobY, bobM);
        double joMonthly    = calcSSMonthlyBenefit(womanPIA, womanBY, womanBM, joY, joM);
        double bobAnnual    = bobMonthly * 12;
        double joAnnual     = joMonthly  * 12;

        double bal      = portfolio;
        double inflAcc  = 1.0;  // starts at 1.0 for year 0, compounds each year
        double totalIncYr1  = 0;
        double portWdYr1    = 0;
        double goGoTotal    = 0;
        double inflAccYr1   = 1.0;
        int startY = wdYear - baseYear;

        for (int y = 0; y < horizon; y++) {
            int calYear  = baseYear + y;
            boolean draw = calYear >= wdYear;
            if (y > 0) inflAcc *= (1 + infl);  // compound from year 1 onward, not year 0

            // SS income with COLA
            double manSS = 0, womanSS = 0;
            if (calYear >= bobY) {
                double years = calYear - bobY;
                manSS = (calYear == bobY)
                        ? bobAnnual * (13.0 - bobM) / 12.0
                        : bobAnnual * Math.pow(1 + ssCola, years);
            }
            if (calYear >= joY) {
                double years = calYear - joY;
                womanSS = (calYear == joY)
                        ? joAnnual * (13.0 - joM) / 12.0
                        : joAnnual * Math.pow(1 + ssCola, years);
            }
            // Annuity
            double ann = 0;
            if (calYear >= annSY) {
                ann = (calYear == annSY) ? annuity * (13.0 - annSM) / 12.0 : annuity;
            }
            double guaranteed = manSS + womanSS + ann;

            // Spending
            int goGoRem = Math.max(0, goGoDur - Math.max(0, y - startY));
            double goGoMult = (goGoRem > 0) ? goGo : 1.0;
            double spendLiving = draw ? living * inflAcc * goGoMult : 0;
            double spendMed    = draw ? med * Math.pow(1 + medI, y) : 0;
            double spendTax    = draw ? baseTax * Math.pow(1 + taxI, y) : 0;
            double totalSpend  = spendLiving + spendMed + spendTax;

            // Portfolio draw: cover the gap
            double portDraw = draw ? Math.max(0, totalSpend - guaranteed) : 0;
            double totalInc = guaranteed + portDraw;

            if (draw && y == startY) {
                // Store nominal; display converts to real when needed
                totalIncYr1 = guaranteed + portDraw;
                portWdYr1   = portDraw;
                inflAccYr1  = inflAcc;
            }
            // Sum GUARANTEED income (SS + annuity) across go-go years.
            // This is the primary score: more guaranteed income during go-go = less portfolio draw.
            // Total income always equals max(totalSpend, guaranteed) which varies too little.
            if (draw && goGoRem > 0) goGoTotal += guaranteed;  // nominal; display converts

            bal = Math.max(0, bal * (1 + ret) - portDraw);
        }

        SsOptResult r = new SsOptResult();
        r.bobYear = bobY; r.bobMonth = bobM;
        r.joYear  = joY;  r.joMonth  = joM;
        r.bobMonthly    = bobMonthly;
        r.joMonthly     = joMonthly;
        r.combinedAnnual= bobAnnual + joAnnual;
        r.totalIncomeYr1= totalIncYr1;
        r.portWdYr1     = portWdYr1;
        r.projFinalBal  = bal;                 // nominal; display converts to real if needed
        r.inflAccFinal  = inflAcc;             // to convert projFinalBal to real
        r.goGoTotalIncome = goGoTotal;          // accumulated as real in the loop
        return r;
    }

    private void populateOptTable(java.util.List<SsOptResult> results,
                                  int manBY, int manBM, int womanBY, int womanBM,
                                  int manPIA, int womanPIA) {
        // Cache for real/nominal toggle refresh
        lastOptResults  = results;
        lastOptManBY    = manBY;  lastOptManBM   = manBM;
        lastOptWomanBY  = womanBY; lastOptWomanBM = womanBM;
        lastOptManPIA   = manPIA;  lastOptWomanPIA = womanPIA;

        tblOptModel.setRowCount(0);
        optRowDates.clear();
        int show = results.size();  // show all combinations
        for (int rank = 0; rank < show; rank++) {
            SsOptResult r = results.get(rank);
            int bobAgeM = (r.bobYear - manBY)*12   + (r.bobMonth - manBM);
            int joAgeM  = (r.joYear  - womanBY)*12 + (r.joMonth  - womanBM);
            int portfolio = iv(spPortfolio);
            double initRate = portfolio > 0 ? r.portWdYr1 / portfolio * 100.0 : 0;
            tblOptModel.addRow(new Object[]{
                    rank + 1,
                    String.format("%02d/%d", r.bobMonth, r.bobYear),
                    ssAgeStr(bobAgeM),
                    CURRENCY.format((long) r.bobMonthly),
                    String.format("%02d/%d", r.joMonth, r.joYear),
                    ssAgeStr(joAgeM),
                    CURRENCY.format((long) r.joMonthly),
                    CURRENCY.format((long) r.combinedAnnual),
                    CURRENCY.format((long)(r.totalIncomeYr1 / (showRealDollars && r.inflAccYr1 > 0 ? r.inflAccYr1 : 1.0))),
                    CURRENCY.format((long)(r.portWdYr1 / (showRealDollars && r.inflAccYr1 > 0 ? r.inflAccYr1 : 1.0))),
                    String.format("%.2f%%", initRate),
                    CURRENCY.format((long)(r.goGoTotalIncome / (showRealDollars && r.inflAccFinal > 0 ? r.inflAccFinal : 1.0))),
                    CURRENCY.format((long)(r.projFinalBal / (showRealDollars && r.inflAccFinal > 0 ? r.inflAccFinal : 1.0))),
            });
            optRowDates.add(new int[]{r.bobYear, r.bobMonth, r.joYear, r.joMonth});
        }
        lblOptStatus.setText(String.format(
                "Showing all %,d combinations ranked. Click any row to apply and run IL.",
                show));
    }

    private static String ssAgeStr(int totalMonths) {
        int y = totalMonths / 12, m = totalMonths % 12;
        return m == 0 ? y + "y" : y + "y" + m + "m";
    }

    // Parallel list: maps optimizer table row index to SS date array [bobY,bobM,joY,joM]
    private final java.util.List<int[]> optRowDates = new java.util.ArrayList<>();

    static class SsOptResult {
        int bobYear, bobMonth, joYear, joMonth;
        double bobMonthly, joMonthly, combinedAnnual;
        double totalIncomeYr1, portWdYr1, projFinalBal;
        double goGoTotalIncome;  // sum of guaranteed income across all go-go years
        double inflAccYr1   = 1.0;   // inflation factor at withdrawal start year (default 1=nominal)
        double inflAccFinal = 1.0;   // inflation factor at end of horizon (default 1=nominal)
    }


    // =========================================================================
    //  SCENARIO SAVE / LOAD
    // =========================================================================
    private static final String RECENT_PREFS_FILE = "recent.ilscen.prefs";
    private static final int    MAX_RECENT = 5;
    private static final String SCENARIO_VERSION = "1";

    private void saveScenario(javax.swing.JTextField tfDesc) {
        String desc = tfDesc.getText().trim();
        if (desc.isEmpty()) desc = "scenario";
        String date = java.time.LocalDate.now().toString();
        String safeName = desc.replaceAll("[^a-zA-Z0-9_-]", "_");
        String defaultName = date + "_" + safeName + ".ilscen";
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Scenario");
        fc.setSelectedFile(new java.io.File(defaultName));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "IncomeLab Scenario files (*.ilscen)", "ilscen"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.io.File file = fc.getSelectedFile();
        if (!file.getName().endsWith(".ilscen"))
            file = new java.io.File(file.getAbsolutePath() + ".ilscen");
        java.util.Properties props = new java.util.Properties();
        props.setProperty("scenario.version",       SCENARIO_VERSION);
        props.setProperty("scenario.description",   desc);
        props.setProperty("scenario.saved",         java.time.LocalDateTime.now().toString());
        props.setProperty("sim.startYear",          String.valueOf(iv(spSimStartYear)));
        props.setProperty("sim.withdrawStartYear",  String.valueOf(iv(spWithdrawStartYear)));
        props.setProperty("sim.withdrawStartMonth", String.valueOf(iv(spWithdrawStartMonth)));
        props.setProperty("sim.targetPoS",          String.valueOf(iv(spTargetPoS)));
        props.setProperty("man.birthYear",          String.valueOf(iv(spManBirthYear)));
        props.setProperty("man.birthMonth",         String.valueOf(iv(spManBirthMonth)));
        props.setProperty("man.planAge",            String.valueOf(iv(spManPlanAge)));
        props.setProperty("woman.birthYear",        String.valueOf(iv(spWomanBirthYear)));
        props.setProperty("woman.birthMonth",       String.valueOf(iv(spWomanBirthMonth)));
        props.setProperty("woman.planAge",          String.valueOf(iv(spWomanPlanAge)));
        props.setProperty("man.pia",                String.valueOf(iv(spManPIA)));
        props.setProperty("woman.pia",              String.valueOf(iv(spWomanPIA)));
        props.setProperty("ss.cola",                String.valueOf(dv(spSSCola)));
        props.setProperty("man.ssStartYear",        String.valueOf(iv(spManSSStartYear)));
        props.setProperty("man.ssStartMonth",       String.valueOf(iv(spManSSStartMonth)));
        props.setProperty("woman.ssStartYear",      String.valueOf(iv(spWomanSSStartYear)));
        props.setProperty("woman.ssStartMonth",     String.valueOf(iv(spWomanSSStartMonth)));
        props.setProperty("annuity.amount",         String.valueOf(iv(spAnnuity)));
        props.setProperty("annuity.startYear",      String.valueOf(iv(spAnnuityStartYear)));
        props.setProperty("annuity.startMonth",     String.valueOf(iv(spAnnuityStartMonth)));
        props.setProperty("man.tradIRA",            String.valueOf(iv(spManTradIRA)));
        props.setProperty("man.rothIRA",            String.valueOf(iv(spManRothIRA)));
        props.setProperty("man.trad401K",           String.valueOf(iv(spManTrad401K)));
        props.setProperty("man.roth401K",           String.valueOf(iv(spManRoth401K)));
        props.setProperty("woman.roth401K",         String.valueOf(iv(spWomanRoth401K)));
        props.setProperty("woman.rothIRA",          String.valueOf(iv(spWomanRothIRA)));
        props.setProperty("woman.tradIRA",          String.valueOf(iv(spWomanTradIRA)));
        props.setProperty("woman.trad401K",         String.valueOf(iv(spWomanTrad401K)));
        props.setProperty("market.nomReturn",       String.valueOf(dv(spNomReturn)));
        props.setProperty("market.stdDev",          String.valueOf(dv(spStdDev)));
        props.setProperty("market.inflation",       String.valueOf(dv(spInflation)));
        props.setProperty("market.inflStdDev",      String.valueOf(dv(spInflationStdDev)));
        props.setProperty("spending.base",          String.valueOf(iv(spLivingExp)));
        props.setProperty("spending.goGo",          String.valueOf(dv(spGoGo)));
        props.setProperty("spending.goGoDuration",  String.valueOf(iv(spGoGoDuration)));
        props.setProperty("spending.medical",       String.valueOf(iv(spMedical)));
        props.setProperty("spending.medInflation",  String.valueOf(dv(spMedInflation)));
        props.setProperty("spending.baseTax",       String.valueOf(iv(spBaseTax)));
        props.setProperty("spending.taxInflation",  String.valueOf(dv(spTaxInflation)));
        props.setProperty("mc.solvePaths",          String.valueOf(iv(spMcSolvePaths)));
        props.setProperty("mc.binIters",            String.valueOf(iv(spBinaryIters)));
        props.setProperty("mc.fanPaths",            String.valueOf(iv(spMcFanPaths)));
        props.setProperty("opt.scenario",           String.valueOf(
                cmbScenario != null ? cmbScenario.getSelectedIndex() : 0));
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            props.store(fos, "IncomeLab_OptimizeSocsec scenario -- " + desc);
            addRecentFile(file.getAbsolutePath());
            if (progressBar != null)
                progressBar.setString("Saved: " + file.getName() + " -- " + desc);
        } catch (java.io.IOException ex) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Could not save scenario:\n" + ex.getMessage(),
                    "Save Error", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadScenario(javax.swing.JTextField tfDesc, JComboBox<String> cmbRecent) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Load Scenario");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "IncomeLab Scenario files (*.ilscen)", "ilscen"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        loadScenarioFromFile(fc.getSelectedFile(), tfDesc, cmbRecent);
    }

    private void loadScenarioFromFile(java.io.File file,
                                      javax.swing.JTextField tfDesc,
                                      JComboBox<String> cmbRecent) {
        java.util.Properties props = new java.util.Properties();
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            props.load(fis);
        } catch (java.io.IOException ex) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Could not read scenario file:\n" + ex.getMessage(),
                    "Load Error", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        java.util.List<String> warnings = new java.util.ArrayList<>();
        String ver = props.getProperty("scenario.version", "1");
        if (!SCENARIO_VERSION.equals(ver))
            warnings.add("version mismatch (file=" + ver + " app=" + SCENARIO_VERSION + ")");

        setSpinnerI(spSimStartYear,       props, "sim.startYear",          warnings);
        setSpinnerI(spWithdrawStartYear,  props, "sim.withdrawStartYear",  warnings);
        setSpinnerI(spWithdrawStartMonth, props, "sim.withdrawStartMonth", warnings);
        setSpinnerI(spTargetPoS,          props, "sim.targetPoS",          warnings);
        setSpinnerI(spManBirthYear,       props, "man.birthYear",          warnings);
        setSpinnerI(spManBirthMonth,      props, "man.birthMonth",         warnings);
        setSpinnerI(spManPlanAge,         props, "man.planAge",            warnings);
        setSpinnerI(spWomanBirthYear,     props, "woman.birthYear",        warnings);
        setSpinnerI(spWomanBirthMonth,    props, "woman.birthMonth",       warnings);
        setSpinnerI(spWomanPlanAge,       props, "woman.planAge",          warnings);
        setSpinnerI(spManPIA,             props, "man.pia",                warnings);
        setSpinnerI(spWomanPIA,           props, "woman.pia",              warnings);
        setSpinnerD(spSSCola,             props, "ss.cola",                warnings);
        setSpinnerI(spManSSStartYear,     props, "man.ssStartYear",        warnings);
        setSpinnerI(spManSSStartMonth,    props, "man.ssStartMonth",       warnings);
        setSpinnerI(spWomanSSStartYear,   props, "woman.ssStartYear",      warnings);
        setSpinnerI(spWomanSSStartMonth,  props, "woman.ssStartMonth",     warnings);
        setSpinnerI(spAnnuity,            props, "annuity.amount",         warnings);
        setSpinnerI(spAnnuityStartYear,   props, "annuity.startYear",      warnings);
        setSpinnerI(spAnnuityStartMonth,  props, "annuity.startMonth",     warnings);
        setSpinnerI(spManTradIRA,         props, "man.tradIRA",            warnings);
        setSpinnerI(spManRothIRA,         props, "man.rothIRA",            warnings);
        setSpinnerI(spManTrad401K,        props, "man.trad401K",           warnings);
        setSpinnerI(spManRoth401K,        props, "man.roth401K",           warnings);
        setSpinnerI(spWomanRoth401K,      props, "woman.roth401K",         warnings);
        setSpinnerI(spWomanRothIRA,       props, "woman.rothIRA",          warnings);
        setSpinnerI(spWomanTradIRA,       props, "woman.tradIRA",          warnings);
        setSpinnerI(spWomanTrad401K,      props, "woman.trad401K",         warnings);
        setSpinnerD(spNomReturn,          props, "market.nomReturn",       warnings);
        setSpinnerD(spStdDev,             props, "market.stdDev",          warnings);
        setSpinnerD(spInflation,          props, "market.inflation",       warnings);
        setSpinnerD(spInflationStdDev,    props, "market.inflStdDev",      warnings);
        setSpinnerI(spLivingExp,          props, "spending.base",          warnings);
        setSpinnerD(spGoGo,               props, "spending.goGo",          warnings);
        setSpinnerI(spGoGoDuration,       props, "spending.goGoDuration",  warnings);
        setSpinnerI(spMedical,            props, "spending.medical",       warnings);
        setSpinnerD(spMedInflation,       props, "spending.medInflation",  warnings);
        setSpinnerI(spBaseTax,            props, "spending.baseTax",       warnings);
        setSpinnerD(spTaxInflation,       props, "spending.taxInflation",  warnings);
        setSpinnerI(spMcSolvePaths,       props, "mc.solvePaths",          warnings);
        setSpinnerI(spBinaryIters,        props, "mc.binIters",            warnings);
        setSpinnerI(spMcFanPaths,         props, "mc.fanPaths",            warnings);
        if (cmbScenario != null) {
            String si = props.getProperty("opt.scenario");
            if (si != null) { try { cmbScenario.setSelectedIndex(Integer.parseInt(si.trim())); } catch (Exception ignored) {} }
        }
        String desc = props.getProperty("scenario.description", "");
        if (tfDesc != null) tfDesc.setText(desc);
        lastOptResults = null;
        if (tblOptModel != null) tblOptModel.setRowCount(0);
        if (lblOptStatus != null) lblOptStatus.setText("Scenario loaded. Review inputs, then Run Simulation or Run SS Optimizer.");
        updateAgeLabels();
        updateAccountTotal();
        updateSSBenefitNote();
        addRecentFile(file.getAbsolutePath());
        if (cmbRecent != null) loadRecentFiles(cmbRecent);
        String warnMsg = warnings.isEmpty() ? "" : "  [Warnings: " + String.join(", ", warnings) + "]";
        if (progressBar != null)
            progressBar.setString("Loaded: " + file.getName() + " -- " + desc + warnMsg);
    }

    private void setSpinnerI(JSpinner sp, java.util.Properties props,
                             String key, java.util.List<String> warnings) {
        String val = props.getProperty(key);
        if (val == null) { warnings.add("missing: " + key); return; }
        try {
            int v = Integer.parseInt(val.trim());
            SpinnerNumberModel m = (SpinnerNumberModel) sp.getModel();
            int lo = ((Number)m.getMinimum()).intValue(), hi = ((Number)m.getMaximum()).intValue();
            sp.setValue(Math.max(lo, Math.min(hi, v)));
        } catch (Exception e) { warnings.add("bad value for " + key + ": " + val); }
    }

    private void setSpinnerD(JSpinner sp, java.util.Properties props,
                             String key, java.util.List<String> warnings) {
        String val = props.getProperty(key);
        if (val == null) { warnings.add("missing: " + key); return; }
        try {
            double v = Double.parseDouble(val.trim());
            SpinnerNumberModel m = (SpinnerNumberModel) sp.getModel();
            double lo = ((Number)m.getMinimum()).doubleValue(), hi = ((Number)m.getMaximum()).doubleValue();
            sp.setValue(Math.max(lo, Math.min(hi, v)));
        } catch (Exception e) { warnings.add("bad value for " + key + ": " + val); }
    }

    private void loadRecentFiles(JComboBox<String> cmb) {
        cmb.removeAllItems();
        cmb.addItem("-- recent files --");
        java.io.File prefs = new java.io.File(RECENT_PREFS_FILE);
        if (!prefs.exists()) return;
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(prefs))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && new java.io.File(line).exists()) cmb.addItem(line);
            }
        } catch (Exception ignored) {}
    }

    private void addRecentFile(String path) {
        java.util.List<String> recent = new java.util.ArrayList<>();
        recent.add(path);
        java.io.File prefs = new java.io.File(RECENT_PREFS_FILE);
        if (prefs.exists()) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(prefs))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.equals(path)) recent.add(line);
                }
            } catch (Exception ignored) {}
        }
        if (recent.size() > MAX_RECENT) recent = recent.subList(0, MAX_RECENT);
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(prefs))) {
            for (String p : recent) pw.println(p);
        } catch (Exception ignored) {}
    }

    private JPanel buildStatusBar() {
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Ready -- click Run Simulation");
        progressBar.setPreferredSize(new Dimension(0, 22));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        p.setBackground(new Color(245, 245, 242));
        p.add(progressBar);
        return p;
    }

    // ========================================================================
    //  SIMULATION  (dispatches Enhanced Pro engine + Guyton-Klinger engine)
    // ========================================================================
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
                // Publish every solve-path batch. Swing's EDT coalesces rapid calls
                // naturally, so this is safe even on fast machines -- and ensures the
                // progress bar visibly moves on slower ones.
                final long batchSize = solvePaths;
                simProgressCallback = running -> {
                    if (running % batchSize < batchSize) publish(running);
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
                        "%,dM / ~%,dM simulations...", latest / 1_000_000, grandTotalM));
            }
            @Override protected void done() {
                try {
                    lastResults = get();
                    updateUI(lastResults);
                    progressBar.setValue(100);
                    progressBar.setString(String.format(
                            "Complete -- ~%,dM simulations . %,d fan paths . %,d solve paths . %d iters",
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
        i.upperGuardrail     = dv(spUpperGuardrail) / 100.0;
        i.lowerGuardrail     = dv(spLowerGuardrail) / 100.0;
        i.gkPreRate          = dv(spGkPreRate)       / 100.0;
        i.scenarioIndex      = cmbScenario != null ? cmbScenario.getSelectedIndex() : 0;
        i.upperGuardrail     = dv(spUpperGuardrail)  / 100.0;
        i.lowerGuardrail     = dv(spLowerGuardrail)  / 100.0;
        i.manAge             = computeAge(i.manBirthYear,   i.manBirthMonth);
        i.womanAge           = computeAge(i.womanBirthYear, i.womanBirthMonth);
        i.currentAge         = i.manAge;
        return i;
    }

    // ========================================================================
    //  ENHANCED PRO SIMULATION ENGINE
    //  1. True stochastic median: runs fan paths first, reads 50th-pct balance
    //  2. Annual re-solve inside trial paths via depth-8 inner binary search
    // ========================================================================
    private ProResults simulatePro(SimInputs inp, long seed,
                                   int solvePaths, int fanPaths, int binIters) {
        ProResults res   = new ProResults();
        res.inp          = inp;
        res.medianRows   = new ArrayList<>();

        int startY = inp.withdrawStartYear - inp.baseYear;

        // == Step 1: Run all fan paths =====================================
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

                double[] ri1 = getReturnAndInflation(inp, y, rng);
                double ret   = ri1[0];
                double infl  = ri1[1];
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

        // == Step 2: Actual PoS ============================================
        int survived = 0;
        for (int p = 0; p < fanPaths; p++)
            if (res.fanBalances[p][inp.horizon] > 0) survived++;
        res.actualPoS    = survived / (double) fanPaths;
        res.fanPathCount = fanPaths;

        double[] finals = new double[fanPaths];
        for (int p = 0; p < fanPaths; p++) finals[p] = res.fanBalances[p][inp.horizon];
        Arrays.sort(finals);
        res.medianFinalBalance = (int) finals[fanPaths / 2];

        // == Step 3: Year-1 withdrawal =====================================
        int yr1Wd = solveWithdrawalPro(inp.portfolio, inp.baseYear, inp.horizon,
                inp, 999L + seed, solvePaths, binIters, inp.goGoDuration);
        res.yr1Withdrawal = yr1Wd;

        // == Step 4: True stochastic median path ===========================
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
            // (consistent with the median balance -- both are now genuine 50th percentiles)
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
            // Use actual cumulative inflation factor (50th pct of fan paths) so that
            // historical stress scenarios deflate/inflate living expenses correctly
            double living     = drawing ? inp.livingExp * inflFactor : 0;
            double medical    = drawing ? inp.medical   * Math.pow(1 + inp.medInflation, y) : 0;
            double tax        = taxThisYear(inp, y);
            double totalSpend = drawing ? living + medical + tax : 0;
            double totalIncome= guaranteed + wdActual;
            double surplus    = totalIncome - totalSpend;
            double wdPct      = (drawing && medBal > 0) ? wdActual / (double) medBal * 100.0 : 0;

            String alert = "--";
            if (drawing && yr1Wd > 0) {
                double vsYr1 = (wdActual - (int)(yr1Wd * goGoMult)) / (double)(yr1Wd * goGoMult);
                if      (vsYr1 >= inp.upperGuardrail)  alert = "[^] raise alert";
                else if (vsYr1 <= -inp.lowerGuardrail) alert = "[v] cut alert";
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
            row.goGoMult      = goGoMult;
            row.alert         = alert;
            row.balDelta      = nextMedBal - medBal;
            row.investmentGrowth = (int)(medBal * inp.nomReturn);
            res.medianRows.add(row);
        }
        res.gkResults = simulateGK(inp);
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
                double[] ri2 = getReturnAndInflation(inp, y, rng);
                double ret   = ri2[0];
                double infl  = ri2[1];
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

    // ========================================================================
    //  UI UPDATE
    // ========================================================================
    private void updateUI(ProResults res) {
        SimInputs inp = res.inp;
        int yr1   = res.yr1Withdrawal;
        double rate = yr1 / (double) inp.portfolio * 100.0;

        lblAnswer.setText(CURRENCY.format(yr1) + " / yr");
        lblSub.setText(String.format(
                "  %.2f%% of portfolio  .  %.0f%% PoS target  .  %d-year horizon  .  true stochastic median",
                rate, inp.targetPoS * 100, inp.horizon));
        String scenLabel = inp.scenarioIndex > 0
                ? " . [Stress: " + HistoricalScenarios.SCENARIO_NAMES[inp.scenarioIndex].split(" \\(")[0] + "]"
                : "";
        lblDetail.setText(String.format(
                "Man (age %d) . Woman (age %d) . Draws begin %02d/%d . "
                        + "%.2f%% nom return / %.2f%% inflation%s",
                inp.manAge, inp.womanAge,
                inp.withdrawStartMonth, inp.withdrawStartYear,
                inp.nomReturn * 100, inp.inflation * 100, scenLabel));

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
                    r.drawing ? CURRENCY.format((long)(r.withdrawal / d)) : "--",          // 3
                    r.drawing ? CURRENCY.format((long)(r.wdActual   / d)) : "--",          // 4
                    r.drawing ? String.format("%.2f%%", r.wdPct) : "--",                   // 5
                    r.alert,                                                               // 6
                    r.manSS   > 0 ? CURRENCY.format((long)(r.manSS   / d)) : "--",         // 7
                    r.womanSS > 0 ? CURRENCY.format((long)(r.womanSS / d)) : "--",         // 8
                    r.annuity > 0 ? CURRENCY.format((long)(r.annuity / d)) : "--",         // 9
                    r.guaranteed > 0 ? CURRENCY.format((long)(r.guaranteed / d)) : "--",  // 10
                    r.drawing ? CURRENCY.format((long)(r.living    / d)) : "--",           // 11
                    r.drawing ? CURRENCY.format((long)(r.medical   / d)) : "--",           // 12
                    r.drawing ? CURRENCY.format((long)(r.tax       / d)) : "--",           // 13
                    r.drawing ? CURRENCY.format((long)(r.totalSpend/ d)) : "--",           // 14
                    CURRENCY.format((long)(r.totalIncome / d)),                            // 15
                    r.drawing
                            ? (r.surplus >= 0 ? "+" : "-")
                              + CURRENCY.format((long)(Math.abs(r.surplus) / d)) : "--",   // 16
                    String.format("%.3f", r.inflFactor),                                  // 17
                    r.manRmd   > 0 ? CURRENCY.format((long)(r.manRmd   / d)) : "--",       // 18
                    r.womanRmd > 0 ? CURRENCY.format((long)(r.womanRmd / d)) : "--",       // 19
                    r.combRmd  > 0 ? CURRENCY.format((long)(r.combRmd  / d)) : "--",       // 20
                    r.rmdOverage > 0 ? CURRENCY.format((long)(r.rmdOverage / d)) : "--",   // 21
                    (r.balDelta >= 0 ? "+" : "-")
                            + CURRENCY.format((long)(Math.abs(r.balDelta) / d)),           // 22
            });
        }

        updateGkTable(res);
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
                    "== YEAR %d -- PRE-DRAW ==\n"
                            + "  No portfolio draws.\n"
                            + "  Man (age %d) . Woman (age %d)\n\n",
                    inp.baseYear, inp.manAge, inp.womanAge);
        }

        return preDrawSection + String.format(
                "== INCOME LAB PRO -- FIRST WITHDRAWAL YEAR (%d) ==\n"
                        + "  Portfolio withdrawal:  %s/yr  (%.2f%% of $%,.0f)\n"
                        + "  Method: true stochastic median . annual re-solve inside trial paths\n"
                        + "  + Guaranteed income:   %s\n"
                        + "  = Total income:        %s\n"
                        + "  ? Total spending:      %s\n"
                        + "  -> %s of %s\n\n"
                        + "== SOCIAL SECURITY ==\n"
                        + "  Man: %s/yr from %02d/%d (age %d) . Woman: %s/yr from %02d/%d (age %d)\n"
                        + "  COLA %.1f%%/yr\n\n"
                        + "== ANNUITY ==\n"
                        + "  %s/yr from %d (non-COLA)\n\n"
                        + "== RMD SCHEDULE (SECURE 2.0 -- age 75) ==\n"
                        + "  Man's trad IRA + 401K: %s . RMDs begin %d\n"
                        + "  Woman's trad IRA + 401K: %s . RMDs begin %d\n"
                        + "  Roth accounts (no RMD): %s\n\n"
                        + "== SPENDING ==\n"
                        + "  Base tax %s in %d . Medical %s at %.1f%%/yr\n"
                        + "  Go-go multiplier: %.3f? for first %d years (through %d)\n\n"
                        + "== MARKET ASSUMPTIONS ==\n"
                        + "  Return: %.2f%% / %.2f%% std dev . Inflation: %.2f%% / %.2f%% std dev\n\n"
                        + "== RESULTS ==\n"
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

    // ========================================================================
    //  SS / RMD / INCOME HELPERS
    // ========================================================================
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
                "<html><i>Computed monthly: Man $%,.0f (%s) . Woman $%,.0f (%s)</i></html>",
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

    // ========================================================================
    //  GUYTON-KLINGER 2006 RULES ENGINE
    //  Deterministic (mean-return path). Three phases:
    //  Phase 0  -- pre-withdrawal: portfolio grows, no draws.
    //  Phase 1  -- first draw year: wdGK = balance ? gkPreRate ? proration.
    //  Phase 2  -- subsequent years: WRR / PMR / CPR / PR rules engage.
    // ========================================================================
    private GkResults simulateGK(SimInputs inp) {
        GkResults gk  = new GkResults();
        gk.inp        = inp;
        gk.rows       = new java.util.ArrayList<>();

        int startY           = inp.withdrawStartYear - inp.baseYear;
        double initialWdRate = inp.gkPreRate;
        gk.initialWdRate     = initialWdRate;
        gk.yr1Withdrawal     = 0;

        double bal            = inp.portfolio;
        double wdGK           = 0;
        boolean prevYearLoss  = false;
        double inflFactor     = 1.0; // updated each year from actual yearInfl

        double manTradIRA    = inp.manTradIRA;
        double manTrad401K   = inp.manTrad401K;
        double womanTradIRA  = inp.womanTradIRA;
        double womanTrad401K = inp.womanTrad401K;

        for (int y = 0; y < inp.horizon; y++) {
            int calYear   = inp.baseYear + y;
            int manAge    = calYear - inp.manBirthYear;
            int womanAge  = calYear - inp.womanBirthYear;
            boolean drawing       = calYear >= inp.withdrawStartYear;
            boolean firstDrawYear = calYear == inp.withdrawStartYear;

            int goGoRemaining = Math.max(0, inp.goGoDuration - Math.max(0, y - startY));
            double goGoMult   = (goGoRemaining > 0) ? inp.goGoMultiplier : 1.0;

            // Resolve this year's return and inflation from historical scenario or random mean
            double[][] histSeq = HistoricalScenarios.getSequence(inp.scenarioIndex);
            double yearReturn = (histSeq != null && y < histSeq.length)
                    ? histSeq[y][1] : inp.nomReturn;
            double yearInfl   = (histSeq != null && y < histSeq.length)
                    ? histSeq[y][2] : inp.inflation;

            // Cumulative inflation factor from actual per-year inflation
            if (y == 0) inflFactor = 1.0;
            else        inflFactor *= (1 + yearInfl);

            double manSS      = manSSThisYear(inp, y);
            double womanSS    = womanSSThisYear(inp, y);
            double ann        = annuityThisYear(inp, y);
            double guaranteed = manSS + womanSS + ann;
            double living     = drawing ? inp.livingExp   * inflFactor : 0;
            double medical    = drawing ? inp.medical     * Math.pow(1 + inp.medInflation, y) : 0;
            double tax        = taxThisYear(inp, y);
            double totalSpend = drawing ? living + medical + tax : 0;

            double wdStartProration = firstDrawYear
                    ? (13.0 - inp.withdrawStartMonth) / 12.0 : 1.0;

            String flags = "--";
            if (!drawing) {
                wdGK = 0;
            } else if (firstDrawYear) {
                wdGK = bal * inp.gkPreRate * wdStartProration;
                flags = String.format("%.1f%%", inp.gkPreRate * 100);
                gk.yr1Withdrawal = (int) wdGK;
            } else {
                boolean applyInflation = true;
                double currentWdPct = bal > 0 ? wdGK / bal : 0;
                if (prevYearLoss && currentWdPct > initialWdRate) {
                    applyInflation = false;
                    flags = "PMR\u2070";
                }
                if (applyInflation) wdGK *= (1 + yearInfl);

                double wdPctCheck = bal > 0 ? wdGK / bal : 0;
                if (wdPctCheck > initialWdRate * (1 + inp.lowerGuardrail)) {
                    wdGK *= 0.90;
                    flags = flags.equals("--") ? "CPR\u25bc" : flags + " + CPR\u25bc";
                }
                wdPctCheck = bal > 0 ? wdGK / bal : 0;
                if (wdPctCheck < initialWdRate * (1 - inp.upperGuardrail)) {
                    wdGK *= 1.10;
                    flags = flags.equals("--") ? "PR\u25b2" : flags + " + PR\u25b2";
                }
            }

            double manRmd   = calcRmd(manTradIRA,   manAge) + calcRmd(manTrad401K,  manAge);
            double womanRmd = calcRmd(womanTradIRA, womanAge) + calcRmd(womanTrad401K, womanAge);
            double combRmd  = manRmd + womanRmd;

            int wdActual   = drawing ? (int)(wdGK * goGoMult) : 0;
            int rmdOverage = drawing ? Math.max(0, (int) combRmd - wdActual) : 0;
            double wdPct   = (drawing && bal > 0) ? wdActual / (double) bal * 100.0 : 0.0;

            double totalIncome = guaranteed + wdActual;
            double surplus     = totalIncome - totalSpend;

            GkRow row = new GkRow();
            row.calYear = calYear; row.manAge = manAge; row.womanAge = womanAge;
            row.balance = (int) Math.max(0, bal);
            row.investmentGrowth = (int)(bal * yearReturn);
            row.wdGK = (int) wdGK; row.wdActual = wdActual;
            row.wdPct = wdPct; row.ruleFlags = flags;
            row.manSS = (int) manSS; row.womanSS = (int) womanSS;
            row.annuity = (int) ann; row.guaranteed = (int) guaranteed;
            row.living = (int) living; row.medical = (int) medical;
            row.tax = (int) tax; row.totalSpend = (int) totalSpend;
            row.totalIncome = (int) totalIncome; row.surplus = (int) surplus;
            row.inflFactor = inflFactor;
            row.manRmd = (int) manRmd; row.womanRmd = (int) womanRmd;
            row.combRmd = (int) combRmd; row.rmdOverage = rmdOverage;
            row.drawing = drawing; row.goGoActive = goGoRemaining > 0;
            row.preAnchor = false;

            double nextBal = Math.max(0, bal * (1 + yearReturn) - wdActual);
            row.balDelta   = (int)(nextBal - bal);
            prevYearLoss   = (nextBal < bal);
            bal            = nextBal;
            gk.rows.add(row);

            manTradIRA    = Math.max(0, manTradIRA   * (1 + yearReturn) - calcRmd(manTradIRA,   manAge));
            manTrad401K   = Math.max(0, manTrad401K  * (1 + yearReturn) - calcRmd(manTrad401K,  manAge));
            womanTradIRA  = Math.max(0, womanTradIRA * (1 + yearReturn) - calcRmd(womanTradIRA, womanAge));
            womanTrad401K = Math.max(0, womanTrad401K*(1 + yearReturn) - calcRmd(womanTrad401K, womanAge));
        }

        gk.finalBalance = gk.rows.isEmpty() ? 0
                : gk.rows.get(gk.rows.size()-1).balance + gk.rows.get(gk.rows.size()-1).balDelta;
        return gk;
    }

    private JPanel buildGkTablePanel() {
        // GK column layout:
        // 0=ManAge 1=CalYr 2=PortBal 3=GK-Wd 4=ActualWd 5=WdPct
        // 6=RuleApplied(hidden) 7=RuleFlags
        // 8=ManSS 9=WomanSS 10=Annuity 11=Guaranteed
        // 12=Living 13=Medical 14=Tax
        // 15=TotalSpend 16=TotalIncome 17=SurplusGap
        // 18=InflFactor 19=ManRMD 20=WomanRMD 21=CombRMD 22=->Roth/MM 23=Bal?
        String[] gkCols = {
                "Man age", "Cal yr", "Portfolio bal",                        // 0 1 2
                "GK withdrawal", "Actual wd", "Wd %",                       // 3 4 5
                "Rules (raw)",                                                // 6 hidden
                "Rule flags",                                                 // 7 visible
                "Man SS", "Woman SS", "Annuity", "Fixed Inc",                // 8 9 10 11
                "Living Exp", "Medical", "Tax (est)",                        // 12 13 14
                "Total spend", "Total income", "Surplus/gap",                // 15 16 17
                "Infl factor",                                                // 18
                "Man RMD", "Woman RMD", "Combined RMD", "-> Roth/MM",         // 19 20 21 22
                "Bal Chg"                                                        // 23
        };
        tblGkModel = new DefaultTableModel(gkCols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        tblGk = new JTable(tblGkModel) {
            @Override public String getToolTipText(MouseEvent e) {
                int col = columnAtPoint(e.getPoint());
                int row = rowAtPoint(e.getPoint());
                if (row < 0 || lastResults == null || lastResults.gkResults == null) return null;
                List<GkRow> rows = lastResults.gkResults.rows;
                if (row >= rows.size()) return null;
                GkRow gr = rows.get(row);

                if (col == 7) { // Rule flags
                    String raw = gr.ruleFlags;
                    if (raw == null || raw.equals("--")) return null;
                    StringBuilder sb = new StringBuilder("<html>");
                    // Pre-anchor: flags look like "4.0%" -- contains '%' but not a GK rule keyword
                    if (raw.endsWith("%") && !raw.contains("CPR") && !raw.contains("PR[^]") && !raw.contains("PMR")) {
                        sb.append("<b>Pre-anchor phase (").append(raw).append(" of current balance)</b><br>")
                                .append("All income sources are not yet fully active (SS or annuity<br>")
                                .append("start-month proration still in effect).<br>")
                                .append("Using the user-defined initial rate against the current portfolio<br>")
                                .append("balance as a bridge. Set via 'GK only -- pre-anchor initial wd rate'.<br>")
                                .append("GK guardrail rules engage at the anchor year when all income<br>")
                                .append("streams are paying a full 12-month amount.");
                    } else if (raw.equals("GK start")) {
                        sb.append("<b>GK rules start -- anchor year</b><br>")
                                .append("This is the first year all income sources are paying a full<br>")
                                .append("12-month amount (no start-month proration on any stream).<br>")
                                .append("The net spending need this year sets the GK initial rate<br>")
                                .append("used as the guardrail benchmark for all future years.");
                    } else {
                        sb.append("<b>Guyton-Klinger rules applied this year:</b><br>");
                        if (raw.contains("PMR")) sb.append("<br><b>PMR0 -- Portfolio Management Rule:</b><br>")
                                .append("Inflation adjustment was <i>skipped</i> because the portfolio<br>")
                                .append("lost money last year AND the withdrawal rate exceeds the initial rate.<br>")
                                .append("Result: withdrawal held flat (no inflation raise).<br>");
                        if (raw.contains("CPR")) sb.append("<br><b>CPR[v] -- Capital Preservation Rule:</b><br>")
                                .append("Withdrawal rate exceeded the upper guardrail threshold.<br>")
                                .append("Withdrawal was reduced by 10% to protect capital.<br>");
                        if (raw.contains("PR[^]")) sb.append("<br><b>PR[^] -- Prosperity Rule:</b><br>")
                                .append("Withdrawal rate fell below the lower guardrail threshold.<br>")
                                .append("Withdrawal was increased by 10% to share in portfolio growth.<br>");
                    }
                    sb.append("</html>");
                    return sb.toString();
                }
                if (col == 22 && gr.rmdOverage > 0) { // Roth/MM
                    return "<html><b>RMD overage -> Roth/MM</b><br>"
                            + "Combined RMD (" + CURRENCY.format(gr.combRmd) + ")<br>"
                            + "exceeds planned GK withdrawal (" + CURRENCY.format(gr.wdActual) + ").<br>"
                            + "Overage (" + CURRENCY.format(gr.rmdOverage) + ") goes to Roth/MM -- not spent.</html>";
                }
                if (col == 23) { // Bal ? tooltip
                    double d = showRealDollars ? gr.inflFactor : 1.0;
                    return String.format("<html><b>Portfolio change: %s%s</b><br>"
                                    + "&nbsp;&nbsp;Market growth: +%s<br>"
                                    + "&nbsp;&nbsp;Withdrawal:   ?%s<br>",
                            gr.balDelta >= 0 ? "+" : "",
                            CURRENCY.format((long)(gr.balDelta / d)),
                            CURRENCY.format((long)(gr.investmentGrowth / d)),
                            CURRENCY.format((long)(gr.wdActual / d)));
                }
                return super.getToolTipText(e);
            }
        };

        tblGk.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tblGk.setRowHeight(24);
        tblGk.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        tblGk.setGridColor(new Color(220, 220, 215));
        tblGk.setShowGrid(true);
        tblGk.setSelectionBackground(new Color(210, 230, 250));
        tblGk.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // col widths -- col 6 hidden
        int[] gkw = {
                55, 55, 105, 115, 105, 68,   // 0-5
                0,                            // 6 hidden
                90,                           // 7 rule flags
                75, 80, 72, 90,              // 8-11
                72, 72, 78,                  // 12-14
                88, 95, 85,                  // 15-17
                72,                          // 18 infl
                80, 85, 90, 85,              // 19-22 RMDs
                90                           // 23 Bal ?
        };
        for (int i = 0; i < gkw.length && i < tblGk.getColumnCount(); i++) {
            TableColumn tc = tblGk.getColumnModel().getColumn(i);
            tc.setPreferredWidth(gkw[i]);
            if (gkw[i] == 0) { tc.setMinWidth(0); tc.setMaxWidth(0); }
        }

        // Header tooltips
        JTableHeader gkHeader = tblGk.getTableHeader();
        gkHeader.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int col = gkHeader.columnAtPoint(e.getPoint());
                switch (col) {
                    case 3 -> gkHeader.setToolTipText(
                            "<html><b>GK withdrawal</b><br>"
                                    + "Pre-anchor years: user-entered rate ? current balance.<br>"
                                    + "Anchor year: net spending need (spending minus guaranteed income).<br>"
                                    + "Subsequent years: prior withdrawal, inflation-adjusted, then<br>"
                                    + "modified by CPR, PR, and PMR rules as needed.<br><br>"
                                    + "Guardrail comparisons (CPR[v] / PR[^]) use the user-entered<br>"
                                    + "pre-anchor rate as the initial-rate benchmark -- not the<br>"
                                    + "computed net-need / portfolio ratio.</html>");
                    case 7 -> gkHeader.setToolTipText(
                            "<html><b>Rule flags</b><br>"
                                    + "<b>X.X%</b> = Year 1 of drawing: gkPreRate ? current balance used.<br>"
                                    + "&nbsp;&nbsp;Set via 'GK only -- pre-anchor initial wd rate' in the input panel.<br>"
                                    + "<b>--</b> = GK rules phase, no rule triggered; normal inflation adjustment.<br>"
                                    + "<b>PMR0</b> = Portfolio Management Rule: inflation raise <i>skipped</i>.<br>"
                                    + "<b>CPR[v]</b> = Capital Preservation Rule: withdrawal cut 10%.<br>"
                                    + "<b>PR[^]</b> = Prosperity Rule: withdrawal raised 10%.</html>");
                    case 22 -> gkHeader.setToolTipText(
                            "<html><b>-> Roth/MM -- RMD overage redirected</b><br>"
                                    + "= max(0, Combined RMD ? GK actual withdrawal).<br>"
                                    + "Excess RMD above planned GK spending is redirected<br>"
                                    + "to Roth IRA or money market -- NOT spent.</html>");
                    case 23 -> gkHeader.setToolTipText(
                            "<html><b>Bal ? -- portfolio balance change</b><br>"
                                    + "= market growth ? GK spending withdrawal.<br>"
                                    + "Green = grew . Red = shrank.</html>");
                    default -> gkHeader.setToolTipText(null);
                }
            }
        });

        // Cell renderer
        tblGk.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private final Color AMBER_BG   = new Color(255, 220, 100);
            private final Color AMBER_FG   = new Color(130, 80, 0);
            private final Color GOGO_BG    = new Color(232, 248, 240);
            private final Color GOGO_WD_BG = new Color(180, 230, 205);
            private final Color PMR_BG     = new Color(255, 235, 185);  // gold -- freeze
            private final Color PMR_FG     = new Color(120, 70, 0);
            private final Color CPR_BG     = new Color(255, 210, 210);  // red -- cut
            private final Color CPR_FG     = new Color(150, 30, 30);
            private final Color PR_BG      = new Color(210, 240, 210);  // green -- raise
            private final Color PR_FG      = new Color(30, 110, 30);
            private final Color BENGEN_BG  = new Color(230, 222, 255);  // lavender -- Bengen phase
            private final Color BENGEN_FG  = new Color(70, 40, 140);

            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel && lastResults != null && lastResults.gkResults != null) {
                    List<GkRow> rows = lastResults.gkResults.rows;
                    boolean goGo      = row < rows.size() && rows.get(row).goGoActive;
                    GkRow gr          = row < rows.size() ? rows.get(row) : null;
                    boolean preAnchor = gr != null && gr.preAnchor;

                    Color defaultBg = preAnchor ? BENGEN_BG
                            : goGo ? GOGO_BG
                              : (row % 2 == 0 ? Color.WHITE : new Color(248, 248, 245));
                    c.setBackground(defaultBg);
                    c.setForeground(preAnchor ? BENGEN_FG : Color.BLACK);

                    String flags = gr != null ? (gr.ruleFlags != null ? gr.ruleFlags : "--") : "--";

                    if ((col == 3 || col == 4) && goGo && !preAnchor) {
                        c.setBackground(GOGO_WD_BG); c.setForeground(new Color(0, 90, 50));
                    } else if ((col == 3 || col == 4) && goGo && preAnchor) {
                        c.setBackground(new Color(200, 190, 240)); c.setForeground(new Color(60, 30, 120));
                    } else if (col == 7) {
                        if      (preAnchor)               { c.setBackground(BENGEN_BG); c.setForeground(BENGEN_FG); }
                        else if (flags.contains("CPR"))   { c.setBackground(CPR_BG);    c.setForeground(CPR_FG); }
                        else if (flags.contains("PR[^]"))   { c.setBackground(PR_BG);     c.setForeground(PR_FG); }
                        else if (flags.contains("PMR"))   { c.setBackground(PMR_BG);    c.setForeground(PMR_FG); }
                        else if (flags.equals("GK start")){ c.setBackground(new Color(220,235,255)); c.setForeground(new Color(24,95,165)); }
                    } else if ((col == 21 || col == 22) && gr != null && gr.rmdOverage > 0) {
                        c.setBackground(new Color(255, 200, 120));
                        c.setForeground(new Color(140, 60, 0));
                    } else if ((col == 19 || col == 20) && gr != null) {
                        Object rmdV = tblGkModel.getValueAt(row, col);
                        Object wdV  = tblGkModel.getValueAt(row, 3);
                        if (rmdV != null && wdV != null && !"--".equals(rmdV.toString()) && !"--".equals(wdV.toString())) {
                            try {
                                double rmd = Double.parseDouble(rmdV.toString().replaceAll("[^0-9.]", ""));
                                double wd  = Double.parseDouble(wdV.toString().replaceAll("[^0-9.]", ""));
                                if (rmd > wd) { c.setBackground(AMBER_BG); c.setForeground(AMBER_FG); }
                            } catch (NumberFormatException ignored) {}
                        }
                    } else if (col == 17) {
                        String s = v == null ? "" : v.toString();
                        if (!preAnchor) c.setForeground(s.startsWith("-") ? new Color(180,30,30) : new Color(59,109,17));
                    } else if (col == 23) {
                        String s = v == null ? "" : v.toString();
                        if (!preAnchor) c.setForeground(s.startsWith("-") ? new Color(180,30,30) : new Color(59,109,17));
                    }
                }
                ((JLabel) c).setHorizontalAlignment(col <= 1 ? LEFT : RIGHT);
                return c;
            }
        });

        // Metrics header for GK tab
        lblGkInitWd   = mkMetricLabel();
        lblGkInitRate = mkMetricLabel();
        lblGkFinalBal = mkMetricLabel();
        JPanel gkMetrics = new JPanel(new GridLayout(1, 3, 8, 0));
        gkMetrics.setBackground(new Color(245, 245, 242));
        gkMetrics.add(wrapMetric(lblGkInitWd,   "GK anchor withdrawal",  "carried forward from pre-anchor at GK rules start"));
        gkMetrics.add(wrapMetric(lblGkInitRate,  "GK guardrail anchor",   "user-entered initial rate (CPR/PR benchmark)"));
        gkMetrics.add(wrapMetric(lblGkFinalBal,  "Final portfolio bal",   "end of horizon (mean return)"));

        // Legend bar
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 3));
        legend.setBackground(new Color(245, 245, 242));
        legend.add(gkLegendChip(new Color(230, 222, 255), new Color(70, 40, 140),  "Year 1 initial rate (gkPreRate ? bal)"));
        legend.add(gkLegendChip(new Color(220, 235, 255), new Color(24, 95, 165),  "GK rules start"));
        legend.add(gkLegendChip(new Color(180, 230, 205), new Color(0, 90, 50),    "Go-go years"));
        legend.add(gkLegendChip(new Color(255, 235, 185), new Color(120, 70, 0),   "PMR0 -- inflation frozen"));
        legend.add(gkLegendChip(new Color(255, 210, 210), new Color(150, 30, 30),  "CPR[v] -- cut 10%"));
        legend.add(gkLegendChip(new Color(210, 240, 210), new Color(30, 110, 30),  "PR[^]  -- raised 10%"));
        legend.add(gkLegendChip(new Color(255, 200, 120), new Color(140, 60, 0),   "RMD overage -> Roth/MM"));

        JPanel topGk = new JPanel(new BorderLayout(0, 4));
        topGk.setBackground(new Color(245, 245, 242));
        topGk.add(gkMetrics, BorderLayout.NORTH);
        topGk.add(legend,    BorderLayout.SOUTH);

        JScrollPane gkScroll = new JScrollPane(tblGk);
        gkScroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel gkPanel = new JPanel(new BorderLayout(0, 4));
        gkPanel.setBackground(new Color(245, 245, 242));
        gkPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        gkPanel.add(topGk,    BorderLayout.NORTH);
        gkPanel.add(gkScroll, BorderLayout.CENTER);
        return gkPanel;
    }

    private JLabel gkLegendChip(Color bg, Color fg, String text) {
        JLabel lbl = new JLabel("  " + text + "  ") {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(bg);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                super.paintComponent(g);
            }
        };
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lbl.setForeground(fg);
        lbl.setOpaque(false);
        return lbl;
    }


    private void updateGkTable(ProResults res) {
        if (tblGkModel == null) return;
        SimInputs inp = res.inp;
        boolean showRealDollars = this.showRealDollars;
        if (res.gkResults != null) {
            GkResults gk = res.gkResults;
            tblGkModel.setRowCount(0);
            for (GkRow gr : gk.rows) {
                double d = showRealDollars ? gr.inflFactor : 1.0;
                tblGkModel.addRow(new Object[]{
                        gr.manAge,                                                              // 0
                        gr.calYear,                                                             // 1
                        CURRENCY.format((long)(gr.balance  / d)),                               // 2
                        gr.drawing ? CURRENCY.format((long)(gr.wdGK    / d)) : "--",            // 3
                        gr.drawing ? CURRENCY.format((long)(gr.wdActual/ d)) : "--",            // 4
                        gr.drawing ? String.format("%.2f%%", gr.wdPct) : "--",                  // 5
                        gr.ruleFlags,                                                           // 6 hidden
                        gr.ruleFlags,                                                           // 7 visible flags
                        gr.manSS   > 0 ? CURRENCY.format((long)(gr.manSS   / d)) : "--",        // 8
                        gr.womanSS > 0 ? CURRENCY.format((long)(gr.womanSS / d)) : "--",        // 9
                        gr.annuity > 0 ? CURRENCY.format((long)(gr.annuity / d)) : "--",        // 10
                        gr.guaranteed > 0 ? CURRENCY.format((long)(gr.guaranteed / d)) : "--", // 11
                        gr.drawing ? CURRENCY.format((long)(gr.living    / d)) : "--",          // 12
                        gr.drawing ? CURRENCY.format((long)(gr.medical   / d)) : "--",          // 13
                        gr.tax > 0  ? CURRENCY.format((long)(gr.tax      / d)) : "--",          // 14
                        gr.drawing ? CURRENCY.format((long)(gr.totalSpend/ d)) : "--",          // 15
                        CURRENCY.format((long)(gr.totalIncome / d)),                            // 16
                        gr.drawing
                                ? (gr.surplus >= 0 ? "+" : "-")
                                  + CURRENCY.format((long)(Math.abs(gr.surplus) / d))
                                : "--",                                                              // 17
                        String.format("%.3f", gr.inflFactor),                                  // 18
                        gr.manRmd   > 0 ? CURRENCY.format((long)(gr.manRmd   / d)) : "--",      // 19
                        gr.womanRmd > 0 ? CURRENCY.format((long)(gr.womanRmd / d)) : "--",      // 20
                        gr.combRmd  > 0 ? CURRENCY.format((long)(gr.combRmd  / d)) : "--",      // 21
                        gr.rmdOverage>0  ? CURRENCY.format((long)(gr.rmdOverage/d)) : "--",     // 22
                        (gr.balDelta >= 0 ? "+" : "-")
                                + CURRENCY.format((long)(Math.abs(gr.balDelta) / d)),              // 23
                });
            }
            // Update GK metrics header
            double gkRate   = gk.initialWdRate * 100.0;
            double dEndGk   = !gk.rows.isEmpty() ? gk.rows.get(gk.rows.size()-1).inflFactor : 1.0;
            double wd1InflFactor = Math.pow(1 + inp.inflation, inp.withdrawStartYear - inp.baseYear);
            lblGkInitWd.setText(CURRENCY.format((long)((double)gk.yr1Withdrawal
                    / (showRealDollars ? wd1InflFactor : 1.0))) + " / yr");
            lblGkInitRate.setText(String.format("%.2f%%", gkRate));
            lblGkFinalBal.setText(showRealDollars
                    ? formatMoney((long)(gk.finalBalance / dEndGk)) + " (2026$)"
                    : formatMoney(gk.finalBalance) + " (nom.)");
        }
    }




    /**
     * Returns {equityReturn, inflation} for a simulation year.
     * Uses the historical scenario sequence when available, random otherwise.
     */
    private double[] getReturnAndInflation(SimInputs inp, int simYear, SeededRng rng) {
        double[][] seq = HistoricalScenarios.getSequence(inp.scenarioIndex);
        if (seq != null && simYear < seq.length) {
            return new double[]{ seq[simYear][1], seq[simYear][2] };
        }
        double ret  = inp.nomReturn + inp.stdDev * rng.nextGaussian();
        double infl = Math.max(0, inp.inflation + inp.inflationStdDev * rng.nextGaussian());
        return new double[]{ ret, infl };
    }

    // ========================================================================
    //  CHART PANEL
    // ========================================================================
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

    static class GkRow {
        int  calYear, manAge, womanAge;
        int  balance;
        int  wdGK;            // GK-computed withdrawal (pre-go-go)
        int  wdActual;        // wdGK ? go-go multiplier
        double wdPct;
        String ruleFlags;     // "X.X%", "--", "PMR0", "CPR[v]", "PR[^]", combinations
        int  manSS, womanSS, annuity, guaranteed;
        int  living, medical, tax, totalSpend, totalIncome, surplus;
        double inflFactor;
        int  manRmd, womanRmd, combRmd, rmdOverage;
        int  investmentGrowth, balDelta;
        boolean drawing, goGoActive, preAnchor;
    }

    static class GkResults {
        SimInputs inp;
        List<GkRow> rows;
        int  yr1Withdrawal;
        double initialWdRate;
        int  finalBalance;
    }


    // ========================================================================
    //  HISTORICAL SCENARIOS DATA TABLE
    //
    //  Sources:
    //    Equity returns : Damodaran (NYU Stern), S&P 500 total return incl dividends
    //                     pages.stern.nyu.edu/~adamodar/New_Home_Page/datafile/histretSP.html
    //    Inflation (CPI): U.S. Bureau of Labor Statistics via InflationData.com
    //
    //  Each scenario covers the full crisis + recovery period.
    //  After the historical sequence ends, the simulation reverts to the
    //  user-specified random return distribution for remaining years.
    //
    //  Future JDBC migration: write one row per year to a table with columns:
    //    (scenario_id VARCHAR, cal_year INT, equity_return DOUBLE, cpi_rate DOUBLE)
    // ========================================================================
    static class HistoricalScenarios {

        static final String[] SCENARIO_NAMES = {
                "Random (normal distribution -- default)",
                "Great Depression (1929-1942)",
                "Stagflation Era (1966-1982)",
                "Dot-com Crash (2000-2006)",
                "Housing Crisis / GFC (2007-2013)"
        };

        static double[][] getSequence(int scenarioIndex) {
            return switch (scenarioIndex) {
                case 1 -> GREAT_DEPRESSION;
                case 2 -> STAGFLATION;
                case 3 -> DOT_COM;
                case 4 -> HOUSING_CRISIS;
                default -> null;
            };
        }

        static String getDescription(int scenarioIndex) {
            return switch (scenarioIndex) {
                case 1 -> "<html><b>Great Depression (1929-1942)</b><br>"
                        + "14 years of actual S&P 500 total returns and CPI data.<br>"
                        + "Includes the crash (1929-1932, cumulative ?79%), the volatile<br>"
                        + "recovery (1933-1936), the 1937 relapse (?35%), and stabilization.<br>"
                        + "After 1942 the simulation reverts to your random distribution.</html>";
                case 2 -> "<html><b>Stagflation Era (1966-1982)</b><br>"
                        + "17 years of actual S&P 500 total returns and CPI data.<br>"
                        + "Characterized by low/negative real returns with high inflation.<br>"
                        + "The worst sequence-of-returns era for retirees in modern history.<br>"
                        + "After 1982 the simulation reverts to your random distribution.</html>";
                case 3 -> "<html><b>Dot-com Crash (2000-2006)</b><br>"
                        + "7 years of actual S&P 500 total returns and CPI data.<br>"
                        + "Three consecutive down years (2000-2002), then a strong recovery.<br>"
                        + "After 2006 the simulation reverts to your random distribution.</html>";
                case 4 -> "<html><b>Housing Crisis / GFC (2007-2013)</b><br>"
                        + "7 years of actual S&P 500 total returns and CPI data.<br>"
                        + "The 2008 crash (?37%) followed by one of the fastest recoveries<br>"
                        + "on record. After 2013 reverts to your random distribution.</html>";
                default -> "<html>Random normal distribution based on your input parameters.</html>";
            };
        }

        // { calendarYear, equityTotalReturn, CPI_inflation }  (decimal fractions)
        // Source: Damodaran NYU Stern S&P 500 total return; BLS CPI-U

        private static final double[][] GREAT_DEPRESSION = {
                { 1929, -0.0830,  0.001 },
                { 1930, -0.2512, -0.023 },
                { 1931, -0.4384, -0.089 },
                { 1932, -0.0864, -0.103 },
                { 1933,  0.4998, -0.051 },
                { 1934, -0.0119,  0.033 },
                { 1935,  0.4674,  0.025 },
                { 1936,  0.3194,  0.014 },
                { 1937, -0.3534,  0.037 },
                { 1938,  0.2928, -0.021 },
                { 1939, -0.0110, -0.014 },
                { 1940, -0.1067,  0.007 },
                { 1941, -0.1277,  0.095 },
                { 1942,  0.1917,  0.090 },
        };

        private static final double[][] STAGFLATION = {
                { 1966, -0.0997,  0.042 },
                { 1967,  0.2380,  0.034 },
                { 1968,  0.1081,  0.047 },
                { 1969, -0.0824,  0.062 },
                { 1970,  0.0356,  0.056 },
                { 1971,  0.1422,  0.033 },
                { 1972,  0.1876,  0.034 },
                { 1973, -0.1431,  0.087 },
                { 1974, -0.2590,  0.123 },
                { 1975,  0.3700,  0.069 },
                { 1976,  0.2383,  0.049 },
                { 1977, -0.0698,  0.067 },
                { 1978,  0.0651,  0.090 },
                { 1979,  0.1852,  0.133 },
                { 1980,  0.3174,  0.121 },
                { 1981, -0.0470,  0.089 },
                { 1982,  0.2042,  0.038 },
        };

        private static final double[][] DOT_COM = {
                { 2000, -0.0910,  0.034 },
                { 2001, -0.1189,  0.028 },
                { 2002, -0.2197,  0.016 },
                { 2003,  0.2836,  0.023 },
                { 2004,  0.1074,  0.027 },
                { 2005,  0.0483,  0.034 },
                { 2006,  0.1561,  0.032 },
        };

        private static final double[][] HOUSING_CRISIS = {
                { 2007,  0.0548,  0.028 },
                { 2008, -0.3700,  0.038 },
                { 2009,  0.2646,  0.003 },
                { 2010,  0.1506,  0.016 },
                { 2011,  0.0211,  0.032 },
                { 2012,  0.1600,  0.021 },
                { 2013,  0.3239,  0.015 },
        };
    }

    // ========================================================================
    //  RNG
    // ========================================================================
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

    // ========================================================================
    //  DATA CLASSES
    // ========================================================================
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
        double gkPreRate;
        int scenarioIndex; // 0=Random, 1=Depression, 2=Stagflation, 3=DotCom, 4=GFC
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
        double goGoMult;
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
        GkResults gkResults;
    }

    // ========================================================================
    //  UI HELPERS
    // ========================================================================
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
        JLabel l = new JLabel("--");
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
                            + "Fan: %,d paths ? %d yrs ? %d iters ? %,d paths = <b>%,dM sims</b><br>"
                            + "Median: %d yrs ? %d iters ? %,d paths ? avg %d remaining = <b>%,dM sims</b><br>"
                            + "Grand total: <b>~%,dM simulations</b></html>",
                    fanPaths, horizon, binIters, solvePaths, fanSims / 1_000_000,
                    horizon, binIters, solvePaths, (horizon + 1) / 2, medianSims / 1_000_000, totalM));
            return String.format("Running -- 0 / ~%,dM simulations performed...", totalM);
        } catch (Exception e) { return "Running Monte Carlo..."; }
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

    // ========================================================================
    //  SCROLLABLE PANEL
    // ========================================================================
    static class ScrollablePanel extends JPanel implements javax.swing.Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r,int o,int d){ return 20; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r,int o,int d){ return 60; }
        @Override public boolean getScrollableTracksViewportWidth()  { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // ========================================================================
    //  MAIN
    // ========================================================================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new IncomeLab_OptimizeSocsec();
        });
    }
}
