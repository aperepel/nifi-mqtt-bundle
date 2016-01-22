package org.apache.nifi.processors.mqtt;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
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

@Tags({"mqtt", "listen", "get"})
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@CapabilityDescription("Subscribe to a MQTT broker topic(s)")
@SeeAlso()
@ReadsAttributes({@ReadsAttribute(attribute="", description="")})
@WritesAttributes({@WritesAttribute(attribute="", description="")})
public class GetMQTT extends AbstractProcessor {

    public static final PropertyDescriptor PROPERTY_BROKER_HOSTNAME = new PropertyDescriptor
                                                                 .Builder().name("host")
                                                                 .description("MQTT broker host")
                                                                 .required(true)
                                                                 .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                                                                 .build();

    public static final PropertyDescriptor PROPERTY_BROKER_PORT = new PropertyDescriptor
                                                                 .Builder().name("port")
                                                                 .description("MQTT broker port")
                                                                 .required(true)
                                                                 // TODO optional in fact: 1883 for non-secure, 8883 for secure
                                                                 .defaultValue("1883")
                                                                 .addValidator(StandardValidators.PORT_VALIDATOR)
                                                                 .build();

    public static final PropertyDescriptor PROPERTY_CLIENT_ID = new PropertyDescriptor
                                                                 .Builder().name("clientId")
                                                                 .description("MQTT subscribing client ID. Will be generated if not provided.")
                                                                 .required(false)
                                                                 .build();


    public static final PropertyDescriptor PROPERTY_QOS = new PropertyDescriptor
                                                                .Builder().name("qos")
                                                                .description("MQTT Quality of Service (0, 1 or 2)")
                                                                .required(true)
                                                                .defaultValue("0")
                                                                // TODO custom QoS validator
                                                                .addValidator(StandardValidators.INTEGER_VALIDATOR)
                                                                .build();


    public static final PropertyDescriptor PROPERTY_RECEIVE_BUFFER = new PropertyDescriptor
                                                                 .Builder().name("receive-buffer-count")
                                                                 .description("Max number of messages queued up by this subscriber before they get routed into NiFi. " +
                                                                                      "This is a safety measure, as events must not be queueing up under normal conditions.")
                                                                 .required(true)
                                                                 .defaultValue(String.valueOf(1000000))
                                                                 .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
                                                                 .build();

    public static final PropertyDescriptor PROPERTY_TOPIC = new PropertyDescriptor
                                                                    .Builder().name("topic")
                                                                .description("MQTT topic to subscribe to. Single-level(+) and multi-level(#) syntax supported.")
                                                                .required(true)
                                                                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
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
        descriptors.add(PROPERTY_CLIENT_ID);
        descriptors.add(PROPERTY_QOS);
        descriptors.add(PROPERTY_TOPIC);
//        descriptors.add(PROPERTY_RECEIVE_BUFFER);
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
//            connOptions.setMqttVersion(MqttConnectOptions.MQTT_VERSION_DEFAULT);
        mqttClient.setCallback(new NiFiMqttCallback());
        // TODO cleanSession property
        connOptions.setCleanSession(true);
        getLogger().info("Connecting to MQTT broker: {}", new String[] {brokerUri});

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
            // TODO switch to trace
            getLogger().info("Message arrived from topic {}. Paylod: {}", new Object[] {topic, message});
            org.apache.nifi.processors.mqtt.MqttMessage msg = PahoMqttToMsgTransformer.fromPahoMsg(topic, message);
            msgBuffer.put(msg);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // ignored, not relevant in this case
        }
    }
}
