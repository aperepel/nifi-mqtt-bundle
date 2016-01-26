package org.apache.nifi.processors.mqtt;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.TriggerSerially;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.annotation.lifecycle.OnUnscheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.apache.nifi.processors.mqtt.MqttNiFiConstants.ALLOWABLE_VALUE_CLEAN_SESSION_FALSE;
import static org.apache.nifi.processors.mqtt.MqttNiFiConstants.ALLOWABLE_VALUE_CLEAN_SESSION_TRUE;
import static org.apache.nifi.processors.mqtt.MqttNiFiConstants.ALLOWABLE_VALUE_QOS_0;
import static org.apache.nifi.processors.mqtt.MqttNiFiConstants.ALLOWABLE_VALUE_QOS_1;
import static org.apache.nifi.processors.mqtt.MqttNiFiConstants.ALLOWABLE_VALUE_QOS_2;

@Tags({"mqtt", "listen", "get"})
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@TriggerSerially // we want to have a consistent mapping between clientID and MQTT connection
@CapabilityDescription("Subscribe to a MQTT broker topic(s)")
@SeeAlso()
@ReadsAttributes({@ReadsAttribute(attribute="", description="")})
@WritesAttributes({@WritesAttribute(attribute="", description="")})
public class GetMQTT extends AbstractProcessor {

    public static final PropertyDescriptor PROPERTY_BROKER_HOSTNAME = new PropertyDescriptor
                                                                 .Builder().name("host")
                                                                 .displayName("Broker host name")
                                                                 .description("MQTT broker host")
                                                                 .required(true)
                                                                 .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                                                                 .build();

    public static final PropertyDescriptor PROPERTY_BROKER_PORT = new PropertyDescriptor
                                                                 .Builder().name("port")
                                                                 .displayName("Broker port")
                                                                 .description("MQTT protocol broker port")
                                                                 .required(true)
                                                                 // TODO optional in fact: 1883 for non-secure, 8883 for secure
                                                                 .defaultValue("1883")
                                                                 .addValidator(StandardValidators.PORT_VALIDATOR)
                                                                 .build();

    public static final PropertyDescriptor PROPERTY_BROKER_USERNAME = new PropertyDescriptor
                                                                 .Builder().name("username")
                                                                 .displayName("Username")
                                                                 .required(false)
                                                                 .defaultValue(null)
                                                                  // no validator, empty values allowed
                                                                 .addValidator(Validator.VALID)
                                                                 .build();

    public static final PropertyDescriptor PROPERTY_BROKER_PASSWORD = new PropertyDescriptor
                                                                 .Builder().name("password")
                                                                 .displayName("Password")
                                                                 .required(false)
                                                                 // no validator, empty values allowed
                                                                 .addValidator(Validator.VALID)
                                                                 .defaultValue(null)
                                                                 .sensitive(true)
                                                                 .build();


    public static final PropertyDescriptor PROPERTY_CLIENT_ID = new PropertyDescriptor
                                                                 .Builder().name("clientId")
                                                                 .displayName("Client ID")
                                                                 .description("MQTT subscribing client ID. Will be generated if not provided.")
                                                                 .required(false)
                                                                 .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                                                                 .build();


    public static final PropertyDescriptor PROPERTY_QOS = new PropertyDescriptor
                                                                .Builder().name("qos")
                                                                .displayName("Quality of Service")
                                                                .description("MQTT Quality of Service (0, 1 or 2). See individual values descriptions for complete semantics.")
                                                                .required(true)
                                                                .allowableValues(
                                                                    ALLOWABLE_VALUE_QOS_0,
                                                                    ALLOWABLE_VALUE_QOS_1,
                                                                    ALLOWABLE_VALUE_QOS_2
                                                                )
                                                                .defaultValue(ALLOWABLE_VALUE_QOS_0.getValue())
                                                                .build();

    public static final PropertyDescriptor PROPERTY_CLEAN_SESSION = new PropertyDescriptor
                                                                  .Builder().name("clean-session")
                                                                  .displayName("Session state")
                                                                  .description("Whether to start afresh or resume previous flows. See detailed descriptions for each value.")
                                                                  .required(true)
                                                                  .allowableValues(
                                                                      ALLOWABLE_VALUE_CLEAN_SESSION_TRUE,
                                                                      ALLOWABLE_VALUE_CLEAN_SESSION_FALSE
                                                                  )
                                                                  .defaultValue(ALLOWABLE_VALUE_CLEAN_SESSION_TRUE.getValue())
                                                                  .build();


    // TODO expose in the UI and find the right place to flush/re-init on value change
    public static final PropertyDescriptor PROPERTY_RECEIVE_BUFFER = new PropertyDescriptor
                                                                 .Builder().name("receive-buffer-count")
                                                                 .displayName("Receive Buffer Count")
                                                                 .description("Max number of messages queued up by this subscriber before they get routed into NiFi. " +
                                                                              "This is a safety measure, as events must not be queueing up under normal conditions.")
                                                                 .required(true)
                                                                 .defaultValue(String.valueOf(1000000))
                                                                 .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
                                                                 .build();

    public static final PropertyDescriptor PROPERTY_TOPIC = new PropertyDescriptor
                                                                .Builder().name("topic")
                                                                .displayName("Topic")
                                                                .description("MQTT topic to subscribe to. Single-level(+) and multi-level(#) syntax supported.")
                                                                .required(true)
                                                                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                                                                .build();

    public static final PropertyDescriptor PROPERTY_MQTT_VERSION = new PropertyDescriptor
                                                                .Builder().name("mqtt-version")
                                                                .displayName("MQTT Spec Version")
                                                                .description("MQTT specification version")
                                                                .allowableValues(
                                                                        MqttNiFiConstants.ALLOWABLE_VALUE_MQTT_VERSION_AUTO,
                                                                        MqttNiFiConstants.ALLOWABLE_VALUE_MQTT_VERSION_311,
                                                                        MqttNiFiConstants.ALLOWABLE_VALUE_MQTT_VERSION_310
                                                                )
                                                                .defaultValue(MqttNiFiConstants.ALLOWABLE_VALUE_MQTT_VERSION_AUTO.getValue())
                                                                .required(true)
                                                                .build();

    public static final PropertyDescriptor PROPERTY_CONN_TIMEOUT = new PropertyDescriptor
                                                                .Builder().name("connection-timeout-sec")
                                                                // TODO use idiomatic NiFi duration?
                                                                .displayName("Connection Timeout (seconds)")
                                                                .description("Maximum time interval the client will wait for the network connection to the MQTT server " +
                                                                             "to be established. The default timeout is 30 seconds. " +
                                                                             "A value of 0 disables timeout processing meaning the client will wait until the network connection is made successfully or fails.")
                                                                .required(false)
                                                                .defaultValue(String.valueOf(30))
                                                                .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
                                                                .build();

    public static final PropertyDescriptor PROPERTY_KEEPALIVE_INTERVAL = new PropertyDescriptor
                                                                 .Builder().name("keepalive-interval-sec")
                                                                 // TODO use idiomatic NiFi duration?
                                                                 .displayName("Keep Alive Interval (seconds)")
                                                                 .description("Defines the maximum time interval between messages sent or received. It enables the " +
                                                                              "client to detect if the server is no longer available, without having to wait for the TCP/IP timeout. " +
                                                                              "The client will ensure that at least one message travels across the network within each keep alive period. In the absence of a data-related message during the time period, the client sends a very small \"ping\" message, which the server will acknowledge. A value of 0 disables keepalive processing in the client.")
                                                                 .required(false)
                                                                 .defaultValue(String.valueOf(60))
                                                                 .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
                                                                 .build();

    public static final PropertyDescriptor PROPERTY_LWT_TOPIC = new PropertyDescriptor
                                                                 .Builder().name("lwt-topic")
                                                                 .displayName("'Last Will' Topic")
                                                                 .description("A 'last will' message will be stored on the server and associated with the network connection. " +
                                                                              "The will message is published when the connection is subsequently terminated for any reason other than a clean disconnect.")
                                                                 .required(false)
                                                                 .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                                                                 .build();

    public static final PropertyDescriptor PROPERTY_LWT_PAYLOAD = new PropertyDescriptor
                                                                .Builder().name("lwt-payload")
                                                                .displayName("'Last Will' Payload")
                                                                .description("Actual 'last will' message to be sent.")
                                                                .required(false)
                                                                // can be zero-length, still valid (e.g. to remove LWT message from a broker)
                                                                .addValidator(Validator.VALID)
                                                                .build();

    public static final PropertyDescriptor PROPERTY_LWT_QOS = new PropertyDescriptor
                                                                 .Builder().name("lwt-qos")
                                                                 .displayName("'Last Will' QoS Level")
                                                                 .description("QoS level to be used when publishing the Will Message")
                                                                 .required(true)
                                                                 .defaultValue(ALLOWABLE_VALUE_QOS_0.getValue())
                                                                 .allowableValues(
                                                                    ALLOWABLE_VALUE_QOS_0,
                                                                    ALLOWABLE_VALUE_QOS_1,
                                                                    ALLOWABLE_VALUE_QOS_2
                                                                 )
                                                                 .build();

    public static final PropertyDescriptor PROPERTY_LWT_RETAIN = new PropertyDescriptor
                                                                 .Builder().name("lwt-retain")
                                                                 .displayName("'Last Will' Retain")
                                                                 .description("Specifies if the Will Message is to be Retained when it is published")
                                                                 .required(true)
                                                                 .defaultValue(Boolean.TRUE.toString())
                                                                 .allowableValues(Boolean.TRUE.toString(), Boolean.FALSE.toString())
                                                                 .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
                                                                 .build();

    public static final Relationship RELATIONSHIP_SUCCESS = new Relationship.Builder()
                                                               .name("Success")
                                                               .description("Success relationship")
                                                               .build();

    public static final Relationship RELATIONSHIP_CONNECTION_FAILURE = new Relationship.Builder()
                                                                    .name("Connection failure")
                                                                    .description("Broker connectivity errors")
                                                                    .build();

    public static final Relationship RELATIONSHIP_FAILURE = new Relationship.Builder()
                                                                   .name("Failure")
                                                                   .description("Messages failed to process")
                                                                   .build();

    private List<PropertyDescriptor> descriptors;

    private Set<Relationship> relationships;
    private MqttClient mqttClient;

    private BlockingQueue<org.apache.nifi.processors.mqtt.MqttMessage> msgBuffer = new LinkedBlockingQueue<>();

    @Override
    protected void init(ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(PROPERTY_BROKER_HOSTNAME);
        descriptors.add(PROPERTY_BROKER_PORT);
        descriptors.add(PROPERTY_BROKER_USERNAME);
        descriptors.add(PROPERTY_BROKER_PASSWORD);
        descriptors.add(PROPERTY_CLIENT_ID);
        descriptors.add(PROPERTY_QOS);
        descriptors.add(PROPERTY_TOPIC);
        descriptors.add(PROPERTY_CLEAN_SESSION);
        descriptors.add(PROPERTY_MQTT_VERSION);
        descriptors.add(PROPERTY_CONN_TIMEOUT);
        descriptors.add(PROPERTY_KEEPALIVE_INTERVAL);
//        descriptors.add(PROPERTY_RECEIVE_BUFFER);
        descriptors.add(PROPERTY_LWT_TOPIC);
        descriptors.add(PROPERTY_LWT_PAYLOAD);
        descriptors.add(PROPERTY_LWT_QOS);
        descriptors.add(PROPERTY_LWT_RETAIN);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(RELATIONSHIP_SUCCESS);
        relationships.add(RELATIONSHIP_FAILURE);
        relationships.add(RELATIONSHIP_CONNECTION_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnStopped
    public void onStopped() {
        if (getLogger().isTraceEnabled()) {
            if (msgBuffer.isEmpty()) {
                getLogger().trace("(@OnStopped) Work queue is now empty");
            } else {
                getLogger().trace("(@OnStopped) Work queue still has {} items", new Object[] {msgBuffer.size()});
            }
        }

        if (mqttClient == null || !mqttClient.isConnected()) {
            return;
        }
        try {
            mqttClient.disconnect();
        } catch (MqttException e) {
            getLogger().warn("Error while disconnecting.", e);
        }
        try {
            mqttClient.close();
        } catch (MqttException e) {
            getLogger().warn("Error while closing client connection.", e);
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile flowFile = null;

        try {
            connectMqttBroker(context);

            if (msgBuffer.isEmpty()) {
                context.yield();
                return;
            }

            // avoid any size calls
            List<org.apache.nifi.processors.mqtt.MqttMessage> work = new LinkedList<>();
            msgBuffer.drainTo(work);

            if (getLogger().isTraceEnabled()) {
                getLogger().trace("Incoming work queue size: {}", new Object[] { work.size() });
            }

            String serverURI = mqttClient.getServerURI();

            for (final org.apache.nifi.processors.mqtt.MqttMessage msg : work) {
                flowFile = session.create();
                Map<String, String> attrs = new HashMap<>();
                attrs.put(MqttAttributes.BROKER_URI.key(), serverURI);
                attrs.put(MqttAttributes.TOPIC.key(), msg.getTopic());
                attrs.put(MqttAttributes.DUPLICATE.key(), String.valueOf(msg.isDuplicate()));
                attrs.put(MqttAttributes.RETAINED.key(), String.valueOf(msg.isRetained()));
                attrs.put(MqttAttributes.QOS.key(), String.valueOf(msg.getQos()));

                flowFile = session.putAllAttributes(flowFile, attrs);

                flowFile = session.write(flowFile, new OutputStreamCallback() {
                    @Override
                    public void process(OutputStream out) throws IOException {
                        out.write(msg.getPayload());
                    }
                });
                String transitUri = new StringBuilder(serverURI).append(msg.getTopic()).toString();
                session.transfer(flowFile, RELATIONSHIP_SUCCESS);
                session.getProvenanceReporter().receive(flowFile, transitUri);
            }
        } catch (MqttException e) {
            if (e.getCause() instanceof IOException) {
                getLogger().warn("Failed to receive a MQTT message", e);
                // TODO not sure it's the right thing to do in a receiver
                session.penalize(flowFile);
                session.transfer(flowFile, RELATIONSHIP_CONNECTION_FAILURE);
            } else {
                getLogger().error("Failed to process a message", e);
                session.penalize(flowFile);
                session.transfer(flowFile, RELATIONSHIP_FAILURE);
            }
        }

    }

    @OnUnscheduled
    public void onUnscheduled() {
        if (getLogger().isTraceEnabled()) {
            if (msgBuffer.isEmpty()) {
                getLogger().trace("(@OnUnscheduled) Work queue is now empty");
            } else {
                getLogger().trace("(@OnUnscheduled) Work queue still has {} items", new Object[] {msgBuffer.size()});
            }
        }
    }

    private void connectMqttBroker(ProcessContext context) throws MqttException {
        if (mqttClient != null && mqttClient.isConnected()) {
            return;
        }
        String brokerUri = String.format("tcp://%s:%d",
                context.getProperty(PROPERTY_BROKER_HOSTNAME).getValue(),
                context.getProperty(PROPERTY_BROKER_PORT).asInteger());
        String clientId = context.getProperty(PROPERTY_CLIENT_ID).getValue();
        if (StringUtils.isBlank(clientId)) {
            clientId = "NiFi-" + getIdentifier();
        }
        String topic = context.getProperty(PROPERTY_TOPIC).getValue();
        // TODO persistence
        mqttClient = new MqttClient(brokerUri, clientId, new MemoryPersistence());
        MqttConnectOptions connOptions = new MqttConnectOptions();
        connOptions.setMqttVersion(context.getProperty(PROPERTY_MQTT_VERSION).asInteger());
        String user = context.getProperty(PROPERTY_BROKER_USERNAME).getValue();
        if (user != null) {
            connOptions.setUserName(user);
            String p = context.getProperty(PROPERTY_BROKER_PASSWORD).getValue();
            if (p != null) {
                connOptions.setPassword(p.toCharArray());
            }
        }
        connOptions.setCleanSession(context.getProperty(PROPERTY_CLEAN_SESSION).asBoolean());
        connOptions.setConnectionTimeout(context.getProperty(PROPERTY_CONN_TIMEOUT).asInteger());
        connOptions.setKeepAliveInterval(context.getProperty(PROPERTY_KEEPALIVE_INTERVAL).asInteger());
        String lwtTopic = context.getProperty(PROPERTY_LWT_TOPIC).getValue();
        String lwtPayload = context.getProperty(PROPERTY_LWT_PAYLOAD).getValue();
        Integer lwtQos = context.getProperty(PROPERTY_LWT_QOS).asInteger();
        Boolean lwtRetain = context.getProperty(PROPERTY_LWT_RETAIN).asBoolean();

        if (StringUtils.isNotBlank(lwtTopic)) {
            connOptions.setWill(lwtTopic,
                                lwtPayload.getBytes(),
                                lwtQos,
                                lwtRetain);
        }

        if (getLogger().isInfoEnabled()) {
            getLogger().info("LWT: topic={}, qos={}, retain={}, payload={}",
                             new Object[] {lwtTopic, lwtQos, lwtRetain, lwtPayload});
            getLogger().info("Connecting to MQTT broker: {} Subscription: {} Client ID: {}",
                             new String[] {brokerUri, topic, clientId});
        }

        mqttClient.setCallback(new NiFiMqttCallback());
        mqttClient.connect(connOptions);
        mqttClient.subscribe(topic, context.getProperty(PROPERTY_QOS).asInteger());
    }

    private class NiFiMqttCallback implements MqttCallback {
        @Override
        public void connectionLost(Throwable cause) {
            getLogger().warn("MQTT connection lost", cause);
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) throws Exception {
            if (getLogger().isDebugEnabled()) {
                getLogger().debug("Message arrived from topic {}. Paylod: {}", new Object[] {topic, message});
            }
            org.apache.nifi.processors.mqtt.MqttMessage msg = PahoMqttToMsgTransformer.fromPahoMsg(topic, message);
            msgBuffer.put(msg);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // ignored, not relevant in this case
        }
    }
}
