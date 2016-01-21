package org.apache.nifi.processors.mqtt;

import org.apache.nifi.flowfile.attributes.FlowFileAttributeKey;

public enum MqttAttributes implements FlowFileAttributeKey {

    BROKER_URI("mqtt.broker.uri"),
    TOPIC("mqtt.topic"),
    QOS("mqtt.qos"),
    DUPLICATE("mqtt.duplicate"),
    RETAINED("mqtt.retained");

    private String key;

    MqttAttributes(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}
