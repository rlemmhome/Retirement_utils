package com.hiflite.incomelabs_riskbased;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IncomeLab_Plus_SS_Optimizer.java
 *
 * Social Security Claiming Age Optimizer built on the Income Lab MC engine.
 * Ported from IncomeLab_GK2_and_historical.java.
 *
 * Three scoring tabs:
 *   Max Spending  -- yr1Withdrawal primary, medianFinalBalance tiebreaker
 *   Max Legacy    -- medianFinalBalance primary, yr1Withdrawal tiebreaker
 *   Max Survival  -- actualPoS primary, yr1Withdrawal tiebreaker
 *
 * Phase 1: fast scan of all SS combinations.
 * Phase 2: full fidelity confirmation of top-N from each tab.
 * Historical stress scenario applies to Phase 1, Phase 2, and DetailWindow (Option C).
 *
 * Click any Phase-2-confirmed row to open the full IL year-by-year table.
 *
 * Compile: javac IncomeLab_Plus_SS_Optimizer.java
 * Run:     java com.hiflite.incomelabs_riskbased.IncomeLab_Plus_SS_Optimizer
 * Requires Java 11+. No external dependencies. ASCII source only.
 */
public class IncomeLab_Plus_SS_Optimizer extends JFrame {

    private static final NumberFormat CURRENCY = NumberFormat.getCurrencyInstance(Locale.US);
    static { CURRENCY.setMaximumFractionDigits(0); }

    // Font sizing (identical to IL)
    private static final int BASE_FONT_SIZE = 12;
    private int fontDelta = 2;
    private JSpinner spFontDelta;
    private javax.swing.Timer fontDebounceTimer;
    private static final String FONT_DELTA_KEY = "app.fontDelta";

    // Input spinners
    private JSpinner spSimStartYear, spWithdrawStartYear, spWithdrawStartMonth;
    private JSpinner spManBirthYear, spManBirthMonth, spManPlanAge;
    private JSpinner spWomanBirthYear, spWomanBirthMonth, spWomanPlanAge;
    private JSpinner spManPIA, spWomanPIA, spSSCola;
    private JSpinner spAnnuity, spAnnuityStartYear, spAnnuityStartMonth;
    private JSpinner spManTradIRA, spManRothIRA, spManTrad401K, spManRoth401K;
    private JSpinner spWomanRoth401K, spWomanRothIRA, spWomanTradIRA, spWomanTrad401K;
    private JLabel   lblAccountTotal;
    private JSpinner spNomReturn, spStdDev, spInflation, spInflationStdDev;
    private JSpinner spLivingExp, spMedical, spMedInflation;
    private JSpinner spBaseTax, spTaxInflation;
    private JSpinner spGoGo, spGoGoDuration;
    private JSpinner spTargetPoS, spHorizon;
    private JLabel   lblHorizonNote, lblManAge, lblWomanAge;
    private JSpinner spPhase1Paths, spPhase1BinIters;
    private JSpinner spPhase2Paths, spPhase2BinIters, spPhase2FanPaths, spPhase2TopN;
    private JComboBox<String> cmbScenario;

    // Run control
    private JButton  btnRun, btnCancel;
    private JLabel   lblStatus;
    private volatile boolean cancelRequested = false;

    // Tabs
    private JTabbedPane tabs;
    private ResultTab   tabSpend, tabLegacy, tabSurvival;

    // Progress tracking
    private final AtomicInteger phase1Done  = new AtomicInteger(0);
    private final AtomicInteger phase2Done  = new AtomicInteger(0);
    private volatile int        phase1Total = 0;
    private volatile int        phase2Total = 0;
    private volatile int        currentPhase = 0;
    private volatile long       runStartMs   = 0;
    private volatile long       phaseStartMs = 0;
    private final long[]        lastComboDurMs = new long[20];
    private int                 lastComboDurIdx = 0;
    private ScheduledExecutorService scheduler;

    // Detail window cache
    private final Map<String, ILEngine.ProResults> detailCache = new ConcurrentHashMap<>();
    private volatile SimInputs lastBaseInputs = null;
    private volatile int lastP2Paths = 1000, lastP2Fan = 500, lastP2BinIters = 22;

    // ==============================
    // Constructor
    // ==============================
    public IncomeLab_Plus_SS_Optimizer() {
        super("IncomeLab Plus SS Optimizer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(new Color(245, 245, 242));
        ToolTipManager ttm = ToolTipManager.sharedInstance();
        ttm.setInitialDelay(750); ttm.setDismissDelay(15_000); ttm.setReshowDelay(500);
        add(buildInputPanel(),  BorderLayout.WEST);
        add(buildOutputPanel(), BorderLayout.CENTER);
        add(buildStatusBar(),   BorderLayout.SOUTH);
        pack();
        setMinimumSize(new Dimension(1400, 800));
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLocationRelativeTo(null);
        setVisible(true);
        SwingUtilities.invokeLater(this::refreshAgeAndHorizon);
    }

    // ==============================
    // INPUT PANEL
    // ==============================
    private JPanel buildInputPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(new Color(240, 240, 237));
        outer.setBorder(BorderFactory.createMatteBorder(0,0,0,1,new Color(200,198,193)));
        outer.setPreferredSize(new Dimension(420, 0));
        outer.setMinimumSize(new Dimension(380, 0));

        JPanel inner = new ScrollablePanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBackground(new Color(240, 240, 237));
        inner.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // -- Font size
        spFontDelta = new JSpinner(new SpinnerNumberModel(2, -6, 6, 1));
        spFontDelta.setFont(new Font("SansSerif", Font.PLAIN, BASE_FONT_SIZE + fontDelta));
        spFontDelta.setToolTipText("<html><b>Font size adjustment (pt)</b><br>"
                + "Shifts all application fonts by this many points.<br>"
                + "Default = +2. Update applies ~1 second after adjusting.</html>");
        fontDebounceTimer = new javax.swing.Timer(1000, e -> {
            fontDelta = (Integer) spFontDelta.getValue();
            applyFonts(SwingUtilities.getWindowAncestor(spFontDelta));
        });
        fontDebounceTimer.setRepeats(false);
        spFontDelta.addChangeListener(e -> fontDebounceTimer.restart());
        inner.add(card("Appearance", new Object[]{ "Font size adjustment (pt)", spFontDelta }));
        inner.add(Box.createVerticalStrut(4));

        // -- Simulation timeline
        int curYear = java.time.Year.now().getValue();
        spSimStartYear       = spinI(curYear, 2020, 2040, 1, "#");
        spWithdrawStartYear  = spinI(2027, 2025, 2040, 1, "#");
        spWithdrawStartMonth = spinI(1, 1, 12, 1, "#");
        spTargetPoS          = spinI(80, 60, 99, 1, "#");
        spHorizon            = spinI(30, 10, 50, 1, "#");
        spHorizon.setEnabled(false);
        ((JSpinner.DefaultEditor)spHorizon.getEditor()).getTextField().setEditable(false);
        ((JSpinner.DefaultEditor)spHorizon.getEditor()).getTextField().setBackground(new Color(225,225,220));
        inner.add(card("Simulation Timeline", new Object[]{
                "Simulation start year",              spSimStartYear,
                "Withdrawal start year",              spWithdrawStartYear,
                "Withdrawal start month",             spWithdrawStartMonth,
                "Target probability of success (%)",  spTargetPoS,
                "Horizon (yrs - driven by life expectancy)", spHorizon,
        }));
        inner.add(Box.createVerticalStrut(4));

        // -- People
        spManBirthYear    = spinI(1961, 1940, 2000, 1, "#");
        spManBirthMonth   = spinI(9,    1,    12,   1, "#");
        spWomanBirthYear  = spinI(1962, 1940, 2000, 1, "#");
        spWomanBirthMonth = spinI(12,   1,    12,   1, "#");
        spManPlanAge      = spinI(90, 70, 110, 1, "#");
        spWomanPlanAge    = spinI(92, 70, 110, 1, "#");
        lblHorizonNote = new JLabel(" ");
        lblHorizonNote.setFont(new Font("SansSerif", Font.ITALIC, 12));
        lblHorizonNote.setForeground(new Color(100,100,100));
        lblManAge   = new JLabel("Man age: --");
        lblManAge.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblWomanAge = new JLabel("Woman age: --");
        lblWomanAge.setFont(new Font("SansSerif", Font.PLAIN, 12));
        ChangeListener peopleL = e -> refreshAgeAndHorizon();
        for (JSpinner sp : new JSpinner[]{spManBirthYear, spManBirthMonth,
                spWomanBirthYear, spWomanBirthMonth, spManPlanAge, spWomanPlanAge})
            sp.addChangeListener(peopleL);
        JPanel ageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        ageRow.setOpaque(false); ageRow.setAlignmentX(LEFT_ALIGNMENT);
        ageRow.add(lblManAge); ageRow.add(lblWomanAge);
        inner.add(card("People - Birth Dates & Life Expectancy", new Object[]{
                "Man birth year",           spManBirthYear,
                "Man birth month",          spManBirthMonth,
                "Woman birth year",         spWomanBirthYear,
                "Woman birth month",        spWomanBirthMonth,
                "Man's life expectancy",    spManPlanAge,
                "Woman's life expectancy",  spWomanPlanAge,
                null, ageRow,
                null, lblHorizonNote,
        }));
        inner.add(Box.createVerticalStrut(4));

        // -- Social Security
        spManPIA   = spinI(3788, 0, 10000, 1, "#,###");
        spWomanPIA = spinI(3897, 0, 10000, 1, "#,###");
        spSSCola   = spinD(2.3, 0.0, 10.0, 0.1, "0.0#");
        JLabel ssNote = new JLabel("<html><i>SS start dates are the optimizer's search variables.<br>"
                + "Search range: today to age 70 for each person.</i></html>");
        ssNote.setFont(new Font("SansSerif", Font.ITALIC, 12));
        ssNote.setForeground(new Color(90,70,10));
        ssNote.setAlignmentX(LEFT_ALIGNMENT);
        inner.add(card("Social Security (Start Dates Optimized)", new Object[]{
                "Man PIA ($/mo at FRA)",    spManPIA,
                "Woman PIA ($/mo at FRA)",  spWomanPIA,
                "SS COLA (%/yr)",            spSSCola,
                null, ssNote,
        }));
        inner.add(Box.createVerticalStrut(4));

        // -- Annuity
        spAnnuity           = spinI(22599, 0, 500000, 100, "#,###");
        spAnnuityStartYear  = spinI(2028, 2025, 2060, 1, "#");
        spAnnuityStartMonth = spinI(4, 1, 12, 1, "#");
        inner.add(card("Annuity (non-COLA)", new Object[]{
                "Annual annuity ($/yr)",  spAnnuity,
                "Annuity start year",     spAnnuityStartYear,
                "Annuity start month",    spAnnuityStartMonth,
        }));
        inner.add(Box.createVerticalStrut(4));

        // -- Accounts
        spManTradIRA    = spinI(880000, 0, 10000000, 1000, "#,###");
        spManRothIRA    = spinI( 10000, 0, 10000000, 1000, "#,###");
        spManTrad401K   = spinI(     0, 0, 10000000, 1000, "#,###");
        spManRoth401K   = spinI(     0, 0, 10000000, 1000, "#,###");
        spWomanRoth401K = spinI( 30000, 0, 10000000, 1000, "#,###");
        spWomanRothIRA  = spinI(     0, 0, 10000000, 1000, "#,###");
        spWomanTradIRA  = spinI(266000, 0, 10000000, 1000, "#,###");
        spWomanTrad401K = spinI(314000, 0, 10000000, 1000, "#,###");
        lblAccountTotal = new JLabel("Account total: $1,500,000");
        lblAccountTotal.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblAccountTotal.setForeground(new Color(30,30,30));
        lblAccountTotal.setAlignmentX(LEFT_ALIGNMENT);
        ChangeListener acctL = e -> updateAccountTotal();
        for (JSpinner sp : new JSpinner[]{spManTradIRA,spManRothIRA,spManTrad401K,spManRoth401K,
                spWomanRoth401K,spWomanRothIRA,spWomanTradIRA,spWomanTrad401K})
            sp.addChangeListener(acctL);
        inner.add(card("Account Balances (SECURE 2.0 RMD - age 75)", new Object[]{
                "Man - Traditional IRA ($)  [RMD age 75]",    spManTradIRA,
                "Man - Roth IRA ($)  [no RMD]",              spManRothIRA,
                "Man - Traditional 401K ($)  [RMD age 75]",  spManTrad401K,
                "Man - Roth 401K ($)  [no RMD]",             spManRoth401K,
                "Woman - Roth 401K ($)  [no RMD]",           spWomanRoth401K,
                "Woman - Roth IRA ($)  [no RMD]",            spWomanRothIRA,
                "Woman - Traditional IRA ($)  [RMD age 75]", spWomanTradIRA,
                "Woman - Traditional 401K ($)  [RMD age 75]",spWomanTrad401K,
                null, lblAccountTotal,
        }));
        inner.add(Box.createVerticalStrut(4));

        // -- Market assumptions
        spNomReturn       = spinD(6.70, 0.0, 20.0, 0.01, "0.00#");
        spStdDev          = spinD(10.79, 0.0, 40.0, 0.01, "0.00#");
        spInflation       = spinD(3.79, 0.0, 15.0, 0.01, "0.00#");
        spInflationStdDev = spinD(2.73, 0.0, 10.0, 0.01, "0.00#");
        inner.add(card("Market Assumptions (1961-2024 Historical)", new Object[]{
                "Expected nominal return (%)", spNomReturn,
                "Return std deviation (%)",    spStdDev,
                "Mean inflation (%/yr)",       spInflation,
                "Inflation std deviation (%)", spInflationStdDev,
        }));
        inner.add(Box.createVerticalStrut(4));

        // -- Spending
        spLivingExp    = spinI(105000, 0, 500000, 1000, "#,###");
        spMedical      = spinI( 16000, 0, 100000,  500, "#,###");
        spMedInflation = spinD(4.5, 0.0, 15.0, 0.1, "0.0#");
        spBaseTax      = spinI( 17500, 0, 200000, 1000, "#,###");
        spTaxInflation = spinD(3.79, 0.0, 10.0, 0.01, "0.00#");
        spGoGo         = spinD(1.300, 1.0, 2.0, 0.001, "0.000#");
        spGoGoDuration = spinI(10, 0, 20, 1, "#");
        inner.add(card("Annual Spending (Base $)", new Object[]{
                "Living expenses ($/yr)",               spLivingExp,
                "Medical ($/yr)",                       spMedical,
                "Medical inflation (%/yr)",             spMedInflation,
                "Base tax - yr 1 ($/yr)",               spBaseTax,
                "Tax inflation (%/yr)",                 spTaxInflation,
                "Go-go years multiplier",               spGoGo,
                "Go-go years duration (from wd start)", spGoGoDuration,
        }));
        inner.add(Box.createVerticalStrut(4));

        // -- Historical stress scenario (identical to IL)
        cmbScenario = new JComboBox<>(HistoricalScenarios.SCENARIO_NAMES);
        cmbScenario.setToolTipText(HistoricalScenarios.getDescription(0));
        cmbScenario.addActionListener(e -> {
            int idx = cmbScenario.getSelectedIndex();
            cmbScenario.setToolTipText(HistoricalScenarios.getDescription(idx));
        });

        JPanel cardScen = new JPanel();
        cardScen.setLayout(new BoxLayout(cardScen, BoxLayout.Y_AXIS));
        cardScen.setBackground(Color.WHITE);
        cardScen.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0,0,6,0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(208,206,200),1),
                        BorderFactory.createEmptyBorder(8,10,8,10))));
        JLabel scenTitle = new JLabel("HISTORICAL STRESS SCENARIO");
        scenTitle.setFont(new Font("SansSerif", Font.BOLD, 12));
        scenTitle.setForeground(new Color(110,105,95));
        scenTitle.setBorder(BorderFactory.createEmptyBorder(0,0,6,0));
        scenTitle.setAlignmentX(LEFT_ALIGNMENT);
        cardScen.add(scenTitle);
        JLabel scenRowLbl = new JLabel("Sequence of returns");
        scenRowLbl.setFont(new Font("SansSerif", Font.PLAIN, 14));
        scenRowLbl.setForeground(new Color(75,75,75));
        scenRowLbl.setBorder(BorderFactory.createEmptyBorder(5,0,1,0));
        scenRowLbl.setAlignmentX(LEFT_ALIGNMENT);
        cardScen.add(scenRowLbl);
        cmbScenario.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        cmbScenario.setAlignmentX(LEFT_ALIGNMENT);
        cardScen.add(cmbScenario);
        JLabel scenNote = new JLabel(
                "<html><i>Historical years replay actual S&P 500 returns + CPI.<br>"
                        + "After sequence ends, reverts to random distribution.<br>"
                        + "Applies to Phase 1 scan, Phase 2, and Detail Window.</i></html>");
        scenNote.setFont(new Font("SansSerif", Font.ITALIC, 11));
        scenNote.setForeground(new Color(90,70,10));
        scenNote.setBorder(BorderFactory.createEmptyBorder(4,0,0,0));
        scenNote.setAlignmentX(LEFT_ALIGNMENT);
        cardScen.add(scenNote);
        inner.add(cardScen);
        inner.add(Box.createVerticalStrut(4));

        // -- Optimizer parameters
        spPhase1Paths    = spinI(200,   50, 2000,  50, "#,###");
        spPhase1BinIters = spinI(15,     8,   25,   1, "#");
        spPhase2Paths    = spinI(1000, 100, 5000, 100, "#,###");
        spPhase2BinIters = spinI(22,    8,   30,   1, "#");
        spPhase2FanPaths = spinI(500,  50, 2000,  50, "#,###");
        spPhase2TopN     = spinI(20,    5,   60,   5, "#");
        inner.add(card("Optimizer Parameters", new Object[]{
                "Phase 1 - solve paths (fast scan)",      spPhase1Paths,
                "Phase 1 - binary iterations",            spPhase1BinIters,
                "Phase 2 - solve paths (full fidelity)",  spPhase2Paths,
                "Phase 2 - binary iterations",            spPhase2BinIters,
                "Phase 2 - fan chart paths",              spPhase2FanPaths,
                "Phase 2 - top-N per tab to confirm",     spPhase2TopN,
        }));
        inner.add(Box.createVerticalStrut(8));

        // -- Buttons (ASCII labels only)
        btnRun = new JButton("Run Optimizer");
        btnRun.setFont(new Font("SansSerif", Font.BOLD, 16));
        btnRun.setBackground(new Color(24, 95, 165));
        btnRun.setForeground(Color.WHITE);
        btnRun.setFocusPainted(false);
        btnRun.setAlignmentX(LEFT_ALIGNMENT);
        btnRun.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        btnRun.addActionListener(e -> startOptimizer());

        btnCancel = new JButton("Cancel");
        btnCancel.setFont(new Font("SansSerif", Font.BOLD, 14));
        btnCancel.setBackground(new Color(180, 40, 40));
        btnCancel.setForeground(Color.WHITE);
        btnCancel.setFocusPainted(false);
        btnCancel.setAlignmentX(LEFT_ALIGNMENT);
        btnCancel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        btnCancel.setEnabled(false);
        btnCancel.addActionListener(e -> cancelRequested = true);

        inner.add(btnRun);
        inner.add(Box.createVerticalStrut(4));
        inner.add(btnCancel);

        JScrollPane scroll = new JScrollPane(inner);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        outer.add(scroll, BorderLayout.CENTER);
        return outer;
    }

    // ==============================
    // OUTPUT PANEL
    // ==============================
    private JPanel buildOutputPanel() {
        tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.BOLD, 14));
        tabSpend    = new ResultTab("Max Spending",
                "yr1Withdrawal primary, medianFinalBalance tiebreaker",
                "Which SS timing lets you spend the most sustainably at target PoS?",
                ScoringMode.MAX_SPENDING);
        tabLegacy   = new ResultTab("Max Legacy",
                "medianFinalBalance primary, yr1Withdrawal tiebreaker",
                "Which SS timing leaves the largest portfolio at end of horizon?",
                ScoringMode.MAX_LEGACY);
        tabSurvival = new ResultTab("Max Survival",
                "actualPoS primary, yr1Withdrawal tiebreaker",
                "Which SS timing gives the highest probability of not running out of money?",
                ScoringMode.MAX_SURVIVAL);
        tabs.addTab("Max Spending",  tabSpend);
        tabs.addTab("Max Legacy",    tabLegacy);
        tabs.addTab("Max Survival",  tabSurvival);
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(245, 245, 242));
        p.add(tabs, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildStatusBar() {
        lblStatus = new JLabel("Ready - configure inputs and click Run Optimizer");
        lblStatus.setFont(new Font("Monospaced", Font.PLAIN, 13));
        lblStatus.setForeground(new Color(60, 60, 60));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1,0,0,0,new Color(200,198,193)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        p.setBackground(new Color(240, 240, 237));
        p.add(lblStatus, BorderLayout.CENTER);
        return p;
    }

    // ==============================
    // FONT HELPERS (identical to IL)
    // ==============================
    private void applyFonts(java.awt.Component root) {
        if (root == null) return;
        applyFontRecursive(root, fontDelta);
        if (root instanceof java.awt.Window w) { w.pack(); w.revalidate(); }
    }

    private void applyFontRecursive(java.awt.Component c, int newDelta) {
        Font f = c.getFont();
        if (f != null) {
            Integer prevDelta = (c instanceof JComponent jc)
                    ? (Integer) jc.getClientProperty(FONT_DELTA_KEY) : null;
            if (prevDelta == null) prevDelta = 2;
            int newSize = Math.max(8, f.getSize() - prevDelta + newDelta);
            c.setFont(f.deriveFont((float) newSize));
            if (c instanceof JComponent jc) jc.putClientProperty(FONT_DELTA_KEY, newDelta);
        }
        if (c instanceof java.awt.Container ct)
            for (java.awt.Component child : ct.getComponents())
                applyFontRecursive(child, newDelta);
    }

    // ==============================
    // OPTIMIZER ENTRY POINT
    // ==============================
    private void startOptimizer() {
        SimInputs inp = readInputs();
        lastBaseInputs = inp;
        lastP2Paths    = iv(spPhase2Paths);
        lastP2Fan      = iv(spPhase2FanPaths);
        lastP2BinIters = iv(spPhase2BinIters);

        btnRun.setEnabled(false);
        btnCancel.setEnabled(true);
        cancelRequested = false;
        phase1Done.set(0); phase2Done.set(0);
        currentPhase = 1;
        runStartMs = phaseStartMs = System.currentTimeMillis();
        Arrays.fill(lastComboDurMs, 0); lastComboDurIdx = 0;
        detailCache.clear();

        tabSpend.reset(); tabLegacy.reset(); tabSurvival.reset();

        List<SSCandidate> candidates = buildCandidateGrid(inp);
        phase1Total = candidates.size();

        int p1Paths    = iv(spPhase1Paths);
        int p1BinIters = iv(spPhase1BinIters);
        int p2Paths    = lastP2Paths;
        int p2BinIters = lastP2BinIters;
        int p2Fan      = lastP2Fan;
        int topN       = iv(spPhase2TopN);
        int scenIdx    = cmbScenario != null ? cmbScenario.getSelectedIndex() : 0;

        String scenLabel = scenIdx > 0
                ? " [Stress: " + HistoricalScenarios.SCENARIO_NAMES[scenIdx].split(" \\(")[0] + "]" : "";
        setStatus(String.format(
                "Phase 1 starting - %,d combinations to scan (%d paths x %d iters per combo)%s...",
                phase1Total, p1Paths, p1BinIters, scenLabel));

        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdownNow();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "status-scheduler"); t.setDaemon(true); return t;
        });
        scheduler.scheduleAtFixedRate(this::updateStatusLine, 500, 500, TimeUnit.MILLISECONDS);

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override protected Void doInBackground() {
                int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

                // Phase 1
                List<CandidateResult> phase1Results = new CopyOnWriteArrayList<>();
                ForkJoinPool pool1 = new ForkJoinPool(threads);
                try {
                    pool1.submit(() -> candidates.parallelStream().forEach(cand -> {
                        if (cancelRequested) return;
                        long t0 = System.currentTimeMillis();
                        SimInputs ci = inp.withSS(cand.bobStartYear, cand.bobStartMonth,
                                cand.joStartYear, cand.joStartMonth, scenIdx);
                        int yr1 = ILEngine.solveWithdrawalPro(ci.portfolio, ci.baseYear,
                                ci.horizon, ci, 42L, p1Paths, p1BinIters, ci.goGoDuration);
                        phase1Results.add(new CandidateResult(cand, yr1, 0, 0.0, false));
                        phase1Done.incrementAndGet();
                        recordDuration(System.currentTimeMillis() - t0);
                    })).get();
                } catch (Exception ex) { ex.printStackTrace(); }
                finally { pool1.shutdown(); }

                if (cancelRequested) return null;

                List<CandidateResult> bySpend1    = sortedBy(phase1Results, ScoringMode.MAX_SPENDING);
                List<CandidateResult> byLegacy1   = sortedBy(phase1Results, ScoringMode.MAX_LEGACY);
                List<CandidateResult> bySurvival1 = sortedBy(phase1Results, ScoringMode.MAX_SURVIVAL);

                Set<String> seen = new LinkedHashSet<>();
                List<SSCandidate> p2Cands = new ArrayList<>();
                for (List<CandidateResult> list : List.of(bySpend1, byLegacy1, bySurvival1))
                    list.stream().limit(topN).forEach(cr -> { if (seen.add(cr.cand.key())) p2Cands.add(cr.cand); });

                // Phase 2
                currentPhase = 2;
                phaseStartMs = System.currentTimeMillis();
                Arrays.fill(lastComboDurMs, 0); lastComboDurIdx = 0;
                phase2Total = p2Cands.size();
                phase2Done.set(0);

                List<CandidateResult> phase2Results = new CopyOnWriteArrayList<>();
                ForkJoinPool pool2 = new ForkJoinPool(threads);
                try {
                    pool2.submit(() -> p2Cands.parallelStream().forEach(cand -> {
                        if (cancelRequested) return;
                        long t0 = System.currentTimeMillis();
                        SimInputs ci = inp.withSS(cand.bobStartYear, cand.bobStartMonth,
                                cand.joStartYear, cand.joStartMonth, scenIdx);
                        ILEngine.ProResults fr = ILEngine.simulatePro(ci, 42L, p2Paths, p2Fan, p2BinIters);
                        detailCache.put(cand.key(), fr);
                        phase2Results.add(new CandidateResult(cand,
                                fr.yr1Withdrawal, fr.medianFinalBalance, fr.actualPoS, true));
                        phase2Done.incrementAndGet();
                        recordDuration(System.currentTimeMillis() - t0);
                    })).get();
                } catch (Exception ex) { ex.printStackTrace(); }
                finally { pool2.shutdown(); }

                if (cancelRequested) return null;

                Map<String, CandidateResult> p2Map = new HashMap<>();
                for (CandidateResult cr : phase2Results) p2Map.put(cr.cand.key(), cr);
                List<CandidateResult> merged = new ArrayList<>();
                for (CandidateResult cr : phase1Results) {
                    CandidateResult confirmed = p2Map.get(cr.cand.key());
                    merged.add(confirmed != null ? confirmed : cr);
                }

                List<CandidateResult> finalSpend    = sortedBy(merged, ScoringMode.MAX_SPENDING);
                List<CandidateResult> finalLegacy   = sortedBy(merged, ScoringMode.MAX_LEGACY);
                List<CandidateResult> finalSurvival = sortedBy(merged, ScoringMode.MAX_SURVIVAL);
                Map<String, int[]> crossRanks = buildCrossRanks(finalSpend, finalLegacy, finalSurvival);

                SwingUtilities.invokeLater(() -> {
                    tabSpend.populate(finalSpend,    inp, crossRanks, ScoringMode.MAX_SPENDING,  topN);
                    tabLegacy.populate(finalLegacy,  inp, crossRanks, ScoringMode.MAX_LEGACY,   topN);
                    tabSurvival.populate(finalSurvival, inp, crossRanks, ScoringMode.MAX_SURVIVAL, topN);
                });
                return null;
            }

            @Override protected void done() {
                if (scheduler != null) scheduler.shutdownNow();
                currentPhase = 0;
                boolean cancelled = cancelRequested;
                long totalMs = System.currentTimeMillis() - runStartMs;
                SwingUtilities.invokeLater(() -> {
                    btnRun.setEnabled(true);
                    btnCancel.setEnabled(false);
                    setStatus(cancelled
                            ? "Cancelled by user."
                            : String.format("Complete - %,d combinations scanned, %,d confirmed at full fidelity, total time: %s",
                            phase1Total, phase2Total, formatDuration(totalMs)));
                });
            }
        };
        worker.execute();
    }

    private void recordDuration(long ms) {
        synchronized (lastComboDurMs) {
            lastComboDurMs[lastComboDurIdx % lastComboDurMs.length] = ms;
            lastComboDurIdx++;
        }
    }

    private void updateStatusLine() {
        int phase = currentPhase;
        if (phase == 0) return;
        long elapsedMs = System.currentTimeMillis() - phaseStartMs;
        int done  = phase == 1 ? phase1Done.get() : phase2Done.get();
        int total = phase == 1 ? phase1Total       : phase2Total;
        double pct = total > 0 ? done * 100.0 / total : 0;
        long etaMs = estimateEta(done, total, elapsedMs);
        String eta = etaMs >= 0 ? "~" + formatDuration(etaMs) + " remaining" : "estimating...";
        String s = String.format(
                "Phase %d of 2 - %s   %,d / %,d  (%.1f%%)   |   %s   |   %s elapsed",
                phase,
                phase == 1 ? "Scanning combinations..." : "Full fidelity confirmation...",
                done, total, pct, eta,
                formatDuration(System.currentTimeMillis() - runStartMs));
        SwingUtilities.invokeLater(() -> setStatus(s));
    }

    private long estimateEta(int done, int total, long elapsedMs) {
        if (done == 0) return -1;
        long sum = 0; int cnt = 0;
        synchronized (lastComboDurMs) {
            for (long d : lastComboDurMs) if (d > 0) { sum += d; cnt++; }
        }
        long avg = cnt > 0 ? sum / cnt : elapsedMs / done;
        return avg * (total - done);
    }

    private static String formatDuration(long ms) {
        if (ms < 0) return "?";
        long s = ms / 1000;
        if (s < 60)  return s + "s";
        long m = s / 60; s %= 60;
        if (m < 60)  return m + "m " + s + "s";
        long h = m / 60; m %= 60;
        return h + "h " + m + "m";
    }

    private void setStatus(String msg) { lblStatus.setText(msg); }

    // ==============================
    // CANDIDATE GRID
    // ==============================
    private List<SSCandidate> buildCandidateGrid(SimInputs inp) {
        LocalDate today = LocalDate.now();
        List<int[]> bobMonths = ssMonthRange(inp.manBirthYear,   inp.manBirthMonth,
                today.getYear(), today.getMonthValue());
        List<int[]> joMonths  = ssMonthRange(inp.womanBirthYear, inp.womanBirthMonth,
                today.getYear(), today.getMonthValue());
        List<SSCandidate> list = new ArrayList<>(bobMonths.size() * joMonths.size());
        for (int[] b : bobMonths)
            for (int[] j : joMonths)
                list.add(new SSCandidate(b[0], b[1], j[0], j[1]));
        return list;
    }

    private List<int[]> ssMonthRange(int birthYear, int birthMonth,
                                     int startYear, int startMonth) {
        List<int[]> result = new ArrayList<>();
        int endYear = birthYear + 70, endMonth = birthMonth;
        int y = startYear, m = startMonth;
        while (y < endYear || (y == endYear && m <= endMonth)) {
            result.add(new int[]{y, m});
            if (++m > 12) { m = 1; y++; }
        }
        return result;
    }

    // ==============================
    // SORTING & CROSS-RANK HELPERS
    // ==============================
    private List<CandidateResult> sortedBy(List<CandidateResult> list, ScoringMode mode) {
        List<CandidateResult> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> switch (mode) {
            case MAX_SPENDING -> {
                int c = Integer.compare(b.yr1Withdrawal, a.yr1Withdrawal);
                yield c != 0 ? c : Integer.compare(b.medianFinalBalance, a.medianFinalBalance);
            }
            case MAX_LEGACY -> {
                int c = Integer.compare(b.medianFinalBalance, a.medianFinalBalance);
                yield c != 0 ? c : Integer.compare(b.yr1Withdrawal, a.yr1Withdrawal);
            }
            case MAX_SURVIVAL -> {
                int c = Double.compare(b.actualPoS, a.actualPoS);
                yield c != 0 ? c : Integer.compare(b.yr1Withdrawal, a.yr1Withdrawal);
            }
        });
        return sorted;
    }

    private Map<String, int[]> buildCrossRanks(List<CandidateResult> bySpend,
                                               List<CandidateResult> byLegacy,
                                               List<CandidateResult> bySurvival) {
        Map<String, int[]> map = new HashMap<>();
        for (int i = 0; i < bySpend.size();    i++) map.computeIfAbsent(bySpend.get(i).cand.key(),    k -> new int[3])[0] = i+1;
        for (int i = 0; i < byLegacy.size();   i++) map.computeIfAbsent(byLegacy.get(i).cand.key(),   k -> new int[3])[1] = i+1;
        for (int i = 0; i < bySurvival.size(); i++) map.computeIfAbsent(bySurvival.get(i).cand.key(), k -> new int[3])[2] = i+1;
        return map;
    }

    // ==============================
    // DETAIL WINDOW
    // ==============================
    void openDetailWindow(CandidateResult cr, SimInputs baseInp, int scenIdx) {
        String key = cr.cand.key();
        String title = String.format("IL Detail - Bob %02d/%d  Jo %02d/%d",
                cr.cand.bobStartMonth, cr.cand.bobStartYear,
                cr.cand.joStartMonth,  cr.cand.joStartYear);
        DetailWindow win = new DetailWindow(title);
        win.setVisible(true);

        ILEngine.ProResults cached = detailCache.get(key);
        if (cached != null) { win.populate(cached); return; }

        SimInputs ci = baseInp.withSS(cr.cand.bobStartYear, cr.cand.bobStartMonth,
                cr.cand.joStartYear, cr.cand.joStartMonth, scenIdx);
        int p2p = lastP2Paths, p2f = lastP2Fan, p2b = lastP2BinIters;
        SwingWorker<ILEngine.ProResults, Void> w = new SwingWorker<>() {
            @Override protected ILEngine.ProResults doInBackground() {
                return ILEngine.simulatePro(ci, 42L, p2p, p2f, p2b);
            }
            @Override protected void done() {
                try {
                    ILEngine.ProResults res = get();
                    detailCache.put(key, res);
                    win.populate(res);
                } catch (Exception ex) { win.setError(ex.getMessage()); }
            }
        };
        w.execute();
    }

    // ==============================
    // SCORING MODE
    // ==============================
    enum ScoringMode { MAX_SPENDING, MAX_LEGACY, MAX_SURVIVAL }

    // ==============================
    // RESULT TAB
    // ==============================
    static final int COL_PHASE2 = 12;
    static final String CONFIRMED_MARKER = "Y";

    static final String[] COL_NAMES = {
            "Rank", "Bob SS Start", "Bob Age", "Bob Monthly",
            "Jo SS Start", "Jo Age", "Jo Monthly",
            "Combined SS/yr", "Year-1 Withdrawal", "Init Rate %",
            "Median Final Bal", "Actual PoS %", "Full Fidelity",
            "Rank (Spend)", "Rank (Legacy)", "Rank (Survival)"
    };

    class ResultTab extends JPanel {
        private final String      modeLabel, primaryMetric, question;
        final ScoringMode         mode;
        private JLabel            lblWinnerTitle, lblWinnerDetail;
        private JLabel            lblWinnerJust, lblCrossTab, lblPhase2Note;
        private DefaultTableModel tblModel;
        JTable                    tbl;
        private List<CandidateResult> displayList = new ArrayList<>();
        private SimInputs             currentInp  = null;
        private int                   currentScenIdx = 0;

        ResultTab(String label, String primary, String question, ScoringMode mode) {
            super(new BorderLayout(0, 6));
            this.modeLabel = label; this.primaryMetric = primary;
            this.question = question; this.mode = mode;
            setBackground(new Color(245, 245, 242));
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            add(buildWinnerPanel(), BorderLayout.NORTH);
            add(buildTablePanel(),  BorderLayout.CENTER);
        }

        private JPanel buildWinnerPanel() {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(new Color(235, 245, 255));
            p.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(160,200,240),1),
                    BorderFactory.createEmptyBorder(10,14,10,14)));

            addLbl(p, modeLabel.toUpperCase(), Font.BOLD, 11, new Color(80,100,140));
            addLbl(p, question, Font.ITALIC, 13, new Color(70,70,100));
            addLbl(p, "Sorted by: " + primaryMetric, Font.PLAIN, 12, new Color(100,100,130));
            p.add(Box.createVerticalStrut(6));

            lblWinnerTitle  = addLbl(p, "-- awaiting results --", Font.BOLD,  18, new Color(20,70,140));
            lblWinnerDetail = addLbl(p, " ", Font.PLAIN, 13, new Color(30,30,30));
            lblWinnerJust   = addLbl(p, " ", Font.PLAIN, 12, new Color(50,50,80));
            p.add(Box.createVerticalStrut(4));
            lblCrossTab     = addLbl(p, " ", Font.PLAIN, 12, new Color(80,80,80));
            lblPhase2Note   = addLbl(p, " ", Font.ITALIC, 11, new Color(100,100,100));
            p.add(Box.createVerticalStrut(4));
            addLbl(p, "[Hint] Click any Full Fidelity confirmed row (Y) to open the full IL year-by-year table.",
                    Font.ITALIC, 11, new Color(0,80,150));
            return p;
        }

        private JLabel addLbl(JPanel p, String text, int style, int size, Color color) {
            JLabel l = new JLabel(text);
            l.setFont(new Font("SansSerif", style, size));
            l.setForeground(color);
            l.setAlignmentX(LEFT_ALIGNMENT);
            p.add(l);
            return l;
        }

        private JScrollPane buildTablePanel() {
            tblModel = new DefaultTableModel(COL_NAMES, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
                @Override public Class<?> getColumnClass(int c) {
                    return (c == 0 || c == 13 || c == 14 || c == 15) ? Integer.class : Object.class;
                }
            };

            tbl = new JTable(tblModel);
            tbl.setFont(new Font("SansSerif", Font.PLAIN, 13));
            tbl.setRowHeight(24);
            tbl.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
            tbl.setGridColor(new Color(220, 220, 215));
            tbl.setShowGrid(true);
            tbl.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            tbl.setSelectionBackground(new Color(190, 220, 255));

            // Explicit sorter defaulting to ascending on rank (col 0) so rank 1 is always at top
            TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tblModel);
            tbl.setRowSorter(sorter);
            sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
            sorter.sort();

            int[] widths = {40, 90, 60, 90, 90, 60, 90, 100, 120, 75, 110, 75, 45, 85, 85, 90};
            for (int i = 0; i < widths.length && i < tbl.getColumnCount(); i++)
                tbl.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

            DefaultTableCellRenderer colorRenderer = new DefaultTableCellRenderer() {
                final Color GOLD   = new Color(255, 245, 150);
                final Color SILVER = new Color(232, 232, 232);
                final Color BRONZE = new Color(245, 225, 200);
                final Color P2COL  = new Color(240, 252, 240);
                @Override public Component getTableCellRendererComponent(
                        JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                    Component c = super.getTableCellRendererComponent(t,v,sel,foc,row,col);
                    if (!sel) {
                        int modelRow = tbl.convertRowIndexToModel(row);
                        Object rankObj = tblModel.getValueAt(modelRow, 0);
                        int rank = rankObj instanceof Integer ? (Integer) rankObj : 9999;
                        Object p2Obj = tblModel.getValueAt(modelRow, COL_PHASE2);
                        boolean p2 = CONFIRMED_MARKER.equals(p2Obj != null ? p2Obj.toString() : "");
                        if      (rank == 1) c.setBackground(GOLD);
                        else if (rank == 2) c.setBackground(SILVER);
                        else if (rank == 3) c.setBackground(BRONZE);
                        else if (p2)        c.setBackground(P2COL);
                        else                c.setBackground(row%2==0 ? Color.WHITE : new Color(248,248,245));
                        c.setForeground(Color.BLACK);
                    }
                    ((JLabel)c).setHorizontalAlignment((col==1||col==4) ? LEFT : RIGHT);
                    return c;
                }
            };
            tbl.setDefaultRenderer(Object.class, colorRenderer);

            // Integer renderer for rank columns
            DefaultTableCellRenderer intRenderer = new DefaultTableCellRenderer() {
                final Color GOLD   = new Color(255, 245, 150);
                final Color SILVER = new Color(232, 232, 232);
                final Color BRONZE = new Color(245, 225, 200);
                final Color P2COL  = new Color(240, 252, 240);
                @Override public Component getTableCellRendererComponent(
                        JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                    Component c = super.getTableCellRendererComponent(t,v,sel,foc,row,col);
                    ((JLabel)c).setHorizontalAlignment(RIGHT);
                    if (!sel) {
                        int modelRow = tbl.convertRowIndexToModel(row);
                        Object rankObj = tblModel.getValueAt(modelRow, 0);
                        int rank = rankObj instanceof Integer ? (Integer) rankObj : 9999;
                        if      (rank == 1) c.setBackground(GOLD);
                        else if (rank == 2) c.setBackground(SILVER);
                        else if (rank == 3) c.setBackground(BRONZE);
                        else {
                            Object p2Obj = tblModel.getValueAt(modelRow, COL_PHASE2);
                            boolean p2 = CONFIRMED_MARKER.equals(p2Obj != null ? p2Obj.toString() : "");
                            c.setBackground(p2 ? P2COL : (row%2==0?Color.WHITE:new Color(248,248,245)));
                        }
                        c.setForeground(Color.BLACK);
                    }
                    return c;
                }
            };
            tbl.getColumnModel().getColumn(0).setCellRenderer(intRenderer);
            tbl.getColumnModel().getColumn(13).setCellRenderer(intRenderer);
            tbl.getColumnModel().getColumn(14).setCellRenderer(intRenderer);
            tbl.getColumnModel().getColumn(15).setCellRenderer(intRenderer);

            tbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int viewRow = tbl.rowAtPoint(e.getPoint());
                    if (viewRow < 0) return;
                    int modelRow = tbl.convertRowIndexToModel(viewRow);
                    Object p2Obj = tblModel.getValueAt(modelRow, COL_PHASE2);
                    if (!CONFIRMED_MARKER.equals(p2Obj != null ? p2Obj.toString() : "")) return;
                    if (modelRow >= displayList.size()) return;
                    CandidateResult cr = displayList.get(modelRow);
                    if (currentInp != null) openDetailWindow(cr, currentInp, currentScenIdx);
                }
            });

            JScrollPane sp = new JScrollPane(tbl);
            sp.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
            return sp;
        }

        void reset() {
            SwingUtilities.invokeLater(() -> {
                tblModel.setRowCount(0);
                displayList.clear();
                lblWinnerTitle.setText("-- running... --");
                lblWinnerDetail.setText(" ");
                lblWinnerJust.setText(" ");
                lblCrossTab.setText(" ");
                lblPhase2Note.setText(" ");
            });
        }

        void populate(List<CandidateResult> sorted, SimInputs baseInp,
                      Map<String, int[]> crossRanks, ScoringMode scoreMode, int topN) {
            this.currentInp = baseInp;
            this.currentScenIdx = baseInp.scenarioIndex;
            displayList = new ArrayList<>(sorted);

            tblModel.setRowCount(0);
            for (int rank = 0; rank < sorted.size(); rank++) {
                CandidateResult cr = sorted.get(rank);
                SSCandidate c = cr.cand;
                SimInputs ci = baseInp.withSS(c.bobStartYear, c.bobStartMonth,
                        c.joStartYear, c.joStartMonth, baseInp.scenarioIndex);
                double bobMo = ILEngine.calcSSMonthlyBenefit(ci.manPIA, ci.manBirthYear,
                        ci.manBirthMonth, c.bobStartYear, c.bobStartMonth);
                double joMo  = ILEngine.calcSSMonthlyBenefit(ci.womanPIA, ci.womanBirthYear,
                        ci.womanBirthMonth, c.joStartYear, c.joStartMonth);
                int bobAgeM = (c.bobStartYear-ci.manBirthYear)*12+(c.bobStartMonth-ci.manBirthMonth);
                int joAgeM  = (c.joStartYear-ci.womanBirthYear)*12+(c.joStartMonth-ci.womanBirthMonth);
                double rate = ci.portfolio>0 ? cr.yr1Withdrawal/(double)ci.portfolio*100.0 : 0;
                int[] ranks = crossRanks.getOrDefault(c.key(), new int[3]);

                tblModel.addRow(new Object[]{
                        rank+1,
                        String.format("%02d/%d", c.bobStartMonth, c.bobStartYear),
                        ageStr(bobAgeM),
                        CURRENCY.format((long)bobMo),
                        String.format("%02d/%d", c.joStartMonth, c.joStartYear),
                        ageStr(joAgeM),
                        CURRENCY.format((long)joMo),
                        CURRENCY.format((long)((bobMo+joMo)*12)),
                        CURRENCY.format(cr.yr1Withdrawal),
                        String.format("%.2f%%", rate),
                        cr.confirmed ? CURRENCY.format(cr.medianFinalBalance) : "--",
                        cr.confirmed ? String.format("%.1f%%", cr.actualPoS*100) : "--",
                        cr.confirmed ? CONFIRMED_MARKER : "",
                        ranks[0]>0 ? ranks[0] : null,
                        ranks[1]>0 ? ranks[1] : null,
                        ranks[2]>0 ? ranks[2] : null,
                });
            }

            if (!sorted.isEmpty()) {
                CandidateResult w  = sorted.get(0);
                SSCandidate wc = w.cand;
                SimInputs wi = baseInp.withSS(wc.bobStartYear, wc.bobStartMonth,
                        wc.joStartYear, wc.joStartMonth, baseInp.scenarioIndex);
                double wBob = ILEngine.calcSSMonthlyBenefit(wi.manPIA, wi.manBirthYear,
                        wi.manBirthMonth, wc.bobStartYear, wc.bobStartMonth);
                double wJo  = ILEngine.calcSSMonthlyBenefit(wi.womanPIA, wi.womanBirthYear,
                        wi.womanBirthMonth, wc.joStartYear, wc.joStartMonth);
                int ba = (wc.bobStartYear-wi.manBirthYear)*12+(wc.bobStartMonth-wi.manBirthMonth);
                int ja = (wc.joStartYear-wi.womanBirthYear)*12+(wc.joStartMonth-wi.womanBirthMonth);

                lblWinnerTitle.setText(String.format(
                        "Winner: Bob starts %02d/%d (age %s)  |  Jo starts %02d/%d (age %s)",
                        wc.bobStartMonth, wc.bobStartYear, ageStr(ba),
                        wc.joStartMonth,  wc.joStartYear,  ageStr(ja)));
                lblWinnerDetail.setText(String.format(
                        "Bob: %s/mo  |  Jo: %s/mo  |  Combined: %s/yr  |  Year-1 Withdrawal: %s  |  Init Rate: %.2f%%",
                        CURRENCY.format((long)wBob), CURRENCY.format((long)wJo),
                        CURRENCY.format((long)((wBob+wJo)*12)),
                        CURRENCY.format(w.yr1Withdrawal),
                        wi.portfolio>0 ? w.yr1Withdrawal/(double)wi.portfolio*100.0 : 0));
                lblWinnerJust.setText(buildJustification(w, sorted, scoreMode, wi));
                int[] ranks = crossRanks.getOrDefault(wc.key(), new int[3]);
                lblCrossTab.setText(String.format(
                        "Cross-tab ranks:  #%s on Max Spending  |  #%s on Max Legacy  |  #%s on Max Survival",
                        ranks[0]>0?ranks[0]:"?", ranks[1]>0?ranks[1]:"?", ranks[2]>0?ranks[2]:"?"));
                lblPhase2Note.setText(w.confirmed
                        ? "Winner confirmed at full fidelity - click rank 1 row to view full IL year-by-year table"
                        : "Winner from Phase 1 scan only - not yet full-fidelity confirmed");
            }
        }

        private String buildJustification(CandidateResult w, List<CandidateResult> sorted,
                                          ScoringMode mode, SimInputs wi) {
            if (sorted.size() < 2) return "Only one combination evaluated.";
            CandidateResult second = sorted.get(1);
            return switch (mode) {
                case MAX_SPENDING -> String.format(
                        "Sustains %s/yr at %.0f%% PoS target - %s/yr more than the next-best option.",
                        CURRENCY.format(w.yr1Withdrawal), wi.targetPoS*100,
                        CURRENCY.format(Math.abs(w.yr1Withdrawal-second.yr1Withdrawal)));
                case MAX_LEGACY -> String.format(
                        "Median portfolio at end of %d-year horizon: %s - %s more than next-best.",
                        wi.horizon,
                        w.confirmed ? CURRENCY.format(w.medianFinalBalance) : "pending Phase 2",
                        w.confirmed&&second.confirmed
                                ? CURRENCY.format(Math.abs(w.medianFinalBalance-second.medianFinalBalance)) : "--");
                case MAX_SURVIVAL -> String.format(
                        "Actual PoS: %.1f%% - %.1f ppt above next-best. Guaranteed floor income reduces sequence-of-returns risk.",
                        w.confirmed ? w.actualPoS*100 : 0,
                        w.confirmed&&second.confirmed ? (w.actualPoS-second.actualPoS)*100 : 0);
            };
        }
    }

    // ==============================
    // DETAIL WINDOW
    // ==============================
    static class DetailWindow extends JFrame {
        private static final String[] DET_COLS = {
                "Man age", "Cal yr", "Portfolio bal",
                "80% PoS wd", "Actual wd", "Wd %", "Alert",
                "Man SS", "Woman SS", "Annuity", "Guaranteed",
                "Living", "Medical", "Tax",
                "Total spend", "Total income", "Surplus/gap",
                "Infl factor",
                "Man RMD", "Woman RMD", "Comb RMD", "->Roth/MM",
                "Bal Delta"
        };
        private DefaultTableModel     model;
        private JTable                table;
        private JLabel                lblTitle;
        private JToggleButton         tglReal;
        private ILEngine.ProResults   lastRes;

        DetailWindow(String title) {
            super(title);
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setLayout(new BorderLayout(0, 4));
            getContentPane().setBackground(new Color(245, 245, 242));

            lblTitle = new JLabel("  Computing - please wait...");
            lblTitle.setFont(new Font("SansSerif", Font.ITALIC, 13));
            lblTitle.setForeground(new Color(80,80,80));
            lblTitle.setBorder(BorderFactory.createEmptyBorder(6,10,4,10));

            tglReal = new JToggleButton("Real $");
            tglReal.setFont(new Font("SansSerif", Font.PLAIN, 12));
            tglReal.addActionListener(e -> repopulate());

            JPanel top = new JPanel(new BorderLayout());
            top.setBackground(new Color(240,240,237));
            top.setBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(200,198,193)));
            top.add(lblTitle, BorderLayout.CENTER);
            top.add(tglReal,  BorderLayout.EAST);
            add(top, BorderLayout.NORTH);

            model = new DefaultTableModel(DET_COLS, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            table = new JTable(model);
            table.setFont(new Font("SansSerif", Font.PLAIN, 12));
            table.setRowHeight(22);
            table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
            table.setGridColor(new Color(220,220,215));
            table.setShowGrid(true);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

            int[] dw = {55,55,100, 100,100,65,90, 75,80,72,90, 72,72,78, 88,95,85, 72, 80,85,90,85, 90};
            for (int i=0; i<dw.length && i<table.getColumnCount(); i++)
                table.getColumnModel().getColumn(i).setPreferredWidth(dw[i]);

            table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                final Color GOGO_BG   = new Color(232,248,240);
                final Color GOGO_WD   = new Color(180,230,205);
                final Color AMBER_BG  = new Color(255,220,100);
                final Color AMBER_FG  = new Color(130, 80,  0);
                final Color ORANGE_BG = new Color(255,200,120);
                final Color ORANGE_FG = new Color(140, 60,  0);
                @Override public Component getTableCellRendererComponent(
                        JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                    Component c = super.getTableCellRendererComponent(t,v,sel,foc,row,col);
                    if (!sel && lastRes!=null && row<lastRes.medianRows.size()) {
                        ILEngine.EnhRow er = lastRes.medianRows.get(row);
                        boolean goGo = er.goGoActive;
                        c.setBackground(goGo ? GOGO_BG : (row%2==0?Color.WHITE:new Color(248,248,245)));
                        c.setForeground(Color.BLACK);
                        String s = v==null ? "" : v.toString();
                        if ((col==3||col==4)&&goGo) { c.setBackground(GOGO_WD); c.setForeground(new Color(0,90,50)); }
                        else if (col==6) {
                            if ("[^] raise alert".equals(er.alert)) c.setForeground(new Color(59,109,17));
                            else if ("[v] cut alert".equals(er.alert)) c.setForeground(new Color(163,45,45));
                        } else if (col==20||col==21) {
                            if (er.rmdOverage>0) { c.setBackground(ORANGE_BG); c.setForeground(ORANGE_FG); }
                        } else if ((col==18||col==19)&&er.rmdOverage>0) {
                            c.setBackground(AMBER_BG); c.setForeground(AMBER_FG);
                        } else if (col==16) {
                            c.setForeground(s.startsWith("-")?new Color(180,30,30):new Color(59,109,17));
                        } else if (col==22) {
                            c.setForeground(s.startsWith("-")?new Color(180,30,30):new Color(59,109,17));
                        }
                    }
                    ((JLabel)c).setHorizontalAlignment(col<=1?LEFT:RIGHT);
                    return c;
                }
            });

            add(new JScrollPane(table), BorderLayout.CENTER);
            setSize(1800, 700);
            setLocationRelativeTo(null);
        }

        void populate(ILEngine.ProResults res) {
            this.lastRes = res;
            SimInputs inp = res.inp;
            NumberFormat cf = NumberFormat.getCurrencyInstance(Locale.US);
            cf.setMaximumFractionDigits(0);
            String scenLabel = inp.scenarioIndex > 0
                    ? "  [Stress: " + HistoricalScenarios.SCENARIO_NAMES[inp.scenarioIndex].split(" \\(")[0] + "]" : "";
            lblTitle.setText(String.format(
                    "  Bob SS: %02d/%d ($%,.0f/mo)  |  Jo SS: %02d/%d ($%,.0f/mo)"
                            + "  |  Year-1 Wd: %s  |  Actual PoS: %.1f%%  |  Median Final: %s%s",
                    inp.manSSStartMonth, inp.manSSStartYear, inp.manSSMonthly,
                    inp.womanSSStartMonth, inp.womanSSStartYear, inp.womanSSMonthly,
                    cf.format(res.yr1Withdrawal), res.actualPoS*100,
                    cf.format(res.medianFinalBalance), scenLabel));
            repopulate();
        }

        private void repopulate() {
            if (lastRes==null) return;
            NumberFormat cf = NumberFormat.getCurrencyInstance(Locale.US);
            cf.setMaximumFractionDigits(0);
            boolean real = tglReal.isSelected();
            model.setRowCount(0);
            for (ILEngine.EnhRow r : lastRes.medianRows) {
                double d = real ? r.inflFactor : 1.0;
                model.addRow(new Object[]{
                        r.manAge, r.calYear,
                        cf.format((long)(r.balance/d)),
                        r.drawing ? cf.format((long)(r.withdrawal/d)) : "--",
                        r.drawing ? cf.format((long)(r.wdActual/d))   : "--",
                        r.drawing ? String.format("%.2f%%", r.wdPct)  : "--",
                        r.alert,
                        r.manSS>0    ? cf.format((long)(r.manSS/d))    : "--",
                        r.womanSS>0  ? cf.format((long)(r.womanSS/d))  : "--",
                        r.annuity>0  ? cf.format((long)(r.annuity/d))  : "--",
                        r.guaranteed>0 ? cf.format((long)(r.guaranteed/d)) : "--",
                        r.drawing ? cf.format((long)(r.living/d))    : "--",
                        r.drawing ? cf.format((long)(r.medical/d))   : "--",
                        r.drawing ? cf.format((long)(r.tax/d))       : "--",
                        r.drawing ? cf.format((long)(r.totalSpend/d)) : "--",
                        cf.format((long)(r.totalIncome/d)),
                        r.drawing ? ((r.surplus>=0?"+":"-")+cf.format((long)(Math.abs(r.surplus)/d))) : "--",
                        String.format("%.3f", r.inflFactor),
                        r.manRmd>0    ? cf.format((long)(r.manRmd/d))    : "--",
                        r.womanRmd>0  ? cf.format((long)(r.womanRmd/d))  : "--",
                        r.combRmd>0   ? cf.format((long)(r.combRmd/d))   : "--",
                        r.rmdOverage>0 ? cf.format((long)(r.rmdOverage/d)) : "--",
                        (r.balDelta>=0?"+":"-")+cf.format((long)(Math.abs(r.balDelta)/d)),
                });
            }
        }

        void setError(String msg) {
            lblTitle.setText("  Error: " + (msg!=null?msg:"unknown"));
            lblTitle.setForeground(new Color(180,30,30));
        }
    }

    // ==============================
    // IL ENGINE (static inner class)
    // ==============================
    static class ILEngine {

        private static final int RMD_AGE = 75;
        private static final Map<Integer,Double> ULT = new HashMap<>();
        static {
            ULT.put(75,24.6); ULT.put(76,23.7); ULT.put(77,22.9); ULT.put(78,22.0);
            ULT.put(79,21.1); ULT.put(80,20.2); ULT.put(81,19.4); ULT.put(82,18.5);
            ULT.put(83,17.7); ULT.put(84,16.8); ULT.put(85,16.0); ULT.put(86,15.2);
            ULT.put(87,14.4); ULT.put(88,13.7); ULT.put(89,12.9); ULT.put(90,12.2);
            ULT.put(91,11.5); ULT.put(92,10.8); ULT.put(93,10.1); ULT.put(94, 9.5);
            ULT.put(95, 8.9); ULT.put(96, 8.4); ULT.put(97, 7.8); ULT.put(98, 7.3);
            ULT.put(99, 6.8); ULT.put(100,6.4);
        }

        static ProResults simulatePro(SimInputs inp, long seed,
                                      int solvePaths, int fanPaths, int binIters) {
            ProResults res = new ProResults();
            res.inp = inp; res.medianRows = new ArrayList<>();
            int startY = inp.withdrawStartYear - inp.baseYear;

            double[][] fanBalances    = new double[fanPaths][inp.horizon+1];
            double[][] fanWithdrawals = new double[fanPaths][inp.horizon];
            double[][] fanInflFactors = new double[fanPaths][inp.horizon+1];
            double[][] fpManTrad      = new double[fanPaths][inp.horizon+1];
            double[][] fpManT401K     = new double[fanPaths][inp.horizon+1];
            double[][] fpWomanTrad    = new double[fanPaths][inp.horizon+1];
            double[][] fpWomanT401K   = new double[fanPaths][inp.horizon+1];

            for (int p=0; p<fanPaths; p++) {
                SeededRng rng = new SeededRng(p*17+11+seed);
                double b=inp.portfolio, mt=inp.manTradIRA, m4=inp.manTrad401K;
                double wt=inp.womanTradIRA, w4=inp.womanTrad401K;
                fanBalances[p][0]=b; fanInflFactors[p][0]=1.0;
                fpManTrad[p][0]=mt; fpManT401K[p][0]=m4;
                fpWomanTrad[p][0]=wt; fpWomanT401K[p][0]=w4;
                for (int y=0; y<inp.horizon; y++) {
                    int calYear=inp.baseYear+y;
                    int manAge=calYear-inp.manBirthYear, womanAge=calYear-inp.womanBirthYear;
                    boolean drawing=calYear>=inp.withdrawStartYear;
                    double[] ri=getReturnAndInflation(inp,y,rng);
                    double ret=ri[0], infl=ri[1];
                    fanInflFactors[p][y+1]=fanInflFactors[p][y]*(1+infl);
                    int goGoRem=Math.max(0,inp.goGoDuration-Math.max(0,y-startY));
                    int wd=0;
                    if (drawing&&b>0)
                        wd=solveWithdrawalPro((int)b,calYear,inp.horizon-y,inp,
                                p*1000L+y*37+seed,Math.max(20,solvePaths/8),Math.min(binIters,10),goGoRem);
                    double mult=(goGoRem>0)?inp.goGoMultiplier:1.0;
                    int wdActual=drawing?(int)(wd*mult):0;
                    fanWithdrawals[p][y]=wdActual;
                    b=Math.max(0,b*(1+ret)-wdActual);
                    mt=Math.max(0,mt*(1+ret)-calcRmd(mt,manAge));
                    m4=Math.max(0,m4*(1+ret)-calcRmd(m4,manAge));
                    wt=Math.max(0,wt*(1+ret)-calcRmd(wt,womanAge));
                    w4=Math.max(0,w4*(1+ret)-calcRmd(w4,womanAge));
                    fanBalances[p][y+1]=b;
                    fpManTrad[p][y+1]=mt; fpManT401K[p][y+1]=m4;
                    fpWomanTrad[p][y+1]=wt; fpWomanT401K[p][y+1]=w4;
                }
            }

            int survived=0;
            for (int p=0; p<fanPaths; p++) if (fanBalances[p][inp.horizon]>0) survived++;
            res.actualPoS=survived/(double)fanPaths;
            res.fanPathCount=fanPaths;
            double[] finals=new double[fanPaths];
            for (int p=0; p<fanPaths; p++) finals[p]=fanBalances[p][inp.horizon];
            Arrays.sort(finals);
            res.medianFinalBalance=(int)finals[fanPaths/2];

            int yr1Wd=solveWithdrawalPro(inp.portfolio,inp.baseYear,inp.horizon,
                    inp,999L+seed,solvePaths,binIters,inp.goGoDuration);
            res.yr1Withdrawal=yr1Wd;

            for (int y=0; y<inp.horizon; y++) {
                int calYear=inp.baseYear+y;
                int manAge=calYear-inp.manBirthYear, womanAge=calYear-inp.womanBirthYear;
                boolean drawing=calYear>=inp.withdrawStartYear;

                double[] balArr=new double[fanPaths];
                for (int p=0;p<fanPaths;p++) balArr[p]=fanBalances[p][y];
                Arrays.sort(balArr); int medBal=(int)balArr[fanPaths/2];

                double[] mtArr=new double[fanPaths],m4Arr=new double[fanPaths];
                double[] wtArr=new double[fanPaths],w4Arr=new double[fanPaths];
                for (int p=0;p<fanPaths;p++){
                    mtArr[p]=fpManTrad[p][y]; m4Arr[p]=fpManT401K[p][y];
                    wtArr[p]=fpWomanTrad[p][y]; w4Arr[p]=fpWomanT401K[p][y];
                }
                Arrays.sort(mtArr); Arrays.sort(m4Arr); Arrays.sort(wtArr); Arrays.sort(w4Arr);
                double medMt=mtArr[fanPaths/2],medM4=m4Arr[fanPaths/2];
                double medWt=wtArr[fanPaths/2],medW4=w4Arr[fanPaths/2];

                int goGoRem=Math.max(0,inp.goGoDuration-Math.max(0,y-startY));
                int wd=drawing&&medBal>0
                        ?solveWithdrawalPro(medBal,calYear,inp.horizon-y,inp,
                        999L+y*37+seed,solvePaths,binIters,goGoRem):0;

                double manRmd=calcRmd(medMt,manAge)+calcRmd(medM4,manAge);
                double womanRmd=calcRmd(medWt,womanAge)+calcRmd(medW4,womanAge);
                double combRmd=manRmd+womanRmd;
                double goGoMult=(goGoRem>0)?inp.goGoMultiplier:1.0;
                double startPror=(drawing&&calYear==inp.withdrawStartYear)
                        ?(13.0-inp.withdrawStartMonth)/12.0:1.0;
                int wdActual=drawing?(int)(wd*goGoMult*startPror):0;
                int rmdOverage=drawing?Math.max(0,(int)combRmd-wdActual):0;

                double[] inflArr=new double[fanPaths];
                for (int p=0;p<fanPaths;p++) inflArr[p]=fanInflFactors[p][y];
                Arrays.sort(inflArr); double inflFactor=inflArr[fanPaths/2];

                double[] nextBal=new double[fanPaths];
                for (int p=0;p<fanPaths;p++) nextBal[p]=fanBalances[p][y+1];
                Arrays.sort(nextBal); int nextMedBal=(int)nextBal[fanPaths/2];

                double manSS=manSSThisYear(inp,y), womanSS=womanSSThisYear(inp,y);
                double ann=annuityThisYear(inp,y);
                double guaranteed=manSS+womanSS+ann;
                double living=drawing?inp.livingExp*inflFactor:0;
                double medical=drawing?inp.medical*Math.pow(1+inp.medInflation,y):0;
                double tax=taxThisYear(inp,y);
                double totalSpend=drawing?living+medical+tax:0;
                double totalIncome=guaranteed+wdActual;
                double surplus=totalIncome-totalSpend;
                double wdPct=(drawing&&medBal>0)?wdActual/(double)medBal*100.0:0;

                String alert="--";
                if (drawing&&yr1Wd>0) {
                    double vsYr1=(wdActual-(int)(yr1Wd*goGoMult))/(double)(yr1Wd*goGoMult);
                    if      (vsYr1>=0.20) alert="[^] raise alert";
                    else if (vsYr1<=-0.20) alert="[v] cut alert";
                }

                EnhRow row=new EnhRow();
                row.calYear=calYear; row.manAge=manAge; row.womanAge=womanAge;
                row.balance=medBal; row.withdrawal=wd; row.wdActual=wdActual; row.wdPct=wdPct;
                row.manRmd=(int)manRmd; row.womanRmd=(int)womanRmd;
                row.combRmd=(int)combRmd; row.rmdOverage=rmdOverage;
                row.manSS=(int)manSS; row.womanSS=(int)womanSS;
                row.annuity=(int)ann; row.guaranteed=(int)guaranteed;
                row.living=(int)living; row.medical=(int)medical; row.tax=(int)tax;
                row.totalSpend=(int)totalSpend; row.totalIncome=(int)totalIncome;
                row.surplus=(int)surplus; row.inflFactor=inflFactor;
                row.drawing=drawing; row.goGoActive=goGoRem>0; row.goGoMult=goGoMult;
                row.alert=alert; row.balDelta=nextMedBal-medBal;
                row.investmentGrowth=(int)(medBal*inp.nomReturn);
                res.medianRows.add(row);
            }
            res.fanBalances=fanBalances; res.fanWithdrawals=fanWithdrawals;
            res.fanInflFactors=fanInflFactors;
            return res;
        }

        static int solveWithdrawalPro(int balance, int fromYear, int horizon,
                                      SimInputs inp, long seed,
                                      int solvePaths, int binIters, int goGoYearsRem) {
            if (balance<=0||horizon<=0) return 0;
            double lo=0, hi=balance*0.22;
            for (int i=0;i<binIters;i++) {
                double mid=(lo+hi)/2.0;
                // Pass fromYear so survivalRatePro computes correct SS + annuity offsets
                double rate=survivalRatePro(balance,fromYear,horizon,mid,inp,seed+i*31L,solvePaths,goGoYearsRem);
                if (rate>inp.targetPoS) lo=mid; else hi=mid;
            }
            return (int)((lo+hi)/2.0);
        }

        /**
         * Survival rate estimator. firstYrWd is the PORTFOLIO DRAW being tested.
         * Guaranteed income (SS COLA-adjusted + annuity flat) is subtracted from
         * the gross spending need each year so the portfolio only covers the net gap.
         * fromYear is the calendar year at the start of this horizon slice.
         */
        static double survivalRatePro(int balance, int fromYear, int horizon, double firstYrWd,
                                      SimInputs inp, long seed,
                                      int solvePaths, int goGoYearsRem) {
            int survived=0;
            for (int i=0;i<solvePaths;i++) {
                SeededRng rng=new SeededRng(seed*1000L+i*7+3);
                double b=balance;
                for (int y=0;y<horizon;y++) {
                    double[] ri=getReturnAndInflation(inp,y,rng);
                    double ret=ri[0],infl=ri[1];
                    int goGoRem=Math.max(0,goGoYearsRem-y);
                    double mult=(goGoRem>0)?inp.goGoMultiplier:1.0;

                    // Gross portfolio draw (inflation-escalated, go-go adjusted)
                    double portDraw=(b>0)?firstYrWd*mult*Math.pow(1+infl,y):0;

                    // Guaranteed income offsets: SS (COLA) + annuity (flat non-COLA)
                    // simY is year index relative to inp.baseYear
                    int simY=(fromYear-inp.baseYear)+y;
                    double guaranteed=manSSThisYear(inp,simY)+womanSSThisYear(inp,simY)+annuityThisYear(inp,simY);

                    // Net portfolio draw after guaranteed income covers part of spending
                    double netDraw=Math.max(0, portDraw-guaranteed);

                    b=b*(1+ret)-netDraw;
                    if (b<=0) break;
                }
                if (b>0) survived++;
            }
            return survived/(double)solvePaths;
        }

        static double[] getReturnAndInflation(SimInputs inp, int simYear, SeededRng rng) {
            double[][] seq = HistoricalScenarios.getSequence(inp.scenarioIndex);
            if (seq!=null && simYear<seq.length) return new double[]{seq[simYear][1], seq[simYear][2]};
            double ret=inp.nomReturn+inp.stdDev*rng.nextGaussian();
            double infl=Math.max(0,inp.inflation+inp.inflationStdDev*rng.nextGaussian());
            return new double[]{ret,infl};
        }

        static double manSSThisYear(SimInputs inp,int y){
            int c=inp.baseYear+y;
            if (c<inp.manSSStartYear) return 0;
            if (c==inp.manSSStartYear) return inp.manSSAmount*(13.0-inp.manSSStartMonth)/12.0;
            return inp.manSSAmount*Math.pow(1+inp.ssCola,c-inp.manSSStartYear);
        }
        static double womanSSThisYear(SimInputs inp,int y){
            int c=inp.baseYear+y;
            if (c<inp.womanSSStartYear) return 0;
            if (c==inp.womanSSStartYear) return inp.womanSSAmount*(13.0-inp.womanSSStartMonth)/12.0;
            return inp.womanSSAmount*Math.pow(1+inp.ssCola,c-inp.womanSSStartYear);
        }
        static double annuityThisYear(SimInputs inp,int y){
            int c=inp.baseYear+y;
            if (c<inp.annuityStartYear) return 0;
            if (c==inp.annuityStartYear) return inp.annuity*(13.0-inp.annuityStartMonth)/12.0;
            return inp.annuity;
        }
        static double taxThisYear(SimInputs inp,int y){
            int c=inp.baseYear+y;
            if (c<inp.withdrawStartYear) return 0;
            return inp.baseTax*Math.pow(1+inp.taxInflation,c-inp.withdrawStartYear);
        }
        static double calcRmd(double bal,int age){
            if (age<RMD_AGE) return 0;
            Double f=ULT.get(Math.min(age,100)); if (f==null) f=6.4; return bal/f;
        }
        static int fraMonths(int birthYear){
            if (birthYear<=1954) return 66*12;
            if (birthYear==1955) return 66*12+2;
            if (birthYear==1956) return 66*12+4;
            if (birthYear==1957) return 66*12+6;
            if (birthYear==1958) return 66*12+8;
            if (birthYear==1959) return 66*12+10;
            return 67*12;
        }
        static double calcSSMonthlyBenefit(int pia,int birthYear,int birthMonth,
                                           int claimYear,int claimMonth){
            if (pia<=0) return 0;
            int ageMonths=(claimYear-birthYear)*12+(claimMonth-birthMonth);
            int fra=fraMonths(birthYear);
            if (ageMonths<=fra){
                int me=fra-ageMonths;
                double r=me<=36?me*(5.0/900.0):36*(5.0/900.0)+(me-36)*(5.0/1200.0);
                return pia*(1.0-r);
            } else {
                int ml=Math.min(ageMonths-fra,70*12-fra);
                return pia*(1.0+ml*(8.0/1200.0));
            }
        }

        static class ProResults {
            SimInputs inp;
            int yr1Withdrawal, medianFinalBalance, fanPathCount;
            double actualPoS;
            List<EnhRow> medianRows;
            double[][] fanBalances, fanWithdrawals, fanInflFactors;
        }

        static class EnhRow {
            int calYear,manAge,womanAge;
            int balance,withdrawal,wdActual;
            double wdPct;
            int manRmd,womanRmd,combRmd,rmdOverage;
            int manSS,womanSS,annuity,guaranteed;
            int living,medical,tax,totalSpend,totalIncome,surplus;
            double inflFactor;
            boolean drawing,goGoActive;
            double goGoMult;
            String alert;
            int balDelta,investmentGrowth;
        }
    }

    // ==============================
    // HISTORICAL SCENARIOS (identical data to IL)
    // ==============================
    static class HistoricalScenarios {
        static final String[] SCENARIO_NAMES = {
                "Random (normal distribution -- default)",
                "Great Depression (1929-1942)",
                "Stagflation Era (1966-1982)",
                "Dot-com Crash (2000-2006)",
                "Housing Crisis / GFC (2007-2013)"
        };
        static double[][] getSequence(int idx){
            return switch(idx){
                case 1 -> GREAT_DEPRESSION;
                case 2 -> STAGFLATION;
                case 3 -> DOT_COM;
                case 4 -> HOUSING_CRISIS;
                default -> null;
            };
        }
        static String getDescription(int idx){
            return switch(idx){
                case 1 -> "<html><b>Great Depression (1929-1942)</b><br>"
                        + "14 years of actual S&P 500 total returns and CPI data.<br>"
                        + "Includes the crash (1929-1932, cumulative -79%), the volatile<br>"
                        + "recovery (1933-1936), the 1937 relapse (-35%), and stabilization.</html>";
                case 2 -> "<html><b>Stagflation Era (1966-1982)</b><br>"
                        + "17 years of actual S&P 500 total returns and CPI data.<br>"
                        + "Characterized by low/negative real returns with high inflation.<br>"
                        + "The worst sequence-of-returns era for retirees in modern history.</html>";
                case 3 -> "<html><b>Dot-com Crash (2000-2006)</b><br>"
                        + "7 years of actual S&P 500 total returns and CPI data.<br>"
                        + "Three consecutive down years (2000-2002), then strong recovery.</html>";
                case 4 -> "<html><b>Housing Crisis / GFC (2007-2013)</b><br>"
                        + "7 years of actual S&P 500 total returns and CPI data.<br>"
                        + "The 2008 crash (-37%) followed by one of the fastest recoveries on record.</html>";
                default -> "<html>Random normal distribution based on your input parameters.</html>";
            };
        }
        // { calendarYear, equityTotalReturn, CPI_inflation }
        private static final double[][] GREAT_DEPRESSION = {
                {1929,-0.0830,0.001},{1930,-0.2512,-0.023},{1931,-0.4384,-0.089},
                {1932,-0.0864,-0.103},{1933,0.4998,-0.051},{1934,-0.0119,0.033},
                {1935,0.4674,0.025},{1936,0.3194,0.014},{1937,-0.3534,0.037},
                {1938,0.2928,-0.021},{1939,-0.0110,-0.014},{1940,-0.1067,0.007},
                {1941,-0.1277,0.095},{1942,0.1917,0.090},
        };
        private static final double[][] STAGFLATION = {
                {1966,-0.0997,0.042},{1967,0.2380,0.034},{1968,0.1081,0.047},
                {1969,-0.0824,0.062},{1970,0.0356,0.056},{1971,0.1422,0.033},
                {1972,0.1876,0.034},{1973,-0.1431,0.087},{1974,-0.2590,0.123},
                {1975,0.3700,0.069},{1976,0.2383,0.049},{1977,-0.0698,0.067},
                {1978,0.0651,0.090},{1979,0.1852,0.133},{1980,0.3174,0.121},
                {1981,-0.0470,0.089},{1982,0.2042,0.038},
        };
        private static final double[][] DOT_COM = {
                {2000,-0.0910,0.034},{2001,-0.1189,0.028},{2002,-0.2197,0.016},
                {2003,0.2836,0.023},{2004,0.1074,0.027},{2005,0.0483,0.034},
                {2006,0.1561,0.032},
        };
        private static final double[][] HOUSING_CRISIS = {
                {2007,0.0548,0.028},{2008,-0.3700,0.038},{2009,0.2646,0.003},
                {2010,0.1506,0.016},{2011,0.0211,0.032},{2012,0.1600,0.021},
                {2013,0.3239,0.015},
        };
    }

    // ==============================
    // SEEDED RNG
    // ==============================
    static class SeededRng {
        private long state;
        SeededRng(long seed){ state=seed^0x6c62272e07bb0142L; }
        private double nextUniform(){
            state=state*6364136223846793005L+1442695040888963407L;
            long bits=(state>>>33)^state;
            return (bits>>>1)/(double)Long.MAX_VALUE;
        }
        double nextGaussian(){
            double u=Math.max(1e-12,nextUniform()),v=nextUniform();
            return Math.sqrt(-2*Math.log(u))*Math.cos(2*Math.PI*v);
        }
    }

    // ==============================
    // DATA CLASSES
    // ==============================
    record SSCandidate(int bobStartYear,int bobStartMonth,int joStartYear,int joStartMonth){
        String key(){ return bobStartYear+"-"+bobStartMonth+"_"+joStartYear+"-"+joStartMonth; }
    }

    static class CandidateResult {
        final SSCandidate cand;
        final int yr1Withdrawal,medianFinalBalance;
        final double actualPoS;
        final boolean confirmed;
        CandidateResult(SSCandidate c,int yr1,int med,double pos,boolean conf){
            cand=c; yr1Withdrawal=yr1; medianFinalBalance=med; actualPoS=pos; confirmed=conf;
        }
    }

    static class SimInputs {
        int baseYear,portfolio,horizon; double targetPoS;
        int withdrawStartYear,withdrawStartMonth;
        int manBirthYear,manBirthMonth,manAge;
        int womanBirthYear,womanBirthMonth,womanAge;
        int manPIA,womanPIA;
        double manSSMonthly,womanSSMonthly;
        int manSSAmount,manSSStartYear,manSSStartMonth;
        int womanSSAmount,womanSSStartYear,womanSSStartMonth;
        double ssCola;
        int annuity,annuityStartYear,annuityStartMonth;
        int manTradIRA,manRothIRA,manTrad401K,manRoth401K;
        int womanRoth401K,womanRothIRA,womanTradIRA,womanTrad401K;
        int manPlanAge,womanPlanAge;
        double nomReturn,stdDev,inflation,inflationStdDev;
        int livingExp,medical; double medInflation;
        int baseTax; double taxInflation;
        double goGoMultiplier; int goGoDuration;
        int scenarioIndex;

        SimInputs withSS(int bobYear,int bobMonth,int joYear,int joMonth,int scenIdx){
            SimInputs c=new SimInputs();
            c.baseYear=baseYear; c.portfolio=portfolio; c.horizon=horizon; c.targetPoS=targetPoS;
            c.withdrawStartYear=withdrawStartYear; c.withdrawStartMonth=withdrawStartMonth;
            c.manBirthYear=manBirthYear; c.manBirthMonth=manBirthMonth; c.manAge=manAge;
            c.womanBirthYear=womanBirthYear; c.womanBirthMonth=womanBirthMonth; c.womanAge=womanAge;
            c.manPIA=manPIA; c.womanPIA=womanPIA;
            c.manSSStartYear=bobYear; c.manSSStartMonth=bobMonth;
            c.womanSSStartYear=joYear; c.womanSSStartMonth=joMonth;
            c.manSSMonthly=ILEngine.calcSSMonthlyBenefit(manPIA,manBirthYear,manBirthMonth,bobYear,bobMonth);
            c.womanSSMonthly=ILEngine.calcSSMonthlyBenefit(womanPIA,womanBirthYear,womanBirthMonth,joYear,joMonth);
            c.manSSAmount=(int)Math.round(c.manSSMonthly*12);
            c.womanSSAmount=(int)Math.round(c.womanSSMonthly*12);
            c.ssCola=ssCola;
            c.annuity=annuity; c.annuityStartYear=annuityStartYear; c.annuityStartMonth=annuityStartMonth;
            c.manTradIRA=manTradIRA; c.manRothIRA=manRothIRA; c.manTrad401K=manTrad401K; c.manRoth401K=manRoth401K;
            c.womanRoth401K=womanRoth401K; c.womanRothIRA=womanRothIRA; c.womanTradIRA=womanTradIRA; c.womanTrad401K=womanTrad401K;
            c.manPlanAge=manPlanAge; c.womanPlanAge=womanPlanAge;
            c.nomReturn=nomReturn; c.stdDev=stdDev; c.inflation=inflation; c.inflationStdDev=inflationStdDev;
            c.livingExp=livingExp; c.medical=medical; c.medInflation=medInflation;
            c.baseTax=baseTax; c.taxInflation=taxInflation;
            c.goGoMultiplier=goGoMultiplier; c.goGoDuration=goGoDuration;
            c.scenarioIndex=scenIdx;
            return c;
        }
    }

    // ==============================
    // UI HELPERS
    // ==============================
    private JPanel card(String title, Object[] items){
        JPanel card=new JPanel(); card.setLayout(new BoxLayout(card,BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0,0,6,0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(208,206,200),1),
                        BorderFactory.createEmptyBorder(8,10,8,10))));
        JLabel tl=new JLabel(title.toUpperCase());
        tl.setFont(new Font("SansSerif",Font.BOLD,12)); tl.setForeground(new Color(110,105,95));
        tl.setBorder(BorderFactory.createEmptyBorder(0,0,6,0)); tl.setAlignmentX(LEFT_ALIGNMENT);
        card.add(tl);
        for (int i=0;i<items.length;i+=2){
            Object lo=items[i]; Object comp=items[i+1];
            if (lo!=null){
                JLabel lbl=new JLabel((String)lo);
                lbl.setFont(new Font("SansSerif",Font.PLAIN,13)); lbl.setForeground(new Color(75,75,75));
                lbl.setBorder(BorderFactory.createEmptyBorder(4,0,1,0)); lbl.setAlignmentX(LEFT_ALIGNMENT);
                card.add(lbl);
            }
            JComponent row=(comp instanceof JSpinner sp)?wrapSpinner(sp):(JComponent)comp;
            row.setAlignmentX(LEFT_ALIGNMENT); row.setMaximumSize(new Dimension(Integer.MAX_VALUE,32));
            card.add(row);
        }
        return card;
    }
    private JPanel wrapSpinner(JSpinner sp){
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE,28)); sp.setPreferredSize(new Dimension(200,28));
        JPanel p=new JPanel(new BorderLayout()); p.setOpaque(false); p.add(sp,BorderLayout.CENTER); return p;
    }
    private JSpinner spinI(int v,int mn,int mx,int step,String fmt){
        JSpinner s=new JSpinner(new SpinnerNumberModel(v,mn,mx,step));
        s.setEditor(new JSpinner.NumberEditor(s,fmt)); s.setFont(new Font("SansSerif",Font.PLAIN,13)); return s;
    }
    private JSpinner spinD(double v,double mn,double mx,double step,String fmt){
        JSpinner s=new JSpinner(new SpinnerNumberModel(v,mn,mx,step));
        s.setEditor(new JSpinner.NumberEditor(s,fmt)); s.setFont(new Font("SansSerif",Font.PLAIN,13)); return s;
    }
    private int    iv(JSpinner s){ return ((Number)s.getValue()).intValue(); }
    private double dv(JSpinner s){ return ((Number)s.getValue()).doubleValue(); }

    private static String ageStr(int totalMonths){
        int y=totalMonths/12,m=totalMonths%12;
        return m==0?y+"y":y+"y"+m+"m";
    }

    private void refreshAgeAndHorizon(){
        try{
            int ma=computeAge(iv(spManBirthYear),iv(spManBirthMonth));
            int wa=computeAge(iv(spWomanBirthYear),iv(spWomanBirthMonth));
            lblManAge.setText("Man age: "+ma); lblWomanAge.setText("Woman age: "+wa);
            int mp=iv(spManPlanAge),wp=iv(spWomanPlanAge);
            int h=Math.max(10,Math.min(50,Math.max(mp,wp)-ma));
            spHorizon.setValue(h);
            String drv=(mp>=wp)?"man to "+mp:"woman to "+wp;
            lblHorizonNote.setText("<html><i>Horizon = "+h+" yrs ("+drv+", man age "+ma+")</i></html>");
        }catch(Exception ignored){}
    }

    private void updateAccountTotal(){
        try{
            long t=(long)iv(spManTradIRA)+iv(spManRothIRA)+iv(spManTrad401K)+iv(spManRoth401K)
                    +iv(spWomanRoth401K)+iv(spWomanRothIRA)+iv(spWomanTradIRA)+iv(spWomanTrad401K);
            NumberFormat cf=NumberFormat.getCurrencyInstance(Locale.US); cf.setMaximumFractionDigits(0);
            lblAccountTotal.setText("Account total: "+cf.format(t));
        }catch(Exception ignored){}
    }

    private int computeAge(int by,int bm){
        LocalDate today=LocalDate.now();
        LocalDate birth=LocalDate.of(Math.max(1900,Math.min(2100,by)),Math.max(1,Math.min(12,bm)),1);
        return (int)java.time.temporal.ChronoUnit.YEARS.between(birth,today);
    }

    private SimInputs readInputs(){
        SimInputs i=new SimInputs();
        i.baseYear=iv(spSimStartYear); i.withdrawStartYear=iv(spWithdrawStartYear);
        i.withdrawStartMonth=iv(spWithdrawStartMonth); i.targetPoS=iv(spTargetPoS)/100.0;
        i.horizon=iv(spHorizon);
        i.manBirthYear=iv(spManBirthYear); i.manBirthMonth=iv(spManBirthMonth);
        i.womanBirthYear=iv(spWomanBirthYear); i.womanBirthMonth=iv(spWomanBirthMonth);
        i.manPlanAge=iv(spManPlanAge); i.womanPlanAge=iv(spWomanPlanAge);
        i.manPIA=iv(spManPIA); i.womanPIA=iv(spWomanPIA); i.ssCola=dv(spSSCola)/100.0;
        i.manSSStartYear=2027; i.manSSStartMonth=1;
        i.womanSSStartYear=2027; i.womanSSStartMonth=12;
        i.manSSMonthly=ILEngine.calcSSMonthlyBenefit(i.manPIA,i.manBirthYear,i.manBirthMonth,i.manSSStartYear,i.manSSStartMonth);
        i.womanSSMonthly=ILEngine.calcSSMonthlyBenefit(i.womanPIA,i.womanBirthYear,i.womanBirthMonth,i.womanSSStartYear,i.womanSSStartMonth);
        i.manSSAmount=(int)Math.round(i.manSSMonthly*12); i.womanSSAmount=(int)Math.round(i.womanSSMonthly*12);
        i.annuity=iv(spAnnuity); i.annuityStartYear=iv(spAnnuityStartYear); i.annuityStartMonth=iv(spAnnuityStartMonth);
        i.manTradIRA=iv(spManTradIRA); i.manRothIRA=iv(spManRothIRA);
        i.manTrad401K=iv(spManTrad401K); i.manRoth401K=iv(spManRoth401K);
        i.womanRoth401K=iv(spWomanRoth401K); i.womanRothIRA=iv(spWomanRothIRA);
        i.womanTradIRA=iv(spWomanTradIRA); i.womanTrad401K=iv(spWomanTrad401K);
        i.portfolio=i.manTradIRA+i.manRothIRA+i.manTrad401K+i.manRoth401K
                +i.womanRoth401K+i.womanRothIRA+i.womanTradIRA+i.womanTrad401K;
        i.nomReturn=dv(spNomReturn)/100.0; i.stdDev=dv(spStdDev)/100.0;
        i.inflation=dv(spInflation)/100.0; i.inflationStdDev=dv(spInflationStdDev)/100.0;
        i.livingExp=iv(spLivingExp); i.medical=iv(spMedical); i.medInflation=dv(spMedInflation)/100.0;
        i.baseTax=iv(spBaseTax); i.taxInflation=dv(spTaxInflation)/100.0;
        i.goGoMultiplier=dv(spGoGo); i.goGoDuration=iv(spGoGoDuration);
        i.manAge=computeAge(i.manBirthYear,i.manBirthMonth);
        i.womanAge=computeAge(i.womanBirthYear,i.womanBirthMonth);
        i.scenarioIndex=cmbScenario!=null?cmbScenario.getSelectedIndex():0;
        return i;
    }

    static class ScrollablePanel extends JPanel implements javax.swing.Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize(){ return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r,int o,int d){ return 20; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r,int o,int d){ return 60; }
        @Override public boolean getScrollableTracksViewportWidth(){ return true; }
        @Override public boolean getScrollableTracksViewportHeight(){ return false; }
    }

    // ==============================
    // MAIN
    // ==============================
    public static void main(String[] args){
        SwingUtilities.invokeLater(()->{
            try{ UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch(Exception ignored){}
            new IncomeLab_Plus_SS_Optimizer();
        });
    }
}
