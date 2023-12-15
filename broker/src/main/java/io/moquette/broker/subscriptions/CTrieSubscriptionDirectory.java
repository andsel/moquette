/*
 * Copyright (c) 2012-2018 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.moquette.broker.subscriptions;

import io.moquette.broker.ISubscriptionsRepository;
import io.moquette.broker.subscriptions.CTrie.SubscriptionRequest;
import io.netty.handler.codec.mqtt.MqttQoS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class CTrieSubscriptionDirectory implements ISubscriptionsDirectory {

    private static final Logger LOG = LoggerFactory.getLogger(CTrieSubscriptionDirectory.class);

    private CTrie ctrie;
    private volatile ISubscriptionsRepository subscriptionsRepository;

    @Override
    public void init(ISubscriptionsRepository subscriptionsRepository) {
        LOG.info("Initializing CTrie");
        ctrie = new CTrie();

        LOG.info("Initializing subscriptions store...");
        this.subscriptionsRepository = subscriptionsRepository;
        // reload any subscriptions persisted
        if (LOG.isTraceEnabled()) {
            LOG.trace("Reloading all stored subscriptions. SubscriptionTree = {}", dumpTree());
        }

        for (Subscription subscription : this.subscriptionsRepository.listAllSubscriptions()) {
            LOG.debug("Re-subscribing {}", subscription);
            ctrie.addToTree(SubscriptionRequest.buildNonShared(subscription));
        }
        if (LOG.isTraceEnabled()) {
            LOG.trace("Stored subscriptions have been reloaded. SubscriptionTree = {}", dumpTree());
        }
    }

    /**
     * @return the list of client ids that has a subscription stored.
     */
    @Override
    public Set<String> listAllSessionIds() {
        final Set<Subscription> subscriptions = subscriptionsRepository.listAllSubscriptions();
        final Set<String> clientIds = new HashSet<>(subscriptions.size());
        for (Subscription subscription : subscriptions) {
            clientIds.add(subscription.clientId);
        }
        return clientIds;
    }

    Optional<CNode> lookup(Topic topic) {
        return ctrie.lookup(topic);
    }

    /**
     * Given a topic string return the clients subscriptions that matches it. Topic string can't
     * contain character # and + because they are reserved to listeners subscriptions, and not topic
     * publishing.
     *
     * @param topicName
     *            to use for search matching subscriptions.
     * @return the list of matching subscriptions, or empty if not matching.
     */
    @Override
    public List<Subscription> matchWithoutQosSharpening(Topic topicName) {
        return ctrie.recursiveMatch(topicName);
    }

    @Override
    public List<Subscription> matchQosSharpening(Topic topicName) {
        final List<Subscription> subscriptions = matchWithoutQosSharpening(topicName);

        // for each session select the subscription with higher QoS
        Map<String, Subscription> subsGroupedByClient = new HashMap<>();
        for (Subscription sub : subscriptions) {
            // If same client is subscribed to two different shared subscription that overlaps
            // then it has to return both subscriptions, because the share name made them independent.
            final String key = sub.clientAndShareName();
            Subscription existingSub = subsGroupedByClient.get(key);
            // update the selected subscriptions if not present or if it has a greater qos
            if (existingSub == null || existingSub.qosLessThan(sub)) {
                subsGroupedByClient.put(key, sub);
            }
        }
        return new ArrayList<>(subsGroupedByClient.values());
    }

    @Override
    public void add(String clientId, Topic filter, MqttQoS requestedQoS) {
        SubscriptionRequest subRequest = SubscriptionRequest.buildNonShared(clientId, filter, requestedQoS);
        ctrie.addToTree(subRequest);
        subscriptionsRepository.addNewSubscription(subRequest.subscription());
    }

    @Override
    public void addShared(String clientId, ShareName name, Topic topicFilter, MqttQoS requestedQoS) {
        SubscriptionRequest shareSubRequest = SubscriptionRequest.buildShared(name, topicFilter, clientId, requestedQoS);
        ctrie.addToTree(shareSubRequest);
    }

    /**
     * Removes subscription from CTrie, adds TNode when the last client unsubscribes, then calls for cleanTomb in a
     * separate atomic CAS operation.
     *
     * @param topic the subscription's topic to remove.
     * @param clientID the Id of client owning the subscription.
     */
    @Override
    public void removeSubscription(Topic topic, String clientID) {
        ctrie.removeFromTree(topic, clientID);
        this.subscriptionsRepository.removeSubscription(topic.toString(), clientID);
    }

    @Override
    public int size() {
        return ctrie.size();
    }

    @Override
    public String dumpTree() {
        return ctrie.dumpTree();
    }
}
