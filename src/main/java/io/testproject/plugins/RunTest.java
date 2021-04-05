package io.testproject.plugins;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.testproject.constants.Constants;
import io.testproject.constants.ExecutionType;
import io.testproject.helpers.*;
import io.testproject.model.AgentBrowser;
import io.testproject.model.AgentData;
import io.testproject.model.AgentDevice;
import io.testproject.model.ExecutionResponseData;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.io.IOException;
import java.util.HashMap;

public class RunTest extends Builder implements SimpleBuildStep {

    //region Private members
    private ApiHelper apiHelper;
    private ExecutionHelper executionHelper;
    private String executionId;
    private String junitResultsFile;
    private int waitTestFinishSeconds;

    private @Nonnull
    String projectId;

    private @Nonnull
    String testId;

    private @Nonnull
    String agentId;

    private String browser;
    private String device;

    private @Nonnull
    String executionParameters;
    //endregion

    //region Setters & Getters
    @Nonnull
    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(@Nonnull String projectId) {
        this.projectId = projectId;
    }

    @Nonnull
    public String getTestId() {
        return testId;
    }

    public void setTestId(@Nonnull String testId) {
        this.testId = testId;
    }

    @Nonnull
    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(@Nonnull String agentId) {
        this.agentId = agentId;
    }

    @Nonnull
    public String getExecutionParameters() {
        return executionParameters;
    }

    public void setExecutionParameters(@Nonnull String executionParameters) {
        this.executionParameters = executionParameters;
    }

    public int getWaitTestFinishSeconds() {
        return waitTestFinishSeconds;
    }

    public void setWaitTestFinishSeconds(int waitTestFinishSeconds) {
        this.waitTestFinishSeconds = waitTestFinishSeconds;
    }

    public String getJunitResultsFile() {
        return junitResultsFile;
    }

    public void setJunitResultsFile(String junitResultsFile) {
        this.junitResultsFile = junitResultsFile;
    }

    public String getBrowser() {
        return browser;
    }

    public void setBrowser(String browser) {
        this.browser = browser;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }
    //endregion

    //region Constructors
    @DataBoundConstructor
    public RunTest(String junitResultsFile,
                   int waitTestFinishSeconds,
                   @Nonnull String projectId,
                   @Nonnull String testId,
                   @Nonnull String agentId,
                   String browser,
                   String device,
                   @Nonnull String executionParameters) {
        this.junitResultsFile = junitResultsFile;
        this.waitTestFinishSeconds = waitTestFinishSeconds;
        this.projectId = projectId;
        this.testId = testId;
        this.agentId = agentId;
        this.browser = browser;
        this.device = device;
        this.executionParameters = executionParameters;
    }

    public RunTest() {
        this.junitResultsFile = "";
        this.waitTestFinishSeconds = 0;
        this.projectId = "";
        this.testId = "";
        this.agentId = "";
        this.executionParameters = "";
    }
    //endregion

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath filePath, @Nonnull Launcher launcher, @Nonnull TaskListener taskListener) throws InterruptedException, IOException {
        try {
            PluginConfiguration config = PluginConfiguration.getInstance();
            this.apiHelper = new ApiHelper(config.getApiKey());
            LogHelper.SetLogger(taskListener.getLogger(), config.isVerbose());

            LogHelper.Info("Sending a test run command to TestProject");

            if (StringUtils.isEmpty(getProjectId()))
                throw new AbortException("The project id cannot be empty");

            if (StringUtils.isEmpty(getTestId()))
                throw new AbortException("The test id cannot be empty");

            if (StringUtils.isEmpty(getAgentId()))
                throw new AbortException("The agent id cannot be empty");

            triggerTest(run.getNumber(), filePath);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    private void init(FilePath filePath) {
        executionHelper = new ExecutionHelper(
                getProjectId(),
                getTestId(),
                getAgentId(),
                getBrowser(),
                getDevice(),
                getExecutionParameters(),
                getWaitTestFinishSeconds(),
                ExecutionType.TEST,
                getJunitResultsFile(),
                filePath,
                apiHelper);
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public RunTest.DescriptorImpl getDescriptor() {
        return (RunTest.DescriptorImpl) super.getDescriptor();
    }

    private void triggerTest(Object buildNumber, FilePath filePath) throws Exception {
        try {
            init(filePath);

            LogHelper.Info(String.format("Starting TestProject test %s under project %s...", testId, projectId));

            HashMap<String, Object> headers = new HashMap<>();
            headers.put(Constants.CI_NAME_HEADER, Constants.CI_NAME);
            headers.put(Constants.CI_BUILD_HEADER, buildNumber);

            ApiResponse<ExecutionResponseData> response = apiHelper.Post(
                    String.format(Constants.TP_RUN_TEST_URL, projectId, testId),
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
                throw new AbortException(response.generateErrorMessage("Unable to trigger TestProject test"));
            }
        } catch (InterruptedException ie) {
            LogHelper.Error(ie);
            if (executionId != null) {
                executionHelper.abortExecution(executionId);
            }
        }
    }

    @Extension
    @Symbol(Constants.TP_TEST_SYMBOL)
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        public static final int defaultWaitTestFinishSeconds = Constants.DEFAULT_WAIT_TIME;

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
            return Constants.TP_TEST_DISPLAY_NAME;
        }

        public FormValidation doCheckProjectId(@QueryParameter String value) {
            if (value.isEmpty())
                return FormValidation.error("Project Id cannot be empty");

            return FormValidation.ok();
        }

        public FormValidation doCheckTestId(@QueryParameter String value) {
            if (value.isEmpty())
                return FormValidation.error("Test Id cannot be empty");

            return FormValidation.ok();
        }

        public FormValidation doCheckAgentId(@QueryParameter String value) {
            if (value.isEmpty())
                return FormValidation.error("Agent Id cannot be empty");

            return FormValidation.ok();
        }

        public FormValidation doCheckWaitTestFinishSeconds(@QueryParameter int value) {
            if (!(value == 0 || value >= 10))
                return FormValidation.error("Wait for test to finish must be at least 10 seconds (0 = Don't wait)");

            return FormValidation.ok();
        }

        public FormValidation doCheckExecutionParameters(@QueryParameter String value, @QueryParameter String agentId) {
            JsonObject executionParams = null;
            try {
                executionParams = SerializationHelper.fromJson(value, JsonObject.class);

                // In case the value parameter is empty
                if (executionParams == null)
                    return FormValidation.ok();

            } catch (JsonSyntaxException e) {
                return FormValidation.error("Invalid JSON object");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckBrowser(@QueryParameter String value, @QueryParameter String device) {
            if (value.isEmpty() && device.isEmpty())
                return FormValidation.error("You must provide a browser or device to execute the test on");

            return FormValidation.ok();
        }

        public FormValidation doCheckDevice(@QueryParameter String value, @QueryParameter String browser) {
            if (value.isEmpty() && browser.isEmpty())
                return FormValidation.error("You must provide a browser or device to execute the test on");

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
                model.add("Select an agent that will execute the test", "");
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

        public ListBoxModel doFillTestIdItems(@QueryParameter String projectId) {
            try {
                return DescriptorHelper.fillTestIdItems(projectId, new ApiHelper(PluginConfiguration.getInstance().getApiKey()));
            } catch (Exception e) {
                LogHelper.Error(e);
            }

            return null;
        }

        public ListBoxModel doFillBrowserItems(@QueryParameter String agentId, @QueryParameter String device) {
            ListBoxModel res = new ListBoxModel();

            if (agentId.isEmpty()) {
                res.add("You must select an agent first!");
                return res;
            }

            if (!device.isEmpty()) {
                res.add("", "");
                return res;
            }

            HashMap<String, Object> headers = new HashMap<>();
            headers.put(Constants.ACCEPT, Constants.APPLICATION_JSON);

            ApiResponse<AgentBrowser[]> response = null;
            try {
                response = new ApiHelper(PluginConfiguration.getInstance().getApiKey()).Get(String.format(Constants.TP_GET_AGENT_BROWSERS, agentId), headers, AgentBrowser[].class);

                if (!response.isSuccessful()) {
                    throw new AbortException(response.generateErrorMessage("Unable to fetch the agent's browsers"));
                }

                ListBoxModel model = new ListBoxModel();
                model.add("Select a browser", "");
                for (AgentBrowser browser : response.getData()) {
                    model.add(browser.getType() + " (v" + browser.getVersion() + ")", browser.getType());
                }

                return model;
            } catch (Exception e) {
                LogHelper.Error(e);
            }

            return null;
        }

        public ListBoxModel doFillDeviceItems(@QueryParameter String agentId, @QueryParameter String browser) {
            ListBoxModel res = new ListBoxModel();

            if (agentId.isEmpty()) {
                res.add("You must select an agent first!");
                return res;
            }

            if (!browser.isEmpty()) {
                res.add("", "");
                return res;
            }

            HashMap<String, Object> headers = new HashMap<>();
            headers.put(Constants.ACCEPT, Constants.APPLICATION_JSON);

            ApiResponse<AgentDevice[]> response = null;
            try {
                response = new ApiHelper(PluginConfiguration.getInstance().getApiKey()).Get(String.format(Constants.TP_GET_AGENT_DEVICES, agentId), headers, AgentDevice[].class);

                if (!response.isSuccessful()) {
                    throw new AbortException(response.generateErrorMessage("Unable to fetch the agent's devices"));
                }

                ListBoxModel model = new ListBoxModel();
                model.add("Select a mobile device (make sure that you have at least one connected device to the same machine that the agent is installed on)", "");
                for (AgentDevice device : response.getData()) {
                    String displayName = String.format("%s: %s - %s (v%s) [%s]",
                            device.getOsType(),
                            device.getName(),
                            device.getModel(),
                            device.getOsVersion(),
                            device.getUdid());

                    model.add(displayName, device.getUdid());
                }

                return model;
            } catch (Exception e) {
                LogHelper.Error(e);
            }

            return null;
        }
    }
}
