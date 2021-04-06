package io.testproject.model;

/**
 * Represents a mobile device of a specific agent
 */
public class AgentDevice {
    /**
     * Device UDID
     */
    public String udid;
    /**
     * Device name
     */
    public String name;
    /**
     * Device model
     */
    public String model;
    /**
     * OS type (Android/iOS)
     */
    public String osType;
    /**
     * The OS version
     */
    public String osVersion;

    public String getUdid() {
        return udid;
    }

    public void setUdid(String udid) {
        this.udid = udid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getOsType() {
        return osType;
    }

    public void setOsType(String osType) {
        this.osType = osType;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }
}
