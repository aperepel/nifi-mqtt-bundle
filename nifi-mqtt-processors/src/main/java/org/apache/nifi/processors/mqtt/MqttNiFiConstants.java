package org.apache.nifi.processors.mqtt;

import org.apache.nifi.components.AllowableValue;

public class MqttNiFiConstants {
    public static final AllowableValue ALLOWABLE_VALUE_CLEAN_SESSION_TRUE =
            new AllowableValue("true", "Clean Session", "Client and Server discard any previous session and start a new " +
                                                                "one. This session lasts as long as the network connection. " +
                                                                "State data associated with this session is not reused in any subsequent session");

    public static final AllowableValue ALLOWABLE_VALUE_CLEAN_SESSION_FALSE =
            new AllowableValue("false", "Resume Session", "Server resumes communications with the client based on state from " +
                                                          "the current session (as identified by the ClientID). The client and server store the session after " +
                                                          "the client and server are disconnected. After the disconnection of a session that had " +
                                                          "CleanSession set to 0, the server stores further QoS 1 and QoS 2 messages that match any " +
                                                          "subscriptions that the client had at the time of disconnection as part of the session state");

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
