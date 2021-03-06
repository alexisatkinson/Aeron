/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.agent;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.test.Tests;
import org.agrona.IoUtil;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.File;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.aeron.agent.DriverEventCode.*;
import static io.aeron.agent.EventConfiguration.EVENT_READER_FRAME_LIMIT;
import static io.aeron.agent.EventConfiguration.EVENT_RING_BUFFER;
import static java.util.Collections.synchronizedSet;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.EnumSource.Mode.INCLUDE;

public class DriverLoggingAgentTest
{
    private static final String NETWORK_CHANNEL = "aeron:udp?endpoint=localhost:24325";
    private static final int STREAM_ID = 1777;

    private static final Set<Integer> LOGGED_EVENTS = synchronizedSet(new HashSet<>());
    private static final Set<Integer> WAIT_LIST = synchronizedSet(new HashSet<>());
    private static CountDownLatch latch;

    private File testDir;

    @AfterEach
    public void after()
    {
        AgentTests.afterAgent();

        LOGGED_EVENTS.clear();
        WAIT_LIST.clear();

        if (testDir != null && testDir.exists())
        {
            IoUtil.delete(testDir, false);
        }
    }

    @Test
    @Timeout(10)
    public void logAll() throws InterruptedException
    {
        testLogMediaDriverEvents("all", EnumSet.of(
            FRAME_IN,
            FRAME_OUT,
            CMD_IN_ADD_PUBLICATION,
            CMD_IN_REMOVE_PUBLICATION,
            CMD_IN_ADD_SUBSCRIPTION,
            CMD_IN_REMOVE_SUBSCRIPTION,
            CMD_OUT_PUBLICATION_READY,
            CMD_OUT_AVAILABLE_IMAGE,
            CMD_OUT_ON_OPERATION_SUCCESS,
            REMOVE_PUBLICATION_CLEANUP,
            REMOVE_IMAGE_CLEANUP,
            SEND_CHANNEL_CREATION,
            RECEIVE_CHANNEL_CREATION,
            SEND_CHANNEL_CLOSE,
            RECEIVE_CHANNEL_CLOSE,
            CMD_OUT_SUBSCRIPTION_READY,
            CMD_IN_CLIENT_CLOSE));
    }

    @ParameterizedTest
    @EnumSource(value = DriverEventCode.class, mode = INCLUDE, names = {
        "REMOVE_IMAGE_CLEANUP",
        "REMOVE_PUBLICATION_CLEANUP",
        "SEND_CHANNEL_CREATION",
        "SEND_CHANNEL_CLOSE",
        "RECEIVE_CHANNEL_CREATION",
        "RECEIVE_CHANNEL_CLOSE",
        "FRAME_IN",
        "FRAME_OUT",
        "CMD_IN_ADD_SUBSCRIPTION",
        "CMD_OUT_AVAILABLE_IMAGE"
    })
    @Timeout(10)
    public void logIndividualEvents(final DriverEventCode eventCode) throws InterruptedException
    {
        try
        {
            testLogMediaDriverEvents(eventCode.name(), EnumSet.of(eventCode));
        }
        finally
        {
            after();
        }
    }

    private void testLogMediaDriverEvents(
        final String enabledEvents, final EnumSet<DriverEventCode> expectedEvents) throws InterruptedException
    {
        before(enabledEvents, expectedEvents);

        final String aeronDirectoryName = testDir.toPath().resolve("media").toString();

        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .errorHandler(Throwable::printStackTrace)
            .publicationLingerTimeoutNs(0)
            .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(1))
            .aeronDirectoryName(aeronDirectoryName);

        try (MediaDriver ignore = MediaDriver.launchEmbedded(driverCtx))
        {
            final Aeron.Context clientCtx = new Aeron.Context()
                .aeronDirectoryName(driverCtx.aeronDirectoryName());

            try (Aeron aeron = Aeron.connect(clientCtx);
                Subscription subscription = aeron.addSubscription(NETWORK_CHANNEL, STREAM_ID);
                Publication publication = aeron.addPublication(NETWORK_CHANNEL, STREAM_ID))
            {
                final UnsafeBuffer offerBuffer = new UnsafeBuffer(new byte[32]);
                while (publication.offer(offerBuffer) < 0)
                {
                    Thread.yield();
                    Tests.checkInterruptStatus();
                }

                final MutableInteger counter = new MutableInteger();
                final FragmentHandler handler = (buffer, offset, length, header) -> counter.value++;

                while (0 == subscription.poll(handler, 1))
                {
                    Thread.yield();
                    Tests.checkInterruptStatus();
                }

                assertEquals(counter.get(), 1);
            }

            latch.await();
        }

        assertEquals(expectedEvents.stream().map(DriverEventCode::id).collect(toSet()), LOGGED_EVENTS);
    }

    private void before(final String enabledEvents, final EnumSet<DriverEventCode> expectedEvents)
    {
        System.setProperty(EventLogAgent.READER_CLASSNAME_PROP_NAME, StubEventLogReaderAgent.class.getName());
        System.setProperty(EventConfiguration.ENABLED_EVENT_CODES_PROP_NAME, enabledEvents);
        AgentTests.beforeAgent();

        latch = new CountDownLatch(expectedEvents.size());
        WAIT_LIST.addAll(expectedEvents.stream().map(DriverEventCode::id).collect(toSet()));

        testDir = Paths.get(IoUtil.tmpDirName(), "driver-test").toFile();
        if (testDir.exists())
        {
            IoUtil.delete(testDir, false);
        }
    }

    static class StubEventLogReaderAgent implements Agent, MessageHandler
    {
        public String roleName()
        {
            return "event-log-reader";
        }

        public int doWork()
        {
            return EVENT_RING_BUFFER.read(this, EVENT_READER_FRAME_LIMIT);
        }

        public void onMessage(final int msgTypeId, final MutableDirectBuffer buffer, final int index, final int length)
        {
            LOGGED_EVENTS.add(msgTypeId);

            if (WAIT_LIST.contains(msgTypeId) && WAIT_LIST.remove(msgTypeId))
            {
                latch.countDown();
            }
        }
    }
}
