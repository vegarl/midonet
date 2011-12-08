/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.openflow;

import java.util.List;
import java.util.UUID;

import junit.framework.Assert;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionType;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;

public class FlowStats {
    OFMatch match;
    OpenFlowStats controller;
    OFFlowStatisticsReply stat;

    public FlowStats(OFMatch match, OpenFlowStats controller,
            OFFlowStatisticsReply stat) {
        this.match = match;
        this.controller = controller;
        this.stat = stat;
    }

    public final OFMatch getMatch() {
        return match;
    }

    /**
     * Return the FlowStats from the list whose match field is equal to the one
     * in 'this'. Assert. This is a convenience method that can be used like
     * this:
     * 
     * <pre>
     * {
     *     OFMatch match; // initialize appropriately
     *     List&lt;FlowStats&gt; stats = controller.getFlowStats(match);
     *     FlowStat fStat = stats.get(0);
     *     fStat.expectCount(4).expectOutput(1);
     *     stats = controller.getFlowStats(match); // refresh stats from switch
     *     fStat.findSameInList(stats).expectCount(5).expectOutput(1);
     * }
     * </pre>
     * 
     * @return The equivalent FlowStat from the list or 'this' if none is found
     *         in the list. Assert.fail with a message if no equivalent is found
     *         in the list.
     */
    public FlowStats findSameInList(List<FlowStats> stats) {
        for (FlowStats fStat : stats) {
            if (match.equals(fStat.match))
                return fStat;
        }
        Assert.fail("Did not find a FlowStats with the same match.");
        return this;
    }

    public FlowStats expectCount(int i) {
        Assert.assertEquals(i, stat.getPacketCount());
        return this;
    }

    public FlowStats expectOutputAction(UUID id) {
        // TODO: get the port number corresponding to this id.
        int portNum = 0;
        List<OFAction> actions = stat.getActions();
        Assert.assertTrue(actions.size() > 0);
        OFAction act = actions.get(actions.size() - 1);
        Assert.assertEquals(OFActionType.OUTPUT, act.getType());
        OFActionOutput outAct = OFActionOutput.class.cast(act);
        Assert.assertEquals(portNum, outAct.getPort());
        return this;
    }
}
