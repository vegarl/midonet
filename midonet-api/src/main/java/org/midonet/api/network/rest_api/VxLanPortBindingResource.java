/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network.rest_api;

import java.util.List;
import java.util.UUID;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.network.VTEPBinding;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.vtep.VtepClusterClient;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.midonet.api.validation.MessageProperty.PORT_NOT_VXLAN_PORT;
import static org.midonet.api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.VTEP_BINDING_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;

public class VxLanPortBindingResource extends AbstractVtepResource {

    private static final Logger log = LoggerFactory.getLogger(
            VxLanPortBindingResource.class);

    /** ID of VXLAN port to get bindings for. */
    private UUID vxLanPortId;

    @Inject
    public VxLanPortBindingResource(
            RestApiConfig config, UriInfo uriInfo,
            SecurityContext context, Validator validator,
            DataClient dataClient, ResourceFactory factory,
            VtepClusterClient vtepClient, @Assisted UUID vxLanPortId) {
        super(config, uriInfo, context, validator,
              dataClient, factory, vtepClient);
        this.vxLanPortId = vxLanPortId;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_BINDING_JSON,
            VendorMediaType.APPLICATION_JSON})
    @Path("{portName}/{vlanId}")
    public VTEPBinding get(@PathParam("portName") String portName,
                           @PathParam("vlanId") short vlanId)
            throws SerializationException, StateAccessException {

        // Get the ID of the bridge in the specified binding.
        VxLanPort vxLanPort = getVxLanPort(vxLanPortId);
        IPv4Addr ipAddr = vxLanPort.getMgmtIpAddr();
        java.util.UUID boundBridgeId =
                vtepClient.getBoundBridgeId(ipAddr, portName, vlanId);

        // Make sure it matches the VXLAN port's bridge ID.
        if (!boundBridgeId.equals(vxLanPort.getDeviceId())) {
            throw new NotFoundHttpException(getMessage(
                    VTEP_BINDING_NOT_FOUND, ipAddr, vlanId, portName));
        }

        VTEPBinding b = new VTEPBinding(ipAddr.toString(), portName,
                                        vlanId, boundBridgeId);
        b.setBaseUri(getBaseUri());
        return b;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_BINDING_COLLECTION_JSON,
            MediaType.APPLICATION_JSON})
    public List<VTEPBinding> list() throws StateAccessException,
            SerializationException {

        VxLanPort vxLanPort = getVxLanPort(vxLanPortId);
        return listVtepBindings(vxLanPort.getMgmtIpAddr().toString(),
                                vxLanPort.getDeviceId());
    }

    /**
     * Gets a VxLanPort with the specified ID.
     *
     * @throws org.midonet.api.rest_api.NotFoundHttpException
     *         if no port with the specified ID exists.
     *
     * @throws org.midonet.api.rest_api.BadRequestHttpException
     *         if the port with the specified ID is not a VxLanPort.
     */
    private VxLanPort getVxLanPort(UUID vxLanPortId)
            throws SerializationException, StateAccessException {
        Port<?, ?> port = dataClient.portsGet(vxLanPortId);
        if (port == null) {
            throw new NotFoundHttpException(getMessage(
                    RESOURCE_NOT_FOUND, "port", vxLanPortId));
        }

        if (!(port instanceof VxLanPort)) {
            throw new BadRequestHttpException(getMessage(
                    PORT_NOT_VXLAN_PORT, vxLanPortId));
        }

        return (VxLanPort)port;
    }
}
