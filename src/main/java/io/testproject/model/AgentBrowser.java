package io.testproject.model;

/**
 * Represents a browser of a specific agent
 */
public class AgentBrowser {
    /**
     * The type of the browser (Chrome, Firefox, etc.)
     */
    public String type;
    /**
     * The version of the browser
     */
    public String version;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
