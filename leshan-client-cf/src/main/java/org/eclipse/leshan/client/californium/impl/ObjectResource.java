/*******************************************************************************
 * Copyright (c) 2015 Sierra Wireless and others.
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
 *     Achim Kraus (Bosch Software Innovations GmbH) - use ObserveRelationFilter
 *     Achim Kraus (Bosch Software Innovations GmbH) - use ServerIdentity
 *     Achim Kraus (Bosch Software Innovations GmbH) - implement POST "/oid/iid" 
 *                                                     as UPDATE instance
 *******************************************************************************/
package org.eclipse.leshan.client.californium.impl;

import static org.eclipse.leshan.core.californium.ResponseCodeUtil.toCoapResponseCode;

import java.util.List;

import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.leshan.Link;
import org.eclipse.leshan.client.request.ServerIdentity;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.resource.NotifySender;
import org.eclipse.leshan.client.servers.BootstrapHandler;
import org.eclipse.leshan.core.attributes.AttributeSet;
import org.eclipse.leshan.core.model.LwM2mModel;
import org.eclipse.leshan.core.model.StaticModel;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.node.codec.CodecException;
import org.eclipse.leshan.core.node.codec.LwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.LwM2mNodeEncoder;
import org.eclipse.leshan.core.request.BootstrapDeleteRequest;
import org.eclipse.leshan.core.request.BootstrapWriteRequest;
import org.eclipse.leshan.core.request.ContentFormat;
import org.eclipse.leshan.core.request.CreateRequest;
import org.eclipse.leshan.core.request.DeleteRequest;
import org.eclipse.leshan.core.request.DiscoverRequest;
import org.eclipse.leshan.core.request.DownlinkRequest;
import org.eclipse.leshan.core.request.ExecuteRequest;
import org.eclipse.leshan.core.request.ObserveRequest;
import org.eclipse.leshan.core.request.ReadRequest;
import org.eclipse.leshan.core.request.WriteAttributesRequest;
import org.eclipse.leshan.core.request.WriteRequest;
import org.eclipse.leshan.core.request.WriteRequest.Mode;
import org.eclipse.leshan.core.response.BootstrapDeleteResponse;
import org.eclipse.leshan.core.response.BootstrapWriteResponse;
import org.eclipse.leshan.core.response.CreateResponse;
import org.eclipse.leshan.core.response.DeleteResponse;
import org.eclipse.leshan.core.response.DiscoverResponse;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ObserveResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteAttributesResponse;
import org.eclipse.leshan.core.response.WriteResponse;

/**
 * A CoAP {@link Resource} in charge of handling requests for of a lwM2M Object.
 */
public class ObjectResource extends LwM2mClientCoapResource implements NotifySender {

    private final LwM2mObjectEnabler nodeEnabler;
    private final LwM2mNodeEncoder encoder;
    private final LwM2mNodeDecoder decoder;

    public ObjectResource(LwM2mObjectEnabler nodeEnabler, BootstrapHandler bootstrapHandler, LwM2mNodeEncoder encoder,
            LwM2mNodeDecoder decoder) {
        super(Integer.toString(nodeEnabler.getId()), bootstrapHandler);
        this.nodeEnabler = nodeEnabler;
        this.nodeEnabler.setNotifySender(this);
        this.encoder = encoder;
        this.decoder = decoder;
        setObservable(true);
    }

    @Override
    public void handleGET(CoapExchange exchange) {
        ServerIdentity identity = extractServerIdentity(exchange);
        String URI = exchange.getRequestOptions().getUriPathString();

        // Manage Discover Request
        if (exchange.getRequestOptions().getAccept() == MediaTypeRegistry.APPLICATION_LINK_FORMAT) {
            DiscoverResponse response = nodeEnabler.discover(identity, new DiscoverRequest(URI));
            if (response.getCode().isError()) {
                exchange.respond(toCoapResponseCode(response.getCode()), response.getErrorMessage());
            } else {
                exchange.respond(toCoapResponseCode(response.getCode()), Link.serialize(response.getObjectLinks()),
                        MediaTypeRegistry.APPLICATION_LINK_FORMAT);
            }
        } else {
            // handle content format for Read and Observe Request
            ContentFormat requestedContentFormat = null;
            if (exchange.getRequestOptions().hasAccept()) {
                // If an request ask for a specific content format, use it (if we support it)
                requestedContentFormat = ContentFormat.fromCode(exchange.getRequestOptions().getAccept());
                if (!encoder.isSupported(requestedContentFormat)) {
                    exchange.respond(ResponseCode.NOT_ACCEPTABLE);
                    return;
                }
            }

            // Manage Observe Request
            if (exchange.getRequestOptions().hasObserve()) {
                ObserveRequest observeRequest = new ObserveRequest(URI);
                ObserveResponse response = nodeEnabler.observe(identity, observeRequest);
                if (response.getCode() == org.eclipse.leshan.ResponseCode.CONTENT) {
                    LwM2mPath path = new LwM2mPath(URI);
                    LwM2mNode content = response.getContent();
                    LwM2mModel model = new StaticModel(nodeEnabler.getObjectModel());
                    ContentFormat format = getContentFormat(observeRequest, requestedContentFormat);
                    exchange.respond(ResponseCode.CONTENT, encoder.encode(content, format, path, model),
                            format.getCode());
                    return;
                } else {
                    exchange.respond(toCoapResponseCode(response.getCode()), response.getErrorMessage());
                    return;
                }
            }
            // Manage Read Request
            else {
                ReadRequest readRequest = new ReadRequest(URI);
                ReadResponse response = nodeEnabler.read(identity, readRequest);
                if (response.getCode() == org.eclipse.leshan.ResponseCode.CONTENT) {
                    LwM2mPath path = new LwM2mPath(URI);
                    LwM2mNode content = response.getContent();
                    LwM2mModel model = new StaticModel(nodeEnabler.getObjectModel());
                    ContentFormat format = getContentFormat(readRequest, requestedContentFormat);
                    exchange.respond(ResponseCode.CONTENT, encoder.encode(content, format, path, model),
                            format.getCode());
                    return;
                } else {
                    exchange.respond(toCoapResponseCode(response.getCode()), response.getErrorMessage());
                    return;
                }
            }
        }
    }

    private ContentFormat getContentFormat(DownlinkRequest<?> request, ContentFormat requestedContentFormat) {
        if (requestedContentFormat != null) {
            // we already check before this content format is supported.
            return requestedContentFormat;
        }

        ContentFormat format = nodeEnabler.getDefaultEncodingFormat(request);
        return format == null ? ContentFormat.DEFAULT : format;
    }

    @Override
    public void handlePUT(CoapExchange coapExchange) {
        ServerIdentity identity = extractServerIdentity(coapExchange);
        String URI = coapExchange.getRequestOptions().getUriPathString();

        // get Observe Spec
        AttributeSet attributes = null;
        if (coapExchange.advanced().getRequest().getOptions().getURIQueryCount() != 0) {
            List<String> uriQueries = coapExchange.advanced().getRequest().getOptions().getUriQuery();
            attributes = AttributeSet.parse(uriQueries);
        }

        // Manage Write Attributes Request
        if (attributes != null) {
            WriteAttributesResponse response = nodeEnabler.writeAttributes(identity,
                    new WriteAttributesRequest(URI, attributes));
            if (response.getCode().isError()) {
                coapExchange.respond(toCoapResponseCode(response.getCode()), response.getErrorMessage());
            } else {
                coapExchange.respond(toCoapResponseCode(response.getCode()));
            }
            return;
        }
        // Manage Write and Bootstrap Write Request (replace)
        else {
            LwM2mPath path = new LwM2mPath(URI);

            if (!coapExchange.getRequestOptions().hasContentFormat()) {
                handleInvalidRequest(coapExchange, "Content Format is mandatory");
                return;
            }

            ContentFormat contentFormat = ContentFormat.fromCode(coapExchange.getRequestOptions().getContentFormat());
            if (!decoder.isSupported(contentFormat)) {
                coapExchange.respond(ResponseCode.UNSUPPORTED_CONTENT_FORMAT);
                return;
            }
            LwM2mNode lwM2mNode;
            try {
                LwM2mModel model = new StaticModel(nodeEnabler.getObjectModel());
                lwM2mNode = decoder.decode(coapExchange.getRequestPayload(), contentFormat, path, model);
                if (identity.isLwm2mBootstrapServer()) {
                    BootstrapWriteResponse response = nodeEnabler.write(identity,
                            new BootstrapWriteRequest(path, lwM2mNode, contentFormat));
                    if (response.getCode().isError()) {
                        coapExchange.respond(toCoapResponseCode(response.getCode()), response.getErrorMessage());
                    } else {
                        coapExchange.respond(toCoapResponseCode(response.getCode()));
                    }
                } else {
                    WriteResponse response = nodeEnabler.write(identity,
                            new WriteRequest(Mode.REPLACE, contentFormat, URI, lwM2mNode));
                    if (response.getCode().isError()) {
                        coapExchange.respond(toCoapResponseCode(response.getCode()), response.getErrorMessage());
                    } else {
                        coapExchange.respond(toCoapResponseCode(response.getCode()));
                    }
                }

                return;
            } catch (CodecException e) {
                handleInvalidRequest(coapExchange.advanced(), "Unable to decode payload on WRITE", e);
                return;
            }

        }
    }

    @Override
    public void handlePOST(CoapExchange exchange) {
        ServerIdentity identity = extractServerIdentity(exchange);
        String URI = exchange.getRequestOptions().getUriPathString();

        LwM2mPath path = new LwM2mPath(URI);

        // Manage Execute Request
        if (path.isResource()) {
            byte[] payload = exchange.getRequestPayload();
            ExecuteResponse response = nodeEnabler.execute(identity,
                    new ExecuteRequest(URI, payload != null ? new String(payload) : null));
            if (response.getCode().isError()) {
                exchange.respond(toCoapResponseCode(response.getCode()), response.getErrorMessage());
            } else {
                exchange.respond(toCoapResponseCode(response.getCode()));
            }
            return;
        }

        // handle content format for Write (Update) and Create request
        if (!exchange.getRequestOptions().hasContentFormat()) {
            handleInvalidRequest(exchange, "Content Format is mandatory");
            return;
        }

        ContentFormat contentFormat = ContentFormat.fromCode(exchange.getRequestOptions().getContentFormat());
        if (!decoder.isSupported(contentFormat)) {
            exchange.respond(ResponseCode.UNSUPPORTED_CONTENT_FORMAT);
            return;
        }
        LwM2mModel model = new StaticModel(nodeEnabler.getObjectModel());

        // Manage Update Instance
        if (path.isObjectInstance()) {
            try {
                LwM2mNode lwM2mNode = decoder.decode(exchange.getRequestPayload(), contentFormat, path, model);
                WriteResponse response = nodeEnabler.write(identity,
                        new WriteRequest(Mode.UPDATE, contentFormat, URI, lwM2mNode));
                if (response.getCode().isError()) {
                    exchange.respond(toCoapResponseCode(response.getCode()), response.getErrorMessage());
                } else {
                    exchange.respond(toCoapResponseCode(response.getCode()));
                }
            } catch (CodecException e) {
                handleInvalidRequest(exchange.advanced(), "Unable to decode payload on WRITE", e);
            }
            return;
        }

        // Manage Create Request
        try {
            // decode the payload as an instance
            LwM2mObjectInstance newInstance = decoder.decode(exchange.getRequestPayload(), contentFormat,
                    new LwM2mPath(path.getObjectId()), model, LwM2mObjectInstance.class);

            CreateRequest createRequest;
            if (newInstance.getId() != LwM2mObjectInstance.UNDEFINED) {
                createRequest = new CreateRequest(contentFormat, path.getObjectId(), newInstance);
            } else {
                // the instance Id was not part of the create request payload.
                // will be assigned by the client.
                createRequest = new CreateRequest(contentFormat, path.getObjectId(),
                        newInstance.getResources().values());
            }

            CreateResponse response = nodeEnabler.create(identity, createRequest);
            if (response.getCode() == org.eclipse.leshan.ResponseCode.CREATED) {
                exchange.setLocationPath(response.getLocation());
                exchange.respond(toCoapResponseCode(response.getCode()));
                return;
            } else {
                exchange.respond(toCoapResponseCode(response.getCode()), response.getErrorMessage());
                return;
            }
        } catch (CodecException e) {
            handleInvalidRequest(exchange.advanced(), "Unable to decode payload on CREATE", e);
            return;
        }
    }

    @Override
    public void handleDELETE(CoapExchange coapExchange) {
        // Manage Delete Request
        String URI = coapExchange.getRequestOptions().getUriPathString();
        ServerIdentity identity = extractServerIdentity(coapExchange);

        if (identity.isLwm2mBootstrapServer()) {
            BootstrapDeleteResponse response = nodeEnabler.delete(identity, new BootstrapDeleteRequest(URI));
            if (response.getCode().isError()) {
                coapExchange.respond(toCoapResponseCode(response.getCode()), response.getErrorMessage());
            } else {
                coapExchange.respond(toCoapResponseCode(response.getCode()));
            }
        } else {
            DeleteResponse response = nodeEnabler.delete(identity, new DeleteRequest(URI));
            if (response.getCode().isError()) {
                coapExchange.respond(toCoapResponseCode(response.getCode()), response.getErrorMessage());
            } else {
                coapExchange.respond(toCoapResponseCode(response.getCode()));
            }
        }
    }

    @Override
    public void sendNotify(String URI) {
        changed(new ResourceObserveFilter(URI));
    }

    /*
     * Override the default behavior so that requests to sub resources (typically /ObjectId/*) are handled by this
     * resource.
     */
    @Override
    public Resource getChild(String name) {
        return this;
    }
}
