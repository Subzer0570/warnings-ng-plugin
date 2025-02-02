package io.jenkins.plugins.analysis.core.steps;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.collections.impl.factory.Sets;

import edu.hm.hafner.analysis.Severity;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;

import io.jenkins.plugins.analysis.core.model.LabelProviderFactory;
import io.jenkins.plugins.analysis.core.model.ResultAction;
import io.jenkins.plugins.analysis.core.model.StaticAnalysisLabelProvider;
import io.jenkins.plugins.analysis.core.steps.WarningChecksPublisher.AnnotationScope;
import io.jenkins.plugins.analysis.core.util.HealthDescriptor;
import io.jenkins.plugins.analysis.core.util.QualityGate;
import io.jenkins.plugins.analysis.core.util.QualityGate.QualityGateResult;
import io.jenkins.plugins.analysis.core.util.QualityGate.QualityGateType;
import io.jenkins.plugins.analysis.core.util.QualityGateEvaluator;
import io.jenkins.plugins.analysis.core.util.TrendChartType;
import io.jenkins.plugins.checks.steps.ChecksInfo;
import io.jenkins.plugins.util.LogHandler;
import io.jenkins.plugins.util.PipelineResultHandler;
import io.jenkins.plugins.util.StageResultHandler;
import io.jenkins.plugins.util.ValidationUtilities;

/**
 * Publish issues created by a static analysis build. The recorded issues are stored as a {@link ResultAction} in the
 * associated Jenkins build. If the issues report has a unique ID, then the created action will use this ID as well.
 * Otherwise, a default ID is used to publish the results. In any case, the computed ID can be overwritten by specifying
 * an ID as step parameter.
 */
@SuppressWarnings({"InstanceVariableMayNotBeInitialized", "PMD.ExcessiveImports", "PMD.ExcessivePublicCount", "PMD.DataClass", "PMD.GodClass", "PMD.TooManyFields"})
public class PublishIssuesStep extends Step implements Serializable {
    private static final long serialVersionUID = -1833335402353771148L;
    private static final ValidationUtilities VALIDATION_UTILITIES = new ValidationUtilities();

    private final List<AnnotatedReport> reports;

    private String sourceCodeEncoding = StringUtils.EMPTY;

    private boolean ignoreQualityGate = false; // by default, a successful quality gate is mandatory
    private boolean ignoreFailedBuilds = true; // by default, failed builds are ignored
    private String referenceJobName = StringUtils.EMPTY;
    private String referenceBuildId = StringUtils.EMPTY;
    private boolean failOnError = false; // by default, it should not fail on error

    private boolean skipPublishingChecks; // by default, warnings should be published to SCM platforms
    private boolean publishAllIssues; // by default, only new issues will be published

    private boolean quiet = false; // by default, logger content goes to loghandler output

    private int healthy;
    private int unhealthy;
    private Severity minimumSeverity = Severity.WARNING_LOW;

    private List<QualityGate> qualityGates = new ArrayList<>();

    private TrendChartType trendChartType = TrendChartType.AGGREGATION_TOOLS;

    private String id = StringUtils.EMPTY;
    private String name = StringUtils.EMPTY;

    /**
     * Creates a new instance of {@link PublishIssuesStep}.
     *
     * @param issues
     *         the reports to publish as {@link Action} in the {@link Job}.
     *
     * @throws IllegalArgumentException
     *         if the array of issues is {@code null} or empty
     */
    @DataBoundConstructor
    public PublishIssuesStep(@CheckForNull final List<AnnotatedReport> issues) {
        super();

        if (issues == null) {
            reports = new ArrayList<>();
        }
        else {
            reports = new ArrayList<>(issues);
        }
    }

    public List<AnnotatedReport> getIssues() {
        return new ArrayList<>(reports);
    }

    /**
     * Defines the ID of the results. The ID is used as URL of the results and as name in UI elements. If no ID is
     * given, then the ID of the associated result object is used.
     *
     * @param id
     *         the ID of the results
     */
    @DataBoundSetter
    public void setId(final String id) {
        VALIDATION_UTILITIES.ensureValidId(id);

        this.id = id;
    }

    public String getId() {
        return id;
    }

    /**
     * Defines the name of the results. The name is used for all labels in the UI. If no name is given, then the name of
     * the associated {@link StaticAnalysisLabelProvider} is used.
     *
     * @param name
     *         the name of the results
     */
    @DataBoundSetter
    public void setName(final String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * Determines whether to fail the step on errors during the step of recording issues.
     *
     * @param failOnError
     *         if {@code true} then the build will be failed on errors, {@code false} then errors are only reported in
     *         the UI
     */
    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setFailOnError(final boolean failOnError) {
        this.failOnError = failOnError;
    }

    @SuppressWarnings({"PMD.BooleanGetMethodName", "WeakerAccess"})
    public boolean getFailOnError() {
        return failOnError;
    }

    /**
     * Returns whether publishing checks should be skipped.
     *
     * @return {@code true} if publishing checks should be skipped, {@code false} otherwise
     */
    public boolean isSkipPublishingChecks() {
        return skipPublishingChecks;
    }

    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setSkipPublishingChecks(final boolean skipPublishingChecks) {
        this.skipPublishingChecks = skipPublishingChecks;
    }

    /**
     * Returns whether all issues should be published using the Checks API. If set to {@code false} only new issues will
     * be published.
     *
     * @return {@code true} if all issues should be published, {@code false} if only new issues should be published
     */
    public boolean isPublishAllIssues() {
        return publishAllIssues;
    }

    @DataBoundSetter
    public void setPublishAllIssues(final boolean publishAllIssues) {
        this.publishAllIssues = publishAllIssues;
    }

    /**
     * If {@code true}, then the result of the quality gate is ignored when selecting a reference build. This option is
     * disabled by default so a failing quality gate will be passed from build to build until the original reason for
     * the failure has been resolved.
     *
     * @param ignoreQualityGate
     *         if {@code true} then the result of the quality gate is ignored, otherwise only build with a successful
     *         quality gate are selected
     */
    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setIgnoreQualityGate(final boolean ignoreQualityGate) {
        this.ignoreQualityGate = ignoreQualityGate;
    }

    @SuppressWarnings({"PMD.BooleanGetMethodName", "WeakerAccess"})
    public boolean getIgnoreQualityGate() {
        return ignoreQualityGate;
    }

    /**
     * If {@code true}, then only successful or unstable reference builds will be considered. This option is enabled by
     * default, since analysis results might be inaccurate if the build failed. If {@code false}, every build that
     * contains a static analysis result is considered, even if the build failed.
     *
     * @param ignoreFailedBuilds
     *         if {@code true} then a stable build is used as reference
     */
    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setIgnoreFailedBuilds(final boolean ignoreFailedBuilds) {
        this.ignoreFailedBuilds = ignoreFailedBuilds;
    }

    @SuppressWarnings({"PMD.BooleanGetMethodName", "WeakerAccess"})
    public boolean getIgnoreFailedBuilds() {
        return ignoreFailedBuilds;
    }

    /**
     * If {@code true}, then logger content is muted
     * If {@code false}, then logger content goes to loghandler output.
     *
     * @param quiet
     *         if {@code true} then logger content is muted.
     */
    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setQuiet(final boolean quiet) {
        this.quiet = quiet;
    }

    public boolean isQuiet() {
        return quiet;
    }

    /**
     * Sets the reference job to get the results for the issue difference computation.
     *
     * @param referenceJobName
     *         the name of reference job
     */
    @DataBoundSetter
    public void setReferenceJobName(final String referenceJobName) {
        if (IssuesRecorder.NO_REFERENCE_DEFINED.equals(referenceJobName)) {
            this.referenceJobName = StringUtils.EMPTY;
        }
        this.referenceJobName = referenceJobName;
    }

    /**
     * Returns the reference job to get the results for the issue difference computation. If the job is not defined,
     * then {@link IssuesRecorder#NO_REFERENCE_DEFINED} is returned.
     *
     * @return the name of reference job, or {@link IssuesRecorder#NO_REFERENCE_DEFINED} if undefined
     */
    public String getReferenceJobName() {
        if (StringUtils.isBlank(referenceJobName)) {
            return IssuesRecorder.NO_REFERENCE_DEFINED;
        }
        return referenceJobName;
    }

    /**
     * Sets the reference build id of the reference job for the issue difference computation.
     *
     * @param referenceBuildId
     *         the build id of the reference job
     */
    @DataBoundSetter
    public void setReferenceBuildId(final String referenceBuildId) {
        if (IssuesRecorder.NO_REFERENCE_DEFINED.equals(referenceBuildId)) {
            this.referenceBuildId = StringUtils.EMPTY;
        }
        else {
            this.referenceBuildId = referenceBuildId;
        }
    }

    /**
     * Returns the reference build id of the reference job to get the results for the issue difference computation.
     * If the build id is not defined, then {@link IssuesRecorder#NO_REFERENCE_DEFINED} is returned.
     *
     * @return the reference build id, or {@link IssuesRecorder#NO_REFERENCE_DEFINED} if undefined
     */
    public String getReferenceBuildId() {
        if (StringUtils.isBlank(referenceBuildId)) {
            return IssuesRecorder.NO_REFERENCE_DEFINED;
        }
        return referenceBuildId;
    }

    @CheckForNull
    public String getSourceCodeEncoding() {
        return sourceCodeEncoding;
    }

    /**
     * Sets the encoding to use to read source files.
     *
     * @param sourceCodeEncoding
     *         the encoding, e.g. "ISO-8859-1"
     */
    @DataBoundSetter
    public void setSourceCodeEncoding(final String sourceCodeEncoding) {
        this.sourceCodeEncoding = sourceCodeEncoding;
    }

    public int getHealthy() {
        return healthy;
    }

    /**
     * Sets the healthy threshold, i.e. the number of issues when health is reported as 100%.
     *
     * @param healthy
     *         the number of issues when health is reported as 100%
     */
    @DataBoundSetter
    public void setHealthy(final int healthy) {
        this.healthy = healthy;
    }

    public int getUnhealthy() {
        return unhealthy;
    }

    /**
     * Sets the healthy threshold, i.e. the number of issues when health is reported as 0%.
     *
     * @param unhealthy
     *         the number of issues when health is reported as 0%
     */
    @DataBoundSetter
    public void setUnhealthy(final int unhealthy) {
        this.unhealthy = unhealthy;
    }

    @CheckForNull
    @SuppressWarnings("unused") // Used by Stapler
    public String getMinimumSeverity() {
        return minimumSeverity.getName();
    }

    @CheckForNull
    @SuppressWarnings("WeakerAccess") // Required by Stapler
    public Severity getMinimumSeverityAsSeverity() {
        return minimumSeverity;
    }

    /**
     * Sets the minimum severity to consider when computing the health report. Issues with a severity less than this
     * value will be ignored.
     *
     * @param minimumSeverity
     *         the severity to consider
     */
    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setMinimumSeverity(final String minimumSeverity) {
        this.minimumSeverity = Severity.valueOf(minimumSeverity, Severity.WARNING_LOW);
    }

    /**
     * Sets the type of the trend chart that should be shown on the job page.
     *
     * @param trendChartType
     *         the type of the trend chart to use
     */
    @DataBoundSetter
    public void setTrendChartType(final TrendChartType trendChartType) {
        this.trendChartType = trendChartType;
    }

    public TrendChartType getTrendChartType() {
        return trendChartType;
    }

    /**
     * Defines the optional list of quality gates.
     *
     * @param qualityGates
     *         the quality gates
     */
    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setQualityGates(final List<QualityGate> qualityGates) {
        this.qualityGates = qualityGates;
    }

    @SuppressWarnings("WeakerAccess") // Required by Stapler
    public List<QualityGate> getQualityGates() {
        return qualityGates;
    }

    /**
     * Appends the specified quality gates to the end of the list of quality gates.
     *
     * @param size
     *         the minimum number of issues that fails the quality gate
     * @param type
     *         the type of the quality gate
     * @param result
     *         determines whether the quality gate is a warning or failure
     */
    private void addQualityGate(final int size, final QualityGateType type, final QualityGateResult result) {
        qualityGates.add(new QualityGate(size, type, result));
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setUnstableTotalAll(final int size) {
        addQualityGate(size, QualityGateType.TOTAL, QualityGateResult.UNSTABLE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getUnstableTotalAll() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setUnstableTotalHigh(final int size) {
        addQualityGate(size, QualityGateType.TOTAL_HIGH, QualityGateResult.UNSTABLE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getUnstableTotalHigh() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setUnstableNewAll(final int size) {
        addQualityGate(size, QualityGateType.NEW, QualityGateResult.UNSTABLE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getUnstableNewAll() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setUnstableTotalNormal(final int size) {
        addQualityGate(size, QualityGateType.TOTAL_NORMAL, QualityGateResult.UNSTABLE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getUnstableTotalNormal() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setUnstableTotalLow(final int size) {
        addQualityGate(size, QualityGateType.TOTAL_LOW, QualityGateResult.UNSTABLE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getUnstableTotalLow() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setUnstableNewHigh(final int size) {
        addQualityGate(size, QualityGateType.NEW_HIGH, QualityGateResult.UNSTABLE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getUnstableNewHigh() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setUnstableNewNormal(final int size) {
        addQualityGate(size, QualityGateType.NEW_NORMAL, QualityGateResult.UNSTABLE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getUnstableNewNormal() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setUnstableNewLow(final int size) {
        addQualityGate(size, QualityGateType.NEW_LOW, QualityGateResult.UNSTABLE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getUnstableNewLow() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setFailedTotalAll(final int size) {
        addQualityGate(size, QualityGateType.TOTAL, QualityGateResult.FAILURE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getFailedTotalAll() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setFailedTotalHigh(final int size) {
        addQualityGate(size, QualityGateType.TOTAL_HIGH, QualityGateResult.FAILURE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getFailedTotalHigh() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setFailedTotalNormal(final int size) {
        addQualityGate(size, QualityGateType.TOTAL_NORMAL, QualityGateResult.FAILURE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getFailedTotalNormal() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setFailedTotalLow(final int size) {
        addQualityGate(size, QualityGateType.TOTAL_LOW, QualityGateResult.FAILURE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getFailedTotalLow() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setFailedNewAll(final int size) {
        addQualityGate(size, QualityGateType.NEW, QualityGateResult.FAILURE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getFailedNewAll() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setFailedNewHigh(final int size) {
        addQualityGate(size, QualityGateType.NEW_HIGH, QualityGateResult.FAILURE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getFailedNewHigh() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setFailedNewNormal(final int size) {
        addQualityGate(size, QualityGateType.NEW_NORMAL, QualityGateResult.FAILURE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getFailedNewNormal() {
        return 0;
    }

    /**
     * Sets the quality gate.
     *
     * @param size
     *         number of issues
     *
     * @deprecated replaced by {@link PublishIssuesStep#addQualityGate(int, QualityGate.QualityGateType,
     *         QualityGate.QualityGateResult)}
     */
    @Deprecated
    @DataBoundSetter
    public void setFailedNewLow(final int size) {
        addQualityGate(size, QualityGateType.NEW_LOW, QualityGateResult.FAILURE);
    }

    /**
     * Gets the quality gate.
     *
     * @return 0
     * @deprecated replaced by {@link PublishIssuesStep#getQualityGates()}
     */
    @Deprecated
    public int getFailedNewLow() {
        return 0;
    }

    @Override
    public StepExecution start(final StepContext stepContext) {
        return new Execution(stepContext, this);
    }

    /**
     * Actually performs the execution of the associated step.
     */
    @SuppressFBWarnings(value = "THROWS", justification = "false positive")
    static class Execution extends AnalysisExecution<ResultAction> {
        private static final long serialVersionUID = 6438321240776419897L;

        private final PublishIssuesStep step;

        /**
         * Creates a new instance of the step execution object.
         *
         * @param context
         *         context for this step
         * @param step
         *         the actual step to execute
         */
        @SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
        Execution(@NonNull final StepContext context, final PublishIssuesStep step) {
            super(context);

            if (step.reports.isEmpty()) {
                throw new IllegalArgumentException(
                        "No reports provided in publish issues step, parameter 'issues' must be set!");
            }
            this.step = step;
        }

        @Override
        protected ResultAction run() throws IOException, InterruptedException, IllegalStateException {
            QualityGateEvaluator qualityGate = new QualityGateEvaluator();
            qualityGate.addAll(new ArrayList<>(step.getQualityGates()));

            AnnotatedReport report;
            if (step.reports.size() > 1) {
                report = new AnnotatedReport(StringUtils.defaultIfEmpty(step.getId(), IssuesRecorder.DEFAULT_ID));
                report.logInfo("Aggregating reports of:");
                LabelProviderFactory factory = new LabelProviderFactory();
                for (AnnotatedReport subReport : step.reports) {
                    StaticAnalysisLabelProvider labelProvider = factory.create(subReport.getId());
                    report.logInfo("-> %s", labelProvider.getToolTip(subReport.size()));
                }
            }
            else {
                report = new AnnotatedReport(StringUtils.defaultIfEmpty(step.getId(), step.reports.get(0).getId())); // use ID from single report
            }
            report.addAll(step.reports);

            StageResultHandler statusHandler = new PipelineResultHandler(getRun(),
                    getContext().get(FlowNode.class));
            IssuesPublisher publisher = new IssuesPublisher(getRun(), report,
                    new HealthDescriptor(step.getHealthy(), step.getUnhealthy(),
                            step.getMinimumSeverityAsSeverity()), qualityGate,
                    StringUtils.defaultString(step.getName()), step.getReferenceJobName(), step.getReferenceBuildId(),
                    step.getIgnoreQualityGate(), step.getIgnoreFailedBuilds(),
                    getCharset(step.getSourceCodeEncoding()), getLogger(report), statusHandler, step.getFailOnError());
            ResultAction action = publisher.attachAction(step.getTrendChartType());

            if (!step.isSkipPublishingChecks()) {
                WarningChecksPublisher checksPublisher = new WarningChecksPublisher(action, getTaskListener(), getContext().get(ChecksInfo.class));
                checksPublisher.publishChecks(
                        step.isPublishAllIssues() ? AnnotationScope.PUBLISH_ALL_ISSUES : AnnotationScope.PUBLISH_NEW_ISSUES);
            }

            return action;
        }

        private LogHandler getLogger(final AnnotatedReport annotatedReport) throws InterruptedException {
            String toolName = new LabelProviderFactory().create(annotatedReport.getId(),
                    StringUtils.defaultString(step.getName())).getName();
            LogHandler logHandler = new LogHandler(getTaskListener(), toolName);
            logHandler.setQuiet(step.isQuiet());

            var report = annotatedReport.getReport();
            logHandler.logInfoMessages(report.getInfoMessages());
            logHandler.logErrorMessages(report.getErrorMessages());

            return logHandler;
        }
    }

    /**
     * Descriptor for this step: defines the context and the UI labels.
     */
    @Extension
    public static class Descriptor extends AnalysisStepDescriptor {
        @Override
        public Set<Class<?>> getRequiredContext() {
            return Sets.immutable.of(FlowNode.class, Run.class, TaskListener.class).castToSet();
        }

        @Override
        public String getFunctionName() {
            return "publishIssues";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.PublishIssues_DisplayName();
        }
    }
}
