package io.moquette.persistence;

import io.moquette.broker.ISessionsRepository;
import io.netty.handler.codec.mqtt.MqttVersion;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;
import org.h2.mvstore.type.StringDataType;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Collection;

class H2SessionsRepository implements ISessionsRepository {

    private final MVMap<String, SessionData> sessionMap;

    public H2SessionsRepository(MVStore mvStore) {
        final MVMap.Builder<String, ISessionsRepository.SessionData> sessionTypeBuilder =
            new MVMap.Builder<String, ISessionsRepository.SessionData>()
                .valueType(new SessionDataValueType());

        this.sessionMap = mvStore.openMap("sessions_store", sessionTypeBuilder);
    }

    @Override
    public Collection<SessionData> list() {
        return sessionMap.values();
    }

    @Override
    public void saveSession(SessionData session) {
        sessionMap.put(session.clientId(), session);
    }

    /**
     * Codec data type to load and store SessionData instances
     */
    private final class SessionDataValueType extends BasicDataType<SessionData> {

        private final StringDataType stringDataType = new StringDataType();

        @Override
        public int getMemory(SessionData obj) {
            return stringDataType.getMemory(obj.clientId()) + 8 + 1 + 8;
        }

        @Override
        public void write(WriteBuffer buff, SessionData obj) {
            stringDataType.write(buff, obj.clientId());
            buff.putLong(obj.created().toEpochMilli());
            buff.put(obj.protocolVersion().protocolLevel());
            buff.putLong(obj.expiryInterval());
        }

        @Override
        public SessionData read(ByteBuffer buff) {
            final String clientId = stringDataType.read(buff);
            final long created = buff.getLong();
            final byte rawVersion = buff.get();
            final MqttVersion version;
            switch (rawVersion) {
                case 3: version = MqttVersion.MQTT_3_1; break;
                case 4: version = MqttVersion.MQTT_3_1_1; break;
                case 5: version = MqttVersion.MQTT_5; break;
                default:
                    throw new IllegalArgumentException("Unrecognized MQTT version value " + rawVersion);
            }
            final long expiryInterval = buff.getLong();

            return new SessionData(clientId, Instant.ofEpochMilli(created), version, expiryInterval);
        }

        @Override
        public SessionData[] createStorage(int i) {
            return new SessionData[i];
        }
    }
}
