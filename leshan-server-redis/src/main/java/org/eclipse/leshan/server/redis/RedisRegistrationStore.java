/*******************************************************************************
 * Copyright (c) 2016 Sierra Wireless and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *     Sierra Wireless - initial API and implementation
 *     Achim Kraus (Bosch Software Innovations GmbH) - rename CorrelationContext to
 *                                                     EndpointContext
 *     Achim Kraus (Bosch Software Innovations GmbH) - update to modified 
 *                                                     ObservationStore API
 *******************************************************************************/
package org.eclipse.leshan.server.redis;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.californium.core.coap.Token;
import org.eclipse.californium.elements.EndpointContext;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.server.Startable;
import org.eclipse.leshan.server.Stoppable;
import org.eclipse.leshan.server.californium.CaliforniumRegistrationStore;
import org.eclipse.leshan.server.californium.ObserveUtil;
import org.eclipse.leshan.server.redis.serialization.ObservationSerDes;
import org.eclipse.leshan.server.redis.serialization.RegistrationSerDes;
import org.eclipse.leshan.server.registration.Deregistration;
import org.eclipse.leshan.server.registration.ExpirationListener;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.registration.RegistrationUpdate;
import org.eclipse.leshan.server.registration.UpdatedRegistration;
import org.eclipse.leshan.util.NamedThreadFactory;
import org.eclipse.leshan.util.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.util.Pool;

/**
 * A RegistrationStore which stores registrations and observations in Redis.
 */
public class RedisRegistrationStore implements CaliforniumRegistrationStore, Startable, Stoppable {

    /** Default time in seconds between 2 cleaning tasks (used to remove expired registration). */
    public static final long DEFAULT_CLEAN_PERIOD = 60;
    public static final int DEFAULT_CLEAN_LIMIT = 500;
    /** Defaut Extra time for registration lifetime in seconds */
    public static final long DEFAULT_GRACE_PERIOD = 0;

    private static final Logger LOG = LoggerFactory.getLogger(RedisRegistrationStore.class);

    // Redis key prefixes
    private static final String REG_EP = "REG:EP:"; // (Endpoint => Registration)
    private static final String REG_EP_REGID_IDX = "EP:REGID:"; // secondary index key (Registration ID => Endpoint)
    private static final String REG_EP_ADDR_IDX = "EP:ADDR:"; // secondary index key (Socket Address => Endpoint)
    private static final String LOCK_EP = "LOCK:EP:";
    private static final byte[] OBS_TKN = "OBS:TKN:".getBytes(UTF_8);
    private static final String OBS_TKNS_REGID_IDX = "TKNS:REGID:"; // secondary index (token list by registration)
    private static final byte[] EXP_EP = "EXP:EP".getBytes(UTF_8); // a sorted set used for registration expiration
                                                                   // (expiration date, Endpoint)

    private final Pool<Jedis> pool;

    // Listener use to notify when a registration expires
    private ExpirationListener expirationListener;

    private final ScheduledExecutorService schedExecutor;
    private boolean started = false;

    private final long cleanPeriod; // in seconds
    private final int cleanLimit; // maximum number to clean in a clean period
    private final long gracePeriod; // in seconds

    public RedisRegistrationStore(Pool<Jedis> p) {
        this(p, DEFAULT_CLEAN_PERIOD, DEFAULT_GRACE_PERIOD, DEFAULT_CLEAN_LIMIT); // default clean period 60s
    }

    public RedisRegistrationStore(Pool<Jedis> p, long cleanPeriodInSec, long lifetimeGracePeriodInSec, int cleanLimit) {
        this(p, Executors.newScheduledThreadPool(1,
                new NamedThreadFactory(String.format("RedisRegistrationStore Cleaner (%ds)", cleanPeriodInSec))),
                cleanPeriodInSec, lifetimeGracePeriodInSec, cleanLimit);
    }

    public RedisRegistrationStore(Pool<Jedis> p, ScheduledExecutorService schedExecutor, long cleanPeriodInSec,
            long lifetimeGracePeriodInSec, int cleanLimit) {
        this.pool = p;
        this.schedExecutor = schedExecutor;
        this.cleanPeriod = cleanPeriodInSec;
        this.cleanLimit = cleanLimit;
        this.gracePeriod = lifetimeGracePeriodInSec;
    }

    /* *************** Redis Key utility function **************** */

    private byte[] toKey(byte[] prefix, byte[] key) {
        byte[] result = new byte[prefix.length + key.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(key, 0, result, prefix.length, key.length);
        return result;
    }

    private byte[] toKey(String prefix, String registrationID) {
        return (prefix + registrationID).getBytes();
    }

    private byte[] toLockKey(String endpoint) {
        return toKey(LOCK_EP, endpoint);
    }

    private byte[] toLockKey(byte[] endpoint) {
        return toKey(LOCK_EP.getBytes(UTF_8), endpoint);
    }

    /* *************** Leshan Registration API **************** */

    @Override
    public Deregistration addRegistration(Registration registration) {
        try (Jedis j = pool.getResource()) {
            byte[] lockValue = null;
            byte[] lockKey = toLockKey(registration.getEndpoint());

            try {
                lockValue = RedisLock.acquire(j, lockKey);

                // add registration
                byte[] k = toEndpointKey(registration.getEndpoint());
                byte[] old = j.getSet(k, serializeReg(registration));

                // add registration: secondary indexes
                byte[] regid_idx = toRegIdKey(registration.getId());
                j.set(regid_idx, registration.getEndpoint().getBytes(UTF_8));
                byte[] addr_idx = toRegAddrKey(registration.getSocketAddress());
                j.set(addr_idx, registration.getEndpoint().getBytes(UTF_8));

                // Add or update expiration
                addOrUpdateExpiration(j, registration);

                if (old != null) {
                    Registration oldRegistration = deserializeReg(old);
                    // remove old secondary index
                    if (!registration.getId().equals(oldRegistration.getId()))
                        j.del(toRegIdKey(oldRegistration.getId()));
                    if (!oldRegistration.getSocketAddress().equals(registration.getSocketAddress())) {
                        removeAddrIndex(j, oldRegistration);
                    }
                    // remove old observation
                    Collection<Observation> obsRemoved = unsafeRemoveAllObservations(j, oldRegistration.getId());

                    return new Deregistration(oldRegistration, obsRemoved);
                }

                return null;
            } finally {
                RedisLock.release(j, lockKey, lockValue);
            }
        }
    }

    @Override
    public UpdatedRegistration updateRegistration(RegistrationUpdate update) {
        try (Jedis j = pool.getResource()) {

            // Fetch the registration ep by registration ID index
            byte[] ep = j.get(toRegIdKey(update.getRegistrationId()));
            if (ep == null) {
                return null;
            }

            byte[] lockValue = null;
            byte[] lockKey = toLockKey(ep);
            try {
                lockValue = RedisLock.acquire(j, lockKey);

                // Fetch the registration
                byte[] data = j.get(toEndpointKey(ep));
                if (data == null) {
                    return null;
                }

                Registration r = deserializeReg(data);

                Registration updatedRegistration = update.update(r);

                // Store the new registration
                j.set(toEndpointKey(updatedRegistration.getEndpoint()), serializeReg(updatedRegistration));

                // Add or update expiration
                addOrUpdateExpiration(j, updatedRegistration);

                // Update secondary index :
                // If registration is already associated to this address we don't care as we only want to keep the most
                // recent binding.
                byte[] addr_idx = toRegAddrKey(updatedRegistration.getSocketAddress());
                j.set(addr_idx, updatedRegistration.getEndpoint().getBytes(UTF_8));
                if (!r.getSocketAddress().equals(updatedRegistration.getSocketAddress())) {
                    removeAddrIndex(j, r);
                }

                return new UpdatedRegistration(r, updatedRegistration);

            } finally {
                RedisLock.release(j, lockKey, lockValue);
            }
        }
    }

    @Override
    public Registration getRegistration(String registrationId) {
        try (Jedis j = pool.getResource()) {
            return getRegistration(j, registrationId);
        }
    }

    @Override
    public Registration getRegistrationByEndpoint(String endpoint) {
        Validate.notNull(endpoint);
        try (Jedis j = pool.getResource()) {
            byte[] data = j.get(toEndpointKey(endpoint));
            if (data == null) {
                return null;
            }
            return deserializeReg(data);
        }
    }

    @Override
    public Registration getRegistrationByAdress(InetSocketAddress address) {
        Validate.notNull(address);
        try (Jedis j = pool.getResource()) {
            byte[] ep = j.get(toRegAddrKey(address));
            if (ep == null) {
                return null;
            }
            byte[] data = j.get(toEndpointKey(ep));
            if (data == null) {
                return null;
            }
            return deserializeReg(data);
        }
    }

    @Override
    public Iterator<Registration> getAllRegistrations() {
        return new RedisIterator(pool, new ScanParams().match(REG_EP + "*").count(100));
    }

    protected class RedisIterator implements Iterator<Registration> {

        private Pool<Jedis> pool;
        private ScanParams scanParams;

        private String cursor;
        private List<Registration> scanResult;

        public RedisIterator(Pool<Jedis> p, ScanParams scanParams) {
            pool = p;
            this.scanParams = scanParams;
            // init scan result
            scanNext("0");
        }

        private void scanNext(String cursor) {
            try (Jedis j = pool.getResource()) {
                do {
                    ScanResult<byte[]> sr = j.scan(cursor.getBytes(), scanParams);

                    this.scanResult = new ArrayList<>();
                    if (sr.getResult() != null && !sr.getResult().isEmpty()) {
                        for (byte[] value : j.mget(sr.getResult().toArray(new byte[][] {}))) {
                            this.scanResult.add(deserializeReg(value));
                        }
                    }

                    cursor = sr.getStringCursor();
                } while (!"0".equals(cursor) && scanResult.isEmpty());

                this.cursor = cursor;
            }
        }

        @Override
        public boolean hasNext() {
            if (!scanResult.isEmpty()) {
                return true;
            }
            if ("0".equals(cursor)) {
                // no more elements to scan
                return false;
            }

            // read more elements
            scanNext(cursor);
            return !scanResult.isEmpty();
        }

        @Override
        public Registration next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return scanResult.remove(0);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Deregistration removeRegistration(String registrationId) {
        try (Jedis j = pool.getResource()) {
            return removeRegistration(j, registrationId, false);
        }
    }

    private Deregistration removeRegistration(Jedis j, String registrationId, boolean removeOnlyIfNotAlive) {
        // fetch the client ep by registration ID index
        byte[] ep = j.get(toRegIdKey(registrationId));
        if (ep == null) {
            return null;
        }

        byte[] lockValue = null;
        byte[] lockKey = toLockKey(ep);
        try {
            lockValue = RedisLock.acquire(j, lockKey);

            // fetch the client
            byte[] data = j.get(toEndpointKey(ep));
            if (data == null) {
                return null;
            }
            Registration r = deserializeReg(data);

            if (!removeOnlyIfNotAlive || !r.isAlive(gracePeriod)) {
                long nbRemoved = j.del(toRegIdKey(r.getId()));
                if (nbRemoved > 0) {
                    j.del(toEndpointKey(r.getEndpoint()));
                    Collection<Observation> obsRemoved = unsafeRemoveAllObservations(j, r.getId());
                    removeAddrIndex(j, r);
                    removeExpiration(j, r);
                    return new Deregistration(r, obsRemoved);
                }
            }
            return null;
        } finally {
            RedisLock.release(j, lockKey, lockValue);
        }
    }

    private void removeAddrIndex(Jedis j, Registration registration) {
        byte[] regAddrKey = toRegAddrKey(registration.getSocketAddress());
        byte[] epFromAddr = j.get(regAddrKey);
        if (Arrays.equals(epFromAddr, registration.getEndpoint().getBytes(UTF_8))) {
            j.del(regAddrKey);
        }
    }

    private void addOrUpdateExpiration(Jedis j, Registration registration) {
        j.zadd(EXP_EP, registration.getExpirationTimeStamp(gracePeriod), registration.getEndpoint().getBytes(UTF_8));
    }

    private void removeExpiration(Jedis j, Registration registration) {
        j.zrem(EXP_EP, registration.getEndpoint().getBytes(UTF_8));
    }

    private byte[] toRegIdKey(String registrationId) {
        return toKey(REG_EP_REGID_IDX, registrationId);
    }

    private byte[] toRegAddrKey(InetSocketAddress addr) {
        return toKey(REG_EP_ADDR_IDX, addr.getAddress().toString() + ":" + addr.getPort());
    }

    private byte[] toEndpointKey(String endpoint) {
        return toKey(REG_EP, endpoint);
    }

    private byte[] toEndpointKey(byte[] endpoint) {
        return toKey(REG_EP.getBytes(UTF_8), endpoint);
    }

    private byte[] serializeReg(Registration registration) {
        return RegistrationSerDes.bSerialize(registration);
    }

    private Registration deserializeReg(byte[] data) {
        return RegistrationSerDes.deserialize(data);
    }

    /* *************** Leshan Observation API **************** */

    /*
     * The observation is not persisted here, it is done by the Californium layer (in the implementation of the
     * org.eclipse.californium.core.observe.ObservationStore#add method)
     */
    @Override
    public Collection<Observation> addObservation(String registrationId, Observation observation) {

        List<Observation> removed = new ArrayList<>();
        try (Jedis j = pool.getResource()) {

            // fetch the client ep by registration ID index
            byte[] ep = j.get(toRegIdKey(registrationId));
            if (ep == null) {
                return null;
            }

            byte[] lockValue = null;
            byte[] lockKey = toLockKey(ep);

            try {
                lockValue = RedisLock.acquire(j, lockKey);

                // cancel existing observations for the same path and registration id.
                for (Observation obs : getObservations(j, registrationId)) {
                    if (observation.getPath().equals(obs.getPath())
                            && !Arrays.equals(observation.getId(), obs.getId())) {
                        removed.add(obs);
                        unsafeRemoveObservation(j, registrationId, obs.getId());
                    }
                }

            } finally {
                RedisLock.release(j, lockKey, lockValue);
            }
        }
        return removed;
    }

    @Override
    public Observation removeObservation(String registrationId, byte[] observationId) {
        try (Jedis j = pool.getResource()) {

            // fetch the client ep by registration ID index
            byte[] ep = j.get(toRegIdKey(registrationId));
            if (ep == null) {
                return null;
            }

            // remove observation
            byte[] lockValue = null;
            byte[] lockKey = toLockKey(ep);
            try {
                lockValue = RedisLock.acquire(j, lockKey);

                Observation observation = build(get(new Token(observationId)));
                if (observation != null && registrationId.equals(observation.getRegistrationId())) {
                    unsafeRemoveObservation(j, registrationId, observationId);
                    return observation;
                }
                return null;

            } finally {
                RedisLock.release(j, lockKey, lockValue);
            }
        }
    }

    @Override
    public Observation getObservation(String registrationId, byte[] observationId) {
        return build(get(new Token(observationId)));
    }

    @Override
    public Collection<Observation> getObservations(String registrationId) {
        try (Jedis j = pool.getResource()) {
            return getObservations(j, registrationId);
        }
    }

    private Collection<Observation> getObservations(Jedis j, String registrationId) {
        Collection<Observation> result = new ArrayList<>();
        for (byte[] token : j.lrange(toKey(OBS_TKNS_REGID_IDX, registrationId), 0, -1)) {
            byte[] obs = j.get(toKey(OBS_TKN, token));
            if (obs != null) {
                result.add(build(deserializeObs(obs)));
            }
        }
        return result;
    }

    @Override
    public Collection<Observation> removeObservations(String registrationId) {
        try (Jedis j = pool.getResource()) {
            // check registration exists
            Registration registration = getRegistration(j, registrationId);
            if (registration == null)
                return Collections.emptyList();

            // get endpoint and create lock
            String endpoint = registration.getEndpoint();
            byte[] lockValue = null;
            byte[] lockKey = toKey(LOCK_EP, endpoint);
            try {
                lockValue = RedisLock.acquire(j, lockKey);

                return unsafeRemoveAllObservations(j, registrationId);
            } finally {
                RedisLock.release(j, lockKey, lockValue);
            }
        }
    }

    /* *************** Californium ObservationStore API **************** */

    @Override
    public org.eclipse.californium.core.observe.Observation putIfAbsent(Token token,
            org.eclipse.californium.core.observe.Observation obs) {
        return add(token, obs, true);
    }

    @Override
    public org.eclipse.californium.core.observe.Observation put(Token token,
            org.eclipse.californium.core.observe.Observation obs) {
        return add(token, obs, false);
    }

    private org.eclipse.californium.core.observe.Observation add(Token token,
            org.eclipse.californium.core.observe.Observation obs, boolean ifAbsent) {
        String endpoint = ObserveUtil.validateCoapObservation(obs);
        org.eclipse.californium.core.observe.Observation previousObservation = null;

        try (Jedis j = pool.getResource()) {
            byte[] lockValue = null;
            byte[] lockKey = toKey(LOCK_EP, endpoint);
            try {
                lockValue = RedisLock.acquire(j, lockKey);

                String registrationId = ObserveUtil.extractRegistrationId(obs);
                if (!j.exists(toRegIdKey(registrationId)))
                    throw new IllegalStateException("no registration for this Id");
                byte[] key = toKey(OBS_TKN, obs.getRequest().getToken().getBytes());
                byte[] serializeObs = serializeObs(obs);
                byte[] previousValue = null;
                if (ifAbsent) {
                    previousValue = j.get(key);
                    if (previousValue == null || previousValue.length == 0) {
                        j.set(key, serializeObs);
                    } else {
                        return deserializeObs(previousValue);
                    }
                } else {
                    previousValue = j.getSet(key, serializeObs);
                }

                // secondary index to get the list by registrationId
                j.lpush(toKey(OBS_TKNS_REGID_IDX, registrationId), obs.getRequest().getToken().getBytes());

                // log any collisions
                if (previousValue != null && previousValue.length != 0) {
                    previousObservation = deserializeObs(previousValue);
                    LOG.warn(
                            "Token collision ? observation from request [{}] will be replaced by observation from request [{}] ",
                            previousObservation.getRequest(), obs.getRequest());
                }
            } finally {
                RedisLock.release(j, lockKey, lockValue);
            }
        }
        return previousObservation;
    }

    @Override
    public void remove(Token token) {
        try (Jedis j = pool.getResource()) {
            byte[] tokenKey = toKey(OBS_TKN, token.getBytes());

            // fetch the observation by token
            byte[] serializedObs = j.get(tokenKey);
            if (serializedObs == null)
                return;

            org.eclipse.californium.core.observe.Observation obs = deserializeObs(serializedObs);
            String registrationId = ObserveUtil.extractRegistrationId(obs);
            Registration registration = getRegistration(j, registrationId);
            if (registration == null) {
                LOG.warn("Unable to remove observation {}, registration {} does not exist anymore", obs.getRequest(),
                        registrationId);
                return;
            }

            String endpoint = registration.getEndpoint();
            byte[] lockValue = null;
            byte[] lockKey = toKey(LOCK_EP, endpoint);
            try {
                lockValue = RedisLock.acquire(j, lockKey);

                unsafeRemoveObservation(j, registrationId, token.getBytes());
            } finally {
                RedisLock.release(j, lockKey, lockValue);
            }
        }

    }

    @Override
    public org.eclipse.californium.core.observe.Observation get(Token token) {
        try (Jedis j = pool.getResource()) {
            byte[] obs = j.get(toKey(OBS_TKN, token.getBytes()));
            if (obs == null) {
                return null;
            } else {
                return deserializeObs(obs);
            }
        }
    }

    /* *************** Observation utility functions **************** */

    private Registration getRegistration(Jedis j, String registrationId) {
        byte[] ep = j.get(toRegIdKey(registrationId));
        if (ep == null) {
            return null;
        }
        byte[] data = j.get(toEndpointKey(ep));
        if (data == null) {
            return null;
        }

        return deserializeReg(data);
    }

    private void unsafeRemoveObservation(Jedis j, String registrationId, byte[] observationId) {
        if (j.del(toKey(OBS_TKN, observationId)) > 0L) {
            j.lrem(toKey(OBS_TKNS_REGID_IDX, registrationId), 0, observationId);
        }
    }

    private Collection<Observation> unsafeRemoveAllObservations(Jedis j, String registrationId) {
        Collection<Observation> removed = new ArrayList<>();
        byte[] regIdKey = toKey(OBS_TKNS_REGID_IDX, registrationId);

        // fetch all observations by token
        for (byte[] token : j.lrange(regIdKey, 0, -1)) {
            byte[] obs = j.get(toKey(OBS_TKN, token));
            if (obs != null) {
                removed.add(build(deserializeObs(obs)));
            }
            j.del(toKey(OBS_TKN, token));
        }
        j.del(regIdKey);

        return removed;
    }

    @Override
    public void setContext(Token token, EndpointContext correlationContext) {
        // TODO should be implemented
    }

    private byte[] serializeObs(org.eclipse.californium.core.observe.Observation obs) {
        return ObservationSerDes.serialize(obs);
    }

    private org.eclipse.californium.core.observe.Observation deserializeObs(byte[] data) {
        return ObservationSerDes.deserialize(data);
    }

    private Observation build(org.eclipse.californium.core.observe.Observation cfObs) {
        if (cfObs == null)
            return null;

        return ObserveUtil.createLwM2mObservation(cfObs.getRequest());
    }

    /* *************** Expiration handling **************** */

    /**
     * Start regular cleanup of dead registrations.
     */
    @Override
    public synchronized void start() {
        if (!started) {
            started = true;
            schedExecutor.scheduleAtFixedRate(new Cleaner(), cleanPeriod, cleanPeriod, TimeUnit.SECONDS);
        }
    }

    /**
     * Stop the underlying cleanup of the registrations.
     */
    @Override
    public synchronized void stop() {
        if (started) {
            started = false;
            schedExecutor.shutdownNow();
            try {
                schedExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOG.warn("Clean up registration thread was interrupted.", e);
            }
        }
    }

    private class Cleaner implements Runnable {

        @Override
        public void run() {

            try (Jedis j = pool.getResource()) {
                Set<byte[]> endpointsExpired = j.zrangeByScore(EXP_EP, Double.NEGATIVE_INFINITY,
                        System.currentTimeMillis(), 0, cleanLimit);

                for (byte[] endpoint : endpointsExpired) {
                    Registration r = deserializeReg(j.get(toEndpointKey(endpoint)));
                    if (!r.isAlive(gracePeriod)) {
                        Deregistration dereg = removeRegistration(j, r.getId(), true);
                        if (dereg != null)
                            expirationListener.registrationExpired(dereg.getRegistration(), dereg.getObservations());
                    }
                }
            } catch (Exception e) {
                LOG.warn("Unexpected Exception while registration cleaning", e);
            }
        }
    }

    @Override
    public void setExpirationListener(ExpirationListener listener) {
        expirationListener = listener;
    }

    @Override
    public void setExecutor(ScheduledExecutorService executor) {
        // TODO we could reuse californium executor ?
    }
}
