package org.apache.nifi.processors.mqtt;

import org.apache.nifi.components.AllowableValue;

public class MqttNiFiConstants {
    public static final AllowableValue ALLOWABLE_VALUE_CLEAN_SESSION_TRUE =
            new AllowableValue("true", "Clean Session", "Fresh start, ignore any outstanding QoS 1 & 2 messages");
    public static final AllowableValue ALLOWABLE_VALUE_CLEAN_SESSION_FALSE =
            new AllowableValue("false", "Resume Session", "Pick up a previous session state - works in conjunction with a ClientID");

    public static final AllowableValue ALLOWABLE_VALUE_QOS_0 =
            new AllowableValue("0", "QoS 0", "TODO");
    public static final AllowableValue ALLOWABLE_VALUE_QOS_1 =
            new AllowableValue("1", "QoS 1", "TODO");
    public static final AllowableValue ALLOWABLE_VALUE_QOS_2 =
            new AllowableValue("2", "QoS 2", "TODO");


}
