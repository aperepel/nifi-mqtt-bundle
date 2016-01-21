package org.apache.nifi.processors.mqtt;

/**
 * Convert from a library-specific into a generic transportable message.
 */
public class PahoMqttToMsgTransformer {

    public static MqttMessage fromPahoMsg(String topic, org.eclipse.paho.client.mqttv3.MqttMessage paho) {
        MqttMessage msg = new MqttMessage();
        msg.setPayload(paho.getPayload());
        msg.setQos(paho.getQos());
        msg.setDuplicate(paho.isDuplicate());
        msg.setRetained(paho.isRetained());
        msg.setTopic(topic);

        return msg;
    }

}
