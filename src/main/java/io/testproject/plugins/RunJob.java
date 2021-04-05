package io.testproject.plugins;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import hudson.*;
import hudson.model.*;
import hudson.tasks.*;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.testproject.constants.Constants;
import io.testproject.constants.ExecutionType;
import io.testproject.helpers.*;
import io.testproject.model.*;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnull;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Timer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RunJob extends Builder implements SimpleBuildStep {

    //region Private members
    private @Nonnull
    String projectId;

    private @Nonnull
    String jobId;

    private String executionId;
    private String agentId;
    private int waitJobFinishSeconds;
    private String executionParameters;
    private String junitResultsFile;

    private ApiHelper apiHelper;
    private ExecutionHelper executionHelper;
    //endregion

    //region Setters & Getters
    @Nonnull
    public String getProjectId() {
        return projectId;
    }

    @DataBoundSetter
    public void setProjectId(@Nonnull String projectId) {
        this.projectId = projectId;
    }

    @Nonnull
    public String getJobId() {
        return jobId;
    }

    @DataBoundSetter
    public void setJobId(@Nonnull String jobId) {
        this.jobId = jobId;
    }

    public int getWaitJobFinishSeconds() {
        return waitJobFinishSeconds;
    }

    @DataBoundSetter
    public void setWaitJobFinishSeconds(int waitJobFinishSeconds) {
        this.waitJobFinishSeconds = waitJobFinishSeconds;
    }

    public String getAgentId() {
        return agentId;
    }

    @DataBoundSetter
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getExecutionParameters() {
        return executionParameters;
    }

    @DataBoundSetter
    public void setExecutionParameters(String executionParameters) {
        this.executionParameters = executionParameters;
    }

    public String getJunitResultsFile() {
        return junitResultsFile;
    }

    @DataBoundSetter
    public void setJunitResultsFile(String junitResultsFile) {
        this.junitResultsFile = junitResultsFile;
    }

    //endregion

    //region Constructors
    public RunJob() {
        this.projectId = "";
        this.jobId = "";
        this.agentId = "";
        this.waitJobFinishSeconds = 0;
        this.executionParameters = "";
        this.junitResultsFile = "";
    }

    @DataBoundConstructor
    public RunJob(@Nonnull String projectId, @Nonnull String jobId, String agentId, int waitJobFinishSeconds, String executionParameters, String junitResultsFile) {
        this.projectId = projectId;
        this.jobId = jobId;
        this.agentId = agentId;
        this.waitJobFinishSeconds = waitJobFinishSeconds;
        this.executionParameters = executionParameters;
        this.junitResultsFile = junitResultsFile;
    }
    //endregion

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    private void init(FilePath filePath) {
        executionHelper = new ExecutionHelper(
                getProjectId(),
                getJobId(),
                getAgentId(),
                getExecutionParameters(),
                getWaitJobFinishSeconds(),
                ExecutionType.JOB,
                getJunitResultsFile(),
                filePath,
                apiHelper);
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath filePath, @Nonnull Launcher launcher, @Nonnull TaskListener taskListener) throws AbortException {
        try {
            PluginConfiguration config = PluginConfiguration.getInstance();
            this.apiHelper = new ApiHelper(config.getApiKey());
            LogHelper.SetLogger(taskListener.getLogger(), config.isVerbose());

            LogHelper.Info("Sending a job run command to TestProject");

            if (StringUtils.isEmpty(getProjectId()))
                throw new AbortException("The project id cannot be empty");

            if (StringUtils.isEmpty(getJobId()))
                throw new AbortException("The job id cannot be empty");

            triggerJob(run.getNumber(), filePath);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    private void triggerJob(Object buildNumber, FilePath filePath) throws Exception {
        try {
            init(filePath);

            String logMsg = StringUtils.isEmpty(getAgentId())
                    ? String.format("Starting TestProject job %s under project %s using the default agent...", this.jobId, this.projectId)
                    : String.format("Starting TestProject job %s under project %s using agent %s...", this.jobId, this.projectId, this.agentId);
            LogHelper.Info(logMsg);

            HashMap<String, Object> headers = new HashMap<>();
            headers.put(Constants.CI_NAME_HEADER, Constants.CI_NAME);
            headers.put(Constants.CI_BUILD_HEADER, buildNumber);

            ApiResponse<ExecutionResponseData> response = apiHelper.Post(
                    String.format(Constants.TP_RUN_JOB_URL, projectId, jobId),
                    headers,
                    null,
                    executionHelper.generateRequestBody(),
                    ExecutionResponseData.class);

            if (response.isSuccessful()) {
                if (response.getData() != null) {
                    ExecutionResponseData data = response.getData();
                    executionId = data.getId();
                    LogHelper.Info("Execution id: " + executionId);

                    executionHelper.waitForItemFinish(executionId);
                }
            } else {
                throw new AbortException(response.generateErrorMessage("Unable to trigger TestProject job"));
            }
        } catch (InterruptedException ie) {
            LogHelper.Error(ie);
            if (executionId != null) {
                executionHelper.abortExecution(executionId);
            }
        }
    }

    @Extension
    @Symbol(Constants.TP_JOB_SYMBOL)
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        public static final int defaultWaitJobFinishSeconds = Constants.DEFAULT_WAIT_TIME;

        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            req.bindJSON(this, formData);
            save();

            return super.configure(req, formData);
        }

        @Override
        public boolean isApplicable(@SuppressWarnings("rawtypes") Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return Constants.TP_JOB_DISPLAY_NAME;
        }

        public FormValidation doCheckProjectId(@QueryParameter String value) {

            if (value.isEmpty())
                return FormValidation.error("Project Id cannot be empty");

            return FormValidation.ok();
        }

        public FormValidation doCheckJobId(@QueryParameter String value) {

            if (value.isEmpty())
                return FormValidation.error("Job Id cannot be empty");

            return FormValidation.ok();
        }

        public FormValidation doCheckWaitJobFinishSeconds(@QueryParameter int value) {

            if (!(value == 0 || value >= 10))
                return FormValidation.error("Wait for job to finish must be at least 10 seconds (0 = Don't wait)");

            return FormValidation.ok();
        }

        public FormValidation doCheckExecutionParameters(@QueryParameter String value, @QueryParameter String agentId) {

            // Initial state where the user has just started to create the build step
            if (StringUtils.isEmpty(value) && StringUtils.isEmpty(agentId))
                return FormValidation.ok();

            JsonObject executionParams = null;
            try {
                executionParams = SerializationHelper.fromJson(value, JsonObject.class);

                // In case the value parameter is empty
                if (executionParams == null)
                    return FormValidation.ok();

            } catch (JsonSyntaxException e) {
                return FormValidation.error("Invalid JSON object");
            }

            // In case the user did not select agent from the dropdown and/or in the JSON object, return ok.
            if ((executionParams.get("agentId") != null && StringUtils.isEmpty(executionParams.get("agentId").getAsString())) || StringUtils.isEmpty(agentId))
                return FormValidation.ok();

            // In case the user has selected the same agentId in the dropdown and in the JSON object, no need to show a warning
            if ((executionParams.get("agentId") != null && StringUtils.equals(executionParams.get("agentId").getAsString(), agentId)))
                return FormValidation.ok();

            // In case the user has selected two different agents in the dropdown and in the JSON object, show warning
            if (executionParams.get("agentId") != null &&
                    !StringUtils.isEmpty(executionParams.get("agentId").getAsString()) &&
                    !StringUtils.isEmpty(agentId))
                return FormValidation.warning("You've selected an agent and specified a different one in executionParameters. The job will be executed on the selected agent.");

            return FormValidation.ok();
        }

        public ListBoxModel doFillProjectIdItems() {
            try {
                return DescriptorHelper.fillProjectIdItems(new ApiHelper(PluginConfiguration.getInstance().getApiKey()));
            } catch (Exception e) {
                LogHelper.Error(e);
            }

            return null;
        }

        public ListBoxModel doFillAgentIdItems() {
            HashMap<String, Object> headers = new HashMap<>();
            headers.put(Constants.ACCEPT, Constants.APPLICATION_JSON);

            ApiResponse<AgentData[]> response = null;
            try {
                response = new ApiHelper(PluginConfiguration.getInstance().getApiKey()).Get(Constants.TP_RETURN_ACCOUNT_AGENTS, headers, AgentData[].class);

                if (!response.isSuccessful()) {
                    throw new AbortException(response.generateErrorMessage("Unable to fetch the agents list"));
                }

                ListBoxModel model = new ListBoxModel();
                model.add("Select an agent to override job default", "");
                for (AgentData agent : response.getData()) {
                    if (agent.getOsType().equals("Unknown"))
                        continue;

                    model.add(agent.getAlias() + " (v" + agent.getVersion() + " on " + agent.getOsType() + ") [" + agent.getId() + "]",
                            agent.getId());
                }

                return model;
            } catch (Exception e) {
                LogHelper.Error(e);
            }

            return null;
        }

        public ListBoxModel doFillJobIdItems(@QueryParameter String projectId) {
            try {
                return DescriptorHelper.fillJobIdItems(projectId, new ApiHelper(PluginConfiguration.getInstance().getApiKey()));
            } catch (Exception e) {
                LogHelper.Error(e);
            }

            return null;
        }
    }
}