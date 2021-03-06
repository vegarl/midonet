/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.data.vtep.model

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class PhysicalLocatorTest extends FeatureSpec with Matchers {
    val plUuid = UUID.randomUUID()
    val plIpStr = "1.2.3.4"
    val plIp = IPv4Addr.fromString(plIpStr)

    feature("constructors") {
        scenario("default constructor") {
            val pl = PhysicalLocator(plUuid, plIp)
            pl.uuid shouldBe plUuid
            pl.dstIp shouldBe plIp
            pl.encapsulation shouldBe "vxlan_over_ipv4"
        }
        scenario("non-uuid constructor") {
            val pl = PhysicalLocator(plIp)
            pl.uuid shouldBe null
            pl.dstIp shouldBe plIp
        }
    }
    feature("physical locator tolerates null values") {
        scenario("null uuid") {
            val pl = PhysicalLocator(null, plIp)
            pl.uuid shouldBe null
            pl.dstIp shouldBe plIp
        }
        scenario("null ip") {
            val pl = new PhysicalLocator(plUuid, null)
            pl.uuid shouldBe plUuid
            pl.dstIp shouldBe null
        }
    }
    feature("operations") {
        scenario("equality does not depend on uuid") {
            val pl1 = PhysicalLocator(plUuid, plIp)
            val pl2 = PhysicalLocator(null, plIp)
            pl1.equals(pl2) shouldBe true
        }
        scenario("hashcode depends on uuid") {
            val pl1 = PhysicalLocator(UUID.randomUUID(), plIp)
            val pl2 = PhysicalLocator(UUID.randomUUID(), plIp)
            pl1.hashCode shouldNot be (pl2.hashCode)
        }
    }
}
