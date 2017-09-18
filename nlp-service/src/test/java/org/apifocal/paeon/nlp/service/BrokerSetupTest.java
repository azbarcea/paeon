/*
 * Copyright 2017 apifocal LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apifocal.paeon.nlp.service;

import java.util.ArrayList;
import java.util.List;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class BrokerSetupTest {
    private static final Logger LOG = LoggerFactory.getLogger(BrokerSetupTest.class);
    private static final String PAEON_BROKER = "paeon.broker";
    private static final List<BrokerService> BROKERS = new ArrayList<>();
    private static final int PORT_START = 60616;
    private static String brokerUrl;


    @BeforeClass
    public static void startBroker() throws Exception {
        // TODO: use Properties for configuring credentials too
        brokerUrl = System.getenv(PAEON_BROKER);
        if (brokerUrl == null) {
            brokerUrl = "nio://localhost:" + PORT_START;

            createBroker("one");
            // createBroker("two");
        }
    }

    @AfterClass
    public static void stopBroker() throws Exception {
        for (BrokerService b : BROKERS) {
            if (b != null) {
                b.stop();
            }
        }
    }

 
    @Test
    public void testSetup() throws Exception {
        final List<String> msgs = new ArrayList<>();

        // Test destination fencing
        ConnectionFactory fc =  new ActiveMQConnectionFactory(brokerUrl);
        Connection consumerConnection = fc.createConnection("apollo", "password");
        Session consumerSession = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination q = consumerSession.createQueue("Stocks");
        MessageConsumer consumer = consumerSession.createConsumer(q);
        consumer.setMessageListener(new PaeonMessageListener(consumerSession));
        consumerConnection.start();

        ConnectionFactory fp =  new ActiveMQConnectionFactory(brokerUrl);
        Connection producerConnection = fp.createConnection("artemis", "secret");
        Session producerSession = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination r = producerSession.createQueue("Reply");
        MessageProducer producer = producerSession.createProducer(q);
        MessageConsumer replies = producerSession.createConsumer(r);
        replies.setMessageListener(new MessageListener() {
            public void onMessage(Message message) {
                LOG.debug("PING");
                try {
                    if (message instanceof TextMessage) {
                        TextMessage tm = (TextMessage)message;
                        msgs.add(tm.getText());
                        LOG.debug("Paeon MessageListener text received: '{}'", tm.getText());
                    }
                } catch (JMSException e) {
                }
            }
        });
        producerConnection.start();

        Message message = producerSession.createTextMessage("Complex provider note...");
        message.setJMSCorrelationID("1234");
        message.setJMSReplyTo(r);
        producer.send(message);
        Thread.sleep(2000);

        Assert.assertEquals(1, msgs.size());
        Assert.assertNotNull(msgs.get(0));
        Assert.assertTrue(msgs.get(0).contains("result"));

        producerConnection.stop();
        consumerConnection.stop();
    }

    public static BrokerService createBroker(String name) throws Exception {
        BrokerService b = BrokerFactory.createBroker("xbean:META-INF/org/apache/activemq/" + name + ".xml");
        if (!name.equals(b.getBrokerName())) {
            LOG.warn("Broker name mismatch; expecting '{}', found '{}'). Check configuration.", name, b.getBrokerName());
            return null;
        }
        BROKERS.add(b);
        b.start();
        b.waitUntilStarted();
        LOG.info("Broker '{}' started.", name);
        return b;
    }

}