package org.apache.nifi.processors.mqtt;

import java.io.Serializable;

public class MqttMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private byte[] payload;
    private int qos;
    private boolean duplicate;
    private boolean retained;
    private String topic;

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public int getQos() {
        return qos;
    }

    public void setQos(int qos) {
        this.qos = qos;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public void setDuplicate(boolean duplicate) {
        this.duplicate = duplicate;
    }

    public boolean isRetained() {
        return retained;
    }

    public void setRetained(boolean retained) {
        this.retained = retained;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }
}
