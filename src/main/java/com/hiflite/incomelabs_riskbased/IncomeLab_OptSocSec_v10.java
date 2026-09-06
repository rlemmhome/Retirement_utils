// ==============================================================
// IncomeLab_OptSocSec_v10.java
// Last modified: Saturday, September 05, 2026 at 07:51 PM MST (UTC-7)
// ==============================================================
package com.hiflite.incomelabs_riskbased;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
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
 * IncomeLab_OptSocSec_v10.java
 *
 * Income Lab Pro + Guyton-Klinger Withdrawal Simulator
 * (Enhanced stochastic engine with Guyton-Klinger Option C tab)
 *
 * == METHODOLOGY ===============================================================
 *
 *  1. TRUE STOCHASTIC MEDIAN PATH
 *     Runs all fan paths first, each drawing stochastic returns/inflation and
 *     applying the solved first-year withdrawal indexed to inflation. The
 *     displayed table then reads the 50th-percentile balance across all fan
 *     paths at each year -- not the mean-return path used in simplified tools.
 *
 *  2. TWO-LEVEL WITHDRAWAL ENGINE
 *     INNER (per Monte Carlo trial in survivalRatePro):
 *       Given a starting balance and a candidate first-year withdrawal W0,
 *       simulate `horizon` years of stochastic returns and inflation with
 *       spending = W0 * goGoMultiplier(y) * cumInflFactor(y), where
 *       cumInflFactor chains the per-year inflation draws multiplicatively
 *       (year 0 = 1.0, year y = product of (1 + infl_k) for k = 1..y).
 *       This is a Bengen-style real-dollar fixed schedule -- no inner
 *       re-optimization.
 *     MIDDLE (solveWithdrawalPro):
 *       Binary search over W0 such that >= targetPoS of inner trials
 *       survive the full horizon for the given balance.
 *     OUTER (fan paths in Step 1, displayed median path in Step 4):
 *       Each simulated year, call solveWithdrawalPro again on the current
 *       (or 50th-percentile) balance for the remaining horizon. This
 *       produces year-by-year withdrawals that adapt to observed
 *       portfolio drift -- the engine IS annually re-solving at this
 *       level, even though each individual call's inner trials are not.
 *
 *     The Guyton-Klinger guardrails tab (Option C) provides a separate
 *     dynamic-spending overlay with explicit upper/lower guardrails.
 *
 *  3. COUPLE-AWARE SS / RMD
 *     Full SSA FRA schedule, early/delayed adjustments, SECURE 2.0 RMDs (age 75),
 *     seven-account decomposition (trad/Roth for both spouses).
 *
 *  4. TAX ENGINE (v3, state selector added v5)
 *     Computed federal + STATE + IRMAA on the Pro PoS median path. The Tax
 *     column is the LIVING-EXPENSES tax: taxable Social Security (provisional-
 *     income formula) + ordinary income (max(RMD, Traditional draw) + annuity)
 *     minus the MFJ + age-65 standard deduction, through inflation-indexed 2026
 *     brackets + the selected STATE tax + IRMAA (costed, 2-year MAGI lookback).
 *
 *     STATE TAX (v5): the state is selectable. Two profiles ship: Arizona
 *     (flat 2.5%, Social Security excluded from the state base -- this
 *     reproduces the pre-v5 hardcoded behavior EXACTLY) and Custom (a user-
 *     entered flat rate with two flags: "tax Social Security" and "exclude
 *     retirement income", the latter with an optional dollar cap). Every state
 *     is a StateTaxProfile holding a year->StateTaxYear map, so rules are
 *     modifiable per state AND per year with full year-history: forYear() uses
 *     the most recent entry at or before the simulation year (floorEntry), and
 *     adding a new tax year is a one-line data append with no code change.
 *     Bracketed (progressive) state tax is scaffolded (bracketedTax) but no
 *     shipped profile uses it yet -- AZ and Custom are flat. See TaxEngine's
 *     StateTaxProfile/StateTaxYear classes and the Assumptions & Methods tab.
 *     Roth conversions are modeled as a SEPARATE
 *     Traditional distribution: gross from the Traditional IRAs, taxed at the
 *     stacked marginal rate (Conv Tax column), the tax paid from the conversion
 *     (leaves the asset base), the net split into the two Roth IRAs. The gross
 *     conversion raises MAGI (IRMAA-relevant, fill-to-target sizing) but is NOT
 *     in the living-expenses Tax column. Toggle the engine off to revert to the
 *     legacy flat escalator. The OBBBA senior bonus deduction is deliberately
 *     NOT modeled (conservative). See the TaxEngine class and the Assumptions &
 *     Methods tab.
 *
 *  Remaining gaps vs. full Income Lab spec (deliberate design choices):
 *    - No asset allocation glide path (single return/stdDev for full horizon)
 *    - No mortality weighting (fixed planning age, conservative)
 *    - Guyton-Klinger tab retains the legacy flat tax escalator
 *
 * =============================================================================
 * Compile:  javac IncomeLab_OptSocSec_v10.java
 * Run:      java com.hiflite.incomelabs_riskbased.IncomeLab_OptSocSec_v10
 * Requires Java 11+. No external dependencies.
 */
public class IncomeLab_OptSocSec_v10 extends JFrame {

    // v7: window-title identity. Per Bob's standing request (Aug 12, 2026) the
    // title reads "Income withdrawal and Probability of Success -- " followed by
    // the version and the build datestamp, replacing the old feature-list suffix.
    // Keep BUILD_STAMP in sync with the header "Last modified" line on each edit.
    private static final String APP_VERSION = "v10";
    private static final String BUILD_STAMP = "Saturday, September 05, 2026 at 07:51 PM MST (UTC-7)";
    private static String windowTitle() {
        return "Income withdrawal and Probability of Success -- "
                + APP_VERSION + " (" + BUILD_STAMP + ")";
    }

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
    private static final int COL_TAX       = 13;   // v3
    private static final int COL_IRMAA     = 23;   // v3
    private static final int COL_MAGI      = 24;   // v7 (inserted left of Roth Conv)
    private static final int COL_ROTH_CONV = 25;   // v3 (was 24; +1 for MAGI)
    private static final int COL_CONV_TAX  = 26;   // v3 (was 25; +1 for MAGI)

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
    private JCheckBox chkUseAnnuity; // v4: activate/deactivate annuity (default off)
    private JSpinner spNomReturn, spStdDev, spInflation, spInflationStdDev;
    private JSpinner spLivingExp, spMedical, spMedInflation;
    private JSpinner spBaseTax, spTaxInflation;
    private JSpinner spGoGo, spGoGoDuration;
    private JSpinner spSlowGo, spSlowGoDuration;   // v6: slow-go tier
    private JSpinner spProPosUpperGuardrail, spProPosLowerGuardrail;
    // v3 tax engine: computed-tax toggle, Roth conversion controls
    private JCheckBox  chkComputedTax;      // true = TaxEngine, false = legacy flat escalator
    // v5 state tax: state selector + custom-state controls
    private JComboBox<String> cmbState;     // state profile selector (Arizona / Custom)
    private JSpinner   spCustomStateRate;   // custom flat state rate (%), custom only
    private JCheckBox  chkCustomTaxSS;      // custom: state taxes Social Security
    private JCheckBox  chkCustomExclRetire; // custom: state excludes retirement income
    private JSpinner   spCustomExclCap;     // custom: retirement-exclusion cap ($, 0=unlimited)
    private JToggleButton tglConvMode;      // selected = fill-to-target, unselected = flat $
    private JSpinner   spConvFlat;          // flat annual conversion $ (flat mode)
    private JSpinner   spConvBuffer;        // MAGI buffer below IRMAA cliff (fill mode)
    private JSpinner   spConvCap;           // max conversion cap (fill mode; 0 = uncapped)
    private JComboBox<String> cmbIrmaaMode; // IRMAA threshold indexing mode
    private JComboBox<String> cmbFilingStatus; // v4: MFJ / Single tax basis
    // v7: fill-to-target ceiling selectors + the editable-threshold dialog button.
    private JComboBox<String> cmbFillIrmaaTier; // IRMAA tier to stay under (fill mode)
    private JComboBox<String> cmbFillBracket;   // tax-bracket top to stay under (fill mode)
    private JButton    btnEditThresholds;       // opens the Edit thresholds... dialog
    // v7: user-editable base (2026) thresholds. These start as copies of the
    // TaxEngine 2026 constants and are what fillConversion / the tax engine read
    // once the user has opened the dialog (see applyEditableThresholds). Held on
    // the frame so save/load can persist them. The inflation projection is
    // unchanged -- these are the BASE-YEAR values the projection scales forward.
    private double[] editIrmaaThreshMFJ    = TaxEngine.IRMAA_THRESH_MFJ_2026.clone();
    private double[] editIrmaaThreshSingle = TaxEngine.IRMAA_THRESH_SINGLE_2026.clone();
    private double[] editBracketTopMFJ     = bracketTops(TaxEngine.BRACKETS_2026);
    private double[] editBracketTopSingle  = bracketTops(TaxEngine.BRACKETS_SINGLE_2026);

    // Pull the upper edges out of a bracket table (drops the +inf top row's use
    // as an editable value -- the last finite top is the 35->37 boundary).
    private static double[] bracketTops(double[][] brk) {
        double[] tops = new double[brk.length];
        for (int i = 0; i < brk.length; i++) tops[i] = brk[i][1];
        return tops;
    }
    private JComboBox<String> cmbDeathWho;  // v6: Neither / User dies / Spouse dies
    private JSpinner   spDeathYear;         // v6: calendar year of death
    private JSpinner   spHisRmdShare;       // v6: User's % of combined Traditional (RMD split)
    private JSpinner   spSurvivorSpendCut;  // v6: survivor living-expense reduction %
    private JCheckBox  chkSSColaTracksInfl;  // v6: SS rides simulated inflation
    private JSpinner   spColaShortfall;      // v6: annual COLA haircut
    private JSpinner   spSeqOffset;          // v6: shift the sequence N years in
    private JSpinner   spSSHaircutPct;       // v6: scheduled benefit cut %
    private JSpinner   spSSHaircutYear;      // v6: year the cut begins
    // v9: SS bridge is now a THREE-WAY mode, replacing the v6 on/off checkbox.
    //   SIM_START   -- bridge BOTH spouses from the withdrawal-start date to
    //                  each one's own claim date, at the benefit each WOULD have
    //                  received claiming at withdrawal-start. Fills the early
    //                  pre-claim years so Surplus/gap never goes negative purely
    //                  because SS has not switched on yet. (New default.)
    //   FIRST_CLAIM -- v6 behavior: bridge ONLY the later claimant, ONLY across
    //                  the gap between the first and second claim dates.
    //   NONE        -- no bridge; the portfolio simply carries the full load and
    //                  spending falls until each benefit actually begins.
    private static final int BRIDGE_SIM_START   = 0;
    private static final int BRIDGE_FIRST_CLAIM = 1;
    private static final int BRIDGE_NONE        = 2;
    private static final String[] BRIDGE_MODE_LABELS = {
            "From simulation start (both)",   // index 0 -> SIM_START
            "From first claim (v6)",          // index 1 -> FIRST_CLAIM
            "No bridge",                      // index 2 -> NONE
    };
    private JComboBox<String> cmbBridgeMode;  // v9: replaces chkBridgeSS
    private JComboBox<String> cmbOptSort;    // v6: what the SS Optimizer ranks by
    private JLabel     lblOptObjective;      // v6: banner naming the active objective
    private JLabel     lblColaWarn;          // v6: guard note on historical sequences
    private JSpinner spGkUpperGuardrail, spGkLowerGuardrail;
    private JSpinner spManTradIRA, spManRothIRA, spManTrad401K, spManRoth401K;
    private JSpinner spWomanRoth401K, spWomanRothIRA, spWomanTradIRA, spWomanTrad401K;
    private JSpinner spMmPrior;
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
    // Stress Test tab (v3)
    private JButton    btnRunStress;
    private JLabel     lblStressStatus, lblStressVerdict;
    private javax.swing.table.DefaultTableModel tblStressModel;
    private JTable     tblStress;
    private JTable     tblOpt;
    private javax.swing.table.DefaultTableModel tblOptModel;
    private volatile boolean optCancelRequested = false;
    private JButton    btnCancelOpt;

    // == Output widgets =======================================================
    private JTabbedPane       mainTabs;  // direct ref for tab switching
    private JLabel            lblAnswer, lblSub, lblDetail;
    private JLabel            lblBaseline;      // v6: trigger balance + baseline comparison
    private JButton           btnSetBaseline;   // v6: capture this run as the annual baseline
    private JSpinner          spMinChangePct;   // v6: minimum material change %
    // v6: annual baseline captured deliberately (NOT on every save), so the
    // year-over-year comparison is against the figure Bob actually drew
    // against, not against a mid-session experiment.
    private boolean baselineSet = false;
    private int     baselineActualWd = 0, baselineBalance = 0, baselineHorizon = 0;
    private double  baselineGoGoMult = 1.0;
    private String  baselineDate = "";
    // last run values, used when the baseline button is pressed
    private int     lastRunActualWd = 0, lastRunBalance = 0, lastRunHorizon = 0;
    private double  lastRunGoGoMult = 1.0;
    private boolean lastRunValid = false;
    private JLabel            lblActualPoS, lblMedianFinal, lblYr10Wd, lblInitRate;
    private JTable            tblPro;
    private DefaultTableModel tblProModel;
    // v9: Pro PoS column picker state. Model-column indices the user has hidden,
    // and indices marked non-hideable (protected). Model index 6 (Rate drift) is
    // permanently hidden by the engine and is never offered in the picker. The
    // picker, apply helper, and per-scenario save/load all key on MODEL indices,
    // matching the tooltip switches (which use convertColumnIndexToModel), so the
    // numbering never shifts when columns are shown or hidden.
    private final java.util.Set<Integer> proHiddenCols    = new java.util.TreeSet<>();
    private final java.util.Set<Integer> proProtectedCols =
            new java.util.TreeSet<>(java.util.Arrays.asList(0, 1)); // User Age, Cal yr
    private static final int PRO_RATE_DRIFT_COL = 6;   // engine-hidden, never in picker
    private String[] proColNames;                      // model-index -> header text
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
    private int lastOptInfeasMode = 0;   // v9: remembered for toggle refresh
    // v9: Option-2 optimizer inputs. Travel-headroom floors and stay-green buffer
    // (SS Optimizer tab ONLY -- they feed the feasibility test, never the Pro
    // engine's spending). Grid/fidelity controls for the two-stage scan, and the
    // infeasible-set fallback selector.
    private JSpinner spOptGoGoFloor, spOptSlowGoFloor, spOptGreenBuffer;
    private JSpinner spOptScanPaths, spOptScanFan, spOptVerifyTopN;
    private JSpinner spOptTermGrace;             // v9: terminal-year grace window
    private JComboBox<String> cmbOptGrid;        // year / half-year granularity
    private JComboBox<String> cmbOptInfeasible;  // fallback when nothing is feasible
    private int lastOptManBY, lastOptManBM, lastOptWomanBY, lastOptWomanBM;
    private int lastOptManPIA, lastOptWomanPIA;
    private boolean optResultsStale = false;  // true if inputs changed after optimizer ran
    private final java.util.concurrent.atomic.AtomicLong simCount = new java.util.concurrent.atomic.AtomicLong(0);
    private          long simTotal    = 0;
    private volatile java.util.function.LongConsumer simProgressCallback = null;

    // Calibration: stores past run times to estimate duration of next run
    private static final int    CALIB_MAX              = 20;
    private static final int    CALIB_MIN_FOR_ESTIMATE = 1;

    // Directory structure under user home:
    //   ~/.retirement_utils/.incomelab/scenarios/    <- scenario files
    //   ~/.retirement_utils/.incomelab/calibration/  <- prefs files
    private static final java.io.File DIR_ROOT      = new java.io.File(
            System.getProperty("user.home"), ".retirement_utils");
    private static final java.io.File DIR_INCOMELAB = new java.io.File(DIR_ROOT,     ".incomelab");
    private static final java.io.File DIR_SCENARIOS = new java.io.File(DIR_INCOMELAB,"scenarios");
    private static final java.io.File DIR_CALIB     = new java.io.File(DIR_INCOMELAB,"calibration");
    private static final String CALIB_FILE   = new java.io.File(DIR_CALIB, "calibration.ilscen.prefs").getAbsolutePath();
    private static final String RECENT_FILE  = new java.io.File(DIR_CALIB, "recent.ilscen.prefs").getAbsolutePath();
    // v7: collapsible input-section state is an APP preference (per-user layout),
    // independent of any scenario. Stored separately so loading a scenario never
    // re-expands the pane. Key = section slug derived from the card title;
    // value = "true" (expanded, the default) / "false" (collapsed).
    private static final String PANELSTATE_FILE = new java.io.File(DIR_CALIB, "panelstate.prefs").getAbsolutePath();
    private final java.util.Map<String,Boolean> sectionExpanded = new java.util.HashMap<>();

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

    public IncomeLab_OptSocSec_v10() {
        super(windowTitle());
        // Ensure app directories exist under ~/.retirement_utils/.incomelab/
        DIR_SCENARIOS.mkdirs();
        DIR_CALIB.mkdirs();
        loadPanelState();   // v7: must precede any card() so sections open in saved state
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(new Color(245, 245, 242));

        ToolTipManager ttm = ToolTipManager.sharedInstance();
        ttm.setInitialDelay(750);
        ttm.setDismissDelay(15_000);
        ttm.setReshowDelay(500);

        // v6: the input panel and the results area now live in a horizontal
        // JSplitPane so the divider can be dragged to widen/narrow the inputs
        // (previously the inputs were pinned at a fixed 420px via BorderLayout
        // .WEST). setContinuousLayout keeps the tables repainting live while
        // dragging; setOneTouchExpandable adds the little arrows to collapse or
        // restore the inputs in one click. Resize weight 0 means extra window
        // width goes to the results side, not the inputs.
        JPanel inputPanel  = buildInputPanel();
        JPanel outputPanel = buildOutputPanel();
        inputPanel.setMinimumSize(new Dimension(260, 0));   // allow narrowing
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                inputPanel, outputPanel);
        mainSplit.setDividerLocation(420);   // matches the previous fixed width
        mainSplit.setDividerSize(8);
        mainSplit.setContinuousLayout(true);
        mainSplit.setOneTouchExpandable(true);
        mainSplit.setResizeWeight(0.0);
        mainSplit.setBorder(null);

        add(mainSplit,          BorderLayout.CENTER);
        add(buildStatusBar(),   BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(1300, 760));
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLocationRelativeTo(null);
        setVisible(true);
        SwingUtilities.invokeLater(this::updateSSBenefitNote);

        // v9 Option C: show the branded splash centered on THIS frame, once the
        // frame's bounds are final. Maximization is applied by the OS
        // asynchronously, so we must not read getBounds() immediately -- the
        // pre-maximized rectangle would mis-center the splash. We place the splash
        // from a WindowStateListener that fires when the frame reaches
        // MAXIMIZED_BOTH, with a guarded deferred EDT fallback in case the maximize
        // already happened (or the WM never fires the event). A one-shot flag makes
        // the two paths safe against showing the splash twice.
        final boolean[] splashShown = { false };
        final Runnable trigger = () -> {
            if (splashShown[0]) return;
            splashShown[0] = true;
            showSplashScreen(this);
        };
        addWindowStateListener(new java.awt.event.WindowAdapter() {
            @Override public void windowStateChanged(java.awt.event.WindowEvent e) {
                if ((e.getNewState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH) {
                    trigger.run();
                }
            }
        });
        // Fallback: if the frame is already maximized by the time we get here (the
        // state event can fire before the listener is attached), or the WM does not
        // emit the event, place the splash on a later EDT cycle once bounds settle.
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(trigger));
    }

    // ========================================================================
    //  INPUT PANEL  (includes GK initial rate and guardrail spinners)
    // ========================================================================
    private JPanel buildInputPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(new Color(240, 240, 237));
        outer.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(200, 198, 193)));
        // v6: width is now governed by the JSplitPane divider (see the frame
        // layout); this preferred size only sets the INITIAL width the split
        // pane opens at. Height 0 lets it fill vertically.
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
        btnSave.setOpaque(true);
        btnSave.setBorderPainted(true);
        btnSave.setBorder(BorderFactory.createLineBorder(new Color(40, 70, 120), 1));
        btnLoad.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnLoad.setBackground(new Color(80, 130, 60));
        btnLoad.setForeground(Color.WHITE);
        btnLoad.setFocusPainted(false);
        btnLoad.setOpaque(true);
        btnLoad.setBorderPainted(true);
        btnLoad.setBorder(BorderFactory.createLineBorder(new Color(55, 95, 40), 1));

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

        // v6: capture the annual baseline DELIBERATELY. Ordinary saves leave it
        // alone, so a session of experiments cannot silently overwrite the figure
        // you actually drew against last year.
        btnSetBaseline = new JButton("Set as annual baseline");
        btnSetBaseline.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btnSetBaseline.setFocusPainted(false);
        btnSetBaseline.setEnabled(false);
        btnSetBaseline.setToolTipText("<html><b>Set as annual baseline (v6)</b><br>"
                + "Records THIS run's Actual wd, portfolio balance, horizon and go-go<br>"
                + "state as the reference for future year-over-year comparisons.<br><br>"
                + "Press it once a year, when you commit to the withdrawal figure you<br>"
                + "will actually draw against. Ordinary <i>Save Scenario</i> does NOT change<br>"
                + "the baseline, so experimenting freely will not corrupt it.<br><br>"
                + "The baseline is stored in the .ilscen file and survives reload.</html>");
        btnSetBaseline.addActionListener(e -> {
            if (!lastRunValid) return;
            baselineActualWd = lastRunActualWd;
            baselineBalance  = lastRunBalance;
            baselineHorizon  = lastRunHorizon;
            baselineGoGoMult = lastRunGoGoMult;
            baselineDate     = java.time.LocalDate.now().toString();
            baselineSet      = true;
            refreshBaselineLine();
            JOptionPane.showMessageDialog(this,
                    "Annual baseline set to " + CURRENCY.format(baselineActualWd)
                            + " (portfolio " + CURRENCY.format(baselineBalance) + ")"
                            + "\nDated " + baselineDate
                            + "\n\nRemember to Save Scenario to keep it.",
                    "Baseline captured", JOptionPane.INFORMATION_MESSAGE);
        });

        spMinChangePct = spinD(5.0, 0.0, 25.0, 1.0, "0.0");
        spMinChangePct.setToolTipText("<html><b>Minimum material change %% (v6)</b><br>"
                + "Year-over-year withdrawal changes smaller than this are reported as<br>"
                + "<i>no material change</i> rather than prompting action, so ordinary<br>"
                + "market noise between annual runs does not read as a signal.<br><br>"
                + "<b>Default 5%%</b>, matching the threshold risk-based guardrail<br>"
                + "methodologies commonly use.</html>");

        JPanel scenBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scenBtnRow.setOpaque(false); scenBtnRow.setAlignmentX(LEFT_ALIGNMENT);
        scenBtnRow.add(btnSave); scenBtnRow.add(btnLoad);
        JPanel baseRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        baseRow.setOpaque(false); baseRow.setAlignmentX(LEFT_ALIGNMENT);
        baseRow.add(btnSetBaseline);
        baseRow.add(new JLabel("Min chg %"));
        baseRow.add(spMinChangePct);

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
        scenSaveCard.add(baseRow);
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
                + "200 = ~4x faster, ~$500 variance. 100 = ~8x faster, ~$1,000 variance.<br>"
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
                + "100 = ~4x faster but noisier. 50 = rough but usable for quick checks.<br>"
                + "Each fan path re-solves the withdrawal annually against its current balance -- most expensive per path.</html>");

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
                + "Has no effect on the Income PoS tab.<br><br>"
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
        lblManAge        = new JLabel("User age: --");
        lblManAge.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblWomanAge      = new JLabel("Spouse age: --");
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
                "User birth year",         spManBirthYear,
                "User birth month",        spManBirthMonth,
                "Spouse birth year",       spWomanBirthYear,
                "Spouse birth month",      spWomanBirthMonth,
                "User's life expectancy",  spManPlanAge,
                "Spouse's life expectancy", spWomanPlanAge,
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
        spManTradIRA     = spinI(300_000,  0, 10_000_000, 1_000, "#,###");
        spManRothIRA     = spinI(  5_000,  0, 10_000_000, 1_000, "#,###");
        spManTrad401K    = spinI(      0,  0, 10_000_000, 1_000, "#,###");
        spManRoth401K    = spinI(      0,  0, 10_000_000, 1_000, "#,###");
        spWomanRoth401K  = spinI( 15_000,  0, 10_000_000, 1_000, "#,###");
        spWomanRothIRA   = spinI(      0,  0, 10_000_000, 1_000, "#,###");
        spWomanTradIRA   = spinI( 80_000,  0, 10_000_000, 1_000, "#,###");
        spWomanTrad401K  = spinI(100_000,  0, 10_000_000, 1_000, "#,###");
        spMmPrior        = spinI(      0,  0, 20_000_000, 1_000, "#,###");
        spMmPrior.setToolTipText("<html><b>Money Mkt (prior, $) -- already-taxed carry-in reserve</b><br>"
                + "Accumulated RMD overage from a PREVIOUS run of this simulation --<br>"
                + "already-taxed cash you park in a real money-market account.<br><br>"
                + "It <b>seeds row 1</b> of the <b>Money Mkt (total)</b> column, which then grows<br>"
                + "each year at inflation (MM return ~= inflation, ~0% real) and adds<br>"
                + "new RMD overage. The simulation <b>never spends it</b> (informational<br>"
                + "buffer, like the rest of Money Mkt (total)).<br><br>"
                + "<b>Excluded from Portfolio balance</b> (Option A): the Portfolio column is<br>"
                + "the money the sim actually draws on (qualified + spendable taxable).<br>"
                + "Your ACTUAL total = Portfolio balance + Money Mkt (total). Because this<br>"
                + "reserve is walled off from spending, it is NOT added to the Account<br>"
                + "total above and does NOT redistribute into the qualified accounts.</html>");
        lblAccountTotal  = new JLabel("Account total: --");
        lblAccountTotal.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblAccountTotal.setForeground(new Color(40, 80, 40));

        ChangeListener acctListener = e -> updateAccountTotal();
        for (JSpinner sp : new JSpinner[]{ spManTradIRA, spManRothIRA, spManTrad401K, spManRoth401K,
                spWomanRoth401K, spWomanRothIRA, spWomanTradIRA, spWomanTrad401K })
            sp.addChangeListener(acctListener);

        inner.add(card("Account Balances (SECURE 2.0 RMD -- Age 75)", new Object[]{
                "User -- Traditional IRA ($)  [RMD age 75]",    spManTradIRA,
                "User -- Roth IRA ($)  [no RMD]",               spManRothIRA,
                "User -- Traditional 401K ($)  [RMD age 75]",   spManTrad401K,
                "User -- Roth 401K ($)  [no RMD]",              spManRoth401K,
                "Spouse -- Roth 401K ($)  [no RMD]",            spWomanRoth401K,
                "Spouse -- Roth IRA ($)  [no RMD]",             spWomanRothIRA,
                "Spouse -- Traditional IRA ($)  [RMD age 75]",  spWomanTradIRA,
                "Spouse -- Traditional 401K ($)  [RMD age 75]", spWomanTrad401K,
                "Money Mkt (prior, $)  [carry-in, not spent, not in Portfolio]", spMmPrior,
                null,                                          lblAccountTotal,
        }));
        inner.add(Box.createVerticalStrut(4));
        SwingUtilities.invokeLater(this::updateAccountTotal);

        // == Social Security ===============================================
        spManPIA           = spinI(3_788, 0, 6_000, 50, "#,###");
        spManPIA.setToolTipText("<html><b>User's Primary Insurance Amount (PIA)</b><br>"
                + "Monthly SS benefit payable at Full Retirement Age (FRA).<br>"
                + "Found on your SSA statement at ssa.gov/myaccount.<br><br>"
                + "Reduced if claiming before FRA; increased if after FRA.<br>"
                + "FRA = 67 for those born 1960 or later.</html>");
        spManSSStartYear   = spinI(2027,  2020, 2040, 1, "#");
        spManSSStartMonth  = spinI(1,     1,    12,   1, "#");
        spWomanPIA         = spinI(3_897, 0, 6_000, 50, "#,###");
        spWomanPIA.setToolTipText("<html><b>Spouse's Primary Insurance Amount (PIA)</b><br>"
                + "Monthly SS benefit payable at Full Retirement Age (FRA).<br>"
                + "Found on your SSA statement at ssa.gov/myaccount.<br><br>"
                + "Reduced if claiming before FRA; increased if after FRA.<br>"
                + "FRA = 67 for those born 1960 or later.</html>");
        spWomanSSStartYear = spinI(2027,  2020, 2040, 1, "#");
        spWomanSSStartMonth= spinI(12,    1,    12,   1, "#");
        cmbBridgeMode = new JComboBox<>(BRIDGE_MODE_LABELS);
        cmbBridgeMode.setSelectedIndex(BRIDGE_SIM_START);   // v9: new default
        cmbBridgeMode.setToolTipText("<html><b>SS bridge mode (v9)</b> -- how the portfolio replaces<br>"
                + "Social Security income that has not started yet.<br><br>"
                + "<b>From simulation start (both):</b> from the withdrawal-start date, the<br>"
                + "portfolio bridges BOTH spouses -- each until their own claim date --<br>"
                + "at the benefit each WOULD have received claiming at withdrawal-start.<br>"
                + "This fills the early pre-claim years, so Surplus/gap does not go<br>"
                + "negative merely because neither benefit has switched on yet. Each<br>"
                + "spouse's bridge runs to THEIR OWN claim date, not the earlier one.<br>"
                + "This is the new default.<br><br>"
                + "<b>From first claim (v6):</b> the earlier behavior. Only the LATER<br>"
                + "claimant is bridged, and only across the gap between the first and<br>"
                + "second claim dates. The early claimant's pre-claim years are left<br>"
                + "unbridged (Surplus/gap can go negative there).<br><br>"
                + "<b>No bridge:</b> the portfolio carries the full load and spending<br>"
                + "simply falls until each benefit actually begins.<br><br>"
                + "<b>Reference benefit</b> is the smaller EARLY-claim amount, not the<br>"
                + "eventual delayed one -- the bridge replaces the early-claim<br>"
                + "equivalent, then real SS (possibly larger) takes over at the date.<br><br>"
                + "<b>Grossed up for tax.</b> Social Security is at most 85%% taxable; a<br>"
                + "Traditional withdrawal is 100%% ordinary income. Each spouse's<br>"
                + "bridge covers its own extra tax so Surplus/gap lands where a<br>"
                + "no-delay run would, and that extra tax becomes real drawdown.<br><br>"
                + "The draw is applied inside the PoS solver, so probability of<br>"
                + "success reflects it -- a heavier early bridge lowers the solved<br>"
                + "base withdrawal rather than the success probability.</html>");

        spSSHaircutPct = spinD(0.0, 0.0, 50.0, 1.0, "0.0#");
        spSSHaircutPct.setToolTipText("<html><b>SS benefit haircut %% (v6) -- a STRESS input</b><br>"
                + "Applies an across-the-board reduction to BOTH benefits from the<br>"
                + "start year onward. <b>0 = disabled</b> (default), which reproduces<br>"
                + "every earlier scenario exactly.<br><br>"
                + "<b>Why it exists:</b> under CURRENT LAW, when the OASI trust fund<br>"
                + "depletes, benefits are automatically reduced to what incoming<br>"
                + "payroll tax can cover -- no legislation required. CBO's 2026<br>"
                + "projection puts depletion at <b>2032</b>, with payroll tax covering<br>"
                + "about 72%% of scheduled benefits -- roughly a <b>28%% cut</b>.<br><br>"
                + "<b>This is not a forecast.</b> Congress has never allowed a scheduled<br>"
                + "cut to take effect, and any fix would likely protect people already<br>"
                + "claiming. Use it to see how much of your plan leans on Social<br>"
                + "Security, not to predict policy.<br><br>"
                + "It bites hardest on a DELAYED claiming strategy, because more of<br>"
                + "your income is Social Security.</html>");

        spSSHaircutYear = spinI(0, 0, 2100, 1, "0");
        spSSHaircutYear.setToolTipText("<html><b>Haircut start year (v6)</b><br>"
                + "First calendar year the reduction applies. <b>0 = never</b> (default).<br>"
                + "Set 2032 to model the CBO-projected depletion date.<br><br>"
                + "The cut is permanent from that year on -- it does not taper or<br>"
                + "recover, which is what current law actually specifies.</html>");

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
                "User PIA (monthly at FRA, $)",     spManPIA,
                "User SS start year",               spManSSStartYear,
                "User SS start month",              spManSSStartMonth,
                "Spouse PIA (monthly at FRA, $)",   spWomanPIA,
                "Spouse SS start year",             spWomanSSStartYear,
                "Spouse SS start month",            spWomanSSStartMonth,
                "SS COLA (%/yr)",                  spSSCola,
                "SS bridge mode (v9)",             cmbBridgeMode,
                "SS haircut % (v6, stress)",       spSSHaircutPct,
                "Haircut start year (0=never)",    spSSHaircutYear,
                null,                              lblSSBenefitNote,
        }));
        inner.add(Box.createVerticalStrut(4));

        // == Annuity =======================================================
        spAnnuity           = spinI(0, 0, 500_000, 500, "#,###");
        spAnnuityStartYear  = spinI(2028, 2020, 2040, 1, "#");
        spAnnuityStartMonth = spinI(4,    1,    12,   1, "#");
        chkUseAnnuity       = new JCheckBox("Use annuity", false); // default: deactivated
        chkUseAnnuity.setToolTipText("<html><b>Use annuity</b><br>"
                + "When OFF (default), the annuity is deactivated: its income is treated as $0 in<br>"
                + "the simulation and the SS optimizer, and the three annuity fields below are<br>"
                + "grayed and locked. Their values are preserved, so turning this back ON restores<br>"
                + "the annuity with no re-entry.<br><br>"
                + "The on/off state is saved with the scenario. Scenarios saved before this control<br>"
                + "existed have no stored state and load deactivated.</html>");
        chkUseAnnuity.addActionListener(e -> refreshAnnuityFieldsEnabled());
        inner.add(card("Annuity (non-COLA)", new Object[]{
                "Use annuity",                chkUseAnnuity,
                "Annual annuity income ($)",  spAnnuity,
                "Annuity start year",         spAnnuityStartYear,
                "Annuity start month",        spAnnuityStartMonth,
        }));
        inner.add(Box.createVerticalStrut(4));

        // == Market Assumptions ============================================
        spNomReturn       = spinD(5.5,  0.0, 20.0, 0.01, "0.00#");
        spNomReturn.setToolTipText("<html><b>Expected NOMINAL return (%/yr)</b><br>"
                + "A forward-looking <b>nominal</b> return -- BEFORE removing inflation. The<br>"
                + "engine applies inflation separately (to spending), so feed a nominal number<br>"
                + "here, not a real one.<br><br>"
                + "The 5.5% default reflects 2026 forward-looking capital market assumptions<br>"
                + "from J.P. Morgan (October 2025) and Vanguard (December 2025 VCMM run). Both<br>"
                + "houses forecast muted near-term returns driven by elevated large-cap<br>"
                + "valuations: Vanguard projects ~3.9-5.9% annualized U.S. equities over 10<br>"
                + "years; J.P. Morgan projects ~6.4% for a 60/40 over 10-15 years. Historical<br>"
                + "nominal S&amp;P CAGR of ~10.5% is well above forward consensus.<br><br>"
                + "See Assumptions &amp; Methods section 2 for the full CMA basis and the<br>"
                + "corresponding EDJ-managed net series.<br><br>"
                + "<b>Nominal vs real:</b> real (inflation-adjusted) return &asymp; this figure minus<br>"
                + "your inflation assumption. At 5.5% nominal - 2.3% inflation that is ~3.2%<br>"
                + "real. Do NOT enter a real number here or inflation gets removed twice.<br>"
                + "Update at least annually as new forward estimates publish.</html>");
        spStdDev          = spinD(10.79, 0.0, 40.0, 0.01, "0.00#");
        spStdDev.setToolTipText("<html><b>Return standard deviation (%/yr)</b><br>"
                + "Annual volatility of returns, from the 1961-2024 historical S&amp;P 500 series.<br>"
                + "Volatility -- not average return -- is the dominant driver of sequence-of-<br>"
                + "returns risk and thus the sustainable withdrawal. Default 10.79%.</html>");
        spInflation       = spinD(2.3,   0.0, 15.0, 0.01, "0.00#");
        spInflation.setToolTipText("<html><b>General spending Mean inflation (%/yr)</b><br>"
                + "Applied to living expenses and Roth-conversion tax growth.<br>"
                + "<b>Does not include medical</b> &mdash; medical typically outpaces general<br>"
                + "inflation (Medigap age-rating, Part D drug creep, dental/vision/hearing<br>"
                + "usage rising with age), so it has its own dedicated <b>Medical Cost Increase</b><br>"
                + "field in the Annual Spending section.<br><br>"
                + "The 2.3% default is anchored to the Social Security 2026 Trustees Report<br>"
                + "(intermediate assumptions, ultimate long-range CPI-W of 2.4%/yr) and the<br>"
                + "CBO's projection that the Federal Reserve reaches its 2% target over the<br>"
                + "2027-2035 window. Housing and health are modeled as separate, faster-<br>"
                + "growing series and are excluded from this &quot;core&quot; figure.<br><br>"
                + "See Assumptions &amp; Methods section 2 for the full basis. The 1961-2024<br>"
                + "historical average is 3.79% and is available as a stress-case setting.<br>"
                + "Update at least annually.</html>");
        spInflationStdDev = spinD(2.73,  0.0, 10.0, 0.01, "0.00#");
        spInflationStdDev.setToolTipText("<html><b>Inflation standard deviation (%/yr)</b><br>"
                + "Year-to-year volatility of inflation, from the 1961-2024 historical CPI series.<br>"
                + "Paired with the mean inflation above for the stochastic inflation draws.<br>"
                + "Default 2.73%.</html>");
        inner.add(card("Market Assumptions (1961-2024 Historical)", new Object[]{
                "Expected nominal return (%)",  spNomReturn,
                "Return std deviation (%)",     spStdDev,
                "General spending Mean inflation (%/yr)",        spInflation,
                "Inflation std deviation (%)",  spInflationStdDev,
        }));
        inner.add(Box.createVerticalStrut(4));

        // == Spending ======================================================
        spLivingExp    = spinI( 70_000, 0, 500_000, 1_000, "#,###");
        spMedical      = spinI(  6_200, 0, 100_000,   500, "#,###");
        spMedical.setToolTipText("<html><b>Medical premiums &amp; out-of-pocket ($/yr)</b><br>"
                + "All health-care costs: <b>medical premiums and out-of-pocket</b> (deductible<br>"
                + "and copay, dentist / vision / hearing).<br><br>"
                + "<b>Include your base Medicare premiums here</b> -- Part B (~$203/mo/person in<br>"
                + "2026) plus a Part D drug plan (~$40-55/mo/person) plus any Medigap/supplement.<br>"
                + "For a couple both on Medicare that base runs roughly $6,000/yr before supplement<br>"
                + "and out-of-pocket. Medicare premiums are a MEDICAL cost, not a tax -- only the<br>"
                + "income-related IRMAA <i>surcharge</i> (above $218k MAGI) appears near the Tax<br>"
                + "column; your base premiums belong here.<br><br>"
                + "Pre-Medicare (bridge years) this line is your ACA/marketplace premium instead;<br>"
                + "the figure changes once you and your spouse are both on Medicare (~2027-2028).<br>"
                + "The tool inflates this line at the medical-inflation rate below.</html>");
        spMedInflation = spinD(4.5,     0.0, 15.0,   0.1,  "0.0#");
        spMedInflation.setToolTipText("<html><b>Medical Cost Increase (%/yr)</b><br>"
                + "The annual growth rate applied to the Medical spending line -- effectively<br>"
                + "the <b>medical spending growth rate</b>, not pure CPI inflation. It combines two<br>"
                + "forces:<br><br>"
                + "&nbsp;&nbsp;<b>1. General price inflation for medical goods and services</b> --<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;medical CPI historically outpaces core CPI by 1-2%/yr.<br>"
                + "&nbsp;&nbsp;<b>2. Age-related cost creep</b> -- Medigap Plan G in Arizona is age-<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;rated (premiums rise ~5-7%/yr just from getting older),<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Part D drug costs creep as more medications are added, and<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;dental/vision/hearing usage rises with age.<br><br>"
                + "The 4.5% default is a reasonable midpoint for a household on Medicare with<br>"
                + "Medigap. If your Medigap plan issuer publishes a specific age-rating<br>"
                + "schedule, or your prescription list is unusually stable/growing, adjust<br>"
                + "accordingly. This is separate from the General spending inflation above.</html>");
        spBaseTax      = spinI( 17_500, 0, 200_000, 1_000, "#,###");
        spTaxInflation = spinD(3.79,    0.0, 10.0,  0.01,  "0.00#");
        spGoGo         = spinD(1.000,   0.0,  5.0,  0.001, "0.000#");
        spGoGo.setToolTipText("<html><b>This is a front-loading lever, not just a travel toggle.</b><br>"
                + "Raising it bends the spending curve forward in time -- more in the<br>"
                + "years you can best use it, less later. A flat or back-loaded curve is<br>"
                + "a failure mode for most retirees, since late-year dollars carry lower<br>"
                + "utility. Your <b>Surplus/gap</b> column is where the front-loaded money<br>"
                + "actually shows up.<br><br>"
                + "<b>Common multiplier ranges:</b><br><br>"
                + "&nbsp;&nbsp;<b>1.2x&nbsp;(20% more)</b> -- Conservative; suitable if you already have<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                + "an active lifestyle baked into your baseline<br><br>"
                + "&nbsp;&nbsp;<b>1.3x&nbsp;(30% more)</b> -- The most commonly cited \"middle ground\"<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                + "in retirement planning literature<br><br>"
                + "&nbsp;&nbsp;<b>1.5x&nbsp;(50% more)</b> -- Used for people expecting significant travel,<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                + "bucket-list spending, or major lifestyle upgrades<br><br>"
                + "<b>Below 1.0 (v8):</b> the floor is now 0.0, so the multiplier can also model<br>"
                + "deliberate <i>under</i>-withdrawal. 0.5 draws half the solved amount; <b>0.0<br>"
                + "withdraws nothing</b> for the window (a deferral / bridge-gap period). In<br>"
                + "those years living expenses still exist, so <b>Surplus/gap goes negative</b> --<br>"
                + "that shortfall is shown, not funded, which is correct. Leaving more in<br>"
                + "Traditional also raises later RMDs and can push RMD past the draw --<br>"
                + "the way to exercise the RMD-overage / Money Mkt path.</html>");
        spSlowGo = spinD(1.000, 0.0, 5.0, 0.005, "0.000#");
        spSlowGo.setToolTipText("<html><b>Slow-go multiplier (v6)</b><br>"
                + "The MIDDLE tier of the spending curve. Applies for its own number<br>"
                + "of years immediately AFTER the go-go window ends, then spending<br>"
                + "drops to 1.0 (no-go) for the remainder.<br><br>"
                + "<b>Default 1.000 / 0 years = disabled</b>, which reproduces every<br>"
                + "pre-v6 scenario exactly.<br><br>"
                + "<b>Why you want this:</b> with only a go-go tier the curve is a cliff --<br>"
                + "elevated spending, then an abrupt drop to flat. Meanwhile the<br>"
                + "PoS re-solve keeps raising your sustainable withdrawal as the<br>"
                + "horizon shortens, so late-life Actual wd climbs steeply while<br>"
                + "Total spend stays flat. The difference piles up as unspent<br>"
                + "Surplus -- which is legacy by accident, not by choice.<br>"
                + "A slow-go tier of 1.05-1.15 converts that surplus into actual<br>"
                + "living: travel in your seventies, home help, comfort.<br><br>"
                + "<b>Below 1.0 (v8):</b> floor is now 0.0, so this tier can also model a<br>"
                + "reduced- or zero-withdrawal middle period (draws less than the<br>"
                + "solved amount). Same caveats as go-go: Surplus/gap goes negative<br>"
                + "in a shortfall year, and un-drawn Traditional feeds larger RMDs.</html>");

        spSlowGoDuration = spinI(0, 0, 30, 1, "#");
        spSlowGoDuration.setToolTipText("<html><b>Slow-go years (v6)</b><br>"
                + "How many years the slow-go multiplier runs, starting the year<br>"
                + "AFTER the go-go window ends. 0 disables the tier.<br><br>"
                + "Example: go-go 1.25 for 10 years then slow-go 1.10 for 10 years<br>"
                + "gives elevated spending 2027-2036, moderately elevated<br>"
                + "2037-2046, and baseline thereafter.</html>");

        spGoGoDuration = spinI(10,      0,    20,    1,     "#");
        spGoGoDuration.setToolTipText("<html><b>Go-go years duration</b><br>"
                + "Number of years from withdrawal start that the go-go<br>"
                + "spending multiplier applies.<br><br>"
                + "<b>Default: 10 years</b> -- roughly covers ages 65-75 for a typical<br>"
                + "early retiree. Set to 0 to disable the go-go multiplier entirely.</html>");
        inner.add(card("Annual Spending (2027 Base $)", new Object[]{
                "Living expenses ($/yr)",             spLivingExp,
                "Medical -- premiums & out-of-pocket ($/yr)", spMedical,
                "Medical Cost Increase (%/yr)",       spMedInflation,
                "Go-go years multiplier",             spGoGo,
                "Go-go years duration (from wd start)", spGoGoDuration,
                "Slow-go multiplier (v6)",             spSlowGo,
                "Slow-go years (after go-go)",         spSlowGoDuration,
        }));
        // v6: frame the multiplier as a spending-curve lever rather than a
        // travel line item -- the distinction drives how it should be set.
        JLabel lblGoGoNote = new JLabel(
                "<html><i>Front-loads the spending curve: go-go, then slow-go, then no-go.<br>Leaving slow-go at 1.000/0 makes the drop a cliff.</i></html>");
        lblGoGoNote.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lblGoGoNote.setForeground(new Color(110, 110, 110));
        lblGoGoNote.setAlignmentX(LEFT_ALIGNMENT);
        lblGoGoNote.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 0));
        inner.add(lblGoGoNote);
        inner.add(Box.createVerticalStrut(4));

        // == Tax engine + Roth conversion (v3) =============================
        chkComputedTax = new JCheckBox("Use computed tax engine", true);
        chkComputedTax.setBackground(Color.WHITE);
        chkComputedTax.setToolTipText("<html><b>Computed tax engine (v3)</b><br>"
                + "When ON, the Tax column is COMPUTED each year from taxable income:<br>"
                + "&nbsp;&nbsp;taxable Social Security (provisional-income formula)<br>"
                + "&nbsp;&nbsp;+ ordinary income (max(RMD, Traditional draw) + annuity + conversion)<br>"
                + "&nbsp;&nbsp;- standard deduction (MFJ + age-65 add-ons), all inflation-indexed<br>"
                + "then run through the MFJ brackets + the selected state tax + IRMAA surcharge.<br><br>"
                + "When OFF, reverts to the legacy flat 'Base tax' escalator<br>"
                + "(baseTax * (1+taxInflation)^year). See the Assumptions &amp; Methods tab.<br><br>"
                + "<b>Deliberately NOT modeled &mdash; OBBBA 'senior bonus' deduction:</b><br>"
                + "The up-to-$6,000/person 65+ bonus (tax years 2025-2028 only) is omitted<br>"
                + "on purpose, for two reasons: (1) leaving it out slightly OVERSTATES tax,<br>"
                + "which is the conservative and preferred direction; and (2) it avoids<br>"
                + "encoding fragile temporary-statute logic -- a hard 2028 sunset plus an MFJ<br>"
                + "MAGI phase-out from $150,000 (gone by $250,000). That phase-out matters<br>"
                + "here because it keys off MAGI, the SAME quantity the fill-to-target Roth<br>"
                + "conversion sizing drives -- so modeling the bonus would couple it to the<br>"
                + "conversion engine rather than being a clean add-on. See section 4 of the<br>"
                + "Assumptions &amp; Methods tab. Verify with a tax professional before relying<br>"
                + "on this deduction.</html>");

        // == v5 State tax selector + custom-state controls ================
        java.util.List<String> stateNames = new java.util.ArrayList<>();
        for (TaxEngine.StateTaxProfile p : TaxEngine.STATE_REGISTRY.values())
            stateNames.add(p.displayName);
        cmbState = new JComboBox<>(stateNames.toArray(new String[0]));
        cmbState.setSelectedIndex(0); // default: Arizona (prior behavior)
        cmbState.setToolTipText("<html><b>State income tax (v5)</b><br>"
                + "Selects the state tax rules folded into the Tax column<br>"
                + "(federal + <b>state</b> + IRMAA).<br><br>"
                + "<b>Arizona:</b> flat 2.5%, Social Security excluded from the<br>"
                + "state base. Reproduces the app's prior fixed behavior.<br>"
                + "<b>Custom (flat rate):</b> enter your own flat rate and two<br>"
                + "flags below to model any other state's basic treatment.<br><br>"
                + "State rules are stored per year with history, so a future<br>"
                + "rate change is a data update, not a code change.</html>");
        cmbState.addActionListener(e -> refreshStateFieldsEnabled());

        spCustomStateRate = spinD(0.0, 0.0, 15.0, 0.1, "0.0");
        spCustomStateRate.setToolTipText("<html><b>Custom state flat rate (%)</b><br>"
                + "Applied to the state taxable base. Used only when the state<br>"
                + "selector is set to <b>Custom (flat rate)</b>.</html>");
        spCustomStateRate.addChangeListener(e -> syncCustomStateProfile());

        chkCustomTaxSS = new JCheckBox("Tax Social Security", false);
        chkCustomTaxSS.setBackground(Color.WHITE);
        chkCustomTaxSS.setToolTipText("<html><b>Custom: state taxes Social Security</b><br>"
                + "OFF (default) = taxable Social Security is subtracted from the<br>"
                + "state base (most states). ON = the state taxes SS like other<br>"
                + "income. Custom mode only.</html>");
        chkCustomTaxSS.addActionListener(e -> syncCustomStateProfile());

        chkCustomExclRetire = new JCheckBox("Exclude retirement income", false);
        chkCustomExclRetire.setBackground(Color.WHITE);
        chkCustomExclRetire.setToolTipText("<html><b>Custom: exclude retirement income</b><br>"
                + "ON = the retirement ordinary draw (RMD / Traditional withdrawal)<br>"
                + "is subtracted from the state base, up to the cap below (0 =<br>"
                + "unlimited). Models states that exempt IRA/401(k)/pension income.<br>"
                + "Custom mode only.</html>");
        chkCustomExclRetire.addActionListener(e -> { refreshStateFieldsEnabled(); syncCustomStateProfile(); });

        spCustomExclCap = spinI(0, 0, 1_000_000, 1_000, "#,###");
        spCustomExclCap.setToolTipText("<html><b>Custom: retirement exclusion cap ($/yr)</b><br>"
                + "Maximum retirement income excluded from the state base per year<br>"
                + "(base-year dollars, inflation-indexed). <b>0 = unlimited.</b> Live only<br>"
                + "when 'Exclude retirement income' is checked in Custom mode.</html>");
        spCustomExclCap.addChangeListener(e -> syncCustomStateProfile());

        tglConvMode = new JToggleButton("Fill to MAGI target", true);
        tglConvMode.setToolTipText("<html><b>Roth conversion sizing mode</b><br>"
                + "<b>Selected (Fill to MAGI target):</b> each year converts the largest amount<br>"
                + "that keeps MAGI under the binding ceiling -- the lower of the IRMAA<br>"
                + "Tier-0 cliff (minus your buffer) and the 22%-&gt;24% bracket edge --<br>"
                + "then capped by the cap below. The table reports which ceiling bound it.<br>"
                + "<b>Unselected (Flat $):</b> converts the fixed amount below every year.</html>");
        tglConvMode.addActionListener(e -> {
            tglConvMode.setText(tglConvMode.isSelected() ? "Fill to MAGI target" : "Flat $ amount");
            refreshTaxEngineEnabled();
        });
        chkComputedTax.addActionListener(e -> refreshTaxEngineEnabled());

        spConvFlat   = spinI(40_000, 0, 1_000_000, 1_000, "#,###");
        spConvFlat.setToolTipText("<html><b>Flat annual Roth conversion ($)</b><br>"
                + "Used when conversion mode is set to <b>Flat $ amount</b>.<br>"
                + "A fixed conversion applied every drawing year, added to ordinary<br>"
                + "income (raising MAGI and tax). Default $40,000.</html>");
        spConvBuffer = spinI(13_000, 0, 100_000, 1_000, "#,###");
        spConvBuffer.setToolTipText("<html><b>MAGI buffer below the IRMAA cliff ($)</b><br>"
                + "Used in <b>Fill to MAGI target</b> mode. The fill stops this many dollars<br>"
                + "below the IRMAA Tier-0 threshold (2026 base $218,000, inflation-indexed),<br>"
                + "leaving headroom so an income surprise does not trip the surcharge.<br>"
                + "Default $13,000 (targets ~$205,000 MAGI in 2026 dollars).</html>");
        spConvCap    = spinI(40_000, 0, 1_000_000, 1_000, "#,###");
        spConvCap.setToolTipText("<html><b>Roth conversion cap ($/yr)</b><br>"
                + "Used in <b>Fill to MAGI target</b> mode. The computed fill is never<br>"
                + "larger than this. <b><font color=red>0 means NO LIMIT -- not \"no conversions\".</font></b><br><br>"
                + "With 0, the fill runs all the way to the IRMAA ceiling every year.<br>"
                + "Because each conversion pays tax OUT of the asset base, an uncapped<br>"
                + "fill can drain Traditional and materially LOWER the sustainable<br>"
                + "withdrawal (and shrink go-go spending, which is a multiple of it).<br>"
                + "Default $40,000.</html>");

        cmbIrmaaMode = new JComboBox<>(new String[]{
                "Frozen (nominal 2026)", "Chained-CPI (current law)", "Full CPI" });
        cmbIrmaaMode.setSelectedIndex(1); // default: chained-CPI
        cmbIrmaaMode.setToolTipText("<html><b>IRMAA threshold indexing mode</b><br>"
                + "How the IRMAA income cliffs move over the horizon. The surcharge<br>"
                + "AMOUNT always tracks general inflation; this controls only the<br>"
                + "THRESHOLDS.<br><br>"
                + "<b>Chained-CPI (default, current law):</b> thresholds grow at inflation<br>"
                + "minus ~0.3%/yr, matching how the first four IRMAA tiers have been<br>"
                + "indexed since 2020.<br>"
                + "<b>Frozen (nominal 2026):</b> thresholds never move -- the conservative<br>"
                + "stress case, matching the 2007-2019 freeze and Medicare funding-<br>"
                + "pressure risk. Surfaces late-life RMD-driven IRMAA most clearly.<br>"
                + "<b>Full CPI:</b> thresholds grow at your full inflation assumption -- the<br>"
                + "most optimistic (thresholds outrun income, IRMAA rarely triggers).<br><br>"
                + "See the Assumptions &amp; Methods tab.</html>");

        // v7: IRMAA-tier ceiling selector. Index maps directly to the IRMAA
        // upper-boundary array index used in fillConversion. Index 0 = the first
        // cliff (fill stays in Standard, no surcharge) -- the pre-v7 default.
        cmbFillIrmaaTier = new JComboBox<>(new String[]{
                "Standard (stay under Tier 1)",
                "Tier 1 (stay under Tier 2)",
                "Tier 2 (stay under Tier 3)",
                "Tier 3 (stay under Tier 4)",
                "Tier 4 (stay under Tier 5)" });
        cmbFillIrmaaTier.setSelectedIndex(0);
        cmbFillIrmaaTier.setToolTipText("<html><b>Fill-to-target: IRMAA tier ceiling</b><br>"
                + "Used in <b>Fill to MAGI target</b> mode. The conversion is sized so MAGI<br>"
                + "stays UNDER the selected IRMAA cliff (minus the buffer below).<br><br>"
                + "<b>Standard (default):</b> stay under the first cliff (2026 base $218,000<br>"
                + "MFJ) -- zero Medicare surcharge. <b>Tier 1..4:</b> deliberately accept that<br>"
                + "surcharge tier to convert more, e.g. to drain Traditional faster and<br>"
                + "cut future RMDs / widow-year tax. The engine still takes the LOWER of<br>"
                + "this ceiling and the tax-bracket ceiling below.</html>");

        // v7: tax-bracket ceiling selector. Values map to indices into
        // brackets(fs); label shows the 2026 MFJ taxable-income top for context.
        // Bob's chosen set: top of 12 / 22 / 24 / 32.
        cmbFillBracket = new JComboBox<>(new String[]{
                "Top of 12% bracket",
                "Top of 22% bracket",
                "Top of 24% bracket",
                "Top of 32% bracket" });
        cmbFillBracket.setSelectedIndex(1); // default: top of 22% (pre-v7 behavior)
        cmbFillBracket.setToolTipText("<html><b>Fill-to-target: tax-bracket ceiling</b><br>"
                + "Used in <b>Fill to MAGI target</b> mode. The conversion is also held so<br>"
                + "taxable income does not exceed the TOP of the selected ordinary bracket.<br>"
                + "Unlike IRMAA this is NOT a cliff -- crossing it taxes only the marginal<br>"
                + "dollars -- so no buffer is applied to it; the fill may land exactly on it.<br><br>"
                + "The engine converts up to the LOWER of this and the IRMAA ceiling. With<br>"
                + "Bob's default (IRMAA Standard + top of 22%), the $218k IRMAA cliff binds<br>"
                + "first at ~$205k MAGI, filling all of 12% and most of 22% at zero surcharge.</html>");

        // v7: Edit thresholds... dialog button.
        btnEditThresholds = new JButton("Edit thresholds\u2026");
        btnEditThresholds.setToolTipText("<html><b>Edit base (2026) thresholds</b><br>"
                + "Opens a small editor for the BASE-YEAR IRMAA cliffs and ordinary-bracket<br>"
                + "tops (MFJ and Single). These are the 2026 figures the inflation projection<br>"
                + "scales forward via the IRMAA threshold-growth mode -- edit them if the IRS<br>"
                + "or CMS publishes new numbers, or to stress-test a different schedule.<br>"
                + "Values persist with the scenario. Reset-to-2026 is available in the dialog.</html>");
        btnEditThresholds.addActionListener(e -> showThresholdDialog());

        cmbFilingStatus = new JComboBox<>(new String[]{
                "Married filing jointly", "Single" });
        cmbFilingStatus.setSelectedIndex(0); // default: MFJ (preserves prior behavior)
        cmbFilingStatus.addActionListener(e -> refreshTaxEngineEnabled());
        cmbFilingStatus.setToolTipText("<html><b>Filing status (federal + SS taxation + IRMAA basis)</b><br>"
                + "Switches the entire tax basis between MFJ and Single. Affects the<br>"
                + "standard deduction, the ordinary brackets, the age-65 add-on, the<br>"
                + "Social Security provisional-income thresholds, and the IRMAA<br>"
                + "thresholds and per-person surcharge.<br><br>"
                + "<b>Married filing jointly (default):</b> the two-person basis; both<br>"
                + "spouses' ages drive the age-65 add-ons.<br>"
                + "<b>Single:</b> the survivor-year / single-friend basis. The age-65<br>"
                + "add-on follows the USER/primary person's age; the spouse age is<br>"
                + "ignored (a single filer has one taxpayer).<br><br>"
                + "This flag changes ONLY the tax math. To model a survivor year,<br>"
                + "also consolidate the decedent's account balances into the<br>"
                + "surviving person's fields, keep the survivor's (larger) Social<br>"
                + "Security benefit, and zero the decedent's inputs in the saved<br>"
                + "scenario. See the Assumptions &amp; Methods tab.<br><br>"
                + "<b>v6:</b> for a mid-projection survivor transition, prefer the<br>"
                + "<b>Death event</b> control below -- it flips to Single automatically<br>"
                + "the year AFTER the death year and carries balances forward.</html>");

        // v6: death-event controls. The dropdown selects who (if anyone) dies
        // mid-projection; the year field is the calendar year of death. The
        // survivor basis (Single tax, dropped SS, survivor-age RMD) begins the
        // following year. Both are saved/loaded with the scenario.
        cmbDeathWho = new JComboBox<>(new String[]{
                "Neither (both survive)", "User dies", "Spouse dies" });
        cmbDeathWho.setSelectedIndex(0);
        cmbDeathWho.setToolTipText("<html><b>Death event (v6) -- mid-projection survivor transition</b><br>"
                + "Models one spouse dying in a chosen year. Beginning the year AFTER<br>"
                + "the death year, the run automatically switches to the <b>Single</b> tax<br>"
                + "basis (deduction, brackets, SS provisional thresholds, IRMAA tiers),<br>"
                + "stops the decedent's Social Security (the survivor keeps the LARGER<br>"
                + "of the two benefits), and computes RMDs on the survivor's age alone.<br>"
                + "Because the Traditional and Roth balances are already pooled, nothing<br>"
                + "is dropped -- the whole portfolio carries to the survivor.<br><br>"
                + "<b>Neither:</b> both survive the horizon (no transition).</html>");

        spDeathYear = spinI(2045, 2025, 2100, 1, "0");
        spDeathYear.setToolTipText("<html><b>Year of death (v6)</b><br>"
                + "Calendar year the selected spouse dies. The survivor tax basis and<br>"
                + "SS/RMD changes take effect the FOLLOWING January. Ignored when the<br>"
                + "death-event dropdown is set to Neither.</html>");

        spHisRmdShare = spinD(50.0, 0.0, 100.0, 5.0, "0.0#");
        spHisRmdShare.setToolTipText("<html><b>User's share of combined Traditional (v6)</b><br>"
                + "The combined Traditional bucket is split by this %% (User) and its<br>"
                + "complement (Spouse) so RMDs can be computed on each person's age.<br>"
                + "Set to your actual User/Spouse Traditional split. <b>Default 50%%</b><br>"
                + "for a near-equal couple; the value is saved with the scenario.<br>"
                + "At the death year the survivor's share becomes 100%%.</html>");
        spSurvivorSpendCut = spinD(20.0, 0.0, 40.0, 5.0, "0.0");
        spSurvivorSpendCut.setToolTipText("<html><b>Survivor spending reduction %% (v6)</b><br>"
                + "How much <b>living expenses</b> fall once one spouse has died. Applied<br>"
                + "beginning the year AFTER the death year, alongside the Single tax<br>"
                + "basis and the survivor Social Security benefit.<br><br>"
                + "<b>Default 20%%</b> -- the standard survivor estimate. A survivor does<br>"
                + "NOT need half a couple's budget: housing, utilities, insurance and<br>"
                + "property costs barely change. Range 0-40%%.<br><br>"
                + "<b>Medical is handled separately and automatically:</b> it is halved,<br>"
                + "because it is mechanically per-person (Part B, Medigap Plan G,<br>"
                + "Part D and the self-insurance reserve all stop for the decedent).<br>"
                + "This spinner does not affect medical.</html>");
        cmbDeathWho.addActionListener(e -> refreshDeathFieldsEnabled());

        JPanel convBtnRow = new JPanel();
        convBtnRow.setLayout(new BoxLayout(convBtnRow, BoxLayout.Y_AXIS));
        convBtnRow.setBackground(Color.WHITE);
        chkComputedTax.setAlignmentX(LEFT_ALIGNMENT);
        tglConvMode.setAlignmentX(LEFT_ALIGNMENT);
        tglConvMode.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        spBaseTax.setToolTipText("<html><b>Base tax -- year 1 ($/yr) [LEGACY, flat mode only]</b><br>"
                + "Used ONLY when 'Use computed tax engine' is OFF. Drives the legacy flat<br>"
                + "escalator: baseTax * (1+taxInflation)^year. When the computed engine is ON<br>"
                + "this field is greyed out and ignored (the Tax column is computed instead).<br>"
                + "Set from your actual prior-year return when using flat mode.</html>");
        spTaxInflation.setToolTipText("<html><b>Tax inflation (%/yr) [LEGACY, flat mode only]</b><br>"
                + "Used ONLY when 'Use computed tax engine' is OFF. The annual escalation rate<br>"
                + "for the flat Base tax above. Greyed out and ignored when the computed engine<br>"
                + "is ON.</html>");

        inner.add(card("Tax Engine (v3)", new Object[]{
                "Filing status",           cmbFilingStatus,
                "Death event (v6)",        cmbDeathWho,
                "Year of death (v6)",      spDeathYear,
                "User Traditional share % (v6)", spHisRmdShare,
                "Survivor spending reduction % (v6)", spSurvivorSpendCut,
                "Computed tax (vs flat)",  chkComputedTax,
                "State",                   cmbState,
                "Custom state rate (%)",   spCustomStateRate,
                "Custom: tax SS",          chkCustomTaxSS,
                "Custom: exclude retire",  chkCustomExclRetire,
                "Custom: exclude cap ($, 0=none)", spCustomExclCap,
                "Base tax -- yr 1 ($/yr) [flat only]",  spBaseTax,
                "Tax inflation (%/yr) [flat only]",     spTaxInflation,
        }));
        inner.add(Box.createVerticalStrut(4));
        // v9: Roth conversion + IRMAA controls split into their own collapsible
        // section, collapsed by default (rarely edited). All persistence keys and
        // tooltips unchanged -- only the visual grouping moved.
        inner.add(card("Roth Conversion & IRMAA Surcharge", new Object[]{
                "Conversion mode",         tglConvMode,
                "Flat conversion ($/yr)",  spConvFlat,
                "Fill: IRMAA tier ceiling", cmbFillIrmaaTier,
                "Fill: tax-bracket ceiling", cmbFillBracket,
                "Fill buffer below IRMAA ($)", spConvBuffer,
                "Fill cap ($/yr, 0=NO LIMIT)", spConvCap,
                "IRMAA threshold growth",  cmbIrmaaMode,
                "Base thresholds",         btnEditThresholds,
        }, /*defaultExpanded=*/false));
        inner.add(Box.createVerticalStrut(4));
        refreshTaxEngineEnabled();  // set initial enabled/greyed state
        refreshStateFieldsEnabled();  // v5: initial state-field enable/greyed state
        refreshDeathFieldsEnabled();  // v6: initial death-event enable/greyed state
        refreshColaWarn();            // v6: initial COLA guard note
        refreshOptObjective();        // v6: initial optimizer objective banner
        refreshAnnuityFieldsEnabled();  // v4: initial annuity enable/greyed state

        // == Pro PoS advisory guardrails ===================================
        spProPosUpperGuardrail = spinD(20.0, 5.0, 50.0, 1.0, "0.0#");
        spProPosUpperGuardrail.setToolTipText("<html><b>Pro PoS -- Upper advisory guardrail (raise alert)</b><br>"
                + "Used ONLY by the Pro PoS table's Alert column. It is <b>informational</b> --<br>"
                + "it does NOT change any withdrawal amount.<br><br>"
                + "If this year's base withdrawal RATE (draw / balance, go-go removed) rises<br>"
                + "more than this % <b>above</b> the Year-1 base rate, the table flags a<br>"
                + "<b>[^] above</b> flag in the Rate drift column, signalling the portfolio has<br>"
                + "could sustainably spend more.<br><b>Default: 20%</b></html>");
        spProPosLowerGuardrail = spinD(20.0, 5.0, 50.0, 1.0, "0.0#");
        spProPosLowerGuardrail.setToolTipText("<html><b>Pro PoS -- Lower advisory guardrail (cut alert)</b><br>"
                + "Used ONLY by the Pro PoS table's Alert column. It is <b>informational</b> --<br>"
                + "it does NOT change any withdrawal amount.<br><br>"
                + "If this year's base withdrawal RATE (draw / balance, go-go removed) falls<br>"
                + "more than this % <b>below</b> the Year-1 base rate, the table flags a<br>"
                + "<b>[v] below</b> flag in the Rate drift column, suggesting you re-run and<br>"
                + "<b>Default: 20%</b></html>");
        inner.add(card("Pro PoS Guardrails (advisory alerts only)", new Object[]{
                "Upper guardrail (% above yr1, raise alert)", spProPosUpperGuardrail,
                "Lower guardrail (% below yr1, cut alert)",   spProPosLowerGuardrail,
        }));
        inner.add(Box.createVerticalStrut(4));

        // == Historical Stress Scenario card =====================================
        chkSSColaTracksInfl = new JCheckBox("SS COLA tracks simulated inflation");
        chkSSColaTracksInfl.setSelected(false);   // default OFF: preserves saved scenarios
        chkSSColaTracksInfl.setToolTipText("<html><b>SS COLA tracks simulated inflation (v6)</b><br>"
                + "<b>Off (default):</b> Social Security compounds at your fixed SS COLA<br>"
                + "input. Reproduces every pre-v6 scenario exactly and keeps a<br>"
                + "deliberately conservative COLA assumption for random runs.<br><br>"
                + "<b>On:</b> Social Security instead grows with the SIMULATED inflation,<br>"
                + "holding constant real purchasing power -- which is what CPI<br>"
                + "indexing actually does under current law. On stochastic runs each<br>"
                + "fan path uses ITS OWN inflation, so the PoS calculation is affected<br>"
                + "too, not just the displayed table.<br><br>"
                + "<b>Turn this ON for historical stress sequences.</b> With a fixed 2.4%%<br>"
                + "COLA against 1966-82 inflation (~5.4%%/yr), modelled SS loses about<br>"
                + "60%% of its real value over 30 years -- an artifact, not a risk. That<br>"
                + "single distortion can turn a survivable stress run into a failing one.</html>");

        spSeqOffset = spinI(0, 0, 20, 1, "0");
        spSeqOffset.setToolTipText("<html><b>Sequence starts N years in (v6)</b><br>"
                + "Shifts the historical crisis N years into the projection. Years<br>"
                + "before it use ordinary random draws; the sequence then replays<br>"
                + "in full from that point.<br><br>"
                + "<b>0 (default):</b> the crisis lands in year 1 -- maximum remaining<br>"
                + "runway, but no surplus banked yet.<br><br>"
                + "<b>Why shift it:</b> year 1 is not your worst case. A crisis a few<br>"
                + "years in finds the portfolio already partly drawn AND the go-go<br>"
                + "multiplier still running, which is usually the harder test. Push<br>"
                + "it past the go-go window and the plan is far more resilient,<br>"
                + "because the elevated spending has already ended.<br><br>"
                + "Try 0, 5 and 10 on the same sequence -- the spread between them<br>"
                + "is your real sequence-of-returns exposure.<br><br>"
                + "Applies everywhere the sequence does: the Pro PoS fan paths, the<br>"
                + "PoS solver, the GK loop, and the Stress Test tab.</html>");

        spColaShortfall = spinD(0.2, 0.0, 2.0, 0.1, "0.0#");
        spColaShortfall.setToolTipText("<html><b>COLA shortfall (%%/yr) -- v6</b><br>"
                + "A constant annual haircut on the inflation-tracked COLA, so Social<br>"
                + "Security drifts slowly DOWN in real terms instead of holding flat.<br>"
                + "Only applies when <i>SS COLA tracks simulated inflation</i> is on.<br><br>"
                + "<b>Why a shortfall exists:</b> SS is indexed to <b>CPI-W</b>, which weights a<br>"
                + "working-age wage-earner basket. Retirees spend proportionally more<br>"
                + "on healthcare and housing, which inflate faster. The BLS<br>"
                + "experimental <b>CPI-E</b> (elderly) has historically run about<br>"
                + "<b>0.2 pp/yr higher</b> -- hence the default.<br><br>"
                + "Note CPI-W is a HEADLINE index: food and energy ARE included.<br>"
                + "(Core CPI excludes them, but core is not used for COLA.)<br><br>"
                + "Raise toward <b>0.4-0.5%%</b> if you also want it to absorb the Medicare<br>"
                + "Part B drag. Do NOT raise it for the taxation drag -- the frozen<br>"
                + "$32,000/$44,000 provisional thresholds are already modelled<br>"
                + "directly by the tax engine, so that would double-count.<br><br>"
                + "Real SS remaining after 30 yrs: 0.2%% -> 94%%, 0.3%% -> 91%%, 0.5%% -> 86%%.</html>");

        lblColaWarn = new JLabel(" ");
        lblColaWarn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lblColaWarn.setForeground(new Color(150, 60, 0));

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

        // v6: sequence offset sits directly under the sequence selector.
        JPanel offRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        offRow.setOpaque(false);
        offRow.setAlignmentX(LEFT_ALIGNMENT);
        offRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        JLabel offLbl = new JLabel("Sequence starts N years in");
        offLbl.setFont(new Font("SansSerif", Font.PLAIN, 13));
        offLbl.setForeground(new Color(75, 75, 75));
        spSeqOffset.setPreferredSize(new Dimension(70, 26));
        offRow.add(offLbl); offRow.add(spSeqOffset);
        cardScenario.add(offRow);

        // v6: COLA option + guard note live with the sequence selector, because
        // that pairing is exactly where a fixed COLA misleads.
        chkSSColaTracksInfl.setAlignmentX(LEFT_ALIGNMENT);
        chkSSColaTracksInfl.setBorder(BorderFactory.createEmptyBorder(6,0,0,0));
        cardScenario.add(chkSSColaTracksInfl);
        JPanel colaRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        colaRow.setOpaque(false);
        colaRow.setAlignmentX(LEFT_ALIGNMENT);
        colaRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        JLabel colaLbl = new JLabel("COLA shortfall (%/yr)");
        colaLbl.setFont(new Font("SansSerif", Font.PLAIN, 13));
        colaLbl.setForeground(new Color(75, 75, 75));
        spColaShortfall.setPreferredSize(new Dimension(80, 26));
        colaRow.add(colaLbl); colaRow.add(spColaShortfall);
        cardScenario.add(colaRow);
        lblColaWarn.setAlignmentX(LEFT_ALIGNMENT);
        cardScenario.add(lblColaWarn);
        chkSSColaTracksInfl.addActionListener(e -> refreshColaWarn());
        cmbScenario.addActionListener(e -> refreshColaWarn());

        inner.add(cardScenario);
        inner.add(Box.createVerticalStrut(4));

        // == Guyton-Klinger guardrails (GK tab) ============================
        // Conventional GK mapping: the UPPER guardrail is the ceiling that
        // triggers the Capital Preservation Rule (a CUT); the LOWER guardrail is
        // the floor that triggers the Prosperity Rule (a RAISE). Unlike the Pro
        // PoS advisory guardrails above, these actually MODIFY the GK withdrawal.
        spGkUpperGuardrail = spinD(20.0, 5.0, 60.0, 1.0, "0.0#");
        spGkUpperGuardrail.setToolTipText("<html><b>Guyton-Klinger -- Upper guardrail (Capital Preservation Rule, CPR[v])</b><br>"
                + "This <b>actually cuts</b> the GK withdrawal (it is not just an alert).<br><br>"
                + "If the current GK withdrawal RATE rises more than this % <b>above</b> the<br>"
                + "initial rate -- meaning the portfolio has shrunk and the fixed draw is now<br>"
                + "too large a slice of it -- the withdrawal is <b>cut 10%</b> to preserve capital.<br>"
                + "<b>Default: 20%</b></html>");
        spGkLowerGuardrail = spinD(20.0, 5.0, 60.0, 1.0, "0.0#");
        spGkLowerGuardrail.setToolTipText("<html><b>Guyton-Klinger -- Lower guardrail (Prosperity Rule, PR[^])</b><br>"
                + "This <b>actually raises</b> the GK withdrawal (it is not just an alert).<br><br>"
                + "If the current GK withdrawal RATE falls more than this % <b>below</b> the<br>"
                + "initial rate -- meaning the portfolio has grown and the fixed draw is now<br>"
                + "too small a slice of it -- the withdrawal is <b>raised 10%</b>.<br>"
                + "<b>Default: 20%</b></html>");
        inner.add(card("Guyton-Klinger inputs", new Object[]{
                "GK only -- initial wd rate (%)",                  spGkPreRate,
                "Upper guardrail -- Capital Preservation, cut (%)", spGkUpperGuardrail,
                "Lower guardrail -- Prosperity, raise (%)",         spGkLowerGuardrail,
        }));
        inner.add(Box.createVerticalStrut(4));

        // == Run button ====================================================
        btnRun = new JButton("  Run Simulation");
        btnRun.setFont(new Font("SansSerif", Font.BOLD, 16));
        btnRun.setBackground(new Color(24, 95, 165));
        btnRun.setForeground(Color.WHITE);
        btnRun.setFocusPainted(false);
        btnRun.setOpaque(true);
        btnRun.setBorderPainted(true);
        btnRun.setBorder(BorderFactory.createLineBorder(new Color(16, 65, 115), 1));
        btnRun.setAlignmentX(LEFT_ALIGNMENT);
        btnRun.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        btnRun.addActionListener(e -> runSimulation());

        JScrollPane scroll = new JScrollPane(inner,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        outer.add(scroll, BorderLayout.CENTER);

        // v6: Run lives in a PINNED footer instead of at the bottom of the
        // scrolling content. Previously it was the last child of `inner`, so
        // reaching it meant scrolling past every card -- and then scrolling back
        // up to find the field you had just changed. Now it never moves.
        JPanel runBar = new JPanel(new BorderLayout());
        runBar.setBackground(new Color(245, 245, 242));
        runBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(210, 210, 205)),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        btnRun.setMaximumSize(null);   // BorderLayout stretches it; no Box cap needed
        runBar.add(btnRun, BorderLayout.CENTER);
        outer.add(runBar, BorderLayout.SOUTH);

        // v6: run from the keyboard so a spinner edit can be tested without
        // moving the mouse or looking away from the field. Registered on the
        // whole window, so it works whichever control has focus.
        KeyStroke f5     = KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0);
        KeyStroke ctrlR  = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK);
        JRootPane rp = getRootPane();
        if (rp != null) {
            InputMap im = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            im.put(f5, "runSim"); im.put(ctrlR, "runSim");
            rp.getActionMap().put("runSim", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (btnRun.isEnabled()) runSimulation();
                }
            });
        }
        btnRun.setToolTipText("<html><b>Run Simulation</b>  (F5 or Ctrl+R)<br>"
                + "Pinned here so it stays reachable no matter where you are<br>"
                + "scrolled in the inputs above.</html>");
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
        lblAnswer.setToolTipText("<html><b>What this number is</b><br>"
                + "The <b>maximum</b> you can withdraw from the portfolio in Year 1 and still<br>"
                + "hit your Probability-of-Success target -- found by binary search, not a<br>"
                + "single guessed rate. It is what you <b>can</b> pull, not what you <b>need</b>.<br><br>"
                + "<b>Guaranteed income is deliberately excluded.</b> Social Security and the<br>"
                + "annuity are NOT netted out here -- they appear separately in the table's<br>"
                + "Guaranteed / Total Income columns. Read the <b>Surplus / Gap</b> column to<br>"
                + "see headroom above your budget; that surplus is your room for Roth<br>"
                + "conversions up to your bracket and IRMAA limits.</html>");
        lblSub = new JLabel(" ");
        lblSub.setFont(new Font("SansSerif", Font.PLAIN, 15));
        lblSub.setForeground(new Color(80, 80, 80));
        lblDetail = new JLabel(" ");
        lblDetail.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblDetail.setForeground(new Color(100, 100, 100));

        tglDollars = new JToggleButton("Showing: Future $ (nominal)");
        tglDollars.setToolTipText("<html><b>Real vs nominal dollars</b><br>"
                + "Converts <i>future-dated</i> figures between today's purchasing power<br>"
                + "and future nominal dollars.<br><br>"
                + "<b>Affects:</b> Portfolio bal, Yr 10 withdrawal, True median final<br>"
                + "balance, and every dollar column in the tables.<br><br>"
                + "<b>Does NOT affect</b> the headline withdrawal or the re-run trigger<br>"
                + "balance. Both are base-year figures where the inflation factor is<br>"
                + "1.000, so real and nominal are the same number -- they are marked<br>"
                + "<i>(today's $)</i> for that reason. This is correct, not a display bug.</html>");
        tglDollars.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tglDollars.setFocusPainted(false);
        tglDollars.addActionListener(e -> {
            showRealDollars = tglDollars.isSelected();
            tglDollars.setText(showRealDollars ? "Showing: Today's $ (real)" : "Showing: Future $ (nominal)");
            if (lastResults != null) updateUI(lastResults);
            // v9: the SS Optimizer's floors and columns are computed in the dollar
            // mode active at scan time, so we do NOT re-render cached optimizer rows
            // on toggle (that would relabel stale numbers). Flip the toggle, then
            // re-run the optimizer to see it in the other mode.
        });

        JPanel aNorth = new JPanel(new BorderLayout()); aNorth.setOpaque(false);
        JLabel aTitle = new JLabel(
                "Max sustainable Year-1 portfolio withdrawal at your PoS target -- re-solved annually on observed median balance");
        aTitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
        aTitle.setForeground(new Color(90, 90, 90));
        aNorth.add(aTitle, BorderLayout.WEST);
        aNorth.add(tglDollars, BorderLayout.EAST);

        JPanel aMid = new JPanel(new BorderLayout(2, 2)); aMid.setOpaque(false);
        aMid.add(lblAnswer, BorderLayout.CENTER);
        aMid.add(lblSub,    BorderLayout.SOUTH);
        lblBaseline = new JLabel(" ");
        lblBaseline.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblBaseline.setForeground(new Color(70, 90, 120));
        lblBaseline.setToolTipText("<html><b>Re-run trigger &amp; annual baseline (v6)</b><br>"
                + "<b>Re-run if below:</b> the portfolio balance at which your withdrawal<br>"
                + "rate would drift past your Pro PoS upper guardrail. Check it against<br>"
                + "your actual account any time between scheduled runs -- if you are<br>"
                + "under it, re-run early instead of waiting for your annual date.<br><br>"
                + "<b>vs baseline:</b> compares this run's Actual wd against the figure you<br>"
                + "captured with <i>Set as annual baseline</i>, and splits the change into:<br>"
                + "&nbsp;&nbsp;* <b>portfolio</b> -- your balance moved<br>"
                + "&nbsp;&nbsp;* <b>horizon</b> -- one fewer year to fund raises the safe rate<br>"
                + "&nbsp;&nbsp;* <b>go-go</b> -- you changed the multiplier, or it turned off at<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;the end of the go-go window (planned, not distress). Raising<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;go-go also slightly LOWERS the base draw, since the survival<br>"
                + "&nbsp;&nbsp;&nbsp;&nbsp;test spends more during those years -- both effects are shown.<br>"
                + "The split reconciles exactly to the total change.<br><br>"
                + "Changes smaller than the minimum-change %% are reported as<br>"
                + "<i>no material change</i> so ordinary noise does not prompt action.</html>");

        JPanel aSouth = new JPanel(new BorderLayout(2, 2)); aSouth.setOpaque(false);
        aSouth.add(lblDetail,   BorderLayout.NORTH);
        aSouth.add(lblBaseline, BorderLayout.SOUTH);
        answerBox.add(aNorth,   BorderLayout.NORTH);
        answerBox.add(aMid,     BorderLayout.CENTER);
        answerBox.add(aSouth,   BorderLayout.SOUTH);

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
                "OK Annual re-solve on observed balance (inner trials use inflation-indexed schedule)",
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
        tabs.addTab("Stress Test",                   buildStressTestPanel());
        tabs.addTab("Assumptions & Methods",         buildAssumptionsPanel());

        panel.add(topSection, BorderLayout.NORTH);
        panel.add(tabs,       BorderLayout.CENTER);
        return panel;
    }

    // == Pro PoS Table =====================================================
    private JComponent buildTablePanel() {
        String[] cols = {
                "User Age", "Cal yr", "Portfolio bal (50th%)",         // 0 1 2
                "Pro PoS withdrawal", "Actual wd", "Wd %",              // 3 4 5
                "Rate drift",                                             // 6 (v6: renamed from "Alert")
                "User SS", "Spouse SS", "Annuity", "Fixed Inc",          // 7 8 9 10
                "Living Exp", "Medical", "Tax (est)",                    // 11 12 13
                "Total spend", "Total income", "Surplus/gap",            // 14 15 16
                "Infl factor",                                           // 17
                "User RMD", "Spouse RMD", "Combined RMD", "-> Roth/MM (year)",  // 18 19 20 21
                "Portfolio Chg",                                            // 22
                "IRMAA", "MAGI", "Roth Conv", "Conv Tax",                  // 23 24 25 26 (24 MAGI v7)
                "Trad Bal", "Roth Bal", "Money Mkt (total)",               // 27 28 29 (v6)
                "Spend mult", "SS bridge"                                 // 30 31 (v6)
        };
        proColNames = cols.clone();   // v9: model-index -> header text for the picker
        tblProModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        tblPro = new JTable(tblProModel) {
            @Override public String getToolTipText(MouseEvent e) {
                int col = convertColumnIndexToModel(columnAtPoint(e.getPoint()));
                int row = rowAtPoint(e.getPoint());
                if (row < 0 || lastResults == null) return null;
                List<EnhRow> rows = lastResults.medianRows;
                if (row >= rows.size()) return null;
                EnhRow er = rows.get(row);
                switch (col) {
                    case 2 -> {
                        // v8: match the app-wide convention -- when Real $ is
                        // selected, tooltip figures are shown in today's dollars
                        // (value / inflFactor), same as every other cell tooltip.
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        long port   = (long) (er.balance / d);
                        long mm     = (long) (er.mmBal   / d);
                        long actual = port + mm;
                        return "<html><b>Portfolio balance -- true 50th percentile</b><br>"
                                + "The median of all " + lastResults.fanPathCount
                                + " stochastic fan paths at this year.<br>"
                                + "More conservative and accurate than a mean-return path.<br><br>"
                                + "<b>This column is the money the simulation actually draws on</b><br>"
                                + "(qualified accounts + spendable taxable). It does NOT include<br>"
                                + "the Money Mkt (total) reserve, which is real, already-taxed<br>"
                                + "cash the sim leaves untouched.<br><br>"
                                + "<b>Your ACTUAL total is Portfolio + Money Mkt (total):</b><br>"
                                + "&nbsp;&nbsp;Portfolio balance:&nbsp;&nbsp;&nbsp;" + CURRENCY.format(port) + "<br>"
                                + "&nbsp;&nbsp;+ Money Mkt (total): " + CURRENCY.format(mm) + "<br>"
                                + "&nbsp;&nbsp;<b>= ACTUAL total:&nbsp;&nbsp;&nbsp;&nbsp;" + CURRENCY.format(actual) + "</b>"
                                + (showRealDollars ? " <i>(today's $)</i>" : "") + "<br><br>"
                                + "The Money Mkt reserve is real spendable money you manage by<br>"
                                + "hand; it is simply walled off from the sim's withdrawal logic.</html>"; }
                    case 3 -> { return "<html><b>Pro PoS withdrawal</b><br>"
                            + "For each displayed year, runs a Monte Carlo binary search<br>"
                            + "against the 50th-percentile balance and remaining horizon<br>"
                            + "to find the largest withdrawal where &ge; target % of inner<br>"
                            + "trial paths survive. Inner trials use an inflation-indexed<br>"
                            + "schedule (per-year inflation draws chained cumulatively).<br>"
                            + "Year-over-year, the displayed withdrawal adapts to observed<br>"
                            + "portfolio drift. The Guyton-Klinger guardrails tab provides<br>"
                            + "an alternative dynamic-spending overlay.</html>"; }
                    case 9 -> {
                        // Annuity -- fixed nominal, erodes in real terms
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format(
                                "<html><b>Annuity / pension income</b><br>"
                                        + "This is a <b>fixed nominal amount</b> -- it does not adjust for inflation.<br><br>"
                                        + "Its purchasing power erodes every year. At a 3.79% inflation rate,<br>"
                                        + "it retains only about <b>67%% of its starting-year buying power</b><br>"
                                        + "after 10 years -- and roughly 50%% after 18 years.<br><br>"
                                        + "Toggle the <b>Real $</b> button (top right) to see this erosion<br>"
                                        + "directly: the annuity column will visibly shrink year over year<br>"
                                        + "while SS and portfolio withdrawals (which are inflation-adjusted)<br>"
                                        + "remain relatively stable in real-dollar terms.<br><br>"
                                        + "This year's nominal value: %s</html>",
                                CURRENCY.format((long)(er.annuity / d)));
                    }
                    case 5 -> {
                        // v6: Wd % cell -- explain the colour AND show the actual
                        // figures that tripped (or did not trip) the guardrail.
                        double up  = (spProPosUpperGuardrail != null) ? dv(spProPosUpperGuardrail) : 20.0;
                        double dn  = (spProPosLowerGuardrail != null) ? dv(spProPosLowerGuardrail) : 20.0;
                        String verdict;
                        if ("[^] above".equals(er.alert)) {
                            verdict = "<font color='#155E2D'><b>GREEN -- rate is at or above the upper "
                                    + "guardrail.</b></font><br>Capacity to spend more than plan.";
                        } else if ("[v] below".equals(er.alert)) {
                            verdict = "<font color='#A32D2D'><b>RED -- rate is at or below the lower "
                                    + "guardrail.</b></font><br>Consider trimming discretionary spend.";
                        } else {
                            verdict = "<b>Inside the guardrails</b> -- no signal.";
                        }
                        return String.format(
                                "<html><b>Wd %% -- effective withdrawal rate</b><br>"
                                        + "= Actual wd / portfolio balance for this year.<br><br>"
                                        + "<b>The numbers behind the colour</b> (go-go divided out of both,<br>"
                                        + "so this compares underlying base draws):<br>"
                                        + "&nbsp;&nbsp;Base rate this year:&nbsp;&nbsp;<b>%.3f%%</b><br>"
                                        + "&nbsp;&nbsp;Base rate in year 1:&nbsp;&nbsp;<b>%.3f%%</b><br>"
                                        + "&nbsp;&nbsp;Change vs year 1:&nbsp;&nbsp;&nbsp;&nbsp;<b>%+.1f%%</b><br>"
                                        + "&nbsp;&nbsp;Your guardrails:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;+%.0f%% / -%.0f%%<br><br>"
                                        + "%s<br><br>"
                                        + "<b>Read as context, not instruction.</b> The rate rises with age for<br>"
                                        + "a structural reason -- a shorter remaining horizon supports a<br>"
                                        + "higher safe rate -- so green appears from roughly year 6 onward in<br>"
                                        + "almost any plan. Judge a year against that trend.<br><br>"
                                        + "For decisions use the <b>re-run trigger balance</b> and the<br>"
                                        + "<b>vs baseline</b> comparison under the headline figure.</html>",
                                er.baseRate * 100, er.yr1Rate * 100, er.rateVsYr1 * 100, up, dn, verdict);
                    }
                    case 4 -> {
                        // Actual wd -- show the go-go breakdown if active
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        String wdStr     = CURRENCY.format((long)(er.wdActual  / d));
                        String posWdStr  = CURRENCY.format((long)(er.withdrawal / d));
                        // v6: three phases now, not two. Naming the wrong tier --
                        // or claiming a 1.0 multiplier during slow-go -- made the
                        // tooltip contradict the Actual wd figure beside it.
                        // v6: when a bridge exists the multiplier alone does NOT
                        // reconcile to Actual wd -- the bridge is added after it.
                        // Show the intermediate base draw so the arithmetic ties.
                        String bridgeLines = "";
                        if (er.ssBridge > 0) {
                            long base = Math.round((er.withdrawal / d) * er.goGoMult);
                            bridgeLines = "&nbsp;&nbsp;= base draw:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                                    + CURRENCY.format(base) + "<br>"
                                    + "&nbsp;&nbsp;+ SS bridge:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
                                    + CURRENCY.format((long)(er.ssBridge / d)) + "<br>";
                        }
                        if (er.goGoActive) {
                            return String.format(
                                    "<html><b>Actual wd -- GO-GO years active</b><br>"
                                            + "&nbsp;&nbsp;Pro PoS withdrawal: %s<br>"
                                            + "&nbsp;&nbsp;x go-go multiplier:&nbsp;&nbsp;%.3f<br>"
                                            + bridgeLines
                                            + "&nbsp;&nbsp;= Actual wd:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;%s<br><br>"
                                            + "The first tier of the spending curve -- elevated for<br>"
                                            + "your active early-retirement travel years.<br>"
                                            + "Slow-go follows for %d year(s), then no-go at 1.000.</html>",
                                    posWdStr, er.goGoMult, wdStr, lastResults.inp.slowGoDuration);
                        } else if (er.slowGoActive) {
                            return String.format(
                                    "<html><b>Actual wd -- SLOW-GO years active</b><br>"
                                            + "&nbsp;&nbsp;Pro PoS withdrawal: %s<br>"
                                            + "&nbsp;&nbsp;x slow-go multiplier: %.3f<br>"
                                            + bridgeLines
                                            + "&nbsp;&nbsp;= Actual wd:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;%s<br><br>"
                                            + "The MIDDLE tier of the spending curve. Go-go ended<br>"
                                            + "after %d year(s); this tier runs %d year(s) before<br>"
                                            + "spending drops to no-go at 1.000.<br><br>"
                                            + "This is the tier that converts late-life unspent<br>"
                                            + "Surplus into actual living rather than letting it<br>"
                                            + "accumulate unspent.</html>",
                                    posWdStr, er.goGoMult, wdStr,
                                    lastResults.inp.goGoDuration, lastResults.inp.slowGoDuration);
                        } else {
                            return String.format(
                                    "<html><b>Actual wd -- NO-GO years</b><br>"
                                            + "Both elevated tiers have ended -- multiplier = 1.000.<br>"
                                            + (er.ssBridge > 0
                                            ? "Pro PoS withdrawal (%s) + SS bridge "
                                            + CURRENCY.format((long)(er.ssBridge / d)) + ".<br><br>"
                                            : "Actual wd = Pro PoS withdrawal (%s).<br><br>")
                                            + "Go-go ran %d year(s), slow-go %d year(s).</html>",
                                    posWdStr, lastResults.inp.goGoDuration,
                                    lastResults.inp.slowGoDuration);
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
                    case 10 -> {
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format(
                                "<html><b>Fixed Inc = User SS + Spouse SS + Annuity</b><br>"
                                        + "&nbsp;&nbsp;User SS:&nbsp;&nbsp;&nbsp;&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;Spouse SS:&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;Annuity:&nbsp;&nbsp;&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;= Fixed Inc:&nbsp;<b>%s</b></html>",
                                CURRENCY.format((long)(er.manSS / d)),
                                CURRENCY.format((long)(er.womanSS / d)),
                                CURRENCY.format((long)(er.annuity / d)),
                                CURRENCY.format((long)(er.guaranteed / d)));
                    }
                    case 11 -> {
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format(
                                "<html><b>Living Exp = base input inflated to this year</b><br>"
                                        + "&nbsp;&nbsp;This year's value:&nbsp;<b>%s</b><br>"
                                        + "&nbsp;&nbsp;Inflation factor applied:&nbsp;%.3f<br><br>"
                                        + "Source: your Living Expense input, scaled by the median<br>"
                                        + "cumulative inflation factor for this year.</html>",
                                CURRENCY.format((long)(er.living / d)),
                                er.inflFactor);
                    }
                    case 14 -> {
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format(
                                "<html><b>Total spend = Living Exp + Medical + Tax</b><br>"
                                        + "&nbsp;&nbsp;Living Exp:&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;Medical:&nbsp;&nbsp;&nbsp;&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;Tax (est):&nbsp;&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;= Total spend:&nbsp;<b>%s</b></html>",
                                CURRENCY.format((long)(er.living / d)),
                                CURRENCY.format((long)(er.medical / d)),
                                CURRENCY.format((long)(er.tax / d)),
                                CURRENCY.format((long)(er.totalSpend / d)));
                    }
                    case 15 -> {
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format(
                                "<html><b>Total income = Actual wd + Fixed Inc</b><br>"
                                        + "&nbsp;&nbsp;Actual wd (portfolio draw):&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;Fixed Inc (guaranteed):&nbsp;&nbsp;&nbsp;&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;= Total income:&nbsp;<b>%s</b></html>",
                                CURRENCY.format((long)(er.wdActual / d)),
                                CURRENCY.format((long)(er.guaranteed / d)),
                                CURRENCY.format((long)(er.totalIncome / d)));
                    }
                    case 16 -> {
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format(
                                "<html><b>Surplus / gap = Total income - Total spend</b><br>"
                                        + "&nbsp;&nbsp;Total income:&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;Total spend:&nbsp;&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;= Surplus/gap:&nbsp;<b>%s</b><br><br>"
                                        + "Discretionary money over the budgeted living expenses --<br>"
                                        + "in go-go years this is the travel budget; later it funds<br>"
                                        + "lumpy costs or can be banked in a money market.<br><br>"
                                        + "<b>Does not accumulate:</b> this is this year's headroom only.<br>"
                                        + "The model never carries surplus forward, which is why the<br>"
                                        + "last years can show a negative gap.</html>",
                                CURRENCY.format((long)(er.totalIncome / d)),
                                CURRENCY.format((long)(er.totalSpend / d)),
                                (er.surplus >= 0 ? "+" : "-")
                                        + CURRENCY.format((long)(Math.abs(er.surplus) / d)));
                    }
                    case COL_ALERT -> {
                        if ("[^] above".equals(er.alert))
                            return "<html><b>[^] Raise alert</b><br>"
                                    + "This year's base withdrawal RATE (draw / balance, go-go removed)<br>"
                                    + "rose above the upper guardrail vs. the Year-1 base rate.<br>"
                                    + "Portfolio has outperformed; sustainable to spend more.</html>";
                        if ("[v] below".equals(er.alert))
                            return "<html><b>[v] Cut alert</b><br>"
                                    + "This year's base withdrawal RATE (draw / balance, go-go removed)<br>"
                                    + "fell below the lower guardrail vs. the Year-1 base rate.<br>"
                                    + "Consider reducing discretionary spending this year.</html>";
                        return null;
                    }
                    case COL_ROTH_MM -> {
                        if (er.rmdOverage <= 0) return null;
                        return "<html><b>-&gt; Roth/MM (year) -- this year's RMD overage</b><br>"
                                + "Combined RMD (" + CURRENCY.format(er.combRmd) + ")<br>"
                                + "exceeds this year's planned spending withdrawal.<br>"
                                + "Overage (" + CURRENCY.format(er.rmdOverage) + ") is a <b>yearly amount</b>, not a running total.<br><br>"
                                + "<b>v8:</b> this overage is RETAINED as already-taxed cash -- it is pulled<br>"
                                + "from Traditional (its income tax is already in the Tax column) and<br>"
                                + "added to <b>Money Mkt (total)</b>, where it accumulates. The simulation<br>"
                                + "never draws it for spending; it is your manual safety buffer.</html>";
                    }
                    case COL_BAL_DELTA -> {
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format("<html><b>Portfolio change: %s%s</b><br>"
                                        + "&nbsp;&nbsp;Market growth:&nbsp;&nbsp;+%s<br>"
                                        + "&nbsp;&nbsp;Withdrawal:&nbsp;&nbsp;&nbsp;-%s</html>",
                                er.balDelta >= 0 ? "+" : "",
                                CURRENCY.format((long)(er.balDelta / d)),
                                CURRENCY.format((long) (showRealDollars
                                        ? er.investmentGrowthReal : er.investmentGrowth)),
                                CURRENCY.format((long)(er.wdActual / d)));
                    }
                    case COL_TAX -> {
                        if (!er.drawing) return null;
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        if (er.magi == 0 && er.taxableSS == 0) {
                            // Legacy flat escalator was used (computed tax OFF).
                            return "<html><b>Tax (est) -- legacy flat escalator</b><br>"
                                    + "Computed tax engine is OFF. This is the Base tax input<br>"
                                    + "escalated by tax inflation: baseTax * (1+taxInfl)^year.<br>"
                                    + "Turn on 'Use computed tax engine' for a bracket-based figure.</html>";
                        }
                        return String.format(
                                "<html><b>Tax (est) -- living-expenses tax (federal + state + IRMAA)</b><br>"
                                        + "This is the tax on your SPENDING income only. Any Roth<br>"
                                        + "conversion is taxed SEPARATELY (see the Conv Tax column).<br><br>"
                                        + "&nbsp;&nbsp;Taxable Social Security:&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;Ordinary income (RMD/draw + annuity):&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;Top marginal bracket:&nbsp;<b>%s</b><br><br>"
                                        + "&nbsp;&nbsp;Federal tax:&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;%s:&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;IRMAA surcharge:&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;= Total living-expenses tax:&nbsp;<b>%s</b><br><br>"
                                        + "Brackets, standard deduction (MFJ + age-65) and IRMAA<br>"
                                        + "thresholds are 2026 statutory values, inflation-indexed.<br>"
                                        + "SS taxability via the provisional-income formula. See the<br>"
                                        + "Assumptions &amp; Methods tab.</html>",
                                CURRENCY.format((long)(er.taxableSS / d)),
                                CURRENCY.format((long)(er.ordinaryTax / d)),
                                er.topBracket,
                                CURRENCY.format((long)(er.fedTax / d)),
                                stateTaxLabel(er.calYear),
                                CURRENCY.format((long)(er.stateTax / d)),
                                CURRENCY.format((long)(er.irmaa / d)),
                                CURRENCY.format((long)(er.tax / d)));
                    }
                    case COL_IRMAA -> {
                        if (!er.drawing) return null;
                        if (er.irmaa <= 0)
                            return "<html><b>IRMAA surcharge: none</b><br>"
                                    + "MAGI from 2 years prior was at or below the Tier-0<br>"
                                    + "threshold (2026 base $218,000, indexed per your chosen<br>"
                                    + "IRMAA threshold-growth mode), so no Medicare Part B/D<br>"
                                    + "surcharge applies this year. If this stays all-dashes even in<br>"
                                    + "high-RMD late years, try the 'Frozen' threshold mode to see<br>"
                                    + "the conservative IRMAA exposure.</html>";
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format(
                                "<html><b>IRMAA surcharge (Medicare Part B + D)</b><br>"
                                        + "&nbsp;&nbsp;Amount this year:&nbsp;<b>%s</b> (per couple)<br><br>"
                                        + "Assessed on your MAGI from <b>2 years prior</b> (the IRMAA<br>"
                                        + "lookback). Because your conversions and later RMDs raise<br>"
                                        + "MAGI, they can trip a higher tier two years out. Thresholds<br>"
                                        + "are 2026 values, inflation-indexed. See Assumptions &amp; Methods.</html>",
                                CURRENCY.format((long)(er.irmaa / d)));
                    }
                    case COL_MAGI -> {
                        if (!er.drawing) return null;
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        long magiDisp = (long)(er.magi / d);
                        // Headroom to the active fill ceiling, when a fill ceiling
                        // exists for this row (fill mode). convCeiling is stored in
                        // nominal $, matching er.magi.
                        String headroom;
                        if (er.convCeiling > 0) {
                            long gap = (long)((er.convCeiling - er.magi) / d);
                            String which = er.convBoundByIrmaa
                                    ? "the IRMAA cliff (minus buffer)"
                                    : "the selected tax-bracket top";
                            headroom = (gap >= 0)
                                    ? String.format("&nbsp;&nbsp;Headroom to %s:&nbsp;<b>%s</b><br>",
                                    which, CURRENCY.format(gap))
                                    : String.format("&nbsp;&nbsp;<font color=red>OVER %s by %s</font><br>",
                                    which, CURRENCY.format(-gap));
                        } else {
                            headroom = "&nbsp;&nbsp;<i>No fill ceiling this row (flat or no conversion).</i><br>";
                        }
                        // Component breakdown. The engine builds MAGI as exactly
                        //   magi = taxableSS + tradDraw + annuity + conversion
                        // (see simulatePro: r.magi = taxableSS + ordinaryOther,
                        //  then magiWithConv = tr.magi + conv). taxableSS, annuity,
                        // conversion and magi are all stored on the row; the
                        // Traditional-draw term is not, so it is recovered exactly
                        // as the residual. Because the same pre-conversion taxableSS
                        // appears in both the stored magi and the stored taxableSS
                        // field, the residual equals the true Traditional draw
                        // (RMD or larger discretionary draw, whichever applied).
                        // Deriving it as the residual guarantees the four lines sum
                        // to the MAGI shown above in every case, in both nominal and
                        // real-dollar modes (each term divided by the same d).
                        long ssComp   = (long)(er.taxableSS / d);
                        long annComp  = (long)(er.annuity   / d);
                        long convComp = (long)(er.conversion/ d);
                        long tradComp = magiDisp - ssComp - annComp - convComp;
                        return String.format(
                                "<html><b>MAGI -- Modified AGI this year</b><br>"
                                        + "&nbsp;&nbsp;MAGI:&nbsp;<b>%s</b><br>"
                                        + "%s"
                                        + "<br><b>Components (sum to MAGI):</b><br>"
                                        + "&nbsp;&nbsp;Taxable Social Security:&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;Ordinary income (Traditional draw):&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;&nbsp;&nbsp;<i>= RMD or larger discretionary draw</i><br>"
                                        + "&nbsp;&nbsp;Annuity:&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;Roth conversion (gross):&nbsp;+%s<br>"
                                        + "&nbsp;&nbsp;----------------------------<br>"
                                        + "&nbsp;&nbsp;<b>MAGI total:&nbsp;%s</b><br>"
                                        + "<br>MAGI is the quantity IRMAA and the fill-to-target sizing<br>"
                                        + "key off -- NOT the Total income column (a cash-flow figure<br>"
                                        + "using full SS and excluding conversions). The gross conversion<br>"
                                        + "is ordinary income here even though only the net lands in Roth.<br>"
                                        + "The IRMAA surcharge you actually pay lags this by 2 years.</html>",
                                CURRENCY.format(magiDisp), headroom,
                                CURRENCY.format(ssComp),
                                CURRENCY.format(tradComp),
                                CURRENCY.format(annComp),
                                CURRENCY.format(convComp),
                                CURRENCY.format(magiDisp));
                    }
                    case COL_ROTH_CONV -> {
                        if (!er.drawing || er.conversion <= 0) return null;
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        String ceil = er.convCeiling > 0
                                ? CURRENCY.format((long)(er.convCeiling / d)) : "n/a";
                        String bind = er.convCeiling <= 0 ? "flat amount (no fill ceiling)"
                                : (er.convBoundByIrmaa
                                ? "selected IRMAA cliff (minus buffer)"
                                : "selected tax-bracket top");
                        return String.format(
                                "<html><b>Roth conversion this year (gross)</b><br>"
                                        + "&nbsp;&nbsp;Gross conversion:&nbsp;<b>%s</b><br>"
                                        + "&nbsp;&nbsp;Conversion tax (see Conv Tax col):&nbsp;%s<br>"
                                        + "&nbsp;&nbsp;Net landing in Roth IRAs:&nbsp;<b>%s</b><br>"
                                        + "&nbsp;&nbsp;Binding ceiling:&nbsp;%s (MAGI)<br>"
                                        + "&nbsp;&nbsp;Bound by:&nbsp;%s<br><br>"
                                        + "A separate Traditional distribution. The gross comes<br>"
                                        + "proportionally from the Traditional IRAs; the tax is paid<br>"
                                        + "from the conversion and leaves your asset base; the net<br>"
                                        + "(gross - tax) is split equally into the two Roth IRAs.<br>"
                                        + "The conversion raises MAGI (IRMAA-relevant) but is NOT in<br>"
                                        + "the living-expenses Tax column. See Assumptions &amp; Methods.</html>",
                                CURRENCY.format((long)(er.conversion / d)),
                                CURRENCY.format((long)(er.convTax / d)),
                                CURRENCY.format((long)(er.convNetToRoth / d)),
                                ceil, bind);
                    }
                    case COL_CONV_TAX -> {
                        if (!er.drawing || er.convTax <= 0) return null;
                        double d = showRealDollars ? er.inflFactor : 1.0;
                        return String.format(
                                "<html><b>Conversion tax -- stacked marginal (federal + state)</b><br>"
                                        + "&nbsp;&nbsp;Tax on this year's Roth conversion:&nbsp;<b>%s</b><br><br>"
                                        + "Computed as the MARGINAL tax the conversion adds ON TOP of<br>"
                                        + "your living-expenses taxable income: tax(living + conversion)<br>"
                                        + "minus tax(living), plus the selected state's tax on the<br>"
                                        + "conversion. The conversion does not get its own standard<br>"
                                        + "deduction (already used by spending income), so it stacks at<br>"
                                        + "your top bracket (typically 22%%).<br><br>"
                                        + "This tax is paid FROM the conversion and leaves your asset<br>"
                                        + "base -- the one real cost of converting. It is separate from<br>"
                                        + "the living-expenses Tax column. See Assumptions &amp; Methods.</html>",
                                CURRENCY.format((long)(er.convTax / d)));
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
                90,                         // 22
                80, 95, 90, 90,             // 23-26 (IRMAA, MAGI v7, Roth Conv, Conv Tax)
                100, 100, 100,               // 27-29 (v6 Trad Bal, Roth Bal, Money Mkt)
                85, 95                       // 30 31 (v6 Spend mult, SS bridge)
        };
        for (int i = 0; i < cw.length && i < tblPro.getColumnCount(); i++)
            tblPro.getColumnModel().getColumn(i).setPreferredWidth(cw[i]);

        // v9: the Rate drift column (model index 6) is hidden by
        // applyProColumnVisibility(), called once the panel is assembled, along
        // with any user-chosen hidden columns. The value stays in the MODEL so
        // every tooltip case, width entry and row-builder position keeps its
        // number. The signal it carried lives as colour on the Wd % number.

        // Header tooltips -- override getToolTipText directly for reliable per-column display
        JTableHeader hdr = new JTableHeader(tblPro.getColumnModel()) {
            @Override public String getToolTipText(MouseEvent e) {
                // v6: header lives on the VIEW; the tooltip switch is keyed on
                // MODEL indices, and they differ now that a column is hidden.
                int col = tblPro.convertColumnIndexToModel(columnAtPoint(e.getPoint()));
                return switch (col) {
                    case 4  -> "<html><b>Actual wd -- spending withdrawal</b><br>"
                            + "= Pro PoS withdrawal x the spending multiplier for that<br>"
                            + "year -- go-go, slow-go, or 1.000 in the no-go years.<br>"
                            + "Hover any cell to see which tier applies and the arithmetic.<br>"
                            + "This is the amount spent and deducted from the portfolio each year.<br>"
                            + "Actual wd = Pro PoS wd x the spending multiplier for that year,<br>"
                            + "PLUS any SS bridge draw (see the SS bridge column). The bridge<br>"
                            + "is added AFTER the multiplier, so in bridge years Actual wd is<br>"
                            + "more than multiplier x Pro PoS wd.<br>"
                            + "After go-go years: Actual wd = Pro PoS wd (multiplier = 1.0).<br>"
                            + "RMD overage above this is retained in Money Mkt (v8), not spent.</html>";
                    case 3  -> "<html><b>Pro PoS withdrawal -- max sustainable portfolio draw</b><br>"
                            + "The largest base withdrawal at which &ge; your PoS target of inner<br>"
                            + "Monte Carlo trials survive, re-solved each year on the median balance.<br>"
                            + "<b>Guaranteed income (SS/annuity) is NOT subtracted</b> -- this is what the<br>"
                            + "portfolio alone can sustain. See Surplus/gap for budget headroom.</html>";
                    case 10 -> "<html><b>Fixed Inc -- guaranteed income (fed by User SS + Spouse SS + Annuity)</b><br>"
                            + "= User SS + Spouse SS + Annuity for the year.<br>"
                            + "This is the non-portfolio income floor. Hover a cell to see the<br>"
                            + "three source values that add up to that year's figure.</html>";
                    case 11 -> "<html><b>Living Exp -- core living budget (fed by your Living Exp input)</b><br>"
                            + "= your base Living Expense input, inflated by the median cumulative<br>"
                            + "inflation factor for that year (stochastic 50th-percentile path).<br>"
                            + "Hover a cell to see the base input and the inflation factor applied.</html>";
                    case 14 -> "<html><b>Total spend -- committed budget (fed by Living Exp + Medical + Tax)</b><br>"
                            + "= Living Exp + Medical + Tax (est).<br>"
                            + "This is your fixed yearly commitment. Hover a cell to see the<br>"
                            + "three source values that add up to that year's total.</html>";
                    case 15 -> "<html><b>Total income -- money available (fed by Actual wd + Fixed Inc)</b><br>"
                            + "= Actual wd (portfolio draw) + Fixed Inc (guaranteed income).<br>"
                            + "This is the full pool available this year. Hover a cell to see the<br>"
                            + "two source values that add up to that year's total.</html>";
                    case 16 -> "<html><b>Surplus / gap -- Total income minus Total spend</b><br>"
                            + "= (Guaranteed income + Actual wd) - (Living + Medical + Tax).<br><br>"
                            + "This is <b>discretionary money -- already withdrawn, already taxed</b>.<br>"
                            + "It is not slack to ignore:<br>"
                            + "&nbsp;&nbsp;* <b>Go-go years:</b> this IS the travel budget. The go-go multiplier<br>"
                            + "&nbsp;&nbsp;&nbsp;&nbsp;raises the draw and the extra lands here. Meant to be spent,<br>"
                            + "&nbsp;&nbsp;&nbsp;&nbsp;not banked.<br>"
                            + "&nbsp;&nbsp;* <b>After go-go:</b> available for lumpy costs -- a car, a roof, a big<br>"
                            + "&nbsp;&nbsp;&nbsp;&nbsp;trip -- or to hold in a money market for later years.<br>"
                            + "&nbsp;&nbsp;* It is also the headroom for Roth conversions up to your bracket<br>"
                            + "&nbsp;&nbsp;&nbsp;&nbsp;and IRMAA ceilings.<br><br>"
                            + "<b>This column does NOT accumulate.</b> Each row is that year's headroom<br>"
                            + "only. The model never carries surplus forward into any bucket, so the<br>"
                            + "Money Mkt column does not grow from it and the portfolio balance does<br>"
                            + "not reflect it. Cumulative surplus over a long plan can reach several<br>"
                            + "hundred thousand dollars.<br><br>"
                            + "That is why the <b>final years can show a negative gap</b>: the capped draw<br>"
                            + "plus guaranteed income falls short of the inflation-indexed budget. In<br>"
                            + "practice you would cover it from surplus banked in earlier years --<br>"
                            + "which this model deliberately does not track. A negative gap is a<br>"
                            + "planning signal, not a shortfall you would actually face, unless you<br>"
                            + "had spent every surplus dollar along the way.</html>";
                    case 6  -> "<html><b>Rate drift (projected) -- advisory only</b><br>"
                            + "Flags when this year's base withdrawal RATE has drifted past your<br>"
                            + "Pro PoS guardrail versus the rate in year 1. The go-go multiplier is<br>"
                            + "divided out first, so it compares underlying draws.<br><br>"
                            + "<b>Read this as projection, not instruction.</b> Three cautions:<br>"
                            + "&nbsp;&nbsp;* It assumes you <b>never re-run</b>. Every re-run resets year 1, so<br>"
                            + "&nbsp;&nbsp;&nbsp;&nbsp;drift shown for a future year will usually never occur.<br>"
                            + "&nbsp;&nbsp;* <b>Row 1 can never flag</b> -- drift is zero there by construction --<br>"
                            + "&nbsp;&nbsp;&nbsp;&nbsp;so this column cannot trigger the run you actually act on.<br>"
                            + "&nbsp;&nbsp;* Late rows flag <b>structurally</b>: a shorter remaining horizon<br>"
                            + "&nbsp;&nbsp;&nbsp;&nbsp;supports a higher safe rate, so '[^] above' becomes normal with<br>"
                            + "&nbsp;&nbsp;&nbsp;&nbsp;age rather than signalling anything wrong.<br><br>"
                            + "To decide whether to act, use the <b>re-run trigger balance</b> and the<br>"
                            + "<b>vs baseline</b> comparison shown under the headline figure instead.</html>";
                    case 5  -> "<html><b>Wd % -- effective withdrawal rate</b><br>"
                            + "<b>Colour = guardrail signal (v6).</b> <font color='#155E2D'><b>Deep green</b></font>"
                            + " means this year's base<br>"
                            + "rate sits at least your upper guardrail ABOVE the year-1 rate --<br>"
                            + "capacity to spend more. <font color='#A32D2D'><b>Red</b></font>"
                            + " means it is that far BELOW --<br>"
                            + "consider trimming. Plain means inside the guardrails.<br><br>"
                            + "<b>Read it as context, not instruction.</b> The rate rises with age<br>"
                            + "for a purely structural reason -- a shorter remaining horizon<br>"
                            + "supports a higher safe rate -- so green appears from roughly<br>"
                            + "year 6 onward in almost every plan. Judge a year against that<br>"
                            + "trend, not against the colour alone.<br><br>"
                            + "For actual decisions use the <b>re-run trigger balance</b> and the<br>"
                            + "<b>vs baseline</b> comparison under the headline figure.<br><br>"
                            + "= Actual wd / portfolio balance.<br>"
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
                    case 18 -> "<html><b>User RMD</b><br>"
                            + "Required Minimum Distribution from the user's traditional IRA + 401K.<br>"
                            + "Begins age 75 (SECURE 2.0, born after 1960).</html>";
                    case 19 -> "<html><b>Spouse RMD</b><br>"
                            + "Required Minimum Distribution from the spouse's traditional IRA + 401K.<br>"
                            + "Begins age 75 (SECURE 2.0, born after 1960).</html>";
                    case 20 -> "<html><b>Combined RMD</b><br>"
                            + "Sum of man + woman RMDs.<br>"
                            + "Orange = RMD exceeds planned withdrawal; overage retained in Money Mkt (v8).</html>";
                    case 21 -> "<html><b>-> Roth/MM (year) -- RMD overage, per year</b><br>"
                            + "= max(0, Combined RMD - Actual wd). A <b>yearly flow</b>, not a total.<br>"
                            + "When RMD exceeds the planned spending withdrawal, the excess must<br>"
                            + "still be taken but is not spent on living expenses.<br><br>"
                            + "<b>v8:</b> this overage is RETAINED as already-taxed cash -- pulled from<br>"
                            + "Traditional (income tax already counted) and accumulated in the<br>"
                            + "<b>Money Mkt (total)</b> reserve, which the simulation never draws for<br>"
                            + "spending or conversions. It stays in the portfolio total as your<br>"
                            + "manual safety buffer.</html>";
                    case 22 -> "<html><b>Portfolio Chg -- portfolio balance change</b><br>"
                            + "<b>Basis follows the dollar toggle.</b> In <i>Today's $ (real)</i> this is the<br>"
                            + "change in PURCHASING POWER: each year's balance is deflated by its<br>"
                            + "own price level before subtracting. In <i>Future $ (nominal)</i> it is the<br>"
                            + "raw dollar change. The real figure can be negative while the nominal<br>"
                            + "one is positive -- that is inflation eating the gain, not an error.<br>"
                            + "= end-of-year balance - start-of-year balance.<br>"
                            + "= market growth - spending withdrawal.<br>"
                            + "Green = portfolio grew. Red = portfolio shrank.</html>";
                    case COL_TAX -> "<html><b>Tax (est) -- computed federal + state + IRMAA</b><br>"
                            + "When the computed tax engine is ON, this is derived each year from<br>"
                            + "taxable income: taxable Social Security (provisional-income formula)<br>"
                            + "+ ordinary income (max(RMD, Traditional draw) + annuity + conversion),<br>"
                            + "minus the MFJ + age-65 standard deduction, run through inflation-indexed<br>"
                            + "2026 brackets, plus the selected STATE tax and the IRMAA surcharge.<br>"
                            + "(State defaults to Arizona 2.5%, SS-excluded; choose Custom to model<br>"
                            + "another state.) When OFF, reverts to the legacy flat escalator. Hover<br>"
                            + "a cell for that year's breakdown. See the Assumptions &amp; Methods tab.</html>";
                    case COL_IRMAA -> "<html><b>IRMAA -- Medicare Part B + D surcharge (per couple)</b><br>"
                            + "Assessed on your MAGI from 2 years prior (the IRMAA lookback).<br>"
                            + "2026 tier thresholds, inflation-indexed. Conversions and later RMDs<br>"
                            + "raise MAGI and can trip a higher tier two years out. Included in Tax.<br>"
                            + "Hover a cell for the amount and tier detail.</html>";
                    case COL_MAGI -> "<html><b>MAGI -- Modified Adjusted Gross Income this year</b><br>"
                            + "Taxable Social Security + ordinary income (RMD / Traditional draw +<br>"
                            + "annuity) + this year's gross Roth conversion. This is the number the<br>"
                            + "IRMAA cliffs and the fill-to-target sizing key off -- distinct from the<br>"
                            + "Total income column (a cash-flow figure using full SS, no conversion).<br>"
                            + "Hover a cell to see headroom to the active fill ceiling. The IRMAA you<br>"
                            + "actually pay lags this MAGI by 2 years.</html>";
                    case COL_ROTH_CONV -> "<html><b>Roth Conv -- Roth conversion executed this year (gross)</b><br>"
                            + "In <b>Flat $</b> mode: the fixed annual amount you set. In <b>Fill to<br>"
                            + "MAGI target</b> mode: the largest conversion that keeps MAGI under the<br>"
                            + "binding ceiling (the lower of your selected IRMAA cliff minus buffer,<br>"
                            + "and your selected tax-bracket top), then capped. A separate Traditional<br>"
                            + "distribution: gross from the Traditional IRAs, net into the two Roth<br>"
                            + "IRAs. Raises MAGI (IRMAA) but is NOT in the Tax column. Hover a cell.</html>";
                    case COL_CONV_TAX -> "<html><b>Conv Tax -- tax on this year's Roth conversion</b><br>"
                            + "The stacked marginal tax (federal + AZ 2.5%) the conversion adds on<br>"
                            + "top of your living-expenses taxable income. Paid FROM the conversion<br>"
                            + "(net = gross - this tax lands in Roth) and leaves your asset base --<br>"
                            + "the one real cost of converting. Separate from the living-expenses<br>"
                            + "Tax column. Hover a cell for detail.</html>";
                    case 27 -> "<html><b>Trad Bal (v6) -- combined Traditional balance, 50th pct</b><br>"
                            + "The median across fan paths of the combined Traditional bucket<br>"
                            + "(User + Spouse, IRA + 401k) at this year. Unlike v5, this balance<br>"
                            + "is drawn down by spending (Traditional-FIRST) and by Roth<br>"
                            + "conversions, so it falls over time -- which is what makes future<br>"
                            + "RMDs and the widow-year tax shrink as conversions accumulate.<br>"
                            + "Drives the RMD columns via the User/Spouse Traditional share.<br><br>"
                            + "<b>This is a re-baselined median projection, not your decision<br>"
                            + "input</b> -- you re-run quarterly with live balances, so a future<br>"
                            + "year's figure is never something you act on. For the act-now<br>"
                            + "decision use the current-year <b>Roth Conv</b> column, sized against<br>"
                            + "the IRMAA cliff (2-yr MAGI lookback, both-spouse surcharge).<br>"
                            + "See Assumptions &amp; Methods section 6a.</html>";
                    case 31 -> "<html><b>SS bridge (v9) -- extra draw replacing SS not yet received</b><br>"
                            + "Nonzero when the <i>SS bridge mode</i> is not \"No bridge\".<br><br>"
                            + "<b>From simulation start (both):</b> from the withdrawal-start year,<br>"
                            + "BOTH spouses are bridged -- each until their own claim date -- at<br>"
                            + "the benefit each would have received claiming at withdrawal-start.<br>"
                            + "This fills the early pre-claim years so Surplus/gap does not go<br>"
                            + "negative just because SS has not switched on yet.<br><br>"
                            + "<b>From first claim (v6):</b> only the later claimant is bridged,<br>"
                            + "across the gap between the two claim dates.<br><br>"
                            + "The amount is grossed up to cover the extra tax an IRA<br>"
                            + "withdrawal carries versus Social Security.<br><br>"
                            + "<b>Partial years are prorated by month.</b> A claim starting mid-year<br>"
                            + "bridges only the months before it, so the first and last bridge<br>"
                            + "years are often smaller than the full years between them. That is<br>"
                            + "correct, not a rounding error.<br><br>"
                            + "It is already INCLUDED in Actual wd -- this column just shows how<br>"
                            + "much of that draw is bridge. The draw is applied in the PoS solver<br>"
                            + "as well: a heavier early bridge lowers the solved base withdrawal<br>"
                            + "rather than the success probability.</html>";
                    case 30 -> "<html><b>Spend mult (v6) -- which spending tier this year is in</b><br>"
                            + "The multiplier applied to the base withdrawal:<br>"
                            + "&nbsp;&nbsp;<b>go-go</b> -- first tier, elevated for active travel years<br>"
                            + "&nbsp;&nbsp;<b>slow-go</b> -- middle tier, moderately elevated<br>"
                            + "&nbsp;&nbsp;<b>1.000</b> -- no-go, base spending<br><br>"
                            + "Row shading matches: strong green for go-go, pale green for<br>"
                            + "slow-go, plain for no-go.<br><br>"
                            + "This is applied inside the PoS solver as well as the display,<br>"
                            + "so the probability of success reflects the elevated spending --<br>"
                            + "it is not a cosmetic uplift.</html>";
                    case 29 -> "<html><b>Money Mkt (total) -- already-taxed reserve</b><br>"
                            + "Running <b>account balance</b> (a total, not a yearly flow) of already-<br>"
                            + "taxed cash. Each year it (1) grows at that year's inflation rate,<br>"
                            + "then (2) adds that year's RMD overage (the -&gt; Roth/MM (year) value):<br>"
                            + "&nbsp;&nbsp;row 1 = <b>Money Mkt (prior, $)</b> input (carry-in from a past run)<br>"
                            + "&nbsp;&nbsp;each year: balance x (1 + inflation) + overage<br><br>"
                            + "The inflation growth reflects the assumption that money-market<br>"
                            + "return roughly equals inflation (~0% real), so the reserve holds<br>"
                            + "its purchasing power rather than eroding. It is strictly increasing<br>"
                            + "and computed as a true running balance -- no wobble, no lag. (v8<br>"
                            + "replaced the old median-composite version, which wobbled/lagged.)<br><br>"
                            + "<b>Never spent by the sim</b> -- it is your manual safety buffer and is<br>"
                            + "EXCLUDED from the Portfolio balance column. Your ACTUAL total =<br>"
                            + "Portfolio balance + Money Mkt (total) (see the Portfolio tooltip).<br><br>"
                            + "<b>Reads '--'</b> when it is zero: no prior carry-in and no overage yet.</html>";
                    case 28 -> "<html><b>Roth Bal (v6) -- combined Roth balance, 50th pct</b><br>"
                            + "The median combined Roth bucket (User + Spouse, IRA + 401k).<br>"
                            + "Grows by market return and by the NET of each Roth conversion<br>"
                            + "(gross minus conversion tax); drawn only after Traditional is<br>"
                            + "exhausted. Has no RMD and passes whole to the survivor, so it is<br>"
                            + "the tax-free reserve that blunts the widow-year bracket jump.<br><br>"
                            + "<b>Why the year-over-year jump can exceed the Roth Conv line:</b><br>"
                            + "this cell is an independently re-medianed share of the median<br>"
                            + "portfolio (median balance x median Roth fraction), not a single<br>"
                            + "traced account, so its delta folds in the rising Roth SHARE of<br>"
                            + "the whole portfolio as conversions accumulate -- it will not<br>"
                            + "reconcile against Roth Conv by simple addition, and is not meant to.<br><br>"
                            + "<b>This is a re-baselined median projection, not your decision<br>"
                            + "input</b> -- you re-run quarterly with live balances. For the act-now<br>"
                            + "decision use the current-year <b>Roth Conv</b> column, sized against<br>"
                            + "the IRMAA cliff (2-yr MAGI lookback, both-spouse surcharge).<br>"
                            + "See Assumptions &amp; Methods section 6a.</html>";
                    default -> null;
                };
            }
        };
        tblPro.setTableHeader(hdr);

        // Cell renderer
        tblPro.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private final Color GOGO_BG    = new Color(232, 248, 240);
            private final Color GOGO_WD_BG = new Color(180, 230, 205);
            // v6: slow-go tier -- deliberately paler than go-go so the three
            // phases read as high / medium / none at a glance.
            private final Color SLOWGO_BG    = new Color(243, 250, 246);
            private final Color SLOWGO_WD_BG = new Color(214, 240, 226);
            private final Color AMBER_BG   = new Color(255, 220, 100);
            private final Color AMBER_FG   = new Color(130, 80, 0);
            private final Color ORANGE_BG  = new Color(255, 200, 120);
            private final Color ORANGE_FG  = new Color(140, 60, 0);

            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                // v6 FIX: 'col' arrives as a VIEW index, but every test below is
                // written against MODEL indices. Once the Rate drift column was
                // hidden the two diverged by one for every column past it, which
                // silently shifted all colouring -- Portfolio Chg and Surplus/gap
                // stopped going red and the RMD highlighting moved one column right.
                col = t.convertColumnIndexToModel(col);
                if (!sel && lastResults != null && row < lastResults.medianRows.size()) {
                    EnhRow er = lastResults.medianRows.get(row);
                    boolean goGo   = er.goGoActive;
                    boolean slowGo = er.slowGoActive;
                    c.setBackground(goGo ? GOGO_BG
                            : slowGo ? SLOWGO_BG
                            : (row % 2 == 0 ? Color.WHITE : new Color(248, 248, 245)));
                    c.setForeground(Color.BLACK);
                    String s = v == null ? "" : v.toString();

                    if ((col == 3 || col == 4) && goGo) {
                        c.setBackground(GOGO_WD_BG); c.setForeground(new Color(0, 90, 50));
                    } else if ((col == 3 || col == 4) && slowGo) {
                        c.setBackground(SLOWGO_WD_BG); c.setForeground(new Color(0, 80, 60));
                    } else if (col == 5) {
                        // v6: the guardrail signal now lives as COLOUR on the Wd %
                        // number itself, instead of a separate always-on column.
                        // Deep green = rate well above yr1 (capacity to spend more);
                        // red = well below (consider trimming).
                        if ("[^] above".equals(er.alert)) c.setForeground(new Color(21, 94, 45));
                        else if ("[v] below".equals(er.alert)) c.setForeground(new Color(163, 45, 45));
                    } else if (col == COL_ALERT) {
                        if ("[^] above".equals(er.alert)) c.setForeground(new Color(21, 94, 45));
                        else if ("[v] below".equals(er.alert)) c.setForeground(new Color(163, 45, 45));
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

        // v9: toolbar above the table holding the column picker button.
        javax.swing.JButton btnCols = new javax.swing.JButton("Columns\u2026");
        btnCols.setToolTipText("Choose which Pro PoS columns to show. "
                + "Protected columns can't be hidden. Saved into the scenario file.");
        btnCols.addActionListener(e -> showProColumnPicker());
        JPanel bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2));
        bar.add(btnCols);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(bar,    BorderLayout.NORTH);
        wrap.add(scroll, BorderLayout.CENTER);

        // v9: apply the initial visibility (this also performs the engine-hidden
        // Rate drift removal, replacing the old manual removeColumn call).
        applyProColumnVisibility();
        return wrap;
    }

    // == v9: Pro PoS column picker ========================================
    // Rebuild the table's VIEW from the model so that exactly the non-hidden,
    // non-engine-hidden columns are shown, in model order. We remove every
    // current view column, then add back the ones we want. Because the tooltip
    // switches key on the MODEL index (via convertColumnIndexToModel), hiding a
    // column never breaks any tooltip. The engine-hidden Rate drift column (model
    // index 6) is always excluded regardless of picker state. Data is untouched --
    // this is view-only; the engine still computes every column.
    private void applyProColumnVisibility() {
        if (tblPro == null) return;
        javax.swing.table.TableColumnModel cm = tblPro.getColumnModel();

        // Preserve any per-column preferred widths the user or code has set, keyed
        // by model index, so re-adding a column does not reset its width.
        java.util.Map<Integer,Integer> widthByModel = new java.util.HashMap<>();
        for (int v = 0; v < cm.getColumnCount(); v++) {
            javax.swing.table.TableColumn tc = cm.getColumn(v);
            Object id = tc.getIdentifier();
            int mi = (id instanceof Integer) ? (Integer) id
                    : tblPro.convertColumnIndexToModel(v);
            widthByModel.put(mi, tc.getWidth());
        }

        // Remove all current view columns.
        while (cm.getColumnCount() > 0) cm.removeColumn(cm.getColumn(0));

        // Add back, in model order, every column that is not engine-hidden and not
        // user-hidden. Stamp each TableColumn's identifier with its model index so
        // width preservation and future rebuilds stay correct.
        int modelCount = tblProModel.getColumnCount();
        for (int mi = 0; mi < modelCount; mi++) {
            if (mi == PRO_RATE_DRIFT_COL) continue;      // engine-hidden, always
            if (proHiddenCols.contains(mi)) continue;    // user-hidden
            javax.swing.table.TableColumn tc = new javax.swing.table.TableColumn(mi);
            tc.setIdentifier(Integer.valueOf(mi));
            tc.setHeaderValue(tblProModel.getColumnName(mi));
            Integer w = widthByModel.get(mi);
            if (w != null && w > 0) tc.setPreferredWidth(w);
            cm.addColumn(tc);
        }
        tblPro.getTableHeader().repaint();
        tblPro.revalidate();
        tblPro.repaint();
    }

    // Modal picker: a flat checklist of every column (except the engine-hidden
    // Rate drift). Each row has a "Show" checkbox and a "Protect" (non-hideable)
    // checkbox. Protecting a column disables and forces-on its Show box so it
    // cannot be hidden. Reset restores all-shown. OK applies and is picked up by
    // the next save into the .ilscen file.
    private void showProColumnPicker() {
        if (tblProModel == null) return;
        int n = tblProModel.getColumnCount();

        final javax.swing.JCheckBox[] showBox    = new javax.swing.JCheckBox[n];
        final javax.swing.JCheckBox[] protectBox = new javax.swing.JCheckBox[n];

        JPanel grid = new JPanel(new java.awt.GridBagLayout());
        grid.setBackground(Color.WHITE);
        java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
        g.anchor = java.awt.GridBagConstraints.WEST;
        g.insets = new java.awt.Insets(2, 8, 2, 8);

        // Header row.
        g.gridy = 0;
        g.gridx = 0; grid.add(boldLabel("Column"), g);
        g.gridx = 1; grid.add(boldLabel("Show"), g);
        g.gridx = 2; grid.add(boldLabel("Protect"), g);

        int row = 1;
        for (int mi = 0; mi < n; mi++) {
            if (mi == PRO_RATE_DRIFT_COL) continue;   // never offered
            final int idx = mi;

            javax.swing.JCheckBox show = new javax.swing.JCheckBox();
            show.setBackground(Color.WHITE);
            show.setSelected(!proHiddenCols.contains(idx));

            javax.swing.JCheckBox prot = new javax.swing.JCheckBox();
            prot.setBackground(Color.WHITE);
            prot.setSelected(proProtectedCols.contains(idx));

            // A protected column cannot be hidden: force Show on and disable it.
            if (prot.isSelected()) { show.setSelected(true); show.setEnabled(false); }
            prot.addActionListener(e -> {
                if (prot.isSelected()) { show.setSelected(true); show.setEnabled(false); }
                else show.setEnabled(true);
            });

            showBox[idx]    = show;
            protectBox[idx] = prot;

            g.gridy = row++;
            g.gridx = 0; grid.add(new JLabel(proColNames[idx]), g);
            g.gridx = 1; grid.add(show, g);
            g.gridx = 2; grid.add(prot, g);
        }

        JScrollPane sp = new JScrollPane(grid);
        sp.setPreferredSize(new Dimension(360, 460));
        sp.getVerticalScrollBar().setUnitIncrement(18);

        javax.swing.JButton reset = new javax.swing.JButton("Reset (show all)");
        reset.addActionListener(e -> {
            for (int mi = 0; mi < n; mi++) {
                if (showBox[mi] == null) continue;
                showBox[mi].setSelected(true);
                // leave Protect flags as-is; reset is about visibility, not protection
            }
        });

        JPanel wrap = new JPanel(new BorderLayout(0, 8));
        wrap.add(new JLabel("<html><b>Choose which Pro PoS columns to show.</b><br>"
                + "Protected columns cannot be hidden. This selection is saved into<br>"
                + "the scenario (.ilscen) file.</html>"), BorderLayout.NORTH);
        wrap.add(sp, BorderLayout.CENTER);
        JPanel south = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        south.add(reset);
        wrap.add(south, BorderLayout.SOUTH);

        int res = javax.swing.JOptionPane.showConfirmDialog(
                this, wrap, "Pro PoS columns",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (res != javax.swing.JOptionPane.OK_OPTION) return;

        // Commit picker state to the model-index sets.
        proHiddenCols.clear();
        proProtectedCols.clear();
        for (int mi = 0; mi < n; mi++) {
            if (showBox[mi] == null) continue;             // skipped (Rate drift)
            if (protectBox[mi].isSelected()) proProtectedCols.add(mi);
            if (!showBox[mi].isSelected())   proHiddenCols.add(mi);
        }
        // A protected column can never be hidden, even if some other state tried to.
        proHiddenCols.removeAll(proProtectedCols);
        applyProColumnVisibility();
    }

    private JLabel boldLabel(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
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
    //  ASSUMPTIONS & METHODS TAB  (v3)
    //  Read-only reference documenting every coded assumption so a user (or
    //  anyone the tool is shared with) can see what is baked in without reading
    //  source. This is the canonical source of truth referenced by the tax /
    //  IRMAA / conversion tooltips.
    // =========================================================================
    private JScrollPane buildAssumptionsPanel() {
        JEditorPane ep = new JEditorPane();
        ep.setContentType("text/html");
        ep.setEditable(false);
        ep.setBackground(new Color(250, 253, 248));
        ep.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        // v9: table of contents at the top links to section anchors, and each
        // section ends with a back-to-top link back to the TOC. JEditorPane does
        // not auto-scroll on anchor clicks; this listener catches ACTIVATED events
        // and calls scrollToReference() to jump to the fragment.
        ep.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                String desc = e.getDescription();
                if (desc != null && desc.startsWith("#") && desc.length() > 1) {
                    ep.scrollToReference(desc.substring(1));
                }
            }
        });

        String html = ""
                + "<html><body style='font-family:sans-serif; font-size:12px; color:#222;'>"
                + "<h2 style='color:#2a5d34; margin-bottom:2px;'>Assumptions &amp; Methods</h2>"
                + "<div style='background:#eef5ec; border-left:4px solid #2a5d34; padding:8px 12px; "
                + "margin:6px 0 10px 0;'>"
                + "<p style='margin:0; font-weight:bold; color:#1f4a29;'>This software is executed "
                + "locally/offline: no per-seat licensing, no data leaving your machine.</p>"
                + "<p style='margin:4px 0 0 0; color:#33562f;'>(no mortality credit, temporary "
                + "one-big-beautiful-bill senior-bonus deduction omitted, fixed planning age) &mdash; errs "
                + "toward under-spending, which is the safe direction for a plan you're actually going to "
                + "live on.</p>"
                + "</div>"
                + "<p style='color:#555; margin-top:0;'><i>Read-only reference. Every coded assumption in the "
                + "tool is documented here. This tab is the canonical source the tax, IRMAA, and conversion "
                + "tooltips point to.</i></p>"

                + "<div style='background:#f0f4ee; border:1px solid #c8d2c1; padding:8px 14px; margin:0 0 16px 0;'>"
                + "<a name='toc'></a>"
                + "<div style='font-weight:bold; color:#2a5d34; margin-bottom:6px;'>Contents</div>"
                + "<ul style='margin:0 0 0 18px; padding:0;'>"
                + "<li><a href='#intro' style='color:#264653; text-decoration:none;'>Three approaches to dynamic spending</a></li>"
                + "<li><a href='#sec1' style='color:#264653; text-decoration:none;'>1. Core method &mdash; PoS as input, dollars as output</a></li>"
                + "<li><a href='#sec2' style='color:#264653; text-decoration:none;'>2. Market assumptions &mdash; forward capital-market basis (2026 CMAs)</a></li>"
                + "<li><a href='#sec3' style='color:#264653; text-decoration:none;'>3. Tax engine (v3) &mdash; federal + state + IRMAA</a></li>"
                + "<li><a href='#sec3a' style='color:#264653; text-decoration:none;'>3a. Single mode &mdash; spouse fields disabled (v4)</a></li>"
                + "<li><a href='#sec4' style='color:#264653; text-decoration:none;'>4. Standard deduction &mdash; what IS and is NOT modeled</a></li>"
                + "<li><a href='#sec5' style='color:#264653; text-decoration:none;'>5. Social Security taxability &mdash; provisional-income formula</a></li>"
                + "<li><a href='#sec6' style='color:#264653; text-decoration:none;'>6. IRMAA &mdash; Medicare surcharge, costed and visible</a></li>"
                + "<li><a href='#sec6a' style='color:#264653; text-decoration:none;'>6a. Which numbers to act on &mdash; the live column vs. the projected balances</a></li>"
                + "<li><a href='#sec7' style='color:#264653; text-decoration:none;'>7. Spending, medical costs, and Roth conversions</a></li>"
                + "<li><a href='#sec7a' style='color:#264653; text-decoration:none;'>7a. Spending curve &mdash; go-go, slow-go, no-go</a></li>"
                + "<li><a href='#sec8' style='color:#264653; text-decoration:none;'>8. Why PoS is the primary method &mdash; the withdrawal-rate flaw</a></li>"
                + "<li><a href='#sec9' style='color:#264653; text-decoration:none;'>9. RMDs, additional v9 stress inputs, and deliberately omitted features</a></li>"
                + "<li><a href='#sec10' style='color:#264653; text-decoration:none;'>10. Caveats</a></li>"
                + "<li><a href='#sec11' style='color:#264653; text-decoration:none;'>11. SS Optimizer &mdash; objective, feasibility, and ranking</a></li>"
                + "</ul></div>"
                + "<h3 style='color:#2a5d34;'><a name='intro'></a>Three approaches to dynamic spending</h3>"
                + "<p>This tool contains two spending engines, and it is worth understanding how each relates to "
                + "the risk-based guardrail method used by commercial platforms such as Income Lab (IL).</p>"

                + "<p><b>GK tab (withdrawal-rate guardrails).</b> This tab models the large Guyton-Klinger "
                + "adaptation of the naive 4% rule: Capital Preservation and Prosperity rules that mechanically "
                + "cut or raise the paycheck when the withdrawal <i>rate</i> (withdrawal divided by balance) "
                + "drifts past a band. It is a correct implementation of what it models. Its trigger, however, "
                + "sees only two numbers -- the withdrawal and the balance -- and is therefore blind to Social "
                + "Security timing, taxes, the spending smile, and mortality. It is retained deliberately: it is "
                + "the only engine in this tool that produces a lived cut series (initial real spend, worst real "
                + "spend, percent cut, years to recover), which is why the Stress Test runs on it.</p>"

                + "<p><b>Pro PoS Table tab (preferred).</b> This tab holds Probability of Success (PoS) constant "
                + "and re-solves the maximum sustainable withdrawal on every run. The dollar amount floats to "
                + "whatever pins the target PoS, so the plan's risk stays steady while the paycheck moves. There "
                + "is no spending-changing guardrail here by design -- when you re-pin PoS every period, there is "
                + "no drift to catch. The guardrail alerts on this tab are advisory only.</p>"

                + "<p><b>Income Lab (risk-based guardrails).</b> IL defines its trigger in risk space: a PoS "
                + "level, which folds in taxes, Social Security timing, longevity, mortality, and inflation. It "
                + "then inverts that risk threshold, holding the paycheck fixed, into a displayed portfolio-balance "
                + "number the household can watch (\"if your balance falls to $X we cut; if it rises to $Y you can "
                + "spend more\"). \"Risk-based\" means the trigger is defined in PoS space; \"guardrail\" means it "
                + "is expressed as a balance. IL does force withdrawals to meet the PoS -- but it waits until the "
                + "PoS has drifted far enough to hit a guardrail before forcing a reset, holding the paycheck "
                + "constant in between.</p>"

                + "<div style='background:#eef5ec; border-left:4px solid #2a5d34; padding:8px 12px; "
                + "margin:10px 0;'>"
                + "<p style='margin:0 0 6px 0; font-weight:bold; color:#1f4a29;'>The basic difference</p>"
                + "<p style='margin:0 0 6px 0; color:#33562f;'>The two risk-based methodologies work on two "
                + "different philosophies:</p>"
                + "<p style='margin:0 0 6px 0; color:#33562f;'><b>Pro PoS Tab:</b> This software holds Probability "
                + "of Success (PoS) constant, and accepts a withdrawal amount that can vary with each run. The "
                + "displayed yearly withdrawal amount needs to be divided by 4 if the user wants to run and reset "
                + "quarterly, and divided by 12 if the user wants to reset monthly.</p>"
                + "<p style='margin:0 0 6px 0; color:#33562f;'><b>Income Lab:</b> IL's method is to freeze the "
                + "withdrawal amount at a given level, and let the PoS drift until it hits a guardrail. Once a "
                + "guardrail is reached, the withdrawal amount can be reset.</p>"
                + "<p style='margin:0 0 6px 0; color:#33562f;'>The two risk-based philosophies solve the same "
                + "problem differently. Income Lab uses hysteresis so withdrawals stay constant between resets. "
                + "This tool performs a substantive reset whenever the user enters changed inputs (balances, "
                + "this year's taxes, spending, claim ages); a re-run with unchanged inputs only re-rolls the "
                + "random-number draw and shows the same answer with different noise. Both pin PoS; they differ "
                + "only in what triggers the reset and who does the sensing.</p>"
                + "<p style='margin:0; color:#33562f;'>This tool can be run as often as the user desires, even if "
                + "for curiosity. I suggest that the user make a regular habit of sticking to a major schedule to "
                + "run the Pro PoS Table with corrected and saved data, and reset the withdrawal amount every time "
                + "a new retirement \"paycheck\" is about to be created.</p>"
                + "</div>"

                + "<p><b>Pull versus push.</b> The deeper operational contrast is how each tool learns that "
                + "something has changed. This tool is a <i>pull</i> system: it recomputes on demand, whenever "
                + "the user brings it fresh data and asks. Its cadence is entirely user-chosen -- daily, weekly, "
                + "monthly, or whenever inputs change -- and the engine imposes no schedule of its own; the "
                + "quarterly habit suggested above is discipline, not a rule. Income Lab is a <i>push</i> system: "
                + "it pulls account balances automatically, recalculates monthly, and alerts the advisor when a "
                + "guardrail balance or the 5% accumulated-inflation threshold is crossed. The real difference is "
                + "therefore not the method but who does the data-refresh legwork: Income Lab automates the "
                + "sensing of market-driven balance drift, whereas this tool sees a change only when the user "
                + "types it in. That is an operational gap, not a defect in method -- and for a disciplined "
                + "single household willing to keep its own inputs current, pulling on demand pins risk just as "
                + "tightly as pushing on a schedule.</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec1'></a>1. Core method &mdash; PoS as input, dollars as output</h3>"
                + "<p>The Pro PoS engine is a two-level Monte Carlo withdrawal solver. Probability of Success (PoS) "
                + "is the <b>input constraint</b>; the maximum sustainable dollar withdrawal is the <b>output</b>. "
                + "The engine re-solves the sustainable draw each year against the observed 50th-percentile "
                + "(median) balance, so withdrawals adapt to portfolio drift rather than following a fixed schedule. "
                + "The intended workflow is frequent re-running (the sustainable dollar figure moves with the "
                + "current balance and other live inputs).</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec2'></a>2. Market assumptions &mdash; forward capital-market basis (2026 CMAs)</h3>"
                + "<p>Prior versions used the 1961-2024 historical average as the primary basis. The current "
                + "defaults are anchored to <b>forward-looking capital market assumptions</b> published in the "
                + "second half of 2025 by major asset managers, which forecast materially lower near-term returns "
                + "than the long-run historical average due to elevated large-cap valuations. Historical figures "
                + "remain available as user-selectable stress-case settings; they are documented at the end of "
                + "this section.</p>"

                + "<p><b>General inflation CAGR.</b> General price inflation is anchored to the Social Security "
                + "2026 Trustees Report, whose intermediate assumptions set an ultimate long-range CPI-W growth "
                + "rate of 2.4% per year, consistent with the CBO's projection that the Federal Reserve reaches "
                + "its 2% target over the 2027-2035 window. This assumption schedule holds general inflation "
                + "near 2.2% in the first five years and drifts to 2.4% for the balance of the horizon. Housing "
                + "and health are modeled as separate, faster-growing series and are therefore excluded from "
                + "this &quot;core&quot; figure; shelter CPI starts elevated at roughly 3.4% (per BLS data "
                + "through May 2026) and tapers toward ~2.5% long-run, while health is escalated on its own "
                + "spending basis (see the health assumption). All nominal returns elsewhere in the model are "
                + "deflated against this general-inflation series. The current default value is <b>2.3%</b>.</p>"

                + "<p><b>Market return CAGR.</b> Equity returns use nominal U.S. large-cap total-return "
                + "assumptions drawn from the 2026 forward-looking capital market assumptions of J.P. Morgan "
                + "(October 2025) and Vanguard (December 2025 VCMM run). Both houses forecast unusually muted "
                + "near-term returns driven by elevated starting valuations in large-cap technology: Vanguard "
                + "projects roughly 3.9%-5.9% annualized for U.S. equities over the next ten years, and J.P. "
                + "Morgan projects a 6.4% return for a 60/40 portfolio over a 10-15 year horizon. Accordingly, "
                + "this model uses ~4.5% for the first five years and ~4.9% at ten years, then reverts toward "
                + "a long-run ~7.0% for years 15 through 30 as valuations are assumed to normalize. Figures "
                + "beyond the sources' published 10-15 year horizons are interpolated toward historical norms "
                + "and are not independently sourced; all figures are nominal and are deflated by the general-"
                + "inflation series within the simulation. The current default value is <b>5.5%</b>.</p>"

                + "<p><b>EDJ (Edward Jones) CAGR.</b> The Edward Jones return series models a 65/35 stock-bond "
                + "allocation held in the Advisory Solutions Fund Models program, stated net of all fees. Gross "
                + "returns are built from the same 2026 J.P. Morgan and Vanguard building blocks used for the "
                + "market-return series (near-term ~5.3%, rising toward ~7.0% long-run for a 65/35 mix). From "
                + "this gross figure an all-in annual cost drag of approximately 1.7% is subtracted, comprising "
                + "the Edward Jones Program Fee (beginning at 1.35% per the February 2025 schedule, before "
                + "balance breakpoints), the added Platform Fee (~0.09%), and the embedded expense ratios of "
                + "the underlying funds (~0.35-0.50%). The resulting net series runs ~3.6% at year five, ~4.2% "
                + "at year ten, and ~5.3% for years 20 through 30. The near-term impact of fees is material "
                + "&mdash; roughly a third of gross return in the early years &mdash; and a higher household-"
                + "asset breakpoint would lower the program fee and raise these net figures by an estimated "
                + "10-20 basis points. This series is nominal and is deflated by the general-inflation series "
                + "within the simulation. <i>Reference documentation only &mdash; the model uses a single return "
                + "input; to evaluate the EDJ-managed path, enter the corresponding net figure in the Expected "
                + "nominal return field.</i></p>"

                + "<p><b>Other market inputs.</b></p>"
                + "<ul>"
                + "<li>Return standard deviation: <b>10.79%</b> (1961-2024 historical S&amp;P 500). Long-run "
                + "equity volatility is more stable than long-run mean return, so retaining the historical "
                + "volatility while using a forward mean is standard practice. Volatility, not average return, "
                + "dominates sequence-of-returns risk and the sustainable withdrawal.</li>"
                + "<li>Inflation standard deviation: 2.73% (1961-2024 historical CPI).</li>"
                + "<li>Social Security COLA: 2.4% (matches SSA Trustees intermediate assumption).</li>"
                + "</ul>"

                + "<p><b>Historical basis (available as a stress case).</b> The 1961-2024 historical averages "
                + "&mdash; nominal S&amp;P 500 CAGR of ~10.5%, general inflation of 3.79% &mdash; remain "
                + "available by editing the inputs. A useful stress pairing is the historical inflation (3.79%) "
                + "with the forward return (5.5%), which produces an implied real return near 1.7% and tests "
                + "the plan against a low-returns-and-high-inflation regime at once.</p>"

                + "<p>Sources: Social Security 2026 Trustees Report; Congressional Budget Office 2025 long-"
                + "term projections; J.P. Morgan 2026 Long-Term Capital Market Assumptions (October 2025); "
                + "Vanguard Capital Markets Model (December 2025 run); BLS CPI series through May 2026; "
                + "Edward Jones Advisory Solutions Fund Models fee schedule (February 2025). Asset-allocation "
                + "glide path and mortality weighting are deliberately NOT modeled (see section 9).</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec3'></a>3. Tax engine (v3) &mdash; federal + state + IRMAA</h3>"
                + "<p>When 'Use computed tax engine' is ON, the Tax column is computed each year rather than "
                + "escalated from a flat figure. All 2026 statutory dollar boundaries are <b>inflation-indexed "
                + "forward</b> using the same cumulative inflation factor the median path carries &mdash; this "
                + "mirrors how the IRS adjusts brackets, the standard deduction, and IRMAA thresholds via "
                + "chained-CPI, and prevents artificial bracket creep over a multi-decade horizon.</p>"

                + "<p><b>Filing status (v4).</b> A 'Filing status' selector switches the entire tax basis "
                + "between <b>Married filing jointly</b> (default) and <b>Single</b>. Changing it swaps the "
                + "standard deduction, the ordinary brackets, the age-65 add-on, the Social Security "
                + "provisional-income thresholds, and the IRMAA thresholds and per-person surcharge to the "
                + "matching 2026 single-filer figures (std deduction $16,100; 65+ add-on $2,050; brackets "
                + "$12,400 / $50,400 / $105,700 / $201,775 / $256,225 / $640,600; SS provisional $25,000 / "
                + "$34,000; IRMAA thresholds $109,000 / $137,000 / $171,000 / $205,000 / $500,000). In Single "
                + "mode the age-65 add-on follows the <b>User/primary person's age</b>; the spouse age is "
                + "ignored, because a single filer has one taxpayer.</p>"
                + "<p><b>Using Single for a survivor year.</b> This flag changes ONLY the tax math &mdash; it "
                + "does not alter accounts, Social Security streams, or RMDs. To model the death of one spouse, "
                + "load the saved joint scenario, consolidate the decedent's Traditional/Roth balances into the "
                + "surviving person's account fields (a surviving spouse rolls the IRA into their own, so RMDs "
                + "are then computed on the combined balance at the survivor's age &mdash; which this tool does "
                + "correctly), keep the survivor's larger Social Security benefit and zero the decedent's, zero "
                + "the decedent's remaining inputs, then set Filing status to Single. This is the one piece the "
                + "tool cannot infer on its own: without the switch, a survivor's taxes and IRMAA would be "
                + "computed on the roughly-double MFJ thresholds and would be understated &mdash; the unsafe "
                + "direction. This is the well-known 'widow's penalty': the survivor keeps much of the couple's "
                + "income but loses half the deduction and hits the brackets and IRMAA tiers at about half the "
                + "income.</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec3a'></a>3a. Single mode &mdash; spouse fields disabled (v4)</h3>"
                + "<p>Selecting <b>Single</b> filing status does more than switch the tax basis: every "
                + "<b>Spouse</b> input on the People, Accounts, and Social Security cards is <b>disabled, "
                + "grayed, and locked against editing</b>, and is <b>excluded from every calculation</b> "
                + "(income sum, RMD, portfolio total, and the Social Security optimizer). This applies to the "
                + "spouse's birth year/month, life expectancy, PIA and SS start dates, and all four spouse "
                + "account fields.</p>"
                + "<p>The entered spouse <b>values are preserved and stay visible in gray</b> &mdash; they are "
                + "not zeroed in the input fields &mdash; so switching back to <b>Married filing jointly</b> "
                + "restores the full joint plan instantly, with no re-entry. A grayed spouse value is never "
                + "counted in any total; if you see one, it is parked, not active.</p>"
                + "<p><b>Consolidation is therefore mandatory for a survivor run, and it is one-directional.</b> "
                + "Because the spouse balances are ignored while Single, the surviving person must hold the "
                + "combined portfolio: consolidate the decedent's account balances into the primary/User "
                + "account fields by hand. The toggle never moves money for you and never reverses a manual "
                + "consolidation. For this reason, do survivor modeling in a <b>separate saved scenario</b> "
                + "(Save-As a copy of the married plan): if you consolidate balances into the primary fields "
                + "and later flip back to MFJ in the same file, the decedent's original balances become live "
                + "again alongside your consolidated figure and double-count. A separate file keeps the "
                + "pristine married plan safe.</p>"
                + "<p>With the spouse's life-expectancy field disabled, the planning horizon follows the "
                + "primary/User plan age. Put the <b>survivor in the User/primary fields</b> so the horizon and "
                + "the age-65 add-on both anchor to the survivor's age.</p>"

                + "<p><b>How to consolidate (which field money lands in).</b> The default, and what this tool "
                + "assumes, is that the survivor rolls the decedent's accounts into their own IRAs:</p>"
                + "<ul>"
                + "<li>Spouse Traditional IRA + Spouse Traditional 401K &rarr; add into <b>User Traditional IRA</b></li>"
                + "<li>Spouse Roth IRA + Spouse Roth 401K &rarr; add into <b>User Roth IRA</b></li>"
                + "</ul>"
                + "<p>The one hard rule is that <b>pre-tax stays pre-tax and Roth stays Roth</b>: a Traditional "
                + "balance may only be consolidated into a Traditional (User) field, and a Roth balance only into "
                + "a Roth field. Moving Traditional money into a Roth field would represent a taxable Roth "
                + "conversion, which is not what consolidation means and is not modeled here.</p>"
                + "<p>The <b>IRA-versus-401K label does not affect the math.</b> RMDs are computed on the combined "
                + "Traditional balance at the survivor's age whether that balance sits in the 'IRA' or the '401K' "
                + "field, so collapsing all Traditional into the User Traditional IRA field (and all Roth into "
                + "the User Roth IRA field) is both correct and the simplest way to enter it.</p>"
                + "<p><b>Caveat &mdash; the real event has options this tool does not model.</b> A surviving spouse "
                + "is an eligible designated beneficiary with choices beyond a straight IRA rollover: leaving "
                + "funds in the employer plan, keeping an inherited IRA, or (under SECURE 2.0) electing to be "
                + "treated as the deceased for RMD purposes &mdash; an election that can let a <i>younger</i> "
                + "survivor defer RMDs until the older decedent would have reached RMD age. These can shift RMD "
                + "timing and are worth reviewing with a fee-only fiduciary and a CPA at the actual event. The "
                + "consolidation model here is a sound planning baseline, not tax advice.</p>"

                + "<p><b>2026 MFJ ordinary brackets</b> (taxable income = MAGI &minus; deductions):</p>"
                + "<ul>"
                + "<li>10% : $0 &ndash; $24,800</li>"
                + "<li>12% : $24,800 &ndash; $100,800</li>"
                + "<li>22% : $100,800 &ndash; $211,400</li>"
                + "<li>24% : $211,400 &ndash; $403,550</li>"
                + "<li>32% : $403,550 &ndash; $512,450</li>"
                + "<li>35% : $512,450 &ndash; $768,700</li>"
                + "<li>37% : above $768,700</li>"
                + "</ul>"
                + "<p><b>State income tax (selectable, v5):</b> the state is chosen from the 'State' "
                + "selector in the Tax Engine card. Two profiles ship. <b>Arizona</b> (the default) applies "
                + "a flat 2.5% to taxable income and excludes Social Security from the state base &mdash; "
                + "identical to the app's prior fixed behavior. <b>Custom (flat rate)</b> lets you enter any "
                + "flat rate plus two flags: <i>tax Social Security</i> (off = SS is subtracted from the "
                + "state base, matching most states) and <i>exclude retirement income</i> (on = the "
                + "retirement ordinary draw &mdash; RMD or Traditional withdrawal &mdash; is subtracted from "
                + "the state base, up to an optional dollar cap where 0 means unlimited). Each state's rules "
                + "are stored per tax year with full history, so a future rate or rule change is a data "
                + "update rather than a code change; a simulation year with no exact entry inherits the most "
                + "recent prior year's rules. Progressive (bracketed) state tax is supported by the engine "
                + "but no shipped profile uses it yet. The Custom profile is UNVERIFIED &mdash; confirm your "
                + "state's actual treatment of Social Security, pensions, IRA/401(k) distributions, and any "
                + "local income tax before relying on it.</p>"
                + "<p>Source: IRS Rev. Proc. 2025-32 (tax year 2026).</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec4'></a>4. Standard deduction &mdash; what IS and is NOT modeled</h3>"
                + "<p><b>Modeled (permanent provisions, inflation-indexed):</b></p>"
                + "<ul>"
                + "<li>Base MFJ standard deduction: $32,200 (2026)</li>"
                + "<li>Additional age-65 standard deduction: $1,650 per qualifying spouse (2026, per IRS "
                + "Rev. Proc. 2025-32), applied once each spouse reaches age 65 ($3,300 combined once both "
                + "are 65+). Supersedes the 2025 figure of $1,600.</li>"
                + "</ul>"
                + "<p><b>DELIBERATELY NOT modeled &mdash; LONG-TERM CARE:</b> A two- to three-year "
                + "long-term-care event commonly runs $300,000&ndash;$500,000 and is typically the largest "
                + "single financial risk in a retirement plan &mdash; larger than any sequence risk in the "
                + "Stress Test tab. <b>Nothing in this tool provides for it.</b> The Medical input covers "
                + "routine premiums and out-of-pocket costs only.</p>"
                + "<p>Two ways to bring it into the model yourself: carry an <b>LTC insurance policy and "
                + "include the premium inside Living expenses</b>, or add a <b>self-insurance amount to the "
                + "Medical figure</b> and let it inflate at your medical rate. Either choice makes the cost "
                + "visible in every projection. Ignoring it does not make it go away &mdash; it just moves "
                + "the risk off the screen.</p>"
                + "<p><b>DELIBERATELY NOT modeled &mdash; OBBBA 'senior bonus' deduction (a decision):</b> The One "
                + "Big Beautiful Bill Act (2025) added a temporary senior bonus deduction of up to $6,000 per "
                + "person age 65+, available for tax years <b>2025&ndash;2028 only</b>, with an MFJ phase-out "
                + "beginning at $150,000 MAGI (reduced 6% of MAGI over the threshold, fully gone by $250,000 MAGI), "
                + "and a hard sunset after 2028. It is <b>intentionally omitted</b> from this tool for three "
                + "reasons: (a) omitting it slightly OVERSTATES tax, the conservative and preferred direction; "
                + "(b) it avoids encoding fresh-statute phase-out and per-spouse eligibility-timing logic that is "
                + "date-of-birth and MAGI sensitive; and (c) at the household's planned MAGI (~$205k with "
                + "conversions) the bonus is already heavily phased out. <b>Verify with a tax professional before "
                + "relying on this deduction.</b> It can be added later once eligibility timing is confirmed.</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec5'></a>5. Social Security taxability &mdash; provisional-income formula</h3>"
                + "<p>The taxable portion of gross Social Security is computed with the real provisional-income "
                + "rule (not a flat 85%), so the tool is correct across income levels: provisional income = other "
                + "ordinary income + 50% of gross SS. MFJ thresholds: below $32,000 none of SS is taxable; between "
                + "$32,000 and $44,000 up to 50% is taxable; above $44,000 up to 85% is taxable (capped at 85% of "
                + "gross SS). These provisional thresholds are statutory and NOT inflation-indexed (fixed since "
                + "1993), which realistically pulls more SS into the taxable base over time.</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec6'></a>6. IRMAA &mdash; Medicare surcharge, costed and visible</h3>"
                + "<p><b>Important distinction:</b> the IRMAA column and the IRMAA piece of the Tax column contain "
                + "ONLY the income-related <b>surcharge</b> &mdash; the extra amount added to Medicare Part B and "
                + "Part D premiums when MAGI crosses the tier thresholds. They do NOT contain your <b>base</b> "
                + "Medicare premiums (Part B ~$203/mo/person and a Part D drug plan ~$40-55/mo/person in 2026). "
                + "Those base premiums are a MEDICAL cost, not a tax, and belong in your Medical spending input "
                + "(section 7 / the Medical column), not here. At MAGI below $218,000 the IRMAA surcharge is $0, "
                + "which is why the IRMAA column shows dashes at your planned income &mdash; that is correct, not "
                + "a missing cost.</p>"
                + "<p>The IRMAA <b>surcharge</b> is computed as a real dollar cost (not merely flagged) and "
                + "included in the Tax column, with its own IRMAA column. It is assessed on MAGI from <b>2 years "
                + "prior</b> (the statutory lookback). 2026 base tiers, inflation-indexed. The dollar figures below "
                + "are <b>per couple</b> &mdash; each spouse on Medicare owes the surcharge, so a married couple with "
                + "both enrolled pays <b>twice</b> the per-person amount, and that is what the tool charges (the MFJ "
                + "surcharge table is the per-person schedule &times; 2):</p>"
                + "<ul>"
                + "<li>Tier 0: MAGI &le; $218,000 &rarr; $0</li>"
                + "<li>Tier 1: $218,001 &ndash; $274,000 &rarr; $2,297 &nbsp;<span style='color:#777;'>($1,148/person &times; 2)</span></li>"
                + "<li>Tier 2: $274,001 &ndash; $342,000 &rarr; $5,770 &nbsp;<span style='color:#777;'>($2,885/person &times; 2)</span></li>"
                + "<li>Tier 3: $342,001 &ndash; $410,000 &rarr; $9,240 &nbsp;<span style='color:#777;'>($4,620/person &times; 2)</span></li>"
                + "<li>Tier 4: $410,001 &ndash; $750,000 &rarr; $12,710 &nbsp;<span style='color:#777;'>($6,355/person &times; 2)</span></li>"
                + "<li>Tier 5: &gt; $750,000 &rarr; $13,872 &nbsp;<span style='color:#777;'>($6,936/person &times; 2)</span></li>"
                + "</ul>"
                + "<p>IRMAA is a cliff: one dollar over a threshold triggers the full tier jump &mdash; which is "
                + "why the conversion fill leaves a buffer (section 7).</p>"
                + "<p><b>IRMAA threshold growth (user toggle):</b> unlike federal tax brackets (fully CPI-indexed), "
                + "IRMAA thresholds have a poor indexing history &mdash; they were frozen in nominal dollars from "
                + "2007 to 2019, and only the first four tiers have been indexed (to chained-CPI) since 2020; the "
                + "top tier remains frozen. So the tool lets you choose how the thresholds move:</p>"
                + "<ul>"
                + "<li><b>Chained-CPI (default, current law):</b> thresholds grow at inflation minus ~0.3%/yr, "
                + "matching post-2020 indexing.</li>"
                + "<li><b>Frozen (nominal 2026):</b> thresholds never move &mdash; the conservative stress case, "
                + "matching the 2007-2019 freeze and the real risk that Medicare funding pressure leads to future "
                + "freezes. This surfaces late-life RMD-driven IRMAA most clearly.</li>"
                + "<li><b>Full CPI:</b> thresholds grow at your full inflation assumption &mdash; the most "
                + "optimistic, since thresholds then outrun nominal income and IRMAA rarely triggers.</li>"
                + "</ul>"
                + "<p>The surcharge AMOUNT always tracks general inflation regardless of mode (Medicare costs rise "
                + "with prices); the toggle affects only the income THRESHOLDS. An all-dashes IRMAA column means "
                + "your MAGI stays under the (mode-dependent) thresholds every year.</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec6a'></a>6a. Which numbers to act on &mdash; the live column vs. the projected balances</h3>"
                + "<div style='background:#eef5ec; border-left:4px solid #2a5d34; padding:8px 12px; margin:6px 0 10px 0;'>"
                + "<p style='margin:0; font-weight:bold; color:#1f4a29;'>The balance columns are a re-baselined "
                + "projection, not the plan's decision input. The current-year Roth Conv column is the number you "
                + "act on.</p></div>"
                + "<p><b>Why the future balances are not the point.</b> Every dollar balance to the right of the "
                + "table &mdash; Portfolio, Trad Bal, Roth Bal, Money Mkt &mdash; is a percentile snapshot of a "
                + "stochastic fan (each cell is the independent median of <i>that</i> quantity across paths), so "
                + "the columns do not tie out arithmetically row-to-row and are not meant to. The intended workflow "
                + "is to re-run this tool every quarter (or at least yearly) with your <i>current, actual</i> "
                + "account balances typed in fresh. That re-baselining is what keeps the plan honest, so a balance "
                + "the tool projects for some year a decade out is never something you act on &mdash; by the time "
                + "that year arrives you will have re-run with real numbers many times over.</p>"
                + "<p><b>What you DO act on: the current-year Roth Conv column.</b> That figure answers the one "
                + "question the tool exists to answer for the here-and-now: <i>how much can I convert to Roth this "
                + "year without punching through the IRMAA cliff?</i> In fill-to-target mode it is sized to bring "
                + "your MAGI up to &mdash; but not past &mdash; the lower of your chosen IRMAA tier ceiling (minus "
                + "your buffer) and your chosen bracket edge. Because the IRMAA cliff is a true cliff (one dollar "
                + "over triggers the full tier), the fill leaves the buffer you set and stops there.</p>"
                + "<p><b>The two-year lookback is built in.</b> IRMAA bills on MAGI from <b>two years prior</b>, so "
                + "the conversion you do this year sets a Medicare premium two years out. Concretely for a couple "
                + "reaching Medicare together, the conversion sized in a given year is scored against the IRMAA "
                + "threshold of the year whose premium it will drive &mdash; a 2027 conversion is what sets 2029 "
                + "premiums. The engine carries a MAGI history and reads the year&minus;2 value for every IRMAA "
                + "assessment, so the column already respects the lookback; you do not need to shift years by hand.</p>"
                + "<p><b>Both spouses' surcharges are counted.</b> Once both of you are enrolled in Medicare, a tier "
                + "breach costs the surcharge <b>twice</b> &mdash; once per person. The tool uses the per-couple "
                + "(per-person &times; 2) MFJ schedule in section 6, so the cost it shows for crossing a cliff is "
                + "the full household cost, not a single person's. That is deliberately why the cliff is worth "
                + "protecting so carefully in the conversion sizing.</p>"
                + "<p><b>One conservative caveat in transition years.</b> The tool assumes both spouses are enrolled "
                + "in Medicare in every year it charges the couple surcharge. In a year when only one of you has "
                + "actually reached Medicare (for the household here, calendar 2027, before the younger spouse's "
                + "Part B begins), the true IRMAA owed for that single-enrollee year is only one person's share. "
                + "The tool may therefore show that one transition year's IRMAA as larger than it will really be. "
                + "This errs on the safe side &mdash; an overstated cliff cost makes the fill slightly more "
                + "cautious, so trusting the column never causes an over-conversion into a cliff; at worst it "
                + "leaves a little conversion headroom unused in that one year. Given that busting a cliff is far "
                + "worse than under-filling, that is the right direction to be wrong.</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec7'></a>7. Spending, medical costs, and Roth conversions</h3>"
                + "<p><b>Medical spending (the Medical column)</b> is a single lump input covering all health-care "
                + "costs: medical premiums and out-of-pocket (deductible and copay, dentist / vision / hearing). "
                + "This is where your <b>base Medicare premiums</b> belong &mdash; Part B (~$203/mo/person, 2026) "
                + "plus a Part D drug plan (~$40-55/mo/person) plus any Medigap/supplement &mdash; because Medicare "
                + "premiums are a medical cost, not a tax. The Medical line inflates at the medical-inflation rate. "
                + "Note that in the pre-Medicare bridge years this line represents your ACA/marketplace premium; "
                + "the figure changes once both spouses are on Medicare (~2027-2028), and the tool does not switch "
                + "it automatically &mdash; update the Medical input when that transition occurs.</p>"
                + "<p><b>Living-expenses tax (the Tax column)</b> is computed on SPENDING income only: "
                + "taxable Social Security (provisional formula) + ordinary income (max(combined RMD, "
                + "Traditional draw) + annuity), minus the MFJ + age-65 standard deduction, through the "
                + "brackets + the selected state tax + IRMAA. Pre-75 the discretionary "
                + "draw is treated as 100% Traditional; post-75 the RMD floors (and usually exceeds) the draw. "
                + "The annuity is ordinary income (held inside an IRA). RMD overages &mdash; the amount the "
                + "RMD forces out beyond what the household actually spends &mdash; are already-taxed dollars "
                + "that accumulate into a separate <b>Money Market reserve</b> (its own column on the Pro table). "
                + "The reserve grows with your inflation assumption each year, and is <b>excluded from the "
                + "drawable Portfolio balance</b> in every solve, so it never props up sustainability numbers. "
                + "A &quot;last-resort tap&quot; rule for when both Traditional and Roth run dry exists as a "
                + "clean engine hook but is intentionally unused today &mdash; the reserve is a real safety "
                + "pool the tool refuses to spend for you.</p>"
                + "<p><b>Roth conversions are modeled as a SEPARATE Traditional distribution</b>, not folded "
                + "into the living-expenses tax. Each conversion:</p>"
                + "<ul>"
                + "<li>Gross amount comes proportionally from the Traditional IRA accounts.</li>"
                + "<li>Its tax (Conv Tax column) is the <b>stacked marginal</b> tax it adds on top of the "
                + "living-expenses taxable income: tax(living + conversion) &minus; tax(living), plus the "
                + "selected state's tax on the conversion. It does not get its own standard deduction "
                + "(already consumed by spending income), so "
                + "it stacks at the top bracket (typically 22%).</li>"
                + "<li>The conversion tax is paid FROM the conversion and <b>leaves the asset base</b> &mdash; "
                + "the one real cost of converting. The net (gross &minus; tax) is split equally into the two "
                + "Roth IRA accounts.</li>"
                + "<li>The gross conversion raises MAGI and therefore matters for the IRMAA 2-year lookback and "
                + "for fill-to-target sizing &mdash; but it is NOT in the living-expenses Tax column.</li>"
                + "</ul>"
                + "<p><b>Why convert now:</b> Traditional dollars are ordinary-income-taxable whenever they come "
                + "out &mdash; by conversion now or by forced RMD later. Converting in the low-income gap years "
                + "pays a controlled ~22% and keeps MAGI under the IRMAA cliff; waiting lets the Traditional "
                + "balance grow until RMDs force larger distributions that, stacked on two full SS checks, can "
                + "land in the 24% bracket and trip IRMAA tiers. Conversion is rate arbitrage and cliff "
                + "avoidance, not tax avoidance.</p>"
                + "<p><b>Conversion sizing &mdash; two modes:</b></p>"
                + "<ul>"
                + "<li><b>Flat $ amount:</b> a fixed conversion applied every drawing year.</li>"
                + "<li><b>Fill to MAGI target:</b> each year converts the largest amount that keeps MAGI under "
                + "the binding ceiling &mdash; the lower of (a) the IRMAA Tier-0 cliff minus your buffer and "
                + "(b) the 22%&rarr;24% bracket edge &mdash; then capped by the cap input. Both ceilings are "
                + "inflation-indexed. In practice the IRMAA cliff usually binds first; the table reports which "
                + "ceiling bound the conversion each year.</li>"
                + "</ul>"
                + "<p><b>Fill mode + go-go interaction.</b> In Fill mode + go-go, the code protects a "
                + "<b>Roth conversion</b> from crossing the line, but nothing stops the <b>go-go withdrawal</b> "
                + "alone from pushing MAGI over an IRMAA threshold or into the 24% bracket. A go-go multiplier "
                + "that is higher than expected can breach either limit on its own. You can look for a Roth "
                + "conversion column of zeroes for hints of where you may need to look.</p>"
                + "<p><i>Account note: when the spouse retires, her 401(k) balances roll over (trustee-to-"
                + "trustee, non-taxable) into EDJ IRAs. The tool treats Traditional IRA and Traditional 401(k) "
                + "identically for RMD, so this is a label change, not a math change.</i></p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec7a'></a>7a. Spending curve &mdash; go-go, slow-go, no-go</h3>"
                + "<p>The tool models retirement spending as a <b>three-tier curve</b>, not a flat inflation-"
                + "indexed schedule. Each year's Living-Expenses figure is your base spending multiplied by "
                + "the multiplier active in that year:</p>"
                + "<ul>"
                + "<li><b>Go-go</b> years: base &times; the go-go multiplier (default 1.5x), for the go-go "
                + "duration (default 10 years starting at withdrawal-start).</li>"
                + "<li><b>Slow-go</b> years: base &times; the slow-go multiplier (default 1.3x), for the "
                + "slow-go duration (default 7 years), immediately following the go-go window.</li>"
                + "<li><b>No-go</b> years: base &times; 1.0, for every remaining year of the horizon.</li>"
                + "</ul>"
                + "<p>The three tiers together give retirement a <b>front-loaded shape</b> &mdash; high in "
                + "the active-travel decade, moderated in the middle years, tapered late. This matches lived "
                + "retirement: early dollars have far higher utility than late-life dollars, when travel "
                + "stops, socializing narrows, and spending concentrates on housing, food, and medical. A "
                + "flat or back-loaded curve is not the intent.</p>"
                + "<p><b>Travel money lives in the multiplier.</b> The go-go bump is not a separate travel "
                + "line item on top of ordinary spending; it <i>is</i> the travel-and-active-living uplift. "
                + "A 1.5x multiplier on a $105K base moves living expenses to $157.5K &mdash; the extra "
                + "$52.5K is your active-decade travel plus the general lift in going out, hobbies, and "
                + "comfort. Slow-go's 1.3x works the same way, sized for closer-to-home travel and creeping "
                + "medical. Because travel is <i>in</i> the multiplier, the SS Optimizer's go-go and slow-go "
                + "floors (section 11) are not travel budgets &mdash; they are surplus cushions on top of "
                + "the already-elevated spend. Setting them to your travel amount double-counts.</p>"
                + "<p><b>Multipliers can go below 1.0.</b> A go-go multiplier of 0.9 or a slow-go of 0.0 is "
                + "a legitimate design choice for a deliberate under-withdrawal window (for example, "
                + "protecting a Roth conversion window from pushing MAGI into IRMAA). The engine does not "
                + "force multipliers &ge; 1.0. If you set a slow-go multiplier to zero, the tool records a "
                + "zero living-expenses figure for those years and the surplus in that window equals the "
                + "guaranteed income &mdash; not an error, an intentional shape.</p>"
                + "<p><b>Sub-1.0 windows imply outside cash.</b> If living expenses in a window fall below "
                + "what the household actually consumes, the difference has to come from somewhere outside "
                + "the portfolio model &mdash; taxable savings, a home equity draw, family gifts. The tool "
                + "does not model those sources; it faithfully reports the surplus/gap the multiplier "
                + "produces and leaves the funding source to you.</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec8'></a>8. Why PoS is the primary method &mdash; the withdrawal-rate flaw</h3>"
                + "<p><b>Guyton-Klinger faithfulness.</b> The GK tab implements the 2006 Guyton-Klinger decision "
                + "rules (Journal of Financial Planning, \"Decision Rules and Maximum Initial Withdrawal Rates\"). "
                + "The core rules match the specification:</p>"
                + "<ul>"
                + "<li><b>Capital Preservation Rule (CPR, cut):</b> when the current withdrawal rate rises more "
                + "than the upper guardrail (default 20%) above the initial rate &mdash; because the portfolio "
                + "shrank &mdash; the withdrawal is cut 10%.</li>"
                + "<li><b>Prosperity Rule (PR, raise):</b> when the current rate falls more than the lower "
                + "guardrail (default 20%) below the initial rate &mdash; because the portfolio grew &mdash; the "
                + "withdrawal is raised 10%.</li>"
                + "<li><b>Inflation-skip (PMR0):</b> after a down-return year, the annual inflation raise is "
                + "skipped if the withdrawal rate is above the initial rate.</li>"
                + "</ul>"
                + "<p><b>Three deliberate deviations from the textbook GK spec.</b> This tool "
                + "(a) does NOT suspend the guardrails in the final 15 years of the horizon (the original authors "
                + "say the CPR/PR need not apply in the last 15 years; keeping them applied cuts spending later, "
                + "which is more cautious &mdash; conservative), (b) skips the inflation adjustment entirely in a "
                + "down-plus-high-rate year rather than capping it at 6% (conservative), and (c) applies this "
                + "application's <b>go-go/slow-go spending multiplier on top of the GK-rule withdrawal</b> "
                + "(Actual wd = GK withdrawal &times; go-go multiplier), which the 2006 spec has no concept of. "
                + "Deviation (c) is a front-loading overlay, NOT conservative: it deliberately raises early "
                + "withdrawals so the GK tab's spending shape is comparable to the Pro PoS tab. <b>To run a GK "
                + "simulation true to the textbook spec, set the go-go and slow-go durations (go-go years and "
                + "slow-go years) to 0 in the input panel</b> &mdash; then the multiplier resolves to 1.0 and the "
                + "withdrawal shown is the pure rule output. Note those durations are <b>shared inputs</b> that "
                + "also drive the Pro PoS and Stress Test tabs, so restore your normal go-go/slow-go settings "
                + "afterward.</p>"
                + "<p><b>The GK tab does not implement slow-go.</b> Unlike the Pro PoS engine, which has a full "
                + "three-tier go-go / slow-go / no-go curve, the GK engine reads only the go-go duration and "
                + "multiplier. The slow-go multiplier and slow-go duration are inert on this tab &mdash; setting "
                + "them changes nothing in the GK simulation. (Zeroing the slow-go years for a pure-GK run is "
                + "therefore harmless but, on the GK tab specifically, unnecessary; it matters for the shared "
                + "Pro PoS and Stress Test tabs.) The GK tab is provided as a comparison overlay and as the "
                + "engine behind the Stress Test tab &mdash; it is NOT the recommended planning method.</p>"
                + "<p><b>The core problem with ALL withdrawal-rate methods.</b> The 4% rule and its dynamic "
                + "descendants (including Guyton-Klinger) share a fundamental flaw in how they define success. "
                + "A withdrawal-rate method takes a percentage of the CURRENT balance, and 'success' is "
                + "conventionally measured as the portfolio never hitting zero. But a percentage of a balance can "
                + "never mathematically reach zero: 4% of $100 is $4, 4% of $4 is $0.16, and so on &mdash; it "
                + "approaches zero without ever arriving. <b>So a withdrawal-rate plan almost always 'succeeds' "
                + "by the portfolio-survival test, even when the retiree's actual spending has collapsed to "
                + "numbers no one could live on.</b> A $100 portfolio taking a $4 withdrawal 'succeeds' &mdash; "
                + "while the retiree starves. The portfolio survives; the lifestyle does not.</p>"
                + "<p>This is not a quirk of this implementation &mdash; it is the published critique. Kitces "
                + "(\"Why Guyton-Klinger Guardrails Are Too Risky For Most Retirees\") documents a 45% cut in real "
                + "income during the Great Depression under GK: the plan 'survived,' but spending was nearly "
                + "halved. The method over-preserves capital in downturns at the cost of living standards, with "
                + "no guarantee that withdrawals align with the retiree's actual needs or lifespan.</p>"
                + "<p><b>This is exactly why the Surplus/gap column on the GK tab turns red in later years, and "
                + "why the GK 'final balance' is misleading.</b> A red gap means the guardrail-driven paycheck "
                + "plus guaranteed income fell short of the budgeted spending that year. In this model the GK "
                + "withdrawal is set by the RULE (a percentage of balance, guardrail-adjusted), not by the "
                + "spending need &mdash; and that rule-driven withdrawal, after the go-go/slow-go multiplier is "
                + "applied (the Actual wd column), is what decrements the portfolio. The "
                + "shortfall is displayed but not funded, and the RMD overage is redirected to Roth/MM rather "
                + "than covering it. So the GK tab reports 'portfolio survived' while the red gaps show the "
                + "paycheck under-funded the budget for years. The survival is real; the ADEQUACY is not.</p>"
                + "<p><b>Why the PoS method is better &mdash; here and in general.</b> Probability of Success "
                + "inverts the question. Instead of asking 'does the portfolio survive at withdrawal rate X,' it "
                + "asks 'what SPENDING is sustainable at confidence level Y.' Spending &mdash; the retiree's "
                + "actual dollars &mdash; is the constraint that matters, and PoS puts it at the center. By "
                + "construction there is no unfunded gap: the Pro PoS tab solves for the withdrawal that funds "
                + "the target lifestyle at the chosen confidence, re-solving each year against the observed "
                + "balance. This is the risk-based-guardrails approach (Fitzpatrick and Tharp; see also Kitces, "
                + "Blanchett), and it is the overwhelmingly preferred methodology for retirement income planning "
                + "generally, not merely for this application. The GK tab is retained for comparison and for the "
                + "Stress Test's spending-adjustment story; the Pro PoS tab is the tool to plan by.</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec9'></a>9. RMDs, additional v9 stress inputs, and deliberately omitted features</h3>"
                + "<p><b>RMDs:</b> SECURE 2.0, begin at age 75 (born after 1960), computed from the IRS Uniform "
                + "Lifetime Table on median Traditional-account balances.</p>"

                + "<p><b>Death event (mid-projection).</b> A death-event input causes one spouse to die at a "
                + "chosen age; from that year forward the survivor holds the combined portfolio (Traditional "
                + "and Roth balances consolidated), the decedent's Social Security stream ends (survivor "
                + "keeps the larger of the two benefits), and living expenses are reduced by the "
                + "survivor-spend-cut percentage (default 20%). This is a Pro-engine mechanism that runs the "
                + "surviving-spouse half of the horizon inline; it does <b>not</b> switch the tax basis to "
                + "Single. For proper survivor tax modeling, use the Single filing status described in "
                + "section 3a as a separate scenario.</p>"

                + "<p><b>SS COLA shortfall.</b> The tool models the Social Security COLA as lagging general "
                + "inflation slightly (default 0.2%/yr shortfall vs CPI, adjustable). This captures the "
                + "well-documented under-indexing of the SSA's basket relative to the retiree spending "
                + "basket, and produces a small but compounding real-benefit erosion over a 30-year "
                + "horizon.</p>"

                + "<p><b>SS benefit haircut (v6 stress input).</b> A haircut percentage and start year lets "
                + "you stress-test a legislated cut &mdash; commonly used to model the 2033 Trust Fund "
                + "depletion projection where scheduled benefits would fall to ~77% of promised. Set the "
                + "percentage to 0 to disable. The haircut applies to both spouses' benefits from the "
                + "chosen year onward and is layered on top of the COLA shortfall.</p>"

                + "<p><b>SS bridge modes (v9).</b> The pre-claim &quot;bridge&quot; income schedule the "
                + "engine uses in years before a spouse claims their benefit has three modes:</p>"
                + "<ul>"
                + "<li><b>From simulation start (default)</b> &mdash; each spouse is bridged independently "
                + "from the withdrawal-start year up to that spouse's own claim date. Correct treatment of "
                + "asymmetric claim strategies (e.g., User claims early, Spouse delays to 70).</li>"
                + "<li><b>From first claim</b> &mdash; legacy v6 behavior. The bridge starts at whichever "
                + "spouse claims first. Retained byte-identical for regression checks.</li>"
                + "<li><b>None</b> &mdash; no bridge; the pre-claim years show whatever the portfolio can "
                + "actually cover without a phantom SS income stream. Useful for stress cases.</li>"
                + "</ul>"

                + "<p><b>Terminal-year artifact.</b> The Pro engine sizes withdrawals to hold your PoS "
                + "target, re-solved each year &mdash; it is <b>not</b> a &quot;spend the balance to zero&quot; "
                + "rule. In the final year of a long horizon, the PoS-driven draw can leave the surplus "
                + "slightly negative <i>even though the portfolio still holds plenty to cover it</i>. This "
                + "is a display artifact of a probability-of-success solver, not a real shortfall. The SS "
                + "Optimizer's &quot;Term grace&quot; setting (section 11) accounts for this by exempting "
                + "such covered terminal dips from the feasibility test; on the Pro tab, read a small "
                + "final-year negative surplus with balance remaining as &quot;you have the money, you just "
                + "wouldn't need to draw it under the PoS rule.&quot;</p>"

                + "<p><b>Deliberately omitted</b> (design decisions, not oversights):</p>"
                + "<ul>"
                + "<li>Asset-allocation glide path &mdash; a single return/volatility pair is used across "
                + "the horizon; a fixed slightly-pessimistic late-life volatility is the preferred "
                + "conservative direction.</li>"
                + "<li>Mortality discounting of the planning horizon &mdash; the plan runs to your entered "
                + "planning age with certainty. Late-life dollars are not weighted by survival probability. "
                + "This is separate from any per-spouse life-expectancy input, which only anchors default "
                + "planning ages; the horizon itself is deterministic. The direction is conservative &mdash; "
                + "planning for certainty at your planning age means the tool never assumes you die before "
                + "then and stops worrying about you.</li>"
                + "<li>The Guyton-Klinger tab retains the legacy flat tax escalator (the computed engine "
                + "applies to the Pro PoS median path). GK is a separate dynamic-spending overlay; keeping "
                + "its tax simple preserves it as a clean comparison baseline.</li>"
                + "</ul>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec10'></a>10. Caveats</h3>"
                + "<p>This tool is a self-directed planning aid, not tax or financial advice. Tax figures are "
                + "estimates from statutory tables current as of tax year 2026 and inflation-projected forward; "
                + "real future law will differ. The OBBBA senior bonus (section 4), the annuity decision point, "
                + "and any threshold-sensitive conversion should be verified with a fee-only fiduciary and/or a "
                + "tax professional.</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "<h3 style='color:#2a5d34;'><a name='sec11'></a>11. SS Optimizer &mdash; objective, feasibility, and ranking</h3>"
                + "<p>The SS Optimizer scans (User claim month) x (Spouse claim month) combinations and "
                + "<b>scores each one by running the real Pro engine</b> read-only. There is no separate deterministic "
                + "scorer -- every combination is a genuine Monte Carlo Pro simulation, so the taxes, bridge, RMDs, "
                + "conversions and everything else the Pro tab computes are all in play. The scan runs at reduced "
                + "fidelity (the <i>Scan paths</i> / <i>fan</i> inputs) for fast ranking; the top rows are then "
                + "<b>re-verified at your full Pro-tab fidelity</b> and marked <i>full</i>, so their numbers are precise.</p>"

                + "<p><b>Objective &mdash; feasibility, then survivor floor.</b> A combination is <i>feasible</i> when "
                + "it satisfies all of these:</p>"
                + "<ul>"
                + "<li><b>Stays green</b> every year at or above the <i>Green buffer</i> (subject to terminal grace, "
                + "below).</li>"
                + "<li><b>Go-go headroom</b> in every go-go year at or above the <i>Go-go floor</i>.</li>"
                + "<li><b>Slow-go headroom</b> in every slow-go year at or above the <i>Slow-go floor</i>.</li>"
                + "<li><b>PoS</b> holds at or above your <i>Target probability of success</i>.</li>"
                + "</ul>"
                + "<p>Among <b>feasible</b> combinations, the optimizer maximizes <b>JoAnn's (the spouse's) survivor "
                + "floor</b> -- the annual benefit the surviving spouse would keep for life, which is the larger of "
                + "the two claimed benefits. This directly rewards delaying the higher earner when the portfolio "
                + "can carry it, and it replaces the old go-go-guaranteed-income proxy that was retired because it "
                + "structurally forced early claiming.</p>"

                + "<p><b>Travel floors are cushions, not travel budgets.</b> Your go-go and slow-go multipliers "
                + "(e.g. 1.5x, 1.3x) already inflate spending during those years -- travel money lives in the "
                + "elevated spend. The optimizer's go-go and slow-go floors are the <b>additional surplus</b> "
                + "required <i>after</i> that elevated spend. Setting Go-go floor $25,000 does not mean $25K of "
                + "travel; it means $25K of leftover surplus on top of the 1.5x-elevated spending. To require a "
                + "meaningful cushion, keep them small (say, $0 to $5,000). Setting them to your intended travel "
                + "amount will double-count it and reject nearly every strategy.</p>"

                + "<p><b>Terminal grace.</b> The Pro-engine withdrawal is sized to hold your PoS target, not to "
                + "spend the balance to zero. In the last year or two of a long horizon that can leave surplus "
                + "slightly negative <i>even though the portfolio still holds plenty to cover it</i>. The <i>Term "
                + "grace</i> setting tells the optimizer to forgive a below-buffer surplus in the final N years "
                + "<b>only when the portfolio balance can cover the shortfall</b>. This prevents harmless end-of-"
                + "horizon dips from marking otherwise-sound strategies infeasible.</p>"

                + "<p><b>Real / Nominal.</b> The optimizer's floors and columns follow the top-right dollar toggle "
                + "at the moment you press Run SS Optimizer. In <b>Real (today's $)</b> mode, floors are compared to "
                + "surpluses deflated by inflation; in <b>Nominal (future $)</b> mode, to the raw surpluses. Flipping "
                + "the toggle does <i>not</i> recompute the displayed results -- switch modes and re-run to see them "
                + "in the other units.</p>"

                + "<p><b>Randomization.</b> When the <i>Re-randomize each run</i> checkbox on the Portfolio panel is "
                + "on, the optimizer captures one fresh seed per scan so every combination in that scan faces the "
                + "identical random future (fair ranking), but two different scans -- or the optimizer vs a later "
                + "Pro tab run -- draw different futures. To reproduce an optimizer row exactly, turn randomize "
                + "OFF; both the optimizer and the confirming Pro tab run will use seed 0 and agree within scan-"
                + "fidelity noise.</p>"

                + "<p><b>Ranking rules &mdash; and why some infeasible orderings look surprising.</b> Feasible "
                + "combinations always sort ahead of infeasible ones (Feasibility-min cell color tells you which). "
                + "Among <b>feasible</b>, ranking is by survivor floor descending, tiebreaker raw min surplus.</p>"
                + "<p>Among <b>infeasible</b>, the <i>If infeasible</i> dropdown decides:</p>"
                + "<ul>"
                + "<li><b>Show trade-off frontier</b> (default) &mdash; sorted by <b>survivor floor</b>. A badly-"
                + "infeasible combination with a high survivor floor can rank <i>above</i> a barely-infeasible one "
                + "with a lower survivor floor. Answers: <i>if I must accept infeasibility, what maximizes the "
                + "survivor floor?</i></li>"
                + "<li><b>Show closest (min shortfall)</b> &mdash; sorted by the <b>smallest worst-floor miss first</b>. "
                + "Barely-infeasible strategies rank at the top. Answers: <i>which strategies come closest to "
                + "passing?</i></li>"
                + "<li><b>Relax travel floor</b> &mdash; sorts like frontier; useful when you want to see what "
                + "headroom the best combinations actually deliver.</li>"
                + "</ul>"
                + "<p><b>Example of the frontier surprise.</b> Under frontier mode you can see a rank 5 row with "
                + "Feasibility min -$4,826 sitting above a rank 53 row with Feasibility min +$6,051 -- the rank 5 "
                + "misses several floors while rank 53 misses one small floor. The reason is not a bug: rank 5 has "
                + "survivor floor $57,987 (spouse delayed to 12/2032) while rank 53 has survivor floor $45,203 "
                + "(spouse claiming 08/2026). Frontier mode is optimizing survivor floor among infeasible, not "
                + "closeness to feasibility. Switch the dropdown to <b>Show closest</b> to get the ranking that "
                + "puts nearly-passing strategies at the top.</p>"

                + "<p><b>Feasibility-min column and its tooltip.</b> The color-coded <i>Feasibility min</i> column "
                + "shows the worst counted year (after terminal-grace exemption), green when feasible, red when "
                + "not. Hover any cell for a per-row tooltip that lists every failing year (calendar year, floor "
                + "type, surplus, needed level, portfolio balance), plus any terminal dips that grace forgave. "
                + "This is the single most useful way to see <i>why</i> a strategy fails without leaving the tab.</p>"

                + "<p><b>Filing status and annuity.</b> In Single mode the optimizer scans only the primary/User "
                + "person's claim months; spouse Social Security is excluded. In MFJ it scans the full two-person "
                + "grid. The <i>Use annuity</i> checkbox on the Portfolio panel is respected -- when the annuity is "
                + "off, it contributes $0 to every combination.</p>"

                + "<div style='text-align:right; margin:6px 0 12px 0;'><a href='#toc' style='color:#5566aa; text-decoration:none; font-size:11px;'>&uarr; back to top</a></div>"
                + "</body></html>";

        ep.setText(html);
        ep.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ep);
        sp.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        sp.getVerticalScrollBar().setUnitIncrement(16);
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

        // v9 Option 2: ranking is now FIXED -- feasibility first (green + travel
        // floors + PoS), then survivor floor among the feasible set. The old
        // "rank by" dropdown is retired; cmbOptSort is kept as a hidden field to
        // avoid touching the several places that still reference it, but it is not
        // added to the panel.
        cmbOptSort = new JComboBox<>(new String[]{ "Feasibility then survivor floor" });

        lblOptObjective = new JLabel(
                "  Objective: keep every year green + fund go-go/slow-go travel + hold PoS,"
                        + " then maximize JoAnn's survivor floor.");
        lblOptObjective.setFont(new Font("SansSerif", Font.ITALIC, 12));
        lblOptObjective.setForeground(new Color(150, 60, 0));

        chkOptimize = new JCheckBox("Optimize SS start dates (scan all combinations)", false);
        chkOptimize.setFont(new Font("SansSerif", Font.BOLD, 14));
        chkOptimize.setForeground(new Color(20, 60, 140));
        chkOptimize.setOpaque(false);
        chkOptimize.setToolTipText("<html><b>Checked:</b> Run the SS optimizer to find the best claiming ages.<br>"
                + "<b>Unchecked:</b> Use the SS start dates entered manually in the input panel.<br><br>"
                + "When unchecked, click Run Simulation on the Pro PoS / GK tabs as usual.</html>");

        JLabel modeNote = new JLabel(
                "  Each combination is scored by the real Pro engine (reduced-fidelity"
                        + " scan, then full re-verify of the top few).");
        modeNote.setFont(new Font("SansSerif", Font.ITALIC, 12));
        modeNote.setForeground(new Color(80, 80, 80));

        modeRow.add(chkOptimize);
        modeRow.add(lblOptObjective);
        modeRow.add(modeNote);

        // == Optimizer controls =============================================
        JPanel ctrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        ctrlRow.setBackground(new Color(245, 245, 242));

        btnRunOpt = new JButton("Run SS Optimizer");
        btnRunOpt.setFont(new Font("SansSerif", Font.BOLD, 14));
        btnRunOpt.setBackground(new Color(24, 130, 80));
        btnRunOpt.setForeground(Color.WHITE);
        btnRunOpt.setFocusPainted(false);
        btnRunOpt.setOpaque(true);
        btnRunOpt.setBorderPainted(true);
        btnRunOpt.setBorder(BorderFactory.createLineBorder(new Color(16, 90, 55), 1));
        btnRunOpt.setEnabled(false);
        btnRunOpt.addActionListener(e -> runSsOptimizer());

        btnCancelOpt = new JButton("Cancel");
        btnCancelOpt.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnCancelOpt.setBackground(new Color(180, 40, 40));
        btnCancelOpt.setForeground(Color.WHITE);
        btnCancelOpt.setFocusPainted(false);
        btnCancelOpt.setOpaque(true);
        btnCancelOpt.setBorderPainted(true);
        btnCancelOpt.setBorder(BorderFactory.createLineBorder(new Color(130, 28, 28), 1));
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

        // == v9 Option-2 objective controls: travel floors, buffer, grid, fidelity,
        //    infeasible fallback. All feed the feasibility test on THIS tab only.
        spOptGoGoFloor   = spinI(25000, 0, 200000, 1000, "#,###");
        spOptGoGoFloor.setToolTipText("<html><b>Go-go travel floor ($/yr, real)</b><br>"
                + "Minimum surplus/gap required in each go-go year for a claiming<br>"
                + "combination to be 'feasible'. This is your active-travel budget<br>"
                + "that must be affordable from discretionary headroom, not just on<br>"
                + "paper. SS Optimizer only -- does not affect Pro PoS spending.</html>");
        spOptSlowGoFloor = spinI(15000, 0, 200000, 1000, "#,###");
        spOptSlowGoFloor.setToolTipText("<html><b>Slow-go travel floor ($/yr, real)</b><br>"
                + "Minimum surplus/gap required in each slow-go year. Your closer-to-<br>"
                + "home travel budget. SS Optimizer only.</html>");
        spOptGreenBuffer = spinI(0, 0, 100000, 500, "#,###");
        spOptGreenBuffer.setToolTipText("<html><b>Stay-green buffer ($/yr, real)</b><br>"
                + "Minimum surplus/gap required in EVERY year (including non-travel<br>"
                + "years). 0 means 'never go red'. Raise it for a safety cushion.<br>"
                + "SS Optimizer only.</html>");

        spOptTermGrace = spinI(1, 0, 5, 1, "#");
        spOptTermGrace.setToolTipText("<html><b>Terminal grace (years)</b><br>"
                + "The Pro engine's withdrawal is sized to hold your probability-of-<br>"
                + "success target, re-solved each year -- it is not a 'spend the<br>"
                + "balance to zero' rule. In the final year or two of a long horizon,<br>"
                + "that can leave the surplus slightly negative <i>even though the<br>"
                + "portfolio still holds plenty to cover it</i> (e.g. surplus -$7K with<br>"
                + "$225K still in the account). That is a harmless end-of-horizon<br>"
                + "artifact, not a real shortfall -- in reality you would simply draw<br>"
                + "the small gap from the remaining balance.<br><br>"
                + "This tells the optimizer to tolerate a below-buffer surplus <b>only<br>"
                + "in the last N years, and only when the portfolio balance can cover<br>"
                + "the shortfall.</b> It prevents good claiming strategies from being<br>"
                + "marked 'infeasible' purely because of that terminal dip (which is<br>"
                + "why moving the horizon from 95 to 94 used to flip rows to<br>"
                + "feasible). Set it to 0 to require every single year to stay green<br>"
                + "with no exceptions. SS Optimizer only.</html>");

        cmbOptGrid = new JComboBox<>(new String[]{ "Year grid", "Half-year grid" });
        cmbOptGrid.setToolTipText("<html><b>Scan granularity</b><br>"
                + "Claim dates are scanned at this resolution in the coarse pass.<br>"
                + "Year grid is fastest; half-year doubles the combinations. The<br>"
                + "top results are then re-verified at full fidelity.</html>");

        spOptScanPaths = spinI(200, 50, 1000, 50, "#,###");
        spOptScanPaths.setToolTipText("<html><b>Scan solve paths (reduced fidelity)</b><br>"
                + "Monte Carlo paths per combination during the fast ranking scan.<br>"
                + "Lower = faster, noisier ranking. The winners are re-verified at<br>"
                + "your full Pro-tab path count, so the final numbers are precise.</html>");
        spOptScanFan   = spinI(100, 20, 500, 20, "#,###");
        spOptScanFan.setToolTipText("<html><b>Scan fan paths (reduced fidelity)</b><br>"
                + "Fan paths per combination during the scan. Same trade-off as<br>"
                + "solve paths -- fast ranking now, full re-verify later.</html>");
        spOptVerifyTopN= spinI(5, 1, 25, 1, "#");
        spOptVerifyTopN.setToolTipText("<html><b>Re-verify top N</b><br>"
                + "After the coarse scan ranks the combinations, the best N are<br>"
                + "re-run at your full Pro-tab fidelity so their PoS, surplus and<br>"
                + "survivor-floor numbers are exact.</html>");

        cmbOptInfeasible = new JComboBox<>(new String[]{
                "Show trade-off frontier", "Show closest (min shortfall)", "Relax travel floor" });
        cmbOptInfeasible.setToolTipText("<html><b>When no combination meets all floors</b><br>"
                + "<b>Show trade-off frontier</b> -- rank by survivor floor anyway and show<br>"
                + "each option's travel headroom, so you can see the trade you must<br>"
                + "make (e.g. claim JoAnn a year earlier to regain go-go headroom).<br>"
                + "<b>Show closest</b> -- rank by the smallest worst-floor miss.<br>"
                + "<b>Relax travel floor</b> -- drop the go-go/slow-go floors to whatever<br>"
                + "the best feasible combination can support, and report that level.</html>");

        JPanel objRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        objRow.setBackground(new Color(245, 245, 242));
        objRow.add(new JLabel("Go-go floor $:"));    objRow.add(spOptGoGoFloor);
        objRow.add(new JLabel("Slow-go floor $:"));  objRow.add(spOptSlowGoFloor);
        objRow.add(new JLabel("Green buffer $:"));   objRow.add(spOptGreenBuffer);
        objRow.add(new JLabel("Term grace yr:"));    objRow.add(spOptTermGrace);
        objRow.add(new JLabel("   Grid:"));          objRow.add(cmbOptGrid);
        objRow.add(new JLabel("Scan paths:"));       objRow.add(spOptScanPaths);
        objRow.add(new JLabel("fan:"));              objRow.add(spOptScanFan);
        objRow.add(new JLabel("Verify top:"));       objRow.add(spOptVerifyTopN);
        objRow.add(new JLabel("   If infeasible:")); objRow.add(cmbOptInfeasible);

        // == Results table ==================================================
        // v9 Option 2: columns show the real Pro-engine metrics for each claim
        // combination, so the trade-off is visible rather than collapsed to one pick.
        String[] optCols = {
                "Rank", "User SS Start", "Spouse SS Start",
                "PoS %", "Feasibility min", "Min surplus",
                "Go-go headroom", "Slow-go headroom", "Survivor Floor",
                "SS at full claim", "Verified"
        };
        tblOptModel = new javax.swing.table.DefaultTableModel(optCols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        tblOpt = new JTable(tblOptModel) {
            @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                int vRow = rowAtPoint(e.getPoint());
                int vCol = columnAtPoint(e.getPoint());
                if (vRow >= 0 && vCol == 4 && vRow < optRowResults.size()) {
                    return feasibilityTooltip(optRowResults.get(vRow));
                }
                return super.getToolTipText(e);
            }
        };
        tblOpt.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tblOpt.setRowHeight(24);
        tblOpt.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        tblOpt.setGridColor(new Color(220, 220, 215));
        tblOpt.setShowGrid(true);
        tblOpt.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        tblOpt.setSelectionBackground(new Color(190, 220, 255));

        int[] optWidths = {40, 110, 110, 60, 100, 100, 110, 110, 110, 110, 70};
        for (int i = 0; i < optWidths.length && i < tblOpt.getColumnCount(); i++)
            tblOpt.getColumnModel().getColumn(i).setPreferredWidth(optWidths[i]);

        // Row coloring: gold/silver/bronze for the top 3; infeasible rows tinted.
        // The "Feasibility min" column (index 4) is additionally shaded green (pass)
        // or red (fail) so the verdict reads at a glance without a Yes/No column.
        javax.swing.table.DefaultTableCellRenderer optRend = new javax.swing.table.DefaultTableCellRenderer() {
            final Color GOLD   = new Color(255, 245, 150);
            final Color SILVER = new Color(232, 232, 232);
            final Color BRONZE = new Color(245, 225, 200);
            final Color INFEAS = new Color(250, 232, 232);   // faint red row tint for not-feasible
            final Color FEAS_GREEN = new Color(198, 239, 206); // pass cell (col 4)
            final Color FEAS_RED   = new Color(255, 199, 199); // fail cell (col 4)
            @Override public java.awt.Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                java.awt.Component c = super.getTableCellRendererComponent(t,v,sel,foc,row,col);
                boolean feas = row < optRowResults.size() && optRowResults.get(row).feasible;
                if (!sel) {
                    Object ro = tblOptModel.getValueAt(row, 0);
                    int rank = ro instanceof Integer ? (Integer)ro : 9999;
                    if (col == 4) {
                        // Feasibility-min cell: green if the plan passes, red if it fails.
                        c.setBackground(feas ? FEAS_GREEN : FEAS_RED);
                    } else if (rank == 1) c.setBackground(GOLD);
                    else if (rank == 2) c.setBackground(SILVER);
                    else if (rank == 3) c.setBackground(BRONZE);
                    else if (!feas)     c.setBackground(INFEAS);
                    else                c.setBackground(row%2==0 ? Color.WHITE : new Color(248,248,245));
                    c.setForeground(Color.BLACK);
                }
                // Left-align the two date columns (1,2); right-align the rest.
                ((JLabel)c).setHorizontalAlignment((col==1||col==2)?LEFT:RIGHT);
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
                        + "  |  Floors & columns use the Real/Nominal mode active when you press Run"
                        + " -- flip the toggle (top right) and re-run to switch.");
        clickHint.setFont(new Font("SansSerif", Font.ITALIC, 12));
        clickHint.setForeground(new Color(0, 80, 150));

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setBackground(new Color(245, 245, 242));
        modeRow.setAlignmentX(LEFT_ALIGNMENT);
        ctrlRow.setAlignmentX(LEFT_ALIGNMENT);
        objRow.setAlignmentX(LEFT_ALIGNMENT);
        clickHint.setAlignmentX(LEFT_ALIGNMENT);
        north.add(modeRow);
        north.add(ctrlRow);
        north.add(objRow);
        north.add(clickHint);

        p.add(north,                   BorderLayout.NORTH);
        p.add(new JScrollPane(tblOpt), BorderLayout.CENTER);
        return p;
    }

    // =========================================================================
    //  STRESS TEST TAB  (v3)  -- Income Lab style
    // -------------------------------------------------------------------------
    //  Runs the existing Guyton-Klinger guardrail engine (simulateGK, UNCHANGED)
    //  through each historical crisis sequence and reports the spending-
    //  ADJUSTMENT and RECOVERY story rather than a pass/fail score. Philosophy
    //  follows Income Lab's Retirement Stress Test: a dynamic (guardrail) plan
    //  rarely fails outright -- it adjusts, usually temporarily, and recovers.
    //  All metrics are derived from the GkRow series the engine already returns.
    //  Uses current inputs (including the go-go multiplier); no prior run needed.
    // =========================================================================
    private JPanel buildStressTestPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(new Color(245, 245, 242));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // == Intro / philosophy banner =====================================
        JPanel banner = new JPanel(new BorderLayout());
        banner.setBackground(new Color(230, 240, 255));
        banner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(150, 190, 240), 1),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        JLabel bannerText = new JLabel("<html><b>Retirement Stress Test</b> -- runs your Guyton-Klinger "
                + "guardrail plan through the worst market periods in history and shows how spending would "
                + "have been <b>adjusted and restored</b>, not whether it 'passes' or 'fails'. A dynamic "
                + "plan bends; the question is how much and for how long.</html>");
        bannerText.setFont(new Font("SansSerif", Font.PLAIN, 12));
        bannerText.setForeground(new Color(30, 60, 120));
        banner.add(bannerText, BorderLayout.CENTER);

        // == Controls ======================================================
        JPanel ctrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        ctrlRow.setBackground(new Color(245, 245, 242));

        btnRunStress = new JButton("Run Stress Test");
        btnRunStress.setFont(new Font("SansSerif", Font.BOLD, 14));
        btnRunStress.setBackground(new Color(24, 130, 80));
        btnRunStress.setForeground(Color.WHITE);
        btnRunStress.setFocusPainted(false);
        btnRunStress.setOpaque(true);
        btnRunStress.setBorderPainted(true);
        btnRunStress.setBorder(BorderFactory.createLineBorder(new Color(16, 90, 55), 1));
        btnRunStress.addActionListener(e -> runStressTest());

        lblStressStatus = new JLabel("Click Run Stress Test to run your current plan through each historical crisis.");
        lblStressStatus.setFont(new Font("Monospaced", Font.PLAIN, 12));
        lblStressStatus.setForeground(new Color(60, 60, 60));

        ctrlRow.add(btnRunStress);
        ctrlRow.add(lblStressStatus);

        JPanel north = new JPanel(new BorderLayout(0, 6));
        north.setBackground(new Color(245, 245, 242));
        north.add(banner,  BorderLayout.NORTH);
        north.add(ctrlRow, BorderLayout.CENTER);

        // == Results table =================================================
        String[] cols = {
                "Historical Scenario", "Starts yr", "Initial Spend", "Worst-Yr Spend (real)",
                "Max Cut %", "# Guardrail Cuts", "Yrs to Recover",
                "Final Balance", "Survived"
        };
        tblStressModel = new javax.swing.table.DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        tblStress = new JTable(tblStressModel) {
            @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                int col = convertColumnIndexToModel(columnAtPoint(e.getPoint()));
                switch (col) {
                    case 0: return "<html>The historical crisis the guardrail plan was run through.<br>"
                            + "After the sequence ends, remaining years use your random distribution.</html>";
                    case 1: return "<html>Spending in the first drawing year (after the go-go multiplier).<br>"
                            + "This is the baseline the recovery metric returns to.</html>";
                    case 2: return "<html>The lowest spending year during the crisis, in <b>real</b> (today's<br>"
                            + "dollars) terms -- so an inflation-driven nominal rise is not mistaken for<br>"
                            + "'no cut'. This is the trough of the guardrail adjustment.</html>";
                    case 3: return "<html>Peak-to-trough spending cut: (initial - worst) / initial, in real<br>"
                            + "terms. How deep the temporary spending reduction went.</html>";
                    case 4: return "<html>Number of years the Capital Preservation Rule (CPR) cut the<br>"
                            + "withdrawal 10%. Each is a guardrail adjustment triggered by the crisis.</html>";
                    case 5: return "<html>Years from the spending trough until real spending returned to the<br>"
                            + "initial level. 'not recovered' means spending stayed reduced through the<br>"
                            + "remaining horizon. Income Lab emphasizes this recovery timeline.</html>";
                    case 6: return "<html>Portfolio balance at the end of the full horizon (nominal).</html>";
                    case 7: return "<html><b>Yes</b> = the portfolio never depleted through the full horizon.<br>"
                            + "A guardrail plan that survives with reduced-then-restored spending is a<br>"
                            + "success in the Income Lab sense, even if spending dipped along the way.</html>";
                    default: return null;
                }
            }
        };
        tblStress.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tblStress.setRowHeight(26);
        tblStress.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        tblStress.setGridColor(new Color(220, 220, 215));
        tblStress.setShowGrid(true);
        tblStress.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        tblStress.setSelectionBackground(new Color(190, 220, 255));

        int[] cw = { 220, 110, 150, 90, 130, 120, 130, 90 };
        for (int i = 0; i < cw.length && i < tblStress.getColumnCount(); i++)
            tblStress.getColumnModel().getColumn(i).setPreferredWidth(cw[i]);

        // Coloring: survived/recovered green tones; not-recovered amber (NOT red).
        tblStress.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private final Color OK_BG   = new Color(225, 245, 225);
            private final Color WARN_BG = new Color(255, 240, 205);
            @Override public java.awt.Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                java.awt.Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel) {
                    c.setBackground(Color.WHITE);
                    Object survived = tblStressModel.getValueAt(row, 7);
                    Object recover  = tblStressModel.getValueAt(row, 5);
                    boolean didSurvive = "Yes".equals(survived);
                    boolean didRecover = recover != null && !recover.toString().startsWith("not");
                    if (col == 7) c.setBackground(didSurvive ? OK_BG : WARN_BG);
                    if (col == 5) c.setBackground(didRecover ? OK_BG : WARN_BG);
                }
                setHorizontalAlignment(col == 0 ? LEFT : RIGHT);
                return c;
            }
        });

        // == Verdict line ==================================================
        lblStressVerdict = new JLabel(" ");
        lblStressVerdict.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblStressVerdict.setForeground(new Color(30, 90, 50));
        lblStressVerdict.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(new Color(245, 245, 242));
        south.add(lblStressVerdict, BorderLayout.CENTER);

        p.add(north,                       BorderLayout.NORTH);
        p.add(new JScrollPane(tblStress),  BorderLayout.CENTER);
        p.add(south,                       BorderLayout.SOUTH);
        return p;
    }

    /**
     * Run the GK guardrail engine through each historical scenario (indices 1-4)
     * and populate the stress-test summary. Reuses simulateGK unchanged; all
     * metrics derive from the returned GkRow series. Uses current inputs.
     */
    private void runStressTest() {
        if (tblStressModel == null) return;
        tblStressModel.setRowCount(0);
        lblStressStatus.setText("Running guardrail plan through historical crises...");

        int scenarioCount = HistoricalScenarios.SCENARIO_NAMES.length; // includes index 0 (random)
        int survivedCount = 0, recoveredCount = 0, crisisCount = 0;
        int worstCutPct = 0; String worstCutName = "";

        for (int s = 1; s < scenarioCount; s++) {   // skip 0 (random/non-crisis)
            crisisCount++;
            // Fresh inputs per run (readInputs reads the UI); override scenario.
            SimInputs inp = readInputs();
            inp.scenarioIndex = s;
            GkResults gk  = simulateGK(inp);

            // -- Derive the adjustment/recovery story from the GkRow series ----
            java.util.List<GkRow> rows = gk.rows;
            double initialRealSpend = 0;   // first drawing year, real
            int    initialIdx = -1;
            double worstRealSpend = Double.MAX_VALUE;
            int    worstIdx = -1;
            int    cutCount = 0;

            for (int i = 0; i < rows.size(); i++) {
                GkRow r = rows.get(i);
                if (!r.drawing) continue;
                double realSpend = (r.inflFactor > 0) ? r.wdActual / r.inflFactor : r.wdActual;
                if (initialIdx < 0) { initialRealSpend = realSpend; initialIdx = i; }
                if (realSpend < worstRealSpend) { worstRealSpend = realSpend; worstIdx = i; }
                if (r.ruleFlags != null && r.ruleFlags.contains("CPR")) cutCount++;
            }
            if (initialIdx < 0) { initialRealSpend = 0; worstRealSpend = 0; }

            double maxCutPct = (initialRealSpend > 0)
                    ? Math.max(0.0, (initialRealSpend - worstRealSpend) / initialRealSpend * 100.0) : 0;

            // Years to recover: first drawing year AFTER the trough whose real
            // spend returns to >= the initial real spend. -1 = never recovered.
            int yrsToRecover = -1;
            if (worstIdx >= 0 && worstRealSpend < initialRealSpend) {
                for (int i = worstIdx + 1; i < rows.size(); i++) {
                    GkRow r = rows.get(i);
                    if (!r.drawing) continue;
                    double realSpend = (r.inflFactor > 0) ? r.wdActual / r.inflFactor : r.wdActual;
                    if (realSpend >= initialRealSpend) {
                        yrsToRecover = rows.get(i).calYear - rows.get(worstIdx).calYear;
                        break;
                    }
                }
            } else if (worstRealSpend >= initialRealSpend) {
                yrsToRecover = 0; // never dipped below initial -- no recovery needed
            }

            boolean survived = gk.finalBalance > 0;
            if (survived) survivedCount++;
            boolean recovered = (yrsToRecover >= 0);
            if (recovered) recoveredCount++;
            if ((int) Math.round(maxCutPct) > worstCutPct) {
                worstCutPct = (int) Math.round(maxCutPct);
                worstCutName = HistoricalScenarios.SCENARIO_NAMES[s].split(" \\(")[0];
            }

            tblStressModel.addRow(new Object[]{
                    HistoricalScenarios.SCENARIO_NAMES[s],
                    // v6: the offset is a live input read by readInputs(), so it
                    // applies here even when the Sequence dropdown is on Random
                    // and the spinner looks greyed out. Show it, don't hide it.
                    (inp.seqOffset == 0 ? "yr 1" : "+" + inp.seqOffset + " yr"),
                    CURRENCY.format((long) initialRealSpend),
                    CURRENCY.format((long) worstRealSpend),
                    String.format("%.0f%%", maxCutPct),
                    String.valueOf(cutCount),
                    yrsToRecover < 0 ? "not recovered"
                            : (yrsToRecover == 0 ? "no cut" : yrsToRecover + " yr"
                            + (yrsToRecover == 1 ? "" : "s")),
                    CURRENCY.format((long) gk.finalBalance),
                    survived ? "Yes" : "No",
            });
        }

        // -- Income Lab style verdict (adjustments, not pass/fail) ------------
        String verdict;
        if (survivedCount == crisisCount && recoveredCount == crisisCount) {
            verdict = "Your plan adjusted through all " + crisisCount
                    + " historical crises and restored spending in each; the portfolio survived every scenario"
                    + (worstCutPct > 0 ? " (deepest temporary cut: " + worstCutPct + "% in the "
                    + worstCutName + ")." : ".");
        } else if (survivedCount == crisisCount) {
            verdict = "The portfolio survived all " + crisisCount + " crises. Spending recovered in "
                    + recoveredCount + " of " + crisisCount + "; in the rest it stayed reduced through the "
                    + "horizon -- a temporary-to-lasting adjustment, not a portfolio failure.";
        } else {
            verdict = "The portfolio survived " + survivedCount + " of " + crisisCount
                    + " crises. Where it did not, the plan would need larger or longer spending "
                    + "adjustments than the guardrails alone applied -- review spending or claiming choices.";
        }
        lblStressVerdict.setText("<html>" + verdict + "</html>");
        lblStressVerdict.setForeground(survivedCount == crisisCount
                ? new Color(30, 90, 50) : new Color(150, 90, 20));
        int stressOff = readInputs().seqOffset;   // v6
        lblStressStatus.setText("Done. Ran " + crisisCount
                + " historical crises through your Guyton-Klinger guardrail plan"
                + (stressOff > 0
                ? " -- each sequence STARTING " + stressOff + " YEARS IN"
                + " (from the 'Sequence starts N years in' spinner)."
                : ", each starting in year 1."));
    }

    // == Apply SS dates and run IL simulation ================================
    private void applyAndRun(int bobYear, int bobMonth, int joYear, int joMonth) {
        spManSSStartYear.setValue(bobYear);
        spManSSStartMonth.setValue(bobMonth);
        // v4: joYear==0 is the single-mode sentinel (no spouse claim); leave the
        // spouse SS spinners untouched (they are disabled in Single mode anyway).
        if (joYear > 0) {
            spWomanSSStartYear.setValue(joYear);
            spWomanSSStartMonth.setValue(joMonth);
        }
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

        // v9 Option 2: the optimizer now SCORES EACH CLAIM COMBINATION BY RUNNING
        // THE REAL PRO ENGINE (simulatePro) read-only. Two stages:
        //   1) coarse scan on a YEAR (or half-year) grid at REDUCED fidelity --
        //      fast, noisy ranking;
        //   2) RE-VERIFY the top N at the user's FULL Pro-tab fidelity so the
        //      reported numbers are exact.
        // Feasibility = stays green every year (>= green buffer), meets the go-go
        // and slow-go travel floors, and holds the PoS target. Among feasible
        // combos we rank by SURVIVOR FLOOR. If none is feasible, the chosen
        // fallback decides what to show. NONE of this touches the Pro engine's
        // own inputs or table -- it clones the current inputs per combination.
        final SimInputs baseInp = readInputs();
        final int manBY    = baseInp.manBirthYear,   manBM    = baseInp.manBirthMonth;
        final int womanBY  = baseInp.womanBirthYear, womanBM  = baseInp.womanBirthMonth;
        final int manPIA   = baseInp.manPIA,          womanPIA = baseInp.womanPIA;
        final boolean single = (cmbFilingStatus != null && cmbFilingStatus.getSelectedIndex() == 1);

        // Objective inputs (this tab only).
        final int goGoFloor   = iv(spOptGoGoFloor);
        final int slowGoFloor = iv(spOptSlowGoFloor);
        final int greenBuf    = iv(spOptGreenBuffer);
        final int termGrace   = iv(spOptTermGrace);   // v9: terminal-year grace window
        final int posTarget   = iv(spTargetPoS);
        final int gridStepMo  = (cmbOptGrid != null && cmbOptGrid.getSelectedIndex() == 1) ? 6 : 12;
        final int scanPaths   = iv(spOptScanPaths);
        final int scanFan     = iv(spOptScanFan);
        final int verifyTopN  = iv(spOptVerifyTopN);
        final int fullPaths   = iv(spMcSolvePaths);
        final int fullFan     = iv(spMcFanPaths);
        final int binIters    = iv(spBinaryIters);
        final int infeasMode  = (cmbOptInfeasible != null) ? cmbOptInfeasible.getSelectedIndex() : 0;
        // v9: ONE seed for the whole scan so every combination faces the identical
        // random future and the ranking is fair. Honor the "Re-randomize each run"
        // checkbox exactly as the Pro tab does (line ~5115): a fresh System.nanoTime()
        // when randomize is on, else the deterministic 0. Because randomize draws a
        // new future each press, the optimizer's numbers are a valid self-consistent
        // SAMPLE -- applying a winner and re-running with randomize ON gives another
        // sample that will not match exactly; run the Pro tab with randomize OFF to
        // reproduce an optimizer row precisely.
        runSeedOffset = (chkRandomize != null && chkRandomize.isSelected())
                ? System.nanoTime() : 0L;
        final long optSeed    = runSeedOffset;
        // v9: capture the display-dollar mode so the optimizer's floors and columns
        // follow the Real/Nominal toggle -- floors are compared, and headroom/min
        // surplus shown, in the SAME units the Pro table is currently showing.
        final boolean optReal = showRealDollars;

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override protected Void doInBackground() {
                java.time.LocalDate today = java.time.LocalDate.now();
                int sy = today.getYear(), sm = today.getMonthValue();

                // Coarse candidate months on the chosen grid step.
                java.util.List<int[]> bobMonths = buildSsRangeStep(manBY, manBM, sy, sm, gridStepMo);
                java.util.List<int[]> joMonths;
                if (single) { joMonths = new java.util.ArrayList<>(); joMonths.add(new int[]{0,0}); }
                else joMonths = buildSsRangeStep(womanBY, womanBM, sy, sm, gridStepMo);

                int total = bobMonths.size() * joMonths.size();
                publish(String.format("Stage 1: scanning %,d combinations at reduced fidelity...", total));

                java.util.List<SsOptResult> results = new java.util.ArrayList<>();
                int done = 0;
                long t0 = System.currentTimeMillis();
                for (int[] bob : bobMonths) {
                    for (int[] jo : joMonths) {
                        if (optCancelRequested) break;
                        SsOptResult r = scoreCombinationPro(
                                baseInp, bob[0], bob[1], jo[0], jo[1], single,
                                goGoFloor, slowGoFloor, greenBuf, termGrace, posTarget,
                                scanPaths, scanFan, binIters, optSeed, optReal, /*verified=*/false);
                        results.add(r);
                        done++;
                        if (done % 2 == 0 || done == total) {
                            long el = System.currentTimeMillis() - t0;
                            double per = el / (double) done;
                            long etaMs = (long)(per * (total - done));
                            publish(String.format("Stage 1: %,d / %,d  (%.0f%%)  ~%ds left",
                                    done, total, done*100.0/total, etaMs/1000));
                        }
                    }
                    if (optCancelRequested) break;
                }

                // Rank the coarse results by the objective, honoring feasibility.
                rankOptResults(results, infeasMode);

                // Stage 2: re-verify the top N at full fidelity.
                if (!optCancelRequested) {
                    int n = Math.min(verifyTopN, results.size());
                    for (int i = 0; i < n; i++) {
                        if (optCancelRequested) break;
                        publish(String.format("Stage 2: re-verifying %d / %d at full fidelity...", i+1, n));
                        SsOptResult r = results.get(i);
                        SsOptResult v = scoreCombinationPro(
                                baseInp, r.bobYear, r.bobMonth, r.joYear, r.joMonth, single,
                                goGoFloor, slowGoFloor, greenBuf, termGrace, posTarget,
                                fullPaths, fullFan, binIters, optSeed, optReal, /*verified=*/true);
                        results.set(i, v);
                    }
                    // Re-rank after verification (full-fidelity numbers may reorder the top).
                    rankOptResults(results, infeasMode);
                }

                final java.util.List<SsOptResult> finalResults = results;
                final int fMode = infeasMode;
                SwingUtilities.invokeLater(() ->
                        populateOptTable(finalResults, manBY, manBM, womanBY, womanBM, manPIA, womanPIA, fMode));
                return null;
            }
            @Override protected void process(java.util.List<String> msgs) {
                if (!msgs.isEmpty()) lblOptStatus.setText(msgs.get(msgs.size()-1));
            }
            @Override protected void done() {
                btnRunOpt.setEnabled(true);
                btnCancelOpt.setEnabled(false);
                if (optCancelRequested)
                    lblOptStatus.setText("Cancelled. Partial results shown.");
            }
        };
        worker.execute();
    }

    // v9: candidate claim months from (startY,startM) to age 70, stepping by
    // stepMo months (12 = yearly grid, 6 = half-year). Always includes the age-70
    // endpoint so the maximal-delay option is scored.
    private java.util.List<int[]> buildSsRangeStep(int birthYear, int birthMonth,
                                                   int startYear, int startMonth, int stepMo) {
        java.util.List<int[]> result = new java.util.ArrayList<>();
        int endY = birthYear + 70, endM = birthMonth;
        int y = startYear, m = startMonth;
        int endTot = endY * 12 + endM;
        while (y * 12 + m <= endTot) {
            result.add(new int[]{y, m});
            int nt = y * 12 + m + stepMo;
            y = (nt - 1) / 12; m = ((nt - 1) % 12) + 1;
        }
        // Ensure the exact age-70 endpoint is present.
        int[] last = result.get(result.size()-1);
        if (last[0]*12+last[1] < endTot) result.add(new int[]{endY, endM});
        return result;
    }

    // v9 Option 2: score ONE claim combination by running the real Pro engine.
    // Clones the base inputs, sets this combination's SS start dates, runs
    // simulatePro at the given fidelity, and reads back PoS + per-year surplus to
    // test feasibility against the travel/green floors. Read-only w.r.t. the Pro
    // engine and its table.
    private SsOptResult scoreCombinationPro(
            SimInputs base, int bobY, int bobM, int joY, int joM, boolean single,
            int goGoFloor, int slowGoFloor, int greenBuf, int termGrace, int posTarget,
            int solvePaths, int fanPaths, int binIters, long seed, boolean realDollars, boolean verified) {

        SsOptResult r = new SsOptResult();
        r.bobYear = bobY; r.bobMonth = bobM; r.joYear = joY; r.joMonth = joM;
        r.verified = verified;

        // Clone inputs and set this combination's claim dates.
        SimInputs inp = base.copy();
        inp.manSSStartYear = bobY; inp.manSSStartMonth = bobM;
        if (single) { inp.womanPIA = 0; }
        else { inp.womanSSStartYear = joY; inp.womanSSStartMonth = joM; }

        // Monthly benefits at these claim dates (for display + survivor floor).
        r.bobMonthly = calcSSMonthlyBenefit(inp.manPIA, inp.manBirthYear, inp.manBirthMonth, bobY, bobM);
        r.joMonthly  = single ? 0
                : calcSSMonthlyBenefit(inp.womanPIA, inp.womanBirthYear, inp.womanBirthMonth, joY, joM);
        r.combinedAnnual = (r.bobMonthly + r.joMonthly) * 12.0;
        r.survivorFloor  = Math.max(r.bobMonthly, r.joMonthly) * 12.0;

        ProResults pr;
        try {
            pr = simulatePro(inp, seed, solvePaths, fanPaths, binIters);
        } catch (Exception ex) {
            r.feasible = false; r.shortfall = Integer.MAX_VALUE; return r;
        }
        r.actualPoS = pr.actualPoS * 100.0;   // engine stores a 0..1 fraction; keep as percent here

        // Read per-year surplus in the SAME dollar mode the Pro table is showing.
        // The Pro table uses d = showRealDollars ? inflFactor : 1.0 (line ~6047);
        // we mirror that so the optimizer's floors and columns match what the user
        // sees and sets. In nominal mode d=1.0 (raw surplus); in real mode we
        // deflate by inflFactor. row.surplus is stored NOMINAL.
        double goGoMultVal   = base.goGoMultiplier;      // e.g. 1.5
        double slowGoMultVal = base.slowGoMultiplier;    // e.g. 1.3
        int minGo = Integer.MAX_VALUE, minSlow = Integer.MAX_VALUE;
        boolean sawGo = false, sawSlow = false;
        int minGreenTest = Integer.MAX_VALUE;   // drives feasibility
        int minGreenTestYear = 0;               // calendar year of that worst counted year
        int minRawSurplus = Integer.MAX_VALUE;  // shown in the Min surplus column
        r.violations.clear();
        r.gracedDips.clear();
        r.dollarsReal = realDollars;
        if (pr.medianRows != null) {
            int nRows = pr.medianRows.size();
            for (int i = 0; i < nRows; i++) {
                EnhRow row = pr.medianRows.get(i);
                double d = (realDollars && row.inflFactor > 0) ? row.inflFactor : 1.0;
                int surplusDisp = (int) Math.round(row.surplus / d);
                int balanceDisp = (int) Math.round(row.balance / d);
                int calY        = row.calYear;
                if (surplusDisp < minRawSurplus) minRawSurplus = surplusDisp;

                // Is this a graced terminal year whose dip the balance can cover?
                boolean inTerminalWindow = (i >= nRows - termGrace);
                int shortfallHere = greenBuf - surplusDisp;         // >0 means below buffer
                boolean covered = (shortfallHere <= 0) ||           // not below buffer, or
                        (inTerminalWindow && balanceDisp >= shortfallHere);  // covered terminal dip
                if (!covered) {
                    if (surplusDisp < minGreenTest) { minGreenTest = surplusDisp; minGreenTestYear = calY; }
                    // Record the green-buffer violation for the tooltip.
                    r.violations.add(new FloorMiss(calY, surplusDisp, balanceDisp, "green", greenBuf));
                } else if (shortfallHere > 0 && inTerminalWindow) {
                    // Below buffer but forgiven by terminal grace (balance covers it).
                    r.gracedDips.add(new FloorMiss(calY, surplusDisp, balanceDisp, "green", greenBuf));
                }

                if (approxEq(row.goGoMult, goGoMultVal)) {
                    sawGo = true; if (surplusDisp < minGo) minGo = surplusDisp;
                    if (surplusDisp < goGoFloor)
                        r.violations.add(new FloorMiss(calY, surplusDisp, balanceDisp, "go-go", goGoFloor));
                } else if (approxEq(row.goGoMult, slowGoMultVal)) {
                    sawSlow = true; if (surplusDisp < minSlow) minSlow = surplusDisp;
                    if (surplusDisp < slowGoFloor)
                        r.violations.add(new FloorMiss(calY, surplusDisp, balanceDisp, "slow-go", slowGoFloor));
                }
            }
        }
        // Min surplus SHOWN is the raw worst year (so the user still sees the dip).
        r.minSurplus       = (minRawSurplus == Integer.MAX_VALUE) ? 0 : minRawSurplus;
        r.minGoGoSurplus   = sawGo   ? minGo   : Integer.MAX_VALUE;   // no go-go years -> not binding
        r.minSlowGoSurplus = sawSlow ? minSlow : Integer.MAX_VALUE;   // no slow-go years -> not binding
        // The stay-green feasibility figure is the worst NON-EXEMPT year. If every
        // below-buffer year was a covered terminal dip, this stays at MAX_VALUE and
        // the green test passes.
        int greenTestMin = (minGreenTest == Integer.MAX_VALUE) ? Integer.MAX_VALUE : minGreenTest;
        // Feasibility-min shown in the color-coded column: the worst COUNTED year.
        // If nothing was counted below buffer, fall back to the raw min (a positive
        // number that the user can still read as "worst year").
        r.feasMin     = (greenTestMin != Integer.MAX_VALUE) ? greenTestMin : r.minSurplus;
        r.feasMinYear = (greenTestMin != Integer.MAX_VALUE) ? minGreenTestYear : 0;

        // Feasibility test + worst shortfall (how far the worst floor is missed).
        int shortfall = 0;
        boolean feas = true;
        if (greenTestMin != Integer.MAX_VALUE && greenTestMin < greenBuf)
        { feas = false; shortfall = Math.max(shortfall, greenBuf - greenTestMin); }
        if (sawGo && r.minGoGoSurplus < goGoFloor)     { feas = false; shortfall = Math.max(shortfall, goGoFloor - r.minGoGoSurplus); }
        if (sawSlow && r.minSlowGoSurplus < slowGoFloor){ feas = false; shortfall = Math.max(shortfall, slowGoFloor - r.minSlowGoSurplus); }
        if (r.actualPoS < posTarget)      { feas = false; shortfall = Math.max(shortfall, 1); }  // PoS miss flagged
        r.feasible = feas; r.shortfall = shortfall;
        return r;
    }

    private static boolean approxEq(double a, double b) { return Math.abs(a - b) < 1e-6; }

    // v9 Option 2: rank optimizer results. Feasible combinations always sort ahead
    // of infeasible ones and, among feasible, by SURVIVOR FLOOR (descending) --
    // the objective. Among infeasible, the fallback decides: frontier still sorts
    // by survivor floor (so you see the trade), 'closest' sorts by smallest
    // shortfall, 'relax' behaves like frontier (the table shows achieved headroom).
    private void rankOptResults(java.util.List<SsOptResult> results, int infeasMode) {
        results.sort((a, b) -> {
            if (a.feasible != b.feasible) return a.feasible ? -1 : 1;   // feasible first
            if (a.feasible) {
                int c = Double.compare(b.survivorFloor, a.survivorFloor);
                if (c == 0) c = Integer.compare(b.minSurplus, a.minSurplus);
                return c;
            }
            // both infeasible
            if (infeasMode == 1) {   // Show closest: least shortfall first
                int c = Integer.compare(a.shortfall, b.shortfall);
                if (c == 0) c = Double.compare(b.survivorFloor, a.survivorFloor);
                return c;
            }
            // frontier / relax: survivor floor, then least shortfall
            int c = Double.compare(b.survivorFloor, a.survivorFloor);
            if (c == 0) c = Integer.compare(a.shortfall, b.shortfall);
            return c;
        });
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

    /**
     * v4: Linear survival probability for the SS optimizer's mortality weighting.
     * Returns 1.0 at (and before) the withdrawal-start age, declines linearly to
     * 0.5 at the life-expectancy age (LE is the median age of death, so ~50%
     * survive to it), and continues down to 0.0 at LE + (LE - startAge),
     * symmetric about LE. Clamped to [0, 1]. If LE <= startAge (degenerate),
     * returns 1.0 through startAge and 0 after, avoiding a divide-by-zero.
     */
    private static double survivalRamp(int age, int startAge, int lifeExpectancy) {
        if (age <= startAge) return 1.0;
        int span = lifeExpectancy - startAge;
        if (span <= 0) return 0.0;               // degenerate: LE at/before start
        // slope: from 1.0 at startAge to 0.5 at LE -> lose 0.5 over 'span' years
        double surv = 1.0 - 0.5 * (age - startAge) / (double) span;
        if (surv < 0.0) return 0.0;
        if (surv > 1.0) return 1.0;
        return surv;
    }


    // v9: HTML tooltip for a Feasibility-min cell. Lists every year that failed a
    // floor (calendar year, surplus, balance, which floor + its level), notes any
    // terminal dips that terminal-grace exempted, and on a passing row states that
    // it passes. All dollars in the mode the scan was run in (real or nominal).
    private String feasibilityTooltip(SsOptResult r) {
        String unit = r.dollarsReal ? "today's $" : "future $";
        StringBuilder sb = new StringBuilder("<html>");
        if (r.feasible) {
            sb.append("<b>Feasible</b> &mdash; meets green buffer, go-go and slow-go floors, and the PoS target.<br>");
            sb.append("Worst counted year: <b>")
                    .append(r.feasMinYear > 0 ? String.valueOf(r.feasMinYear) : "n/a")
                    .append("</b> at ").append(CURRENCY.format((long) r.feasMin))
                    .append(" surplus (").append(unit).append(").");
        } else {
            sb.append("<b>Not feasible</b> &mdash; the following year(s) miss a floor (").append(unit).append("):<br>");
            if (r.violations.isEmpty() && r.actualPoS > 0) {
                sb.append("PoS ").append(String.format("%.1f%%", r.actualPoS))
                        .append(" is below the target.");
            } else {
                sb.append("<table cellpadding=2>");
                sb.append("<tr><td><b>Year</b></td><td><b>Floor</b></td><td><b>Surplus</b></td>")
                        .append("<td><b>Needs</b></td><td><b>Portfolio</b></td></tr>");
                for (FloorMiss m : r.violations) {
                    sb.append("<tr><td>").append(m.calYear).append("</td><td>")
                            .append(m.floor).append("</td><td>")
                            .append(CURRENCY.format((long) m.surplus)).append("</td><td>")
                            .append(CURRENCY.format((long) m.floorLevel)).append("</td><td>")
                            .append(CURRENCY.format((long) m.balance)).append("</td></tr>");
                }
                sb.append("</table>");
            }
        }
        if (!r.gracedDips.isEmpty()) {
            sb.append("<br><i>Terminal grace forgave a below-buffer dip (portfolio covers it) in: ");
            for (int i = 0; i < r.gracedDips.size(); i++) {
                FloorMiss m = r.gracedDips.get(i);
                if (i > 0) sb.append(", ");
                sb.append(m.calYear).append(" (").append(CURRENCY.format((long) m.surplus))
                        .append(", bal ").append(CURRENCY.format((long) m.balance)).append(")");
            }
            sb.append(".</i>");
        }
        sb.append("</html>");
        return sb.toString();
    }

    private void populateOptTable(java.util.List<SsOptResult> results,
                                  int manBY, int manBM, int womanBY, int womanBM,
                                  int manPIA, int womanPIA, int infeasMode) {
        // Cache for real/nominal toggle refresh
        lastOptResults  = results;
        lastOptManBY    = manBY;  lastOptManBM   = manBM;
        lastOptWomanBY  = womanBY; lastOptWomanBM = womanBM;
        lastOptManPIA   = manPIA;  lastOptWomanPIA = womanPIA;
        lastOptInfeasMode = infeasMode;

        tblOptModel.setRowCount(0);
        optRowDates.clear();
        optRowResults.clear();

        int feasibleCount = 0;
        for (SsOptResult r : results) if (r.feasible) feasibleCount++;

        int show = results.size();
        for (int rank = 0; rank < show; rank++) {
            SsOptResult r = results.get(rank);
            boolean joClaims = (r.joYear > 0);
            String goHead   = (r.minGoGoSurplus   == Integer.MAX_VALUE) ? "--" : CURRENCY.format((long) r.minGoGoSurplus);
            String slowHead = (r.minSlowGoSurplus == Integer.MAX_VALUE) ? "--" : CURRENCY.format((long) r.minSlowGoSurplus);
            tblOptModel.addRow(new Object[]{
                    rank + 1,
                    String.format("%02d/%d", r.bobMonth, r.bobYear),
                    joClaims ? String.format("%02d/%d", r.joMonth, r.joYear) : "--",
                    String.format("%.1f%%", r.actualPoS),
                    CURRENCY.format((long) r.feasMin),
                    CURRENCY.format((long) r.minSurplus),
                    goHead,
                    slowHead,
                    CURRENCY.format((long) r.survivorFloor),
                    CURRENCY.format((long) r.combinedAnnual),
                    r.verified ? "full" : "scan",
            });
            optRowDates.add(new int[]{r.bobYear, r.bobMonth, r.joYear, r.joMonth});
            optRowResults.add(r);
        }

        String head;
        if (feasibleCount > 0) {
            head = String.format(
                    "%,d of %,d combinations feasible (meet green + travel floors + PoS). "
                            + "Ranked by survivor floor. Click a row to apply and run.",
                    feasibleCount, show);
        } else {
            String modeName = (infeasMode == 1) ? "closest (least shortfall)"
                    : (infeasMode == 2) ? "relaxed floors" : "trade-off frontier";
            head = String.format(
                    "No combination meets all floors. Showing %s. "
                            + "Trade travel headroom against survivor floor; click a row to apply and run.",
                    modeName);
        }
        lblOptStatus.setText(head);
    }

    private static String ssAgeStr(int totalMonths) {
        int y = totalMonths / 12, m = totalMonths % 12;
        return m == 0 ? y + "y" : y + "y" + m + "m";
    }

    // Parallel list: maps optimizer table row index to SS date array [bobY,bobM,joY,joM]
    private final java.util.List<int[]> optRowDates = new java.util.ArrayList<>();
    // v9: row-ordered results backing the SS Optimizer table, so the renderer can
    // read feasibility for cell coloring and the header/cell tooltips can describe
    // the exact floor violations for each row.
    private final java.util.List<SsOptResult> optRowResults = new java.util.ArrayList<>();

    static class SsOptResult {
        int bobYear, bobMonth, joYear, joMonth;
        double bobMonthly, joMonthly, combinedAnnual;
        double totalIncomeYr1, portWdYr1, projFinalBal;
        double goGoTotalIncome;  // sum of guaranteed income across all go-go years
        // v6: the SURVIVOR INCOME FLOOR for this combination -- the annual
        // benefit the surviving spouse keeps after the first death, which is
        // the LARGER of the two claimed benefits. Delaying the higher earner
        // raises this floor permanently; the go-go objective cannot see that,
        // because a delayed benefit pays nothing during the go-go window.
        double survivorFloor;
        double inflAccYr1   = 1.0;   // inflation factor at withdrawal start year (default 1=nominal)
        double inflAccFinal = 1.0;   // inflation factor at end of horizon (default 1=nominal)
        // v9 Option 2: metrics read back from a real Pro-engine run of this
        // claim-date combination. All in today's real dollars where noted.
        double actualPoS      = 0;    // Pro engine PoS for this combination, as a PERCENT (0..100)
        int    minSurplus     = 0;    // worst-year surplus/gap across the horizon (real $)
        int    minGoGoSurplus = 0;    // worst surplus among go-go years (real $)
        int    minSlowGoSurplus = 0;  // worst surplus among slow-go years (real $)
        boolean feasible      = false;// meets green + travel floors + PoS target
        int    shortfall      = 0;    // if not feasible: worst floor miss (>=0 real $)
        boolean verified      = false;// true = scored at full fidelity (re-verify pass)
        // v9 display: the feasibility-relevant minimum -- the worst year the green
        // test actually counts, AFTER terminal-grace exemptions. This is the number
        // the color-coded "Feasibility min" column shows (green=pass, red=fail).
        int    feasMin        = 0;    // worst counted surplus (display $ at scan-time mode)
        int    feasMinYear    = 0;    // calendar year of that worst counted year
        boolean dollarsReal   = false;// which mode feasMin/violations were computed in
        // Per-year floor violations for the tooltip. Each entry: calendar year, the
        // surplus that year, the portfolio balance that year, and which floor it
        // missed. Plus terminal dips that were EXEMPTED by grace (not failures).
        java.util.List<FloorMiss> violations = new java.util.ArrayList<>();
        java.util.List<FloorMiss> gracedDips = new java.util.ArrayList<>();
    }

    // v9: one floor miss (or graced dip) recorded for the SS Optimizer tooltip.
    static class FloorMiss {
        int calYear;       // calendar year (matches the Pro PoS "Cal yr" column)
        int surplus;       // surplus/gap that year, in the scan-time dollar mode
        int balance;       // portfolio balance that year, same mode
        String floor;      // "green", "go-go", or "slow-go"
        int floorLevel;    // the floor threshold it failed
        FloorMiss(int y, int s, int b, String f, int lvl) {
            calYear = y; surplus = s; balance = b; floor = f; floorLevel = lvl;
        }
    }


    // =========================================================================
    //  SCENARIO SAVE / LOAD
    // =========================================================================
    // RECENT_FILE replaced by RECENT_FILE (see DIR_CALIB above)
    private static final int    MAX_RECENT = 5;
    private static final String SCENARIO_VERSION = "1";

    /**
     * Enable/disable (grey out) the tax-engine inputs based on the two toggles.
     * Computed-tax OFF: the whole tax engine is bypassed (legacy flat escalator),
     *   so grey out conversion mode, all conversion inputs, and IRMAA growth; and
     *   ENABLE the legacy Base tax + Tax inflation (they now drive the flat path).
     * Computed-tax ON: grey out Base tax + Tax inflation (unused); enable the
     *   engine controls; within them, Fill mode enables buffer/cap and greys the
     *   flat conversion, while Flat mode does the reverse.
     * Living expenses / Medical / Go-go are always enabled (tax-method-independent).
     */
    private void refreshTaxEngineEnabled() {
        if (chkComputedTax == null) return; // guard: called during construction
        boolean computed = chkComputedTax.isSelected();

        // Legacy flat-tax inputs: live ONLY when the computed engine is OFF.
        if (spBaseTax != null)      spBaseTax.setEnabled(!computed);
        if (spTaxInflation != null) spTaxInflation.setEnabled(!computed);

        // Engine controls: live ONLY when the computed engine is ON.
        if (tglConvMode != null)     tglConvMode.setEnabled(computed);
        if (cmbIrmaaMode != null)    cmbIrmaaMode.setEnabled(computed);
        if (cmbFilingStatus != null) cmbFilingStatus.setEnabled(computed);

        boolean fillMode = (tglConvMode != null) && tglConvMode.isSelected();
        // Flat conversion is live only in computed + flat mode.
        if (spConvFlat != null)   spConvFlat.setEnabled(computed && !fillMode);
        // Fill buffer + cap + the v7 ceiling dropdowns are live only in
        // computed + fill mode.
        if (spConvBuffer != null) spConvBuffer.setEnabled(computed && fillMode);
        if (spConvCap != null)    spConvCap.setEnabled(computed && fillMode);
        if (cmbFillIrmaaTier != null) cmbFillIrmaaTier.setEnabled(computed && fillMode);
        if (cmbFillBracket != null)   cmbFillBracket.setEnabled(computed && fillMode);
        // The Edit thresholds dialog is live whenever the computed engine is on
        // (it edits base tables the whole engine reads, not just fill mode).
        if (btnEditThresholds != null) btnEditThresholds.setEnabled(computed);

        // v5: state selector lives only when the computed engine is ON; the
        // custom-state sub-fields additionally follow the state selection.
        if (cmbState != null) cmbState.setEnabled(computed);
        refreshStateFieldsEnabled();
        refreshDeathFieldsEnabled();  // v6: death-event fields follow computed toggle

        // v4: spouse fields follow filing status (see method).
        refreshSpouseFieldsEnabled();
    }

    // v7: the tax-bracket dropdown offers 12/22/24/32; these map to the ordinary
    // bracket indices in brackets(fs) (index 0 = 10%, 1 = 12%, 2 = 22%, 3 = 24%,
    // 4 = 32%). The dropdown never offers 10% (never a sane fill target).
    private static final int[] BRACKET_DROPDOWN_TO_INDEX = { 1, 2, 3, 4 };

    /** v7: push the frame's editable base thresholds into the TaxEngine active
     *  tables. Called before every simulation (from the input-gather) so any
     *  edits made in the dialog are live. No-op in effect when unedited. */
    private void syncActiveThresholds() {
        TaxEngine.setActiveThresholds(
                editBracketTopMFJ, editBracketTopSingle,
                editIrmaaThreshMFJ, editIrmaaThreshSingle);
    }

    /** v7: modal editor for the base-year (2026) IRMAA cliffs and ordinary-
     *  bracket tops, MFJ and Single. Edits the frame's editable arrays in place
     *  on OK; the inflation projection scales them forward unchanged. Reset
     *  restores the 2026 constants. Values persist with the scenario. */
    private void showThresholdDialog() {
        // Labels for the five IRMAA upper boundaries and the editable bracket
        // tops (10/12/22/24/32/35 -> six finite tops; the 37% band has no top).
        String[] irmaaLabels = {
                "Cliff 1 (Standard \u2192 Tier 1)",
                "Cliff 2 (Tier 1 \u2192 Tier 2)",
                "Cliff 3 (Tier 2 \u2192 Tier 3)",
                "Cliff 4 (Tier 3 \u2192 Tier 4)",
                "Cliff 5 (Tier 4 \u2192 Tier 5)" };
        String[] brkLabels = {
                "Top of 10% bracket", "Top of 12% bracket", "Top of 22% bracket",
                "Top of 24% bracket", "Top of 32% bracket", "Top of 35% bracket" };

        // Working copies so Cancel discards.
        double[] wIrmaaMFJ    = editIrmaaThreshMFJ.clone();
        double[] wIrmaaSingle = editIrmaaThreshSingle.clone();
        double[] wBrkMFJ      = editBracketTopMFJ.clone();
        double[] wBrkSingle   = editBracketTopSingle.clone();

        // Build the spinner grids. Bracket-top arrays may include the +inf top
        // row; only edit the finite tops (all but the last).
        JSpinner[] spIrmaaMFJ    = new JSpinner[wIrmaaMFJ.length];
        JSpinner[] spIrmaaSingle = new JSpinner[wIrmaaSingle.length];
        int nBrk = Math.min(brkLabels.length, wBrkMFJ.length - 1); // exclude +inf top
        JSpinner[] spBrkMFJ    = new JSpinner[nBrk];
        JSpinner[] spBrkSingle = new JSpinner[nBrk];

        JPanel grid = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
        g.insets = new java.awt.Insets(2, 4, 2, 4);
        g.anchor = java.awt.GridBagConstraints.WEST;
        int row = 0;

        java.util.function.BiConsumer<String, int[]> header = (title, span) -> { };
        // -- IRMAA section header --
        g.gridx = 0; g.gridy = row; g.gridwidth = 3;
        grid.add(sectionLabel("IRMAA base cliffs (MAGI, 2026 $)"), g);
        g.gridwidth = 1; row++;
        g.gridx = 1; g.gridy = row; grid.add(new JLabel("MFJ"), g);
        g.gridx = 2;                grid.add(new JLabel("Single"), g);
        row++;
        for (int i = 0; i < wIrmaaMFJ.length; i++) {
            g.gridx = 0; g.gridy = row; grid.add(new JLabel(irmaaLabels[i]), g);
            spIrmaaMFJ[i]    = spinI((int) Math.round(wIrmaaMFJ[i]),    0, 5_000_000, 1_000, "#,###");
            spIrmaaSingle[i] = spinI((int) Math.round(wIrmaaSingle[i]), 0, 5_000_000, 1_000, "#,###");
            g.gridx = 1; grid.add(spIrmaaMFJ[i], g);
            g.gridx = 2; grid.add(spIrmaaSingle[i], g);
            row++;
        }
        // -- Bracket section header --
        g.gridx = 0; g.gridy = row; g.gridwidth = 3;
        grid.add(sectionLabel("Ordinary bracket tops (taxable income, 2026 $)"), g);
        g.gridwidth = 1; row++;
        g.gridx = 1; g.gridy = row; grid.add(new JLabel("MFJ"), g);
        g.gridx = 2;                grid.add(new JLabel("Single"), g);
        row++;
        for (int i = 0; i < nBrk; i++) {
            g.gridx = 0; g.gridy = row; grid.add(new JLabel(brkLabels[i]), g);
            spBrkMFJ[i]    = spinI((int) Math.round(wBrkMFJ[i]),    0, 10_000_000, 1_000, "#,###");
            spBrkSingle[i] = spinI((int) Math.round(wBrkSingle[i]), 0, 10_000_000, 1_000, "#,###");
            g.gridx = 1; grid.add(spBrkMFJ[i], g);
            g.gridx = 2; grid.add(spBrkSingle[i], g);
            row++;
        }

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setPreferredSize(new java.awt.Dimension(460, 420));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel content = new JPanel(new java.awt.BorderLayout(0, 6));
        JLabel note = new JLabel("<html><i>Base-year (2026) values. The IRMAA "
                + "threshold-growth setting projects these forward. Cliffs must "
                + "increase down each column; bracket tops likewise.</i></html>");
        content.add(note, java.awt.BorderLayout.NORTH);
        content.add(scroll, java.awt.BorderLayout.CENTER);

        // Reset-to-2026 button lives inside the option pane's button row via a
        // custom options array.
        Object[] options = { "OK", "Cancel", "Reset to 2026" };
        while (true) {
            int choice = JOptionPane.showOptionDialog(this, content,
                    "Edit base thresholds", JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null, options, options[0]);

            if (choice == 2) { // Reset to 2026 -- refill spinners, keep dialog open
                double[] rIrmaaMFJ    = TaxEngine.IRMAA_THRESH_MFJ_2026.clone();
                double[] rIrmaaSingle = TaxEngine.IRMAA_THRESH_SINGLE_2026.clone();
                double[] rBrkMFJ      = bracketTops(TaxEngine.BRACKETS_2026);
                double[] rBrkSingle   = bracketTops(TaxEngine.BRACKETS_SINGLE_2026);
                for (int i = 0; i < spIrmaaMFJ.length; i++) {
                    spIrmaaMFJ[i].setValue((int) Math.round(rIrmaaMFJ[i]));
                    spIrmaaSingle[i].setValue((int) Math.round(rIrmaaSingle[i]));
                }
                for (int i = 0; i < nBrk; i++) {
                    spBrkMFJ[i].setValue((int) Math.round(rBrkMFJ[i]));
                    spBrkSingle[i].setValue((int) Math.round(rBrkSingle[i]));
                }
                continue;
            }
            if (choice != 0) return; // Cancel / closed -> discard

            // OK: read spinners into working copies, validate monotonicity.
            for (int i = 0; i < spIrmaaMFJ.length; i++) {
                wIrmaaMFJ[i]    = iv(spIrmaaMFJ[i]);
                wIrmaaSingle[i] = iv(spIrmaaSingle[i]);
            }
            for (int i = 0; i < nBrk; i++) {
                wBrkMFJ[i]    = iv(spBrkMFJ[i]);
                wBrkSingle[i] = iv(spBrkSingle[i]);
            }
            String err = validateMonotonic("IRMAA MFJ cliffs", wIrmaaMFJ);
            if (err == null) err = validateMonotonic("IRMAA Single cliffs", wIrmaaSingle);
            if (err == null) err = validateMonotonic("Bracket tops (MFJ)", java.util.Arrays.copyOf(wBrkMFJ, nBrk));
            if (err == null) err = validateMonotonic("Bracket tops (Single)", java.util.Arrays.copyOf(wBrkSingle, nBrk));
            if (err != null) {
                JOptionPane.showMessageDialog(this, err, "Invalid thresholds",
                        JOptionPane.WARNING_MESSAGE);
                continue; // keep dialog open so the user can fix it
            }

            // Commit into the frame's editable arrays (bracket-top arrays keep
            // their trailing +inf slot untouched).
            editIrmaaThreshMFJ    = wIrmaaMFJ;
            editIrmaaThreshSingle = wIrmaaSingle;
            System.arraycopy(wBrkMFJ,    0, editBracketTopMFJ,    0, nBrk);
            System.arraycopy(wBrkSingle, 0, editBracketTopSingle, 0, nBrk);
            syncActiveThresholds();
            return;
        }
    }

    /** Small bold section label for the threshold dialog. */
    private static JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD));
        return l;
    }

    /** Returns null if values strictly increase, else a human error string. */
    private static String validateMonotonic(String name, double[] vals) {
        for (int i = 1; i < vals.length; i++) {
            if (vals[i] <= vals[i - 1]) {
                return name + ": each value must be larger than the one above it "
                        + "(row " + (i + 1) + " = " + (long) vals[i]
                        + " is not > row " + i + " = " + (long) vals[i - 1] + ").";
            }
        }
        return null;
    }

    /** v5: the state code ("AZ" / "CUSTOM") for the current dropdown index,
     *  resolved by matching display name in the registry's insertion order. */
    private String selectedStateCode() {
        if (cmbState == null) return "AZ";
        int idx = cmbState.getSelectedIndex();
        int k = 0;
        for (TaxEngine.StateTaxProfile p : TaxEngine.STATE_REGISTRY.values()) {
            if (k++ == idx) return p.code;
        }
        return "AZ";
    }

    /**
     * v5: The custom-state controls (rate, the two flags, and the exclusion cap)
     * are live ONLY when the computed engine is ON and the state selector is set
     * to Custom. The cap additionally requires the "exclude retirement income"
     * flag to be checked. Non-live fields are disabled (grayed and locked); their
     * values are preserved. Arizona needs none of these, so selecting Arizona
     * grays all custom fields. Mirrors the annuity/spouse gray-enable pattern.
     */
    private void refreshStateFieldsEnabled() {
        if (cmbState == null) return; // guard: called during construction
        boolean computed = (chkComputedTax != null) && chkComputedTax.isSelected();
        boolean custom   = computed && "CUSTOM".equals(selectedStateCode());
        if (spCustomStateRate != null)   spCustomStateRate.setEnabled(custom);
        if (chkCustomTaxSS != null)      chkCustomTaxSS.setEnabled(custom);
        if (chkCustomExclRetire != null) chkCustomExclRetire.setEnabled(custom);
        boolean capLive = custom && chkCustomExclRetire != null
                && chkCustomExclRetire.isSelected();
        if (spCustomExclCap != null)     spCustomExclCap.setEnabled(capLive);
    }

    // v6: grey the year-of-death and share fields when no death event is set,
    // and only when the computed tax engine is on (the death event is a tax /
    // RMD feature). The share field stays live even without a death event,
    // because the his/her split drives both-living RMD math too.
    // v6: warn when a historical sequence is selected while SS is still on the
    // fixed COLA -- the combination that makes a survivable stress run look fatal.
    // v9 Option 2: the objective is now FIXED (feasibility then survivor floor)
    // and its explanation is a static label, so this is a no-op kept only because
    // it is still called once at init. Left in place to avoid touching that path.
    private void refreshOptObjective() {
        // intentionally empty -- lblOptObjective is set once at construction.
    }

    private void refreshColaWarn() {
        if (lblColaWarn == null || cmbScenario == null || chkSSColaTracksInfl == null) return;
        boolean hist = cmbScenario.getSelectedIndex() > 0;
        boolean tracking = chkSSColaTracksInfl.isSelected();
        if (spColaShortfall != null) spColaShortfall.setEnabled(tracking);
        if (spSeqOffset != null) spSeqOffset.setEnabled(hist);
        lblColaWarn.setText(hist && !tracking
                ? "<html><i>Fixed COLA vs simulated inflation -- SS will be<br>"
                + "understated in high-inflation sequences.</i></html>"
                : " ");
    }

    private void refreshDeathFieldsEnabled() {
        if (cmbDeathWho == null) return; // guard: called during construction
        boolean computed = (chkComputedTax != null) && chkComputedTax.isSelected();
        boolean hasDeath = computed && cmbDeathWho.getSelectedIndex() != 0;
        cmbDeathWho.setEnabled(computed);
        if (spDeathYear != null)   spDeathYear.setEnabled(hasDeath);
        if (spHisRmdShare != null) spHisRmdShare.setEnabled(computed);
        if (spSurvivorSpendCut != null) spSurvivorSpendCut.setEnabled(hasDeath);
    }

    /** v5: a human-readable label for the state-tax line in the per-row tax
     *  tooltip, reflecting the selected profile's rules for the given year
     *  (e.g. "Arizona 2.5% (excludes Social Security)"). */
    private String stateTaxLabel(int simYear) {
        TaxEngine.StateTaxProfile p = TaxEngine.stateProfile(selectedStateCode());
        TaxEngine.StateTaxYear sty = p.forYear(simYear);
        StringBuilder sb = new StringBuilder(p.displayName);
        if (sty.brackets == null)
            sb.append(String.format(" %.2f%%", sty.flatRate * 100.0));
        else
            sb.append(" (progressive)");
        java.util.List<String> notes = new java.util.ArrayList<>();
        if (!sty.taxesSocialSecurity)      notes.add("excludes Social Security");
        if (sty.excludesRetirementIncome)  notes.add("excludes retirement income");
        if (!notes.isEmpty()) sb.append(" (").append(String.join(", ", notes)).append(")");
        return sb.toString();
    }

    /** v5: push the live custom-state UI values into the Custom profile so the
     *  next simulation reads the current rate and flags. Rate entered as a
     *  percent (e.g. 3.07) is stored as a fraction (0.0307). Safe to call any
     *  time; a no-op effect unless Custom is the active state at run time. */
    private void syncCustomStateProfile() {
        if (spCustomStateRate == null) return; // guard: called during construction
        double ratePct = dv(spCustomStateRate);
        boolean taxSS   = chkCustomTaxSS != null && chkCustomTaxSS.isSelected();
        boolean exclRet = chkCustomExclRetire != null && chkCustomExclRetire.isSelected();
        double cap      = (spCustomExclCap != null) ? iv(spCustomExclCap) : 0;
        TaxEngine.setCustom(ratePct / 100.0, taxSS, exclRet, cap);
    }

    /**
     * v4: In SINGLE filing status, every Spouse input is disabled (grayed and
     * locked against editing) and excluded from all calculations. The entered
     * VALUES are preserved and remain visible in gray, so switching back to
     * Married filing jointly restores the full joint plan with no re-entry.
     *
     * Two things happen together and must stay in sync:
     *   (1) here -- the widgets are disabled/grayed/locked; and
     *   (2) in the SimInputs builder -- the spouse values are passed as 0 to
     *       the simulation when status is SINGLE, so no downstream calculation
     *       (income sum, RMD, portfolio total, SS optimizer) uses them.
     * The toggle never mutates the stored widget values and never consolidates
     * accounts; consolidating the decedent's balances into the surviving
     * (primary/User) person's fields is a manual step and is one-directional.
     */
    private void refreshSpouseFieldsEnabled() {
        if (cmbFilingStatus == null) return; // guard: called during construction
        boolean single = (cmbFilingStatus.getSelectedIndex() == 1);
        boolean on = !single; // spouse fields live only when NOT single
        JSpinner[] spouseFields = {
                spWomanBirthYear, spWomanBirthMonth, spWomanPlanAge,
                spWomanPIA, spWomanSSStartYear, spWomanSSStartMonth,
                spWomanRoth401K, spWomanRothIRA, spWomanTradIRA, spWomanTrad401K
        };
        for (JSpinner s : spouseFields) {
            if (s != null) s.setEnabled(on);
        }
        // Account-total label reflects filing status (spouse excluded in Single).
        updateAccountTotal();
    }

    /**
     * v4: The "Use annuity" checkbox activates/deactivates the annuity. When
     * OFF (default), the three annuity spinners are disabled (grayed and locked)
     * and their values are preserved; the annuity is passed as $0 into both the
     * main simulation and the SS optimizer (see the SimInputs builder and the
     * optimizer snapshot). Turning it back ON restores the annuity with no
     * re-entry. The on/off state is persisted with the scenario.
     */
    private void refreshAnnuityFieldsEnabled() {
        if (chkUseAnnuity == null) return; // guard: called during construction
        boolean on = chkUseAnnuity.isSelected();
        if (spAnnuity != null)           spAnnuity.setEnabled(on);
        if (spAnnuityStartYear != null)  spAnnuityStartYear.setEnabled(on);
        if (spAnnuityStartMonth != null) spAnnuityStartMonth.setEnabled(on);
    }

    private void saveScenario(javax.swing.JTextField tfDesc) {
        String desc = tfDesc.getText().trim();
        if (desc.isEmpty()) desc = "scenario";
        String date = java.time.LocalDate.now().toString();
        String safeName = desc.replaceAll("[^a-zA-Z0-9_-]", "_");
        String defaultName = date + "_" + safeName + ".ilscen";
        JFileChooser fc = new JFileChooser(DIR_SCENARIOS);
        fc.setDialogTitle("Save Scenario");
        fc.setSelectedFile(new java.io.File(DIR_SCENARIOS, defaultName));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Income PoS files (*.ilscen)", "ilscen"));
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
        // v9: bridge is now a 3-way mode. Write the new key. Also write the legacy
        // ss.bridgeDelayed boolean so an older build can still open the file
        // sensibly: SIM_START and FIRST_CLAIM both map to "true" (bridge on),
        // NONE maps to "false".
        int bridgeModeSel = (cmbBridgeMode != null)
                ? cmbBridgeMode.getSelectedIndex() : BRIDGE_SIM_START;
        props.setProperty("ss.bridgeMode", String.valueOf(bridgeModeSel));
        props.setProperty("ss.bridgeDelayed", String.valueOf(bridgeModeSel != BRIDGE_NONE));
        props.setProperty("ss.haircutPct",          String.valueOf(dv(spSSHaircutPct)));
        props.setProperty("ss.haircutStartYear",    String.valueOf(iv(spSSHaircutYear)));
        props.setProperty("man.ssStartYear",        String.valueOf(iv(spManSSStartYear)));
        props.setProperty("man.ssStartMonth",       String.valueOf(iv(spManSSStartMonth)));
        props.setProperty("woman.ssStartYear",      String.valueOf(iv(spWomanSSStartYear)));
        props.setProperty("woman.ssStartMonth",     String.valueOf(iv(spWomanSSStartMonth)));
        props.setProperty("annuity.amount",         String.valueOf(iv(spAnnuity)));
        props.setProperty("annuity.enabled",        String.valueOf(chkUseAnnuity.isSelected()));
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
        props.setProperty("mm.prior",               String.valueOf(iv(spMmPrior)));
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
        props.setProperty("tax.computed",           String.valueOf(chkComputedTax.isSelected()));
        props.setProperty("tax.convFillMode",       String.valueOf(tglConvMode.isSelected()));
        props.setProperty("tax.convFlat",           String.valueOf(iv(spConvFlat)));
        props.setProperty("tax.convBuffer",         String.valueOf(iv(spConvBuffer)));
        props.setProperty("tax.convCap",            String.valueOf(iv(spConvCap)));
        props.setProperty("tax.irmaaThreshMode",    String.valueOf(cmbIrmaaMode.getSelectedIndex()));
        // v7: fill-target ceiling selections + editable base thresholds.
        props.setProperty("tax.fillIrmaaTierIdx",   String.valueOf(cmbFillIrmaaTier.getSelectedIndex()));
        props.setProperty("tax.fillBracketDropdown", String.valueOf(cmbFillBracket.getSelectedIndex()));
        props.setProperty("tax.editIrmaaMFJ",       csv(editIrmaaThreshMFJ));
        props.setProperty("tax.editIrmaaSingle",    csv(editIrmaaThreshSingle));
        props.setProperty("tax.editBracketMFJ",     csv(editBracketTopMFJ));
        props.setProperty("tax.editBracketSingle",  csv(editBracketTopSingle));
        props.setProperty("tax.filingStatus",       String.valueOf(cmbFilingStatus.getSelectedIndex()));
        // v6: death event + Traditional share (saved with the scenario).
        props.setProperty("tax.deathWho",           String.valueOf(
                cmbDeathWho != null ? cmbDeathWho.getSelectedIndex() : 0));
        props.setProperty("tax.deathYear",          String.valueOf(iv(spDeathYear)));
        props.setProperty("tax.hisRmdShare",        String.valueOf(dv(spHisRmdShare)));
        props.setProperty("tax.survivorSpendCut",   String.valueOf(dv(spSurvivorSpendCut)));
        props.setProperty("ss.colaTracksInflation", String.valueOf(
                chkSSColaTracksInfl != null && chkSSColaTracksInfl.isSelected()));
        props.setProperty("ss.colaShortfall",      String.valueOf(dv(spColaShortfall)));
        props.setProperty("stress.seqOffset",      String.valueOf(iv(spSeqOffset)));
        props.setProperty("spending.slowGo",       String.valueOf(dv(spSlowGo)));
        props.setProperty("spending.slowGoDuration", String.valueOf(iv(spSlowGoDuration)));
        // v6: annual baseline (only written when one has been captured)
        props.setProperty("base.set",        String.valueOf(baselineSet));
        props.setProperty("base.actualWd",   String.valueOf(baselineActualWd));
        props.setProperty("base.balance",    String.valueOf(baselineBalance));
        props.setProperty("base.horizon",    String.valueOf(baselineHorizon));
        props.setProperty("base.goGoMult",   String.valueOf(baselineGoGoMult));
        props.setProperty("base.date",       baselineDate == null ? "" : baselineDate);
        props.setProperty("base.minChgPct",  String.valueOf(dv(spMinChangePct)));
        // v5 state tax
        props.setProperty("tax.stateCode",          selectedStateCode());
        props.setProperty("tax.customStateRate",    String.valueOf(dv(spCustomStateRate)));
        props.setProperty("tax.customTaxSS",        String.valueOf(chkCustomTaxSS.isSelected()));
        props.setProperty("tax.customExclRetire",   String.valueOf(chkCustomExclRetire.isSelected()));
        props.setProperty("tax.customExclCap",      String.valueOf(iv(spCustomExclCap)));
        props.setProperty("mc.solvePaths",          String.valueOf(iv(spMcSolvePaths)));
        props.setProperty("mc.binIters",            String.valueOf(iv(spBinaryIters)));
        props.setProperty("mc.fanPaths",            String.valueOf(iv(spMcFanPaths)));
        props.setProperty("opt.scenario",           String.valueOf(
                cmbScenario != null ? cmbScenario.getSelectedIndex() : 0));
        // v9: per-scenario Pro PoS column view (model indices, CSV). Absent keys on
        // load mean "show all" (pre-v9 behavior), so old files are unaffected.
        props.setProperty("view.proHiddenCols",    csvOfInts(proHiddenCols));
        props.setProperty("view.proProtectedCols", csvOfInts(proProtectedCols));
        // v9: SS Optimizer objective inputs (this tab only). Persisted per-scenario
        // so a saved plan reopens with the same feasibility criteria. Grid/fidelity
        // are scan-tuning, not planning intent, so they stay session-only.
        if (spOptGoGoFloor  != null) props.setProperty("opt.goGoFloor",   String.valueOf(iv(spOptGoGoFloor)));
        if (spOptSlowGoFloor!= null) props.setProperty("opt.slowGoFloor", String.valueOf(iv(spOptSlowGoFloor)));
        if (spOptGreenBuffer!= null) props.setProperty("opt.greenBuffer", String.valueOf(iv(spOptGreenBuffer)));
        if (spOptTermGrace  != null) props.setProperty("opt.termGrace",   String.valueOf(iv(spOptTermGrace)));
        if (cmbOptInfeasible!= null) props.setProperty("opt.infeasMode",  String.valueOf(cmbOptInfeasible.getSelectedIndex()));
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            props.store(fos, "IncomePoS_OptSocSec_v2 scenario -- " + desc);
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
        JFileChooser fc = new JFileChooser(DIR_SCENARIOS);
        fc.setDialogTitle("Load Scenario");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Income PoS files (*.ilscen)", "ilscen"));
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
        // v9: prefer the new 3-way key. If it is absent (an older file), fall back
        // to the legacy boolean: a file that had the v6 bridge ON reproduces the
        // v6 behavior (FIRST_CLAIM), and a file that had it OFF loads as NONE.
        // Only genuinely new files default to SIM_START.
        if (cmbBridgeMode != null) {
            String modeStr = props.getProperty("ss.bridgeMode");
            int sel;
            if (modeStr != null) {
                try { sel = Integer.parseInt(modeStr.trim()); }
                catch (NumberFormatException ex) { sel = BRIDGE_SIM_START; }
                if (sel < 0 || sel > BRIDGE_NONE) sel = BRIDGE_SIM_START;
            } else {
                boolean legacyOn = Boolean.parseBoolean(
                        props.getProperty("ss.bridgeDelayed", "false"));
                sel = legacyOn ? BRIDGE_FIRST_CLAIM : BRIDGE_NONE;
            }
            cmbBridgeMode.setSelectedIndex(sel);
        }
        if (props.getProperty("ss.haircutPct") != null)
            setSpinnerD(spSSHaircutPct, props, "ss.haircutPct", warnings);
        if (props.getProperty("ss.haircutStartYear") != null)
            setSpinnerI(spSSHaircutYear, props, "ss.haircutStartYear", warnings);
        setSpinnerI(spManSSStartYear,     props, "man.ssStartYear",        warnings);
        setSpinnerI(spManSSStartMonth,    props, "man.ssStartMonth",       warnings);
        setSpinnerI(spWomanSSStartYear,   props, "woman.ssStartYear",      warnings);
        setSpinnerI(spWomanSSStartMonth,  props, "woman.ssStartMonth",     warnings);
        setSpinnerI(spAnnuity,            props, "annuity.amount",         warnings);
        // v4: annuity on/off. Absent in pre-feature scenarios -> default off.
        if (chkUseAnnuity != null) {
            String ae = props.getProperty("annuity.enabled");
            chkUseAnnuity.setSelected(ae != null && ae.trim().equalsIgnoreCase("true"));
        }
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
        setSpinnerI(spMmPrior,            props, "mm.prior",                warnings);
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
        if (props.getProperty("tax.computed") != null)
            chkComputedTax.setSelected(Boolean.parseBoolean(props.getProperty("tax.computed")));
        if (props.getProperty("tax.convFillMode") != null) {
            boolean fill = Boolean.parseBoolean(props.getProperty("tax.convFillMode"));
            tglConvMode.setSelected(fill);
            tglConvMode.setText(fill ? "Fill to MAGI target" : "Flat $ amount");
        }
        setSpinnerI(spConvFlat,           props, "tax.convFlat",           warnings);
        setSpinnerI(spConvBuffer,         props, "tax.convBuffer",         warnings);
        setSpinnerI(spConvCap,            props, "tax.convCap",            warnings);
        if (props.getProperty("tax.irmaaThreshMode") != null) {
            try {
                int m = Integer.parseInt(props.getProperty("tax.irmaaThreshMode").trim());
                if (m >= 0 && m < cmbIrmaaMode.getItemCount()) cmbIrmaaMode.setSelectedIndex(m);
            } catch (Exception ignore) { }
        }
        // v7: fill-target ceiling selections. Absent in pre-v7 scenarios ->
        // defaults (IRMAA Standard, top of 22%) preserve prior behavior.
        if (props.getProperty("tax.fillIrmaaTierIdx") != null) {
            try {
                int t = Integer.parseInt(props.getProperty("tax.fillIrmaaTierIdx").trim());
                if (t >= 0 && t < cmbFillIrmaaTier.getItemCount()) cmbFillIrmaaTier.setSelectedIndex(t);
            } catch (Exception ignore) { }
        }
        if (props.getProperty("tax.fillBracketDropdown") != null) {
            try {
                int b = Integer.parseInt(props.getProperty("tax.fillBracketDropdown").trim());
                if (b >= 0 && b < cmbFillBracket.getItemCount()) cmbFillBracket.setSelectedIndex(b);
            } catch (Exception ignore) { }
        }
        // v7: editable base thresholds. Absent -> keep the 2026 defaults already
        // held on the frame. Length-checked so a malformed/old file can't corrupt
        // the arrays; a mismatch is ignored and the 2026 default stands.
        editIrmaaThreshMFJ    = parseCsvOr(props.getProperty("tax.editIrmaaMFJ"),      editIrmaaThreshMFJ);
        editIrmaaThreshSingle = parseCsvOr(props.getProperty("tax.editIrmaaSingle"),   editIrmaaThreshSingle);
        editBracketTopMFJ     = parseCsvOr(props.getProperty("tax.editBracketMFJ"),    editBracketTopMFJ);
        editBracketTopSingle  = parseCsvOr(props.getProperty("tax.editBracketSingle"), editBracketTopSingle);
        syncActiveThresholds(); // make loaded thresholds live immediately
        // v4: filing status. Absent in older (v3) scenarios -> default MFJ.
        if (props.getProperty("tax.filingStatus") != null && cmbFilingStatus != null) {
            try {
                int fsIdx = Integer.parseInt(props.getProperty("tax.filingStatus").trim());
                if (fsIdx >= 0 && fsIdx < cmbFilingStatus.getItemCount())
                    cmbFilingStatus.setSelectedIndex(fsIdx);
            } catch (Exception ignore) { }
        } else if (cmbFilingStatus != null) {
            cmbFilingStatus.setSelectedIndex(0);
        }
        // v6: death event + Traditional share. Absent in pre-v6 scenarios ->
        // defaults (Neither / 2045 / 50%), which reproduce prior behavior.
        if (cmbDeathWho != null) {
            String dw = props.getProperty("tax.deathWho");
            int dwi = 0;
            if (dw != null) { try { dwi = Integer.parseInt(dw.trim()); } catch (Exception ignore) {} }
            if (dwi < 0 || dwi >= cmbDeathWho.getItemCount()) dwi = 0;
            cmbDeathWho.setSelectedIndex(dwi);
        }
        if (props.getProperty("tax.deathYear") != null)
            setSpinnerI(spDeathYear,      props, "tax.deathYear",          warnings);
        if (props.getProperty("tax.hisRmdShare") != null)
            setSpinnerD(spHisRmdShare,    props, "tax.hisRmdShare",        warnings);
        if (props.getProperty("tax.survivorSpendCut") != null)
            setSpinnerD(spSurvivorSpendCut, props, "tax.survivorSpendCut", warnings);
        if (chkSSColaTracksInfl != null)
            chkSSColaTracksInfl.setSelected(Boolean.parseBoolean(
                    props.getProperty("ss.colaTracksInflation", "false")));
        if (props.getProperty("ss.colaShortfall") != null)
            setSpinnerD(spColaShortfall, props, "ss.colaShortfall", warnings);
        if (props.getProperty("stress.seqOffset") != null)
            setSpinnerI(spSeqOffset, props, "stress.seqOffset", warnings);
        if (props.getProperty("spending.slowGo") != null)
            setSpinnerD(spSlowGo, props, "spending.slowGo", warnings);
        if (props.getProperty("spending.slowGoDuration") != null)
            setSpinnerI(spSlowGoDuration, props, "spending.slowGoDuration", warnings);
        // v6: annual baseline
        try {
            baselineSet      = Boolean.parseBoolean(props.getProperty("base.set", "false"));
            baselineActualWd = Integer.parseInt(props.getProperty("base.actualWd", "0").trim());
            baselineBalance  = Integer.parseInt(props.getProperty("base.balance", "0").trim());
            baselineHorizon  = Integer.parseInt(props.getProperty("base.horizon", "0").trim());
            baselineGoGoMult = Double.parseDouble(props.getProperty("base.goGoMult", "1.0").trim());
            baselineDate     = props.getProperty("base.date", "");
        } catch (Exception ignore) { baselineSet = false; }
        if (props.getProperty("base.minChgPct") != null)
            setSpinnerD(spMinChangePct, props, "base.minChgPct", warnings);
        refreshBaselineLine();
        // v5: state tax. Absent in older scenarios -> default Arizona (prior
        // behavior). Custom fields load regardless; they are inert unless the
        // state is Custom, and syncCustomStateProfile() rebuilds the profile.
        setSpinnerD(spCustomStateRate,    props, "tax.customStateRate",    warnings);
        setSpinnerI(spCustomExclCap,      props, "tax.customExclCap",      warnings);
        if (chkCustomTaxSS != null && props.getProperty("tax.customTaxSS") != null)
            chkCustomTaxSS.setSelected(Boolean.parseBoolean(props.getProperty("tax.customTaxSS")));
        if (chkCustomExclRetire != null && props.getProperty("tax.customExclRetire") != null)
            chkCustomExclRetire.setSelected(Boolean.parseBoolean(props.getProperty("tax.customExclRetire")));
        if (cmbState != null) {
            String sc = props.getProperty("tax.stateCode", "AZ");
            int idx = 0, k = 0;
            for (TaxEngine.StateTaxProfile p : TaxEngine.STATE_REGISTRY.values()) {
                if (p.code.equals(sc)) { idx = k; break; }
                k++;
            }
            cmbState.setSelectedIndex(idx);
        }
        syncCustomStateProfile();  // rebuild Custom profile from loaded values
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
        // v9: restore the per-scenario Pro PoS column view. Missing keys -> show
        // all (old files). Protected always wins over hidden. User Age + Cal yr
        // default protected if the file predates the protected key.
        if (props.getProperty("view.proHiddenCols") != null
                || props.getProperty("view.proProtectedCols") != null) {
            proProtectedCols.clear();
            proProtectedCols.addAll(intsOfCsv(props.getProperty("view.proProtectedCols", "0,1")));
            proHiddenCols.clear();
            proHiddenCols.addAll(intsOfCsv(props.getProperty("view.proHiddenCols", "")));
            proHiddenCols.remove(PRO_RATE_DRIFT_COL);      // never user-managed
            proHiddenCols.removeAll(proProtectedCols);     // protected can't be hidden
            applyProColumnVisibility();
        }
        // v9: restore SS Optimizer objective inputs (missing keys keep defaults).
        setSpinnerIfPresent(spOptGoGoFloor,   props, "opt.goGoFloor");
        setSpinnerIfPresent(spOptSlowGoFloor, props, "opt.slowGoFloor");
        setSpinnerIfPresent(spOptGreenBuffer, props, "opt.greenBuffer");
        setSpinnerIfPresent(spOptTermGrace,   props, "opt.termGrace");
        if (cmbOptInfeasible != null && props.getProperty("opt.infeasMode") != null) {
            try {
                int idx = Integer.parseInt(props.getProperty("opt.infeasMode").trim());
                if (idx >= 0 && idx < cmbOptInfeasible.getItemCount()) cmbOptInfeasible.setSelectedIndex(idx);
            } catch (NumberFormatException ignore) { }
        }
        refreshTaxEngineEnabled();  // greying may change if loaded toggles differ
        refreshStateFieldsEnabled();  // v5: apply loaded state selection greying
        refreshDeathFieldsEnabled();  // v6: apply loaded death-event greying
        refreshColaWarn();            // v6: apply loaded COLA guard note
        refreshAnnuityFieldsEnabled();  // v4: apply loaded annuity on/off state
        addRecentFile(file.getAbsolutePath());
        if (cmbRecent != null) loadRecentFiles(cmbRecent);
        String warnMsg = warnings.isEmpty() ? "" : "  [Warnings: " + String.join(", ", warnings) + "]";
        if (progressBar != null)
            progressBar.setString("Loaded: " + file.getName() + " -- " + desc + warnMsg);
    }

    // v9: small CSV <-> Set<Integer> helpers for the per-scenario column view.
    private static String csvOfInts(java.util.Set<Integer> s) {
        StringBuilder sb = new StringBuilder();
        for (Integer i : s) { if (sb.length() > 0) sb.append(','); sb.append(i); }
        return sb.toString();
    }
    private static java.util.List<Integer> intsOfCsv(String csv) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) {
            p = p.trim();
            if (p.isEmpty()) continue;
            try { out.add(Integer.valueOf(p)); } catch (NumberFormatException ignore) { }
        }
        return out;
    }

    // v9: set a spinner from a property only if the key is present and parses;
    // otherwise leave the spinner at its current (default) value. Null-safe.
    private void setSpinnerIfPresent(JSpinner sp, java.util.Properties props, String key) {
        if (sp == null) return;
        String v = props.getProperty(key);
        if (v == null) return;
        try { sp.setValue(Integer.parseInt(v.trim())); } catch (NumberFormatException ignore) { }
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

    // v7: encode a double[] as a comma-separated integer list for scenario save.
    private static String csv(double[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            // +inf (open-ended top bracket) survives a round trip as a sentinel.
            sb.append(a[i] == Double.MAX_VALUE ? "MAX" : String.valueOf((long) a[i]));
        }
        return sb.toString();
    }
    // Decode a csv list; on any problem OR a length mismatch vs the fallback,
    // returns the fallback unchanged (so a corrupt/old file can't break arrays).
    private static double[] parseCsvOr(String s, double[] fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            String[] parts = s.split(",");
            if (parts.length != fallback.length) return fallback;
            double[] out = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                out[i] = p.equals("MAX") ? Double.MAX_VALUE : Double.parseDouble(p);
            }
            return out;
        } catch (Exception e) {
            return fallback;
        }
    }

    private void loadRecentFiles(JComboBox<String> cmb) {
        cmb.removeAllItems();
        cmb.addItem("-- recent files --");
        java.io.File prefs = new java.io.File(RECENT_FILE);
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
        java.io.File prefs = new java.io.File(RECENT_FILE);
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

    // v7: load the collapsible-section layout preference. Called ONCE in the
    // constructor BEFORE any card() is built, so each card honors its saved
    // state. Missing file or missing key -> default expanded (handled by the
    // getOrDefault in card()). Any parse issue silently leaves the map empty,
    // which also defaults everything expanded.
    private void loadPanelState() {
        java.io.File f = new java.io.File(PANELSTATE_FILE);
        if (!f.exists()) return;
        java.util.Properties p = new java.util.Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            p.load(in);
        } catch (Exception ignored) { return; }
        for (String name : p.stringPropertyNames()) {
            sectionExpanded.put(name, Boolean.parseBoolean(p.getProperty(name)));
        }
    }

    // v7: persist the section layout. Called on every arrow toggle so the state
    // survives an unclean exit. Best-effort: a write failure is ignored (the map
    // still governs the live session).
    private void savePanelState() {
        java.util.Properties p = new java.util.Properties();
        for (java.util.Map.Entry<String,Boolean> e : sectionExpanded.entrySet())
            p.setProperty(e.getKey(), String.valueOf(e.getValue()));
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(PANELSTATE_FILE)) {
            p.store(out, "IncomeLab input-pane section layout (app preference)");
        } catch (Exception ignored) {}
    }


    // =========================================================================
    //  CALIBRATION -- stores and retrieves run times for countdown estimates
    // =========================================================================

    /** Format milliseconds as "Xs" or "Xm Ys" -- used in progress display. */
    private static String formatSecs(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        return (s / 60) + "m " + (s % 60) + "s";
    }

    /**
     * Load average elapsed time for runs matching these parameters.
     * Returns 0 if no matching entries (calibration not yet available).
     */
    private long loadCalibrationEstimate(int solvePaths, int binIters, int fanPaths, int horizon) {
        java.io.File f = new java.io.File(CALIB_FILE);
        if (!f.exists()) return 0;
        java.util.List<Long> matches = new java.util.ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length < 5) continue;
                try {
                    int sp = Integer.parseInt(parts[0].trim());
                    int bi = Integer.parseInt(parts[1].trim());
                    int fp = Integer.parseInt(parts[2].trim());
                    int hz = Integer.parseInt(parts[3].trim());
                    long ms = Long.parseLong(parts[4].trim());
                    if (sp == solvePaths && bi == binIters && fp == fanPaths && hz == horizon)
                        matches.add(ms);
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        if (matches.size() < CALIB_MIN_FOR_ESTIMATE) return 0;
        // Rolling average of last 5 matching entries
        int start = Math.max(0, matches.size() - 5);
        long sum = 0;
        for (int i = start; i < matches.size(); i++) sum += matches.get(i);
        return sum / (matches.size() - start);
    }

    /**
     * Save a calibration entry.  Keeps at most CALIB_MAX entries total
     * (oldest dropped first across all parameter sets).
     */
    private void saveCalibrationEntry(int solvePaths, int binIters,
                                      int fanPaths, int horizon, long elapsedMs) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.io.File f = new java.io.File(CALIB_FILE);
        if (f.exists()) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null)
                    if (!line.isBlank()) lines.add(line);
            } catch (Exception ignored) {}
        }
        // New entry at the end
        lines.add(solvePaths + "\t" + binIters + "\t" + fanPaths + "\t" + horizon + "\t" + elapsedMs);
        // Trim to max
        if (lines.size() > CALIB_MAX)
            lines = lines.subList(lines.size() - CALIB_MAX, lines.size());
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(f))) {
            for (String l : lines) pw.println(l);
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
        // v9: hard-disallow a death that falls inside a spouse's SIM_START bridge
        // window. Under SIM_START each spouse is bridged from the withdrawal-start
        // date to their OWN claim date; a spouse dying inside that window is a
        // combination we do not model (the bridge would replace a benefit the
        // decedent will never claim). Block the run and explain.
        if (cmbBridgeMode != null
                && cmbBridgeMode.getSelectedIndex() == BRIDGE_SIM_START
                && cmbDeathWho != null && cmbDeathWho.getSelectedIndex() != 0) {
            int deathYear = iv(spDeathYear);
            int wsY = iv(spWithdrawStartYear);
            boolean manDies = (cmbDeathWho.getSelectedIndex() == 1);   // 1=User, 2=Spouse
            int claimY = manDies ? iv(spManSSStartYear) : iv(spWomanSSStartYear);
            // Bridge window for the decedent: [withdrawStart, own claim year).
            // A death in that half-open span is disallowed. (If the decedent
            // claims at or before withdrawStart there is no window, so no block.)
            if (claimY > wsY && deathYear >= wsY && deathYear < claimY) {
                String who = manDies ? "User" : "Spouse";
                javax.swing.JOptionPane.showMessageDialog(
                        this,
                        "<html><b>Cannot run: death falls inside an SS bridge window.</b><br><br>"
                                + who + " is set to die in " + deathYear + ", but under the<br>"
                                + "<b>\"From simulation start (both)\"</b> bridge mode that spouse is<br>"
                                + "being bridged from the withdrawal-start year (" + wsY + ") until<br>"
                                + "their own claim year (" + claimY + "). Bridging a benefit for a<br>"
                                + "spouse who dies before ever claiming it is not a combination<br>"
                                + "this model supports.<br><br>"
                                + "Resolve it by any one of:<br>"
                                + "&nbsp;&nbsp;- moving the year of death to " + claimY + " or later, or<br>"
                                + "&nbsp;&nbsp;- setting that spouse's SS start year to " + deathYear + " or earlier, or<br>"
                                + "&nbsp;&nbsp;- switching SS bridge mode to \"From first claim\" or \"No bridge\".</html>",
                        "SS bridge / death-event conflict (v9)",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
                return;   // do not start the simulation
            }
        }

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

        // Scheduler reads simCount every 250ms and updates UI -- avoids EDT flooding
        // entirely (same pattern as SS Optimizer tab status counter).
        java.util.concurrent.ScheduledExecutorService progressScheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "progress-scheduler");
                    t.setDaemon(true);
                    return t;
                });
        final long startMs = System.currentTimeMillis();
        // Load calibration estimate for this parameter set
        final long estimatedMs = loadCalibrationEstimate(solvePaths, binIters, fanPaths, horizon);
        progressScheduler.scheduleAtFixedRate(() -> {
            long elapsed = System.currentTimeMillis() - startMs;
            // Progress bar tracks elapsed time vs estimated duration (not sim count)
            int timePct = estimatedMs > 0
                    ? (int) Math.min(99, elapsed * 100 / estimatedMs)
                    : 0;  // stays at 0 until calibrated; jumps to 100 on done()
            String elapsedStr = formatSecs(elapsed);
            String countdownStr;
            if (estimatedMs > 0) {
                long remaining = Math.max(0, estimatedMs - elapsed);
                countdownStr = "~" + formatSecs(remaining) + " remaining";
            } else {
                countdownStr = "estimating...";
            }
            String msg = String.format(
                    "Running ~%,dM simulations  |  %s  |  %s elapsed",
                    grandTotalM, countdownStr, elapsedStr);
            SwingUtilities.invokeLater(() -> {
                progressBar.setValue(timePct);
                progressBar.setString(msg);
            });
        }, 250, 250, java.util.concurrent.TimeUnit.MILLISECONDS);

        SwingWorker<ProResults, Void> worker = new SwingWorker<>() {
            @Override protected ProResults doInBackground() {
                // No publish() calls -- scheduler handles all UI updates
                simProgressCallback = null;  // disable legacy callback
                return simulatePro(readInputs(), seed, solvePaths, fanPaths, binIters);
            }
            @Override protected void done() {
                progressScheduler.shutdownNow();
                try {
                    lastResults = get();
                    updateUI(lastResults);
                    long elapsed = System.currentTimeMillis() - startMs;
                    String elapsedStr = elapsed < 60000
                            ? (elapsed / 1000) + "s"
                            : (elapsed / 60000) + "m " + ((elapsed % 60000) / 1000) + "s";
                    progressBar.setValue(100);
                    progressBar.setString(String.format(
                            "Complete -- ~%,dM simulations . %,d fan paths . %,d solve paths . %d iters . %s",
                            grandTotalM, fanPaths, solvePaths, binIters, elapsedStr));
                    // Save calibration entry for future countdown estimates
                    saveCalibrationEntry(solvePaths, binIters, fanPaths, horizon, elapsed);
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
        i.bridgeMode         = (cmbBridgeMode != null)
                ? cmbBridgeMode.getSelectedIndex() : BRIDGE_SIM_START;              // v9
        i.bridgeDelayedSS    = (i.bridgeMode != BRIDGE_NONE);                        // v9: derived
        i.ssHaircutPct       = (spSSHaircutPct != null) ? dv(spSSHaircutPct) / 100.0 : 0.0;   // v6
        i.ssHaircutStartYear = (spSSHaircutYear != null) ? iv(spSSHaircutYear) : 0;           // v6
        i.manPIA             = iv(spManPIA);
        i.womanPIA           = iv(spWomanPIA);
        i.manSSMonthly       = calcSSMonthlyBenefit(i.manPIA, i.manBirthYear, i.manBirthMonth,
                i.manSSStartYear, i.manSSStartMonth);
        i.womanSSMonthly     = calcSSMonthlyBenefit(i.womanPIA, i.womanBirthYear, i.womanBirthMonth,
                i.womanSSStartYear, i.womanSSStartMonth);
        i.manSSAmount        = (int) Math.round(i.manSSMonthly   * 12);
        i.womanSSAmount      = (int) Math.round(i.womanSSMonthly * 12);
        // v4: annuity is included only when "Use annuity" is checked (default off).
        i.annuity            = (chkUseAnnuity != null && chkUseAnnuity.isSelected())
                ? iv(spAnnuity) : 0;
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
        i.mmPrior        = iv(spMmPrior);
        i.manPlanAge     = iv(spManPlanAge);
        i.womanPlanAge   = iv(spWomanPlanAge);

        // v4: SINGLE filing status -> exclude the spouse entirely from the
        // simulation. The widget values are preserved (and shown grayed in the
        // UI); here we pass ZERO into the model so no downstream calculation --
        // income sum, RMD, portfolio total, SS optimizer -- uses the spouse.
        // For a survivor run the decedent's balances are consolidated by hand
        // into the primary/User fields, so zeroing the spouse accounts here is
        // correct (their money now lives in the User account fields).
        // NOTE: read the combo directly -- i.filingStatus is assigned further
        // below, so we cannot rely on it here.
        boolean singleFiler = (cmbFilingStatus != null
                && cmbFilingStatus.getSelectedIndex() == 1);
        if (singleFiler) {
            i.womanBirthYear = 0; i.womanBirthMonth = 0;
            i.womanSSStartYear = 0; i.womanSSStartMonth = 0;
            i.womanPIA = 0;
            i.womanSSMonthly = 0; i.womanSSAmount = 0;
            i.womanRoth401K = 0; i.womanRothIRA = 0;
            i.womanTradIRA = 0;  i.womanTrad401K = 0;
            i.womanPlanAge = 0;  // horizon then follows the primary/User plan age
        }

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
        i.computedTax        = chkComputedTax.isSelected();
        i.convFillMode       = tglConvMode.isSelected();
        i.convFlat           = iv(spConvFlat);
        i.convBuffer         = iv(spConvBuffer);
        i.convCap            = iv(spConvCap);
        i.irmaaThreshMode    = cmbIrmaaMode.getSelectedIndex();
        i.fillIrmaaTierIdx   = cmbFillIrmaaTier.getSelectedIndex();
        // map the tax-bracket dropdown (12/22/24/32) to brackets(fs) indices
        // (1/2/3/4 -- index 0 is the 10% bracket, never a fill target).
        i.fillBracketIdx     = BRACKET_DROPDOWN_TO_INDEX[
                Math.max(0, Math.min(cmbFillBracket.getSelectedIndex(),
                        BRACKET_DROPDOWN_TO_INDEX.length - 1))];
        // v7: push the (possibly edited) base thresholds into the engine's active
        // tables so this run reads them. Harmless when unedited (they equal 2026).
        syncActiveThresholds();
        // v5: resolve the selected state and, if Custom, push the live UI values
        // into the Custom profile so the simulation reads the current rate/flags.
        i.stateCode = selectedStateCode();
        if ("CUSTOM".equals(i.stateCode)) syncCustomStateProfile();
        i.filingStatus       = (cmbFilingStatus != null
                && cmbFilingStatus.getSelectedIndex() == 1)
                ? TaxEngine.FilingStatus.SINGLE
                : TaxEngine.FilingStatus.MFJ;
        // v6: mid-projection death event + RMD share split.
        i.deathWho   = (cmbDeathWho != null) ? cmbDeathWho.getSelectedIndex() : 0; // 0/1/2
        i.deathYear  = (spDeathYear != null) ? iv(spDeathYear) : 0;
        i.hisRmdShare = (spHisRmdShare != null) ? dv(spHisRmdShare) / 100.0 : 0.5;
        i.herRmdShare = 1.0 - i.hisRmdShare;   // shares are complementary
        i.survivorSpendCut = (spSurvivorSpendCut != null)
                ? dv(spSurvivorSpendCut) / 100.0 : 0.20;   // v6
        i.ssColaTracksInflation = (chkSSColaTracksInfl != null)
                && chkSSColaTracksInfl.isSelected();   // v6
        i.ssColaShortfall = (spColaShortfall != null)
                ? dv(spColaShortfall) / 100.0 : 0.002;   // v6
        i.seqOffset = (spSeqOffset != null) ? iv(spSeqOffset) : 0;   // v6
        i.goGoMultiplier     = dv(spGoGo);
        i.slowGoMultiplier   = (spSlowGo != null) ? dv(spSlowGo) : 1.0;         // v6
        i.slowGoDuration     = (spSlowGoDuration != null) ? iv(spSlowGoDuration) : 0;  // v6
        i.goGoDuration       = iv(spGoGoDuration);
        i.proPosUpperGuardrail = dv(spProPosUpperGuardrail) / 100.0;
        i.proPosLowerGuardrail = dv(spProPosLowerGuardrail) / 100.0;
        i.gkUpperGuardrail     = dv(spGkUpperGuardrail)     / 100.0;
        i.gkLowerGuardrail     = dv(spGkLowerGuardrail)     / 100.0;
        i.gkPreRate          = dv(spGkPreRate)       / 100.0;
        i.scenarioIndex      = cmbScenario != null ? cmbScenario.getSelectedIndex() : 0;
        i.manAge             = computeAge(i.manBirthYear,   i.manBirthMonth);
        i.womanAge           = computeAge(i.womanBirthYear, i.womanBirthMonth);
        i.currentAge         = i.manAge;
        return i;
    }

    // ========================================================================
    //  ENHANCED PRO SIMULATION ENGINE
    //  1. True stochastic median: runs fan paths first, reads 50th-pct balance
    //  2. Annual re-solve at the fan-path and display levels: each year calls
    //     solveWithdrawalPro on the current (or 50th-pct) balance for the
    //     remaining horizon. Inner trial paths apply an inflation-indexed
    //     schedule (cumulative chained per-year draws) with no further
    //     re-optimization.
    //  3. Guyton-Klinger guardrails (Option C) provide a separate
    //     dynamic-spending overlay.
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

        // v6: two combined buckets tracked as tagged sub-components of the same
        // total portfolio b (tradBal + rothBal <= b; the remainder is taxable /
        // other). tradBal seeds from ALL Traditional accounts (his+her IRA+401k);
        // rothBal from ALL Roth accounts. Spending is drawn Traditional-FIRST so
        // the RMD base bleeds down; Roth conversions move tradBal -> rothBal (net
        // of conversion tax, which leaves the asset base); RMDs are computed off
        // tradBal via the his/her share split on each owner's age. This replaces
        // the v5 four-bucket RMD-only tracking (which never moved with spending
        // or conversions and so never fed back into future RMDs).
        double[][] fpTrad = new double[fanPaths][inp.horizon + 1];
        double[][] fpRoth = new double[fanPaths][inp.horizon + 1];
        double[][] fpConv = new double[fanPaths][inp.horizon];   // v6: per-path gross conversion
        // v6: one bridge schedule shared by the fan paths, the solver and the
        // display, so the extra drawdown is identical in all three.
        double[] ssBridge = buildSsBridgeSchedule(inp);
        double[][] fpMM   = new double[fanPaths][inp.horizon + 1];  // v6: Money Market (taxable)

        for (int p = 0; p < fanPaths; p++) {
            SeededRng rng = new SeededRng(p * 17 + 11 + seed);
            double b  = inp.portfolio;
            double trad = (double) inp.manTradIRA + inp.manTrad401K
                    + inp.womanTradIRA + inp.womanTrad401K;
            double roth = (double) inp.manRothIRA + inp.manRoth401K
                    + inp.womanRothIRA + inp.womanRoth401K;
            // v6 fix: taxable/other is whatever the total portfolio exceeds the
            // qualified accounts. The invariant trad + roth + taxable == b then
            // holds every year by construction -- no rescale, so the buckets
            // reconcile with each other and with the conversion.
            double taxable = Math.max(0, b - trad - roth);
            // v8: separate never-drawn money-market reserve. Seeds at 0 and only
            // ever grows by retained RMD overage (see the draw block below). It
            // is part of the portfolio total b and of Money Mkt (total), but the
            // spending waterfall never touches it.
            double mmReserve = 0;
            // If the qualified inputs exceed the stated portfolio, scale them to
            // fit b so the invariant still holds at t0.
            if (trad + roth > b && (trad + roth) > 0) {
                double sc = b / (trad + roth);
                trad *= sc; roth *= sc; taxable = 0;
            }

            res.fanBalances[p][0]    = b;
            res.fanInflFactors[p][0] = 1.0;
            fpTrad[p][0] = trad; fpRoth[p][0] = roth; fpMM[p][0] = taxable;

            // v6: this path's inflation factor at each SS start year, captured as
            // the loop passes it, so SS can ride the path's own inflation when
            // ssColaTracksInflation is on. Stays 0 (harmless) when the option is off.
            double pManStartInfl = 0, pWomanStartInfl = 0;

            for (int y = 0; y < inp.horizon; y++) {
                int calYear  = inp.baseYear + y;
                int manAge   = calYear - inp.manBirthYear;
                int womanAge = calYear - inp.womanBirthYear;
                boolean drawing = calYear >= inp.withdrawStartYear;
                boolean survivor = isSurvivorYear(inp, calYear);

                double[] ri1 = getReturnAndInflation(inp, y, rng);
                double ret   = ri1[0];
                double infl  = ri1[1];
                res.fanInflFactors[p][y + 1] = res.fanInflFactors[p][y] * (1 + infl);
                if (calYear == inp.manSSStartYear)   pManStartInfl   = res.fanInflFactors[p][y];
                if (calYear == inp.womanSSStartYear) pWomanStartInfl = res.fanInflFactors[p][y];

                int goGoRem = Math.max(0, inp.goGoDuration - Math.max(0, y - startY));
                int wd = 0;
                if (drawing && b > 0) {
                    wd = solveWithdrawalPro((int) b, calYear, inp.horizon - y,
                            inp, p * 1000L + y * 37 + seed,
                            Math.max(20, solvePaths / 8), Math.min(binIters, 10), goGoRem,
                            java.util.Arrays.copyOfRange(ssBridge, y, ssBridge.length));
                }
                double mult   = spendMultFor(inp, y, startY);   // v6
                int wdActual  = drawing ? (int)(wd * mult) : 0;
                res.fanWithdrawals[p][y] = wdActual;

                // --- growth on every component; b is DERIVED as their sum, so
                //     the invariant trad + roth + taxable == b holds exactly. ---
                trad    = trad    * (1 + ret);
                roth    = roth    * (1 + ret);
                taxable = taxable * (1 + ret);

                // --- combined RMD (share-split, survivor-aware) off tradBal ---
                double rmd = combinedRmd(inp, trad, calYear, manAge, womanAge);

                // --- Roth conversion (fill or flat): gross moves trad -> roth;
                //     the conversion TAX leaves the asset base, paid from taxable
                //     first then from the converted Roth if taxable is short. ---
                if (drawing && inp.computedTax) {
                    double convGross = fanConversion(inp, y, calYear, manAge,
                            womanAge, trad, roth, survivor, wdActual,
                            res.fanInflFactors[p][y], pManStartInfl, pWomanStartInfl);
                    convGross = Math.min(convGross, Math.max(0, trad));
                    if (convGross > 0) {
                        fpConv[p][y] = convGross;      // v6: record for display consistency
                        double convNet = fanConversionNet(inp, y, calYear, manAge,
                                womanAge, trad, roth, convGross, survivor, wdActual,
                                res.fanInflFactors[p][y], pManStartInfl, pWomanStartInfl);
                        double convTax = Math.max(0, convGross - Math.max(0, convNet));
                        trad -= convGross;              // gross leaves Traditional
                        roth += convGross;              // gross lands in Roth...
                        // ...then the tax is paid: from taxable first, remainder
                        // from the just-converted Roth. Tax leaves the base.
                        double fromTaxable = Math.min(convTax, taxable);
                        taxable -= fromTaxable;
                        roth    -= (convTax - fromTaxable);
                        roth     = Math.max(0, roth);
                    }
                }

                // --- spending: Traditional FIRST (RMD floors the Traditional
                //     draw), then taxable, then Roth last. ---
                // v6: the SS bridge is spent on top of the normal draw, so the
                // portfolio carries the full cost of delaying.
                double wdWithBridge = wdActual + ssBridge[y];
                double tradDraw = Math.max(rmd, Math.min(wdWithBridge, Math.max(0, trad)));
                trad = Math.max(0, trad - tradDraw);
                // v8: retained RMD overage. The DISPLAYED Money Mkt (total) now
                // comes from an exact-tie-out accumulator at display time
                // (mmPrior + cumulative overage), so this in-sim mmReserve no
                // longer feeds the column or the balance. It is kept here as the
                // hook for the FUTURE rule Bob described: allow the sim to tap
                // this reserve ONLY as a last resort once Traditional AND Roth are
                // both depleted. Until that rule is wired, it simply accumulates
                // and is never drawn.
                mmReserve += Math.max(0, tradDraw - wdWithBridge);
                double need = Math.max(0, wdWithBridge - tradDraw);
                double taxDraw = Math.min(need, Math.max(0, taxable));
                taxable = Math.max(0, taxable - taxDraw);
                need -= taxDraw;
                double rothDraw = Math.min(need, Math.max(0, roth));
                roth = Math.max(0, roth - rothDraw);

                // --- b is the sum of the DRAWABLE components: the invariant,
                //     not an independent quantity. v8 Option A: the never-drawn
                //     mmReserve is EXCLUDED from b, so the displayed "Portfolio
                //     balance" column is exactly the money the sim draws on
                //     (trad + roth + taxable). The reserve is shown separately in
                //     "Money Mkt (total)"; ACTUAL total = Portfolio + Money Mkt.
                //     Since mmReserve is never drawn, excluding it here changes no
                //     withdrawal decision -- it only cleans up the display split.
                b = trad + roth + taxable;

                res.fanBalances[p][y + 1] = b;
                fpTrad[p][y + 1] = trad; fpRoth[p][y + 1] = roth;
                // v8 Option A: fpMM tracks only the spendable taxable bucket, and
                // it is a fraction of the DRAWABLE balance b (which excludes the
                // reserve). The Money Mkt (total) column is no longer taken from
                // fpMM at all -- it is the exact-tie-out reserve accumulated at
                // display time (mmPrior + cumulative overage). fpMM is retained
                // only for the Trad/Roth/taxable fraction bookkeeping.
                fpMM[p][y + 1] = taxable;
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
                inp, 999L + seed, solvePaths, binIters, inp.goGoDuration, ssBridge);
        res.yr1Withdrawal = yr1Wd;

        // == Step 4: Median-path display ===================================
        // v6 FINAL: the table shows the TRUE per-year 50th-percentile balance
        // (smooth, statistically meaningful) and derives the Traditional / Roth
        // columns as the per-year MEDIAN FRACTIONS of the portfolio applied to
        // that median balance.
        //
        // History of this display, so it is not re-broken:
        //  * Original: each column took an INDEPENDENT per-year median, so
        //    Trad Bal and Roth Bal came from different paths and never
        //    reconciled with each other, the balance, or the conversion.
        //  * Interim fix: showed ONE representative path (nearest median FINAL
        //    balance). That reconciled, but a single path is a random draw --
        //    its trajectory swung +/-12% a year and drifted up to 40% away from
        //    the median mid-horizon. Because the withdrawal is re-solved each
        //    year on the displayed balance, an unlucky path silently shrank the
        //    reported sustainable draw (and therefore go-go spending). A single
        //    path is honest about sequence risk but useless as a planning number.
        //  * Now: median balance (smooth) + median bucket FRACTIONS. The buckets
        //    sum to at most the displayed balance by construction, move smoothly,
        //    and give RMDs a stable Traditional base. They represent a TYPICAL
        //    split rather than one literal scenario -- the right trade-off for a
        //    median table.
        // v6: median inflation factor for EVERY year, so Social Security can be
        // grown on the simulated inflation path when ssColaTracksInflation is on.
        double[] medInfl = new double[inp.horizon + 1];
        for (int yy = 0; yy <= inp.horizon; yy++) {
            double[] ia = new double[fanPaths];
            for (int p = 0; p < fanPaths; p++) ia[p] = res.fanInflFactors[p][yy];
            Arrays.sort(ia);
            medInfl[yy] = ia[fanPaths / 2];
        }
        double medManStartInfl   = inflAt(medInfl, inp.baseYear, inp.manSSStartYear);
        double medWomanStartInfl = inflAt(medInfl, inp.baseYear, inp.womanSSStartYear);

        // v3: MAGI history for the IRMAA 2-year lookback (index by sim year).
        double[] magiHistory = new double[inp.horizon];
        // v8: exact-tie-out Money Mkt (total) reserve. This REPLACES the old
        // median-composite (medBal * mFrac) for the displayed Money Mkt column,
        // which wobbled and lagged because it recombined independent per-year
        // medians. The reserve starts at the user's prior-years carry-in
        // (mmPrior) and each year (a) GROWS at that year's median inflation rate
        // -- Bob's assumption is money-market return ~= inflation, i.e. ~0% real,
        // so the nominal reserve compounds at inflation to hold real value flat
        // -- then (b) adds that year's RMD overage. It is a strict running sum of
        // a deterministic quantity: no wobble, no lag. Held as a double for exact
        // compounding; displayed rounded. Informational only -- never drawn by
        // the sim (Option A: excluded from the Portfolio balance column, added on
        // top as ACTUAL total).
        double mmReserveRun = Math.max(0, inp.mmPrior);
        for (int y = 0; y < inp.horizon; y++) {
            int calYear  = inp.baseYear + y;
            int manAge   = calYear - inp.manBirthYear;
            int womanAge = calYear - inp.womanBirthYear;
            boolean drawing = calYear >= inp.withdrawStartYear;

            // True 50th-percentile balance at this year.
            double[] balArr = new double[fanPaths];
            for (int p = 0; p < fanPaths; p++) balArr[p] = res.fanBalances[p][y];
            Arrays.sort(balArr);
            int medBal = (int) balArr[fanPaths / 2];

            // Median Traditional / Roth SHARE of the portfolio, applied to the
            // median balance. Fractions (not dollars) are averaged across paths
            // so the split stays smooth even as individual paths diverge.
            double[] tfArr = new double[fanPaths], rfArr = new double[fanPaths];
            double[] mfArr = new double[fanPaths];
            for (int p = 0; p < fanPaths; p++) {
                double bp = res.fanBalances[p][y];
                if (bp > 1) {
                    tfArr[p] = fpTrad[p][y] / bp;
                    rfArr[p] = fpRoth[p][y] / bp;
                    mfArr[p] = fpMM[p][y]   / bp;
                }
            }
            Arrays.sort(tfArr); Arrays.sort(rfArr); Arrays.sort(mfArr);
            double tFrac = tfArr[fanPaths / 2], rFrac = rfArr[fanPaths / 2];
            double mFrac = mfArr[fanPaths / 2];
            // Independent medians could in principle sum above 1; scale to fit
            // so trad + roth + money market never exceeds the displayed balance.
            if (tFrac + rFrac + mFrac > 1.0) {
                double sc = 1.0 / (tFrac + rFrac + mFrac);
                tFrac *= sc; rFrac *= sc; mFrac *= sc;
            }
            double medTrad = medBal * tFrac;
            double medRoth = medBal * rFrac;
            double medMM   = medBal * mFrac;

            int goGoRem = Math.max(0, inp.goGoDuration - Math.max(0, y - startY));
            int wd = drawing && medBal > 0
                    ? solveWithdrawalPro(medBal, calYear, inp.horizon - y,
                    inp, 999L + y * 37 + seed, solvePaths, binIters, goGoRem,
                    java.util.Arrays.copyOfRange(ssBridge, y, ssBridge.length))
                    : 0;

            // Share-split, survivor-aware RMD. The User/Spouse split is for the
            // display columns; combRmd is the total that drives ordinary income.
            double hs = inp.hisRmdShare, ws = inp.herRmdShare;
            if (isSurvivorYear(inp, calYear)) {
                if (inp.deathWho == 1) { hs = 0; ws = 1; } else { hs = 1; ws = 0; }
            }
            double manRmd   = calcRmd(medTrad * hs, manAge);
            double womanRmd = calcRmd(medTrad * ws, womanAge);
            double combRmd  = manRmd + womanRmd;

            double goGoMult  = spendMultFor(inp, y, startY);   // v6
            double startPror = (drawing && calYear == inp.withdrawStartYear)
                    ? (13.0 - inp.withdrawStartMonth) / 12.0 : 1.0;
            int wdActual   = drawing ? (int)(wd * goGoMult * startPror) : 0;
            // v6: the SS bridge rides on top of the solved draw. It is added to
            // Actual wd and to Total income, so Surplus/gap lands where the
            // no-delay run would put it and the entire cost of delaying shows
            // up as a lower portfolio balance instead of reduced spending.
            int bridgeThisYear = drawing ? (int) ssBridge[y] : 0;
            wdActual += bridgeThisYear;
            int rmdOverage = drawing ? Math.max(0, (int) combRmd - wdActual) : 0;
            // v8: fold this year's overage into the exact-tie-out MM reserve.
            // (Runs every displayed year in order, so the reserve is the true
            // running sum mmPrior + cumulative overage.)
            // v8: (a) grow the reserve at THIS year's median inflation rate, then
            // (b) add this year's overage. Growth uses the ratio of cumulative
            // median inflation factors medInfl[y]/medInfl[y-1] (= 1 + this year's
            // inflation). Year 0 has no prior year, so no growth is applied before
            // the first overage. Bob's model: MM return ~= inflation (~0% real),
            // so the nominal reserve holds real value flat rather than eroding.
            if (y > 0 && medInfl[y - 1] > 0) {
                mmReserveRun *= medInfl[y] / medInfl[y - 1];
            }
            mmReserveRun += rmdOverage;

            // True 50th-percentile inflation factor and next-year balance,
            // consistent with the median balance above (all genuine medians).
            double[] inflArr = new double[fanPaths];
            for (int p = 0; p < fanPaths; p++) inflArr[p] = res.fanInflFactors[p][y];
            Arrays.sort(inflArr);
            double inflFactor = inflArr[fanPaths / 2];

            double[] nextBalArr = new double[fanPaths];
            for (int p = 0; p < fanPaths; p++) nextBalArr[p] = res.fanBalances[p][y + 1];
            Arrays.sort(nextBalArr);
            int nextMedBal = (int) nextBalArr[fanPaths / 2];

            // v6: next-year median inflation factor, needed to express the balance
            // CHANGE in real dollars. A nominal delta spans two price levels, so
            // deflating it by one year's factor is not a real change.
            double[] nextInflArr = new double[fanPaths];
            for (int p = 0; p < fanPaths; p++) nextInflArr[p] = res.fanInflFactors[p][y + 1];
            Arrays.sort(nextInflArr);
            double nextInflFactor = nextInflArr[fanPaths / 2];

            double ssInflNow  = inflAt(medInfl, inp.baseYear, calYear);   // v6
            double manSS      = manSSSurv(inp, y, ssInflNow, medManStartInfl, medWomanStartInfl);
            double womanSS    = womanSSSurv(inp, y, ssInflNow, medManStartInfl, medWomanStartInfl);
            double ann        = annuityThisYear(inp, y);
            double guaranteed = manSS + womanSS + ann;
            boolean survivor  = isSurvivorYear(inp, calYear);   // v6
            TaxEngine.FilingStatus fsYear = filingFor(inp, calYear);  // v6 per-year
            // Inflation indexing (v2):
            //   living  -- stochastic: scaled by the 50th-pct cumulative inflation
            //              factor so historical stress scenarios move it correctly.
            //   medical -- DELIBERATELY deterministic at inp.medInflation. This is
            //              an intentional conservative choice (the base medical
            //              figure already absorbs premium creep + dental); we do NOT
            //              want medical to deflate in a low-inflation path. Indexed
            //              from the withdrawal-start year, consistent with tax below.
            //   tax     -- deterministic from withdrawal-start year (taxThisYear).
            int yearsDrawing  = Math.max(0, calYear - inp.withdrawStartYear);
            double living     = drawing ? inp.livingExp * inflFactor : 0;
            double medical    = drawing ? inp.medical   * Math.pow(1 + inp.medInflation, yearsDrawing) : 0;
            // v6: survivor spending drop. Living falls by the user's chosen
            // percentage; medical is halved automatically because it is
            // mechanically per-person. Both begin the year AFTER the death.
            if (survivor) {
                living  *= (1.0 - inp.survivorSpendCut);
                medical *= SimInputs.SURVIVOR_MEDICAL_FACTOR;
            }

            // ---- v3 TAX ENGINE (Pro PoS median path) --------------------
            // Taxable ordinary income (non-SS): the forced RMD if any, else the
            // discretionary Traditional draw. Pre-75 the draw is 100% Traditional
            // (Roth/MM surplus is funnelled AFTER spend, not drawn for spend);
            // post-75 the RMD dominates and floors ordinary income. Annuity is
            // ordinary (held inside an IRA). See the Assumptions & Methods tab.
            double tax; int convThisYear = 0;
            int    convTaxThisYear = 0, convNetToRoth = 0;
            int    magiInt = 0, taxableSSInt = 0, ordTaxInt = 0, irmaaInt = 0,
                    fedInt = 0, stateInt = 0, convCeilInt = 0;
            String bracketStr = "--";
            boolean convByIrmaa = false;
            if (drawing && inp.computedTax) {
                double grossSS = manSS + womanSS;
                // v6 FIX: only the portion of the withdrawal that actually comes
                // OUT OF TRADITIONAL is ordinary income. The spending order is
                // Traditional -> Money Market -> Roth, so once Traditional is
                // exhausted the draw is Roth (tax-free) or Money Market (basis
                // already taxed) and must NOT be taxed as an IRA distribution.
                // Previously this used the FULL withdrawal, which taxed tax-free
                // Roth dollars as ordinary income and overstated late-life tax by
                // roughly $20K/yr once Traditional ran out. The RMD still floors
                // it: a forced distribution is ordinary income even if the cash
                // is not spent.
                double tradPortion = Math.min(wdActual, Math.max(0, medTrad));
                double tradDraw = Math.max(combRmd, tradPortion);
                double ordinaryBeforeConv = tradDraw + ann;

                // MAGI (before conversion) drives the fill; taxable SS depends on
                // the conversion is a SEPARATE Traditional distribution (see
                // below), so size it on pre-conversion MAGI, then compute the
                // living-expenses tax on spending income ONLY. The conversion's
                // own tax is computed separately and funded from the conversion.
                double taxSSpre = TaxEngine.taxableSocialSecurity(
                        grossSS, ordinaryBeforeConv, inflFactor, fsYear);
                double magiBeforeConv = taxSSpre + ordinaryBeforeConv;

                double conv;
                if (inp.convFillMode) {
                    double[] fill = TaxEngine.fillConversion(
                            magiBeforeConv, inp.convBuffer,
                            manAge >= 65, womanAge >= 65,
                            inp.convCap, inflFactor, fsYear,
                            inp.fillIrmaaTierIdx, inp.fillBracketIdx);
                    conv        = fill[0];
                    convCeilInt = (int) fill[1];
                    convByIrmaa = fill[2] == 1;
                } else {
                    conv = inp.convFlat;
                }
                // v6: the conversion can never exceed the Traditional balance
                // actually available on the displayed median split -- otherwise
                // the Roth Conv column would show a conversion the Trad Bal
                // column cannot fund.
                conv = Math.max(0, Math.min(conv, medTrad));
                convThisYear = (int) conv;

                // ---- Living-expenses tax (Tax column): spending income ONLY,
                //      conversion EXCLUDED. MAGI for IRMAA lookback INCLUDES the
                //      conversion (it is a real distribution) -- so we pass the
                //      2yr-prior MAGI (which already folded conversions) as the
                //      IRMAA driver, while the taxable-income computation here
                //      uses living income only.
                double magiTwoYrPrior = (y >= 2) ? magiHistory[y - 2]
                        : (magiBeforeConv + conv);
                // IRMAA thresholds index per the chosen mode, evaluated at the
                // lookback year (y-2) whose MAGI is being assessed.
                int lookbackY = Math.max(0, y - 2);
                double irmaaTF = TaxEngine.irmaaThreshFactor(
                        inp.irmaaThreshMode, inflFactor, lookbackY);
                TaxEngine.StateTaxProfile stProfile =
                        TaxEngine.stateProfile(inp.stateCode);
                TaxEngine.TaxResult tr = TaxEngine.compute(
                        grossSS, ordinaryBeforeConv, magiTwoYrPrior,
                        manAge >= 65, womanAge >= 65, inflFactor, irmaaTF,
                        fsYear, stProfile, calYear, tradDraw);

                tax          = tr.totalTax;                 // living-expenses tax
                taxableSSInt = (int) tr.taxableSS;
                ordTaxInt    = (int) tr.ordinaryOther;      // living ordinary only
                irmaaInt     = (int) tr.irmaaCost;
                fedInt       = (int) tr.fedTax;
                stateInt     = (int) tr.stateTax;
                bracketStr   = tr.topBracket;

                // ---- Conversion tax: stacked marginal on top of living taxable
                //      income, funded FROM the conversion. Net-to-Roth = conv -
                //      convTax. The conversion tax leaves the asset base.
                double[] ctax = TaxEngine.conversionTax(
                        tr.taxableIncome, conv, inflFactor, fsYear,
                        stProfile, calYear, tradDraw);
                convTaxThisYear = (int) ctax[0];
                convNetToRoth   = (int) (conv - ctax[0]);

                // MAGI reported/stored INCLUDES the conversion (IRMAA-relevant).
                double magiWithConv = tr.magi + conv;
                magiInt        = (int) magiWithConv;
                magiHistory[y] = magiWithConv;
            } else {
                // Legacy flat escalator (computed tax OFF, or pre-withdrawal).
                tax = taxThisYear(inp, y);
                if (drawing) {
                    double grossSS = manSS + womanSS;
                    magiHistory[y] = grossSS * 0.85 + Math.max(combRmd, wdActual) + ann;
                }
            }
            double totalSpend = drawing ? living + medical + tax : 0;
            double totalIncome= guaranteed + wdActual;
            double surplus    = totalIncome - totalSpend;
            double wdPct      = (drawing && medBal > 0) ? wdActual / (double) medBal * 100.0 : 0;

            String alert = "--";
            // GUARDRAIL (fixed in v2): compare the current-year WITHDRAWAL RATE
            // (actual draw / current balance) against the YEAR-1 withdrawal rate
            // (yr1Wd / portfolio). The prior version compared re-solved dollar
            // draws across a SHRINKING remaining horizon; because a shorter horizon
            // always supports a larger sustainable draw, that comparison drifted
            // upward over time and tripped spurious raise-alerts in late years even
            // on a dead-median path. A rate-vs-rate test is a point-in-time measure
            // and is immune to horizon shrinkage. Go-go years are normalized out by
            // dividing each side by its own go-go multiplier so the rate reflects
            // the underlying base draw, not the temporary go-go uplift.
            double rowBaseRate = 0, rowYr1Rate = 0, rowRateVsYr1 = 0;   // v6
            if (drawing && medBal > 0 && inp.portfolio > 0 && goGoMult > 0) {
                double curRate = (wdActual / goGoMult) / (double) medBal;   // base-draw rate now
                double yr1Rate = yr1Wd / (double) inp.portfolio;            // base-draw rate at start
                if (yr1Rate > 0) {
                    double vsYr1 = (curRate - yr1Rate) / yr1Rate;
                    rowBaseRate = curRate; rowYr1Rate = yr1Rate; rowRateVsYr1 = vsYr1;  // v6
                    if      (vsYr1 >= inp.proPosUpperGuardrail)  alert = "[^] above";
                    else if (vsYr1 <= -inp.proPosLowerGuardrail) alert = "[v] below";
                }
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
            row.irmaa         = irmaaInt;
            row.conversion    = convThisYear;
            row.convTax       = convTaxThisYear;
            row.convNetToRoth = convNetToRoth;
            row.ssBridge      = bridgeThisYear;  // v6
            row.baseRate      = rowBaseRate;     // v6
            row.yr1Rate       = rowYr1Rate;      // v6
            row.rateVsYr1     = rowRateVsYr1;    // v6
            row.tradBal       = (int) medTrad;    // v6
            row.rothBal       = (int) medRoth;    // v6
            row.mmBal         = (int) Math.min(Integer.MAX_VALUE, mmReserveRun); // v8 exact-tie-out
            row.survivorYear  = survivor;         // v6
            row.magi          = magiInt;
            row.taxableSS     = taxableSSInt;
            row.ordinaryTax   = ordTaxInt;
            row.fedTax        = fedInt;
            row.stateTax      = stateInt;
            row.topBracket    = bracketStr;
            row.convBoundByIrmaa = convByIrmaa;
            row.convCeiling   = convCeilInt;
            row.totalSpend    = (int) totalSpend;
            row.totalIncome   = (int) totalIncome;
            row.surplus       = (int) surplus;
            row.inflFactor    = inflFactor;
            row.drawing       = drawing;
            row.goGoActive    = goGoRem > 0;
            // v8 FIX: slow-go shading must key on the WINDOW, not on the
            // multiplier being > 1.0. Since v8 allows sub-1.0 multipliers
            // (e.g. 0.200 slow-go), the old "goGoMult > 1.0005" test left
            // genuine slow-go years unshaded. A row is in the slow-go window
            // when the go-go window is exhausted but we are still within the
            // combined go-go + slow-go span, and slow-go has a real duration.
            // This mirrors spendMultFor's own window logic exactly.
            int yFromStart = y - startY;
            row.slowGoActive  = (goGoRem <= 0)
                    && inp.slowGoDuration > 0
                    && yFromStart >= inp.goGoDuration
                    && yFromStart < inp.goGoDuration + inp.slowGoDuration;
            row.goGoMult      = goGoMult;
            row.alert         = alert;
            row.balDelta      = nextMedBal - medBal;
            // v6: true real change = (next balance in its own dollars)
            //                       - (this balance in its own dollars)
            row.balDeltaReal  = (int) ((nextInflFactor > 0 ? nextMedBal / nextInflFactor : 0)
                    - (inflFactor > 0 ? medBal / inflFactor : 0));
            // v2 fix: realized median growth = (next median balance - this median
            // balance) + the withdrawal removed this year. The prior version used
            // medBal * nomReturn (a flat nominal-mean estimate) which never
            // reconciled with the median balance delta, badly so under stress
            // scenarios where the path return differs from the nominal mean.
            row.investmentGrowth = (nextMedBal - medBal) + wdActual;
            row.investmentGrowthReal = row.balDeltaReal
                    + (int) (inflFactor > 0 ? wdActual / inflFactor : wdActual);   // v6
            res.medianRows.add(row);
        }
        res.gkResults = simulateGK(inp);
        return res;
    }

    /**
     * Binary-search the largest first-year withdrawal whose trial-path survival
     * rate meets or exceeds {@code inp.targetPoS} starting from the given balance.
     * Within each trial path the withdrawal is held constant in real terms
     * (W0 * goGoMult * cumInflFactor, where cumInflFactor chains the per-year
     * stochastic inflation draws); this routine does NOT perform an inner annual
     * re-optimization. Called annually by the fan-path and display loops to
     * produce adaptive year-by-year withdrawals against the observed balance.
     */
    private int solveWithdrawalPro(int balance, int fromYear, int horizon,
                                   SimInputs inp, long seed,
                                   int solvePaths, int binIters, int goGoYearsRemaining,
                                   double[] bridgeFromHere) {
        // NOTE (v2): `fromYear` is intentionally unused. This solver is
        // deliberately calendar-agnostic -- by design it sizes the MAXIMUM
        // sustainable PORTFOLIO draw at the target PoS, independent of which
        // calendar year guaranteed income (SS/annuity) starts. The caller layers
        // guaranteed income and the spending budget on top (see the surplus/gap
        // column). The parameter is retained for call-site signature stability and
        // possible future calendar-aware variants.
        if (balance <= 0 || horizon <= 0) return 0;
        double lo = 0, hi = balance * 0.22;
        for (int i = 0; i < binIters; i++) {
            double mid = (lo + hi) / 2.0;
            double rate = survivalRatePro(balance, horizon, mid,
                    inp, seed + i * 31L, solvePaths, goGoYearsRemaining, bridgeFromHere);
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
     * Fraction of Monte Carlo trial paths that survive {@code horizon} years
     * given an inflation-indexed withdrawal stream. For each path, year y
     * spending = firstYrWd * goGoMult(y) * cumInflFactor(y), where
     * cumInflFactor chains the per-year stochastic inflation draws
     * multiplicatively (year 0 = 1.0; thereafter cumInflFactor *= (1 + infl_y)).
     * No inner binary search and no balance-aware re-optimization occurs
     * here -- the withdrawal schedule is a real-dollar fixed stream calibrated
     * by the outer binary search in {@link #solveWithdrawalPro}.
     */
    private double survivalRatePro(int balance, int horizon, double firstYrWd,
                                   SimInputs inp, long seed, int solvePaths, int goGoYearsRemaining,
                                   double[] bridgeFromHere) {
        int survived = 0;
        for (int i = 0; i < solvePaths; i++) {
            SeededRng rng = new SeededRng(seed * 1000L + i * 7 + 3);
            double b = balance;
            double cumInflFactor = 1.0;  // year-0 spend = W0 (no inflation); chained thereafter

            for (int y = 0; y < horizon; y++) {
                double[] ri2 = getReturnAndInflation(inp, y, rng);
                double ret   = ri2[0];
                double infl  = ri2[1];
                if (y > 0) cumInflFactor *= (1 + infl);  // chain per-year draws (matches GK convention)
                // v6 FIX: the solver had never been given the slow-go tier, so PoS
                // was computed against go-go-then-flat spending while the display
                // and fan paths used three tiers. It reported a spending level as
                // safe that had not actually been tested.
                // goGoYearsRemaining counts go-go years left from THIS point, so
                // slow-go occupies the window immediately after it.
                int goGoRem = Math.max(0, goGoYearsRemaining - y);
                double mult;
                if (goGoRem > 0)                                        mult = inp.goGoMultiplier;
                else if (y < goGoYearsRemaining + inp.slowGoDuration)    mult = inp.slowGoMultiplier;
                else                                                     mult = 1.0;
                // v6: the SS bridge is an extra draw the portfolio must fund, so
                // PoS reflects the true cost of delaying rather than only the
                // display showing a lower balance.
                double bridgeY = (bridgeFromHere != null && y < bridgeFromHere.length)
                        ? bridgeFromHere[y] : 0.0;
                double spend = (b > 0) ? firstYrWd * mult * cumInflFactor + bridgeY : 0;
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

        // v6: the year-1 figure is a BASE-YEAR amount (inflation factor 1.000),
        // so it is identical under the real/nominal toggle. Label it so the user
        // is not left wondering why this number does not move when others do.
        lblAnswer.setText(CURRENCY.format(yr1) + " / yr (today's $)");
        lblSub.setText(String.format(
                "  %.2f%% of portfolio  .  %.0f%% PoS target  .  %d-year horizon  .  true stochastic median",
                rate, inp.targetPoS * 100, inp.horizon));
        String scenLabel = inp.scenarioIndex > 0
                ? " . [Stress: " + HistoricalScenarios.SCENARIO_NAMES[inp.scenarioIndex].split(" \\(")[0]
                + (inp.seqOffset > 0 ? ", +" + inp.seqOffset + " yr" : "") + "]"
                : "";
        lblDetail.setText(String.format(
                "User (age %d) . Spouse (age %d) . Draws begin %02d/%d . "
                        + "%.2f%% nom return / %.2f%% inflation%s",
                inp.manAge, inp.womanAge,
                inp.withdrawStartMonth, inp.withdrawStartYear,
                inp.nomReturn * 100, inp.inflation * 100, scenLabel));

        double dEnd = !res.medianRows.isEmpty()
                ? res.medianRows.get(res.medianRows.size() - 1).inflFactor : 1.0;
        int yr10wd  = res.medianRows.size() >= 10 ? res.medianRows.get(9).wdActual : 0;
        double d10  = res.medianRows.size() >= 10 ? res.medianRows.get(9).inflFactor : 1.0;

        // v6: remember this run so the baseline button and comparison line work.
        lastRunActualWd = !res.medianRows.isEmpty() ? res.medianRows.get(0).wdActual : yr1;
        lastRunBalance  = inp.portfolio;
        lastRunHorizon  = inp.horizon;
        lastRunGoGoMult = (inp.goGoDuration > 0) ? inp.goGoMultiplier : 1.0;
        lastRunValid    = true;
        if (btnSetBaseline != null) btnSetBaseline.setEnabled(true);
        refreshBaselineLine();

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
                    ((showRealDollars ? r.balDeltaReal : r.balDelta) >= 0 ? "+" : "-")
                            + CURRENCY.format((long) Math.abs(
                            showRealDollars ? r.balDeltaReal : r.balDelta)),       // 22 (v6)
                    (r.drawing && r.irmaa > 0)
                            ? CURRENCY.format((long)(r.irmaa / d)) : "--",                 // 23 IRMAA
                    (r.drawing && r.magi > 0)
                            ? CURRENCY.format((long)(r.magi / d)) : "--",                  // 24 MAGI (v7)
                    (r.drawing && r.conversion > 0)
                            ? CURRENCY.format((long)(r.conversion / d)) : "--",            // 25 Roth Conv
                    (r.drawing && r.convTax > 0)
                            ? CURRENCY.format((long)(r.convTax / d)) : "--",               // 26 Conv Tax
                    r.tradBal > 0 ? CURRENCY.format((long)(r.tradBal / d)) : "--",          // 27 Trad Bal (v6)
                    r.rothBal > 0 ? CURRENCY.format((long)(r.rothBal / d)) : "--",          // 28 Roth Bal (v6)
                    r.mmBal > 0 ? CURRENCY.format((long)(r.mmBal / d)) : "--",              // 29 Money Mkt (v6)
                    String.format("%.3fx", r.goGoMult),                                     // 30 Spend mult (v6)
                    r.ssBridge > 0 ? CURRENCY.format((long)(r.ssBridge / d)) : "--",        // 31 SS bridge (v6)
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
        // v6 FIX: report the ACTUAL first-year draw, i.e. the go-go-adjusted
        // withdrawal the Pro PoS table shows in "Actual wd". Using the base
        // yr1Withdrawal here made the Summary's income and surplus disagree with
        // the table for the same year -- by the whole go-go uplift (e.g. a $7.5K
        // surplus reported against the table's ~$23K).
        EnhRow r1 = res.medianRows.stream().filter(r -> r.drawing).findFirst().orElse(null);
        int yr1   = (r1 != null && r1.wdActual > 0) ? r1.wdActual : res.yr1Withdrawal;
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
                            + "  User (age %d) . Spouse (age %d)\n\n",
                    inp.baseYear, inp.manAge, inp.womanAge);
        }

        return preDrawSection + String.format(
                "== INCOME PoS -- FIRST WITHDRAWAL YEAR (%d) ==\n"
                        + "  Portfolio withdrawal:  %s/yr  (%.2f%% of $%,.0f, go-go adjusted)\n"
                        + "  Method: true stochastic median . annual re-solve on observed balance\n"
                        + "  + Guaranteed income:   %s\n"
                        + "  = Total income:        %s\n"
                        + "  - Total spending:      %s\n"
                        + "  -> %s of %s\n\n"
                        + "== SOCIAL SECURITY ==\n"
                        + "  User: %s/yr from %02d/%d (age %d) . Spouse: %s/yr from %02d/%d (age %d)\n"
                        + "  COLA %.1f%%/yr%s\n\n"
                        + "== ANNUITY ==\n"
                        + "  %s/yr from %d (non-COLA)\n\n"
                        + "== RMD SCHEDULE (SECURE 2.0 -- age 75) ==\n"
                        + "  User's trad IRA + 401K: %s . RMDs begin %d\n"
                        + "  Spouse's trad IRA + 401K: %s . RMDs begin %d\n"
                        + "  Roth accounts (no RMD): %s\n\n"
                        + "== SPENDING ==\n"
                        + "  Base tax %s in %d . Medical %s at %.1f%%/yr\n"
                        + "  Go-go multiplier: %.3fx for first %d years (through %d)\n\n"
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
                inp.ssColaTracksInflation
                        ? String.format("  [COLA TRACKS SIMULATED INFLATION, shortfall %.1f%%/yr"
                        + " -- the fixed COLA above is NOT in use]", inp.ssColaShortfall * 100)
                        : "",
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
                inp.horizon, inp.baseYear + inp.horizon - 1);   // v6: last row, not one past it
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
                "<html><i>Computed monthly: User $%,.0f (%s) . Spouse $%,.0f (%s)</i></html>",
                manM, manAdj, womM, womAdj));
    }

    // v6: median inflation factor at a given calendar year, clamped to range.
    private static double inflAt(double[] medInfl, int baseYear, int calYear) {
        if (medInfl == null || medInfl.length == 0) return 0;
        int idx = calYear - baseYear;
        if (idx < 0) idx = 0;
        if (idx >= medInfl.length) idx = medInfl.length - 1;
        return medInfl[idx];
    }

    // v6: growth factor applied to a benefit between its start year and calYear.
    // Default (ssColaTracksInflation == false) compounds the fixed ssCola, which
    // is what every pre-v6 scenario expects. When the option is on, the benefit
    // instead rides the SIMULATED inflation: inflFactor(calYear)/inflFactor(start),
    // i.e. constant real purchasing power. inflNow/inflAtStart are passed in by
    // the caller because they are path- or median-specific; passing equal values
    // (or zeros) safely degrades to no growth beyond the fixed-COLA branch.
    private double ssGrowth(SimInputs inp, int yearsSinceStart,
                            double inflNow, double inflAtStart) {
        if (inp.ssColaTracksInflation && inflAtStart > 0 && inflNow > 0) {
            // simulated inflation, then a constant annual haircut == "inflation
            // minus shortfall" compounded over the years since the benefit began.
            double sf = Math.max(0, inp.ssColaShortfall);
            return (inflNow / inflAtStart) / Math.pow(1 + sf, Math.max(0, yearsSinceStart));
        }
        return Math.pow(1 + inp.ssCola, yearsSinceStart);
    }

    // v6/v9: per-year SS BRIDGE schedule, in nominal dollars, index = sim year.
    //
    // v9 makes the bridge a THREE-WAY mode (SimInputs.bridgeMode):
    //
    //   NONE (2)        -- no bridge at all.
    //
    //   FIRST_CLAIM (1) -- the v6 behavior, preserved byte-for-byte. Reference
    //                      date = the EARLIER of the two claim dates; only the
    //                      LATER claimant is bridged, across the gap between the
    //                      two claim dates, at the benefit they WOULD have had
    //                      claiming at the reference (the smaller early amount).
    //
    //   SIM_START (0)   -- the new default. BOTH spouses are bridged, each from
    //                      the WITHDRAWAL-START date to that spouse's OWN claim
    //                      date, at the benefit each WOULD have received claiming
    //                      at withdrawal-start. This fills the early pre-claim
    //                      years so Surplus/gap does not go negative merely
    //                      because SS has not switched on yet. The two per-spouse
    //                      bridges are summed into one schedule.
    //
    // In every mode the raw benefit is GROSSED UP, because replacing Social
    // Security with a Traditional withdrawal is not tax-neutral: SS is at most
    // 85% taxable via the provisional-income formula, an IRA distribution is 100%
    // ordinary income. Three fixed-point passes converge well inside a dollar.
    //
    // The gross-up uses a deterministic tax estimate rather than each fan path's
    // own tax position, so one schedule serves the display, the fan paths and the
    // PoS solver alike -- they cannot drift apart and the portfolio reconciles.
    private double[] buildSsBridgeSchedule(SimInputs inp) {
        double[] bridge = new double[inp.horizon];
        if (inp.bridgeMode == BRIDGE_NONE) return bridge;

        if (inp.bridgeMode == BRIDGE_SIM_START) {
            // v9: bridge BOTH spouses, each from the withdrawal-start date to
            // their own claim date. addBridgeForPerson accumulates into bridge[].
            int wsMo = inp.withdrawStartYear * 12 + inp.withdrawStartMonth;

            int manStartMo   = inp.manSSStartYear   * 12 + inp.manSSStartMonth;
            int womanStartMo = inp.womanSSStartYear * 12 + inp.womanSSStartMonth;

            // Man: bridge from max(withdrawStart, ...) up to his own claim, only
            // if he actually claims AFTER withdrawals begin (else no gap).
            if (manStartMo > wsMo) {
                addBridgeForPerson(inp, bridge,
                        /*isMan=*/true,
                        inp.withdrawStartYear, inp.withdrawStartMonth,   // reference (counterfactual claim)
                        inp.withdrawStartYear, inp.withdrawStartMonth,   // gap start
                        inp.manSSStartYear,    inp.manSSStartMonth);     // gap end (own claim, exclusive)
            }
            // Woman: same, independently.
            if (womanStartMo > wsMo) {
                addBridgeForPerson(inp, bridge,
                        /*isMan=*/false,
                        inp.withdrawStartYear, inp.withdrawStartMonth,
                        inp.withdrawStartYear, inp.withdrawStartMonth,
                        inp.womanSSStartYear,  inp.womanSSStartMonth);
            }
            return bridge;
        }

        // ---- BRIDGE_FIRST_CLAIM : the v6 path, unchanged in result ----------
        int manStart   = inp.manSSStartYear   * 12 + inp.manSSStartMonth;
        int womanStart = inp.womanSSStartYear * 12 + inp.womanSSStartMonth;
        if (manStart == womanStart) return bridge;   // nobody is delaying

        boolean manDelays = manStart > womanStart;
        int refY  = manDelays ? inp.womanSSStartYear  : inp.manSSStartYear;
        int refM  = manDelays ? inp.womanSSStartMonth : inp.manSSStartMonth;
        int ownY  = manDelays ? inp.manSSStartYear    : inp.womanSSStartYear;
        int ownM  = manDelays ? inp.manSSStartMonth   : inp.womanSSStartMonth;
        // The delayer is bridged from the reference (the earlier claim) to their
        // own start, at the counterfactual reference-date benefit.
        addBridgeForPerson(inp, bridge, manDelays, refY, refM, refY, refM, ownY, ownM);
        return bridge;
    }

    // v9: add one person's bridge into the schedule (accumulates -- does not
    // overwrite -- so SIM_START can sum both spouses). Extracted verbatim from
    // the v6 loop so FIRST_CLAIM reproduces the old numbers exactly.
    //
    //   isMan       -- which spouse is being bridged (selects PIA/birth + the
    //                  OTHER spouse's real SS for the tax stack).
    //   refY/refM   -- the counterfactual claim date whose benefit is replaced.
    //   gapStartY/M -- first month bridged (inclusive).
    //   gapEndY/M   -- month the person's real benefit begins (exclusive).
    private void addBridgeForPerson(SimInputs inp, double[] bridge,
                                    boolean isMan,
                                    int refY, int refM,
                                    int gapStartY, int gapStartM,
                                    int gapEndY, int gapEndM) {
        int gapStartMo = gapStartY * 12 + gapStartM;   // inclusive
        int gapEndMo   = gapEndY   * 12 + gapEndM;     // exclusive
        if (gapEndMo <= gapStartMo) return;            // empty window

        double cfMonthly = isMan
                ? calcSSMonthlyBenefit(inp.manPIA,   inp.manBirthYear,   inp.manBirthMonth,   refY, refM)
                : calcSSMonthlyBenefit(inp.womanPIA, inp.womanBirthYear, inp.womanBirthMonth, refY, refM);
        double cfAnnual = cfMonthly * 12.0;
        if (cfAnnual <= 0) return;

        TaxEngine.StateTaxProfile stProfile = TaxEngine.stateProfile(inp.stateCode);

        for (int y = 0; y < inp.horizon; y++) {
            int calYear = inp.baseYear + y;
            int yrStartMo = calYear * 12 + 1, yrEndMo = calYear * 12 + 13;
            int covered = Math.min(yrEndMo, gapEndMo) - Math.max(yrStartMo, gapStartMo);
            if (covered <= 0) continue;                       // outside the gap
            double yearFrac = Math.min(12, covered) / 12.0;   // 1.0 for a full year

            double inflFactor = Math.pow(1 + inp.inflation, y);
            TaxEngine.FilingStatus fs = filingFor(inp, calYear);

            double grown = cfAnnual * Math.pow(1 + inp.ssCola, Math.max(0, calYear - refY))
                    * ssHaircutFactor(inp, calYear)
                    * yearFrac;

            // Tax stack: the OTHER spouse's real SS plus whatever bridge this
            // schedule has already accrued this year (so a two-spouse SIM_START
            // bridge stacks consistently rather than each pretending it is alone).
            double otherSS = isMan ? womanSSThisYear(inp, y) : manSSThisYear(inp, y);
            double stackBase = otherSS + bridge[y];
            double taxSSwith    = TaxEngine.taxableSocialSecurity(stackBase + grown, 0, inflFactor, fs);
            double taxSSwithout = TaxEngine.taxableSocialSecurity(stackBase,         0, inflFactor, fs);
            double ssTaxableDelta = Math.max(0, taxSSwith - taxSSwithout);
            double[] ssTax = TaxEngine.conversionTax(taxSSwithout, ssTaxableDelta,
                    inflFactor, fs, stProfile, calYear, 0);
            double netTarget = grown - ssTax[0];

            double b = grown;
            for (int pass = 0; pass < 3; pass++) {
                double[] bt = TaxEngine.conversionTax(taxSSwithout, b,
                        inflFactor, fs, stProfile, calYear, 0);
                double net = b - bt[0];
                if (net <= 1) break;
                b = b * (netTarget / net);
            }
            bridge[y] += Math.max(0, b);   // ACCUMULATE
        }
    }

    // v6: across-the-board benefit reduction from a given year onward. Applied at
    // this single choke point so EVERY consumer inherits it -- survivor wrappers,
    // fan conversion helpers, the display loop, the GK loop and the survivor
    // floor -- rather than being bolted on in six places.
    static double ssHaircutFactor(SimInputs inp, int calYear) {
        // v6: the start year must be 0 (never) or a real calendar year. A small
        // number like "6" is a typo caught mid-entry, and taken literally EVERY
        // projected year is >= 6, which silently haircut the whole projection --
        // a wrong answer that looks entirely plausible. Fail toward "never" so a
        // typo yields the unmodified plan rather than a quietly damaged one.
        if (inp.ssHaircutStartYear > 0 && inp.ssHaircutStartYear <= 2000) return 1.0;
        if (inp.ssHaircutStartYear <= 0 || inp.ssHaircutPct <= 0) return 1.0;
        return (calYear >= inp.ssHaircutStartYear)
                ? Math.max(0.0, 1.0 - inp.ssHaircutPct) : 1.0;
    }

    private double manSSThisYear(SimInputs inp, int y, double inflNow, double inflAtStart) {
        int calYear = inp.baseYear + y;
        if (calYear < inp.manSSStartYear) return 0;
        double cut = ssHaircutFactor(inp, calYear);   // v6
        if (calYear == inp.manSSStartYear)
            return inp.manSSAmount * (13.0 - inp.manSSStartMonth) / 12.0 * cut;
        return inp.manSSAmount
                * ssGrowth(inp, calYear - inp.manSSStartYear, inflNow, inflAtStart) * cut;
    }
    private double manSSThisYear(SimInputs inp, int y) {   // fixed-COLA convenience
        return manSSThisYear(inp, y, 0, 0);
    }

    private double womanSSThisYear(SimInputs inp, int y, double inflNow, double inflAtStart) {
        int calYear = inp.baseYear + y;
        if (calYear < inp.womanSSStartYear) return 0;
        double cut = ssHaircutFactor(inp, calYear);   // v6
        if (calYear == inp.womanSSStartYear)
            return inp.womanSSAmount * (13.0 - inp.womanSSStartMonth) / 12.0 * cut;
        return inp.womanSSAmount
                * ssGrowth(inp, calYear - inp.womanSSStartYear, inflNow, inflAtStart) * cut;
    }
    private double womanSSThisYear(SimInputs inp, int y) {
        return womanSSThisYear(inp, y, 0, 0);
    }

    private double annuityThisYear(SimInputs inp, int y) {
        int calYear = inp.baseYear + y;
        if (calYear < inp.annuityStartYear) return 0;
        if (calYear == inp.annuityStartYear)
            return inp.annuity * (13.0 - inp.annuityStartMonth) / 12.0;
        return inp.annuity;
    }

    // v6: spending multiplier for a given simulation year. Phase 1 = go-go,
    // phase 2 = slow-go (immediately after), phase 3 = no-go at 1.0. Centralised
    // so the display, the fan paths and the PoS SOLVER cannot drift apart --
    // if the solver missed slow-go it would report a spending level as safe
    // that had never been tested.
    static double spendMultFor(SimInputs inp, int simYear, int startY) {
        int y = simYear - startY;
        if (y < 0) return 1.0;
        if (y < inp.goGoDuration) return inp.goGoMultiplier;
        if (y < inp.goGoDuration + inp.slowGoDuration) return inp.slowGoMultiplier;
        return 1.0;
    }

    // ===================== v6: death-event helpers ==========================
    // A survivor year is any year strictly AFTER the death year (the death year
    // itself is still filed jointly / both benefits paid for that partial year;
    // the survivor basis begins the following January).
    static boolean isSurvivorYear(SimInputs inp, int calYear) {
        return inp.deathWho != 0 && inp.deathYear > 0 && calYear > inp.deathYear;
    }

    // Filing status for THIS calendar year: Single once the survivor year has
    // begun, otherwise the user-selected basis (normally MFJ). A run explicitly
    // set to Single with no death event stays Single throughout (single-friend
    // scenario), which this preserves.
    static TaxEngine.FilingStatus filingFor(SimInputs inp, int calYear) {
        return isSurvivorYear(inp, calYear)
                ? TaxEngine.FilingStatus.SINGLE : inp.filingStatus;
    }

    // Survivor-aware Social Security. In a survivor year the decedent's benefit
    // stops and the survivor keeps the LARGER of the two benefits (standard
    // survivor rule). We compute both raw benefits, then in survivor years route
    // the larger to the survivor's slot and zero the decedent's.
    private double manSSSurv(SimInputs inp, int y, double iNow, double iManStart, double iWomanStart) {
        int calYear = inp.baseYear + y;
        double raw = manSSThisYear(inp, y, iNow, iManStart);
        if (!isSurvivorYear(inp, calYear)) return raw;
        if (inp.deathWho == 1) return 0;                 // user died -> no user SS
        // spouse died -> user (survivor) keeps larger of the two this year
        return Math.max(manSSThisYear(inp, y, iNow, iManStart),
                womanSSThisYear(inp, y, iNow, iWomanStart));
    }
    private double manSSSurv(SimInputs inp, int y) { return manSSSurv(inp, y, 0, 0, 0); }
    private double womanSSSurv(SimInputs inp, int y, double iNow, double iManStart, double iWomanStart) {
        int calYear = inp.baseYear + y;
        double raw = womanSSThisYear(inp, y, iNow, iWomanStart);
        if (!isSurvivorYear(inp, calYear)) return raw;
        if (inp.deathWho == 2) return 0;                 // spouse died -> no spouse SS
        // user died -> spouse (survivor) keeps larger of the two this year
        return Math.max(manSSThisYear(inp, y, iNow, iManStart),
                womanSSThisYear(inp, y, iNow, iWomanStart));
    }
    private double womanSSSurv(SimInputs inp, int y) { return womanSSSurv(inp, y, 0, 0, 0); }

    // Combined RMD off a single Traditional balance, split by the his/her share
    // and each half aged on its owner. In a survivor year only the survivor's
    // age applies (their share -> 1.0, decedent -> 0.0). This is the two-age
    // approximation that lets one combined Traditional bucket produce correct
    // RMDs without tracking two drifting balances (exact when ages are equal;
    // negligible error for near-equal ages).
    double combinedRmd(SimInputs inp, double tradBal,
                       int calYear, int manAge, int womanAge) {
        if (tradBal <= 0) return 0;
        double hs = inp.hisRmdShare, ws = inp.herRmdShare;
        if (isSurvivorYear(inp, calYear)) {
            if (inp.deathWho == 1) { hs = 0; ws = 1; }   // user died -> survivor = spouse
            else                   { hs = 1; ws = 0; }   // spouse died -> survivor = user
        }
        return calcRmd(tradBal * hs, manAge) + calcRmd(tradBal * ws, womanAge);
    }

    // Gross Roth conversion for the fan path, matching the display-loop policy:
    // fill-to-ceiling (below IRMAA Tier-0 / 22%->24% edge) or flat $, using the
    // per-year filing status. Sized on pre-conversion MAGI built from this
    // year's ordinary income (RMD floor) + annuity + survivor-aware SS.
    private double fanConversion(SimInputs inp, int y, int calYear,
                                 int manAge, int womanAge,
                                 double trad, double roth, boolean survivor,
                                 double wdActual,
                                 double iNow, double iManStart, double iWomanStart) {
        TaxEngine.FilingStatus fs = filingFor(inp, calYear);
        double inflFactor = Math.pow(1 + inp.inflation, y);
        double grossSS = manSSSurv(inp, y, iNow, iManStart, iWomanStart)
                + womanSSSurv(inp, y, iNow, iManStart, iWomanStart);
        double rmd     = combinedRmd(inp, trad, calYear, manAge, womanAge);
        double ann     = annuityThisYear(inp, y);
        // v6 FIX: match the display basis -- ordinary income is the actual
        // Traditional portion of the draw (RMD floors it), not the full
        // withdrawal and not the RMD alone. Sizing conversions off RMD only
        // understated income and over-converted.
        double ordinaryBeforeConv = Math.max(rmd, Math.min(wdActual, Math.max(0, trad))) + ann;
        double taxSSpre = TaxEngine.taxableSocialSecurity(grossSS, ordinaryBeforeConv, inflFactor, fs);
        double magiBeforeConv = taxSSpre + ordinaryBeforeConv;
        if (inp.convFillMode) {
            double[] fill = TaxEngine.fillConversion(magiBeforeConv, inp.convBuffer,
                    manAge >= 65, womanAge >= 65, inp.convCap, inflFactor, fs,
                    inp.fillIrmaaTierIdx, inp.fillBracketIdx);
            return fill[0];
        }
        return inp.convFlat;
    }

    // Net-to-Roth for a given gross conversion on the fan path (gross minus the
    // stacked marginal conversion tax), using the per-year filing status.
    private double fanConversionNet(SimInputs inp, int y, int calYear,
                                    int manAge, int womanAge,
                                    double trad, double roth,
                                    double convGross, boolean survivor,
                                    double wdActual,
                                    double iNow, double iManStart, double iWomanStart) {
        TaxEngine.FilingStatus fs = filingFor(inp, calYear);
        double inflFactor = Math.pow(1 + inp.inflation, y);
        double grossSS = manSSSurv(inp, y, iNow, iManStart, iWomanStart)
                + womanSSSurv(inp, y, iNow, iManStart, iWomanStart);
        double rmd     = combinedRmd(inp, trad, calYear, manAge, womanAge);
        double ann     = annuityThisYear(inp, y);
        double ordinaryBeforeConv = Math.max(rmd, Math.min(wdActual, Math.max(0, trad))) + ann;  // v6 FIX
        TaxEngine.StateTaxProfile stProfile = TaxEngine.stateProfile(inp.stateCode);
        TaxEngine.TaxResult tr = TaxEngine.compute(grossSS, ordinaryBeforeConv,
                magiBeforeForConv(grossSS, ordinaryBeforeConv, inflFactor, fs),
                manAge >= 65, womanAge >= 65, inflFactor,
                TaxEngine.irmaaThreshFactor(inp.irmaaThreshMode, inflFactor, Math.max(0, y - 2)),
                fs, stProfile, calYear, rmd);
        double[] ctax = TaxEngine.conversionTax(tr.taxableIncome, convGross,
                inflFactor, fs, stProfile, calYear, rmd);
        return convGross - ctax[0];
    }
    private static double magiBeforeForConv(double grossSS, double ordinaryOther,
                                            double inflFactor, TaxEngine.FilingStatus fs) {
        return TaxEngine.taxableSocialSecurity(grossSS, ordinaryOther, inflFactor, fs) + ordinaryOther;
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
        // v6: GK inflation factor at each SS start year, captured as the loop
        // passes it (see the Pro PoS fan loop for the same pattern).
        double gkManStartInfl = 0, gkWomanStartInfl = 0;

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
            if (calYear == inp.manSSStartYear)   gkManStartInfl   = inflFactor;
            if (calYear == inp.womanSSStartYear) gkWomanStartInfl = inflFactor;

            double manSS      = manSSSurv(inp, y, inflFactor, gkManStartInfl, gkWomanStartInfl);
            double womanSS    = womanSSSurv(inp, y, inflFactor, gkManStartInfl, gkWomanStartInfl);
            double ann        = annuityThisYear(inp, y);
            double guaranteed = manSS + womanSS + ann;
            double living     = drawing ? inp.livingExp   * inflFactor : 0;
            double medical    = drawing ? inp.medical     * Math.pow(1 + inp.medInflation, y) : 0;
            // v6: survivor spending drop (see the Pro PoS loop for rationale).
            if (isSurvivorYear(inp, calYear)) {
                living  *= (1.0 - inp.survivorSpendCut);
                medical *= SimInputs.SURVIVOR_MEDICAL_FACTOR;
            }
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

                // Conventional Guyton-Klinger mapping:
                //   UPPER guardrail -> Capital Preservation Rule (CUT): if the current
                //     withdrawal RATE has risen more than upper% ABOVE the initial rate
                //     (portfolio shrank), cut the withdrawal 10%.
                //   LOWER guardrail -> Prosperity Rule (RAISE): if the current
                //     withdrawal RATE has fallen more than lower% BELOW the initial rate
                //     (portfolio grew), raise the withdrawal 10%.
                double wdPctCheck = bal > 0 ? wdGK / bal : 0;
                if (wdPctCheck > initialWdRate * (1 + inp.gkUpperGuardrail)) {
                    wdGK *= 0.90;
                    flags = flags.equals("--") ? "CPR\u25bc" : flags + " + CPR\u25bc";
                }
                wdPctCheck = bal > 0 ? wdGK / bal : 0;
                if (wdPctCheck < initialWdRate * (1 - inp.gkLowerGuardrail)) {
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
            // v6: true real change -- each balance deflated by ITS OWN year's
            // price level, so the column agrees with the balance column in
            // real-dollar mode instead of contradicting it.
            double nextInflF = inflFactor * (1 + yearInfl);
            row.balDeltaReal = (int) ((nextInflF > 0 ? nextBal / nextInflF : 0)
                    - (inflFactor > 0 ? bal / inflFactor : 0));
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
                "User Age", "Cal yr", "Portfolio bal",                      // 0 1 2
                "GK withdrawal", "Actual wd", "Wd %",                       // 3 4 5
                "Rules (raw)",                                                // 6 hidden
                "Rule flags",                                                 // 7 visible
                "User SS", "Spouse SS", "Annuity", "Fixed Inc",              // 8 9 10 11
                "Living Exp", "Medical", "Tax (est) *",                      // 12 13 14 (v6: * legacy)
                "Total spend", "Total income", "Surplus/gap",                // 15 16 17
                "Infl factor",                                                // 18
                "User RMD *", "Spouse RMD *", "Combined RMD *", "-> Roth/MM *", // 19 20 21 22 (v6: * legacy)
                "Portfolio Chg"                                                  // 23
        };
        tblGkModel = new DefaultTableModel(gkCols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        tblGk = new JTable(tblGkModel) {
            @Override public String getToolTipText(MouseEvent e) {
                int col = convertColumnIndexToModel(columnAtPoint(e.getPoint()));
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
                if (col == 11) { // Fixed Inc
                    double d = showRealDollars ? gr.inflFactor : 1.0;
                    return String.format(
                            "<html><b>Fixed Inc = User SS + Spouse SS + Annuity</b><br>"
                                    + "&nbsp;&nbsp;User SS:&nbsp;&nbsp;&nbsp;&nbsp;%s<br>"
                                    + "&nbsp;&nbsp;Spouse SS:&nbsp;%s<br>"
                                    + "&nbsp;&nbsp;Annuity:&nbsp;&nbsp;&nbsp;%s<br>"
                                    + "&nbsp;&nbsp;= Fixed Inc:&nbsp;<b>%s</b></html>",
                            CURRENCY.format((long)(gr.manSS / d)),
                            CURRENCY.format((long)(gr.womanSS / d)),
                            CURRENCY.format((long)(gr.annuity / d)),
                            CURRENCY.format((long)(gr.guaranteed / d)));
                }
                if (col == 12) { // Living Exp
                    double d = showRealDollars ? gr.inflFactor : 1.0;
                    return String.format(
                            "<html><b>Living Exp = base input inflated to this year</b><br>"
                                    + "&nbsp;&nbsp;This year's value:&nbsp;<b>%s</b><br>"
                                    + "&nbsp;&nbsp;Inflation factor applied:&nbsp;%.3f<br><br>"
                                    + "Source: your Living Expense input, scaled by the median<br>"
                                    + "cumulative inflation factor for this year.</html>",
                            CURRENCY.format((long)(gr.living / d)),
                            gr.inflFactor);
                }
                if (col == 15) { // Total spend
                    double d = showRealDollars ? gr.inflFactor : 1.0;
                    return String.format(
                            "<html><b>Total spend = Living Exp + Medical + Tax</b><br>"
                                    + "&nbsp;&nbsp;Living Exp:&nbsp;%s<br>"
                                    + "&nbsp;&nbsp;Medical:&nbsp;&nbsp;&nbsp;&nbsp;%s<br>"
                                    + "&nbsp;&nbsp;Tax (est):&nbsp;&nbsp;%s<br>"
                                    + "&nbsp;&nbsp;= Total spend:&nbsp;<b>%s</b></html>",
                            CURRENCY.format((long)(gr.living / d)),
                            CURRENCY.format((long)(gr.medical / d)),
                            CURRENCY.format((long)(gr.tax / d)),
                            CURRENCY.format((long)(gr.totalSpend / d)));
                }
                if (col == 16) { // Total income
                    double d = showRealDollars ? gr.inflFactor : 1.0;
                    return String.format(
                            "<html><b>Total income = Actual wd + Fixed Inc</b><br>"
                                    + "&nbsp;&nbsp;Actual wd (portfolio draw):&nbsp;%s<br>"
                                    + "&nbsp;&nbsp;Fixed Inc (guaranteed):&nbsp;&nbsp;&nbsp;&nbsp;%s<br>"
                                    + "&nbsp;&nbsp;= Total income:&nbsp;<b>%s</b></html>",
                            CURRENCY.format((long)(gr.wdActual / d)),
                            CURRENCY.format((long)(gr.guaranteed / d)),
                            CURRENCY.format((long)(gr.totalIncome / d)));
                }
                if (col == 17) { // Surplus/gap
                    double d = showRealDollars ? gr.inflFactor : 1.0;
                    return String.format(
                            "<html><b>Surplus / gap = Total income - Total spend</b><br>"
                                    + "&nbsp;&nbsp;Total income:&nbsp;%s<br>"
                                    + "&nbsp;&nbsp;Total spend:&nbsp;&nbsp;%s<br>"
                                    + "&nbsp;&nbsp;= Surplus/gap:&nbsp;<b>%s</b><br><br>"
                                    + "This column represents the amount over the expected living<br>"
                                    + "expenses that is available for spending.</html>",
                            CURRENCY.format((long)(gr.totalIncome / d)),
                            CURRENCY.format((long)(gr.totalSpend / d)),
                            (gr.surplus >= 0 ? "+" : "-")
                                    + CURRENCY.format((long)(Math.abs(gr.surplus) / d)));
                }
                if (col == 22 && gr.rmdOverage > 0) { // Roth/MM
                    return "<html><b>RMD overage -> Roth/MM</b><br>"
                            + "Combined RMD (" + CURRENCY.format(gr.combRmd) + ")<br>"
                            + "exceeds planned GK withdrawal (" + CURRENCY.format(gr.wdActual) + ").<br>"
                            + "Overage (" + CURRENCY.format(gr.rmdOverage) + ") goes to Roth/MM -- not spent.<br>"
                            + "The simulated portfolio balance is not reduced by RMDs; the overage<br>"
                            + "is assumed re-invested at a return at least matching inflation and<br>"
                            + "remains in your asset base.</html>";
                }
                if (col == 23) { // Bal Chg tooltip
                    double d = showRealDollars ? gr.inflFactor : 1.0;
                    return String.format("<html><b>Portfolio change: %s%s</b><br>"
                                    + "&nbsp;&nbsp;Market growth: +%s<br>"
                                    + "&nbsp;&nbsp;Withdrawal:   -%s<br>",
                            (showRealDollars ? gr.balDeltaReal : gr.balDelta) >= 0 ? "+" : "",
                            CURRENCY.format((long) Math.abs(
                                    showRealDollars ? gr.balDeltaReal : gr.balDelta)),
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
                90                           // 23 Bal Chg
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
                int col = tblGk.convertColumnIndexToModel(gkHeader.columnAtPoint(e.getPoint()));
                switch (col) {
                    case 3 -> gkHeader.setToolTipText(
                            "<html><b>GK withdrawal</b><br>"
                                    + "Pre-anchor years: user-entered rate x current balance.<br>"
                                    + "Anchor year: net spending need (spending minus guaranteed income).<br>"
                                    + "Subsequent years: prior withdrawal, inflation-adjusted, then<br>"
                                    + "modified by CPR, PR, and PMR rules as needed.<br><br>"
                                    + "Guardrail comparisons (CPR[v] / PR[^]) use the user-entered<br>"
                                    + "pre-anchor rate as the initial-rate benchmark -- not the<br>"
                                    + "computed net-need / portfolio ratio.</html>");
                    case 7 -> gkHeader.setToolTipText(
                            "<html><b>Rule flags</b><br>"
                                    + "<b>X.X%</b> = Year 1 of drawing: gkPreRate x current balance used.<br>"
                                    + "&nbsp;&nbsp;Set via 'GK only -- pre-anchor initial wd rate' in the input panel.<br>"
                                    + "<b>--</b> = GK rules phase, no rule triggered; normal inflation adjustment.<br>"
                                    + "<b>PMR0</b> = Portfolio Management Rule: inflation raise <i>skipped</i>.<br>"
                                    + "<b>CPR[v]</b> = Capital Preservation Rule: withdrawal cut 10%.<br>"
                                    + "<b>PR[^]</b> = Prosperity Rule: withdrawal raised 10%.</html>");
                    case 11 -> gkHeader.setToolTipText(
                            "<html><b>Fixed Inc -- guaranteed income (fed by User SS + Spouse SS + Annuity)</b><br>"
                                    + "= User SS + Spouse SS + Annuity for the year.<br>"
                                    + "The non-portfolio income floor. Hover a cell to see the<br>"
                                    + "three source values that add up to that year's figure.</html>");
                    case 12 -> gkHeader.setToolTipText(
                            "<html><b>Living Exp -- core living budget (fed by your Living Exp input)</b><br>"
                                    + "= your base Living Expense input, inflated by the median cumulative<br>"
                                    + "inflation factor for that year. Hover a cell to see the base<br>"
                                    + "input and the inflation factor applied.</html>");
                    case 15 -> gkHeader.setToolTipText(
                            "<html><b>Total spend -- committed budget (fed by Living Exp + Medical + Tax)</b><br>"
                                    + "= Living Exp + Medical + Tax (est).<br>"
                                    + "Hover a cell to see the three source values that add up<br>"
                                    + "to that year's total.</html>");
                    case 16 -> gkHeader.setToolTipText(
                            "<html><b>Total income -- money available (fed by Actual wd + Fixed Inc)</b><br>"
                                    + "= Actual wd (GK portfolio draw) + Fixed Inc (guaranteed income).<br>"
                                    + "Hover a cell to see the two source values that add up<br>"
                                    + "to that year's total.</html>");
                    case 17 -> gkHeader.setToolTipText(
                            "<html><b>Surplus / gap -- Total income minus Total spend</b><br>"
                                    + "= Total income - Total spend.<br>"
                                    + "This column represents the amount over the expected living<br>"
                                    + "expenses that is available for spending.</html>");
                    case 23 -> gkHeader.setToolTipText(
                            "<html><b>Portfolio Chg -- portfolio balance change</b><br>"
                                    + "= market growth - GK spending withdrawal.<br>"
                                    + "Green = grew. Red = shrank.</html>");
                    case 19, 20, 21 -> gkHeader.setToolTipText(
                            "<html><b>RMD -- legacy basis (GK tab)</b><br>"
                                    + "This tab computes RMDs on the ORIGINAL pre-v6 account model: the<br>"
                                    + "Traditional buckets grow at the portfolio return and are reduced<br>"
                                    + "<b>only by their own RMDs</b>. They are never drawn down by spending<br>"
                                    + "or by Roth conversions.<br><br>"
                                    + "Consequence: those balances -- and therefore these RMD figures --<br>"
                                    + "are <b>overstated, increasingly so over time</b>. The Pro PoS tab uses<br>"
                                    + "the v6 combined-bucket model where Traditional is genuinely spent<br>"
                                    + "down, and typically shows much smaller RMDs, falling to zero once<br>"
                                    + "Traditional is exhausted. On the same scenario, 2051 can read<br>"
                                    + "~$140K here versus $0 there.<br><br>"
                                    + "<b>Use the Pro PoS RMD columns for planning.</b> These are retained so<br>"
                                    + "the Guyton-Klinger methodology stays self-contained and comparable<br>"
                                    + "to its published form.</html>");
                    case 22 -> gkHeader.setToolTipText(
                            "<html><b>-&gt; Roth/MM -- legacy basis (GK tab)</b><br>"
                                    + "The part of the forced distribution larger than that year's GK<br>"
                                    + "spending. It is derived from the RMD columns, so it <b>inherits the<br>"
                                    + "same overstatement</b> described there: the Traditional buckets behind<br>"
                                    + "it are never reduced by spending or conversions.<br><br>"
                                    + "Display only -- it does not move money in this tab.<br>"
                                    + "<b>Use the Pro PoS Money Mkt column for planning.</b></html>");
                    case 14 -> gkHeader.setToolTipText(
                            "<html><b>Tax (est) -- legacy flat basis (GK tab)</b><br>"
                                    + "This tab always uses the <b>flat</b> tax model (Base tax yr 1, grown at<br>"
                                    + "Tax inflation), regardless of whether <i>Use computed tax engine</i> is<br>"
                                    + "checked. It does not compute federal brackets, Arizona tax, Social<br>"
                                    + "Security taxability, IRMAA, or the Single-filer switch after a death<br>"
                                    + "event.<br><br>"
                                    + "Consequence: this column stays roughly <b>constant in real terms for<br>"
                                    + "the whole horizon</b> -- it will not fall when Traditional is exhausted,<br>"
                                    + "and will not rise at the widow's-tax transition.<br><br>"
                                    + "<b>Use the Pro PoS Tax column for planning.</b> Retained so the<br>"
                                    + "Guyton-Klinger methodology stays self-contained and comparable to<br>"
                                    + "its published form.</html>");
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
        legend.add(gkLegendChip(new Color(230, 222, 255), new Color(70, 40, 140),  "Year 1 initial rate (gkPreRate x bal)"));
        legend.add(gkLegendChip(new Color(220, 235, 255), new Color(24, 95, 165),  "GK rules start"));
        legend.add(gkLegendChip(new Color(180, 230, 205), new Color(0, 90, 50),    "Go-go years"));
        legend.add(gkLegendChip(new Color(255, 235, 185), new Color(120, 70, 0),   "PMR0 -- inflation frozen"));
        legend.add(gkLegendChip(new Color(255, 210, 210), new Color(150, 30, 30),  "CPR[v] -- cut 10%"));
        legend.add(gkLegendChip(new Color(210, 240, 210), new Color(30, 110, 30),  "PR[^]  -- raised 10%"));
        legend.add(gkLegendChip(new Color(255, 200, 120), new Color(140, 60, 0),   "RMD overage -> Roth/MM"));
        // v6: flag the columns that are still computed on the pre-v6 basis.
        legend.add(gkLegendChip(new Color(232, 232, 232), new Color(90, 90, 90),
                "* legacy basis -- see tooltips"));

        JPanel topGk = new JPanel(new BorderLayout(0, 4));
        topGk.setBackground(new Color(245, 245, 242));
        topGk.add(gkMetrics, BorderLayout.NORTH);
        topGk.add(legend,    BorderLayout.SOUTH);

        // Closing note: point users to the methodology critique.
        JLabel gkNote = new JLabel("<html><i>The 4% rule and derivative dynamic-guardrail methods "
                + "(including Guyton-Klinger) have a fundamental flaw: 'success' means the portfolio "
                + "survives, not that spending stays livable -- a $100 portfolio taking a $4 withdrawal "
                + "still 'succeeds.' This is why the PoS method is the overwhelmingly preferred methodology "
                + "for this application. See section 8, \"Why PoS is the primary method,\" on the "
                + "Assumptions &amp; Methods tab to learn about the problem and why PoS is the better "
                + "option.</i></html>");
        gkNote.setFont(new Font("SansSerif", Font.PLAIN, 12));
        gkNote.setForeground(new Color(120, 60, 20));
        gkNote.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 190, 150), 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));

        // Pure-GK fidelity banner (deep blue, italic). The go-go/slow-go multipliers
        // scale the GK withdrawal, which the 2006 spec does not include. To run a
        // textbook-faithful GK simulation, the user zeroes the go-go/slow-go DURATIONS
        // -- but those inputs are shared with the Pro PoS and Stress Test tabs, so the
        // banner warns to restore them afterward. See Assumptions section 8.
        JLabel gkPureNote = new JLabel("<html><i><b>Pure Guyton-Klinger note:</b> the go-go and slow-go "
                + "multipliers scale the withdrawals shown here (Actual wd = GK withdrawal x go-go multiplier). "
                + "The 2006 Guyton-Klinger spec has no go-go concept. To run a simulation true to the spec, set "
                + "the <b>go-go years</b> and <b>slow-go years</b> (durations) to <b>0</b> in the input panel. "
                + "Note that these are shared inputs that also drive the Pro PoS and Stress Test tabs -- "
                + "<b>restore your go-go/slow-go settings afterward.</b> See section 8 on the Assumptions &amp; "
                + "Methods tab.</i></html>");
        gkPureNote.setFont(new Font("SansSerif", Font.PLAIN, 12));
        gkPureNote.setForeground(new Color(20, 60, 120));   // deep, muted blue
        gkPureNote.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(150, 175, 215), 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));

        // Stack the two notes vertically so neither displaces the other.
        JPanel gkNotes = new JPanel(new BorderLayout(0, 4));
        gkNotes.setBackground(new Color(245, 245, 242));
        gkNotes.add(gkPureNote, BorderLayout.NORTH);
        gkNotes.add(gkNote,     BorderLayout.SOUTH);

        JPanel topGkWrap = new JPanel(new BorderLayout(0, 4));
        topGkWrap.setBackground(new Color(245, 245, 242));
        topGkWrap.add(topGk,   BorderLayout.NORTH);
        topGkWrap.add(gkNotes, BorderLayout.SOUTH);

        JScrollPane gkScroll = new JScrollPane(tblGk);
        gkScroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel gkPanel = new JPanel(new BorderLayout(0, 4));
        gkPanel.setBackground(new Color(245, 245, 242));
        gkPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        gkPanel.add(topGkWrap, BorderLayout.NORTH);
        gkPanel.add(gkScroll,  BorderLayout.CENTER);
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
                        ((showRealDollars ? gr.balDeltaReal : gr.balDelta) >= 0 ? "+" : "-")
                                + CURRENCY.format((long) Math.abs(
                                showRealDollars ? gr.balDeltaReal : gr.balDelta)),         // 23 (v6)
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
        int off = Math.max(0, inp.seqOffset);            // v6
        if (seq != null && simYear >= off && (simYear - off) < seq.length) {
            return new double[]{ seq[simYear - off][1], seq[simYear - off][2] };
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
        int  balDeltaReal;          // v6: true real-dollar change (see EnhRow)
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
    //  TAX ENGINE  (v3)
    // ------------------------------------------------------------------------
    //  Self-contained federal + state + IRMAA calculator for a MFJ couple.
    //  State tax is pluggable via the StateTaxProfile registry (v5); Arizona
    //  (flat 2.5%, SS-excluded) is the default and reproduces the prior fixed
    //  behavior, and a user-configurable Custom flat-rate profile is provided.
    //  Base-year (2026) statutory values are inflation-indexed forward each
    //  simulation year using the same inflation factor the median path already
    //  carries, mirroring how the IRS adjusts brackets, the standard deduction,
    //  and IRMAA thresholds via chained-CPI. See the Assumptions & Methods tab
    //  for sourcing and the deductions actually modeled vs. deliberately omitted.
    //
    //  Verified 2026 figures (IRS Rev. Proc. 2025-32; SSA):
    //    - MFJ ordinary brackets 10/12/22/24/32/35/37
    //    - MFJ standard deduction $32,200
    //    - Age-65 additional standard deduction $1,650 per spouse (MFJ) /
    //      $2,050 (single)
    //    - IRMAA (Part B + D) couple/yr surcharge on 2-years-prior MAGI
    //    - Social Security taxability via the provisional-income formula
    //    - Arizona flat state income tax 2.5% (default state profile; other
    //      states via the Custom profile or added to the StateTaxProfile registry)
    //
    //  DELIBERATELY NOT MODELED: the OBBBA "senior bonus" deduction (up to
    //  $6,000/person, 2025-2028, MFJ phase-out from $150k MAGI, sunsets after
    //  2028). Omitting it slightly OVERSTATES tax (conservative, preferred
    //  direction) and avoids encoding fresh-statute phase-out/eligibility logic.
    //  Documented on the Assumptions & Methods tab. Verify with a tax
    //  professional before relying on it.
    // ========================================================================
    static class TaxEngine {

        // ====================================================================
        //  FILING STATUS (v4)
        //  MFJ is the default and preserves all prior (v3) behavior exactly.
        //  SINGLE is the survivor-year / single-friend basis: it switches the
        //  entire federal + SS-taxation + IRMAA constant set below. Structural
        //  items (accounts, Social Security streams, RMDs) are NOT changed by
        //  this flag -- for a survivor scenario the user consolidates balances
        //  into the surviving person's fields and zeroes the decedent's inputs
        //  in the saved scenario, then sets this flag to SINGLE. See the
        //  Assumptions & Methods tab.
        // ====================================================================
        enum FilingStatus { MFJ, SINGLE }

        // -- 2026 MFJ ordinary brackets {lowerTaxable, upperTaxable, rate} -----
        static final double[][] BRACKETS_2026 = {
                {        0,   24_800, 0.10},
                {   24_800,  100_800, 0.12},
                {  100_800,  211_400, 0.22},
                {  211_400,  403_550, 0.24},
                {  403_550,  512_450, 0.32},
                {  512_450,  768_700, 0.35},
                {  768_700, Double.MAX_VALUE, 0.37}
        };

        // -- 2026 SINGLE ordinary brackets (IRS Rev. Proc. 2025-32) -----------
        static final double[][] BRACKETS_SINGLE_2026 = {
                {        0,   12_400, 0.10},
                {   12_400,   50_400, 0.12},
                {   50_400,  105_700, 0.22},
                {  105_700,  201_775, 0.24},
                {  201_775,  256_225, 0.32},
                {  256_225,  640_600, 0.35},
                {  640_600, Double.MAX_VALUE, 0.37}
        };

        // 22% -> 24% taxable-income boundary (2026); used for conversion cap.
        static final double BRACKET_22_CEILING_TAXABLE_2026        = 211_400.0;  // MFJ
        static final double BRACKET_22_CEILING_TAXABLE_SINGLE_2026 = 201_775.0;  // Single

        // 2026 base standard deduction and age-65 additional.
        static final double STD_DED_MFJ_2026    = 32_200.0;
        static final double STD_DED_SINGLE_2026 = 16_100.0;
        // Age-65 additional standard deduction (IRC 63(f), IRS Rev. Proc.
        // 2025-32). The MARRIED figure is per qualifying spouse; the UNMARRIED
        // figure is larger. 2026: $1,650 married / $2,050 unmarried. Supersedes
        // the 2025 married value of $1,600 used in an earlier build.
        static final double ADD_STD_DED_65_EACH_2026   = 1_650.0;  // per spouse (MFJ)
        static final double ADD_STD_DED_65_SINGLE_2026 = 2_050.0;  // single filer

        // IRMAA (Medicare Part B + D combined surcharge). Tier assessed on MAGI
        // from 2 years prior. 2026 base thresholds and annual surcharges, per
        // CMS 2026 tables. Six tiers (index 0 = no surcharge). The threshold
        // arrays hold the FIVE upper boundaries; irmaaTier() returns 0..5.
        //
        // Per-person 2026 annual surcharge (Part B + Part D) x 12:
        //   T1 (81.20+14.50)*12 = 1,148.40   T2 (202.90+37.50)*12 = 2,884.80
        //   T3 (324.60+60.40)*12 = 4,620.00  T4 (446.30+83.30)*12 = 6,355.20
        //   T5 (487.00+91.00)*12 = 6,936.00
        // MFJ (both on Medicare) = 2 x per-person. Single = per-person.
        //
        // NOTE (v4 fix): the prior (v3) MFJ arrays had only four surcharge
        // tiers with stale values and collapsed the top two tiers; both the
        // thresholds and the surcharge amounts are corrected here to the full
        // six-tier 2026 CMS schedule.
        static final double[] IRMAA_THRESH_MFJ_2026    = {218_000, 274_000, 342_000, 410_000, 750_000};
        static final double[] IRMAA_MFJ_YR_2026        = {0, 2_296.80, 5_769.60, 9_240.00, 12_710.40, 13_872.00};
        static final double[] IRMAA_THRESH_SINGLE_2026 = {109_000, 137_000, 171_000, 205_000, 500_000};
        static final double[] IRMAA_SINGLE_YR_2026     = {0, 1_148.40, 2_884.80, 4_620.00,  6_355.20,  6_936.00};

        // Social Security provisional-income thresholds. Statutory, NOT
        // inflation-indexed in real life (fixed since 1993) -- so we hold them
        // fixed here too, which realistically drags more SS into the taxable
        // base over time (correct behavior).
        static final double SS_PROV_BASE_MFJ      = 32_000.0;   // below: 0% taxable
        static final double SS_PROV_SECOND_MFJ    = 44_000.0;   // between: up to 50%
        static final double SS_PROV_BASE_SINGLE   = 25_000.0;
        static final double SS_PROV_SECOND_SINGLE = 34_000.0;

        // Arizona flat state income tax. RETAINED for reference only; the live
        // state-tax path now flows through the StateTaxProfile registry below,
        // where Arizona is defined with this same 2.5% rate.
        static final double AZ_STATE_RATE = 0.025;

        // ====================================================================
        //  STATE TAX (v5) -- per-state, per-year, with year-history.
        //
        //  Each state is a StateTaxProfile holding a year -> StateTaxYear map.
        //  forYear(simYear) returns the most recent entry at or before simYear
        //  (NavigableMap.floorEntry), so a sim year with no exact entry inherits
        //  the latest prior year's rules -- a documented fallback. To update for
        //  a new tax year, append one StateTaxYear; no code change is needed.
        //
        //  Two profiles ship: ARIZONA (flat 2.5%, SS excluded -- reproduces the
        //  pre-v5 hardcoded behavior exactly) and CUSTOM (user-entered flat rate
        //  + two flags). Bracketed state tax is scaffolded (bracketedTax) for a
        //  future progressive state but is unused by the shipped flat profiles.
        // ====================================================================
        static final class StateTaxYear {
            final int      year;
            final double   flatRate;                 // used when brackets == null
            final double[][] brackets;               // {lowerTaxable, upperTaxable, rate} in base-yr $, or null
            final boolean  taxesSocialSecurity;      // false = subtract taxable SS from the state base
            final boolean  excludesRetirementIncome; // true = subtract retirement ordinary (RMD/trad draw)
            final double   retirementExclusionCap;   // base-yr $ cap on that exclusion (0 = unlimited)
            final double   stateStdDeduction;        // base-yr $ state standard deduction (0 = none)
            final String   note;                     // provenance / caveat, shown in the tooltip

            StateTaxYear(int year, double flatRate, double[][] brackets,
                         boolean taxesSocialSecurity, boolean excludesRetirementIncome,
                         double retirementExclusionCap, double stateStdDeduction, String note) {
                this.year = year;
                this.flatRate = flatRate;
                this.brackets = brackets;
                this.taxesSocialSecurity = taxesSocialSecurity;
                this.excludesRetirementIncome = excludesRetirementIncome;
                this.retirementExclusionCap = retirementExclusionCap;
                this.stateStdDeduction = stateStdDeduction;
                this.note = note;
            }
        }

        static final class StateTaxProfile {
            final String code;         // "AZ", "CUSTOM"
            final String displayName;  // "Arizona", "Custom (flat rate)"
            final java.util.NavigableMap<Integer, StateTaxYear> byYear = new java.util.TreeMap<>();

            StateTaxProfile(String code, String displayName) {
                this.code = code;
                this.displayName = displayName;
            }
            StateTaxProfile add(StateTaxYear y) { byYear.put(y.year, y); return this; }

            /** Rules effective for a simulation calendar year: the most recent
             *  entry at or before simYear, or the earliest entry if simYear
             *  precedes every entry. Never null (profiles always have >=1 year). */
            StateTaxYear forYear(int simYear) {
                java.util.Map.Entry<Integer, StateTaxYear> e = byYear.floorEntry(simYear);
                return (e != null) ? e.getValue() : byYear.firstEntry().getValue();
            }
        }

        // -- Registry. Insertion order drives the UI dropdown order. ----------
        static final java.util.Map<String, StateTaxProfile> STATE_REGISTRY =
                new java.util.LinkedHashMap<>();
        // The CUSTOM profile is mutable: UI edits rebuild its single StateTaxYear.
        static StateTaxProfile customProfile;

        static {
            // ARIZONA -- flat 2.5%, Social Security excluded from the state base,
            // no retirement-income exclusion. Byte-for-byte the prior behavior.
            STATE_REGISTRY.put("AZ", new StateTaxProfile("AZ", "Arizona")
                    .add(new StateTaxYear(2026, 0.025, null,
                            /*taxesSS*/ false, /*exclRetire*/ false, /*cap*/ 0,
                            /*stateStdDed*/ 0,
                            "Arizona flat 2.5%; Social Security excluded from the state base.")));

            // CUSTOM -- user-entered flat rate + two flags. Defaults to a benign
            // 0% / tax-SS / no-exclusion state; rebuilt from the UI via setCustom.
            customProfile = new StateTaxProfile("CUSTOM", "Custom (flat rate)")
                    .add(new StateTaxYear(BASE_YEAR_DEFAULT, 0.0, null,
                            /*taxesSS*/ true, /*exclRetire*/ false, /*cap*/ 0,
                            /*stateStdDed*/ 0,
                            "User-entered flat rate. UNVERIFIED -- confirm your state's rules."));
            STATE_REGISTRY.put("CUSTOM", customProfile);
        }

        /** Rebuild the CUSTOM profile from UI values. One StateTaxYear at the
         *  base year covers the whole horizon (floorEntry). */
        static void setCustom(double flatRate, boolean taxesSS,
                              boolean exclRetire, double exclCap) {
            customProfile.byYear.clear();
            customProfile.add(new StateTaxYear(BASE_YEAR_DEFAULT, flatRate, null,
                    taxesSS, exclRetire, exclCap, 0,
                    "User-entered flat rate. UNVERIFIED -- confirm your state's rules."));
        }

        static StateTaxProfile stateProfile(String code) {
            StateTaxProfile p = STATE_REGISTRY.get(code);
            return (p != null) ? p : STATE_REGISTRY.get("AZ");
        }

        /** State tax on the living-expenses base for one sim year, honoring the
         *  profile's SS and retirement-exclusion flags.
         *  @param federalTaxableIncome federal taxable income (after fed deductions)
         *  @param taxableSS            taxable portion of Social Security (federal)
         *  @param retirementOrdinary   retirement ordinary income eligible for a
         *                              state exclusion (RMD / Traditional draw)
         */
        static double stateTaxLiving(StateTaxYear sty, double federalTaxableIncome,
                                     double taxableSS, double retirementOrdinary,
                                     double inflFactor) {
            double base = federalTaxableIncome;
            if (!sty.taxesSocialSecurity) base -= taxableSS;
            if (sty.excludesRetirementIncome) {
                double excl = (sty.retirementExclusionCap > 0)
                        ? Math.min(retirementOrdinary, infl(sty.retirementExclusionCap, inflFactor))
                        : retirementOrdinary;                 // 0 cap = unlimited
                base -= Math.max(0, excl);
            }
            if (sty.stateStdDeduction > 0) base -= infl(sty.stateStdDeduction, inflFactor);
            base = Math.max(0, base);
            return (sty.brackets == null)
                    ? base * sty.flatRate
                    : bracketedTax(base, sty.brackets, inflFactor);
        }

        /** Progressive state tax over inflation-indexed brackets. Scaffolding
         *  for a future bracketed state; unused by the shipped flat profiles. */
        static double bracketedTax(double taxable, double[][] brackets, double inflFactor) {
            double tax = 0;
            for (double[] b : brackets) {
                double lo = infl(b[0], inflFactor), hi = infl(b[1], inflFactor);
                if (taxable > lo) tax += (Math.min(taxable, hi) - lo) * b[2];
                else break;
            }
            return tax;
        }

        /** State tax on a Roth conversion (a separate Traditional distribution),
         *  honoring the retirement-exclusion flag. If the state excludes
         *  retirement income, the conversion is excluded up to remaining cap
         *  headroom (unlimited cap -> the conversion is fully state-exempt). */
        static double stateTaxConversion(StateTaxYear sty, double conversion,
                                         double retirementOrdinaryAlreadyCounted,
                                         double inflFactor) {
            if (conversion <= 0) return 0;
            double taxablePortion = conversion;
            if (sty.excludesRetirementIncome) {
                if (sty.retirementExclusionCap <= 0) {
                    taxablePortion = 0;                       // unlimited exclusion
                } else {
                    double capNom  = infl(sty.retirementExclusionCap, inflFactor);
                    double headroom = Math.max(0, capNom - retirementOrdinaryAlreadyCounted);
                    taxablePortion = Math.max(0, conversion - headroom);
                }
            }
            return (sty.brackets == null)
                    ? taxablePortion * sty.flatRate
                    : bracketedTax(taxablePortion, sty.brackets, inflFactor);
        }

        // -- Result holder -----------------------------------------------------
        static class TaxResult {
            double taxableSS;      // portion of gross SS that is taxable
            double ordinaryOther;  // RMD/trad draw + annuity + conversion (ordinary)
            double magi;           // AGI proxy used for IRMAA + SS formula
            double taxableIncome;  // MAGI - deductions (federal)
            double fedTax;
            double stateTax;
            double irmaaCost;      // surcharge PAID this year (based on 2yr-prior MAGI)
            int    irmaaTier;
            String topBracket;
            double totalTax;       // fedTax + stateTax + irmaaCost
        }

        /** Scale a base-year (2026) dollar boundary to simulation-year nominal
         *  dollars using the cumulative inflation factor for that year. */
        static double infl(double base2026, double inflFactor) {
            return base2026 * inflFactor;
        }

        // -- Filing-status constant selectors (v4) ---------------------------
        // v7: ACTIVE (editable) base-year tables. They default to deep copies of
        // the 2026 constants and are what every reader below now consults. The
        // "Edit thresholds..." dialog rewrites these via setActiveThresholds();
        // resetThresholds() restores the 2026 constants. The _2026 constants are
        // kept immutable as the reset source and the documented reference values.
        // NOTE: only the base-year values are editable; the forward inflation
        // projection (irmaaThreshFactor + infl()) is unchanged and scales these.
        private static double[][] deepCopy(double[][] a) {
            double[][] c = new double[a.length][];
            for (int i = 0; i < a.length; i++) c[i] = a[i].clone();
            return c;
        }
        static double[][] ACTIVE_BRACKETS_MFJ    = deepCopy(BRACKETS_2026);
        static double[][] ACTIVE_BRACKETS_SINGLE  = deepCopy(BRACKETS_SINGLE_2026);
        static double[]   ACTIVE_IRMAA_MFJ        = IRMAA_THRESH_MFJ_2026.clone();
        static double[]   ACTIVE_IRMAA_SINGLE     = IRMAA_THRESH_SINGLE_2026.clone();

        /** Push edited base-year thresholds into the active tables. Bracket tops
         *  arrays hold the UPPER edge of each bracket; the lower edge of bracket
         *  i+1 is kept in sync with the upper edge of bracket i so the ladder
         *  stays contiguous. Passing null for any argument leaves that table
         *  unchanged. */
        static void setActiveThresholds(double[] bracketTopMFJ, double[] bracketTopSingle,
                                        double[] irmaaMFJ, double[] irmaaSingle) {
            if (bracketTopMFJ != null)    applyBracketTops(ACTIVE_BRACKETS_MFJ,    bracketTopMFJ);
            if (bracketTopSingle != null) applyBracketTops(ACTIVE_BRACKETS_SINGLE, bracketTopSingle);
            if (irmaaMFJ != null)    ACTIVE_IRMAA_MFJ    = irmaaMFJ.clone();
            if (irmaaSingle != null) ACTIVE_IRMAA_SINGLE = irmaaSingle.clone();
        }
        private static void applyBracketTops(double[][] active, double[] tops) {
            int n = Math.min(active.length, tops.length);
            for (int i = 0; i < n; i++) {
                active[i][1] = tops[i];                 // upper edge
                if (i + 1 < active.length) active[i + 1][0] = tops[i]; // next lower edge
            }
        }
        /** Restore all active tables to the immutable 2026 constants. */
        static void resetThresholds() {
            ACTIVE_BRACKETS_MFJ   = deepCopy(BRACKETS_2026);
            ACTIVE_BRACKETS_SINGLE = deepCopy(BRACKETS_SINGLE_2026);
            ACTIVE_IRMAA_MFJ      = IRMAA_THRESH_MFJ_2026.clone();
            ACTIVE_IRMAA_SINGLE   = IRMAA_THRESH_SINGLE_2026.clone();
        }

        static double[][] brackets(FilingStatus fs) {
            return (fs == FilingStatus.SINGLE) ? ACTIVE_BRACKETS_SINGLE : ACTIVE_BRACKETS_MFJ;
        }
        static double[] irmaaThresh(FilingStatus fs) {
            return (fs == FilingStatus.SINGLE) ? ACTIVE_IRMAA_SINGLE : ACTIVE_IRMAA_MFJ;
        }
        static double[] irmaaSurcharge(FilingStatus fs) {
            return (fs == FilingStatus.SINGLE) ? IRMAA_SINGLE_YR_2026 : IRMAA_MFJ_YR_2026;
        }

        /** Taxable portion of Social Security via the provisional-income rule.
         *  provisional = otherOrdinaryIncome + 0.5 * grossSS.
         *  (We treat all non-SS ordinary income as the "other income" base; no
         *  tax-exempt interest is modeled.) Thresholds by filing status. */
        static double taxableSocialSecurity(double grossSS, double otherOrdinary,
                                            double inflFactor, FilingStatus fs) {
            if (grossSS <= 0) return 0;
            double provisional = otherOrdinary + 0.5 * grossSS;
            // Provisional thresholds are statutory-fixed (not indexed).
            double base   = (fs == FilingStatus.SINGLE) ? SS_PROV_BASE_SINGLE   : SS_PROV_BASE_MFJ;
            double second = (fs == FilingStatus.SINGLE) ? SS_PROV_SECOND_SINGLE : SS_PROV_SECOND_MFJ;
            if (provisional <= base) return 0;
            double taxable;
            if (provisional <= second) {
                taxable = 0.5 * (provisional - base);
            } else {
                taxable = 0.5 * (second - base) + 0.85 * (provisional - second);
            }
            // Cap at 85% of gross SS.
            return Math.min(taxable, 0.85 * grossSS);
        }

        /** Progressive federal tax on taxable income, brackets inflated to year. */
        static double fedTax(double taxable, double inflFactor, FilingStatus fs) {
            if (taxable <= 0) return 0;
            double tax = 0;
            for (double[] b : brackets(fs)) {
                double lo = infl(b[0], inflFactor);
                double hi = (b[1] == Double.MAX_VALUE) ? Double.MAX_VALUE : infl(b[1], inflFactor);
                if (taxable <= lo) break;
                tax += (Math.min(taxable, hi) - lo) * b[2];
            }
            return tax;
        }

        /** IRMAA tier index from a (2yr-prior) MAGI, thresholds inflated to year. */
        static int irmaaTier(double magi, double threshFactor, FilingStatus fs) {
            double[] thr = irmaaThresh(fs);
            for (int t = 0; t < thr.length; t++)
                if (magi <= infl(thr[t], threshFactor)) return t;
            return thr.length; // top tier
        }

        /** Human-readable top marginal bracket for a taxable income. */
        static String topBracket(double taxable, double inflFactor, FilingStatus fs) {
            String b = "10%";
            for (double[] br : brackets(fs)) {
                if (taxable > infl(br[0], inflFactor)) b = (int)(br[2]*100) + "%";
                else break;
            }
            return b;
        }

        /** Total deduction (base std ded + age-65 add-on), inflation-indexed.
         *  MFJ: add-on per qualifying spouse (man65, woman65). SINGLE: one
         *  add-on, driven by the primary/User person's age (man65); the spouse
         *  flag is ignored because a single filer has one taxpayer. OBBBA
         *  senior bonus deliberately omitted (see Assumptions tab). */
        static double totalDeduction(boolean man65, boolean woman65,
                                     double inflFactor, FilingStatus fs) {
            if (fs == FilingStatus.SINGLE) {
                double d = infl(STD_DED_SINGLE_2026, inflFactor);
                if (man65) d += infl(ADD_STD_DED_65_SINGLE_2026, inflFactor);
                return d;
            }
            double d = infl(STD_DED_MFJ_2026, inflFactor);
            if (man65)   d += infl(ADD_STD_DED_65_EACH_2026, inflFactor);
            if (woman65) d += infl(ADD_STD_DED_65_EACH_2026, inflFactor);
            return d;
        }

        // IRMAA threshold indexing modes.
        static final int IRMAA_FROZEN = 0;   // thresholds fixed in nominal dollars
        static final int IRMAA_CHAINED = 1;  // chained-CPI (inflation minus ~0.3%/yr)
        static final int IRMAA_FULL = 2;     // full CPI (= general inflation factor)

        // Chained-CPI runs ~0.3 percentage points/yr below regular CPI.
        static final double CHAINED_CPI_LAG = 0.003;

        /**
         * Factor to index IRMAA THRESHOLDS this simulation year, per the chosen
         * mode. Frozen keeps thresholds at nominal 2026 (factor 1.0). Chained
         * grows them at (inflation - 0.3%)/yr. Full uses the general inflation
         * factor. Given the general cumulative inflFactor and the year index y,
         * chained is reconstructed from the effective annual inflation implied
         * by inflFactor over y years, minus the chained lag.
         */
        static double irmaaThreshFactor(int mode, double inflFactor, int y) {
            switch (mode) {
                case IRMAA_FROZEN: return 1.0;
                case IRMAA_FULL:   return inflFactor;
                case IRMAA_CHAINED:
                default:
                    if (y <= 0 || inflFactor <= 1.0) return 1.0;
                    // effective annual inflation implied by the cumulative factor
                    double annual = Math.pow(inflFactor, 1.0 / y) - 1.0;
                    double chained = Math.max(0.0, annual - CHAINED_CPI_LAG);
                    return Math.pow(1.0 + chained, y);
            }
        }

        /**
         * Compute a full year's tax picture.
         *
         * @param grossSS        combined gross Social Security this year
         * @param ordinaryOther  ordinary income other than SS: max(RMD, trad
         *                        draw) + annuity + Roth conversion this year
         * @param magiTwoYrPrior MAGI from 2 years earlier for IRMAA lookback
         *                       (pass this year's MAGI if history not yet built)
         * @param man65          user is 65+ this year
         * @param woman65        spouse is 65+ this year
         * @param inflFactor     cumulative inflation factor for this year
         * @param irmaaThreshFactor factor for indexing IRMAA THRESHOLDS this
         *                       year (may differ from inflFactor: 1.0 = frozen,
         *                       reduced = chained-CPI, = inflFactor = full-CPI)
         */
        static TaxResult compute(double grossSS, double ordinaryOther,
                                 double magiTwoYrPrior,
                                 boolean man65, boolean woman65,
                                 double inflFactor, double irmaaThreshFactor,
                                 FilingStatus fs,
                                 StateTaxProfile stateProfile, int simYear,
                                 double retirementOrdinary) {
            TaxResult r = new TaxResult();
            r.taxableSS     = taxableSocialSecurity(grossSS, ordinaryOther, inflFactor, fs);
            r.ordinaryOther = ordinaryOther;
            r.magi          = r.taxableSS + ordinaryOther;

            double ded      = totalDeduction(man65, woman65, inflFactor, fs);
            r.taxableIncome = Math.max(0, r.magi - ded);
            r.fedTax        = fedTax(r.taxableIncome, inflFactor, fs);
            // State tax via the selected profile's rules for this year. For
            // Arizona (SS excluded, no retirement exclusion) this is exactly the
            // prior azBase * 2.5% computation; other profiles apply their flags.
            StateTaxYear sty = stateProfile.forYear(simYear);
            r.stateTax      = stateTaxLiving(sty, r.taxableIncome, r.taxableSS,
                    retirementOrdinary, inflFactor);

            // IRMAA THRESHOLDS index per the chosen mode; the surcharge AMOUNT
            // still tracks general inflation (Medicare costs rise with prices).
            r.irmaaTier = irmaaTier(magiTwoYrPrior, irmaaThreshFactor, fs);
            double[] tbl = irmaaSurcharge(fs);
            r.irmaaCost = tbl[Math.min(r.irmaaTier, tbl.length - 1)] * inflFactor;

            r.topBracket = topBracket(r.taxableIncome, inflFactor, fs);
            r.totalTax   = r.fedTax + r.stateTax + r.irmaaCost;
            return r;
        }

        /**
         * Size a Roth conversion under the binding ceiling. Returns the largest
         * conversion that keeps MAGI at or below the lower of (a) the IRMAA
         * Tier-0 ceiling minus buffer and (b) the 22%->24% bracket MAGI edge.
         * Both ceilings inflation-indexed. Returned value floored at 0 and
         * capped at userCap (0 = no user cap).
         *
         * @return double[]{ conversion, bindingCeilingMagi, bindingIsIrmaa(1/0) }
         */
        // v7: the IRMAA tier and the tax-bracket ceiling are now BOTH
        // user-selected rather than hardwired (was: IRMAA tier index 0 + the
        // 22% bracket top). irmaaTierIdx selects which upper IRMAA boundary is
        // the cliff to stay under (0 = first/Standard-to-Tier1 cliff, i.e. the
        // old behavior; 1 = Tier1-to-Tier2, etc.). bracketCeilIdx selects which
        // ordinary-bracket TOP is the tax ceiling, as an index into brackets(fs)
        // (e.g. MFJ: 1=top of 12%, 2=top of 22%, 3=top of 24%, 4=top of 32%).
        // The buffer is applied to the IRMAA ceiling ONLY -- a tax bracket is
        // not a cliff (crossing it taxes only the marginal dollars), so the fill
        // may land exactly on the bracket top with no margin. The engine still
        // takes the LOWER of the two ceilings and reports which one bound.
        static double[] fillConversion(double magiBeforeConv, double buffer,
                                       boolean man65, boolean woman65,
                                       double userCap, double inflFactor,
                                       FilingStatus fs,
                                       int irmaaTierIdx, int bracketCeilIdx) {
            double[] thr = irmaaThresh(fs);
            int ti = Math.max(0, Math.min(irmaaTierIdx, thr.length - 1));
            double irmaaCeil = infl(thr[ti], inflFactor) - buffer;
            // Bracket edge is a TAXABLE-income figure; convert to MAGI by adding
            // back the deduction so both ceilings are compared in MAGI.
            double ded        = totalDeduction(man65, woman65, inflFactor, fs);
            double[][] brk    = brackets(fs);
            int bi = Math.max(0, Math.min(bracketCeilIdx, brk.length - 1));
            // brackets(fs)[bi][1] is the UPPER edge (taxable) of bracket bi.
            double bracketCeilTaxable = brk[bi][1];
            double bracketCeil = infl(bracketCeilTaxable, inflFactor) + ded;
            double bindingCeil = Math.min(irmaaCeil, bracketCeil);
            boolean irmaaBinds = irmaaCeil <= bracketCeil;
            double conv = Math.max(0, bindingCeil - magiBeforeConv);
            if (userCap > 0) conv = Math.min(conv, userCap);
            return new double[]{ conv, bindingCeil, irmaaBinds ? 1 : 0 };
        }

        /**
         * Marginal (stacked) tax on a Roth conversion. The conversion is a
         * separate Traditional distribution taxed ON TOP of the living-expenses
         * taxable income, so its tax = tax(livingTaxable + conversion) -
         * tax(livingTaxable), at the couple's marginal rate. Federal + Arizona
         * (Arizona taxes the conversion -- it is not Social Security). The
         * conversion does NOT get its own standard deduction (that is already
         * consumed by living income); it stacks at the top marginal bracket.
         *
         * @param livingTaxableIncome federal taxable income from spending only
         *                            (living-expenses tax base, AFTER deduction)
         * @param conversion          gross conversion amount
         * @param stateProfile        selected state profile
         * @param simYear             simulation calendar year (picks year rules)
         * @param retirementOrdinary  retirement ordinary already counted this
         *                            year (for the state exclusion cap headroom)
         * @return double[]{ totalConvTax, fedConvTax, stateConvTax }
         */
        static double[] conversionTax(double livingTaxableIncome, double conversion,
                                      double inflFactor, FilingStatus fs,
                                      StateTaxProfile stateProfile, int simYear,
                                      double retirementOrdinary) {
            if (conversion <= 0) return new double[]{0, 0, 0};
            double base = Math.max(0, livingTaxableIncome);
            double fedWith = fedTax(base + conversion, inflFactor, fs);
            double fedBase = fedTax(base, inflFactor, fs);
            double fedConv = Math.max(0, fedWith - fedBase);
            // State tax on the conversion via the selected profile (Arizona:
            // flat 2.5%, fully taxable; a retirement-excluding state may exempt).
            StateTaxYear sty = stateProfile.forYear(simYear);
            double stConv  = stateTaxConversion(sty, conversion, retirementOrdinary, inflFactor);
            return new double[]{ fedConv + stConv, fedConv, stConv };
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
        // v8: prior-years Money Mkt (starting) -- an already-taxed cash reserve
        // carried in from a previous run's accumulated RMD overage. It seeds row 1
        // of the Money Mkt (total) column, is NEVER spent by the sim (informational
        // buffer), and is EXCLUDED from the drawn "Portfolio balance" (Option A:
        // Portfolio = qualified + spendable taxable only; ACTUAL total = Portfolio
        // + Money Mkt (total)).
        int mmPrior;
        int manPlanAge, womanPlanAge;
        double nomReturn, stdDev, inflation, inflationStdDev;
        int livingExp, medical; double medInflation;
        int baseTax; double taxInflation;
        // v3 tax engine
        boolean computedTax;
        boolean convFillMode;       // true = fill-to-target, false = flat
        int convFlat, convBuffer, convCap;
        int irmaaThreshMode;        // 0=frozen 1=chained-CPI 2=full-CPI
        // v7: fill-to-target ceilings, both user-selected.
        //   fillIrmaaTierIdx: index into the IRMAA upper-boundary array; 0 = the
        //     first cliff (Standard->Tier1, the pre-v7 default). Fill stays under
        //     THIS tier's threshold (minus buffer).
        //   fillBracketIdx: index into brackets(fs); the ordinary-bracket whose
        //     TOP is the tax ceiling. Default 2 = top of the 22% bracket (MFJ),
        //     matching the pre-v7 hardwired 22% behavior.
        int fillIrmaaTierIdx = 0;
        int fillBracketIdx   = 2;
        String stateCode = "AZ";    // v5: selected state tax profile ("AZ" / "CUSTOM")
        TaxEngine.FilingStatus filingStatus = TaxEngine.FilingStatus.MFJ; // v4
        // v6: mid-projection death event. deathWho: 0=Neither, 1=User(man),
        // 2=Spouse(woman). deathYear = calendar year of death; the SURVIVOR
        // year begins the year AFTER (calYear > deathYear). Before/at deathYear
        // the couple files per inp.filingStatus (normally MFJ); after, the tax
        // basis is forced to Single, the decedent's SS stops (survivor keeps the
        // larger benefit), and RMDs are computed on the survivor's age alone.
        // hisRmdShare/herRmdShare split the single combined Traditional bucket
        // for two-age RMD math while both live (default 0.5/0.5); at the death
        // year the survivor's share -> 1.0 and the decedent's -> 0.0.
        int deathWho = 0, deathYear = 0;      // v6
        double hisRmdShare = 0.5, herRmdShare = 0.5;   // v6
        // v6: survivor spending drop. Applied only in survivor years (the
        // year AFTER the death year). livingExp is reduced by this fraction;
        // medical is ALWAYS halved because it is mechanically per-person
        // (Part B + Medigap + Part D + self-insurance reserve each stop for
        // the decedent). Default 0.20 = the standard survivor estimate --
        // housing, utilities and insurance do not halve.
        // v6: when true, Social Security grows with the SIMULATED inflation
        // (constant real purchasing power, which is what CPI indexing does)
        // instead of compounding the fixed ssCola. Matters most in historical
        // high-inflation sequences, where a fixed COLA badly understates SS.
        boolean ssColaTracksInflation = false;   // v6
        // v6: annual haircut applied to the inflation-tracked COLA. SS is indexed
        // to CPI-W, which weights a working-age basket; retirees spend more on
        // healthcare and housing, and the experimental CPI-E has historically run
        // about 0.2 pp/yr higher. Only used when ssColaTracksInflation is true.
        double ssColaShortfall = 0.002;   // v6
        // v6: shift the historical sequence N years into the projection. 0 = the
        // crisis lands in year 1 (maximum runway, no banked surplus). A crisis a
        // few years in is usually WORSE: the portfolio is partly drawn AND the
        // go-go multiplier may still be running. Years before the offset use
        // ordinary random draws.
        int seqOffset = 0;   // v6
        // v6: scheduled-benefit-cut stress. Current law calls for an automatic
        // across-the-board reduction when the OASI trust fund depletes; CBO 2026
        // projects 2032 at roughly 28%. Congress has never allowed a scheduled
        // cut to take effect, so treat this as a STRESS input, not a forecast.
        // 0 / 0 = disabled, which reproduces every earlier scenario exactly.
        double ssHaircutPct = 0.0; int ssHaircutStartYear = 0;   // v6
        // v6: when one spouse delays, withdraw extra from the portfolio to
        // replace the benefit they are NOT yet receiving, so spending is held
        // constant and the entire cost of delaying lands on the portfolio
        // balance instead of being absorbed as reduced spending. Off by default.
        // v9: three-way bridge mode. bridgeDelayedSS is retained as a derived
        // convenience (true for any non-NONE mode) so existing display-layer
        // checks such as `if (er.ssBridge > 0)` and Summary text need no change.
        //   0 = SIM_START, 1 = FIRST_CLAIM, 2 = NONE  (mirrors the outer class).
        int bridgeMode = 0;                // v9: default SIM_START
        boolean bridgeDelayedSS = true;    // v9: derived (mode != NONE)
        double survivorSpendCut = 0.20;   // v6
        static final double SURVIVOR_MEDICAL_FACTOR = 0.5;   // v6, mechanical
        double goGoMultiplier; int goGoDuration;
        // v6: slow-go tier. Runs immediately AFTER the go-go window for its
        // own duration, then no-go (1.0) for the rest. Defaults 1.0 / 0 leave
        // every pre-existing scenario numerically identical.
        double slowGoMultiplier = 1.0; int slowGoDuration = 0;
        double proPosUpperGuardrail, proPosLowerGuardrail;   // Pro PoS advisory alerts only
        double gkUpperGuardrail, gkLowerGuardrail;           // Guyton-Klinger active adjustments
        double gkPreRate;
        int scenarioIndex; // 0=Random, 1=Depression, 2=Stagflation, 3=DotCom, 4=GFC

        // v9 Option 2: a shallow copy of every instance field, used by the SS
        // Optimizer to run per-combination Pro simulations without disturbing the
        // live inputs. Reflection keeps this correct even as fields are added --
        // there is no field-by-field list to forget to update. All fields here are
        // primitives or immutable String, so shallow copy is a true independent copy.
        SimInputs copy() {
            SimInputs c = new SimInputs();
            for (java.lang.reflect.Field f : SimInputs.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try { f.set(c, f.get(this)); } catch (IllegalAccessException ex) { /* fields are accessible */ }
            }
            return c;
        }
    }

    static class EnhRow {
        int  calYear, manAge, womanAge;
        int  balance, withdrawal, wdActual;
        double wdPct;
        int  manRmd, womanRmd, combRmd, rmdOverage;
        int  manSS, womanSS, annuity, guaranteed;
        int  living, medical, tax, totalSpend, totalIncome, surplus;
        // v3 tax engine detail
        int  irmaa, conversion, magi, taxableSS, ordinaryTax, fedTax, stateTax;
        int  convTax, convNetToRoth;   // v3: conversion tax + net landing in Roth
        // v6: the actual figures behind the Wd % guardrail colour, so the cell
        // tooltip can show WHY a row is green or red rather than just that it is.
        int  ssBridge;   // v6: extra draw replacing a delayed benefit
        double baseRate, yr1Rate, rateVsYr1;
        int  tradBal, rothBal;         // v6: median Traditional / Roth bucket balances
        int  mmBal;                    // v6: median Money Market (taxable) bucket
        boolean survivorYear;          // v6: true once the death event has fired
        String topBracket = "--";
        boolean convBoundByIrmaa;
        int  convCeiling;
        double inflFactor;
        boolean drawing, goGoActive;
        boolean slowGoActive;   // v6: middle spending tier, after go-go
        double goGoMult;
        String alert;
        int  balDelta, investmentGrowth;
        // v6: real-dollar equivalents. balDelta/investmentGrowth are NOMINAL
        // deltas spanning two different price levels; deflating them by a
        // single year factor made the column contradict the balance column in
        // real mode (balance falling while the change read positive).
        int  balDeltaReal, investmentGrowthReal;
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
        return card(title, items, true);   // default: sections start expanded
    }

    // v9: overload allowing a section to start COLLAPSED by default (e.g. the
    // rarely-edited Roth Conversion & IRMAA Surcharge section). A saved panel
    // state still overrides this default on scenario load.
    private JPanel card(String title, Object[] items, boolean defaultExpanded) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0,0,6,0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(208,206,200),1),
                        BorderFactory.createEmptyBorder(8,10,8,10))));

        // v7: section slug (stable key for save/load), derived from the title so
        // no call site changes. Titles are unique across the pane.
        final String key = sectionKey(title);
        final boolean startExpanded = sectionExpanded.getOrDefault(key, defaultExpanded);

        // Body panel holds every row; its visibility is what collapse toggles.
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setAlignmentX(LEFT_ALIGNMENT);

        // Header row: a triangle (▾/▸) + the section title. v9: the ENTIRE header
        // -- triangle, title, and the space across to the right edge -- is the
        // click target that toggles the section, not just the triangle. The arrow
        // and title are passive display; the shared listener lives on the header
        // panel so a click anywhere along the row collapses or expands.
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setOpaque(false);
        header.setAlignmentX(LEFT_ALIGNMENT);
        header.setBorder(BorderFactory.createEmptyBorder(0,0,6,0));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        header.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        header.setToolTipText("Collapse / expand this section");

        final JLabel arrow = new JLabel(startExpanded ? "\u25be" : "\u25b8"); // ▾ / ▸
        arrow.setFont(new Font("SansSerif",Font.BOLD,12));
        arrow.setForeground(new Color(110,105,95));
        arrow.setBorder(BorderFactory.createEmptyBorder(0,0,0,6));

        JLabel titleLbl = new JLabel(title.toUpperCase());
        titleLbl.setFont(new Font("SansSerif",Font.BOLD,12));
        titleLbl.setForeground(new Color(110,105,95));

        header.add(arrow);
        header.add(titleLbl);
        header.add(Box.createHorizontalGlue());

        // Shared toggle: Swing does NOT bubble a child's mouse event up to its
        // parent, so a click landing directly on the triangle or the title label
        // would otherwise be swallowed. Attach the same listener to the header AND
        // both labels so a press anywhere on the row toggles the section.
        java.awt.event.MouseAdapter toggle = new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                boolean nowExpanded = !body.isVisible();
                body.setVisible(nowExpanded);
                arrow.setText(nowExpanded ? "\u25be" : "\u25b8");
                sectionExpanded.put(key, nowExpanded);
                savePanelState();                 // persist immediately
                card.revalidate();
                card.repaint();
            }
        };
        header.addMouseListener(toggle);
        arrow.addMouseListener(toggle);
        titleLbl.addMouseListener(toggle);
        // Hand cursor on the labels too, so the affordance is consistent across the row.
        arrow.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        titleLbl.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

        card.add(header);

        for (int i = 0; i < items.length; i += 2) {
            Object labelObj = items[i]; Object comp = items[i+1];
            if (labelObj != null) {
                JLabel lbl = new JLabel((String) labelObj);
                lbl.setFont(new Font("SansSerif",Font.PLAIN,14));
                lbl.setForeground(new Color(75,75,75));
                lbl.setBorder(BorderFactory.createEmptyBorder(5,0,1,0));
                lbl.setAlignmentX(LEFT_ALIGNMENT); body.add(lbl);
            }
            JComponent row = (comp instanceof JSpinner sp) ? wrapSpinner(sp) : (JComponent) comp;
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            body.add(row);
        }

        body.setVisible(startExpanded);           // honor saved/default state
        card.add(body);
        return card;
    }

    // v7: stable per-section key from the display title. Lowercases, keeps
    // alphanumerics, collapses the rest to nothing -- e.g. "Social Security" ->
    // "socialsecurity", "Tax Engine & Roth Conversion (v3)" ->
    // "taxenginerothconversionv3". Stable as long as the title is unchanged.
    private static String sectionKey(String title) {
        StringBuilder sb = new StringBuilder();
        for (char c : title.toLowerCase().toCharArray())
            if (Character.isLetterOrDigit(c)) sb.append(c);
        return sb.toString();
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

    // v6: re-run trigger balance + year-over-year baseline comparison.
    // Trigger balance: the rate drifts past the upper guardrail when the balance
    // falls to portfolio/(1+g) -- because rate = wd/balance and wd is fixed for
    // the year. One number to check against the real account between runs.
    // Baseline comparison: the withdrawal is essentially rate x balance, so the
    // year-over-year change splits exactly into a portfolio effect and a rate
    // (horizon) effect, with no residual:
    //     portfolio effect = (balNow - balBase) * rateBase
    //     horizon  effect = balNow * (rateNow - rateBase)
    private void refreshBaselineLine() {
        if (lblBaseline == null) return;
        if (!lastRunValid) { lblBaseline.setText(" "); return; }
        StringBuilder sb = new StringBuilder("<html>");

        double g = (spProPosUpperGuardrail != null) ? dv(spProPosUpperGuardrail) / 100.0 : 0.20;
        if (lastRunBalance > 0 && g > 0) {
            long trig = (long) (lastRunBalance / (1.0 + g));
            sb.append("Re-run if portfolio falls below <b>")
                    .append(CURRENCY.format(trig))
                    .append("</b> (a ")
                    .append(String.format("%.0f%%", (1.0 - trig / (double) lastRunBalance) * 100))
                    .append(" drop, your ")
                    .append(String.format("%.0f%%", g * 100))
                    .append(" upper guardrail) <i>(today's $)</i>");
        }

        if (baselineSet && baselineActualWd > 0 && baselineBalance > 0 && lastRunBalance > 0) {
            // v6: THREE-way attribution. Splitting out the go-go multiplier keeps
            // a deliberate multiplier edit from masquerading as market movement --
            // raising go-go also LOWERS the sustainable base draw, because the
            // survival test spends more during the go-go window.
            double mB = (baselineGoGoMult > 0) ? baselineGoGoMult : 1.0;
            double mN = (lastRunGoGoMult  > 0) ? lastRunGoGoMult  : 1.0;
            double baseB = baselineActualWd / mB;      // base draw, multiplier removed
            double baseN = lastRunActualWd  / mN;
            double rBase = baseB / (double) baselineBalance;
            double rNow  = baseN / (double) lastRunBalance;
            // Standard two-factor decomposition of a product change:
            //   d(base * mult) = d(base) * mult_OLD + base_NEW * d(mult)
            // The portfolio and rate effects must therefore carry the OLD
            // multiplier (mB), not the new one. Using mN double-counted the
            // multiplier and the split failed to reconcile whenever the go-go
            // multiplier changed between runs (e.g. -11,658 shown against an
            // actual -10,227 when go-go went 1.25x -> 1.00x). With mB the three
            // parts sum exactly to the total change in every case.
            double balEff  = (lastRunBalance - baselineBalance) * rBase * mB;
            double rateEff = lastRunBalance * (rNow - rBase) * mB;
            double multEff = baseN * (mN - mB);
            double totChg  = lastRunActualWd - (double) baselineActualWd;
            double pct     = (baselineActualWd != 0) ? totChg / baselineActualWd * 100.0 : 0;
            double minChg  = (spMinChangePct != null) ? dv(spMinChangePct) : 5.0;
            boolean multChanged = Math.abs(mN - mB) > 0.001;
            int     horizonDelta = baselineHorizon - lastRunHorizon;

            sb.append("<br>vs baseline of ").append(baselineDate).append(": ")
                    .append(CURRENCY.format(baselineActualWd)).append(" -> <b>")
                    .append(CURRENCY.format(lastRunActualWd)).append("</b> (")
                    .append(String.format("%+.1f%%", pct)).append(")");

            // The threshold governs the ACTION language only. The explanation is
            // always shown: a deliberate input edit is not noise, whatever its size.
            if (Math.abs(pct) < minChg) {
                sb.append(" -- <i>no material change (under ")
                        .append(String.format("%.0f%%", minChg)).append(")</i>");
            }
            sb.append("<br>&nbsp;&nbsp;portfolio ")
                    .append(String.format("%+.1f%%", (lastRunBalance / (double) baselineBalance - 1) * 100))
                    .append(" (").append(signed(balEff)).append("); ")
                    .append(horizonDelta > 0
                            ? "horizon " + horizonDelta + " fewer year(s) to fund"
                            : (horizonDelta < 0 ? "horizon lengthened"
                            : "rate change (assumptions)"))
                    .append(" (").append(signed(rateEff)).append(")");
            if (multChanged) {
                sb.append("; <b>go-go ")
                        .append(String.format("%.2fx -> %.2fx", mB, mN))
                        .append("</b> (").append(signed(multEff)).append(")")
                        .append(mN < mB ? " -- planned, not distress" : " -- your edit");
            }
        } else {
            sb.append("<br><i>No annual baseline set -- press "
                    + "'Set as annual baseline' to enable year-over-year comparison.</i>");
        }
        sb.append("</html>");
        lblBaseline.setText(sb.toString());
    }
    private static String signed(double v) {
        return (v >= 0 ? "+" : "-") + CURRENCY.format((long) Math.abs(v));
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
            lblManAge.setText("User age: "   + computeAge(iv(spManBirthYear),   iv(spManBirthMonth)));
            lblWomanAge.setText("Spouse age: " + computeAge(iv(spWomanBirthYear), iv(spWomanBirthMonth)));
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
            boolean single = (cmbFilingStatus != null && cmbFilingStatus.getSelectedIndex() == 1);
            long userTotal = (long)iv(spManTradIRA) + iv(spManRothIRA)
                    + iv(spManTrad401K) + iv(spManRoth401K);
            long spouseTotal = (long)iv(spWomanRoth401K) + iv(spWomanRothIRA)
                    + iv(spWomanTradIRA) + iv(spWomanTrad401K);
            long total = userTotal + spouseTotal;

            if (single) {
                // Spouse accounts are grayed and excluded from the simulation,
                // so the total reflects only the active (User) accounts, with a
                // reminder to consolidate the decedent's balances by hand.
                lblAccountTotal.setText("Account total: " + CURRENCY.format(userTotal)
                        + "   (spouse accounts excluded -- consolidate manually)");
            } else {
                lblAccountTotal.setText("Account total: " + CURRENCY.format(total));
            }
            if (!distributing) {
                distributing = true;
                long shown = single ? userTotal : total;
                spPortfolio.setValue((int) Math.min(shown, 20_000_000));
                distributing = false;
            }
        } catch (Exception ignored) {}
    }

    private void distributePortfolioDelta() {
        if (distributing) return;
        try {
            boolean single = (cmbFilingStatus != null && cmbFilingStatus.getSelectedIndex() == 1);
            long newTotal = iv(spPortfolio);
            // In Single mode the spouse accounts are excluded, so distribute the
            // portfolio delta across the User accounts only (spouse fields stay
            // as-is, grayed and unused).
            JSpinner[] accts = single
                    ? new JSpinner[]{spManTradIRA, spManRothIRA, spManTrad401K, spManRoth401K}
                    : new JSpinner[]{spManTradIRA, spManRothIRA, spManTrad401K, spManRoth401K,
                    spWomanRoth401K, spWomanRothIRA, spWomanTradIRA, spWomanTrad401K};
            long oldTotal = 0;
            for (JSpinner s : accts) oldTotal += iv(s);
            long delta = newTotal - oldTotal;
            if (delta == 0 || oldTotal == 0) return;
            distributing = true;
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
            if (single) {
                lblAccountTotal.setText("Account total: " + CURRENCY.format(finalTotal)
                        + "   (spouse accounts excluded -- consolidate manually)");
            } else {
                lblAccountTotal.setText("Account total: " + CURRENCY.format(finalTotal));
            }
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
                            + "Fan: %,d paths x %d yrs x %d iters x %,d paths = <b>%,dM sims</b><br>"
                            + "Median: %d yrs x %d iters x %,d paths x avg %d remaining = <b>%,dM sims</b><br>"
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
            // Use Nimbus so custom button/table background colors render reliably
            // across platforms and packaging (uber-jar, WiX executable). The native
            // system L&F ignores custom button backgrounds on some platforms, which
            // made the Save/Load/Run buttons nearly invisible. Fall back to the
            // system L&F if Nimbus is not present on this JRE.
            try {
                boolean nimbusSet = false;
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        nimbusSet = true;
                        break;
                    }
                }
                if (!nimbusSet) {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                }
            } catch (Exception ignored) {}
            // v9: Option C splash. The frame is built, shown, and maximized FIRST;
            // once its bounds settle the frame calls showSplashScreen(this) so the
            // splash can be centered on the frame's ACTUAL rectangle -- which puts
            // it on whatever monitor the app opened on, with no cross-monitor jump.
            // Consequence by design: the app is briefly visible before the splash
            // appears over its center. The trigger lives in the constructor.
            new IncomeLab_OptSocSec_v10();
        });
    }

    // v9 Option C: a chromeless 5-second branded splash (JWindow -- no title bar,
    // no buttons), centered on the OWNER FRAME's actual rectangle so it lands on
    // whatever monitor the app opened on. The frame calls this after its bounds
    // settle. Dismisses at 5s, or immediately on any click or key press; a
    // one-shot guard makes an early click near the 5s mark safe.
    private static void showSplashScreen(JFrame owner) {
        final javax.swing.JWindow splash = new javax.swing.JWindow(owner);

        // Subtle background tint + a thin border so the chromeless window reads as
        // intentional against whatever is on the desktop behind it.
        JPanel content = new JPanel(new java.awt.GridBagLayout());
        content.setBackground(new Color(244, 247, 243));   // subtle warm-neutral tint
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(120, 140, 120), 1),   // thin border
                BorderFactory.createEmptyBorder(40, 64, 40, 64)));

        JLabel title = new JLabel("Personal Retirement Spending Tool");
        title.setFont(new Font("SansSerif", Font.BOLD, 23));
        title.setForeground(new Color(30, 60, 40));
        title.setHorizontalAlignment(SwingConstants.CENTER);

        JLabel copyright = new JLabel("Copyright 2026 Robert Lemm");
        copyright.setFont(new Font("SansSerif", Font.PLAIN, 12));
        copyright.setForeground(new Color(90, 100, 90));
        copyright.setHorizontalAlignment(SwingConstants.CENTER);

        // Title, then two lines of vertical gap, then the smaller copyright line.
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.gridx = 0; gc.gridy = 0; gc.anchor = java.awt.GridBagConstraints.CENTER;
        content.add(title, gc);
        gc.gridy = 1; gc.insets = new java.awt.Insets(
                2 * copyright.getFontMetrics(copyright.getFont()).getHeight(), 0, 0, 0);
        content.add(copyright, gc);

        splash.setContentPane(content);
        splash.pack();
        // Center on the OWNER FRAME's realized rectangle -- this carries the
        // monitor origin offset, so the splash lands centered on the app on
        // whatever monitor it opened on. Fall back to primary-screen center only if
        // the owner has no sensible bounds yet.
        java.awt.Rectangle fb = owner.getBounds();
        if (fb != null && fb.width > 0 && fb.height > 0) {
            int cx = fb.x + fb.width  / 2;
            int cy = fb.y + fb.height / 2;
            splash.setLocation(cx - splash.getWidth() / 2, cy - splash.getHeight() / 2);
        } else {
            splash.setLocationRelativeTo(null);
        }
        splash.setAlwaysOnTop(true);          // stay above the frame it is centered on

        // One-shot dismissal, guarded so timer-and-click can't both dispose.
        final boolean[] dismissed = { false };
        final Runnable dismiss = () -> {
            if (dismissed[0]) return;
            dismissed[0] = true;
            splash.setVisible(false);
            splash.dispose();
        };

        // Auto-close at 5 seconds.
        javax.swing.Timer timer = new javax.swing.Timer(5000, e -> dismiss.run());
        timer.setRepeats(false);

        // Early dismiss on any click or key press anywhere on the splash.
        splash.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                timer.stop(); dismiss.run();
            }
        });
        content.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                timer.stop(); dismiss.run();
            }
        });
        // Key handling: the JWindow can be focusable so a key press dismisses too.
        splash.setFocusableWindowState(true);
        splash.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                timer.stop(); dismiss.run();
            }
        });

        splash.setVisible(true);
        splash.requestFocus();
        timer.start();
    }
}
