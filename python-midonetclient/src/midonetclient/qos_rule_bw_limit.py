# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2016 Midokura PTE LTD.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.


from midonetclient import resource_base
from midonetclient import vendor_media_type


class QOSRuleBWLimit(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_QOS_RULE_BW_LIMIT_JSON

    def __init__(self, uri, dto, auth):
        super(QOSRuleBWLimit, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_policy_id(self):
        return self.dto['policyId']

    def get_max_kbps(self):
        return self.dto['maxKbps']

    def get_max_burst_kb(self):
        return self.dto['maxBurstKb']

    def id(self, id):
        self.dto['id'] = id
        return self

    def policy_id(self, id):
        self.dto['policyId'] = id
        return self

    def max_kbps(self, kbps):
        self.dto['maxKbps'] = kbps
        return self

    def max_burst_kb(self, kbps):
        self.dto['maxBurstKb'] = kbps
        return self
