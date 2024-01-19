package io.moquette.persistence;

import io.moquette.broker.SessionRegistry;
import io.moquette.broker.subscriptions.Topic;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttQoS;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

class SegmentedPeristentQueueSerDes {

    private enum MessageType {PUB_REL_MARKER, PUBLISHED_MESSAGE}

    public ByteBuffer toBytes(SessionRegistry.EnqueuedMessage message) {
        final int memorySize = getMemory(message);
        final ByteBuffer payload = ByteBuffer.allocate(memorySize);
        payload.mark();
        write(message, payload);
        payload.reset();
        return payload;
    }

    private void write(SessionRegistry.EnqueuedMessage obj, ByteBuffer buff) {
        if (obj instanceof SessionRegistry.PublishedMessage) {
            buff.put((byte) MessageType.PUBLISHED_MESSAGE.ordinal());

            final SessionRegistry.PublishedMessage casted = (SessionRegistry.PublishedMessage) obj;
            buff.put((byte) casted.getPublishingQos().value());

            final String topic = casted.getTopic().toString();

            writeTopic(buff, topic);
            writePayload(buff, casted.getPayload());
            if (EnqueuedMessageValueType.hasProperties(casted)) {
                buff.put((byte) 1); // there are properties
                writeProperties(buff, casted.getMqttProperties());
            } else {
                buff.put((byte) 0); // there aren't properties
            }
        } else if (obj instanceof SessionRegistry.PubRelMarker) {
            buff.put((byte) MessageType.PUB_REL_MARKER.ordinal());
        } else {
            throw new IllegalArgumentException("Unrecognized message class " + obj.getClass());
        }
    }

    private void writePayload(ByteBuffer target, ByteBuf source) {
        final int payloadSize = source.readableBytes();
        byte[] rawBytes = new byte[payloadSize];
        final int pinPoint = source.readerIndex();
        source.readBytes(rawBytes).release();
        source.readerIndex(pinPoint);
        target.putInt(payloadSize);
        target.put(rawBytes);
    }

    private void writeTopic(ByteBuffer buff, String topic) {
        writeString(buff, topic);
    }

    private void writeString(ByteBuffer buff, String value) {
        final byte[] topicBytes = value.getBytes(StandardCharsets.UTF_8);
        buff.putInt(topicBytes.length).put(topicBytes);
    }

    private void writeProperties(ByteBuffer dest, MqttProperties.MqttProperty[] properties) {
        dest.putInt(properties.length); // store property list size
        for (MqttProperties.MqttProperty property : properties) {
            writeProperty(dest, property);
        }
    }

    private void writeProperty(ByteBuffer dest, MqttProperties.MqttProperty property) {
        if (property instanceof MqttProperties.StringProperty) {
            MqttProperties.StringProperty stringProp = (MqttProperties.StringProperty) property;
            writePropertyType(dest, PropertyDataType.MqttPropertyEnum.STRING);
            dest.putInt(stringProp.propertyId());
            writeString(dest, stringProp.value());
        } else if (property instanceof MqttProperties.IntegerProperty) {
            MqttProperties.IntegerProperty intProp = (MqttProperties.IntegerProperty) property;
            writePropertyType(dest, PropertyDataType.MqttPropertyEnum.INTEGER);
            dest.putInt(intProp.propertyId());
            dest.putInt(intProp.value());
        } else if (property instanceof MqttProperties.BinaryProperty) {
            MqttProperties.BinaryProperty byteArrayProp = (MqttProperties.BinaryProperty) property;
            writePropertyType(dest, PropertyDataType.MqttPropertyEnum.BINARY);
            writeByteArray(dest, byteArrayProp.value());
        }
        // TODO UserProperties and UserProperty?
    }

    private void writeByteArray(ByteBuffer dest, byte[] payload) {
        dest.putInt(payload.length);
        dest.put(payload);
    }

    private static void writePropertyType(ByteBuffer dest, PropertyDataType.MqttPropertyEnum mqttPropertyEnum) {
        dest.put((byte) mqttPropertyEnum.ordinal());
    }

    private int getMemory(SessionRegistry.EnqueuedMessage obj) {
        if (obj instanceof SessionRegistry.PubRelMarker) {
            return 1;
        }
        final SessionRegistry.PublishedMessage casted = (SessionRegistry.PublishedMessage) obj;
        int propertiesSize = EnqueuedMessageValueType.hasProperties(casted) ?
            propertiesMemorySize(casted.getMqttProperties()) :
            0;
        return 1 + // message type
            1 + // qos
            topicMemorySize(casted.getTopic()) +
            payloadMemorySize(casted.getPayload()) +
            1 +  // flag to indicate if there are MQttProperties or not
            propertiesSize;
    }

    private int payloadMemorySize(ByteBuf payload) {
        return 4 + // size
            payload.readableBytes();
    }

    private int topicMemorySize(Topic topic) {
        return 4 + // size
            topic.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private int propertiesMemorySize(MqttProperties.MqttProperty[] properties) {
        return 4 + // integer containing the number of properties
            Arrays.stream(properties).mapToInt(SegmentedPeristentQueueSerDes::propertyMemorySize).sum();
    }

    private static int propertyMemorySize(MqttProperties.MqttProperty property) {
        int propSize = 4; // propertyId
        if (property instanceof MqttProperties.StringProperty) {
            MqttProperties.StringProperty stringProp = (MqttProperties.StringProperty) property;
            propSize += stringMemorySize(stringProp.value());
        } else if (property instanceof MqttProperties.IntegerProperty) {
            propSize += 4; // integer is 4 bytes
        } else if (property instanceof MqttProperties.BinaryProperty) {
            MqttProperties.BinaryProperty byteArrayProp = (MqttProperties.BinaryProperty) property;
            propSize += byteArrayMemorySize(byteArrayProp.value());
        }
        return 1 + // property type
            propSize;
    }

    private static int stringMemorySize(String value) {
        return 4 + // size
            value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int byteArrayMemorySize(byte[] payload) {
        return 4 + // size
            payload.length;
    }

    public SessionRegistry.EnqueuedMessage fromBytes(ByteBuffer buff) {
        final byte messageType = buff.get();
        if (messageType == MessageType.PUB_REL_MARKER.ordinal()) {
            return new SessionRegistry.PubRelMarker();
        } else if (messageType == MessageType.PUBLISHED_MESSAGE.ordinal()) {
            final MqttQoS qos = MqttQoS.valueOf(buff.get());
            final String topicStr = readTopic(buff);
            final ByteBuf payload = readPayload(buff);
            if (SerdesUtils.containsProperties(buff)) {
                MqttProperties.MqttProperty[] mqttProperties = readProperties(buff);
                return new SessionRegistry.PublishedMessage(Topic.asTopic(topicStr), qos, payload, false, mqttProperties);
            } else {
                return new SessionRegistry.PublishedMessage(Topic.asTopic(topicStr), qos, payload, false);
            }
        } else {
            throw new IllegalArgumentException("Can't recognize record of type: " + messageType);
        }
    }

    private MqttProperties.MqttProperty[] readProperties(ByteBuffer buff) {
        return SerdesUtils.readProperties(buff, buffer -> readProperty(buff));
    }

    private MqttProperties.MqttProperty readProperty(ByteBuffer buff) {
        return SerdesUtils.readSingleProperty(buff, this::readByteArray);
    }

    private String readTopic(ByteBuffer buff) {
        final int stringLen = buff.getInt();
        final byte[] rawString = new byte[stringLen];
        buff.get(rawString);
        return new String(rawString, StandardCharsets.UTF_8);
    }

    private ByteBuf readPayload(ByteBuffer buff) {
        return Unpooled.wrappedBuffer(readByteArray(buff));
    }

    private byte[] readByteArray(ByteBuffer buff) {
        final int payloadSize = buff.getInt();
        byte[] payload = new byte[payloadSize];
        buff.get(payload);
        return payload;
    }
}
