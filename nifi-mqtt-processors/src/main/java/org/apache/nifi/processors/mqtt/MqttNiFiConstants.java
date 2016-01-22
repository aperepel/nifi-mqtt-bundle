package org.apache.nifi.processors.mqtt;

import org.apache.nifi.components.AllowableValue;

public class MqttNiFiConstants {
    public static final AllowableValue ALLOWABLE_VALUE_CLEAN_SESSION_TRUE =
            new AllowableValue("true", "Clean Session", "Fresh start, ignore any outstanding QoS 1 & 2 messages");
    public static final AllowableValue ALLOWABLE_VALUE_CLEAN_SESSION_FALSE =
            new AllowableValue("false", "Resume Session", "Pick up a previous session state - works in conjunction with a ClientID");

    public static final AllowableValue ALLOWABLE_VALUE_QOS_0 =
            new AllowableValue("0", "0 - At most once", "Best effort delivery. A message won’t be acknowledged by the receiver or stored and redelivered by the sender. " +
                                                        "This is often called “fire and forget” and provides the same guarantee as the underlying TCP protocol.");
    public static final AllowableValue ALLOWABLE_VALUE_QOS_1 =
            new AllowableValue("1", "1 - At least once", "Guarantees that a message will be delivered at least once to the receiver. " +
                                                         "The message can also be delivered more than once");
    public static final AllowableValue ALLOWABLE_VALUE_QOS_2 =
            new AllowableValue("2", "2 - Exactly once", "Guarantees that each message is received only once by the counterpart. It is the safest and also " +
                                                        "the slowest quality of service level. The guarantee is provided by two round-trip flows between sender and receiver.");
}
