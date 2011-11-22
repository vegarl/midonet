/*
 * @(#)Authorizer        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.auth;

import java.security.Principal;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

public class Authorizer implements SecurityContext {

    private Principal principal = null;
    private TenantUser tenantUser = null;

    @Context
    UriInfo uriInfo;

    public Authorizer(final TenantUser tenantUser) {
        if (tenantUser != null) {
            principal = new Principal() {
                @Override
                public String getName() {
                    return tenantUser.getTenantId();
                }
            };
        }
        this.tenantUser = tenantUser;
    }

    @Override
    public Principal getUserPrincipal() {
        return principal;
    }

    @Override
    public boolean isSecure() {
        // return "https".equals(uriInfo.getRequestUri().getScheme());
        return true;
    }

    @Override
    public boolean isUserInRole(String role) {
        if (tenantUser == null) {
            return false;
        }
        return tenantUser.isRole(role);
    }

    public void setTenantUser(TenantUser tenantUser) {
        this.tenantUser = tenantUser;
    }

    public TenantUser getTenantUser() {
        return tenantUser;
    }

    @Override
    public String getAuthenticationScheme() {
        return "Keystone";
    }

}
