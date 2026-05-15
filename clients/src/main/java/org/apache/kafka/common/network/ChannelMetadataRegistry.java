/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.network;

import org.apache.kafka.common.Uuid;

import java.io.Closeable;
import java.util.Optional;

/**
 * Metadata about a channel is provided in various places in the network stack. This
 * registry is used as a common place to collect them.
 */
public interface ChannelMetadataRegistry extends Closeable {

    /**
     * Register information about the SSL cipher we are using.
     *  Re-registering the information will overwrite the previous one.
     */
    void registerCipherInformation(CipherInformation cipherInformation);

    /**
     * Get the currently registered cipher information.
     */
    CipherInformation cipherInformation();

    /**
     * Register information about the client we are using.
     * Depending on the clients, the ApiVersionsRequest could be received
     * multiple times or not at all. Re-registering the information will
     * overwrite the previous one.
     */
    void registerClientInformation(ClientInformation clientInformation);

    /**
     * Get the currently registered client information.
     */
    ClientInformation clientInformation();

    /**
     * For KIP-1313, register the first client instance ID that is seen on the first v2 header request
     * on this channel. The value reflects what the client placed in the request header, either:
     *
     * <ul>
     *   <li>{@code Optional.of(uuid)} when the client supplied an ID</li>
     *   <li>{@code Optional.empty()} when the client did not supply an ID</li>
     * </ul>
     *
     * Subsequent v2 header requests are validated in KafkaApis.handle().
     */
    void registerClientInstanceId(Optional<Uuid> clientInstanceId);

    /**
     * Returns the registered client instance ID. Returns an empty {@code Optional} either when
     * no v2 header  request has been seen yet, or when the first seen v2 header request had no
     * client instance ID.
     */
    Optional<Uuid> clientInstanceId();

    /**
     * For KIP-1313, determines whether {@link #registerClientInstanceId(Optional)} has been invoked
     * on this channel.
     */
    boolean wasClientInstanceIdRegistered();

    /**
     * Unregister everything that has been registered and close the registry.
     */
    void close();
}
