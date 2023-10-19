package io.moquette.broker;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttVersion;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * Used to store data about persisted sessions like MQTT version, session's properties.
 * */
public interface ISessionsRepository {

    // Data class
    final class SessionData implements Delayed {
        private final String clientId;
        private Instant expireAt = null;
        final MqttVersion version;

        final Optional<Will> will;
        private final int expiryInterval;
        private transient final Clock clock;

        /**
         * Construct a new SessionData without expiration set yet.
         *
         * @param expiryInterval seconds after which the persistent session could be dropped.
         * */
        public SessionData(String clientId, MqttVersion version, int expiryInterval, Clock clock) {
            this.clientId = clientId;
            this.clock = clock;
            this.expiryInterval = expiryInterval;
            this.version = version;
            this.will = Optional.empty();
        }

        public SessionData(String clientId, MqttVersion version, Will will, int expiryInterval, Clock clock) {
            this.clientId = clientId;
            this.clock = clock;
            this.expiryInterval = expiryInterval;
            this.version = version;
            this.will = Optional.of(will);
        }

        /**
         * Construct SessionData with an expiration instant, created by loading from the storage.
         *
         * @param expiryInterval seconds after which the persistent session could be dropped.
         * */
        public SessionData(String clientId, Instant expireAt, MqttVersion version, int expiryInterval, Clock clock) {
            Objects.requireNonNull(expireAt, "An expiration time is requested");
            this.clock = clock;
            this.clientId = clientId;
            this.expireAt = expireAt;
            this.expiryInterval = expiryInterval;
            this.version = version;
            this.will = Optional.empty();
        }

        public SessionData(String clientId, Instant expireAt, MqttVersion version, Will will, int expiryInterval, Clock clock) {
            Objects.requireNonNull(expireAt, "An expiration time is requested");
            this.clock = clock;
            this.clientId = clientId;
            this.expireAt = expireAt;
            this.expiryInterval = expiryInterval;
            this.version = version;
            this.will = Optional.of(will);
        }

        // Copy constructor
        private SessionData(String clientId, Instant expireAt, MqttVersion version, Optional<Will> will, int expiryInterval, Clock clock) {
            Objects.requireNonNull(expireAt, "An expiration time is requested");
            this.clock = clock;
            this.clientId = clientId;
            this.expireAt = expireAt;
            this.expiryInterval = expiryInterval;
            this.version = version;
            this.will = will;
        }

        public String clientId() {
            return clientId;
        }

        public MqttVersion protocolVersion() {
            return version;
        }

        public Optional<Instant> expireAt() {
            return Optional.ofNullable(expireAt);
        }

        public Optional<Long> expiryInstant() {
            return expireAt()
                .map(Instant::toEpochMilli);
        }

        public int expiryInterval() {
            return expiryInterval;
        }

        public SessionData withExpirationComputed() {
            final Instant expiresAt = clock.instant().plusSeconds(expiryInterval);
            if (hasWill()) {
                return new SessionData(clientId, expiresAt, version, will, expiryInterval, clock);
            } else {
                return new SessionData(clientId, expiresAt, version, expiryInterval, clock);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SessionData that = (SessionData) o;
            return clientId.equals(that.clientId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(clientId);
        }

        @Override
        public String toString() {
            return "SessionData{" +
                "clientId='" + clientId + '\'' +
                ", expireAt=" + expireAt +
                ", version=" + version +
                ", expiryInterval=" + expiryInterval +
                '}';
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(expireAt.toEpochMilli() - clock.millis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
        }

        public boolean hasWill() {
            return will.isPresent();
        }

        public Will will() throws IllegalArgumentException {
            return will.orElseThrow(() -> new IllegalArgumentException("Session's will is not available"));
        }

        public SessionData withWill(Will will) {
            if (expireAt != null) {
                return new SessionData(clientId, expireAt, version, will, expiryInterval, clock);
            } else {
                return new SessionData(clientId, version, will, expiryInterval, clock);
            }
        }

        public SessionData withoutWill() {
            if (expireAt != null) {
                return new SessionData(clientId, expireAt, version, expiryInterval, clock);
            } else {
                return new SessionData(clientId, version, expiryInterval, clock);
            }
        }
    }

     final class Will {

         public final String topic;
         public final byte[] payload;
         public final MqttQoS qos;
         public final boolean retained;

         public Will(String topic, byte[] payload, MqttQoS qos, boolean retained) {
             this.topic = topic;
             this.payload = payload;
             this.qos = qos;
             this.retained = retained;
         }
     }

    /**
     * @return the full list of persisted sessions data.
     * */
    Collection<SessionData> list();

    /**
     * Save data composing a session, es MQTT version, creation date and properties but not queues or subscriptions.
     * */
    void saveSession(SessionData session);

    void delete(SessionData session);
}
