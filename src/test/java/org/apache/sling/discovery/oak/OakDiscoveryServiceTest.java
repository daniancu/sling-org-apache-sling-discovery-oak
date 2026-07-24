/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.discovery.oak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.discovery.TopologyEvent;
import org.apache.sling.discovery.base.its.setup.OSGiMock;
import org.apache.sling.discovery.base.its.setup.VirtualInstance;
import org.apache.sling.discovery.base.its.setup.mock.DummyResourceResolverFactory;
import org.apache.sling.discovery.base.its.setup.mock.MockFactory;
import org.apache.sling.discovery.base.its.setup.mock.PropertyProviderImpl;
import org.apache.sling.discovery.commons.providers.BaseTopologyView;
import org.apache.sling.discovery.commons.providers.ViewStateManager;
import org.apache.sling.discovery.commons.providers.base.DummyListener;
import org.apache.sling.discovery.commons.providers.spi.base.DescriptorHelper;
import org.apache.sling.discovery.commons.providers.spi.base.DiscoveryLiteConfig;
import org.apache.sling.discovery.commons.providers.spi.base.DiscoveryLiteDescriptor;
import org.apache.sling.discovery.commons.providers.spi.base.DiscoveryLiteDescriptorBuilder;
import org.apache.sling.discovery.commons.providers.spi.base.DummySlingSettingsService;
import org.apache.sling.discovery.commons.providers.spi.base.IdMapService;
import org.apache.sling.discovery.oak.cluster.OakClusterViewService;
import org.apache.sling.discovery.oak.its.setup.OakTestConfig;
import org.apache.sling.discovery.oak.its.setup.OakVirtualInstanceBuilder;
import org.apache.sling.discovery.oak.its.setup.SimulatedLease;
import org.apache.sling.discovery.oak.its.setup.SimulatedLeaseCollection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OakDiscoveryServiceTest {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private Log4jErrorCatcher catcher;

    public final class SimpleCommonsConfig implements DiscoveryLiteConfig {

        private long bgIntervalMillis;
        private long bgTimeoutMillis;

        SimpleCommonsConfig(long bgIntervalMillis, long bgTimeoutMillis) {
            this.bgIntervalMillis = bgIntervalMillis;
            this.bgTimeoutMillis = bgTimeoutMillis;
        }

        @Override
        public String getSyncTokenPath() {
            return "/var/synctokens";
        }

        @Override
        public String getIdMapPath() {
            return "/var/idmap";
        }

        @Override
        public long getClusterSyncServiceTimeoutMillis() {
            return bgTimeoutMillis;
        }

        @Override
        public long getClusterSyncServiceIntervalMillis() {
            return bgIntervalMillis;
        }

    }

    @Before
    public void setup() {
        catcher = Log4jErrorCatcher.installErrorCatcher(OakClusterViewService.class);
    }

    @After
    public void teardown() {
        if (catcher != null) {
            final String errorMsg = catcher.getLastErrorMsg();
            if (errorMsg != null) {
                fail("Unexpected error msg : " + errorMsg);
            }
            catcher.uninstall();
            catcher = null;
        }
    }

    @Test
    public void testBindBeforeActivate() throws Exception {
        OakVirtualInstanceBuilder builder =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("test")
                .newRepository("/foo/bar", true);
        String slingId = UUID.randomUUID().toString();;
        DiscoveryLiteDescriptorBuilder discoBuilder = new DiscoveryLiteDescriptorBuilder();
        discoBuilder.id("id").me(1).activeIds(1);
        // make sure the discovery-lite descriptor is marked as not final
        // such that the view is not already set before we want it to be
        discoBuilder.setFinal(false);
        DescriptorHelper.setDiscoveryLiteDescriptor(builder.getResourceResolverFactory(),
                discoBuilder);
        IdMapService idMapService = IdMapService.testConstructor(new SimpleCommonsConfig(1000, -1), new DummySlingSettingsService(slingId), builder.getResourceResolverFactory());
        assertTrue(idMapService.waitForInit(2000));
        OakDiscoveryService discoveryService = (OakDiscoveryService) builder.getDiscoverService();
        assertNotNull(discoveryService);
        DummyListener listener = new DummyListener();
        for(int i=0; i<100; i++) {
            discoveryService.bindTopologyEventListener(listener);
            discoveryService.unbindTopologyEventListener(listener);
        }
        discoveryService.bindTopologyEventListener(listener);
        assertEquals(0, listener.countEvents());
        discoveryService.activate(null);
        assertEquals(0, listener.countEvents());
        // some more confusion...
        discoveryService.unbindTopologyEventListener(listener);
        discoveryService.bindTopologyEventListener(listener);
        // only set the final flag now - this makes sure that handlePotentialTopologyChange
        // will actually detect a valid new, different view and send out an event -
        // exactly as we want to
        discoBuilder.setFinal(true);
        DescriptorHelper.setDiscoveryLiteDescriptor(builder.getResourceResolverFactory(),
                discoBuilder);
        // SLING-6924 : need to simulate a OakViewChecker.activate to trigger resetLeaderElectionId
        // otherwise no TOPOLOGY_INIT will be generated as without a leaderElectionId we now
        // consider a view as NO_ESTABLISHED_VIEW
        OSGiMock.activate(builder.getViewChecker());
        discoveryService.checkForTopologyChange();
        assertEquals(0, discoveryService.getViewStateManager().waitForAsyncEvents(2000));
        assertEquals(1, listener.countEvents());
        discoveryService.unbindTopologyEventListener(listener);
        assertEquals(1, listener.countEvents());
        discoveryService.bindTopologyEventListener(listener);
        assertEquals(0, discoveryService.getViewStateManager().waitForAsyncEvents(2000));
        assertEquals(2, listener.countEvents()); // should now have gotten an INIT too
    }

    @Test
    public void testDescriptorSeqNumChange() throws Exception {
        logger.info("testDescriptorSeqNumChange: start");
        OakVirtualInstanceBuilder builder1 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance1")
                .newRepository("/foo/barry/foo/", true)
                .setConnectorPingInterval(999)
                .setConnectorPingTimeout(999);
        builder1.getConfig().setSuppressPartiallyStartedInstance(true);
;
        VirtualInstance instance1 = builder1.build();
        OakVirtualInstanceBuilder builder2 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance2")
                .useRepositoryOf(instance1)
                .setConnectorPingInterval(999)
                .setConnectorPingTimeout(999);
        builder2.getConfig().setSuppressPartiallyStartedInstance(true);
        VirtualInstance instance2 = builder2.build();
        logger.info("testDescriptorSeqNumChange: created both instances, binding listener...");

        DummyListener listener = new DummyListener();
        OakDiscoveryService discoveryService = (OakDiscoveryService) instance1.getDiscoveryService();
        discoveryService.bindTopologyEventListener(listener);

        logger.info("testDescriptorSeqNumChange: waiting 2sec, listener should not get anything yet");
        assertEquals(0, discoveryService.getViewStateManager().waitForAsyncEvents(2000));
        assertEquals(0, listener.countEvents());

        logger.info("testDescriptorSeqNumChange: issuing 2 heartbeats with each instance should let the topology get established");
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();

        logger.info("testDescriptorSeqNumChange: listener should get an event within 2sec from now at latest");
        assertEquals(0, discoveryService.getViewStateManager().waitForAsyncEvents(2000));
        assertEquals(1, listener.countEvents());

        ResourceResolverFactory factory = instance1.getResourceResolverFactory();
        @SuppressWarnings("unused")
        ResourceResolver resolver = factory.getServiceResourceResolver(null);

        instance1.heartbeatsAndCheckView();
        assertEquals(0, discoveryService.getViewStateManager().waitForAsyncEvents(2000));
        assertEquals(1, listener.countEvents());

        // increment the seqNum by 2 - simulating a coming and going instance
        // while we were sleeping
        SimulatedLeaseCollection c = builder1.getSimulatedLeaseCollection();
        c.incSeqNum(2);
        logger.info("testDescriptorSeqNumChange: incremented seqnum by 2 - issuing another heartbeat should trigger a topology change");
        instance1.heartbeatsAndCheckView();

        // due to the nature of the syncService/minEventDelay we now explicitly first sleep 2sec before waiting for async events for another 2sec
        logger.info("testDescriptorSeqNumChange: sleeping 2sec for topology change to happen");
        Thread.sleep(2000);
        logger.info("testDescriptorSeqNumChange: ensuring no async events are still in the pipe - for another 2sec");
        assertEquals(0, discoveryService.getViewStateManager().waitForAsyncEvents(2000));
        logger.info("testDescriptorSeqNumChange: now listener should have received 3 events, it got: "+listener.countEvents());
        assertEquals(3, listener.countEvents());
    }

    @Test
    public void testNotYetInitializedLeaderElectionid() throws Exception {
        logger.info("testNotYetInitializedLeaderElectionid: start");
        OakVirtualInstanceBuilder builder1 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance")
                .newRepository("/foo/barrx/foo/", true)
                .setConnectorPingInterval(999)
                .setConnectorPingTimeout(999);
        builder1.getConfig().setSuppressPartiallyStartedInstance(true);
        VirtualInstance instance1 = builder1.build();
        logger.info("testNotYetInitializedLeaderElectionid: created 1 instance, binding listener...");

        DummyListener listener = new DummyListener();
        OakDiscoveryService discoveryService = (OakDiscoveryService) instance1.getDiscoveryService();
        discoveryService.bindTopologyEventListener(listener);

        logger.info("testNotYetInitializedLeaderElectionid: waiting 2sec, listener should not get anything yet");
        assertEquals(0, discoveryService.getViewStateManager().waitForAsyncEvents(2000));
        assertEquals(0, listener.countEvents());

        logger.info("testNotYetInitializedLeaderElectionid: issuing 2 heartbeats with each instance should let the topology get established");
        instance1.heartbeatsAndCheckView();
        instance1.heartbeatsAndCheckView();

        logger.info("testNotYetInitializedLeaderElectionid: listener should get an event within 2sec from now at latest");
        assertEquals(0, discoveryService.getViewStateManager().waitForAsyncEvents(2000));
        assertEquals(1, listener.countEvents());

        SimulatedLeaseCollection c = builder1.getSimulatedLeaseCollection();
        String secondSlingId = UUID.randomUUID().toString();
        final SimulatedLease newIncomingInstance = new SimulatedLease(instance1.getResourceResolverFactory(), c, secondSlingId);
        c.hooked(newIncomingInstance);
        c.incSeqNum(1);
        newIncomingInstance.updateLeaseAndDescriptor(new OakTestConfig());
        
        logger.info("testNotYetInitializedLeaderElectionid: issuing another 2 heartbeats");
        instance1.heartbeatsAndCheckView();
        instance1.heartbeatsAndCheckView();
        
        // there are different properties that an instance must set in the repository such that it finally becomes visible.
        // these include:
        // 1) idmap : it must map the oak id to sling id
        // 2) node named after its own slingId under /var/discovery/oak/clusterInstances/<slingId>
        // 3) store the leaderElectionId under /var/discovery/oak/clusterInstances/<slingId>
        // in all 3 cases the code must work fine if that node/property doesn't exist
        // and that's exactly what we're testing here.


        // initially not even the idmap is updated, so we're stuck with TOPOLOGY_CHANGING

        // due to the nature of the syncService/minEventDelay we now explicitly first sleep 2sec before waiting for async events for another 2sec
        logger.info("testNotYetInitializedLeaderElectionid: sleeping 2sec for topology change to happen");
        Thread.sleep(2000);
        logger.info("testNotYetInitializedLeaderElectionid: ensuring no async events are still in the pipe - for another 2sec");
        assertEquals(0, discoveryService.getViewStateManager().waitForAsyncEvents(2000));
        logger.info("testNotYetInitializedLeaderElectionid: now listener should have received 2 events, INIT and CHANGING, it got: "+listener.countEvents());
        assertEquals(2, listener.countEvents());
        List<TopologyEvent> events = listener.getEvents();
        assertEquals(TopologyEvent.Type.TOPOLOGY_INIT, events.get(0).getType());
        assertEquals(TopologyEvent.Type.TOPOLOGY_CHANGING, events.get(1).getType());
        
        // let's update the idmap first then
        DummyResourceResolverFactory factory1 = (DummyResourceResolverFactory) instance1.getResourceResolverFactory();
        ResourceResolverFactory factory2 = MockFactory.mockResourceResolverFactory(factory1.getSlingRepository());

        ResourceResolver resourceResolver = getResourceResolver(instance1.getResourceResolverFactory());
        DiscoveryLiteDescriptor descriptor =
                DiscoveryLiteDescriptor.getDescriptorFrom(resourceResolver);
        resourceResolver.close();
        
        DiscoveryLiteDescriptorBuilder dlb = prefill(descriptor);
        dlb.me(2);
        DescriptorHelper.setDiscoveryLiteDescriptor(factory2, dlb);

        @SuppressWarnings("unused")
        IdMapService secondIdMapService = IdMapService.testConstructor((DiscoveryLiteConfig) builder1.getConnectorConfig(), new DummySlingSettingsService(secondSlingId), factory2);
        
        instance1.heartbeatsAndCheckView();
        instance1.heartbeatsAndCheckView();
        Thread.sleep(2000);
        assertEquals(2, listener.countEvents());
        
        
        // now let's add the /var/discovery/oak/clusterInstances/<slingId> node
        resourceResolver = getResourceResolver(factory2);
        Resource clusterInstancesRes = resourceResolver.getResource(builder1.getConnectorConfig().getClusterInstancesPath());
        assertNull(clusterInstancesRes.getChild(secondSlingId));
        resourceResolver.create(clusterInstancesRes, secondSlingId, null);
        resourceResolver.commit();
        assertNotNull(clusterInstancesRes.getChild(secondSlingId));
        resourceResolver.close();

        instance1.heartbeatsAndCheckView();
        instance1.heartbeatsAndCheckView();
        Thread.sleep(2000);
        assertEquals(2, listener.countEvents());

        // now let's add the leaderElectionId
        resourceResolver = getResourceResolver(factory2);
        Resource instanceResource = resourceResolver.getResource(builder1.getConnectorConfig().getClusterInstancesPath() + "/" + secondSlingId);
        assertNotNull(instanceResource);
        instanceResource.adaptTo(ModifiableValueMap.class).put("leaderElectionId", "0");
        resourceResolver.commit();
        resourceResolver.close();
        
        instance1.heartbeatsAndCheckView();
        instance1.heartbeatsAndCheckView();
        Thread.sleep(2000);
        assertEquals(3, listener.countEvents());
        assertEquals(TopologyEvent.Type.TOPOLOGY_CHANGED, events.get(2).getType());
    }

    private DiscoveryLiteDescriptorBuilder prefill(DiscoveryLiteDescriptor d) throws Exception {
        DiscoveryLiteDescriptorBuilder b = new DiscoveryLiteDescriptorBuilder();
        b.setFinal(true);
        long seqnum = d.getSeqNum();
        b.seq((int) seqnum);
        b.activeIds(box(d.getActiveIds()));
        b.deactivatingIds(box(d.getDeactivatingIds()));
        b.me(d.getMyId());
        b.id(d.getViewId());
        return b;
    }

    private Integer[] box(final int[] ids) {
        return Arrays.stream( ids ).boxed().collect( Collectors.toList() ).toArray(new Integer[ids.length]);
    }
    
    private ResourceResolver getResourceResolver(ResourceResolverFactory resourceResolverFactory) throws LoginException {
        return resourceResolverFactory.getServiceResourceResolver(null);
    }

    @Test
    public void testInvertedLeaderElectionOrder() throws Exception {
        logger.info("testInvertedLeaderElectionOrder: start");
        // instance1 should be leader
        BaseTopologyView lastView = doTestInvertedLeaderElectionOrder(20, 10, false);
        assertTrue(lastView.getLocalInstance().isLeader());
        lastView = doTestInvertedLeaderElectionOrder(20, 10, true);
        assertTrue(lastView.getLocalInstance().isLeader());
        // instance2 should be leader
        lastView = doTestInvertedLeaderElectionOrder(10, 20, false);
        assertFalse(lastView.getLocalInstance().isLeader());
        lastView = doTestInvertedLeaderElectionOrder(10, 20, true);
        assertFalse(lastView.getLocalInstance().isLeader());
        // instance1 should be leader
        lastView = doTestInvertedLeaderElectionOrder(10, 10, false);
        assertTrue(lastView.getLocalInstance().isLeader());
        lastView = doTestInvertedLeaderElectionOrder(10, 10, true);
        assertTrue(lastView.getLocalInstance().isLeader());
    }

    private BaseTopologyView doTestInvertedLeaderElectionOrder(final int instance1Prefix, final int instance2Prefix, boolean syncTokenEnabled)
            throws Exception {
        OakVirtualInstanceBuilder builder1 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance1")
                .newRepository("/foo/barrio/foo/", true)
                .setConnectorPingInterval(999)
                .setConnectorPingTimeout(999);
        builder1.getConfig().leaderElectionPrefix = instance1Prefix;
        builder1.getConfig().invertLeaderElectionPrefixOrder = true;
        builder1.getConfig().setSyncTokenEnabled(syncTokenEnabled);
        builder1.getConfig().setJoinerDelaySeconds(0);
        builder1.getConfig().setSuppressPartiallyStartedInstance(true);
        VirtualInstance instance1 = builder1.build();
        OakVirtualInstanceBuilder builder2 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance2")
                .useRepositoryOf(instance1)
                .setConnectorPingInterval(999)
                .setConnectorPingTimeout(999);
        builder2.getConfig().leaderElectionPrefix = instance2Prefix;
        builder2.getConfig().invertLeaderElectionPrefixOrder = true;
        builder2.getConfig().setSyncTokenEnabled(syncTokenEnabled);
        builder2.getConfig().setJoinerDelaySeconds(0);
        builder2.getConfig().setSuppressPartiallyStartedInstance(true);
        VirtualInstance instance2 = builder2.build();
    
        DummyListener listener = new DummyListener();
        OakDiscoveryService discoveryService = (OakDiscoveryService) instance1.getDiscoveryService();
        discoveryService.bindTopologyEventListener(listener);
    
        instance2.heartbeatsAndCheckView();
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        instance1.heartbeatsAndCheckView();
    
        assertEquals(0, discoveryService.getViewStateManager().waitForAsyncEvents(2000));
        assertEquals(1, listener.countEvents());
        instance1.stop();
        instance2.stop();
        return listener.getLastView();
    }

    @Test
    public void testPropertyProviderRegistrations() throws Exception{
        OakVirtualInstanceBuilder builder =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance1")
                .newRepository("/foo/barrio/foo/", true)
                .setConnectorPingInterval(999)
                .setConnectorPingTimeout(999);
        builder.getConfig().setSuppressPartiallyStartedInstance(true);
        VirtualInstance instance = builder.build();
        OakDiscoveryService discoveryService = (OakDiscoveryService) instance.getDiscoveryService();
        discoveryService.bindPropertyProvider(null, null);
        discoveryService.unbindPropertyProvider(null, null);
        discoveryService.updatedPropertyProvider(null, null);

        PropertyProviderImpl p = new PropertyProviderImpl();
        discoveryService.bindPropertyProvider(p, null);
        discoveryService.unbindPropertyProvider(p, null);
        discoveryService.updatedPropertyProvider(p, null);

        Map<String, Object> m = new HashMap<>();
        m.put(Constants.SERVICE_ID, 42L);
        discoveryService.bindPropertyProvider(p, m);
        discoveryService.unbindPropertyProvider(p, m);
        discoveryService.updatedPropertyProvider(p, m);
    }

    /**
     * After deactivate(): isShuttingDown() latches true and subsequent
     * PropertyProvider callbacks do not reach the ResourceResolverFactory.
     */
    @Test
    public void testIsShuttingDownAfterDeactivate() throws Exception {
        OakDiscoveryService ds = new OakDiscoveryService();

        // Wire only what deactivate() and the property-provider callbacks touch.
        ViewStateManager vsmMock = mock(ViewStateManager.class);
        Field vsmField = OakDiscoveryService.class.getDeclaredField("viewStateManager");
        vsmField.setAccessible(true);
        vsmField.set(ds, vsmMock);

        ResourceResolverFactory rrfMock = mock(ResourceResolverFactory.class);
        Field rrfField = OakDiscoveryService.class.getDeclaredField("resourceResolverFactory");
        rrfField.setAccessible(true);
        rrfField.set(ds, rrfMock);

        assertFalse(ds.isShuttingDown());

        ds.deactivate();
        assertTrue(ds.isShuttingDown());

        PropertyProviderImpl provider = new PropertyProviderImpl();
        Map<String, Object> props = new HashMap<>();
        props.put(Constants.SERVICE_ID, 42L);
        ds.bindPropertyProvider(provider, props);
        ds.updatedPropertyProvider(provider, props);
        ds.unbindPropertyProvider(provider, props);

        verifyNoInteractions(rrfMock);
    }

    /**
     * The inline system-bundle-state check catches framework shutdown before
     * deactivate() runs, and latches on first observation.
     */
    @Test
    public void testInlineSystemBundleStateFallback() {
        OakDiscoveryService ds = new OakDiscoveryService();

        Bundle systemBundleMock = mock(Bundle.class);
        ds.setSystemBundleForTesting(systemBundleMock);

        when(systemBundleMock.getState()).thenReturn(Bundle.ACTIVE);
        assertFalse(ds.isShuttingDown());

        when(systemBundleMock.getState()).thenReturn(Bundle.STOPPING);
        assertTrue(ds.isShuttingDown());

        // latched: state flipping back to ACTIVE must not un-shut-down
        when(systemBundleMock.getState()).thenReturn(Bundle.ACTIVE);
        assertTrue(ds.isShuttingDown());
    }

    /**
     * An invalidated system bundle reference (IllegalStateException on
     * getState) is treated as shutting down.
     */
    @Test
    public void testInvalidatedSystemBundleTreatedAsShuttingDown() {
        OakDiscoveryService ds = new OakDiscoveryService();

        Bundle systemBundleMock = mock(Bundle.class);
        when(systemBundleMock.getState()).thenThrow(new IllegalStateException("bundle invalidated"));
        ds.setSystemBundleForTesting(systemBundleMock);

        assertTrue(ds.isShuttingDown());
    }

    /**
     * If our BundleContext is already invalidated at activation,
     * cacheSystemBundle latches {@code deactivating} immediately.
     */
    @Test
    public void testCacheSystemBundleWithInvalidatedContextLatchesDeactivating() {
        OakDiscoveryService ds = new OakDiscoveryService();

        assertFalse(ds.isShuttingDown());

        Bundle ourBundleMock = mock(Bundle.class);
        when(ourBundleMock.getBundleContext())
                .thenThrow(new IllegalStateException("our context invalidated"));

        ds.cacheSystemBundle(ourBundleMock);

        assertTrue(ds.isShuttingDown());
    }

    /**
     * Happy path: cacheSystemBundle resolves the system bundle via our
     * BundleContext and subsequent isShuttingDown() calls consult it.
     */
    @Test
    public void testCacheSystemBundleHappyPath() {
        OakDiscoveryService ds = new OakDiscoveryService();

        Bundle systemBundleMock = mock(Bundle.class);
        when(systemBundleMock.getState()).thenReturn(Bundle.ACTIVE);

        BundleContext ctxMock = mock(BundleContext.class);
        when(ctxMock.getBundle(0)).thenReturn(systemBundleMock);

        Bundle ourBundleMock = mock(Bundle.class);
        when(ourBundleMock.getBundleContext()).thenReturn(ctxMock);

        ds.cacheSystemBundle(ourBundleMock);

        assertFalse(ds.isShuttingDown());

        when(systemBundleMock.getState()).thenReturn(Bundle.STOPPING);
        assertTrue(ds.isShuttingDown());
    }

    /**
     * If our BundleContext is null (bundle stopped mid-activation),
     * cacheSystemBundle degrades silently: no latch, no NPE.
     */
    @Test
    public void testCacheSystemBundleWithNullBundleContext() {
        OakDiscoveryService ds = new OakDiscoveryService();

        Bundle ourBundleMock = mock(Bundle.class);
        when(ourBundleMock.getBundleContext()).thenReturn(null);

        ds.cacheSystemBundle(ourBundleMock);

        assertFalse(ds.isShuttingDown());
    }

    /**
     * A non-IllegalStateException RuntimeException from getBundleContext()
     * is logged and swallowed - shutdown detection degrades to deactivate()
     * only, but the service does not latch as shutting down.
     */
    @Test
    public void testCacheSystemBundleWithGenericRuntimeException() {
        OakDiscoveryService ds = new OakDiscoveryService();

        Bundle ourBundleMock = mock(Bundle.class);
        when(ourBundleMock.getBundleContext())
                .thenThrow(new SecurityException("denied"));

        ds.cacheSystemBundle(ourBundleMock);

        assertFalse(ds.isShuttingDown());
    }

    /**
     * All non-live system bundle states (RESOLVED, INSTALLED, UNINSTALLED)
     * are treated as shutting down, not just STOPPING.
     */
    @Test
    public void testInlineSystemBundleStateFallbackAllTerminalStates() {
        int[] terminalStates = { Bundle.RESOLVED, Bundle.INSTALLED, Bundle.UNINSTALLED };
        for (int state : terminalStates) {
            OakDiscoveryService ds = new OakDiscoveryService();

            Bundle systemBundleMock = mock(Bundle.class);
            when(systemBundleMock.getState()).thenReturn(state);
            ds.setSystemBundleForTesting(systemBundleMock);

            assertTrue("state " + state + " should be treated as shutting down",
                    ds.isShuttingDown());
        }
    }

    /**
     * End-to-end: with the service still activated but the system bundle
     * STOPPING, PropertyProvider callbacks must not reach the
     * ResourceResolverFactory.
     */
    @Test
    public void testActivatedServiceSkipsResourceResolverWhenSystemBundleStopping() throws Exception {
        OakVirtualInstanceBuilder builder = (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("shutdown-rrf-guard")
                .newRepository("/foo/shutdown-rrf/", true)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder.getConfig().setSuppressPartiallyStartedInstance(true);
        VirtualInstance instance = builder.build();
        try {
            OakDiscoveryService ds = (OakDiscoveryService) instance.getDiscoveryService();

            assertFalse(ds.isShuttingDown());

            ResourceResolverFactory rrfMock = mock(ResourceResolverFactory.class);
            Field rrfField = OakDiscoveryService.class.getDeclaredField("resourceResolverFactory");
            rrfField.setAccessible(true);
            rrfField.set(ds, rrfMock);

            Bundle systemBundleMock = mock(Bundle.class);
            when(systemBundleMock.getState()).thenReturn(Bundle.STOPPING);
            ds.setSystemBundleForTesting(systemBundleMock);

            // Do NOT pre-latch by calling isShuttingDown() here - the guard
            // inside doUpdateProperties() must be the one to short-circuit,
            // otherwise removing that guard would still leave the test green.

            PropertyProviderImpl provider = new PropertyProviderImpl();
            Map<String, Object> props = new HashMap<>();
            props.put(Constants.SERVICE_ID, 42L);
            ds.bindPropertyProvider(provider, props);
            ds.updatedPropertyProvider(provider, props);
            ds.unbindPropertyProvider(provider, props);

            verifyNoInteractions(rrfMock);
        } finally {
            instance.stop();
        }
    }

    @Test
    public void simpleNewJoinerTest() throws Exception {
        doSimpleNewJoinerTest(0);
    }

    @Test
    public void simpleNewJoinerTestWithDelay() throws Exception {
        doSimpleNewJoinerTest(2);
    }

    private boolean waitForEquals(DummyListener listener, int expected, long maxWait) {
        final long timeout = System.currentTimeMillis() + maxWait;
        while(System.currentTimeMillis() < timeout) {
            if (listener.countEvents() == expected) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // this empty catch is okay
            }
        }
        final int actual = listener.countEvents();
        if (actual == expected) {
            return true;
        }
        fail("listener did not get expected number of events, expected: " + expected + ", got : " + actual);
        // never reached:
        return false;
    }

    private void doSimpleNewJoinerTest(int joinerDelaySeconds) throws Exception {
        OakVirtualInstanceBuilder builder1 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance1")
                .newRepository("/foo/barrio/foo/", true)
                .setConnectorPingInterval(999)
                .setConnectorPingTimeout(999);
        builder1.getConfig().setSyncTokenEnabled(true);
        builder1.getConfig().setJoinerDelaySeconds(joinerDelaySeconds);
        builder1.getConfig().setSuppressPartiallyStartedInstance(true);
        VirtualInstance instance1 = builder1.build();
        DummyListener listener1 = new DummyListener();
        OakDiscoveryService discoveryService1 = (OakDiscoveryService) instance1.getDiscoveryService();
        discoveryService1.bindTopologyEventListener(listener1);

        instance1.heartbeatsAndCheckView();
        instance1.heartbeatsAndCheckView();

        assertEquals(0, discoveryService1.getViewStateManager().waitForAsyncEvents(2000));
        Thread.sleep(1000);
        assertEquals(1, listener1.countEvents());

        OakVirtualInstanceBuilder builder2 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance2")
                .useRepositoryOf(instance1)
                .setConnectorPingInterval(999)
                .setConnectorPingTimeout(999);
        builder2.getConfig().setSuppressPartiallyStartedInstance(true);
        builder2.getConfig().setJoinerDelaySeconds(joinerDelaySeconds);

        // while we started instance 2, we don't let it heartbeat yet - just a lease update
        // this simulates a situation where oak is up, but sling-repository not yet
        builder2.updateLease();

        // that way it shouldn't cause TOPOLOGY_CHANGING at instance 1 yet
        instance1.heartbeatsAndCheckView();
        instance1.heartbeatsAndCheckView();

        assertEquals(0, discoveryService1.getViewStateManager().waitForAsyncEvents(2000));
        assertEquals(1, listener1.countEvents());

        instance1.heartbeatsAndCheckView();

        builder2.getConfig().setSyncTokenEnabled(true);
        VirtualInstance instance2 = builder2.build();
        DummyListener listener2 = new DummyListener();
        OakDiscoveryService discoveryService2 = (OakDiscoveryService) instance2.getDiscoveryService();
        discoveryService2.bindTopologyEventListener(listener2);

        instance2.heartbeatsAndCheckView();
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();

        Thread.sleep((joinerDelaySeconds + 2) * 1000);

        waitForEquals(listener1, 3, 1000);
        waitForEquals(listener2, 1, 3000); // had a flaky failure so giving it 3 sec

        OakVirtualInstanceBuilder builder3 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance3")
                .useRepositoryOf(instance1)
                .setConnectorPingInterval(999)
                .setConnectorPingTimeout(999);
        builder3.getConfig().setJoinerDelaySeconds(joinerDelaySeconds);
        builder3.getConfig().setSuppressPartiallyStartedInstance(true);
        builder3.updateLease();

        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        for(int i=0; i<5; i++) {
            Thread.sleep(300);
            instance1.heartbeatsAndCheckView();
            instance2.heartbeatsAndCheckView();
            assertEquals(3, listener1.countEvents());
            assertEquals(1, listener2.countEvents());
        }

        builder3.getConfig().setSyncTokenEnabled(true);
        VirtualInstance instance3 = builder3.build();
        DummyListener listener3 = new DummyListener();
        OakDiscoveryService discoveryService3 = (OakDiscoveryService) instance3.getDiscoveryService();
        discoveryService3.bindTopologyEventListener(listener3);

        instance3.heartbeatsAndCheckView();

        for(int i=0; i<4; i++) {
            Thread.sleep(500);
            instance1.heartbeatsAndCheckView();
            instance2.heartbeatsAndCheckView();
            instance3.heartbeatsAndCheckView();
        }

        Thread.sleep(joinerDelaySeconds * 1000);

        waitForEquals(listener3, 1, 1000);
        waitForEquals(listener1, 5, 1000);
        waitForEquals(listener2, 3, 1000);
    }

    @Test
    public void testSingleStart() throws Exception {
        // 1. start instance 1 normally
        OakVirtualInstanceBuilder builder1 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance1")
                .newRepository("/foo1/barrio/foo1/", true)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder1.getConfig().setMinEventDelay(1);
        builder1.getConfig().setJoinerDelaySeconds(5);
        builder1.getConfig().setSyncTokenEnabled(true);
        builder1.getConfig().setSuppressPartiallyStartedInstance(true);
        // 1. start instance 1 normally -> call builder1.build()
        VirtualInstance instance1 = builder1.build();
        DummyListener listener1 = new DummyListener();
        OakDiscoveryService discoveryService1 = (OakDiscoveryService) instance1.getDiscoveryService();
        discoveryService1.bindTopologyEventListener(listener1);
        instance1.heartbeatsAndCheckView();

        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        assertEquals(1, listener1.countEvents());
    }

    @Test
    public void testSlowLeaderOnChange() throws Exception {
        // a leader reacting slow with syncToken update risks loosing
        // the leader flag. this test should ensure it's fine as long
        // as it is within minEventDelay
        OakVirtualInstanceBuilder builder1 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance1")
                .newRepository("/foo1/barrio/foo1/", true)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder1.getConfig().setMinEventDelay(6);
        builder1.getConfig().setJoinerDelaySeconds(1);
        builder1.getConfig().setSyncTokenEnabled(true);
        builder1.getConfig().setSuppressPartiallyStartedInstance(true);

        // 1. start instance 1 normally -> call builder1.build()
        VirtualInstance instance1 = builder1.build();
        DummyListener listener1 = new DummyListener();
        OakDiscoveryService discoveryService1 = (OakDiscoveryService) instance1.getDiscoveryService();
        discoveryService1.bindTopologyEventListener(listener1);
        instance1.heartbeatsAndCheckView();
        assertEquals(0, listener1.countEvents());
        instance1.heartbeatsAndCheckView();
        waitForEquals(listener1, 1, 1000);
        assertEquals(1, listener1.countEvents());

        OakVirtualInstanceBuilder builder2 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance2")
                .useRepositoryOf(builder1)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder2.getConfig().setMinEventDelay(6);
        builder2.getConfig().setJoinerDelaySeconds(1);
        builder2.getConfig().setSyncTokenEnabled(true);
        builder2.getConfig().setSuppressPartiallyStartedInstance(true);

        // 2. start instance 2 normally -> call builder2.build()
        VirtualInstance instance2 = builder2.build();
        DummyListener listener2 = new DummyListener();
        OakDiscoveryService discoveryService2 = (OakDiscoveryService) instance2.getDiscoveryService();
        discoveryService2.bindTopologyEventListener(listener2);

        // 3. now let instance 1 be slow, instance 2 fast, with updating syncToken
        // in any case, update the discovery-lite-view
        builder2.getSimulatedLeaseCollection().incSeqNum();
        builder2.updateLease();
        // but now don't update anything with instance1 as to not update the syncToken

        Thread.sleep(1000);
        instance2.heartbeatsAndCheckView();
        assertEquals(1, listener1.countEvents());
        assertEquals(0, listener2.countEvents());
        Thread.sleep(1000);
        instance2.heartbeatsAndCheckView();
        assertEquals(1, listener1.countEvents());
        assertEquals(0, listener2.countEvents());
        for(int i=0; i<8; i++) {
            Thread.sleep(1000);
            instance2.heartbeatsAndCheckView();
            assertEquals("i is " + i, 1, listener1.countEvents());
            assertEquals("i is " + i, 0, listener2.countEvents());
        }
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        for(int i=0; i<4; i++) {
            Thread.sleep(1000);
            instance1.heartbeatsAndCheckView();
            instance2.heartbeatsAndCheckView();
            assertEquals("i is " + i, 3, listener1.countEvents());
            assertEquals("i is " + i, 1, listener2.countEvents());
        }
    }

    @Test
    public void testFullPartialFullFullStart() throws Exception {
        // 1. start instance 1 normally
        // 2. start instance 2 only partially -> gets ignored by 1
        // 3. start instance 3 + 4 fully -> they should wait for (4/3 and 2)
        // 4. then start instance 2 fully -> 3 and 4 should get resolved
        OakVirtualInstanceBuilder builder1 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance1")
                .newRepository("/foo1/barrio/foo1/", true)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder1.getConfig().setMinEventDelay(1);
        builder1.getConfig().setJoinerDelaySeconds(5);
        builder1.getConfig().setSyncTokenEnabled(true);
        builder1.getConfig().setSuppressPartiallyStartedInstance(true);

        // 1. start instance 1 normally -> call builder1.build()
        VirtualInstance instance1 = builder1.build();
        DummyListener listener1 = new DummyListener();
        OakDiscoveryService discoveryService1 = (OakDiscoveryService) instance1.getDiscoveryService();
        discoveryService1.bindTopologyEventListener(listener1);
        instance1.heartbeatsAndCheckView();

        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        assertEquals(1, listener1.countEvents());

        OakVirtualInstanceBuilder builder2 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance2")
                .useRepositoryOf(builder1)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder2.getConfig().setJoinerDelaySeconds(5);
        builder2.getConfig().setSyncTokenEnabled(true);
        builder2.getConfig().setSuppressPartiallyStartedInstance(true);
        // 2. start instance 2 only partially -> do not call builder2.build() but just updateLease()
        builder2.updateLease();

        Thread.sleep(1000);

        OakVirtualInstanceBuilder builder3 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance3")
                .useRepositoryOf(builder1)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder3.getConfig().setJoinerDelaySeconds(5);
        builder3.getConfig().setSyncTokenEnabled(true);
        builder3.getConfig().setSuppressPartiallyStartedInstance(true);
        // 3. start instance 3 + 4 fully -> builder3.build()
        VirtualInstance instance3 = builder3.build();
        DummyListener listener3 = new DummyListener();
        OakDiscoveryService discoveryService3 = (OakDiscoveryService) instance3.getDiscoveryService();
        discoveryService3.bindTopologyEventListener(listener3);

        OakVirtualInstanceBuilder builder4 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance4")
                .useRepositoryOf(builder1)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder4.getConfig().setJoinerDelaySeconds(5);
        builder4.getConfig().setSyncTokenEnabled(true);
        builder4.getConfig().setSuppressPartiallyStartedInstance(true);
        // 3. start instance 3 + 4 fully -> builder4.build()
        VirtualInstance instance4 = builder4.build();
        DummyListener listener4 = new DummyListener();
        OakDiscoveryService discoveryService4 = (OakDiscoveryService) instance4.getDiscoveryService();
        discoveryService4.bindTopologyEventListener(listener4);

        instance1.heartbeatsAndCheckView();
        instance3.heartbeatsAndCheckView();
        instance4.heartbeatsAndCheckView();
        for(int i=0; i<7; i++) {
            Thread.sleep(1000);
            instance1.heartbeatsAndCheckView();
            instance3.heartbeatsAndCheckView();
            instance4.heartbeatsAndCheckView();
        }

        assertEquals(0, listener3.countEvents());
        assertEquals(0, listener4.countEvents());

        // 4. then start instance 2 fully -> 3 and 4 should get resolved
        VirtualInstance instance2 = builder2.build();

        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        instance3.heartbeatsAndCheckView();
        instance4.heartbeatsAndCheckView();
        assertEquals(0, listener3.countEvents());
        assertEquals(0, listener4.countEvents());
        for(int i=0; i<7; i++) {
            Thread.sleep(1000);
            instance1.heartbeatsAndCheckView();
            instance2.heartbeatsAndCheckView();
            instance3.heartbeatsAndCheckView();
            instance4.heartbeatsAndCheckView();
        }

        assertEquals(1, listener3.countEvents());
        assertEquals(1, listener4.countEvents());
    }

    @Test
    public void testUpdateDuringJoinDelay() throws Exception {
        OakVirtualInstanceBuilder builder1 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance1")
                .newRepository("/foo1/barrio/foo1/", true)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder1.getConfig().setMinEventDelay(1);
        builder1.getConfig().setJoinerDelaySeconds(5);
        builder1.getConfig().setSyncTokenEnabled(true);
        builder1.getConfig().setSuppressPartiallyStartedInstance(true);
        // 1. start instance 1 normally -> call builder1.build()
        VirtualInstance instance1 = builder1.build();
        DummyListener listener1 = new DummyListener();
        OakDiscoveryService discoveryService1 = (OakDiscoveryService) instance1.getDiscoveryService();
        discoveryService1.bindTopologyEventListener(listener1);
        instance1.heartbeatsAndCheckView();

        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        assertEquals(1, listener1.countEvents());

        OakVirtualInstanceBuilder builder2 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance2")
                .useRepositoryOf(builder1)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder2.getConfig().setJoinerDelaySeconds(6);
        builder2.getConfig().setSyncTokenEnabled(true);
        builder2.getConfig().setMinEventDelay(1);
        builder2.getConfig().setSuppressPartiallyStartedInstance(true);
        // 2. start instance 2 only partially -> do not call builder2.build() but just updateLease()
        builder2.updateLease();

        Thread.sleep(2000);
        assertEquals(1, listener1.countEvents());

        // joinDelay timer starts with building (plus 1sec for minEventDelay)
        VirtualInstance instance2 = builder2.build();
        DummyListener listener2 = new DummyListener();
        OakDiscoveryService discoveryService2 = (OakDiscoveryService) instance2.getDiscoveryService();
        discoveryService2.bindTopologyEventListener(listener2);

        Thread.sleep(1000);
        assertEquals(0, listener2.countEvents());

        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();

        assertEquals(3, listener1.countEvents());
        assertEquals(0, listener2.countEvents());

        OakVirtualInstanceBuilder builder3 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName("instance3")
                .useRepositoryOf(builder1)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder3.getConfig().setMinEventDelay(1);
        builder3.getConfig().setJoinerDelaySeconds(5);
        builder3.getConfig().setSyncTokenEnabled(true);
        builder3.getConfig().setSuppressPartiallyStartedInstance(true);
        VirtualInstance instance3 = builder3.build();
        DummyListener listener3 = new DummyListener();
        OakDiscoveryService discoveryService3 = (OakDiscoveryService) instance3.getDiscoveryService();
        discoveryService3.bindTopologyEventListener(listener3);

        // 3 started, now let 1 and 3 send heartbeats, 2 not
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        instance3.heartbeatsAndCheckView();
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        instance3.heartbeatsAndCheckView();
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        instance3.heartbeatsAndCheckView();
        Thread.sleep(1000);
        // now it's 6sec, so the joinDelay of 2 should have timed out,
        // however, as we started instance 3 the view of 2 should now have changed
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        instance3.heartbeatsAndCheckView();
        Thread.sleep(1000);
        assertEquals(5, listener1.countEvents());
        assertEquals(1, listener2.countEvents());

        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        instance3.heartbeatsAndCheckView();
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        instance3.heartbeatsAndCheckView();
        Thread.sleep(1000);
        instance1.heartbeatsAndCheckView();
        instance2.heartbeatsAndCheckView();
        instance3.heartbeatsAndCheckView();

        // check all the listeners
        waitForEquals(listener1, 5, 1000);
        waitForEquals(listener2, 1, 1000);
        waitForEquals(listener3, 1, 1000);
    }

    /**
     * This test requires the minEventHandlerDelay to be configured properly, ie reasonably
     * high to delay the time between TOPOLOGY_CHANGING and TOPOLOGY_CHANGED
     * (as a replacement of previously not suppressing mismatching synctokens)
     */
    @Test
    public void reuseClusterIdTest() throws Exception {
        logger.info("reuseClusterIdTest : START - creating instance1.");
        OakVirtualInstanceBuilder builder1 = createInstanceWithNewRepo("instance", "/foo1/barrio/foo1/", 1, 5);
        // 1. start instance 1 normally -> call builder1.build()
        VirtualInstance instance1 = builder1.build();
        DummyListener listener1 = createDummyListener(instance1);

        for(int i = 0; i < 3; i++) {
            instance1.heartbeatsAndCheckView();
            Thread.sleep(1000);
        }
        assertEquals(1, listener1.countEvents());

        // 2. start instance 2 partially
        logger.info("reuseClusterIdTest : creating instance2.");
        OakVirtualInstanceBuilder builder2 = createInstanceUsingExistingRepo(builder1, "instance", 1, 5);
        // 2. start instance 2 only partially -> do not call builder2.build() but just updateLease()
        builder2.updateLease();

        for(int i = 0; i < 3; i++) {
            instance1.heartbeatsAndCheckView();
            Thread.sleep(1000);
        }
        assertEquals(1, listener1.countEvents());

        // finish instance2 startup
        VirtualInstance instance2 = builder2.build();
        DummyListener listener2 = createDummyListener(instance2);

        for(int i = 0; i < 3; i++) {
            instance1.heartbeatsAndCheckView();
            instance2.heartbeatsAndCheckView();
            Thread.sleep(1000);
        }
        assertEquals(0, listener2.countEvents());
        assertEquals(3, listener1.countEvents());

        for(int i = 0; i < 3; i++) {
            instance1.heartbeatsAndCheckView();
            instance2.heartbeatsAndCheckView();
            Thread.sleep(1000);
        }
        assertEquals(1, listener2.countEvents());
        assertEquals(3, listener1.countEvents());

        // stop instance2
        logger.info("reuseClusterIdTest : STOP 2");
        instance2.stop();

        for(int i = 0; i < 2; i++) {
            instance1.heartbeatsAndCheckView();
            Thread.sleep(1000);
        }
        assertEquals(5, listener1.countEvents());

        // re-create instance2, with same clusterNodeId/slingId
        logger.info("reuseClusterIdTest : creating instance2b.");
        OakVirtualInstanceBuilder builder2b = createInstanceUsingExistingRepo(builder1,
                "instance2b", 1, 5);
        builder2b.setSlingId(builder2.getSlingId());
        builder2b.getLease().setClusterNodeIdHint(2);
        builder2b.getSimulatedLeaseCollection().incSeqNum();
        // 2. start instance 2 only partially -> do not call builder2.build() but just updateLease()
        builder2b.updateLease();

        for(int i = 0; i < 2; i++) {
            instance1.heartbeatsAndCheckView();
            Thread.sleep(1000);
        }
        instance1.heartbeatsAndCheckView();
        assertEquals(5 /*not yet CHANGING */, listener1.countEvents());

        // finish instance2 startup
        VirtualInstance instance2b = builder2b.build();
        DummyListener listener2b = createDummyListener(instance2b);

        for(int i = 0; i < 3; i++) {
            instance1.heartbeatsAndCheckView();
            instance2b.heartbeatsAndCheckView();
            Thread.sleep(1000);
        }
        instance1.heartbeatsAndCheckView();
        instance2b.heartbeatsAndCheckView();
        assertEquals(0, listener2b.countEvents());
        assertEquals(7, listener1.countEvents());

        for(int i = 0; i < 4; i++) {
            Thread.sleep(1000);
            instance1.heartbeatsAndCheckView();
            instance2b.heartbeatsAndCheckView();
        }
        waitForEquals(listener2b, 1, 2000);
        waitForEquals(listener1, 7, 2000); // had a flaky failure so giving it 2 sec
    }

    @Test
    public void testTopologyChangeWithOneSlowMember() throws Exception {
        logger.info("testTopologyChangeWithOneSlowMember : START - creating instance1.");
        OakVirtualInstanceBuilder builder1 = createInstanceWithNewRepo("instance1",
                "/foo1/barrio/foo1/", 5, 1);
        // 1. start instance 1 normally -> call builder1.build()
        VirtualInstance instance1 = builder1.build();
        DummyListener listener1 = createDummyListener(instance1);

        // instance2 normally
        logger.info("testTopologyChangeWithOneSlowMember : creating instance2.");
        OakVirtualInstanceBuilder builder2 = createInstanceUsingExistingRepo(builder1,
                "instance2", 1, 1);
        // 2. start instance 2 normally -> call builder2.build()
        VirtualInstance instance2 = builder2.build();
        DummyListener listener2 = createDummyListener(instance2);

        // a bunch of heartbeats
        for(int i=0; i<7; i++) {
            instance1.heartbeatsAndCheckView();
            instance2.heartbeatsAndCheckView();
            Thread.sleep(1000);
        }

        logger.info("testTopologyChangeWithOneSlowMember : check normal situation established");
        assertTrue("actual : " + listener1.countEvents(), listener1.countEvents() == 1 || listener1.countEvents() == 3);
        assertTrue("actual : " + listener2.countEvents(), listener2.countEvents() == 1 || listener2.countEvents() == 3);

        listener1.clearEvents();
        listener2.clearEvents();

        // trigger a lightning-fast change in the discovery-lite
        builder1.getLease().incSeqNum();
        // inc seqnum by 2, then it is actually valid (an instance joining/leaving quickly)
        builder1.getLease().incSeqNum();

        // now let instance2 behave slow : it still has a valid lease but doesn't do the syncToken quickly
        for(int i=0; i<4; i++) {
            instance1.heartbeatsAndCheckView();
            Thread.sleep(1000);
        }
        logger.info("testTopologyChangeWithOneSlowMember : check listener1 having gotten a TOPOLOGY_CHANGING but listener2 not yet");
        assertEquals(1, listener1.countEvents()); // a TOPOLOGY_CHANGING only
        assertEquals(TopologyEvent.Type.TOPOLOGY_CHANGING, listener1.getEvents().get(0).getType());
        assertEquals(0, listener2.countEvents());
    }

    private OakVirtualInstanceBuilder createInstanceUsingExistingRepo(
            OakVirtualInstanceBuilder builder1, String debugName, int minEventDelay, int joinerDelay) throws Exception {
        OakVirtualInstanceBuilder builder2 =
                (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName(debugName)
                .useRepositoryOf(builder1)
                .setConnectorPingInterval(999999)
                .setConnectorPingTimeout(999999);
        builder2.getConfig().setMinEventDelay(minEventDelay);
        builder2.getConfig().setJoinerDelaySeconds(joinerDelay);
        builder2.getConfig().setSyncTokenEnabled(true);
        builder2.getConfig().setSuppressPartiallyStartedInstance(true);
        return builder2;
    }

    private DummyListener createDummyListener(VirtualInstance virtualInstance) {
        final DummyListener newListener = new DummyListener();
        final OakDiscoveryService ds = (OakDiscoveryService) virtualInstance.getDiscoveryService();
        ds.bindTopologyEventListener(newListener);
        return newListener;
    }

    private OakVirtualInstanceBuilder createInstanceWithNewRepo(String debugName,
            String discoveryPath, int minEventDelay, long joinerDelay) throws Exception {
        final OakVirtualInstanceBuilder builder = (OakVirtualInstanceBuilder) new OakVirtualInstanceBuilder()
                .setDebugName(debugName).newRepository(discoveryPath, true)
                .setConnectorPingInterval(999999).setConnectorPingTimeout(999999);
        builder.getConfig().setMinEventDelay(minEventDelay);
        builder.setMinEventDelay(minEventDelay);
        builder.getConfig().setJoinerDelaySeconds(joinerDelay);
        builder.getConfig().setSyncTokenEnabled(true);
        builder.getConfig().setSuppressPartiallyStartedInstance(true);
        return builder;
    }
}