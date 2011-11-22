/*
 * @(#)BridgeZkManagerProxy        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Class to manage the bridge ZooKeeper data.
 *
 * @version 1.6 19 Sept 2011
 * @author Ryu Ishimoto
 */
public class BridgeZkManagerProxy extends ZkMgmtManager implements BridgeDao,
        OwnerQueryable {

    private BridgeZkManager zkManager = null;
    private final static Logger log = LoggerFactory
            .getLogger(BridgeZkManagerProxy.class);

    public BridgeZkManagerProxy(Directory zk, String basePath,
            String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new BridgeZkManager(zk, basePath);
    }

    public List<Op> prepareCreate(Bridge bridge) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // Create the root bridge path
        String bridgePath = mgmtPathManager.getBridgePath(bridge.getId());
        log.debug("Preparing to create: " + bridgePath);
        try {
            ops.add(Op.create(bridgePath, serialize(bridge.toMgmtConfig()),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Serialization error occurred while preparing bridge creation multi ops for UUID "
                            + bridge.getId(), e, BridgeMgmtConfig.class);
        }

        // Add under tenant.
        String tenantBridgePath = mgmtPathManager.getTenantBridgePath(
                bridge.getTenantId(), bridge.getId());
        log.debug("Preparing to create: " + tenantBridgePath);
        ops.add(Op.create(tenantBridgePath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        String tenantBridgeNamePath = mgmtPathManager.getTenantBridgeNamePath(
                bridge.getTenantId(), bridge.getName());
        log.debug("Preparing to create:" + tenantBridgeNamePath);
        try {
            ops.add(Op.create(tenantBridgeNamePath,
                    serialize(bridge.toNameMgmtConfig()), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize BridgeNameMgmtConfig", e,
                    BridgeNameMgmtConfig.class);
        }

        // Create Midolman data
        ops.addAll(zkManager.prepareBridgeCreate(bridge.getId(),
                new BridgeConfig()));

        return ops;
    }

    public List<Op> prepareUpdate(Bridge bridge) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // Get the bridge.
        Bridge b = get(bridge.getId());

        // Remove the name of this bridge
        String bridgeOldNamePath = mgmtPathManager.getTenantBridgeNamePath(
                b.getTenantId(), b.getName());
        log.debug("Preparing to delete: " + bridgeOldNamePath);
        ops.add(Op.delete(bridgeOldNamePath, -1));
        b.setName(bridge.getName());

        // Add the new name.
        String bridgeNamePath = mgmtPathManager.getTenantBridgeNamePath(
                b.getTenantId(), b.getName());
        try {
            ops.add(Op.create(bridgeNamePath, serialize(b.toNameMgmtConfig()),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize BridgeNameMgmtConfig", e,
                    BridgeNameMgmtConfig.class);
        }

        // Update bridge
        String bridgePath = mgmtPathManager.getBridgePath(b.getId());
        log.debug("Preparing to update: " + bridgePath);
        try {
            update(bridgePath, serialize(b.toMgmtConfig()));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize bridge mgmt " + b.getId()
                            + " to BridgeMgmtConfig", e, BridgeMgmtConfig.class);
        }
        return ops;
    }

    public List<Op> prepareDelete(UUID id) throws StateAccessException {
        return prepareDelete(get(id));
    }

    public List<Op> prepareDelete(Bridge bridge) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // Delete the Midolman side.
        ZkNodeEntry<UUID, BridgeConfig> bridgeNode = zkManager.get(bridge
                .getId());
        ops.addAll(zkManager.prepareBridgeDelete(bridgeNode));

        // Delete the tenant bridge entry
        String tenantBridgeNamePath = mgmtPathManager.getTenantBridgeNamePath(
                bridge.getTenantId(), bridge.getName());
        log.debug("Preparing to delete:" + tenantBridgeNamePath);
        ops.add(Op.delete(tenantBridgeNamePath, -1));

        String tenantBridgePath = mgmtPathManager.getTenantBridgePath(
                bridge.getTenantId(), bridge.getId());
        log.debug("Preparing to delete: " + tenantBridgePath);
        ops.add(Op.delete(tenantBridgePath, -1));

        // Delete the root bridge path.
        String bridgePath = mgmtPathManager.getBridgePath(bridge.getId());
        log.debug("Preparing to delete: " + bridgePath);
        ops.add(Op.delete(bridgePath, -1));

        // Remove all the ports in mgmt directory but don't cascade here.
        PortZkManagerProxy portMgr = new PortZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        ops.addAll(portMgr.prepareBridgeDelete(bridge.getId()));

        return ops;
    }

    @Override
    public UUID create(Bridge bridge) throws StateAccessException {
        if (null == bridge.getId()) {
            bridge.setId(UUID.randomUUID());
        }
        multi(prepareCreate(bridge));
        return bridge.getId();
    }

    @Override
    public Bridge get(UUID id) throws StateAccessException {
        byte[] data = get(mgmtPathManager.getBridgePath(id));
        BridgeMgmtConfig config = null;
        try {
            config = deserialize(data, BridgeMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Serialization error occurred while getting the bridge with UUID "
                            + id, e, BridgeMgmtConfig.class);
        }
        return Bridge.createBridge(id, config);
    }

    @Override
    public List<Bridge> list(String tenantId) throws StateAccessException {
        List<Bridge> result = new ArrayList<Bridge>();
        String path = mgmtPathManager.getTenantBridgesPath(tenantId);
        Set<String> ids = getChildren(path);
        for (String id : ids) {
            // For now, get each one.
            result.add(get(UUID.fromString(id)));
        }
        return result;
    }

    @Override
    public void update(Bridge bridge) throws StateAccessException {
        // Update any version for now.
        multi(prepareUpdate(bridge));
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        multi(prepareDelete(id));
    }

    @Override
    public String getOwner(UUID id) throws StateAccessException {
        return get(id).getTenantId();
    }
}
