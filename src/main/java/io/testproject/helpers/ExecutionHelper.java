package io.testproject.helpers;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import hudson.AbortException;
import hudson.FilePath;
import io.testproject.constants.Constants;
import io.testproject.constants.ExecutionType;
import io.testproject.model.ExecutionStateResponseData;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


public class ExecutionHelper {

    private String projectId;
    private String itemId;
    private String agentId;
    private String browser;
    private String device;
    private String executionParameters;
    private int waitToFinishSeconds;
    private ExecutionType executionType;
    private String junitResultsFile;
    private FilePath filePath;
    private ApiHelper apiHelper;

    private boolean aborting;
    private Timer stateTimer;

    /**
     * Constructor for 'RunTest' build step
     * @param projectId The ID of the project
     * @param itemId The ID of the test/job
     * @param agentId The ID of the agent
     * @param browser The browser name to execute the test on
     * @param device The device UDID to execute the test on
     * @param executionParameters Execution parameters to override the defaults
     * @param waitToFinishSeconds How much to wait for the step to finish
     * @param executionType The type of the execution (TEST/JOB)
     * @param junitResultsFile The path to the JUnit report file
     * @param filePath An instance of FilePath
     * @param apiHelper An instance of ApiHelper
     */
    public ExecutionHelper(
            String projectId,
            String itemId,
            String agentId,
            String browser,
            String device,
            String executionParameters,
            int waitToFinishSeconds,
            ExecutionType executionType,
            String junitResultsFile,
            FilePath filePath,
            ApiHelper apiHelper) {
        this.projectId = projectId;
        this.itemId = itemId;
        this.agentId = agentId;
        this.browser = browser;
        this.device = device;
        this.executionParameters = executionParameters;
        this.waitToFinishSeconds = waitToFinishSeconds;
        this.executionType = executionType;
        this.junitResultsFile = junitResultsFile;
        this.filePath = filePath;
        this.apiHelper = apiHelper;
    }

    /**
     * Constructor for 'RunJob' build step
     * @param projectId The ID of the project
     * @param itemId The ID of the test/job
     * @param agentId The ID of the agent
     * @param executionParameters Execution parameters to override the defaults
     * @param waitToFinishSeconds How much to wait for the step to finish
     * @param executionType The type of the execution (TEST/JOB)
     * @param junitResultsFile The path to the JUnit report file
     * @param filePath An instance of FilePath
     * @param apiHelper An instance of ApiHelper
     */
    public ExecutionHelper(
            String projectId,
            String itemId,
            String agentId,
            String executionParameters,
            int waitToFinishSeconds,
            ExecutionType executionType,
            String junitResultsFile,
            FilePath filePath,
            ApiHelper apiHelper) {
        this.projectId = projectId;
        this.itemId = itemId;
        this.agentId = agentId;
        this.executionParameters = executionParameters;
        this.waitToFinishSeconds = waitToFinishSeconds;
        this.executionType = executionType;
        this.junitResultsFile = junitResultsFile;
        this.filePath = filePath;
        this.apiHelper = apiHelper;
    }

    public JsonObject generateRequestBody() throws AbortException {
        JsonObject executionData = null;

        try {
            executionData = SerializationHelper.fromJson(executionParameters, JsonObject.class);
            if (executionData == null)
                executionData = new JsonObject();
        } catch (JsonSyntaxException e) {
            throw new AbortException(e.getMessage());
        }

        if (executionType == ExecutionType.JOB) {
            // In case the user has selected an agent from the dropdown list, use it in the executionParameters object
            if (!StringUtils.isEmpty(agentId))
                executionData.addProperty("agentId", agentId);
        } else {
            if (!StringUtils.isEmpty(agentId))
                executionData.addProperty("agentId", agentId);

            if (!StringUtils.isEmpty(browser))
                executionData.addProperty("browser", browser);

            if (!StringUtils.isEmpty(device))
                executionData.addProperty("device", device);
        }

        return executionData;
    }

    public void waitForItemFinish(String executionId) throws IOException, InterruptedException {
        if (waitToFinishSeconds == 0) {
            LogHelper.Info("Will not wait for execution to finish");
            LogHelper.Info(String.format("%s %s under project %s was started successfully", executionType.toString(), itemId, projectId));
            return;
        }

        Calendar itemTimeout = Calendar.getInstance();
        itemTimeout.add(Calendar.SECOND, waitToFinishSeconds);
        LogHelper.Info(String.format("Will wait %s seconds for execution to finish (not later than %s)", waitToFinishSeconds, itemTimeout.getTime().toString()));

        // Waiting for execution to finish
        final ExecutionStateResponseData[] executionState = {null};
        final CountDownLatch latch = new CountDownLatch(1);

        stateTimer = new Timer();
        TimerTask stateCheckTask = new TimerTask() {
            public void run() {
                try {
                    if (latch.getCount() == 0) {
                        cancel(); // Timeout reached, canceling timer
                        return;
                    }

                    LogHelper.Debug("Checking execution state...");
                    executionState[0] = checkExecutionState(executionId);

                    if (executionState[0] != null) {
                        if (executionState[0].hasFinished()) {
                            LogHelper.Info("Execution has finished - state: " + executionState[0].getState());
                            latch.countDown(); // Releasing the latch
                        } else {
                            LogHelper.Debug(String.format("%s agent is still executing the %s %s", executionState[0].getAgent(), executionType, (executionState[0].getTarget() != null ? " on " + executionState[0].getTarget() : "")));
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    cancel();
                }
            }
        };

        stateTimer.scheduleAtFixedRate(stateCheckTask, 5000, 3000); // Will check for execution state every 3 seconds starting after 5 seconds
        boolean tpItemCompleted = latch.await(waitToFinishSeconds, TimeUnit.SECONDS); // Waiting for timeout or the latch to reach 0
        stateTimer.cancel();
        LogHelper.Debug("Latch result: " + tpItemCompleted);

        if (!tpItemCompleted || executionState[0] == null || !executionState[0].hasFinished()) {
            throw new AbortException("The execution did not finish within the defined time frame");
        }

        if (tpItemCompleted && !StringUtils.isEmpty(junitResultsFile)) {
            LogHelper.Info(String.format("Generating an XML report for execution '%s'", executionId));
            File outputFile = getJUnitFilePath(filePath);

            if (outputFile == null)
                return;

            if (!getJUnitXMLReport(outputFile, filePath, executionId))
                LogHelper.Info(String.format("Failed to generate a JUnit XML report for execution '%s'", executionId));
        }

        if (!executionState[0].getReport().isEmpty()) {
            LogHelper.Info("Report: " + executionState[0].getReport());
        }

        if (executionState[0].hasFinishedWithErrors()) {
            String error = executionState[0].getMessage();

            throw new AbortException("The execution has finish with errors" + (error != null ? ": " + error : ""));
        }

        LogHelper.Info("The execution has finished successfully!");
    }

    private ExecutionStateResponseData checkExecutionState(String executionId) throws IOException {
        String url = executionType == ExecutionType.JOB
                ? Constants.TP_CHECK_EXECUTION_STATE_URL
                : Constants.TP_CHECK_TEST_EXECUTION_STATE_URL;

        ApiResponse<ExecutionStateResponseData> response = apiHelper.Get(
                String.format(url, projectId, itemId, executionId), ExecutionStateResponseData.class);

        if (response.isSuccessful()) {
            if (response.getData() != null) {
                return response.getData();
            }
        }

        throw new AbortException("Unable to get execution state!");
    }

    public void abortExecution(String executionId) {

        if (aborting) // If already aborting
            return;

        aborting = true;
        LogHelper.Info("Aborting TestProject execution: " + executionId + "...");

        if (stateTimer != null) // Canceling the state stateTimer
            stateTimer.cancel();

        try {
            String url = executionType == ExecutionType.JOB
                    ? Constants.TP_ABORT_EXECUTION_URL
                    : Constants.TP_ABORT_TEST_EXECUTION_URL;

            ApiResponse response = apiHelper.Post(String.format(url, projectId, itemId, executionId), Object.class);

            if (!response.isSuccessful()) {
                LogHelper.Info(String.format("Unable to abort TestProject %s: %s", executionType.toString(), response.getStatusCode()));
            }

            LogHelper.Info(String.format("Aborted TestProject execution: %s", executionId));

        } catch (IOException e) {
            LogHelper.Error(e);
        } finally {
            aborting = false;
        }
    }

    private File getJUnitFilePath(FilePath filePath) {
        try {
            File file = new File(junitResultsFile);
            FilePath fp = new FilePath(filePath, file.getPath());
            String fileExtension = FilenameUtils.getExtension(file.getName());

            // If it's empty, it means that the user did not specify a file name and we need to create one
            if (StringUtils.isEmpty(fileExtension)) {

                // Make sure that the directory exists
                if (!fp.exists()) {
                    LogHelper.Info(String.format("The directory '%s' does not exist", file.getPath()));
                    return null;
                }

                // Generating a unique filename
                StringBuilder sb = new StringBuilder();
                sb.append(fp.getRemote())
                        .append(File.separator)
                        .append(Constants.JUNIT_FILE_PREFIX)
                        .append(new SimpleDateFormat("dd-MM-yy-HHmm").format(new Date()))
                        .append(".xml");

                return new File(sb.toString());
            } else {
                // Check if the file extension is xml
                if (!fileExtension.equals("xml")) {
                    LogHelper.Info(String.format("Invalid file extension '%s'. Only XML format is allowed.", fileExtension));
                    return null;
                }

                // Paths will throw IOExceptions if the path is not valid
                Paths.get(file.getPath());
                return file;
            }
        } catch (Exception e) {
            LogHelper.Error(e);
            return null;
        }
    }

    private boolean getJUnitXMLReport(File outputFile, FilePath filePath, String executionId) throws IOException {
        HashMap<String, Object> headers = new HashMap<>();

        Map<String, Object> queries = new HashMap<>();
        queries.put(Constants.DETAILS, true);
        queries.put(Constants.FORMAT, Constants.FORMAT_JUNIT);

        String url = executionType == ExecutionType.JOB
                ? Constants.TP_GET_JUNIT_XML_REPORT
                : Constants.TP_GET_JUNIT_XML_TEST_REPORT;

        ApiResponse<Document> response = apiHelper.Get(
                String.format(url, projectId, itemId, executionId),
                headers,
                queries,
                Document.class);

        if (response.isSuccessful()) {
            if (response.getData() != null) {
                try {
                    XPath xPath = XPathFactory.newInstance().newXPath();
                    NodeList nodeList = (NodeList) xPath.evaluate("//text()[normalize-space()='']", response.getData(), XPathConstants.NODESET);

                    for (int i = 0; i < nodeList.getLength(); ++i) {
                        Node node = nodeList.item(i);
                        node.getParentNode().removeChild(node);
                    }

                    Transformer transformer = TransformerFactory.newInstance().newTransformer();
                    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
                    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

                    StringWriter stringWriter = new StringWriter();
                    StreamResult streamResult = new StreamResult(stringWriter);

                    transformer.transform(new DOMSource(response.getData()), streamResult);

                    FilePath fp = new FilePath(filePath, outputFile.getPath());
                    fp.write(stringWriter.toString(), "UTF-8");

                    LogHelper.Info(String.format("JUnit XML report for execution '%s' was stored in '%s'", executionId, fp.getRemote()));
                } catch (Exception e) {
                    LogHelper.Error(e);
                    return false;
                }

                return true;
            }
        }

        return false;
    }
}
